#!/usr/bin/env bash
# 04 — 스케줄러 정지 (관측 장치 자신이 멈춘다)
#
# 심는 사고: app.scheduling.enabled=false. 스냅샷·대사·회수·워커가 전부 사라진다.
# 반응해야 할 지표: 두 staleness — 다만 규칙이 옳아야만 반응한다.
# 침묵해야 할 지표: oldest_pending_age 는 얼어붙는다. 이게 staleness 가 필요한 이유다.
#
# 이 스크립트는 규칙 수정 전/후 두 번 돌린다. 출력 맨 위에 지금 로드된 expr 을 찍는다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

loaded_expr() {
  curl -s "${PROM}/api/v1/rules" | python3 -c '
import json, sys
for g in json.load(sys.stdin)["data"]["groups"]:
    for r in g["rules"]:
        if r.get("name") in ("CreditSnapshotStale", "CreditReconciliationStale"):
            print("     %-26s expr=%s  for=%ss" % (r["name"], r["query"], r["duration"]))
'
}

fresh_stack
say "지금 Prometheus 에 로드된 staleness 규칙"
loaded_expr

restart_app_with APP_SCHEDULING_ENABLED=false
reset_clock
mark "스케줄러 정지 상태로 재기동 (@ConditionalOnExpression 때문에 워커도 같이 사라진다)"

charge 10000
mark "job 3건 생성 — 아무도 처리하지 않는다"
create_jobs 3

say "2분 동안 지켜본다"
for _ in 1 2 3 4 5 6 7 8; do
  sleep 15
  mark "snapshot_staleness=$(prom_num 'credit_snapshot_staleness_seconds') recon_staleness=$(prom_num 'credit_ledger_reconciliation_staleness_seconds') age=$(prom_num 'credit_job_oldest_pending_age_seconds') count=$(prom_num 'credit_hold_outstanding_count') firing=[$(alerts | tr '\n' ' ')]"
done

SNAP="$(prom_num 'credit_snapshot_staleness_seconds')"
RECON="$(prom_num 'credit_ledger_reconciliation_staleness_seconds')"
AGE="$(prom_num 'credit_job_oldest_pending_age_seconds')"
CNT="$(prom_num 'credit_hold_outstanding_count')"
REAL_AGE="$(mysql_q "SELECT TIMESTAMPDIFF(SECOND, MIN(created_at), NOW(6)) FROM jobs WHERE status IN ('HOLDING','PROCESSING','FAILED');")"
CYCLES="$(prom_num 'credit_snapshot_cycles_total')"

say "지표가 말하는 것과 DB 가 말하는 것"
note "게이지 oldest_pending_age = ${AGE}초 / DB 실제 미결 나이 = ${REAL_AGE}초"
note "게이지 outstanding_count  = ${CNT} / DB 실제 미결 = $(mysql_q "SELECT COUNT(*) FROM jobs WHERE status IN ('HOLDING','PROCESSING','FAILED');")"
note "snapshot_cycles_total = ${CYCLES} (스냅샷이 한 번도 안 돌았다)"

FIRING="$(alerts | tr '\n' ' ')"
mark "firing 알람: [${FIRING}]"

report_row "snapshot_staleness"               "규칙 검증 대상"  "${SNAP}"
report_row "reconciliation_staleness"         "규칙 검증 대상"  "${RECON}"
report_row "snapshot_cycles_total"            "0"               "${CYCLES}"
report_row "oldest_pending_age (게이지)"      "얼어붙음"        "${AGE}초"
report_row "  같은 시점 DB 실제 미결 나이"    "-"               "${REAL_AGE}초"
report_row "CreditSnapshotStale"              "firing 이어야"   "$(alerts | grep -cx CreditSnapshotStale)"
report_row "CreditReconciliationStale"        "firing 이어야"   "$(alerts | grep -cx CreditReconciliationStale)"
report_row "firing 알람 전체"                 "-"               "[${FIRING}]"
report_row "방어 카운터 합"                   "job 생성분 3 뿐"  "$(prom_num 'sum(credit_defense_total)')"
report_row "recovery 합"                      "0"               "$(prom_num 'sum(credit_job_recovery_total)')"
report
