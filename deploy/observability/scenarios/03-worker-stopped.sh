#!/usr/bin/env bash
# 03 — 워커 정지 (서버는 완벽히 건강하다)
#
# 심는 사고: app.worker.enabled=false 로 재기동. API 도 스케줄러도 살아 있고 워커만 없다.
# 반응해야 할 지표: outstanding_count, oldest_pending_age(단조 증가), CreditPipelineStalled
# 침묵해야 할 지표: 방어 카운터 전부, 회수 카운터, 5xx, 불변식
#
# 이 시나리오와 07 이 이 챕터의 논지 그 자체다 — 서버 지표는 전부 정상이다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

fresh_stack
restart_app_with APP_WORKER_ENABLED=false
reset_clock
mark "워커 정지 상태로 재기동. 스케줄러·API 는 그대로 산다"

charge 10000
BASE_WORKER_CLAIM="$(prom_num 'credit_defense_total{point="worker_claim",outcome="applied"}')"
BASE_CONFIRM="$(prom_num 'credit_defense_total{point="confirm",outcome="applied"}')"
BASE_5XX="$(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)')"

mark "job 5건 생성"
create_jobs 5
JOBS_AT="$(date +%s)"

say "5분 30초 동안 oldest_pending_age 를 지켜본다 (30초마다)"
for _ in $(seq 1 11); do
  sleep 30
  mark "count=$(prom_num 'credit_hold_outstanding_count') amount=$(prom_num 'credit_hold_outstanding_amount') age=$(prom_num 'credit_job_oldest_pending_age_seconds') pending알람=[$(alerts_pending | tr '\n' ' ')] firing=[$(alerts | tr '\n' ' ')]"
done

wait_until "CreditPipelineStalled firing" 240 'alert_firing CreditPipelineStalled'
STALL_WAIT=$(( $(date +%s) - JOBS_AT ))
mark "CreditPipelineStalled firing — job 생성 이후 ${STALL_WAIT}초 (임계 300초 + for 60초)"

COUNT="$(prom_num 'credit_hold_outstanding_count')"
AMOUNT="$(prom_num 'credit_hold_outstanding_amount')"
AGE="$(prom_num 'credit_job_oldest_pending_age_seconds')"
say "이 시점의 DB: $(job_status)"
silence_check

report_row "outstanding_count"                "5"          "${COUNT}"
report_row "outstanding_amount"               "500"        "${AMOUNT}"
report_row "oldest_pending_age"               "단조 증가"  "${AGE}초"
report_row "CreditPipelineStalled 감지까지"   "~360초"     "${STALL_WAIT}초"
report_row "firing 알람"                      "Stalled 만" "[$(alerts | tr '\n' ' ')]"
report_row "worker_claim/applied 증가분"      "0"          "$(( $(prom_num 'credit_defense_total{point="worker_claim",outcome="applied"}') - BASE_WORKER_CLAIM ))"
report_row "confirm/applied 증가분"           "0"          "$(( $(prom_num 'credit_defense_total{point="confirm",outcome="applied"}') - BASE_CONFIRM ))"
report_row "recovery{heartbeat}"              "0"          "$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
report_row "recovery{backstop}"               "0"          "$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
report_row "http 5xx 증가분"                  "0"          "$(( $(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)') - BASE_5XX ))"
report_row "invariant 4종 합"                 "0"          "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report_row "up"                               "1"          "$(prom_num 'up{job="credit_system"}')"
report
