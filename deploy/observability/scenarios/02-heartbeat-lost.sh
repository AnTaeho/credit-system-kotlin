#!/usr/bin/env bash
# 02 — heartbeat 소실
#
# A. 워커가 죽은 뒤 Redis 를 재시작해 ZSET(`heartbeats`)을 통째로 날린다.
#    heartbeat 탐지기는 잡을 엔트리가 없어 침묵하고, updatedAt 백스톱만 남는다.
# B. 앱이 살아 있는 채로 Redis 를 90초 내려 본다.
#    백스톱이 Redis 를 참조한다는 사실(hasLiveHeartbeat)이 드러난다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

fresh_stack
charge 10000

# ─────────────────────────────── A. ZSET 소실 ────────────────────────────────
say "A. heartbeat ZSET 소실 — 백스톱만 남는다"
reset_clock
mark "job 4건 생성"
create_jobs 4
wait_until "PROCESSING job 이 생겼다" 60 '[ "$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='"'"'PROCESSING'"'"';")" -gt 0 ]'
mark "PROCESSING = $(mysql_q "SELECT GROUP_CONCAT(CONCAT(id,':',attempt_no)) FROM jobs WHERE status='PROCESSING';")"
mark "Redis ZSET: $($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') 개"

mark "앱 SIGKILL"
kill_app
KILL_AT="$(date +%s)"

# 주의: ZSET 을 날리는 데 두 겹의 함정이 있다.
#  1) docker compose restart — redis:7 은 SIGTERM 에 RDB 를 저장하고 재기동 때 그대로 로드한다.
#  2) docker compose up --force-recreate — redis:7 이미지가 VOLUME /data 를 선언하므로
#     Docker 가 익명 볼륨을 만들어 두고, 컨테이너를 새로 만들어도 그 볼륨을 물려준다.
# 익명 볼륨까지 갈아엎어야(--renew-anon-volumes) 비로소 "Redis 가 기억을 잃었다"가 된다.
mark "Redis 컨테이너+익명 볼륨 교체 — ZSET 이 사라진다"
$DC up -d --force-recreate --renew-anon-volumes redis >/dev/null 2>&1
sleep 3
mark "Redis ZSET: $($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') 개 (소실 확인)"

mark "앱 재기동"
start_app
mark "앱 UP — SIGKILL 이후 $(( $(date +%s) - KILL_AT ))초"

wait_until "recovery{backstop} >= 1" 240 '[ "$(prom_num "credit_job_recovery_total{detector=\"backstop\"}")" != "0" ]'
A_BACKSTOP_WAIT="$WAITED"
A_BACKSTOP_TOTAL=$(( $(date +%s) - KILL_AT ))
A_BACKSTOP="$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
A_HEARTBEAT="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
mark "backstop 회수 감지 — 앱 UP 이후 ${A_BACKSTOP_WAIT}초 / SIGKILL 이후 ${A_BACKSTOP_TOTAL}초 (backstop=${A_BACKSTOP}, heartbeat=${A_HEARTBEAT})"

wait_until "CreditBackstopRecovery firing" 90 'alert_firing CreditBackstopRecovery'
A_ALERT_WAIT="$WAITED"
mark "firing 알람: [$(alerts | tr '\n' ' ')]"

wait_until "미결 job 0건" 300 '[ "$(prom_num "credit_hold_outstanding_count")" = "0" ]'
mark "A 종료 — $(job_status)"
A_HEARTBEAT="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"

# ────────────────────── B. Redis 가 죽어 있는 동안의 백스톱 ────────────────────
# 백스톱 경로만 남기려고 워커를 끈 채 재기동한다(카운터도 이때 0 으로 리셋된다).
# 그 다음 PROCESSING 행을 손으로 심는다 — "소유자가 사라진 job" 을 SQL 로 재현한 것이다.
say "B. Redis 다운 중 — 백스톱이 Redis 를 참조한다"
reset_clock
restart_app_with APP_WORKER_ENABLED=false
mark "워커 정지 상태로 재기동 (회수 스케줄러만 남긴다). 회수 카운터 리셋"

charge 10000
create_jobs 3
sleep 3
mysql_q "UPDATE jobs SET status='PROCESSING', updated_at = NOW(6) - INTERVAL 120 SECOND WHERE status='HOLDING';" >/dev/null
PLANTED="$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='PROCESSING';")"
mark "PROCESSING 행 ${PLANTED}건을 updated_at=120초 전으로 심었다 (백스톱 대상)"

mark "Redis 정지"
$DC stop redis >/dev/null 2>&1
LOG_BEFORE_STAGE="$(app_logs | grep -c 'heartbeat 만료 회수 단계 실패')"
LOG_BEFORE_ITEM="$(app_logs | grep -c 'PROCESSING 정체 job 회수 실패')"

mark "90초 동안 방치한다 (스캔 주기 5초 → 18회 스캔)"
for _ in 1 2 3 4 5 6 7 8 9; do
  sleep 10
  mark "backstop=$(prom_num 'credit_job_recovery_total{detector="backstop"}') heartbeat=$(prom_num 'credit_job_recovery_total{detector="heartbeat"}') PROCESSING=$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='PROCESSING';")"
done

B_BACKSTOP_DOWN="$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
B_HEARTBEAT_DOWN="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
LOG_STAGE=$(( $(app_logs | grep -c 'heartbeat 만료 회수 단계 실패') - LOG_BEFORE_STAGE ))
LOG_ITEM=$(( $(app_logs | grep -c 'PROCESSING 정체 job 회수 실패') - LOG_BEFORE_ITEM ))
mark "Redis 다운 90초 결산: backstop=${B_BACKSTOP_DOWN} heartbeat=${B_HEARTBEAT_DOWN}"
mark "  로그 'heartbeat 만료 회수 단계 실패' ${LOG_STAGE}회 / 'PROCESSING 정체 job 회수 실패' ${LOG_ITEM}회"
say "로그 표본"
app_logs | grep -E 'heartbeat 만료 회수 단계 실패|PROCESSING 정체 job 회수 실패' | tail -2 | cut -c1-200 | sed 's/^/     /'

mark "Redis 재시작"
$DC start redis >/dev/null 2>&1
wait_until "Redis 복구 후 recovery{backstop} >= 1" 120 '[ "$(prom_num "credit_job_recovery_total{detector=\"backstop\"}")" != "0" ]'
B_RECOVER_WAIT="$WAITED"
B_BACKSTOP_UP="$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
mark "Redis 복구 ${B_RECOVER_WAIT}초 만에 backstop=${B_BACKSTOP_UP} — 막고 있던 것은 Redis 였다"

silence_check

report_row "A recovery{backstop}"              ">= 1"     "${A_BACKSTOP}"
report_row "A   감지까지(앱 UP 이후)"          "~60초"    "${A_BACKSTOP_WAIT}초"
report_row "A   감지까지(SIGKILL 이후)"        "-"        "${A_BACKSTOP_TOTAL}초"
report_row "A recovery{heartbeat}"             "0"        "${A_HEARTBEAT}"
report_row "A CreditBackstopRecovery firing"   "firing"   "$(alerts | grep -c CreditBackstopRecovery)건 (+${A_ALERT_WAIT}초)"
report_row "B Redis 다운 90초 recovery{backstop}"  "0 (결함)" "${B_BACKSTOP_DOWN}"
report_row "B Redis 다운 90초 recovery{heartbeat}" "0 (결함)" "${B_HEARTBEAT_DOWN}"
report_row "B   '단계 실패' ERROR"             "다수"     "${LOG_STAGE}회"
report_row "B   '정체 job 회수 실패' WARN"     "다수"     "${LOG_ITEM}회"
report_row "B Redis 복구 후 recovery{backstop}" ">= 1"    "${B_BACKSTOP_UP} (+${B_RECOVER_WAIT}초)"
report_row "invariant 4종 합"                  "0"        "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report
