#!/usr/bin/env bash
# 06 — 중복 폭풍
#
# 심는 사고: 같은 idemKey 로 100건을 동시에 던진다(재시도 폭주 / 더블 클릭의 극단).
# 반응해야 할 지표: idem_key{app_hit} + idem_key{db_unique} = 99
# 침묵해야 할 지표: hold_balance{rejected} = 0. 잔액은 정확히 100 만 줄어야 한다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

fresh_stack
charge 20000
BEFORE="$(mysql_q "SELECT balance FROM organizations WHERE id=1;")"
BASE_HOLD="$(prom_num 'credit_defense_total{point="hold_balance",outcome="applied"}')"

KEY="storm-$(date +%s)"
reset_clock
mark "같은 idemKey(${KEY}) 로 100건 동시 발사"
seq 1 100 | xargs -P 100 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST "${API}/api/jobs" -H "$ORG_HEADER" -H 'Content-Type: application/json' \
  -d "{\"idemKey\":\"${KEY}\",\"prompt\":\"duplicate storm\"}" \
  | sort | uniq -c | sed 's/^/     HTTP /'
AFTER="$(mysql_q "SELECT balance FROM organizations WHERE id=1;")"
mark "발사 완료. 잔액 ${BEFORE} → ${AFTER} (차이 $(( BEFORE - AFTER )))"
mark "이 idemKey 로 만들어진 job 행: $(mysql_q "SELECT COUNT(*) FROM jobs;")건, HOLD 원장: $(mysql_q "SELECT COUNT(*) FROM ledger_entries WHERE type='HOLD';")건"

# 카운터가 스크레이프될 때까지 기다린다(scrape_interval 5초)
sleep 12
APP_HIT="$(prom_num 'credit_defense_total{point="idem_key",outcome="app_hit"}')"
DB_UNIQUE="$(prom_num 'credit_defense_total{point="idem_key",outcome="db_unique"}')"
HOLD_DELTA=$(( $(prom_num 'credit_defense_total{point="hold_balance",outcome="applied"}') - BASE_HOLD ))
REJECTED="$(prom_num 'credit_defense_total{point="hold_balance",outcome="rejected"}')"

say "방어 카운터"
prom_all 'credit_defense_total' | sed 's/^/     /'

say "job 이 처리될 때까지 기다린 뒤의 최종 상태"
wait_until "미결 job 0건" 180 '[ "$(prom_num "credit_hold_outstanding_count")" = "0" ]'
mark "$(job_status) 잔액=$(balance)"
silence_check

report_row "app_hit + db_unique"        "99"     "$(( ${APP_HIT%.*} + ${DB_UNIQUE%.*} ))  (app_hit=${APP_HIT}, db_unique=${DB_UNIQUE})"
report_row "hold_balance/applied 증가분" "1"      "${HOLD_DELTA}"
report_row "hold_balance/rejected"      "0"      "${REJECTED}"
report_row "잔액 변화"                  "-100"   "$(( AFTER - BEFORE ))"
report_row "생성된 job 행"              "1"      "$(mysql_q "SELECT COUNT(*) FROM jobs;")"
report_row "invariant 4종 합"           "0"      "$(prom_num 'credit_invariant_negative_balance_orgs + credit_invariant_jobs_without_hold + credit_invariant_unsettled_terminal_jobs + credit_ledger_reconciliation_mismatch')"
report_row "firing 알람"                "없음"   "[$(alerts | tr '\n' ' ')]"
report
