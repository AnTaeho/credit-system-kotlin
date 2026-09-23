#!/usr/bin/env bash
#
# 크레딧 시드 — 부하 실행의 선행조건(요구서 1-5).
#
# Tier A(접수 500 RPS, 10분)는 500 × 600 × 100 = 30,000,000 크레딧을 쓴다.
# 운영자 지급 1회 상한은 app.admin.max-grant-amount: 1000000 이므로 최소 30회 호출해야 한다.
# 시드가 모자라면 부하 중에 409 INSUFFICIENT_BALANCE 가 나는데, 그것은 부하 결과가 아니라
# 시드 실패다. 그래서 여기서 실패하면 즉시 멈추고 응답 본문을 찍는다.
#
# 전제: 앱이 local 프로필로 떠 있어야 한다(개발 로그인 X-Dev-User 헤더).
#   docker compose up -d
#   SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
#
# 사용법:
#   load/seed/grant-credits.sh <userId> [총액] [1회 지급액]
#   load/seed/grant-credits.sh 1 30000000 1000000
#
# 환경변수: BASE_URL(기본 http://localhost:8080), ADMIN_EMAIL(기본 admin@local.test)

set -euo pipefail

USER_ID="${1:?사용법: grant-credits.sh <userId> [총액] [1회 지급액]}"
TOTAL="${2:-30000000}"
PER_GRANT="${3:-1000000}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@local.test}"
# idemKey 는 호출마다 달라야 한다. 같으면 두 번째부터 duplicate=true 로 되돌아오고
# 잔액이 오르지 않는다 — 멱등이 의도대로 동작하는 것이지 실패가 아니다.
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)-$$}"

if [ "$PER_GRANT" -le 0 ] || [ "$TOTAL" -le 0 ]; then
  echo "총액과 1회 지급액은 양수여야 한다: total=$TOTAL per=$PER_GRANT" >&2
  exit 2
fi

CALLS=$(( (TOTAL + PER_GRANT - 1) / PER_GRANT ))
echo "크레딧 시드 시작: userId=$USER_ID, 총액=$TOTAL, 1회=$PER_GRANT, 호출=${CALLS}회, runId=$RUN_ID"

remaining="$TOTAL"
i=0
started=$(date +%s)
while [ "$remaining" -gt 0 ]; do
  i=$(( i + 1 ))
  amount="$PER_GRANT"
  [ "$remaining" -lt "$PER_GRANT" ] && amount="$remaining"

  body=$(printf '{"idemKey":"seed-%s-%d","amount":%d}' "$RUN_ID" "$i" "$amount")
  response=$(curl -sS -o /tmp/grant-body.$$ -w '%{http_code}' \
    -X POST "$BASE_URL/api/admin/users/$USER_ID/grants" \
    -H 'Content-Type: application/json' \
    -H "X-Dev-User: $ADMIN_EMAIL" \
    --data "$body")

  if [ "$response" != "200" ]; then
    echo "지급 실패: ${i}번째 호출이 HTTP $response" >&2
    cat /tmp/grant-body.$$ >&2
    echo >&2
    rm -f /tmp/grant-body.$$
    exit 1
  fi

  echo "  [$i/$CALLS] +$amount → $(cat /tmp/grant-body.$$)"
  rm -f /tmp/grant-body.$$
  remaining=$(( remaining - amount ))
done

elapsed=$(( $(date +%s) - started ))
echo "크레딧 시드 완료: ${CALLS}회, ${elapsed}초"

# 확인. 지급 API 는 지급 후 잔액을 돌려주지만, 그 값이 아니라 DB 를 다시 읽어 본다 —
# 대상 사용자가 로그인한 본인이 아니라서 GET /api/users/me/balance 로는 볼 수 없다.
echo -n "확인(DB): "
docker compose exec -T mysql mysql -N -ucredit -pcredit credit_system \
  -e "SELECT CONCAT('userId=', id, ' balance=', balance, ' initialBalance=', initial_balance) FROM users WHERE id = $USER_ID" 2>/dev/null
