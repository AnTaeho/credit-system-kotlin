/**
 * 00 — 부하 발생기 천장 측정. 04 를 돌리기 **전에** 반드시 먼저 돌린다.
 *
 * 이 머신의 k6 가 목표 RPS 를 실제로 낼 수 있는지만 본다. 낼 수 없다면 04 의 결과는
 * 시스템의 한계가 아니라 **발생기의 한계**이고, 그 구분을 못 하면 "3,000 RPS 에서 무너졌다"는
 * 문장이 거짓이 된다.
 *
 * 대상은 관리 포트의 `/actuator/health` 다. 인증도 DB 도 타지 않는 가장 가벼운 엔드포인트라
 * 여기서 안 나오는 RPS 는 앱 때문이 아니다(SecurityConfig: 관리 포트가 따로면 health·prometheus 는 permitAll).
 *
 * **한계:** 8081 은 8080 과 다른 커넥터다. 이 수치는 "k6 + 루프백 + 서블릿 컨테이너"의 천장이지
 * `/api/jobs` 경로의 천장이 아니다. 그래도 발생기 쪽 병목(파일 디스크립터, 포트 고갈, VU 부족)은
 * 여기서 전부 드러난다.
 *
 * 실행:  k6 run load/k6/00-generator-ceiling.js -e TARGET_RATE=3000
 */
import http from 'k6/http';
import { MGMT_URL, SUMMARY_TREND_STATS, summaryReport } from './lib/common.js';

const TARGET_RATE = Number(__ENV.TARGET_RATE || 3000);
const DURATION = __ENV.CEILING_DURATION || '30s';

export const options = {
    summaryTrendStats: SUMMARY_TREND_STATS,
    scenarios: {
        ceiling: {
            executor: 'constant-arrival-rate',
            rate: TARGET_RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 500),
            maxVUs: Number(__ENV.MAX_VUS || 2000),
        },
    },
    thresholds: {
        // 발생기가 목표를 냈는가. 이것만 본다.
        dropped_iterations: ['count==0'],
        http_req_failed: ['rate==0'],
    },
};

export default function () {
    http.get(`${MGMT_URL}/actuator/health`, { tags: { name: 'health' } });
}

export function handleSummary(data) {
    const reqs = data.metrics.http_reqs && data.metrics.http_reqs.values;
    const achieved = reqs ? Math.round(reqs.rate) : 0;
    return { stdout: summaryReport(data, '00 부하 발생기 천장', [
        '[판정]',
        `  목표 ${TARGET_RATE} RPS / 실제 ${achieved} RPS`,
        achieved >= TARGET_RATE * 0.95
            ? '  PASS — 발생기는 목표를 낼 수 있다. 04 의 결과를 시스템의 결과로 읽어도 된다.'
            : '  FAIL — 발생기가 목표를 못 낸다. 04 의 오류·지연에는 발생기 몫이 섞인다.',
        '  낮으면 확인할 것: ulimit -n, k6 의 "insufficient VUs" 경고, 루프백 포트 고갈(keep-alive 유지).',
    ]) };
}
