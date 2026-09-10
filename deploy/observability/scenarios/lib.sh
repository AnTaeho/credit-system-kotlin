#!/usr/bin/env bash
# 6단계 장애 주입 시나리오 공용 함수.
#
# 각 시나리오 스크립트는 이 파일을 source 하고, fresh_stack 으로 깨끗한 스택을 올린 뒤
# 사고를 심고, wait_until 로 "감지까지 걸린 초"를 실측한다.
#
# 여기서 export 하는 env(APP_*)는 전부 restart_app_with 안에서만 살고, 호출한 셸에는 남지 않는다.

set -uo pipefail

SCEN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCEN_DIR}/../../.." && pwd)"
COMPOSE_FILE="${SCEN_DIR}/../docker-compose.yml"
DC="docker compose -f ${COMPOSE_FILE}"

API="${API:-http://localhost:8080}"
PROM="${PROM:-http://localhost:9090}"
ORG_HEADER="X-Organization-Id: 1"

# ── 출력 ─────────────────────────────────────────────────────────────────────
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
tstamp() { date +%H:%M:%S; }

# T+n 형식의 타임라인. scenario_start 를 기준으로 잰다.
SCENARIO_T0="$(date +%s)"
mark() { printf '   T+%-4s %s\n' "$(( $(date +%s) - SCENARIO_T0 ))" "$*"; }
reset_clock() { SCENARIO_T0="$(date +%s)"; }

# ── Prometheus ───────────────────────────────────────────────────────────────
# prom '<promql>' → 첫 시계열의 값 하나. 결과가 없으면 빈 문자열.
prom() {
  curl -s --get "${PROM}/api/v1/query" --data-urlencode "query=$1" \
    | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)["data"]["result"]
except Exception:
    print(""); sys.exit(0)
print(d[0]["value"][1] if d else "")
'
}

# prom_num — prom 의 결과를 숫자로. 빈 값이면 0.
prom_num() { local v; v="$(prom "$1")"; [ -n "$v" ] || v=0; printf '%s' "$v"; }

# prom_all '<promql>' → "라벨셋<TAB>값" 여러 줄
prom_all() {
  curl -s --get "${PROM}/api/v1/query" --data-urlencode "query=$1" \
    | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)["data"]["result"]
except Exception:
    sys.exit(0)
for r in sorted(d, key=lambda r: sorted(r["metric"].items())):
    m = {k: v for k, v in r["metric"].items() if k not in ("__name__", "application", "instance", "job")}
    print("%-46s %s" % (r["metric"].get("__name__", "") + str(m), r["value"][1]))
'
}

# alerts → firing 중인 알람 이름(중복 제거)
alerts() {
  curl -s "${PROM}/api/v1/alerts" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)["data"]["alerts"]
except Exception:
    sys.exit(0)
print("\n".join(sorted({a["labels"]["alertname"] for a in d if a["state"] == "firing"})))
'
}

# alerts_pending → pending 상태(for 를 채우는 중)인 알람 이름
alerts_pending() {
  curl -s "${PROM}/api/v1/alerts" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)["data"]["alerts"]
except Exception:
    sys.exit(0)
print("\n".join(sorted({a["labels"]["alertname"] for a in d if a["state"] == "pending"})))
'
}

alert_firing() { alerts | grep -qx "$1"; }

# ── 대기 ─────────────────────────────────────────────────────────────────────
# wait_until "설명" 타임아웃초 'shell 조건'
#   조건이 참이 될 때까지 2초 간격으로 폴링하고, 걸린 초를 출력한다.
#   이 초가 곧 "감지 지연" 실측값이다. 마지막 실측치는 $WAITED 에 남는다.
WAITED=0
wait_until() {
  local desc="$1" timeout="$2" cond="$3" start now
  start="$(date +%s)"
  while :; do
    if eval "$cond"; then
      now="$(date +%s)"; WAITED=$(( now - start ))
      printf '   [%3ds] %s — 참\n' "$WAITED" "$desc"
      return 0
    fi
    now="$(date +%s)"
    if [ $(( now - start )) -ge "$timeout" ]; then
      WAITED=$(( now - start ))
      printf '   [%3ds] %s — 타임아웃(조건 거짓)\n' "$WAITED" "$desc"
      return 1
    fi
    sleep 2
  done
}

# 그냥 기다리면서 10초마다 지표를 찍는다. watch_for 초 지표 [지표...]
watch_for() {
  local secs="$1"; shift
  local start now line
  start="$(date +%s)"
  while :; do
    now="$(date +%s)"
    [ $(( now - start )) -ge "$secs" ] && break
    line=""
    for q in "$@"; do line="${line} $(prom_num "$q")"; done
    printf '   T+%-4s %s\n' "$(( now - SCENARIO_T0 ))" "$line"
    sleep 10
  done
}

# ── 스택 ─────────────────────────────────────────────────────────────────────
wait_app_up() {
  local i
  for i in $(seq 1 90); do
    if $DC exec -T app curl -fsS http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  echo "앱이 UP 되지 않았다" >&2
  return 1
}

# fresh_stack — 볼륨까지 지우고 새로 올린다. 이전 시나리오의 카운터가 새지 않게.
fresh_stack() {
  say "깨끗한 스택을 올린다 (down -v → up -d --build)"
  $DC down -v >/dev/null 2>&1
  $DC up -d --build >/dev/null
  wait_app_up
  "${SCEN_DIR}/../scripts/seed.sh" >/dev/null
  note "스택 준비 완료 ($(tstamp))"
  reset_clock
}

# restart_app_with VAR=val ... — env 를 붙여 app 컨테이너만 다시 만든다.
# export 는 서브셸 안에서만 일어나 호출한 셸을 오염시키지 않는다.
restart_app_with() {
  local kvs=("$@")
  ( for kv in "${kvs[@]}"; do export "${kv?}"; done
    $DC up -d --force-recreate app >/dev/null )
  wait_app_up
  note "앱 재기동 완료: ${kvs[*]:-기본값}"
}

kill_app() {
  $DC kill -s SIGKILL app >/dev/null 2>&1
  note "앱 SIGKILL ($(tstamp))"
}

start_app() { $DC start app >/dev/null; wait_app_up; note "앱 재기동 완료 ($(tstamp))"; }

app_logs()   { $DC logs --no-log-prefix app 2>/dev/null; }
# 자격증명은 루트 docker-compose.yml 의 계약과 같다(step8-D). 훼손 시나리오의 UPDATE/DELETE 까지
# credit 사용자로 충분하다 — MYSQL_USER 는 MYSQL_DATABASE 에 ALL 권한을 받는다.
DB_USER="${DB_USER:-credit}"; DB_PASSWORD="${DB_PASSWORD:-credit}"; DB_NAME="${DB_NAME:-credit_system}"
mysql_q()    { $DC exec -T mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N -B -e "$1" 2>/dev/null; }
job_status() { mysql_q "SELECT status, COUNT(*) FROM jobs GROUP BY status;" | tr '\t' '=' | tr '\n' ' '; }

# ── 트래픽 ───────────────────────────────────────────────────────────────────
STAMP=""
charge() {
  STAMP="$(date +%s)-$RANDOM"
  curl -s -o /dev/null -X POST "${API}/api/organizations/me/charge" -H "$ORG_HEADER" \
    -H 'Content-Type: application/json' -d "{\"idemKey\":\"charge-${STAMP}\",\"amount\":${1:-10000}}"
  note "충전 ${1:-10000}, 잔액=$(balance)"
}

balance() { curl -s "${API}/api/organizations/me/balance" -H "$ORG_HEADER"; }

# create_jobs N — job N 건 생성. 충전은 호출자가 미리 해 둔다.
create_jobs() {
  local n="$1" i key
  key="$(date +%s)-$RANDOM"
  for i in $(seq 1 "$n"); do
    curl -s -o /dev/null -X POST "${API}/api/jobs" -H "$ORG_HEADER" \
      -H 'Content-Type: application/json' -d "{\"idemKey\":\"job-${key}-${i}\",\"prompt\":\"fault injection ${i}\"}"
  done
  note "job ${n}건 생성 ($(tstamp))"
}

# ── 보고 ─────────────────────────────────────────────────────────────────────
REPORT_ROWS=()
report_row() { REPORT_ROWS+=("$1|$2|$3"); }   # 항목 | 기대 | 관측
report() {
  printf '\n\033[1m== 기대 vs 관측\033[0m\n'
  printf '   %-44s %-26s %s\n' "항목" "기대" "관측"
  printf '   %-44s %-26s %s\n' "$(printf '%.0s-' {1..44})" "$(printf '%.0s-' {1..26})" "--------------------"
  local r
  for r in "${REPORT_ROWS[@]}"; do
    IFS='|' read -r a b c <<<"$r"
    printf '   %-44s %-26s %s\n' "$a" "$b" "$c"
  done
  printf '\n'
}

# 침묵해야 할 방어 카운터 묶음을 한 번에 찍는다.
silence_check() {
  printf '\n   [침묵 확인] 방어 카운터 / 불변식\n'
  prom_all 'credit_defense_total' | sed 's/^/     /'
  prom_all 'credit_job_recovery_total' | sed 's/^/     /'
  printf '     invariant negative=%s jobs_without_hold=%s unsettled=%s mismatch=%s\n' \
    "$(prom_num 'credit_invariant_negative_balance_orgs')" \
    "$(prom_num 'credit_invariant_jobs_without_hold')" \
    "$(prom_num 'credit_invariant_unsettled_terminal_jobs')" \
    "$(prom_num 'credit_ledger_reconciliation_mismatch')"
  printf '     up=%s  5xx=%s\n' \
    "$(prom_num 'up{job="credit_system"}')" \
    "$(prom_num 'sum(http_server_requests_seconds_count{status=~"5.."}) or vector(0)')"
}
