#!/usr/bin/env bash
# 시드 계정 dev@local.test 가 id=1 로 만들어졌는지 확인한다. 행을 넣지 않는다.
#
# 계정은 앱이 만든다. local 프로필은 기동이 끝난 직후 application-local.yml 의 시드 계정 둘을 순서대로
# 만든다(dev@local.test → admin@local.test). 새 DB 에서는 dev 가 id=1, admin 이 id=2 가 된다.
# 시나리오 SQL 과 지급 경로(/api/admin/users/1/grants)는 전부 users.id=1 을 가정하므로,
# 그 가정이 맞는지 여기서 한 번 확인하고 틀리면 실패로 끝낸다.
#
# 예전에는 이 스크립트가 id=1 행을 직접 INSERT 했다. 지금 그렇게 하면 비밀번호 없는 행이 생겨
# 그 계정으로 토큰을 받을 수 없다. 그래서 확인만 한다.
#
# 시드 계정은 앱이 UP 이 된 뒤에 생기므로, UP 을 기다린 다음 행이 보일 때까지 잠깐 더 기다린다.
set -euo pipefail

COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docker-compose.yml"
DC="docker compose -f ${COMPOSE_FILE}"

# 루트 docker-compose.yml 의 계약과 같은 자격증명이다.
DB_USER="${DB_USER:-credit}"; DB_PASSWORD="${DB_PASSWORD:-credit}"; DB_NAME="${DB_NAME:-credit_system}"
DEV_EMAIL="${DEV_EMAIL:-dev@local.test}"

mysql_q() { $DC exec -T mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" "$@" 2>/dev/null; }

echo "앱이 UP 이 될 때까지 대기한다..."
for i in $(seq 1 60); do
  # 관리 포트는 호스트로 publish 되지 않는다. 컨테이너 안에서 확인한다.
  if $DC exec -T app curl -fsS http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "앱 UP (${i}회 시도)"
    break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "앱이 UP 되지 않았다" >&2; exit 1; fi
done

echo "시드 계정 ${DEV_EMAIL} 을 기다린다..."
row=""
for i in $(seq 1 30); do
  # "id<TAB>비밀번호 있음(1/0)"
  row="$(mysql_q -N -B -e "SELECT id, password_hash IS NOT NULL FROM users WHERE email = '${DEV_EMAIL}';" || true)"
  [ -n "$row" ] && break
  sleep 1
done

if [ -z "$row" ]; then
  echo "시드 계정 ${DEV_EMAIL} 이 없다. 앱이 local 프로필로 떴는지 확인한다(SPRING_PROFILES_ACTIVE=local)." >&2
  exit 1
fi

id="${row%%$'\t'*}"; has_password="${row##*$'\t'}"
if [ "$id" != "1" ]; then
  echo "${DEV_EMAIL} 의 id 가 ${id} 다. 시나리오는 id=1 을 가정한다. 볼륨을 지우고 새로 올린다(down -v → up -d)." >&2
  exit 1
fi
if [ "$has_password" != "1" ]; then
  echo "${DEV_EMAIL} 에 비밀번호가 없다. 이 계정으로는 토큰을 받을 수 없다. 볼륨을 지우고 새로 올린다(down -v → up -d)." >&2
  exit 1
fi

mysql_q -e "SELECT id, email, role, balance, initial_balance FROM users;"
