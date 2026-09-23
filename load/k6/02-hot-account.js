/**
 * 02 — 핫 계정 편중. PERF-05.
 *
 * 전체 쓰기의 **정확히 5%** 를 한 계정으로 보낸다(500 RPS × 5% = 초당 25건이 한 행을 친다).
 * 나머지 95% 는 다른 계정들에 고르게 나눈다. 두 무리의 지연을 `dist:hot` / `dist:uniform`
 * 태그로 **따로** 잰다.
 *
 * 5% 를 Math.random 으로 만들지 않는다. 반복 번호 % 20 == 0 이면 정확히 5% 이고, 런마다
 * 비율이 흔들리지 않는다 — 비교 대상이 비율 자체라서 그 흔들림이 곧 오차가 된다.
 *
 * 조회 부하도 01 과 같게 섞는다. 환경이 같아야 01 의 균등 분포와 비교가 성립한다.
 *
 * ── 판정
 * 목표는 "핫 계정 p99 악화 < 균등 대비 3배"인데, k6 threshold 는 **메트릭 둘을 서로 비교하지
 * 못한다.** 그래서 둘로 나눠 건다.
 *   (1) 정적 방어선: hot p99 < 300ms — PERF-01 의 p99 100ms 의 3배. k6 가 스스로 판정한다.
 *   (2) 실제 비율: handleSummary 가 hot p99 ÷ uniform p99 를 계산해 PASS/FAIL 한 줄로 찍는다.
 *       이 줄이 PERF-05 의 판정이다. (1) 만으로는 균등 분포가 함께 느려진 경우를 못 가른다.
 */
import exec from 'k6/execution';
import {
    DURATION, GENERATION_COST, HOT_USER, READ_RATE, USERS, WRITE_RATE, SUMMARY_TREND_STATS,
    arrivalRate, doRead, p99Of, parseDurationSeconds, precheckNeeds, submitHold, summaryReport, validityThresholds,
} from './lib/common.js';

/** 핫 계정은 균등 무리에서 뺀다. 빼지 않으면 균등 쪽 숫자에 핫 행의 락 대기가 섞인다. */
const UNIFORM_USERS = USERS.filter((u) => u !== HOT_USER);
const UNIFORM_POOL = UNIFORM_USERS.length > 0 ? UNIFORM_USERS : USERS;

/** 20 반복에 1번 = 5%. */
const HOT_EVERY = 20;

export const options = {
    discardResponseBodies: false,
    summaryTrendStats: SUMMARY_TREND_STATS,
    scenarios: {
        hold: arrivalRate(WRITE_RATE, DURATION, 'holdScenario'),
        read: arrivalRate(READ_RATE, DURATION, 'readScenario'),
    },
    thresholds: Object.assign(
        {},
        validityThresholds(),
        {
            // 균등 무리는 PERF-01 과 같은 목표를 그대로 지켜야 한다. 핫 계정이 균등 무리를
            // 끌어내리면 그건 "핫 계정만 느리다"가 아니라 시스템 전체가 말린 것이다.
            'http_req_duration{name:hold,dist:uniform}': ['p(50)<20', 'p(99)<100'],
            // 핫 계정의 정적 방어선. 균등 목표 p99 의 3배.
            'http_req_duration{name:hold,dist:hot}': ['p(99)<300'],
            'http_req_duration{name:balance}': ['p(99)<30'],
            'http_req_duration{name:ledger}': ['p(99)<30'],
            http_req_failed: ['rate<0.01'],
            dropped_iterations: ['count==0'],
        }
    ),
};

export function setup() {
    if (UNIFORM_USERS.length === 0) {
        console.warn(
            `USERS 에 핫 계정(${HOT_USER}) 말고 다른 계정이 없다. 균등 무리와 핫 무리가 같은 행을 쳐서 ` +
                'PERF-05 의 비교가 성립하지 않는다. USERS 에 계정을 더 넣어라.'
        );
    }

    // 여기서 쓰기가 **고르게 나뉘지 않는다.** 핫 계정 하나가 5% 를 혼자 받으므로 균등 가정으로
    // 검사하면 핫 계정의 시드 부족을 통째로 놓친다(부하 중반에 409 가 쏟아진 뒤에야 안다).
    // 그래서 계정별 필요량을 직접 만들어 넘긴다.
    const totalWrites = Math.ceil(WRITE_RATE * parseDurationSeconds(DURATION));
    const hotWrites = Math.ceil(totalWrites / HOT_EVERY);
    const uniformWrites = Math.ceil((totalWrites - hotWrites) / UNIFORM_POOL.length);

    const needs = {};
    needs[HOT_USER] = hotWrites * GENERATION_COST;
    for (const email of UNIFORM_POOL) {
        needs[email] = (needs[email] || 0) + uniformWrites * GENERATION_COST;
    }
    return precheckNeeds(needs, `핫 1/${HOT_EVERY} + 균등 ${UNIFORM_POOL.length}개`);
}

export function holdScenario() {
    const i = exec.scenario.iterationInTest;
    if (i % HOT_EVERY === 0) {
        submitHold(HOT_USER, { run: 'hot-account', dist: 'hot' });
    } else {
        submitHold(UNIFORM_POOL[i % UNIFORM_POOL.length], { run: 'hot-account', dist: 'uniform' });
    }
}

export function readScenario() {
    doRead(UNIFORM_POOL[exec.scenario.iterationInTest % UNIFORM_POOL.length], { run: 'hot-account' });
}

export function handleSummary(data) {
    const hot = p99Of(data, 'hold_duration_hot');
    const uniform = p99Of(data, 'hold_duration_uniform');
    const lines = ['[PERF-05 판정 — 핫 p99 ÷ 균등 p99 < 3]'];
    if (hot === null || uniform === null || uniform === 0) {
        lines.push(`  판정 불가: hot p99=${hot}, uniform p99=${uniform}. 두 무리가 모두 돌았는지 확인해라.`);
    } else {
        const ratio = hot / uniform;
        lines.push(
            `  ${ratio < 3 ? 'PASS' : 'FAIL'}  hot p99=${Math.round(hot * 100) / 100}ms ÷ ` +
                `uniform p99=${Math.round(uniform * 100) / 100}ms = ${Math.round(ratio * 100) / 100}배`
        );
        lines.push('  ※ k6 종료 코드는 이 줄을 반영하지 않는다(threshold 가 아니다). 결과 파일에는 이 줄을 적어라.');
    }
    lines.push(`  핫 계정=${HOT_USER}, 균등 계정 ${UNIFORM_POOL.length}개, 핫 비율=1/${HOT_EVERY}`);
    lines.push('  MySQL 락 대기(README 5절)를 같이 붙여야 원인이 잔액 UPDATE 직렬화인지 갈린다.');
    return { stdout: summaryReport(data, '02 핫 계정 편중 (PERF-05)', lines) };
}
