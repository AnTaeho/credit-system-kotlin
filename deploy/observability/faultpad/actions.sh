#!/usr/bin/env bash
# 장애 주입 버튼 패드가 실제로 손을 대는 곳.
#
# 버튼 하나 = 여기 case 하나 = 사고 하나. 6단계 시나리오 스크립트와 같은 lib.sh 를 쓴다.
# 스크립트와 버튼이 다른 코드로 같은 사고를 심으면 둘 중 하나는 반드시 낡는다.
#
# server.py 는 이 파일을 `actions.sh <action> [args...]` 로만 부른다. 셸 문자열을 만들어
# 넘기는 경로는 없다 — 허용 목록(catalog.json)에 있는 액션 이름과 검증된 인자만 온다.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../scenarios/lib.sh"

PAD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${PAD_DIR}/.state"
mkdir -p "$STATE_DIR"

ACTION="${1:-}"
shift || true

# ── state ────────────────────────────────────────────────────────────────────
# JSON 한 줄. 어느 컨테이너가 죽어 있어도 JSON 자체는 나와야 한다(값을 null 로).
# 화면 상단의 상태 띠가 이걸 3초마다 읽는다.
action_state() {
  local app_up redis_up mysql_up jobs balance zset envs pending_cnt pending_age

  app_up=false
  $DC exec -T app curl -fsS http://localhost:8081/actuator/health 2>/dev/null \
    | grep -q '"status":"UP"' && app_up=true

  redis_up=false
  [ "$($DC exec -T redis redis-cli ping 2>/dev/null | tr -d '\r')" = "PONG" ] && redis_up=true

  mysql_up=false
  [ "$(mysql_q 'SELECT 1;')" = "1" ] && mysql_up=true

  jobs=""; balance=""; pending_cnt=""; pending_age=""
  if [ "$mysql_up" = true ]; then
    jobs="$(job_status)"
    balance="$(mysql_q 'SELECT balance FROM organizations WHERE id=1;')"
    # 게이지와 나란히 놓고 볼 DB 쪽 진실. 04 의 "0 에 얼어붙은 게이지"는 이 값과 비교해야 보인다.
    pending_cnt="$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status IN ('HOLDING','PROCESSING','FAILED');")"
    pending_age="$(mysql_q "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), NOW(6)), 0) FROM jobs WHERE status IN ('HOLDING','PROCESSING','FAILED');")"
  fi

  zset=""
  [ "$redis_up" = true ] && zset="$($DC exec -T redis redis-cli ZCARD heartbeats 2>/dev/null | tr -d '\r')"

  # 컨테이너에 실제로 적용된 env. 서버 메모리의 값과 다르면 화면이 그 차이를 보여준다.
  envs=""
  [ "$app_up" = true ] && envs="$($DC exec -T app env 2>/dev/null | grep '^APP_' | tr '\n' ';')"

  APP_UP="$app_up" REDIS_UP="$redis_up" MYSQL_UP="$mysql_up" \
  JOBS="$jobs" BALANCE="$balance" ZSET="$zset" ENVS="$envs" \
  PENDING_CNT="$pending_cnt" PENDING_AGE="$pending_age" \
  python3 -c '
import json, os

def num(key):
    v = os.environ.get(key, "").strip()
    if not v:
        return None
    try:
        return int(v)
    except ValueError:
        return None

# "HOLDING=3 COMPLETED=5 " → {"HOLDING": 3, "COMPLETED": 5}
jobs_raw = os.environ.get("JOBS", "").strip()
jobs = None
if jobs_raw:
    jobs = {}
    for tok in jobs_raw.split():
        if "=" in tok:
            k, _, v = tok.partition("=")
            try:
                jobs[k] = int(v)
            except ValueError:
                pass

envs_raw = os.environ.get("ENVS", "").strip()
envs = None
if envs_raw:
    envs = {}
    for tok in envs_raw.split(";"):
        if "=" in tok:
            k, _, v = tok.partition("=")
            envs[k] = v

print(json.dumps({
    "app_up": os.environ["APP_UP"] == "true",
    "redis_up": os.environ["REDIS_UP"] == "true",
    "mysql_up": os.environ["MYSQL_UP"] == "true",
    "jobs": jobs,
    "balance": num("BALANCE"),
    "heartbeat_zset": num("ZSET"),
    "db_pending_count": num("PENDING_CNT"),
    "db_oldest_pending_age": num("PENDING_AGE"),
    "container_env": envs,
}, ensure_ascii=False))
'
}

# ── 스택 ─────────────────────────────────────────────────────────────────────
action_stack_up() {
  if ! ls "${REPO_ROOT}"/build/libs/*.jar >/dev/null 2>&1; then
    say "jar 가 없다"
    note "이미지는 저장소 루트의 Dockerfile 로 소스에서 직접 빌드된다. 첫 빌드는 몇 분 걸린다."
    return 1
  fi
  fresh_stack
  note "스택 준비 완료. Grafana http://localhost:3000, Prometheus http://localhost:9090"
}

action_stack_down() {
  say "스택을 내린다 (down -v — 볼륨까지)"
  $DC down -v
  note "내렸다"
}

# ── 트래픽 ───────────────────────────────────────────────────────────────────
# overdraw N — 잔액을 넘기는 요청 N 건. hold_balance{rejected} 를 올리는 것이 목적이다.
action_overdraw() {
  local n="$1" i key code
  key="overdraw-$(date +%s)-$RANDOM"
  say "잔액을 넘기는 job 요청 ${n}건 (현재 잔액 $(balance))"
  for i in $(seq 1 "$n"); do
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${API}/api/jobs" -H "$ORG_HEADER" \
      -H 'Content-Type: application/json' -d "{\"idemKey\":\"${key}-${i}\",\"prompt\":\"overdraw ${i}\"}")"
    note "  요청 ${i} → HTTP ${code}"
  done
  note "잔액=$(balance) — 잔액이 남아 있으면 앞쪽 몇 건은 통과한다(그게 정상이다)"
}

action_smoke() { "${SCEN_DIR}/../scripts/smoke.sh"; }

# ── 프로세스 사고 ────────────────────────────────────────────────────────────
action_crash_and_restart() {
  say "01 — PROCESSING 중인 프로세스를 SIGKILL 하고 즉시 재기동"
  reset_clock
  mark "PROCESSING = $(mysql_q "SELECT GROUP_CONCAT(CONCAT(id,':',attempt_no)) FROM jobs WHERE status='PROCESSING';")"
  mark "Redis ZSET: $($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') 개"
  kill_app
  mark "즉시 재기동"
  start_app
  mark "앱 UP. heartbeat 만료(10초)는 죽어 있는 동안에도 진행됐다 — 다음 스캔(5초)에서 잡혀야 한다"
}

# 02A — 익명 볼륨까지 갈아엎어야 ZSET 이 사라진다. restart 로는 RDB 가 살아난다.
action_amnesia() {
  say "02A — heartbeat 기억을 지운다 (SIGKILL → Redis 컨테이너+익명 볼륨 교체 → 재기동)"
  reset_clock
  mark "지우기 전 ZSET: $($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') 개"
  kill_app
  mark "Redis 컨테이너+익명 볼륨 교체 (redis:7 은 RDB 를 익명 볼륨에 남긴다)"
  $DC up -d --force-recreate --renew-anon-volumes redis >/dev/null 2>&1
  sleep 3
  mark "지운 뒤 ZSET: $($DC exec -T redis redis-cli ZCARD heartbeats | tr -d '\r') 개 (소실 확인)"
  mark "앱 재기동"
  start_app
  mark "앱 UP. 잡을 heartbeat 엔트리가 없으므로 backstop 만 남는다"
}

action_redis_stop()  { say "02B — Redis 정지"; $DC stop redis; note "정지했다. 앱은 살아 있다"; }
action_redis_start() { say "02B — Redis 복구"; $DC start redis; note "복구했다"; }

# 02B 의 SQL. "소유자가 사라진 job" 을 손으로 심는다 — updatedAt 타임아웃(60초)은 이미 지난 상태로.
action_plant_dead_processing() {
  say "02B — 죽은 PROCESSING 행 심기"
  local before after
  before="$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='HOLDING';")"
  mysql_q "UPDATE jobs SET status='PROCESSING', updated_at = NOW(6) - INTERVAL 120 SECOND WHERE status='HOLDING';" >/dev/null
  after="$(mysql_q "SELECT COUNT(*) FROM jobs WHERE status='PROCESSING';")"
  note "HOLDING ${before}건을 updated_at=120초 전의 PROCESSING 으로 바꿨다 (현재 PROCESSING=${after})"
  note "updatedAt 타임아웃(60초)이 이미 지났으므로 다음 스캔 주기(5초)에 잡혀야 한다"
}

# ── env 재기동 ───────────────────────────────────────────────────────────────
# 03/04/07/08 이 전부 이 하나로 처리된다. 서버가 현재 env 집합 전체를 넘긴다.
action_restart_app() {
  say "env 를 바꿔 앱만 다시 만든다: $*"
  note "재기동이므로 카운터는 전부 0 으로 리셋된다"
  restart_app_with "$@"
}

# ── 05 원장 훼손 ─────────────────────────────────────────────────────────────
# 훼손 전에 원복 정보를 .state/ 에 남긴다. 패드는 스크립트와 달리 눌러 놓고 오래 두는 물건이라
# 원복 정보를 셸 변수에 들고 있을 수가 없다.
save_good_balance() {
  local b
  b="$(mysql_q 'SELECT balance FROM organizations WHERE id=1;')"
  if [ -n "$b" ] && [ ! -f "${STATE_DIR}/balance.txt" ]; then
    echo "$b" > "${STATE_DIR}/balance.txt"
    note "훼손 전 balance=${b} 를 .state/balance.txt 에 저장했다"
  else
    note "이미 저장된 훼손 전 balance=$(cat "${STATE_DIR}/balance.txt" 2>/dev/null) 를 유지한다"
  fi
}

action_corrupt_balance_minus_one() {
  say "05 (a) — balance = balance - 1"
  save_good_balance
  mysql_q "UPDATE organizations SET balance = balance - 1 WHERE id=1;" >/dev/null
  note "1 크레딧을 원장 없이 증발시켰다. 잔액 = 최초 잔액 + 원장 합계 가 깨졌다"
  note "지금 balance=$(mysql_q 'SELECT balance FROM organizations WHERE id=1;') — 대사 주기 60초"
}

action_corrupt_balance_negative() {
  say "05 (b) — balance = -1"
  save_good_balance
  mysql_q "UPDATE organizations SET balance = -1 WHERE id=1;" >/dev/null
  note "조건부 UPDATE 의 잔액 가드가 뚫린 것과 같은 상태를 만들었다 — 스냅샷 주기 15초"
}

action_delete_hold_row() {
  say "05 (c) — HOLD 원장 1행 DELETE"
  local victim amt org
  victim="$(mysql_q "SELECT job_id FROM ledger_entries WHERE type='HOLD' ORDER BY id LIMIT 1;")"
  if [ -z "$victim" ]; then
    note "지울 HOLD 원장이 없다. 먼저 job 을 만들어라"
    return 1
  fi
  amt="$(mysql_q "SELECT amount FROM ledger_entries WHERE type='HOLD' AND job_id=${victim};")"
  org="$(mysql_q "SELECT organization_id FROM ledger_entries WHERE type='HOLD' AND job_id=${victim};")"
  mysql_q "DELETE FROM ledger_entries WHERE type='HOLD' AND job_id=${victim};" >/dev/null
  printf '%s\t%s\t%s\n' "$org" "$victim" "$amt" >> "${STATE_DIR}/deleted_hold.tsv"
  note "jobId=${victim} 의 HOLD 원장(amount=${amt})을 지웠다 — 돈을 안 묶고 처리된 job 이 됐다"
  note "복구 정보를 .state/deleted_hold.tsv 에 남겼다 — 스냅샷 주기 15초"
}

action_restore_ledger() {
  say "05 원복 — 알람이 resolve 되는 것까지 본다"
  local org job amt good
  if [ -f "${STATE_DIR}/deleted_hold.tsv" ]; then
    while IFS=$'\t' read -r org job amt; do
      [ -n "$job" ] || continue
      mysql_q "INSERT INTO ledger_entries (organization_id, job_id, type, amount, idem_key, created_at)
               VALUES (${org}, ${job}, 'HOLD', ${amt}, NULL, NOW(6));" >/dev/null
      note "HOLD 원장 재삽입: jobId=${job}, amount=${amt}"
    done < "${STATE_DIR}/deleted_hold.tsv"
    rm -f "${STATE_DIR}/deleted_hold.tsv"
  fi

  if [ -f "${STATE_DIR}/balance.txt" ]; then
    good="$(cat "${STATE_DIR}/balance.txt")"
    note "저장해 둔 훼손 전 balance=${good} 로 되돌린다"
    rm -f "${STATE_DIR}/balance.txt"
  else
    # 상태 파일이 없으면 등식 자체로 다시 계산한다: 잔액 = 최초 잔액 + 원장 합계.
    good="$(mysql_q "SELECT o.initial_balance + COALESCE((SELECT SUM(l.amount) FROM ledger_entries l WHERE l.organization_id = o.id), 0) FROM organizations o WHERE o.id=1;")"
    note "상태 파일이 없어 등식으로 재계산했다: initial_balance + SUM(ledger) = ${good}"
  fi
  [ -n "$good" ] && mysql_q "UPDATE organizations SET balance = ${good} WHERE id=1;" >/dev/null
  note "복구 완료. balance=$(mysql_q 'SELECT balance FROM organizations WHERE id=1;')"
  note "불변식은 다음 스냅샷(15초), 대사 불일치는 다음 대사(60초)에 0 으로 돌아온다"
}

# ── 06 중복 폭풍 ─────────────────────────────────────────────────────────────
action_duplicate_storm() {
  local n="$1" key before after
  before="$(mysql_q "SELECT balance FROM organizations WHERE id=1;")"
  key="storm-$(date +%s)-$RANDOM"
  say "06 — 같은 idemKey(${key}) 로 ${n}건 동시 발사"
  reset_clock
  seq 1 "$n" | xargs -P "$n" -I{} curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST "${API}/api/jobs" -H "$ORG_HEADER" -H 'Content-Type: application/json' \
    -d "{\"idemKey\":\"${key}\",\"prompt\":\"duplicate storm\"}" \
    | sort | uniq -c | sed 's/^/     HTTP /'
  after="$(mysql_q "SELECT balance FROM organizations WHERE id=1;")"
  mark "잔액 ${before} → ${after} (차이 $(( before - after )))"
  mark "job 행 총 $(mysql_q 'SELECT COUNT(*) FROM jobs;')건, HOLD 원장 총 $(mysql_q "SELECT COUNT(*) FROM ledger_entries WHERE type='HOLD';")건"
  note "카운터가 스크레이프될 때까지 12초 기다린다 (scrape_interval 5초)"
  sleep 12
  local hit uniq
  hit="$(prom_num 'credit_defense_total{point="idem_key",outcome="app_hit"}')"
  uniq="$(prom_num 'credit_defense_total{point="idem_key",outcome="db_unique"}')"
  mark "idem_key app_hit=${hit} db_unique=${uniq} (합 $(( ${hit%.*} + ${uniq%.*} )) — 기대 $(( n - 1 )))"
  mark "hold_balance rejected=$(prom_num 'credit_defense_total{point="hold_balance",outcome="rejected"}') (기대 0)"
}

# ── 디스패치 ─────────────────────────────────────────────────────────────────
case "$ACTION" in
  state)                     action_state ;;
  stack_up)                  action_stack_up ;;
  stack_down)                action_stack_down ;;
  charge)                    charge "${1:-10000}" ;;
  create_jobs)               say "job ${1:-5}건 생성"; create_jobs "${1:-5}" ;;
  overdraw)                  action_overdraw "${1:-7}" ;;
  smoke)                     action_smoke ;;
  kill_app)                  say "앱 SIGKILL (재기동하지 않는다)"; kill_app ;;
  start_app)                 say "앱 시작"; start_app ;;
  crash_and_restart)         action_crash_and_restart ;;
  amnesia)                   action_amnesia ;;
  redis_stop)                action_redis_stop ;;
  redis_start)               action_redis_start ;;
  plant_dead_processing)     action_plant_dead_processing ;;
  restart_app)               action_restart_app "$@" ;;
  corrupt_balance_minus_one) action_corrupt_balance_minus_one ;;
  corrupt_balance_negative)  action_corrupt_balance_negative ;;
  delete_hold_row)           action_delete_hold_row ;;
  restore_ledger)            action_restore_ledger ;;
  duplicate_storm)           action_duplicate_storm "${1:-100}" ;;
  *)
    echo "알 수 없는 액션: ${ACTION}" >&2
    exit 2
    ;;
esac
