/**
 * 06 — 세션 인증으로 재측정. 01(개발 로그인 헤더)과 짝이다.
 *
 * ── 왜 이 스크립트가 필요한가
 * 01~04 는 `X-Dev-User` 헤더로 인증했다. 그 경로는 **요청마다** `UserAccountProvisioner
 * .provisionDevUser` 가 돌아 `findByEmail` 트랜잭션을 하나 더 만든다(세션에 저장하지 않으므로).
 * 즉 접수 1건 = DB 트랜잭션 2개 · 커밋 2회다.
 *
 * **운영은 그렇지 않다.** 구글 OIDC 로 한 번 로그인하면 세션 쿠키가 인증을 들고 있고,
 * 이후 요청은 DB 를 치지 않는다. 그러므로 01 의 "150 RPS 상한"은 운영에 없는 비용을 포함한
 * 값이고, 과소평가일 가능성이 크다.
 *
 * 이 스크립트는 그 차이만 걷어낸다. 부하 모델·혼합비·임계값은 01 과 같다.
 *
 * ── 로그인 절차 (브라우저가 하는 것과 같다)
 *   1. GET  /login      → 페이지의 `_csrf` 메타에서 토큰을 얻는다
 *   2. POST /dev-login  → 세션 쿠키 발급. 세션 id 가 바뀌고 CSRF 토큰도 새로 난다
 *   3. GET  /           → 새 CSRF 토큰과 헤더 이름을 얻는다
 *   4. 이후 POST /api/jobs 는 쿠키 + CSRF 헤더로 보낸다
 *
 * `/dev-login` 은 개발 로그인이 켜져 있을 때만 존재한다. 구글 OIDC 를 k6 로 돌 수는 없으므로
 * **세션이라는 성질만 같은 대체 경로**를 쓴다 — 요청당 DB 조회가 없다는 점이 같다.
 * 로그인 자체의 비용(요청 3번)은 VU 당 한 번뿐이라 측정 구간에 거의 섞이지 않는다.
 *
 * ── 사용법
 *   USERS='load01@local.test,...' WRITE_RATE=150 DURATION=3m k6 run load/k6/06-session-auth.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import {
    BASE_URL, DURATION, READ_RATE, WRITE_RATE, SUMMARY_TREND_STATS, GENERATION_COST,
    arrivalRate, newIdemKey, pickUser, perfThresholds, summaryReport, validityThresholds,
    holdOk, holdDuplicateOk, holdConflictInProgress, insufficientBalance,
    unauthorized, invalidRequest, unexpectedStatus,
} from './lib/common.js';

export const loginOk = new Counter('session_login_ok');
export const loginFail = new Counter('session_login_fail');
export const csrfMissing = new Counter('session_csrf_missing');
export const serverError = new Counter('server_error');
export const readOk = new Counter('read_ok');

// 측정 시작 시각. VU 마다 로그인 3요청(그중 둘은 Thymeleaf 렌더)이 몰리는 초반을 갈라내야
// "세션 인증의 정상 상태 비용"을 잰다. 초반을 섞으면 로그인 폭풍을 인증 비용으로 오해한다.
const WARMUP_SECONDS = Number(__ENV.WARMUP_SECONDS || 45);

export const options = {
    discardResponseBodies: false,
    // k6 는 기본적으로 **반복마다 쿠키를 버린다.** 그러면 두 번째 요청부터 세션이 없어
    // 401 이 쏟아지고, 측정하려는 것(세션 인증의 비용)이 아니라 로그인 비용을 재게 된다.
    // VU 하나가 한 번 로그인해 계속 쓰는 것이 브라우저의 모양이고, 이 옵션이 그것을 만든다.
    noCookiesReset: true,
    summaryTrendStats: SUMMARY_TREND_STATS,
    scenarios: {
        hold: arrivalRate(WRITE_RATE, DURATION, 'holdScenario'),
        read: arrivalRate(READ_RATE, DURATION, 'readScenario'),
    },
    thresholds: Object.assign({}, validityThresholds(), {
        // 판정은 정상 상태 구간만 본다. 워밍업은 관찰만 한다.
        'http_req_duration{name:hold,phase:steady}': ['p(50)<20', 'p(99)<100'],
        'http_req_duration{name:balance,phase:steady}': ['p(99)<30'],
        'http_req_duration{name:ledger,phase:steady}': ['p(99)<30'],
        http_req_failed: ['rate<0.01'],
        session_login_fail: ['count==0'],
        session_csrf_missing: ['count==0'],
    }),
};

export function setup() {
    return { t0: Date.now() };
}

/** 워밍업(로그인 폭풍) 구간과 정상 상태를 가른다. */
function phase(data) {
    return (Date.now() - data.t0) / 1000 >= WARMUP_SECONDS ? 'steady' : 'warmup';
}

// VU 하나가 한 번만 로그인한다. 로그인 요청은 name 태그를 달지 않아 PERF 집계에서 빠진다.
let session = null;

function metaContent(html, name) {
    const m = html.match(new RegExp(`<meta name="${name}" content="([^"]*)"`));
    return m ? m[1] : null;
}

function login(email) {
    const loginPage = http.get(`${BASE_URL}/login`, { tags: { name: 'login-page' } });
    const formToken = metaContent(loginPage.body || '', '_csrf');
    if (!formToken) {
        csrfMissing.add(1);
        return null;
    }

    const res = http.post(`${BASE_URL}/dev-login`, { email: email, _csrf: formToken },
        { tags: { name: 'login-post' }, redirects: 0 });
    // 성공하면 "/" 로 리다이렉트한다. 실패는 "/login?error" 다.
    const location = res.headers['Location'] || '';
    if (res.status !== 302 || location.indexOf('error') >= 0) {
        loginFail.add(1);
        return null;
    }

    // 로그인이 세션 id 와 CSRF 토큰을 새로 만든다. 새 토큰을 다시 읽어야 한다.
    const home = http.get(`${BASE_URL}/`, { tags: { name: 'login-home' } });
    const token = metaContent(home.body || '', '_csrf');
    const header = metaContent(home.body || '', '_csrf_header');
    if (!token || !header) {
        csrfMissing.add(1);
        return null;
    }

    loginOk.add(1);
    return { email: email, token: token, header: header };
}

function ensureSession() {
    if (session === null) session = login(pickUser());
    return session;
}

function apiHeaders(s, json) {
    const h = {};
    h[s.header] = s.token;                       // CSRF — 쿠키 인증이므로 면제 대상이 아니다
    if (json) h['Content-Type'] = 'application/json';
    return h;                                     // 세션 쿠키는 k6 의 VU 별 쿠키 자에서 자동으로 붙는다
}

export function holdScenario(data) {
    const s = ensureSession();
    if (!s) return;

    const payload = JSON.stringify({ idemKey: newIdemKey('s'), prompt: 'k6 session load' });
    const res = http.post(`${BASE_URL}/api/jobs`, payload,
        { headers: apiHeaders(s, true), tags: { name: 'hold', run: 'session', phase: phase(data) } });

    if (res.status === 200) {
        const dup = (res.json() || {}).duplicate;
        if (dup) holdDuplicateOk.add(1); else holdOk.add(1);
    } else if (res.status === 409) {
        const code = ((res.json() || {}).code) || '';
        if (code === 'DUPLICATE_IN_PROGRESS') holdConflictInProgress.add(1);
        else if (code === 'INSUFFICIENT_BALANCE') insufficientBalance.add(1);
        else unexpectedStatus.add(1);
    } else if (res.status === 401 || res.status === 403) {
        unauthorized.add(1);                      // 세션이 끊겼거나 CSRF 가 틀렸다
    } else if (res.status === 400) {
        invalidRequest.add(1);
    } else if (res.status >= 500) {
        serverError.add(1);
    } else {
        unexpectedStatus.add(1);
    }
}

export function readScenario(data) {
    const s = ensureSession();
    if (!s) return;

    // 01 과 같이 잔액·원장을 반씩 섞는다.
    const balance = __ITER % 2 === 0;
    const url = balance ? `${BASE_URL}/api/users/me/balance` : `${BASE_URL}/api/ledger?size=20`;
    const res = http.get(url, {
        headers: apiHeaders(s, false),
        tags: { name: balance ? 'balance' : 'ledger', run: 'session', phase: phase(data) },
    });

    if (check(res, { 'read 200': (r) => r.status === 200 })) readOk.add(1);
    else if (res.status === 401 || res.status === 403) unauthorized.add(1);
    else if (res.status >= 500) serverError.add(1);
    else unexpectedStatus.add(1);
}

function trend(data, name, label) {
    const m = data.metrics[name];
    if (!m || !m.values) return `${label} 없음`;
    const v = m.values;
    return `${label} p50=${(v['p(50)'] ?? 0).toFixed(2)} p95=${(v['p(95)'] ?? 0).toFixed(2)} ` +
           `p99=${(v['p(99)'] ?? 0).toFixed(2)} max=${(v.max ?? 0).toFixed(2)}`;
}

export function handleSummary(data) {
    return { stdout: summaryReport(data, '06 세션 인증 재측정', [
        `  로그인 성공 ${(data.metrics.session_login_ok || { values: {} }).values.count || 0}` +
        ` · 실패 ${(data.metrics.session_login_fail || { values: {} }).values.count || 0}`,
        '',
        '  [정상 상태 구간만 — 판정은 이 값으로 한다]',
        trend(data, 'http_req_duration{name:hold,phase:steady}', '  접수  '),
        trend(data, 'http_req_duration{name:balance,phase:steady}', '  잔액  '),
        trend(data, 'http_req_duration{name:ledger,phase:steady}', '  원장  '),
        '',
        '  [워밍업 구간 — 로그인 폭풍이 섞여 있다. 관찰용]',
        trend(data, 'http_req_duration{name:hold,phase:warmup}', '  접수  '),
        '  ※ 01 과 같은 부하 모델이다. 다른 것은 인증이 세션이라는 점뿐 —',
        '     요청마다 findByEmail 트랜잭션이 돌지 않는다.',
    ]) };
}
