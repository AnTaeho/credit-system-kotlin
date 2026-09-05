#!/usr/bin/env bash
# 07 — 외부 생성 API 무한 지연
#
# 심는 사고: 스텁 지연을 600초로 올린다. 워커는 job 을 잡고 heartbeat 도 정상 갱신하며,
#            그저 영원히 돌아오지 않는다.
# 반응해야 할 지표: oldest_pending_age 단조 증가, CreditPipelineStalled
# 침묵해야 할 지표: recovery{heartbeat}=0 **그리고** recovery{backstop}=0.
#            heartbeat 가 살아 있으므로 회수하지 않는 것이 옳다. confirm/mark_failed 도 0.
#
# 이것이 "서버는 완벽히 건강한데 돈이 묶여 있다" 의 가장 순수한 형태다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

fresh_stack
restart_app_with APP_STUB_MIN_DELAY_MILLIS=600000 APP_STUB_MAX_DELAY_MILLIS=600000
reset_clock
mark "스텁 지연 600초로 재기동 (실패율은 0.3 그대로지만 실패 판정도 지연 뒤에 난다)"

charge 10000
mark "job 3건 생성"
create_jobs 3
JOBS_AT="$(date +%s)"

say "6분 동안 지켜본다 (30초마다)"
for _ in $(seq 1 12); do
  sleep 30
  mark "age=$(prom_num 'credit_job_oldest_pending_age_seconds') count=$(prom_num 'credit_hold_outstanding_count') hb=$(prom_num 'credit_job_recovery_total{detector="heartbeat"}') bs=$(prom_num 'credit_job_recovery_total{detector="backstop"}') zset=$($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') firing=[$(alerts | tr '\n' ' ')]"
done

wait_until "CreditPipelineStalled firing" 180 'alert_firing CreditPipelineStalled'
STALL_WAIT=$(( $(date +%s) - JOBS_AT ))
mark "CreditPipelineStalled firing — job 생성 이후 ${STALL_WAIT}초"

AGE="$(prom_num 'credit_job_oldest_pending_age_seconds')"
say "DB: $(job_status)  |  Redis heartbeats ZSET: $($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') 개"
say "컨테이너 CPU/메모리"
docker stats --no-stream --format '     {{.Name}}  CPU {{.CPUPerc}}  MEM {{.MemPerc}}' observability-app-1
silence_check

report_row "oldest_pending_age"             "단조 증가"       "${AGE}초"
report_row "outstanding_count"              "3"               "$(prom_num 'credit_hold_outstanding_count')"
report_row "CreditPipelineStalled 감지까지" "~360초"          "${STALL_WAIT}초"
report_row "recovery{heartbeat}"            "0 (회수 안 함이 옳다)" "$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
report_row "recovery{backstop}"             "0 (회수 안 함이 옳다)" "$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
report_row "confirm/applied"                "0"               "$(prom_num 'credit_defense_total{point="confirm",outcome="applied"}')"
report_row "mark_failed/applied"            "0"               "$(prom_num 'credit_defense_total{point="mark_failed",outcome="applied"}')"
report_row "worker_claim/applied"           "3 (churn 없음)"  "$(prom_num 'credit_defense_total{point="worker_claim",outcome="applied"}')"
report_row "http 5xx"                       "0"               "$(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)')"
report_row "up"                             "1"               "$(prom_num 'up{job="credit_system"}')"
report_row "invariant 4종 합"               "0"               "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report_row "firing 알람"                    "Stalled 만"      "[$(alerts | tr '\n' ' ')]"
report
