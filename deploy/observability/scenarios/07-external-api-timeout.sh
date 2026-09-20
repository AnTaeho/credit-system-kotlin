#!/usr/bin/env bash
# 07 — 외부 생성 API 지연 폭증 → 타임아웃
#
# 심는 사고: 스텁 지연을 600초로 올린다. 사고 자체는 step7 의 07 과 **똑같다.**
#            바뀐 것은 우리 쪽이다 — step11-A 가 외부 호출에 상한
#            (app.generation.timeout-seconds, 기본 20초)을 걸었다.
#
# 그래서 이 시나리오의 이름이 바뀌었다(07-external-api-hang.sh → 07-external-api-timeout.sh).
# 600초 지연은 이제 "무한 지연" 이 아니라 "20초 타임아웃에 걸리는 느린 외부" 다.
# 타임아웃조차 먹지 않는 진짜 무응답은 app.stub.hang 이고, 그건 08 이 잰다.
#
# 반응해야 할 지표: mark_failed{applied} = job 수 × 시도 3, retry_claim = job 수 × 2,
#            final_refund = job 수, 잔액 원복, credit_worker_slots_free 가 3 으로 복귀.
# 침묵해야 할 지표: 회수 4종 전부 0(워커는 멀쩡히 돌아왔다 — 회수할 것이 없다),
#            CreditPipelineStalled(종결이 300초 안에 난다), 불변식 4종, 5xx.
#
# 타임라인 예상: 시도마다 20초(타임아웃) + backoff(10초 → 40초).
#                20 + 10 + 20 + 40 + 20 ≈ 110초에 3건 모두 FAILED·환불.
# 이 110초가 step7 의 "380초가 지나도 돈이 묶인 채였다" 를 대체한다. 그게 11-A 가 산 것이다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

JOBS=3

fresh_stack
restart_app_with APP_STUB_MIN_DELAY_MILLIS=600000 APP_STUB_MAX_DELAY_MILLIS=600000
reset_clock
mark "스텁 지연 600초로 재기동 (타임아웃 20초가 먼저 걸린다)"

grant 10000
BEFORE="$(mysql_q "SELECT balance FROM users WHERE id=1;")"
mark "job ${JOBS}건 생성 (잔액 ${BEFORE})"
create_jobs "$JOBS"
JOBS_AT="$(date +%s)"

say "종결까지 지켜본다 (10초마다 — 슬롯이 돌아오는지가 핵심이다)"
watch_for 130 \
  'credit_worker_slots_free' \
  'credit_hold_outstanding_count' \
  'credit_job_oldest_pending_age_seconds' \
  'credit_defense_total{point="mark_failed",outcome="applied"}' \
  'credit_defense_total{point="final_refund",outcome="applied"}'
note "위 다섯 열: slots_free / outstanding / oldest_age / mark_failed / final_refund"

wait_until "미결 job 0건" 180 '[ "$(prom_num "credit_hold_outstanding_count")" = "0" ]'
SETTLE_WAIT=$(( $(date +%s) - JOBS_AT ))
mark "전부 종결 — job 생성 이후 ${SETTLE_WAIT}초"

# 슬롯은 타임아웃 뒤 풀로 돌아온다. 이 값이 3 이 아니면 11-A 의 전제(타임아웃이 스레드를
# 회수한다)가 깨진 것이다 — 08 의 hang 과 갈리는 지점이 정확히 여기다.
sleep 8
SLOTS="$(prom_num 'credit_worker_slots_free')"
AFTER="$(mysql_q "SELECT balance FROM users WHERE id=1;")"

say "DB: $(job_status)  |  잔액 ${BEFORE} → ${AFTER}"
say "타임아웃 로그 (실패와 같은 경로지만 로그에서만 구분된다)"
app_logs | grep -ci "timeout\|타임아웃" | sed 's/^/     timeout 문자열이 든 로그 줄: /'
silence_check

report_row "종결까지(job 생성 이후)"        "~110초 (step7: 안 끝남)" "${SETTLE_WAIT}초"
report_row "mark_failed/applied"            "9 (3건 × 3시도)"  "$(prom_num 'credit_defense_total{point="mark_failed",outcome="applied"}')"
report_row "retry_claim/applied"            "6 (3건 × 2재시도)" "$(prom_num 'credit_defense_total{point="retry_claim",outcome="applied"}')"
report_row "final_refund/applied"           "3"               "$(prom_num 'credit_defense_total{point="final_refund",outcome="applied"}')"
report_row "잔액 (환불 후)"                 "${BEFORE} 로 원복" "${AFTER}"
report_row "credit_worker_slots_free"       "3 (슬롯 복귀)"   "${SLOTS}"
report_row "confirm/applied"                "0 (성공이 없다)" "$(prom_num 'credit_defense_total{point="confirm",outcome="applied"}')"
report_row "recovery{heartbeat}"            "0"               "$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
report_row "recovery{backstop}"             "0"               "$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
report_row "recovery{hard_cap}"             "0 (08 의 몫이다)" "$(prom_num 'credit_job_recovery_total{detector="hard_cap"}')"
report_row "oldest_pending_age (종결 후)"   "0"               "$(prom_num 'credit_job_oldest_pending_age_seconds')"
report_row "http 5xx"                       "0"               "$(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)')"
report_row "invariant 4종 합"               "0"               "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report_row "firing 알람"                    "없음"            "[$(alerts | tr '\n' ' ')]"
# 소진율은 3/3 = 100% 라 조건은 참이지만 for: 10m 이다. 이 짧은 시나리오에서는 pending 까지만
# 간다 — "firing 없음" 을 통과로 읽기 전에 pending 을 함께 봐야 오판하지 않는다.
report_row "pending 알람"                   "RetryExhaustionRateHigh" "[$(alerts_pending | tr '\n' ' ')]"
report
