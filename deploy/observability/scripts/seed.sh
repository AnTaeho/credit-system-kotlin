#!/usr/bin/env bash
# 조직 1개(id=1)를 직접 INSERT 한다.
#
# 조직 생성 API 가 없어서 SQL 로 넣는다. 테이블은 Hibernate ddl-auto=update 가 앱 기동 때
# 만들므로, 앱이 UP 이 될 때까지 기다린 다음에 넣어야 한다.
set -euo pipefail

COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docker-compose.yml"
DC="docker compose -f ${COMPOSE_FILE}"

echo "앱이 UP 이 될 때까지 대기한다..."
for i in $(seq 1 60); do
  # 관리 포트는 호스트로 publish 되지 않는다. 컨테이너 안에서 확인한다.
  if $DC exec -T app curl -fsS http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "앱 UP (${i}회 시도)"
    break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "앱이 UP 되지 않았다"; exit 1; fi
done

echo "조직 id=1 을 넣는다..."
$DC exec -T mysql mysql -uroot -pan902318 credit_system -e "
  INSERT INTO organizations (id, name, balance, initial_balance, created_at, updated_at)
  VALUES (1, 'seed-org', 0, 0, NOW(6), NOW(6))
  ON DUPLICATE KEY UPDATE name = VALUES(name);
" 2>/dev/null

$DC exec -T mysql mysql -uroot -pan902318 credit_system -e \
  "SELECT id, name, balance, initial_balance FROM organizations;" 2>/dev/null
