#!/usr/bin/env bash
#
# PERF-04 용 원장 대용량 시드 — **여러 사용자에 분산**한다.
#
# 왜 분산이 필요한가 (2026-09-23 실측으로 알게 된 것).
# `seed-ledger.sh` 는 한 사용자에게 전부 넣는다. 그렇게 1천만 행을 넣고 재니 옵티마이저가
# 세컨더리 인덱스를 쓰지 않고 **PRIMARY 역방향 스캔**을 골랐다. 그 사용자가 사실상 모든 행을
# 갖고 있어서 id 를 역순으로 훑기만 하면 바로 맞는 행이 나오기 때문이다. 쿼리당 1.27ms 가
# 나왔지만 이것은 시드 방식이 만든 값이지 이 인덱스의 성질이 아니다.
#
# 실제 서비스에서는 한 사용자가 전체의 극히 일부만 갖는다. 그때 PK 역방향 스캔은
# 남의 행을 계속 건너뛰어야 하므로 옵티마이저는 `idx_ledger_user_id` 를 써야 하고,
# **그 경로가 빠른지가 PERF-04 가 묻는 것**이다.
#
# 대사(INV-02)를 지키는 방법은 `seed-ledger.sh` 와 같다 — 넣은 쌍 수만큼 각 사용자의
# initial_balance 를 올린다(잔액은 건드리지 않는다).
#
# 사용법:
#   load/seed/seed-ledger-spread.sh <사용자 수> <사용자당 쌍 개수> [배치당 쌍]
#   load/seed/seed-ledger-spread.sh 1000 50000 100000    # 1,000명 × 5만쌍 = 1억 행

set -euo pipefail
cd "$(dirname "$0")/../.."

USERS="${1:?사용법: seed-ledger-spread.sh <사용자 수> <사용자당 쌍 개수> [배치당 쌍]}"
PER="${2:?사용법: seed-ledger-spread.sh <사용자 수> <사용자당 쌍 개수> [배치당 쌍]}"
BATCH="${3:-100000}"

MYSQL=(docker exec -i credit-system-kotlin-mysql-1 mysql -N -ucredit -pcredit credit_system)

TOTAL_PAIRS=$(( USERS * PER ))
echo "분산 시드: 사용자 ${USERS}명 × ${PER}쌍 = 쌍 ${TOTAL_PAIRS}개(행 $(( TOTAL_PAIRS * 2 ))개)"

# 1. 시드 전용 사용자를 만든다. email·google_sub 은 NULL 로 둔다 —
#    MySQL 유니크 키는 NULL 을 여러 개 허용하므로 서로 충돌하지 않는다.
echo "── 사용자 생성"
"${MYSQL[@]}" -e "
INSERT INTO users (name, balance, initial_balance, created_at, updated_at)
SELECT CONCAT('seed', n), 0, 0, NOW(6), NOW(6) FROM (
  SELECT (a.i + b.i * 10 + c.i * 100 + d.i * 1000) AS n
  FROM (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
       (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
       (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
       (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
) x WHERE n < $USERS;" 2>/dev/null

read -r MINID MAXID <<< "$("${MYSQL[@]}" -e "
SELECT MIN(id), MAX(id) FROM users WHERE name LIKE 'seed%'" 2>/dev/null)"
echo "   시드 사용자 id ${MINID}~${MAXID}"

# 2. 원장 행을 넣는다. user_id 는 시드 사용자 구간을 순환한다.
#    HOLD(-100) + CONFIRM(0) 쌍이라 사용자당 합이 -100 × PER 이다.
echo "── 원장 삽입"
started=$(date +%s)
done_pairs=0
while [ "$done_pairs" -lt "$TOTAL_PAIRS" ]; do
  remaining=$(( TOTAL_PAIRS - done_pairs ))
  chunk=$(( remaining < BATCH ? remaining : BATCH ))
  # 재귀 CTE 기본 깊이는 1000 이다. 배치 크기만큼 올려 준다(세션 한정).
  "${MYSQL[@]}" -e "
  SET SESSION cte_max_recursion_depth = $(( chunk + 10 ));
  INSERT INTO ledger_entries (user_id, job_id, type, amount, idem_key, created_at)
  WITH RECURSIVE seq(n) AS (
    SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n + 1 < $chunk
  )
  SELECT $MINID + ((n + $done_pairs) % $USERS), NULL, t.type, t.amount, NULL,
         NOW(6) - INTERVAL (n % 86400) SECOND
  FROM seq
  CROSS JOIN (SELECT 'HOLD' AS type, -100 AS amount
              UNION ALL SELECT 'CONFIRM', 0) t;" 2>&1 | grep -v "insecure" || true
  done_pairs=$(( done_pairs + chunk ))
  now=$(date +%s); elapsed=$(( now - started )); [ "$elapsed" -eq 0 ] && elapsed=1
  echo "   진행 ${done_pairs}/${TOTAL_PAIRS} 쌍 (행 $(( done_pairs * 2 ))), ${elapsed}초, $(( done_pairs * 2 / elapsed )) 행/초"
done

# 3. 대사 보정. 사용자마다 같은 수를 넣었으므로 같은 값을 올린다.
echo "── 대사 보정 (initial_balance += 100 × ${PER})"
"${MYSQL[@]}" -e "
UPDATE users SET initial_balance = initial_balance + 100 * $PER, updated_at = NOW(6)
WHERE name LIKE 'seed%';" 2>/dev/null

elapsed=$(( $(date +%s) - started ))
[ "$elapsed" -eq 0 ] && elapsed=1
after=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM ledger_entries" 2>/dev/null)
echo "완료: 전체 원장 행=${after}, ${elapsed}초, $(( TOTAL_PAIRS * 2 / elapsed )) 행/초"

echo -n "대사 확인(0 이어야 한다): "
"${MYSQL[@]}" -e "
SELECT COUNT(*) FROM (
  SELECT u.id FROM users u LEFT JOIN ledger_entries l ON l.user_id = u.id
  GROUP BY u.id, u.balance, u.initial_balance
  HAVING u.balance <> u.initial_balance + COALESCE(SUM(l.amount), 0)
) m;" 2>/dev/null
