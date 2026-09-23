#!/usr/bin/env bash
#
# PERF-04 측정 — 원장이 커질수록 최근 내역 조회가 어떻게 되는가.
#
# 재는 쿼리는 앱의 커서 페이징과 같은 모양이다
# (`LedgerRepository.findByUserIdOrderByIdDesc` / `...AndIdLessThan...`):
#     SELECT ... FROM ledger_entries WHERE user_id = ? ORDER BY id DESC LIMIT 20
#     SELECT ... FROM ledger_entries WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT 20
#
# 인덱스는 `idx_ledger_user_id (user_id)` 단일 컬럼이다(V1). InnoDB 세컨더리 인덱스는
# PK 를 접미로 가지므로 실질적으로 (user_id, id) 처럼 쓰일 수 있다 — 그래서 부족한지는
# EXPLAIN 없이 단정할 수 없다는 것이 `git show req-v3:docs/02-design.md` 2절 gap 표 PERF-04 행의 판단이었다. 여기서 확정한다.
#
# 사용법: load/measure-ledger-read.sh <userId> <반복 횟수>

set -euo pipefail
cd "$(dirname "$0")/.."

USER_ID="${1:?사용법: measure-ledger-read.sh <userId> <반복 횟수>}"
N="${2:-200}"
MYSQL=(docker exec -i credit-system-kotlin-mysql-1 mysql -N -ucredit -pcredit credit_system)
MYSQL_V=(docker exec -i credit-system-kotlin-mysql-1 mysql -ucredit -pcredit credit_system)

rows=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM ledger_entries" 2>/dev/null)
echo "── 원장 행 수: $rows"

echo "── EXPLAIN (첫 페이지)"
"${MYSQL_V[@]}" -e "EXPLAIN SELECT id, user_id, job_id, type, amount, created_at FROM ledger_entries WHERE user_id = $USER_ID ORDER BY id DESC LIMIT 20\G" 2>/dev/null | grep -E "select_type|table:|type:|possible_keys|key:|key_len|rows:|filtered|Extra" | sed 's/^/   /'

echo "── EXPLAIN (커서 다음 페이지)"
maxid=$("${MYSQL[@]}" -e "SELECT MAX(id) FROM ledger_entries WHERE user_id = $USER_ID" 2>/dev/null)
cursor=$(( maxid / 2 ))
"${MYSQL_V[@]}" -e "EXPLAIN SELECT id, user_id, job_id, type, amount, created_at FROM ledger_entries WHERE user_id = $USER_ID AND id < $cursor ORDER BY id DESC LIMIT 20\G" 2>/dev/null | grep -E "select_type|table:|type:|possible_keys|key:|key_len|rows:|filtered|Extra" | sed 's/^/   /'

# 지연 측정. 커서를 매번 다르게 줘서 버퍼 풀에 한 구간만 남는 것을 피한다.
echo "── 지연 측정 ${N}회 (커서 무작위)"
"${MYSQL[@]}" -e "
SET @maxid = (SELECT MAX(id) FROM ledger_entries WHERE user_id = $USER_ID);
SELECT 'warmup', COUNT(*) FROM (SELECT id FROM ledger_entries WHERE user_id = $USER_ID ORDER BY id DESC LIMIT 20) w;
" >/dev/null 2>&1

python3 - "$USER_ID" "$N" <<'PY'
import subprocess, sys, time, random, statistics

user_id, n = sys.argv[1], int(sys.argv[2])
base = ["docker", "exec", "-i", "credit-system-kotlin-mysql-1",
        "mysql", "-N", "-ucredit", "-pcredit", "credit_system", "-e"]

maxid = int(subprocess.run(base + [f"SELECT MAX(id) FROM ledger_entries WHERE user_id = {user_id}"],
                           capture_output=True, text=True).stdout.strip() or 0)
if maxid == 0:
    print("   행이 없다"); sys.exit(0)

# mysql 프로세스 기동 비용이 측정에 섞이지 않게, 한 세션에서 여러 쿼리를 돌리고
# 서버가 보고하는 실행 시간(profiling 대신 반복 후 총 경과 / 횟수)을 쓴다.
queries = []
for _ in range(n):
    cur = random.randint(21, maxid)
    queries.append(f"SELECT id FROM ledger_entries WHERE user_id = {user_id} AND id < {cur} "
                   f"ORDER BY id DESC LIMIT 20;")
script = "\n".join(queries)

t0 = time.monotonic()
subprocess.run(base[:-1] + ["-e", script], capture_output=True, text=True)
elapsed = time.monotonic() - t0

# 세션 기동 비용을 빼기 위해 빈 세션 한 번을 따로 잰다.
t1 = time.monotonic()
subprocess.run(base + ["SELECT 1"], capture_output=True, text=True)
overhead = time.monotonic() - t1

per = (elapsed - overhead) / n * 1000
print(f"   {n}회 총 {elapsed:.2f}초 (세션 기동 {overhead*1000:.0f}ms 제외) → 쿼리당 평균 {per:.3f}ms")
PY
