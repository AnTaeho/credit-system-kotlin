/**
 * 04 — 생존 스파이크. PERF-06.
 *
 * 접수를 3,000 RPS 까지 올린다. 목표는 **빠른 것이 아니라 살아남는 것**이다.
 *   - 오류율 상승은 **허용한다.** 예상 경로는 커넥션 고갈이다(Hikari 최대 10, 요청당 2회 획득).
 *   - 5xx·status 0 에는 **같은 idemKey 로 재시도**한다(lib/common.js). 재시도가 중복 차감을
 *     만들지 않는다는 것이 INV-03 이고, 그 관측치가 retry_ok_duplicate / retry_ok_new 다.
 *   - **불변식 위반 0건**은 이 스크립트가 아니라 부하 직후 `./gradlew postLoadCheck` 가 증명한다.
 *   - **부하 제거 후 5분 내 정상 복귀**를 recovery 시나리오로 코드화했다. 스파이크가 끝난 뒤
 *     50 RPS 로 5분을 더 치고, 그 구간(`phase:recovery`)에만 PERF-01 의 목표치를 건다.
 *
 * Hikari 기본값 10 을 바꾸지 마라. 바꾸면 "현재 구현"의 측정이 아니다.
 *
 * ── 부하 발생기가 병목이면 이 결과는 시스템의 한계가 아니다
 * 실행 전에 `00-generator-ceiling.js` 로 이 머신의 k6 가 3,000 RPS 를 낼 수 있는지 먼저 재라.
 * 런 중 `dropped_iterations` 와 "insufficient VUs" 경고가 나오면 그 수치를 결과 파일에 적고,
 * 그만큼은 시스템이 아니라 발생기의 한계로 읽어라. 그래서 여기서는 dropped_iterations 에
 * threshold 를 걸지 않는다 — 걸면 발생기의 한계가 시스템의 실패로 기록된다.
 */
import { Counter } from 'k6/metrics';
import {
    SUMMARY_TREND_STATS,
    parseDurationSeconds, pickUser, precheckWrites, submitHold, summaryReport,
} from './lib/common.js';

const START_RATE = Number(__ENV.START_RATE || 500);
const PEAK_RATE = Number(__ENV.PEAK_RATE || 3000);
const RAMP_UP = __ENV.RAMP_UP || '2m';
const PEAK_HOLD = __ENV.PEAK_HOLD || '5m';
const RAMP_DOWN = __ENV.RAMP_DOWN || '1m';
const RECOVERY_RATE = Number(__ENV.RECOVERY_RATE || 50);

/**
 * 복구 구간을 **둘로 나눈다.**
 *
 *   recovery  (8~12분): 배수 구간. **threshold 를 걸지 않는다.**
 *   recovered (12~13분): 부하 제거 +4분 뒤의 1분. **여기에만 PERF-01 의 목표치를 건다.**
 *
 * 5분을 통째로 한 창으로 잡고 p99 를 보면 앞부분의 배수가 뒤를 끌어내린다. 스파이크의
 * gracefulStop(기본 30초)과 Hikari 의 획득 타임아웃(기본 30초) 때문에 부하를 끊은 뒤에도
 * 30~60초는 아직 밀린 요청이 빠지는 시간이다. 50 RPS × 300초 = 15,000 건에서 p99 가 봐주는
 * 느린 요청은 150 건뿐인데 30초의 배수만 1,500 건이라, 2분 만에 완전히 회복해도 FAIL 이 뜬다.
 * 요구서가 말하는 "5분 내 정상 복귀"는 **t+5 시점에 정상인가**이지 5분 평균이 아니다.
 */
const RECOVERY_START = __ENV.RECOVERY_START || '8m';      // 스파이크 종료(램프 2m + 유지 5m + 하강 1m)
const RECOVERY_DURATION = __ENV.RECOVERY_DURATION || '4m';
const RECOVERED_START = __ENV.RECOVERED_START || '12m';
const RECOVERED_DURATION = __ENV.RECOVERED_DURATION || '1m';

/** 램프의 사다리꼴 넓이 + 유지 구간 + 복구 두 구간. 시드 선행 검사에 쓴다. */
const ESTIMATED_WRITES = Math.ceil(
    ((START_RATE + PEAK_RATE) / 2) * parseDurationSeconds(RAMP_UP) +
        PEAK_RATE * parseDurationSeconds(PEAK_HOLD) +
        (PEAK_RATE / 2) * parseDurationSeconds(RAMP_DOWN) +
        RECOVERY_RATE * (parseDurationSeconds(RECOVERY_DURATION) + parseDurationSeconds(RECOVERED_DURATION))
);

/** 스파이크 구간에서 접수가 어떤 식으로든 받아들여진 건수. 생존의 최소 증거다. */
export const spikeAccepted = new Counter('spike_accepted');

export const options = {
    discardResponseBodies: false,
    summaryTrendStats: SUMMARY_TREND_STATS,
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: START_RATE,
            timeUnit: '1s',
            // VU 는 넉넉히 잡되 상한을 둔다. VU 하나가 수 MB 라 rate × 3 을 그대로 잡으면
            // k6 자신이 메모리로 죽는다. 부족하면 k6 가 경고를 찍고 dropped_iterations 가 오른다.
            preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 1500),
            maxVUs: Number(__ENV.MAX_VUS || 4000),
            stages: [
                { target: PEAK_RATE, duration: RAMP_UP },
                { target: PEAK_RATE, duration: PEAK_HOLD },
                { target: 0, duration: RAMP_DOWN },
            ],
            exec: 'spikeScenario',
        },
        // 배수 구간 — 관찰만 한다. 회복 곡선을 보려고 찍는다.
        recovery: {
            executor: 'constant-arrival-rate',
            rate: RECOVERY_RATE,
            timeUnit: '1s',
            duration: RECOVERY_DURATION,
            startTime: RECOVERY_START,
            preAllocatedVUs: 50,
            maxVUs: 500,
            exec: 'recoveryScenario',
        },
        // 판정 구간 — 부하 제거 +4분 뒤의 1분. 여기가 PERF-06 의 "5분 내 정상 복귀"다.
        recovered: {
            executor: 'constant-arrival-rate',
            rate: RECOVERY_RATE,
            timeUnit: '1s',
            duration: RECOVERED_DURATION,
            startTime: RECOVERED_START,
            preAllocatedVUs: 50,
            maxVUs: 500,
            exec: 'recoveredScenario',
        },
    },
    thresholds: {
        // 런을 무효로 만드는 조건만 남긴다. 성능 threshold 는 스파이크 구간에 걸지 않는다 —
        // 느려지는 것은 허용된 결과다.
        unauthorized: ['count==0'],
        invalid_request: ['count==0'],
        insufficient_balance: ['count==0'],
        unexpected_status: ['count==0'],
        unexpected_duplicate: ['count==0'],
        // 항상 참인 threshold. k6 는 threshold 가 걸린 서브메트릭만 요약에 만들어 주므로,
        // 판정하지 않는 두 구간을 **보이게** 하려면 이 줄이 필요하다. 회복 곡선이 여기서 읽힌다.
        'http_req_duration{name:hold,phase:spike}': ['max>=0'],
        'http_req_duration{name:hold,phase:recovery}': ['max>=0'],
        // PERF-06 "부하 제거 후 5분 내 정상 복귀". 배수가 끝난 뒤의 1분에만 PERF-01 의 목표치를 건다.
        'http_req_duration{name:hold,phase:recovered}': ['p(50)<20', 'p(99)<100'],
        'http_req_failed{phase:recovered}': ['rate<0.01'],
    },
};

export function setup() {
    console.log(`04 스파이크: ${START_RATE} → ${PEAK_RATE} RPS, 예상 쓰기 ${ESTIMATED_WRITES} 건`);
    return precheckWrites(ESTIMATED_WRITES, `스파이크 예상 쓰기 ${ESTIMATED_WRITES} 건`);
}

export function spikeScenario() {
    const r = submitHold(pickUser(), { run: 'spike', phase: 'spike' });
    if (r.outcome === 'ok' || r.outcome === 'duplicate_ok' || r.outcome === 'duplicate_in_progress') {
        spikeAccepted.add(1);
    }
}

export function recoveryScenario() {
    submitHold(pickUser(), { run: 'spike', phase: 'recovery' });
}

export function recoveredScenario() {
    submitHold(pickUser(), { run: 'spike', phase: 'recovered' });
}

export function handleSummary(data) {
    return { stdout: summaryReport(data, '04 생존 스파이크 (PERF-06)', [
        '[읽는 법]',
        '  오류율 자체는 판정 대상이 아니다. 판정은 셋이다.',
        '   1. phase:recovered(부하 제거 +4~5분) 구간의 threshold 가 통과했는가 (= 5분 내 정상 복귀)',
        '      phase:recovery(+0~4분)는 배수 구간이라 판정하지 않는다. 두 줄을 나란히 보면 회복 곡선이 보인다.',
        '   2. `./gradlew postLoadCheck` 가 INV-01·INV-02 세 검사에서 0 건인가 (= 불변식 위반 0건)',
        '   3. retry_ok_duplicate 가 있으면 재시도가 중복 차감을 만들지 않았다는 관측이다 (INV-03)',
        '  dropped_iterations 와 "insufficient VUs" 경고는 발생기의 한계다. 수치를 결과 파일에 적어라.',
    ]) };
}
