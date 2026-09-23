#!/usr/bin/env bash
#
# 런 사이 초기화 — 부하 시나리오를 연달아 돌릴 때 앞 런의 잔재가 다음 런에 섞이지 않게 한다.
#
# 왜 필요한가. 한 런이 끝나도 HOLDING 이 수만 건 남아 6건/초로 빠진다(2026-09-23 Tier A 실측).
# 그 상태로 다음 런을 돌리면 워커와 커넥션 풀을 계속 갉아먹어, 측정한 지연이 이번 부하의
# 것인지 앞 런의 찌꺼기 때문인지 가를 수 없다.
#
# 무엇을 지우나. `docker compose down -v` 로 볼륨째 버리고 다시 만든다. TRUNCATE 가 아니라
# 볼륨을 버리는 이유는 users.initial_balance 와 ledger_entries 가 대사 공식으로 묶여 있어서다 —
# 한쪽만 지우면 INV-02 가 깨진 상태로 시작하게 된다. 스키마는 앱이 뜨면서 Flyway 가 다시 만든다.
#
# 사용법:
#   load/reset-and-seed.sh <프로파일 a|b> <계정당 크레딧>
#   load/reset-and-seed.sh b 6200000      # Tier A · 핫 계정용
#   load/reset-and-seed.sh b 23000000     # 생존 스파이크용
#
# 끝나면 앱이 떠 있고 계정 5개에 크레딧이 들어 있다.

set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:?사용법: reset-and-seed.sh <a|b> <계정당 크레딧>}"
CREDITS="${2:?사용법: reset-and-seed.sh <a|b> <계정당 크레딧>}"

COMPOSE=(docker compose -f docker-compose.yml -f load/docker-compose.load.yml)

echo "── 1. 앱 정지"
pkill -f 'credit-system-kotlin-.*-SNAPSHOT.jar' 2>/dev/null || true
for _ in $(seq 1 30); do pgrep -f 'credit-system-kotlin-.*-SNAPSHOT.jar' >/dev/null || break; sleep 1; done

echo "── 2. DB·Redis 를 볼륨째 버리고 다시 만든다"
"${COMPOSE[@]}" down -v >/dev/null 2>&1
"${COMPOSE[@]}" up -d mysql redis >/dev/null 2>&1
for _ in $(seq 1 60); do
  docker exec credit-system-kotlin-mysql-1 mysqladmin ping -uroot -proot --silent >/dev/null 2>&1 && break
  sleep 2
done
echo "   mysql 준비됨"

echo "── 3. 앱 기동 (프로파일 $PROFILE, Flyway 가 스키마를 다시 만든다)"
mkdir -p build/load-logs
nohup load/run-app.sh "$PROFILE" > "build/load-logs/app-${PROFILE}-$(date +%H%M%S).log" 2>&1 &
for _ in $(seq 1 120); do
  curl -fsS localhost:8081/actuator/health >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS localhost:8081/actuator/health >/dev/null || { echo "앱이 뜨지 않았다" >&2; exit 1; }
echo "   health OK"

echo "── 4. 계정 5개 생성"
for u in load01 load02 load03 load04 load05; do
  curl -s -o /dev/null -H "X-Dev-User: $u@local.test" localhost:8080/api/users/me/balance
done

echo "── 5. 계정당 ${CREDITS} 크레딧 지급"
# id 를 박아 두지 마라. 이 스크립트가 볼륨을 버리므로 AUTO_INCREMENT 가 1 부터 다시 시작하고,
# 계정이 만들어지는 순서도 위 루프와 지급 API 호출(운영자 행도 그때 생긴다)에 따라 달라진다.
# 2026-09-23 에 실제로 어긋나 운영자 계정에 크레딧이 들어갔다. 이메일로 찾는다.
for u in load01 load02 load03 load04 load05; do
  id=$(docker exec credit-system-kotlin-mysql-1 mysql -N -ucredit -pcredit credit_system \
    -e "SELECT id FROM users WHERE email = '${u}@local.test'" 2>/dev/null | tr -d '[:space:]')
  [ -n "$id" ] || { echo "계정을 찾지 못했다: ${u}@local.test" >&2; exit 1; }
  load/seed/grant-credits.sh "$id" "$CREDITS" >/dev/null 2>&1 || {
    echo "지급 실패: ${u}@local.test (userId=$id)" >&2; exit 1; }
done

docker exec credit-system-kotlin-mysql-1 mysql -N -ucredit -pcredit credit_system \
  -e "SELECT CONCAT('   ', email, ' = ', balance) FROM users ORDER BY id" 2>/dev/null

echo "── 준비 완료"
