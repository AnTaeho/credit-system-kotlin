/**
 * 01 — Tier A 정상 부하. PERF-01 · PERF-02 · PERF-07.
 *
 * 접수 500 RPS 를 10분. 조회는 그 3배(1,500 RPS)를 잔액·원장에 반씩 섞는다.
 * 목표: POST /api/jobs p50 < 20ms, p99 < 100ms / 조회 p99 < 30ms (인증 포함, 요구서 1-4).
 *
 * **이 스크립트는 스텁 프로파일 (b)(지연을 낮춘 설정)에서 돌린다.** 프로파일 (a)(운영 기본값
 * 3~7초)에서의 같은 부하는 03 이다. 두 런의 접수 지연 분포가 같으면 그것이 PERF-03 의 근거다.
 *
 * PERF-07 은 이 런에서 따로 재지 않는다 — 스크립트가 아니라 게이지가 재기 때문이다.
 * README 의 스크레이프 루프를 부하 중과 부하 제거 후 15분간 같이 돌려라.
 */
import {
    DURATION, READ_RATE, WRITE_RATE, SUMMARY_TREND_STATS,
    arrivalRate, doRead, perfThresholds, pickUser, precheck, submitHold, summaryReport, validityThresholds,
} from './lib/common.js';

export const options = {
    discardResponseBodies: false, // 409 의 code 를 읽어야 한다
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
            // 접수가 실제로 실패한 비율. 409 는 위 콜백 덕분에 여기 안 섞인다.
            http_req_failed: ['rate<0.01'],
            // k6 가 500/1,500 RPS 를 못 냈으면 이 런은 시스템의 결과가 아니다.
            dropped_iterations: ['count==0'],
        }
    ),
};

export function setup() {
    return precheck(WRITE_RATE, DURATION);
}

export function holdScenario() {
    submitHold(pickUser(), { run: 'tier-a' });
}

export function readScenario() {
    doRead(pickUser(), { run: 'tier-a' });
}

export function handleSummary(data) {
    return { stdout: summaryReport(data, '01 Tier A (PERF-01 · 02 · 07)', [
        '[읽는 법]',
        '  PERF-01 = http_req_duration{name:hold} 의 p50 < 20, p99 < 100',
        '  PERF-02 = {name:balance} · {name:ledger} 의 p99 < 30',
        '  PERF-07 = 이 요약이 아니라 게이지다. README 의 스크레이프 루프 출력을 결과 파일에 붙여라.',
    ]) };
}
