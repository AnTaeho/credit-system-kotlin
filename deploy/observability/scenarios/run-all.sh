#!/usr/bin/env bash
# 장애 주입 시나리오 8개를 순서대로 돌린다. 40~60분 걸린다.
#
# 08(멈춘 워커)은 step11 에서 더해졌다. 혼자 10분쯤 쓴다 — 슬롯 누수 알람이 실제로
# fire 되는 것(free==0 이 5분 지속)까지 기다리기 때문이다.
#
#   ./deploy/observability/scenarios/run-all.sh
#
# 각 스크립트는 시작할 때 fresh_stack(down -v) 을 하므로 독립 실행도 된다.
# 마지막에 스택을 내린다 — Testcontainers 를 쓰는 테스트와 포트를 다투기 때문이다.
set -uo pipefail
SCEN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for s in "${SCEN_DIR}"/0[1-8]-*.sh; do
  printf '\n\n\033[1;44m  %s  \033[0m\n' "$(basename "$s")"
  bash "$s"
done

printf '\n\033[1m== 스택 정리\033[0m\n'
docker compose -f "${SCEN_DIR}/../docker-compose.yml" down -v
docker ps --filter name=observability
