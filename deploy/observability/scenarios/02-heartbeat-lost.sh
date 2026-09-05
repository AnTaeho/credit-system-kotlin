#!/usr/bin/env bash
# 02 — heartbeat 소실
#
# A. 워커가 죽은 뒤 Redis 를 재시작해 ZSET(`heartbeats`)을 통째로 날린다.
#    heartbeat 탐지기는 잡을 엔트리가 없어 침묵하고, updatedAt 백스톱만 남는다.
# B. 앱이 살아 있는 채로 Redis 를 90초 내려 본다.
#    후속 1 이전에는 백스톱이 Redis 를 참조해(hasLiveHeartbeat) 회수가 0건이었다.
#    지금은 heartbeat 조회가 UNKNOWN 을 돌려주고, 백스톱이 updatedAt 만으로 회수한다.
#    기대: recovery{backstop_blind} 가 오르고 backstop/heartbeat 는 0.
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
A_BLIND="$(prom_num 'credit_job_recovery_total{detector="backstop_blind"}')"
mark "backstop 회수 감지 — 앱 UP 이후 ${A_BACKSTOP_WAIT}초 / SIGKILL 이후 ${A_BACKSTOP_TOTAL}초 (backstop=${A_BACKSTOP}, heartbeat=${A_HEARTBEAT})"

wait_until "CreditBackstopRecovery firing" 90 'alert_firing CreditBackstopRecovery'
A_ALERT_WAIT="$WAITED"
mark "firing 알람: [$(alerts | tr '\n' ' ')]"

wait_until "미결 job 0건" 300 '[ "$(prom_num "credit_hold_outstanding_count")" = "0" ]'
mark "A 종료 — $(job_status)"
A_HEARTBEAT="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
A_BLIND="$(prom_num 'credit_job_recovery_total{detector="backstop_blind"}')"

# ────────────────────── B. Redis 가 죽어 있는 동안의 백스톱 ────────────────────
# 백스톱 경로만 남기려고 워커를 끈 채 재기동한다(카운터도 이때 0 으로 리셋된다).
# 그 다음 PROCESSING 행을 손으로 심는다 — "소유자가 사라진 job" 을 SQL 로 재현한 것이다.
say "B. Redis 다운 중 — 백스톱이 Redis 없이도 돈다"
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
REDIS_DOWN_AT="$(date +%s)"
LOG_BEFORE_STAGE="$(app_logs | grep -c 'heartbeat 만료 회수 단계 실패')"
LOG_BEFORE_BLIND="$(app_logs | grep -c 'heartbeat 저장소에 닿지 않아')"

# 후속 1 이전에는 여기서 90초를 통째로 기다려도 0 이었다. 지금은 updatedAt 타임아웃(60초)이
# 이미 지난 행을 심었으므로 다음 스캔 주기(5초)에 바로 잡혀야 한다.
wait_until "recovery{backstop_blind} >= 1" 90 '[ "$(prom_num "credit_job_recovery_total{detector=\"backstop_blind\"}")" != "0" ]'
B_BLIND_WAIT="$WAITED"
mark "blind 회수 감지 — Redis 정지 이후 $(( $(date +%s) - REDIS_DOWN_AT ))초"

wait_until "CreditBackstopBlindRecovery firing" 90 'alert_firing CreditBackstopBlindRecovery'
B_ALERT_WAIT="$WAITED"
mark "firing 알람: [$(alerts | tr '\n' ' ')]"

mark "Redis 정지 상태로 90초를 마저 채운다 (추가 회수가 없는지 본다)"
for _ in 1 2 3 4 5 6; do
  sleep 10
  mark "blind=$(prom_num 'credit_job_recovery_total{detector="backstop_blind"}') backstop=$(prom_num 'credit_job_recovery_total{detector="backstop"}') heartbeat=$(prom_num 'credit_job_recovery_total{detector="heartbeat"}') PROCESSING=$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='PROCESSING';") HOLDING=$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='HOLDING';")"
done

B_BLIND_DOWN="$(prom_num 'credit_job_recovery_total{detector="backstop_blind"}')"
B_BACKSTOP_DOWN="$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
B_HEARTBEAT_DOWN="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
LOG_STAGE=$(( $(app_logs | grep -c 'heartbeat 만료 회수 단계 실패') - LOG_BEFORE_STAGE ))
LOG_BLIND=$(( $(app_logs | grep -c 'heartbeat 저장소에 닿지 않아') - LOG_BEFORE_BLIND ))
mark "Redis 다운 90초 결산: blind=${B_BLIND_DOWN} backstop=${B_BACKSTOP_DOWN} heartbeat=${B_HEARTBEAT_DOWN}"
mark "  로그 'heartbeat 만료 회수 단계 실패' ${LOG_STAGE}회 / 'updatedAt 만으로 회수' ${LOG_BLIND}회"
say "로그 표본"
app_logs | grep -E 'heartbeat 저장소에 닿지 않아' | tail -2 | cut -c1-200 | sed 's/^/     /'

mark "Redis 재시작 — 이미 회수된 뒤이므로 추가 회수가 없어야 한다"
$DC start redis >/dev/null 2>&1
sleep 30
B_BLIND_UP="$(prom_num 'credit_job_recovery_total{detector="backstop_blind"}')"
B_BACKSTOP_UP="$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
B_HEARTBEAT_UP="$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
mark "Redis 복구 30초 후: blind=${B_BLIND_UP} backstop=${B_BACKSTOP_UP} heartbeat=${B_HEARTBEAT_UP} — $(job_status)"

# 회수된 job 이 재시도 대기(HOLDING)에 들어가 있는지 확인하고, 워커를 켜 종결까지 본다.
# 재기동이라 회수 카운터는 여기서 다시 0 이 된다 — 위에서 이미 읽어 뒀다.
B_PENDING_AGE="$(prom_num 'credit_job_oldest_pending_age_seconds')"
mark "워커를 켠다 (미결 최고 나이 ${B_PENDING_AGE}초)"
restart_app_with APP_WORKER_ENABLED=true
# 여기서는 게이지가 아니라 DB 를 본다. 방금 재기동했으므로 첫 스냅샷이 찍히기 전까지
# credit_hold_outstanding_count 는 초깃값 0 이고, 그 0 은 "미결이 없다"가 아니라
# "아직 아무도 안 셌다"이다 — 6단계 4번 시나리오가 찾은 바로 그 함정이다.
pending_rows() { mysql_q "SELECT COUNT(*) FROM jobs WHERE status IN ('HOLDING','PROCESSING');"; }
wait_until "미결 job(HOLDING+PROCESSING) 0건" 300 '[ "$(pending_rows)" = "0" ]'
B_SETTLE_WAIT="$WAITED"
B_END_STATUS="$(job_status)"
mark "B 종료 — ${B_END_STATUS}"

silence_check

report_row "A recovery{backstop}"              ">= 1"     "${A_BACKSTOP}"
report_row "A   감지까지(앱 UP 이후)"          "~60초"    "${A_BACKSTOP_WAIT}초"
report_row "A   감지까지(SIGKILL 이후)"        "-"        "${A_BACKSTOP_TOTAL}초"
report_row "A recovery{heartbeat}"             "0"        "${A_HEARTBEAT}"
report_row "A recovery{backstop_blind}"        "0 (회귀)" "${A_BLIND}"
report_row "A CreditBackstopRecovery firing"   "firing"   "$(alerts | grep -c CreditBackstopRecovery)건 (+${A_ALERT_WAIT}초)"
report_row "B Redis 다운 recovery{backstop_blind}" ">= 1"  "${B_BLIND_DOWN} (+${B_BLIND_WAIT}초)"
report_row "B Redis 다운 recovery{backstop}"   "0"        "${B_BACKSTOP_DOWN}"
report_row "B Redis 다운 recovery{heartbeat}"  "0"        "${B_HEARTBEAT_DOWN}"
report_row "B CreditBackstopBlindRecovery"     "firing"   "$(alerts | grep -c CreditBackstopBlindRecovery)건 (+${B_ALERT_WAIT}초)"
report_row "B   '단계 실패' ERROR"             "다수"     "${LOG_STAGE}회"
report_row "B   'updatedAt 만으로 회수' WARN"  "${PLANTED}회" "${LOG_BLIND}회"
report_row "B Redis 복구 후 추가 회수"         "없음"     "blind=${B_BLIND_UP} backstop=${B_BACKSTOP_UP}"
report_row "B 워커 재개 후 미결 0건까지"       "종결"     "${B_SETTLE_WAIT}초 / ${B_END_STATUS}"
report_row "invariant 4종 합"                  "0"        "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report
