#!/usr/bin/env bash
# 사용자 1명(id=1, email=dev@local.test)을 직접 INSERT 한다.
#
# 사용자 행은 첫 로그인 때 만들어지지만(step9-B), 시나리오 SQL 은 전부 users.id=1 을 가정한다.
# 그래서 id 를 못 박아 미리 넣어 둔다. 개발 로그인(X-Dev-User: dev@local.test)은 이메일로
# 사용자를 찾으므로 이 행을 찾아 id=1 로 들어온다. 운영자(admin@local.test)는 첫 요청 때
# 개발 로그인이 새 행(id=2)으로 만든다 — 지급하는 쪽이라 id 를 가정하는 곳이 없다.
#
# 테이블은 앱 기동 때 Flyway 마이그레이션이 만들므로, 앱이 UP 이 될 때까지 기다린 다음에 넣어야 한다.
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
  INSERT INTO users (id, name, email, balance, initial_balance, created_at, updated_at)
  VALUES (1, 'dev', 'dev@local.test', 0, 0, NOW(6), NOW(6))
  ON DUPLICATE KEY UPDATE name = VALUES(name), email = VALUES(email);
" 2>/dev/null

$DC exec -T mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -e \
  "SELECT id, name, email, balance, initial_balance FROM users;" 2>/dev/null
