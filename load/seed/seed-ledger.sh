#!/usr/bin/env bash
#
# 원장 대용량 시드 — PERF-04(원장 1억 행 상태에서 최근 내역 조회 p99 < 50ms) 측정용.
#
# API 로는 1억 행을 만들 수 없다. 직접 INSERT 한다.
#
# ── 넣는 것
# HOLD(-100) + CONFIRM(0) 쌍을 넣는다. 1억 행이면 쌍 5천만 개다.
# job_id 는 NULL 로 둔다. 대응하는 job 행이 실제로 없기 때문이다 — 있는 척하면
# countUnsettledTerminalJobs 가 아니라 사람이 속는다. 두 불변식 검사는 모두 jobs 에서
# 원장 쪽으로 걸어가므로(JobRepository.countJobsWithoutHoldEntry/countUnsettledTerminalJobs)
# job 없는 원장 행은 그 검사에 걸리지 않는다.
# idem_key 도 NULL 이다. uk_ledger_user_idem 은 MySQL 유니크라 NULL 을 여러 개 허용한다.
#
# ── 대사가 깨지지 않게 하는 산술 (INV-02 와 INV-01 둘 다)
# 현행 대사 공식은 사용자별 `balance == initial_balance + SUM(amount)` 다(인벤토리 3-3).
# 쌍 하나의 합은 -100 + 0 = -100 이므로, 쌍 N 개를 넣으면 SUM(amount) 가 100N 만큼 줄어든다.
# 공식을 다시 맞추는 방법은 둘인데, 고른 쪽은 **initial_balance 를 100N 올리는 것**이다:
#     initial_balance := initial_balance + 100 * N     (balance 는 건드리지 않는다)
#
# balance 를 100N 내리는 쪽(첫 판)은 버렸다. 그러면 시드 전 잔액이 100N 보다 적을 때
# 잔액이 음수가 되어 INV-01 이 깨지고(2026-09-23 실측으로 확인했다), 그것을 피하려면
# 1억 행 기준 50억 크레딧을 먼저 지급해야 해서 지급 API 를 5,000회 불러야 한다.
#
# initial_balance 를 올리는 것은 회계적으로도 이쪽이 맞다. 이 시드가 만드는 이야기는
# "이 사용자는 처음에 100N 만큼 더 들고 시작했고 그것을 N 번의 생성에 다 썼다" 이고,
# 그 이야기에서 지금 잔액은 시드 전과 같다. 두 불변식이 함께 지켜진다:
#     balance == (initial_balance + 100N) + (SUM - 100N)   → INV-02 통과
#     balance 는 변하지 않는다                              → INV-01 무관
#
# users 한 행의 UPDATE 다. V6 트리거는 ledger_entries 에만 걸려 있으므로 막히지 않는다.
#
# ── V6 트리거와의 관계
# V6 는 UPDATE/DELETE 만 막는다. 이 스크립트는 ledger_entries 에 INSERT 만 하므로 걸리지 않고,
# users 의 balance UPDATE 는 다른 테이블이라 무관하다.
#
# 사용법:
#   load/seed/seed-ledger.sh <userId> <쌍 개수> [배치당 쌍 개수]
#   load/seed/seed-ledger.sh 1 500000 25000     # 쌍 50만 = 행 100만
#
# 1억 행(쌍 5천만)을 한 번에 넣지 마라. 먼저 작은 규모로 재고 소요 시간을 환산해라.

set -euo pipefail

USER_ID="${1:?사용법: seed-ledger.sh <userId> <쌍 개수> [배치당 쌍 개수]}"
PAIRS="${2:?사용법: seed-ledger.sh <userId> <쌍 개수> [배치당 쌍 개수]}"
BATCH="${3:-25000}"

MYSQL=(docker compose exec -T mysql mysql -N -ucredit -pcredit credit_system)

exists=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM users WHERE id = $USER_ID" 2>/dev/null)
if [ "$exists" != "1" ]; then
  echo "사용자 $USER_ID 가 없다. 먼저 만들어라(로그인 1회 또는 INSERT)." >&2
  exit 1
fi

# 잔액은 건드리지 않으므로 INV-01 선행 검사가 필요 없다. 대신 시드 전 상태를 찍어 둔다 —
# 시드 뒤 대사가 틀리면 "시드가 깼나, 원래 틀려 있었나"를 이 출력으로 가른다.
BALANCE=$("${MYSQL[@]}" -e "SELECT balance FROM users WHERE id = $USER_ID" 2>/dev/null)
echo "  시드 전 balance=$BALANCE (시드는 이 값을 바꾸지 않는다)"

echo "원장 시드 시작: userId=$USER_ID, 쌍=${PAIRS}개(행 $(( PAIRS * 2 ))개), 배치=${BATCH}쌍"
before=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM ledger_entries" 2>/dev/null)
echo "  시작 시점 ledger_entries 행 수=$before"

started=$(date +%s)
done_pairs=0
while [ "$done_pairs" -lt "$PAIRS" ]; do
  chunk="$BATCH"
  left=$(( PAIRS - done_pairs ))
  [ "$left" -lt "$BATCH" ] && chunk="$left"

  # 재귀 CTE 로 1..chunk 를 만들고, HOLD/CONFIRM 두 행을 CROSS JOIN 해 한 문장에 2*chunk 행을 넣는다.
  "${MYSQL[@]}" 2>/dev/null <<SQL
SET SESSION cte_max_recursion_depth = $(( chunk + 1 ));
INSERT INTO ledger_entries (user_id, job_id, type, amount, idem_key, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $chunk
)
SELECT $USER_ID, NULL, t.type, t.amount, NULL, NOW(6)
FROM seq
CROSS JOIN (SELECT 'HOLD' AS type, -100 AS amount UNION ALL SELECT 'CONFIRM', 0) t;
SQL

  done_pairs=$(( done_pairs + chunk ))
  now=$(date +%s)
  elapsed=$(( now - started ))
  [ "$elapsed" -eq 0 ] && elapsed=1
  echo "  진행 $done_pairs/$PAIRS 쌍 (행 $(( done_pairs * 2 ))), ${elapsed}초, $(( done_pairs * 2 / elapsed )) 행/초"
done

elapsed=$(( $(date +%s) - started ))
[ "$elapsed" -eq 0 ] && elapsed=1

# 대사 보정. 위 산술 그대로다.
"${MYSQL[@]}" -e "UPDATE users SET initial_balance = initial_balance + 100 * $PAIRS, updated_at = NOW(6) WHERE id = $USER_ID" 2>/dev/null

after=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM ledger_entries" 2>/dev/null)
echo "원장 시드 완료: 넣은 행=$(( PAIRS * 2 )), 전체 행=$after, ${elapsed}초, $(( PAIRS * 2 / elapsed )) 행/초"

echo -n "대사 확인(0 이어야 한다): "
"${MYSQL[@]}" -e "
SELECT COUNT(*) FROM (
    SELECT u.id
    FROM users u LEFT JOIN ledger_entries l ON l.user_id = u.id
    GROUP BY u.id, u.balance, u.initial_balance
    HAVING u.balance <> u.initial_balance + COALESCE(SUM(l.amount), 0)
) mismatched" 2>/dev/null
