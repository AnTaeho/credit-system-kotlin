#!/usr/bin/env bash
# 01 — 워커 프로세스 크래시
#
# 심는 사고: PROCESSING 중인 job 을 들고 있는 프로세스를 SIGKILL 로 죽인다.
#            heartbeat 는 Redis 에 남아 있지만 갱신이 멈춘다.
# 반응해야 할 지표: credit_job_recovery_total{detector="heartbeat"}
# 침묵해야 할 지표: {detector="backstop"}, 불변식 4종, mismatch
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

fresh_stack
charge 10000

mark "job 6건 생성"
create_jobs 6

wait_until "PROCESSING job 이 생겼다" 60 '[ "$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='"'"'PROCESSING'"'"';")" -gt 0 ]'
PROC_BEFORE="$(mysql_q "SELECT GROUP_CONCAT(CONCAT(id,':',attempt_no)) FROM jobs WHERE status='PROCESSING';")"
mark "PROCESSING = ${PROC_BEFORE}  (전체: $(job_status))"

mark "앱 SIGKILL"
kill_app
KILL_AT="$(date +%s)"

mark "즉시 재기동 (env 기본값)"
start_app
UP_AT="$(date +%s)"
mark "앱 UP — 크래시로부터 $(( UP_AT - KILL_AT ))초"

# heartbeat 만료(10초)는 앱이 죽어 있는 동안에도 진행된다. 재기동 후 첫 스캔(5초 주기)에서 잡혀야 한다.
wait_until "recovery{heartbeat} >= 1" 120 '[ "$(prom_num "credit_job_recovery_total{detector=\"heartbeat\"}")" != "0" ]'
HB_WAIT="$WAITED"
HB_TOTAL=$(( $(date +%s) - KILL_AT ))
mark "heartbeat 회수 감지 — 앱 UP 이후 ${HB_WAIT}초 / SIGKILL 이후 ${HB_TOTAL}초"

RECOVERY_HB="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
BACKSTOP_NOW="$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
AGE_PEAK="$(prom_num 'credit_job_oldest_pending_age_seconds')"

mark "회수 직후 지표: recovery{heartbeat}=${RECOVERY_HB} backstop=${BACKSTOP_NOW} oldest_pending_age=${AGE_PEAK}"

say "잔여 job 이 전부 종결될 때까지 기다린다"
wait_until "미결 job 0건" 240 '[ "$(prom_num "credit_hold_outstanding_count")" = "0" ]'
mark "전부 종결 — $(job_status), 잔액=$(balance)"
AGE_AFTER="$(prom_num 'credit_job_oldest_pending_age_seconds')"

silence_check
say "방어 카운터 (재기동 후 누적)"
prom_all 'credit_defense_total' | sed 's/^/     /'

report_row "recovery{detector=heartbeat}"      ">= 1"        "${RECOVERY_HB}"
report_row "  감지까지(앱 UP 이후)"            "10~15초"     "${HB_WAIT}초"
report_row "  감지까지(SIGKILL 이후)"          "-"           "${HB_TOTAL}초"
report_row "recovery{detector=backstop}"       "0"           "$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
report_row "oldest_pending_age (회수 시점)"    "올랐다"      "${AGE_PEAK}"
report_row "oldest_pending_age (종결 후)"      "0 으로 복귀" "${AGE_AFTER}"
report_row "retry_claim/applied"               ">= 1"        "$(prom_num 'credit_defense_total{point="retry_claim",outcome="applied"}')"
report_row "invariant negative_balance_orgs"   "0"           "$(prom_num 'credit_invariant_negative_balance_orgs')"
report_row "invariant jobs_without_hold"       "0"           "$(prom_num 'credit_invariant_jobs_without_hold')"
report_row "invariant unsettled_terminal_jobs" "0"           "$(prom_num 'credit_invariant_unsettled_terminal_jobs')"
report_row "ledger mismatch"                   "0"           "$(prom_num 'credit_ledger_reconciliation_mismatch')"
report_row "firing 알람"                       "없음"        "[$(alerts | tr '\n' ' ')]"
report
