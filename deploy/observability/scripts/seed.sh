#!/usr/bin/env bash
# 사용자 1명(id=1)을 직접 INSERT 한다.
#
# 사용자 생성 API 가 없어서 SQL 로 넣는다. 테이블은 앱 기동 때 Flyway 마이그레이션이
# 만들므로, 앱이 UP 이 될 때까지 기다린 다음에 넣어야 한다.
set -euo pipefail

COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docker-compose.yml"
DC="docker compose -f ${COMPOSE_FILE}"

# 루트 docker-compose.yml 의 계약과 같은 자격증명이다(step8-D).
DB_USER="${DB_USER:-credit}"; DB_PASSWORD="${DB_PASSWORD:-credit}"; DB_NAME="${DB_NAME:-credit_system}"

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

echo "사용자 id=1 을 넣는다..."
$DC exec -T mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -e "
  INSERT INTO users (id, name, balance, initial_balance, created_at, updated_at)
  VALUES (1, 'seed-org', 0, 0, NOW(6), NOW(6))
  ON DUPLICATE KEY UPDATE name = VALUES(name);
" 2>/dev/null

$DC exec -T mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -e \
  "SELECT id, name, balance, initial_balance FROM users;" 2>/dev/null
