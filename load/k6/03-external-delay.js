/**
 * 03 — 외부 지연 주입. PERF-03.
 *
 * 01 과 **완전히 같은 부하**를 스텁 프로파일 (a)(운영 기본값: 지연 3~7초, failure-rate 0.3)에서 건다.
 * 지연 주입은 스크립트가 하는 게 아니라 앱 설정이 하는 것이다 — 01 과 03 의 차이는
 * `APP_STUB_MINDELAYMILLIS` 등 환경변수뿐이고, 코드는 부하 모델까지 동일하다.
 *
 * 보는 것: **접수 지연이 변하지 않는다.** GenerationClient.generate 는 TX-1~TX-5 어디에도
 * 없고 워커 스레드에서 트랜잭션 밖으로 나간다(요구서 3-1). 그래서 구조적으로 접수 경로는
 * 외부 지연과 무관해야 한다. thresholds 는 PERF-01 과 같은 값을 그대로 쓴다 —
 * "같은 목표를 같은 부하에서 다시 통과하는가"가 곧 PERF-03 의 판정이다.
 *
 * 주의: 이 프로파일에서는 워커가 건당 3~7초를 쓰므로 적체가 빠르게 쌓인다(PERF-07 의 산술).
 * 그것은 실패가 아니라 예상된 결과다. 접수 지연만 본다.
 */
import {
    DURATION, READ_RATE, WRITE_RATE, SUMMARY_TREND_STATS,
    arrivalRate, doRead, perfThresholds, pickUser, precheck, submitHold, summaryReport, validityThresholds,
} from './lib/common.js';

export const options = {
    discardResponseBodies: false,
    summaryTrendStats: SUMMARY_TREND_STATS,
    scenarios: {
        hold: arrivalRate(WRITE_RATE, DURATION, 'holdScenario'),
        read: arrivalRate(READ_RATE, DURATION, 'readScenario'),
    },
    thresholds: Object.assign(
        {},
        perfThresholds(),
        validityThresholds(),
        {
            http_req_failed: ['rate<0.01'],
            dropped_iterations: ['count==0'],
        }
    ),
};

export function setup() {
    return precheck(WRITE_RATE, DURATION);
}

export function holdScenario() {
    submitHold(pickUser(), { run: 'external-delay' });
}

export function readScenario() {
    doRead(pickUser(), { run: 'external-delay' });
}

export function handleSummary(data) {
    return { stdout: summaryReport(data, '03 외부 지연 주입 (PERF-03)', [
        '[읽는 법]',
        '  PERF-03 = 이 런의 {name:hold} p50·p99 가 01 의 값과 같은 수준인가.',
        '  같은 목표(p50<20, p99<100)를 여기서도 통과하면 접수 경로가 외부 지연과 분리돼 있다는 뜻이다.',
        '  두 런의 숫자를 결과 파일에 나란히 적어라. threshold 통과만으로는 "같다"를 못 말한다.',
    ]) };
}
