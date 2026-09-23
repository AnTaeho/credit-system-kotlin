/**
 * k6 공통 모듈 — 네 시나리오가 공유하는 계약.
 *
 * 여기 있는 것은 편의 함수가 아니라 **요구서가 정한 규칙의 코드화**다. 스크립트마다 다시 쓰면
 * 한쪽만 고쳐져 결과가 조용히 갈라진다. 특히 셋:
 *
 *   (1) 인증을 우회하지 않는다 — 모든 요청에 `X-Dev-User` 를 붙인다(요구서 1-4).
 *       목표치(p50 < 20ms, p99 < 100ms)는 개발 로그인 트랜잭션을 **포함한** 값이다.
 *   (2) 409 를 두 종류로 가른다(요구서 1-5, 4-2).
 *         DUPLICATE_IN_PROGRESS → 멱등이 동작한 것이므로 성공으로 센다.
 *         INSUFFICIENT_BALANCE  → 부하 결과가 아니라 **시드 실패**다. 오류율에 섞지 않고
 *                                 별도 카운터로 세고, thresholds 가 0 건을 강제해 런을 무효로 만든다.
 *   (3) 응답을 못 받으면 같은 idemKey 로 재시도한다(요구서 PERF-06).
 *       5xx 뿐 아니라 **status 0**(연결 끊김·타임아웃)도 재시도 대상이다. 3,000 RPS 에서 실제로
 *       보게 되는 것은 500 이 아니라 status 0 이고, "답을 못 받았는데 돈은 나갔나"가 곧 INV-03 이다.
 *
 * 200 `{duplicate:true}` 와 409 DUPLICATE_IN_PROGRESS 의 차이:
 *   HoldService.resolveDuplicateRequest 는 idem 행에 jobId 가 이미 붙어 있으면 200 duplicate=true 를
 *   돌려주고, 아직 안 붙어 있으면(첫 요청이 같은 트랜잭션 안에 있는 찰나) 409 를 던진다.
 *   둘 다 "멱등이 동작했다"이므로 성공 쪽이다.
 */
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ── 설정(전부 환경변수로 덮어쓸 수 있다) ────────────────────────────────────
function num(v, fallback) {
    const n = Number(v);
    return Number.isFinite(n) && v !== undefined && v !== '' ? n : fallback;
}

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const MGMT_URL = __ENV.MGMT_URL || 'http://localhost:8081';

/** 런을 구분하는 접두사. idemKey 에 들어가므로 런이 바뀌면 반드시 바뀌어야 한다. */
export const RUN_ID = __ENV.RUN_ID || `r${Date.now().toString(36)}`;

export const WRITE_RATE = num(__ENV.WRITE_RATE, 500);
export const READ_RATE = num(__ENV.READ_RATE, WRITE_RATE * 3);
export const DURATION = __ENV.DURATION || '10m';

/** 쓰기를 나눠 받을 계정들. `app.auth.allowed-emails` 안에 있어야 한다(밖이면 401). */
export const USERS = (__ENV.USERS || 'dev@local.test')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

/** 핫 계정(02 전용). 기본은 USERS 의 첫 번째. */
export const HOT_USER = __ENV.HOT_USER || USERS[0];

export const MAX_RETRIES = num(__ENV.MAX_RETRIES, 3);
export const RETRY_SLEEP_SECONDS = num(__ENV.RETRY_SLEEP_SECONDS, 0.05);

/** app.generation.cost. 시드 선행 검사의 산술에 쓴다. */
export const GENERATION_COST = num(__ENV.GENERATION_COST, 100);

/** 1 이면 setup() 의 잔액 선행 검사를 건너뛴다. **문법 확인 전용**이고 실측에는 쓰지 마라. */
export const SKIP_PRECHECK = __ENV.SKIP_PRECHECK === '1';

/** VU 풀 크기. 필요한 VU 수 ≈ 초당 요청 × 응답 지연(초)이라, 느려지면 더 필요하다. */
export const PRE_ALLOCATED_FACTOR = num(__ENV.PRE_ALLOCATED_FACTOR, 0.3);
export const MAX_VUS_FACTOR = num(__ENV.MAX_VUS_FACTOR, 3);

const PROMPT = __ENV.PROMPT || 'k6 load prompt';

// 200 과 409 는 "정상 응답"이다. 409 를 실패로 세면 http_req_failed 가 멱등 성공까지 오류로 만든다.
// 이 콜백을 거치면 http_req_failed 는 "200·409 가 아닌 것"만 센다.
http.setResponseCallback(http.expectedStatuses(200, 409));

// ── 커스텀 메트릭 ───────────────────────────────────────────────────────────
// 이름을 여기 한 곳에서만 만든다. thresholds 가 문자열로 이 이름을 참조하므로
// 스크립트마다 새로 만들면 threshold 가 조용히 다른 메트릭을 가리킨다.
export const holdOk = new Counter('hold_ok');                       // 200, duplicate=false
export const holdDuplicateOk = new Counter('hold_duplicate_ok');    // 200, duplicate=true
export const holdConflictInProgress = new Counter('hold_conflict_in_progress'); // 409 DUPLICATE_IN_PROGRESS
export const insufficientBalance = new Counter('insufficient_balance');         // 409 INSUFFICIENT_BALANCE = 시드 실패
export const unauthorized = new Counter('unauthorized');            // 401 = 허용 목록/개발 로그인 설정 실패
export const invalidRequest = new Counter('invalid_request');       // 400 = 스크립트 버그
export const serverError = new Counter('server_error');             // 5xx
export const transportError = new Counter('transport_error');       // status 0 (연결 끊김·타임아웃)
export const unexpectedStatus = new Counter('unexpected_status');

export const retries = new Counter('retries');                      // 같은 idemKey 로 다시 쏜 횟수
export const retryOkNew = new Counter('retry_ok_new');              // 재시도가 200 duplicate=false → 첫 시도는 커밋되지 않았다
export const retryOkDuplicate = new Counter('retry_ok_duplicate');  // 재시도가 200 duplicate=true → 첫 시도가 이미 커밋됐다(INV-03 의 증거)
export const retryConflictInProgress = new Counter('retry_conflict_in_progress');
export const retryExhausted = new Counter('retry_exhausted');       // 재시도를 다 쓰고도 못 받았다

/** 새 UUID 로 보냈는데 duplicate=true 가 왔다 = 멱등키 충돌. 0 이어야 한다. */
export const unexpectedDuplicate = new Counter('unexpected_duplicate');

export const readOk = new Counter('read_ok');

// 02 가 쓰는 두 분포. thresholds 는 서브메트릭으로도 걸지만, 3배 비교는 cross-metric 이라
// k6 threshold 로 표현할 수 없어서 handleSummary 가 이 둘의 p99 를 직접 나눈다.
export const holdDurationHot = new Trend('hold_duration_hot', true);
export const holdDurationUniform = new Trend('hold_duration_uniform', true);

// ── 요청 ────────────────────────────────────────────────────────────────────
export function authHeaders(email) {
    return { 'X-Dev-User': email, 'Content-Type': 'application/json' };
}

/** idemKey 는 100자 이하여야 한다(validateIdemKey). 런 접두사 + UUID 로 60자쯤 된다. */
export function newIdemKey(prefix) {
    return `${RUN_ID}-${prefix}-${crypto.randomUUID()}`.slice(0, 100);
}

/** USERS 를 VU·반복 번호로 고르게 나눈다. Math.random 을 쓰지 않는 이유는 재현 가능성이다. */
export function pickUser() {
    return USERS[exec.scenario.iterationInTest % USERS.length];
}

function bodyCode(res) {
    try {
        const parsed = res.json();
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (e) {
        return null;
    }
}

/**
 * 응답 하나를 한 단어로 분류한다. 이 함수가 요구서 규칙 (2) 의 전부다.
 * 반환값: ok | duplicate_ok | duplicate_in_progress | insufficient_balance |
 *         unauthorized | invalid_request | server_error | transport_error | unexpected
 */
export function classifyHold(res) {
    if (res.status === 0) return 'transport_error';
    if (res.status >= 500) return 'server_error';
    if (res.status === 200) {
        const body = bodyCode(res);
        return body && body.duplicate === true ? 'duplicate_ok' : 'ok';
    }
    if (res.status === 401 || res.status === 403) return 'unauthorized';
    if (res.status === 400) return 'invalid_request';
    if (res.status === 409) {
        const body = bodyCode(res);
        const code = body ? body.code : null;
        if (code === 'INSUFFICIENT_BALANCE') return 'insufficient_balance';
        if (code === 'DUPLICATE_IN_PROGRESS') return 'duplicate_in_progress';
        return 'unexpected';
    }
    return 'unexpected';
}

function countOutcome(outcome) {
    switch (outcome) {
        case 'ok': holdOk.add(1); break;
        case 'duplicate_ok': holdDuplicateOk.add(1); break;
        case 'duplicate_in_progress': holdConflictInProgress.add(1); break;
        case 'insufficient_balance': insufficientBalance.add(1); break;
        case 'unauthorized': unauthorized.add(1); break;
        case 'invalid_request': invalidRequest.add(1); break;
        case 'server_error': serverError.add(1); break;
        case 'transport_error': transportError.add(1); break;
        default: unexpectedStatus.add(1); break;
    }
}

function isRetryable(outcome) {
    return outcome === 'server_error' || outcome === 'transport_error';
}

/**
 * 접수 1건. 5xx·status 0 이면 **같은 idemKey 로** 재시도한다.
 *
 * @param email    X-Dev-User 로 쓸 이메일
 * @param tags     이 요청에 붙일 태그. `name` 은 여기서 'hold' 로 고정한다.
 * @returns {{outcome: string, attempts: number, durationMs: number, res: object, idemKey: string}}
 */
export function submitHold(email, tags) {
    const idemKey = newIdemKey('h');
    const payload = JSON.stringify({ idemKey: idemKey, prompt: PROMPT });
    const params = { headers: authHeaders(email), tags: Object.assign({ name: 'hold' }, tags || {}) };

    let attempt = 0;
    for (;;) {
        const res = http.post(`${BASE_URL}/api/jobs`, payload, params);
        const outcome = classifyHold(res);
        countOutcome(outcome);

        const dist = params.tags.dist;
        if (dist === 'hot') holdDurationHot.add(res.timings.duration);
        else if (dist === 'uniform') holdDurationUniform.add(res.timings.duration);

        // 첫 시도가 duplicate=true 로 오면 UUID 가 충돌한 것이다. 있어서는 안 된다.
        if (attempt === 0 && outcome === 'duplicate_ok') unexpectedDuplicate.add(1);

        if (isRetryable(outcome) && attempt < MAX_RETRIES) {
            attempt += 1;
            retries.add(1);
            sleep(RETRY_SLEEP_SECONDS);
            continue;
        }

        if (attempt > 0) {
            // 재시도의 결말이 곧 INV-03 의 관측이다.
            if (outcome === 'ok') retryOkNew.add(1);
            else if (outcome === 'duplicate_ok') retryOkDuplicate.add(1);
            else if (outcome === 'duplicate_in_progress') retryConflictInProgress.add(1);
            else if (isRetryable(outcome)) retryExhausted.add(1);
        }

        return { outcome: outcome, attempts: attempt + 1, durationMs: res.timings.duration, res: res, idemKey: idemKey };
    }
}

function readOnce(url, email, name, tags) {
    const res = http.get(url, { headers: authHeaders(email), tags: Object.assign({ name: name }, tags || {}) });
    if (res.status === 200) readOk.add(1);
    else if (res.status === 401 || res.status === 403) unauthorized.add(1);
    else if (res.status === 400) invalidRequest.add(1);
    else if (res.status === 0) transportError.add(1);
    else if (res.status >= 500) serverError.add(1);
    else unexpectedStatus.add(1);
    return res;
}

/** PERF-02 의 조회 둘. size 를 안 주면 CursorRequest.DEFAULT_SIZE(20)이다 — 400 이 아니다. */
export function readBalance(email, tags) {
    return readOnce(`${BASE_URL}/api/users/me/balance`, email, 'balance', tags);
}

export function readLedger(email, tags) {
    return readOnce(`${BASE_URL}/api/ledger`, email, 'ledger', tags);
}

/** 조회 부하 1회. 잔액과 원장을 번갈아 친다. */
export function doRead(email, tags) {
    if (exec.scenario.iterationInTest % 2 === 0) readBalance(email, tags);
    else readLedger(email, tags);
}

// ── 시나리오·threshold 조립 ─────────────────────────────────────────────────
export function parseDurationSeconds(text) {
    const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(text).trim());
    if (!m) return 0;
    const value = Number(m[1]);
    const unit = { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2]];
    return value * unit;
}

export function arrivalRate(rate, duration, execName, extra) {
    return Object.assign(
        {
            executor: 'constant-arrival-rate',
            rate: rate,
            timeUnit: '1s',
            duration: duration,
            preAllocatedVUs: Math.max(20, Math.ceil(rate * PRE_ALLOCATED_FACTOR)),
            maxVUs: Math.max(100, Math.ceil(rate * MAX_VUS_FACTOR)),
            exec: execName,
        },
        extra || {}
    );
}

/**
 * 모든 런에 공통으로 거는 "이 런은 유효한가" 판정.
 *
 * 성능 수치가 아니라 **결과를 무효로 만드는 조건**이다. 401 이 한 건이라도 나면 허용 목록
 * 설정이 안 먹은 것이고, INSUFFICIENT_BALANCE 가 나오면 시드가 모자란 것이다. 둘 다
 * "느렸다"가 아니라 "재지 못했다"이므로 사람이 눈으로 거르게 두지 않는다.
 */
export function validityThresholds() {
    return {
        unauthorized: ['count==0'],
        invalid_request: ['count==0'],
        insufficient_balance: ['count==0'],
        unexpected_status: ['count==0'],
        unexpected_duplicate: ['count==0'],
    };
}

/** PERF-01·02 의 목표치. 태그별 서브메트릭에 그대로 박는다. */
export function perfThresholds() {
    return {
        'http_req_duration{name:hold}': ['p(50)<20', 'p(99)<100'],
        'http_req_duration{name:balance}': ['p(99)<30'],
        'http_req_duration{name:ledger}': ['p(99)<30'],
    };
}

/** 기본 요약은 p(50) 을 안 찍는다. PERF-01 이 p50 목표라서 반드시 켜야 한다. */
export const SUMMARY_TREND_STATS = ['min', 'avg', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'];

// ── setup: 시드 선행 검사 ───────────────────────────────────────────────────
/**
 * 부하를 걸기 전에 계정들의 잔액이 충분한지 본다. 모자라면 **시작하지 않는다.**
 * 409 INSUFFICIENT_BALANCE 가 쏟아진 뒤에 알아차리면 그 10분은 버리는 시간이다.
 */
export function precheck(writeRate, duration) {
    const seconds = parseDurationSeconds(duration);
    return precheckWrites(
        Math.ceil(writeRate * seconds),
        `쓰기 ${writeRate} RPS × ${seconds}초`
    );
}

/**
 * 총 쓰기 건수를 직접 아는 경우(예: 램프가 섞인 04)의 선행 검사. **쓰기가 계정에 고르게
 * 나뉜다고 가정한다.** 02 처럼 한 계정이 5% 를 혼자 받는 시나리오는 이 함수를 쓰지 말고
 * [precheckNeeds] 에 계정별 필요량을 직접 넘겨라 — 균등으로 나누면 핫 계정이 모자란 것을
 * 놓치고, 부하 중반에 409 INSUFFICIENT_BALANCE 가 쏟아진다.
 */
export function precheckWrites(totalWrites, label) {
    const perUser = Math.ceil(totalWrites / USERS.length);
    const needs = {};
    for (const email of USERS) needs[email] = perUser * GENERATION_COST;
    return precheckNeeds(needs, `${label} ÷ 계정 ${USERS.length}개(균등)`);
}

/**
 * 계정별 필요 크레딧을 직접 받아 검사한다.
 *
 * @param needs {Object} 이메일 → 필요 크레딧
 */
export function precheckNeeds(needs, label) {
    if (SKIP_PRECHECK) {
        console.warn('SKIP_PRECHECK=1 — 잔액 선행 검사를 건너뛴다. 실측 런에서는 쓰지 마라.');
        return { checked: false };
    }

    const balances = {};
    for (const email of Object.keys(needs)) {
        const need = Math.ceil(needs[email]);
        const res = http.get(`${BASE_URL}/api/users/me/balance`, { headers: authHeaders(email) });
        if (res.status !== 200) {
            exec.test.abort(
                `선행 검사 실패: ${email} 의 잔액을 읽지 못했다(HTTP ${res.status}). ` +
                    '개발 로그인(app.auth.dev-login.enabled)과 허용 목록(APP_AUTH_ALLOWEDEMAILS)을 확인해라.'
            );
        }
        const balance = res.json('balance');
        balances[email] = balance;
        if (balance < need) {
            exec.test.abort(
                `시드 부족: ${email} balance=${balance}, 필요=${need} (${label}). ` +
                    'load/seed/grant-credits.sh 로 먼저 채워라.'
            );
        }
    }

    console.log(`선행 검사 통과(${label}): 필요=${JSON.stringify(needs)} 잔액=${JSON.stringify(balances)}`);
    return { checked: true, needs: needs, balances: balances };
}

// ── 요약 ────────────────────────────────────────────────────────────────────
// k6 v1.6 에는 textSummary 를 주는 내장 모듈이 없고(`k6/summary` 는 없는 의존성이다),
// jslib 은 실행 시점에 네트워크를 탄다. 그래서 필요한 것만 직접 찍는다.
// **기본 요약 대신** 이것이 나온다.
function fmt(v) {
    if (v === undefined || v === null || Number.isNaN(v)) return '-';
    return Math.round(v * 100) / 100;
}

function thresholdLines(data) {
    const lines = [];
    for (const name of Object.keys(data.metrics)) {
        const t = data.metrics[name].thresholds;
        if (!t) continue;
        for (const expr of Object.keys(t)) {
            const entry = t[expr];
            const ok = typeof entry === 'boolean' ? entry : entry.ok;
            lines.push(`  ${ok ? 'PASS' : 'FAIL'}  ${name} ${expr}`);
        }
    }
    return lines;
}

// Trend 의 요약 값에는 count 가 없다. 샘플이 하나도 없으면 메트릭 자체가 data.metrics 에 없거나
// avg 가 undefined 다 — 그것으로 "안 돌았다"를 가른다.
function trendLine(data, name) {
    const m = data.metrics[name];
    if (!m || !m.values || m.values.avg === undefined) return null;
    const v = m.values;
    return `  ${name.padEnd(46)} p50=${fmt(v['p(50)'] !== undefined ? v['p(50)'] : v.med)} p95=${fmt(v['p(95)'])} p99=${fmt(v['p(99)'])} max=${fmt(v.max)}`;
}

// 0 건도 반드시 찍는다. 줄이 없는 것과 0 인 것은 다르다 — 특히 insufficient_balance 는
// "안 나왔다"를 눈으로 확인해야 하는 값이다. k6 는 샘플이 없는 커스텀 메트릭을 요약에서 뺀다.
function counterLine(data, name) {
    const m = data.metrics[name];
    const count = m && m.values && m.values.count !== undefined ? m.values.count : 0;
    return `  ${name.padEnd(30)} ${count}`;
}

export function p99Of(data, name) {
    const m = data.metrics[name];
    if (!m || !m.values || m.values.avg === undefined) return null;
    return m.values['p(99)'];
}

/**
 * 공통 요약. `extraLines` 로 시나리오별 판정 한 줄을 덧붙인다.
 */
export function summaryReport(data, title, extraLines) {
    const out = [];
    out.push('');
    out.push(`── ${title} ──`);
    out.push('');
    out.push('[thresholds]');
    out.push(...thresholdLines(data));
    out.push('');
    out.push('[지연 — 단위 ms]');
    for (const name of [
        'http_req_duration{name:hold}',
        'http_req_duration{name:hold,dist:hot}',
        'http_req_duration{name:hold,dist:uniform}',
        'http_req_duration{name:hold,phase:spike}',
        'http_req_duration{name:hold,phase:recovery}',
        'http_req_duration{name:hold,phase:recovered}',
        'http_req_duration{name:balance}',
        'http_req_duration{name:ledger}',
        'hold_duration_hot',
        'hold_duration_uniform',
        'http_req_duration',
        'iteration_duration',
    ]) {
        const line = trendLine(data, name);
        if (line) out.push(line);
    }
    out.push('');
    out.push('[응답 분류]');
    for (const name of [
        'hold_ok',
        'hold_duplicate_ok',
        'hold_conflict_in_progress',
        'read_ok',
        'insufficient_balance',
        'unauthorized',
        'invalid_request',
        'unexpected_status',
        'unexpected_duplicate',
        'server_error',
        'transport_error',
    ]) {
        out.push(counterLine(data, name));
    }
    out.push('');
    out.push('[재시도 — PERF-06 / INV-03]');
    for (const name of ['retries', 'retry_ok_new', 'retry_ok_duplicate', 'retry_conflict_in_progress', 'retry_exhausted']) {
        out.push(counterLine(data, name));
    }
    out.push('');
    out.push('[부하 발생기]');
    for (const name of ['iterations', 'dropped_iterations', 'http_reqs']) {
        const m = data.metrics[name];
        const v = m && m.values ? m.values : { count: 0 };
        out.push(`  ${name.padEnd(30)} ${v.count || 0}${v.rate ? ` (${fmt(v.rate)}/s)` : ''}`);
    }
    const vus = data.metrics['vus_max'];
    if (vus && vus.values) out.push(`  ${'vus_max'.padEnd(30)} ${vus.values.max}`);
    out.push('  ※ dropped_iterations 가 0 이 아니면 k6 가 목표 RPS 를 못 낸 것이다 — 시스템의 한계가 아니다.');
    if (extraLines && extraLines.length > 0) {
        out.push('');
        out.push(...extraLines);
    }
    out.push('');
    return out.join('\n');
}
