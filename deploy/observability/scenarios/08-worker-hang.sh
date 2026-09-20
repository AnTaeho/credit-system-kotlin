#!/usr/bin/env bash
# 08 — 멈춘 워커 (타임아웃조차 먹지 않는 진짜 무응답)
#
# 심는 사고: APP_STUB_HANG=true. 생성 호출이 영원히 돌아오지 않는다. 07 의 "600초 지연" 과
#            다르다 — 지연은 타임아웃(20초)이 끊지만, 이건 끊을 지점 자체가 없다.
#            워커 스레드는 묶이고, 종지기 스레드는 워커 상태를 보지 않으므로 heartbeat 는
#            영원히 LIVE 다. step11-B 이전에는 두 그물(만료·정체 백스톱)이 모두 놓쳤고
#            돈이 held 에 영구히 묶였다.
#
# 반응해야 할 지표: credit_job_recovery_total{detector="hard_cap"} — 절대 상한이 돈을 푼다.
#            시도 3회를 다 쓰면 final_refund 로 잔액이 원복된다.
# **돌아오지 않는 것:** credit_worker_slots_free. 절대 상한은 돈만 푼다. 멈춘 스레드를 깨울
#            수단이 없으므로 슬롯은 재기동 전까지 하나씩 영구히 사라진다. 이 시나리오의
#            진짜 관측 대상이 그 누수이고, 사람에게 그것을 알리는 유일한 장치가
#            CreditWorkerSlotsExhausted 알람이다.
#
# ── 설정값을 왜 이렇게 골랐나 ────────────────────────────────────────────────
# 절대 상한 기본값은 300초다. 시도 3회면 회수만 15분이라 시나리오로 못 쓴다.
# 그래서 이 시나리오에서만 APP_PROCESSING_ABSOLUTE_TIMEOUT_SECONDS=90 으로 낮춘다.
#   - 90 인 이유: app.processing.timeout-seconds(60)보다 **커야 한다.** 같거나 작으면 기동이
#     거부되고, 통과하더라도 절대 상한이 후보 선정 기준을 덮어써 정상 job 까지 전부 회수한다
#   - app.generation.timeout-seconds(20)보다도 당연히 크다
#   - timeout-seconds 를 대신 낮추지 않는다. 그 값은 후보 선정 기준이자 **드레인 상한**
#     (GenerationWorkerLifecycle)이기도 해서, 건드리면 이 시나리오와 무관한 것이 함께 움직인다
#
# job 은 1건만 만든다. 3건을 만들면 슬롯 3개가 동시에 묶여 첫 회수 직후 재시도가 디스패치되지
# 못하고, "돈이 풀린다" 를 관측할 수 없게 된다(그 포화 상태는 이 스크립트 마지막에 따로 만든다).
#
# 예상 타임라인(절대 상한 90초, backoff 10초·40초):
#   T+0   attempt0 PROCESSING, slots 3 → 2
#   T+95  hard_cap 1 → FAILED → 재시도 대기 10초
#   T+105 attempt1 PROCESSING, slots 2 → 1
#   T+200 hard_cap 2 → 재시도 대기 40초
#   T+240 attempt2 PROCESSING, slots 1 → 0
#   T+335 hard_cap 3 → 시도 소진 → final_refund, 잔액 원복
#   T+340 job 1건 추가 → 슬롯이 없어 영원히 HOLDING (이 장치의 한계)
#   T+540 CreditWorkerSlotsExhausted firing (free==0 이 for:5m 지속)
# 알람이 실제로 fire 되는 것까지 기다린다. 그것이 이 누수의 유일한 인간 통보 경로라서,
# pending 까지만 보고 끝내면 정작 증명해야 할 것을 증명하지 못한다. 전체 약 10분.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ABS_CAP=90

fresh_stack
restart_app_with APP_STUB_HANG=true APP_PROCESSING_ABSOLUTE_TIMEOUT_SECONDS="$ABS_CAP"
reset_clock
mark "hang=true, 절대 상한 ${ABS_CAP}초로 재기동"
note "컨테이너에 실제로 들어간 값: $($DC exec -T app env | grep -E '^APP_(STUB_HANG|PROCESSING_ABSOLUTE)' | tr '\n' ' ')"

SLOTS_IDLE="$(prom_num 'credit_worker_slots_free')"
mark "사고 전 빈 슬롯 = ${SLOTS_IDLE}"

grant 10000
BEFORE="$(mysql_q "SELECT balance FROM users WHERE id=1;")"
mark "job 1건 생성 (잔액 ${BEFORE})"
create_jobs 1
JOBS_AT="$(date +%s)"

say "회수 3회를 지켜본다 (15초마다)"
for _ in $(seq 1 24); do
  sleep 15
  mark "slots_free=$(prom_num 'credit_worker_slots_free') hard_cap=$(prom_num 'credit_job_recovery_total{detector="hard_cap"}') outstanding=$(prom_num 'credit_hold_outstanding_count') zset=$($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') $(job_status)"
done

wait_until "final_refund 로 돈이 풀렸다" 180 '[ "$(prom_num "credit_defense_total{point=\"final_refund\",outcome=\"applied\"}")" != "0" ]'
REFUND_WAIT=$(( $(date +%s) - JOBS_AT ))
AFTER="$(mysql_q "SELECT balance FROM users WHERE id=1;")"
mark "환불까지 ${REFUND_WAIT}초, 잔액 ${BEFORE} → ${AFTER}"

HARD_CAP="$(prom_num 'credit_job_recovery_total{detector="hard_cap"}')"
SLOTS_AFTER="$(prom_num 'credit_worker_slots_free')"
ZSET_ORPHAN="$($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r')"
mark "회수 끝: hard_cap=${HARD_CAP} slots_free=${SLOTS_AFTER} (사고 전 ${SLOTS_IDLE}) 고아 ZSET=${ZSET_ORPHAN}"

# ── 한계 관측 ────────────────────────────────────────────────────────────────
# 슬롯이 전부 묶였으면 새 job 은 접수만 되고 영원히 디스패치되지 않는다.
# 돈은 풀렸지만 서비스는 멈췄다는 뜻이다. 이것이 절대 상한이 못 막는 것이다.
say "한계 확인 — 슬롯이 없는 상태에서 새 job 을 넣는다"
create_jobs 1
sleep 45
STUCK_HOLDING="$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='HOLDING';")"
STUCK_PROC="$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='PROCESSING';")"
mark "45초 뒤 HOLDING=${STUCK_HOLDING} PROCESSING=${STUCK_PROC} (슬롯이 0 이면 HOLDING 에서 늙는다)"

say "CreditWorkerSlotsExhausted 가 실제로 울릴 때까지 기다린다 (free==0 이 5분 지속)"
wait_until "CreditWorkerSlotsExhausted firing" 420 'alert_firing CreditWorkerSlotsExhausted'
SLOT_ALERT_WAIT="$WAITED"

say "DB: $(job_status)"
say "hard_cap 경고 로그"
app_logs | grep -c "절대 상한을 넘겨 회수" | sed 's/^/     "절대 상한을 넘겨 회수" 로그 줄: /'
silence_check

report_row "recovery{hard_cap}"              "3 (시도 3회)"      "${HARD_CAP}"
report_row "recovery{heartbeat}"             "0 (LIVE 라 못 잡는다)" "$(prom_num 'credit_job_recovery_total{detector="heartbeat"}')"
report_row "recovery{backstop}"              "0 (같은 이유)"     "$(prom_num 'credit_job_recovery_total{detector="backstop"}')"
report_row "final_refund/applied"            "1"                 "$(prom_num 'credit_defense_total{point="final_refund",outcome="applied"}')"
report_row "  환불까지(job 생성 이후)"       "~335초"            "${REFUND_WAIT}초"
report_row "잔액 (돈은 풀린다)"              "${BEFORE} 로 원복" "${AFTER}"
report_row "credit_worker_slots_free"        "0 (돌아오지 않는다)" "${SLOTS_AFTER} / 사고 전 ${SLOTS_IDLE}"
report_row "  CreditWorkerSlotsExhausted"    "firing (free==0 5분)" "${SLOT_ALERT_WAIT}초 만에 firing"
report_row "새 job 이 HOLDING 에 갇힌다"     "1 (슬롯 없음)"     "HOLDING=${STUCK_HOLDING} PROCESSING=${STUCK_PROC}"
report_row "고아 heartbeat ZSET"             "남는다 (무해)"     "${ZSET_ORPHAN}"
report_row "http 5xx"                        "0"                 "$(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)')"
report_row "invariant 4종 합"                "0"                 "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report_row "firing 알람"                     "SlotsExhausted 등" "[$(alerts | tr '\n' ' ')]"
report

note "주의: 이 스택을 내릴 때 app 종료가 느리다. 드레인이 멈춘 워커 스레드를 상한(60초)까지"
note "      기다렸다가 포기한다. 버그가 아니라 드레인이 설계대로 도는 것이다."
