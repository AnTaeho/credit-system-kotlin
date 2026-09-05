#!/usr/bin/env bash
# 방어 지점을 골고루 밟는 최소 트래픽을 만든다.
#
#   충전 → job 5건 → 같은 idemKey 로 1건 더(app_hit) → 잔액 넘게 요청(rejected)
#
# 스텁 실패율이 0.3 이라 job 몇 건은 FAILED → 재시도(retry_claim) → 최종 환불(final_refund)
# 로 흘러간다. 그 흔적은 마지막의 credit_defense_total 조회에서 확인한다.
set -euo pipefail

API="${API:-http://localhost:8080}"
PROM="${PROM:-http://localhost:9090}"
ORG_HEADER="X-Organization-Id: 1"
STAMP="$(date +%s)"

# post <label> <path> <json>
post() {
  local label="$1" path="$2" body="$3" code
  code="$(curl -s -o /tmp/smoke_body -w '%{http_code}' -X POST "${API}${path}" \
    -H "$ORG_HEADER" -H 'Content-Type: application/json' -d "$body")"
  printf '  %-8s HTTP %s %s\n' "$label" "$code" "$(cat /tmp/smoke_body)"
}

echo "== 1000 크레딧 충전"
post charge /api/organizations/me/charge "{\"idemKey\":\"charge-${STAMP}\",\"amount\":1000}"

echo "== job 5건 생성 (건당 100)"
for i in 1 2 3 4 5; do
  post "job$i" /api/jobs "{\"idemKey\":\"job-${STAMP}-${i}\",\"prompt\":\"smoke prompt ${i}\"}"
done

echo "== 같은 idemKey 로 한 번 더 (idem_key/app_hit 유발)"
post "job1-재" /api/jobs "{\"idemKey\":\"job-${STAMP}-1\",\"prompt\":\"smoke prompt 1\"}"

echo "== 잔액을 넘겨 요청 (hold_balance/rejected 유발)"
for i in $(seq 6 12); do
  post "job$i" /api/jobs "{\"idemKey\":\"job-${STAMP}-${i}\",\"prompt\":\"overdraw ${i}\"}"
done

echo "== 잔액"
curl -s "${API}/api/organizations/me/balance" -H "$ORG_HEADER"; echo

echo "== Prometheus 에서 본 방어 카운터"
curl -s --get "${PROM}/api/v1/query" --data-urlencode 'query=credit_defense_total' \
  | python3 -c "
import json, sys
for r in sorted(json.load(sys.stdin)['data']['result'], key=lambda r: (r['metric']['point'], r['metric']['outcome'])):
    m = r['metric']
    print('  %-14s %-10s %s' % (m['point'], m['outcome'], r['value'][1]))
"
