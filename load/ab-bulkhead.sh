#!/usr/bin/env bash
#
# 벌크헤드 A/B — 접수가 커넥션을 독점하는 것을 막으면 배경 작업이 사는가.
#
# 같은 바이너리로 두 번 돈다. 다른 것은 환경변수 하나뿐이다.
#   A: APP_DB_INTAKE_PERMITS=0   (꺼짐 = 현재 동작)
#   B: APP_DB_INTAKE_PERMITS=8   (접수는 최대 8, 나머지 2는 배경 작업 몫)
#
# 비교하는 것은 지연이 아니라 **배경 작업이 살아 있는가** 다:
#   - credit_snapshot_staleness_seconds   (게이지가 얼마나 낡았나)
#   - 게이지가 말하는 적체 vs DB 실제값   (계기판이 맞나)
#   - 부하 중 완료된 job 수               (워커가 도나)
# 그 대가로 접수 지연이 얼마나 나빠지는지를 함께 본다.
#
# 사용법: load/ab-bulkhead.sh <permits> <라벨>

set -euo pipefail
cd "$(dirname "$0")/.."

PERMITS="${1:?사용법: ab-bulkhead.sh <permits> <라벨>}"
LABEL="${2:?사용법: ab-bulkhead.sh <permits> <라벨>}"
RATE="${RATE:-500}"
DUR="${DUR:-3m}"
OUT="build/load-logs/ab-${LABEL}.txt"
MYSQL=(docker exec -i credit-system-kotlin-mysql-1 mysql -N -ucredit -pcredit credit_system)

export APP_DB_INTAKE_PERMITS="$PERMITS"
export LOAD_USERS='load01@local.test,load02@local.test,load03@local.test,load04@local.test,load05@local.test'

echo "── ${LABEL}: permits=${PERMITS}, ${RATE} RPS ${DUR}" | tee "$OUT"
load/reset-and-seed.sh b 2500000 >/dev/null 2>&1

# 켜짐 여부를 동작으로 확인한다. 꺼져 있으면 미터 자체가 없다.
if curl -s --max-time 5 localhost:8081/actuator/prometheus | grep -q "^credit_intake_bulkhead_permits_used"; then
  echo "   벌크헤드 미터 있음 (켜짐)" | tee -a "$OUT"
else
  echo "   벌크헤드 미터 없음 (꺼짐)" | tee -a "$OUT"
fi

# 부하 중 샘플링
(
  for _ in $(seq 1 8); do
    sleep 20
    printf "[%s] " "$(date +%H:%M:%S)"
    curl -s --max-time 5 localhost:8081/actuator/prometheus 2>/dev/null \
      | grep -E "^(credit_snapshot_staleness_seconds|credit_hold_outstanding_count|hikaricp_connections_pending|credit_intake_bulkhead_permits_used)\{" \
      | sed 's/{[^}]*}//' | awk '{printf "%s=%s ", $1, $2}'
    "${MYSQL[@]}" -e "SELECT CONCAT('db_holding=', (SELECT COUNT(*) FROM jobs WHERE status='HOLDING'),
      ' db_done=', (SELECT COUNT(*) FROM jobs WHERE status='COMPLETED'));" 2>/dev/null
  done
) >> "$OUT" 2>&1 &
SAMPLER=$!

USERS="$LOAD_USERS" WRITE_RATE="$RATE" READ_RATE=$((RATE * 3)) DURATION="$DUR" RUN_ID="ab-$LABEL" \
  k6 run load/k6/01-tier-a.js > "build/load-logs/ab-${LABEL}-k6.txt" 2>&1 || true
sed -n '/── 01 Tier A/,$p' "build/load-logs/ab-${LABEL}-k6.txt" >> "$OUT"

wait "$SAMPLER" 2>/dev/null || true

echo "── 부하 종료 직후" | tee -a "$OUT"
curl -s --max-time 5 localhost:8081/actuator/prometheus 2>/dev/null \
  | grep -E "^(credit_snapshot_staleness_seconds|credit_hold_outstanding_count|credit_intake_bulkhead_wait_seconds_(count|sum))" \
  | sed 's/{[^}]*}//' | tee -a "$OUT"
"${MYSQL[@]}" -e "SELECT CONCAT('db_holding=', (SELECT COUNT(*) FROM jobs WHERE status='HOLDING'),
  ' db_done=', (SELECT COUNT(*) FROM jobs WHERE status='COMPLETED'));" 2>/dev/null | tee -a "$OUT"

echo "결과: $OUT"
