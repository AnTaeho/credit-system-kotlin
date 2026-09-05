#!/usr/bin/env bash
# 05 — 원장 훼손 (HTTP 200 인 채로 나는 사고)
#
# 심는 사고: 정상 처리가 끝난 뒤 DB 를 직접 깬다.
#   (a) balance 를 1 깎는다        → 대사 불일치
#   (b) balance 를 -1 로 만든다    → 음수 잔액
#   (c) 어떤 job 의 HOLD 원장 삭제 → hold 없는 job
# 반응해야 할 지표: mismatch, negative_balance_orgs, jobs_without_hold
# 침묵해야 할 지표: 방어 카운터 전부. up=1, 5xx=0 — 애플리케이션은 완벽히 건강하다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

fresh_stack
charge 10000
create_jobs 5
# 게이지가 아니라 DB 로 판정한다. 스냅샷이 아직 한 번도 안 돌았으면 게이지는 초깃값 0 이라
# "미결 0건"으로 보이는데, 그건 4단계 시나리오가 보여준 바로 그 함정이다.
wait_until "job 5건이 DB 에서 전부 종결" 300 \
  '[ "$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status NOT IN ('"'"'COMPLETED'"'"','"'"'REFUNDED'"'"');")" = "0" ]'
wait_until "스냅샷·대사가 각각 한 번 이상 돌았다" 120 \
  '[ "$(prom_num "credit_snapshot_staleness_seconds")" != "-1" ] && [ "$(prom_num "credit_ledger_reconciliation_staleness_seconds")" != "-1" ]'
mark "정상 처리 완료 — $(job_status), 잔액=$(balance)"

# 스크레이프 한 주기(5초)를 더 흘려보내고 기준선을 잡는다. 그러지 않으면 마지막 job 의
# 카운터 증가분이 아직 안 긁힌 채로 기준선이 되어, 훼손과 무관한 +1 이 섞인다.
sleep 12
DEFENSE_BASE="$(prom_num 'sum(credit_defense_total)')"
GOOD_BALANCE="$(mysql_q "SELECT balance FROM organizations WHERE id=1;")"
note "훼손 전 balance = ${GOOD_BALANCE}"

# ── (a) 대사 불일치 ──────────────────────────────────────────────────────────
say "(a) UPDATE organizations SET balance = balance - 1 WHERE id=1"
reset_clock
mysql_q "UPDATE organizations SET balance = balance - 1 WHERE id=1;" >/dev/null
mark "1 크레딧을 원장 없이 증발시켰다. 잔액 = 최초 잔액 + 원장 합계 가 깨졌다"
wait_until "mismatch > 0" 180 '[ "$(prom_num "credit_ledger_reconciliation_mismatch")" != "0" ]'
A_WAIT="$WAITED"; A_MISMATCH="$(prom_num 'credit_ledger_reconciliation_mismatch')"
wait_until "CreditLedgerReconciliationMismatch firing" 60 'alert_firing CreditLedgerReconciliationMismatch'
mark "mismatch=${A_MISMATCH}, ${A_WAIT}초 만에 감지. firing=[$(alerts | tr '\n' ' ')]"
note "대사 주기가 60초이므로 최악 60초, 평균 30초가 이 훼손의 감지 지연이다"

# ── (b) 음수 잔액 ────────────────────────────────────────────────────────────
say "(b) UPDATE organizations SET balance = -1"
reset_clock
mysql_q "UPDATE organizations SET balance = -1 WHERE id=1;" >/dev/null
mark "조건부 UPDATE 의 잔액 가드가 뚫린 것과 같은 상태를 만들었다"
wait_until "negative_balance_orgs > 0" 60 '[ "$(prom_num "credit_invariant_negative_balance_orgs")" != "0" ]'
B_WAIT="$WAITED"; B_NEG="$(prom_num 'credit_invariant_negative_balance_orgs')"
wait_until "CreditNegativeBalanceOrgs firing" 30 'alert_firing CreditNegativeBalanceOrgs'
mark "negative_balance_orgs=${B_NEG}, ${B_WAIT}초 만에 감지 (스냅샷 주기 15초)"

# ── (c) HOLD 원장 삭제 ───────────────────────────────────────────────────────
say "(c) 어떤 job 의 HOLD 원장 1행 DELETE"
reset_clock
VICTIM="$(mysql_q "SELECT job_id FROM ledger_entries WHERE type='HOLD' ORDER BY id LIMIT 1;")"
VICTIM_AMT="$(mysql_q "SELECT amount FROM ledger_entries WHERE type='HOLD' AND job_id=${VICTIM};")"
VICTIM_ORG="$(mysql_q "SELECT organization_id FROM ledger_entries WHERE type='HOLD' AND job_id=${VICTIM};")"
mysql_q "DELETE FROM ledger_entries WHERE type='HOLD' AND job_id=${VICTIM};" >/dev/null
mark "jobId=${VICTIM} 의 HOLD 원장(amount=${VICTIM_AMT})을 지웠다 — 돈을 안 묶고 처리된 job 이 됐다"
wait_until "jobs_without_hold > 0" 60 '[ "$(prom_num "credit_invariant_jobs_without_hold")" != "0" ]'
C_WAIT="$WAITED"; C_NOHOLD="$(prom_num 'credit_invariant_jobs_without_hold')"
wait_until "CreditJobsWithoutHold firing" 30 'alert_firing CreditJobsWithoutHold'
mark "jobs_without_hold=${C_NOHOLD}, ${C_WAIT}초 만에 감지"

say "세 사고가 겹친 시점의 전체 그림"
for m in credit_ledger_reconciliation_mismatch credit_invariant_negative_balance_orgs \
         credit_invariant_jobs_without_hold credit_invariant_unsettled_terminal_jobs; do
  printf '     %-44s %s\n' "$m" "$(prom_num "$m")"
done
note "firing: [$(alerts | tr '\n' ' ')]"
silence_check
DEFENSE_AFTER="$(prom_num 'sum(credit_defense_total)')"

# ── 원복 ─────────────────────────────────────────────────────────────────────
say "원복 — 알람이 resolve 되는 것까지 확인한다"
reset_clock
mysql_q "INSERT INTO ledger_entries (organization_id, job_id, type, amount, idem_key, created_at)
         VALUES (${VICTIM_ORG}, ${VICTIM}, 'HOLD', ${VICTIM_AMT}, NULL, NOW(6));" >/dev/null
mysql_q "UPDATE organizations SET balance = ${GOOD_BALANCE} WHERE id=1;" >/dev/null
mark "HOLD 원장 재삽입 + balance=${GOOD_BALANCE} 복구"
wait_until "불변식 3종이 전부 0" 90 '[ "$(prom_num "credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold")" = "0" ]'
R1="$WAITED"
wait_until "mismatch 가 0" 180 '[ "$(prom_num "credit_ledger_reconciliation_mismatch")" = "0" ]'
R2="$WAITED"
wait_until "firing 알람 0개" 120 '[ -z "$(alerts)" ]'
mark "전부 resolve — 스냅샷 ${R1}초 / 대사 ${R2}초 / 알람 ${WAITED}초"

report_row "(a) mismatch"                     "1, <=60초"  "${A_MISMATCH}, ${A_WAIT}초"
report_row "(b) negative_balance_orgs"        "1, <=15초"  "${B_NEG}, ${B_WAIT}초"
report_row "(c) jobs_without_hold"            "1, <=15초"  "${C_NOHOLD}, ${C_WAIT}초"
report_row "방어 카운터 합 (훼손 전→후)"     "증가 0"     "${DEFENSE_BASE} → ${DEFENSE_AFTER}"
report_row "up"                               "1"          "$(prom_num 'up{job="credit_system"}')"
report_row "http 5xx"                         "0"          "$(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)')"
report_row "원복 후 firing 알람"              "0개"        "[$(alerts | tr '\n' ' ')]"
report
