# 관측 스택 (Prometheus)

로컬 관측 스택이다. 알람 기준은 [`prometheus/rules/credit.rules.yml`](prometheus/rules/credit.rules.yml) 의 주석에 있다.

## 전제

- Docker (Compose v2)

앱 이미지는 저장소 루트의 `Dockerfile`(멀티스테이지)로 만들어진다. 빌더 스테이지가
이미지 안에서 `bootJar` 까지 돌리므로 호스트에 JDK 도, 미리 만든 jar 도 필요 없다.

호스트의 3306(MySQL)·6379(Redis)는 건드리지 않는다 — 스택의 MySQL/Redis 는 포트를
publish 하지 않고 compose 네트워크 안에서만 산다.

## 올리기 / 내리기

```
# 올리기 (저장소 루트에서)
docker compose -f deploy/observability/docker-compose.yml up -d --build

# 상태 확인 — 4개 서비스(mysql, redis, app, prometheus)가 전부 healthy/running 이 될 때까지
docker compose -f deploy/observability/docker-compose.yml ps

# 내리기 (볼륨까지)
docker compose -f deploy/observability/docker-compose.yml down -v
```

## 데이터 넣기

```
# 시드 계정 dev@local.test 가 id=1 로 생겼는지 확인 — 시나리오 SQL 이 id=1 을 가정한다. 행은 앱이 만든다
./deploy/observability/scripts/seed.sh

# 운영자 지급 + job 생성 + 중복/잔액부족 유발, 마지막에 방어 카운터 출력
./deploy/observability/scripts/smoke.sh
```

### 누구로 요청하나

앱은 `local` 프로필로 뜬다(`SPRING_PROFILES_ACTIVE: local`). 이 프로필은 기동이 끝난 직후
`application-local.yml` 의 시드 계정 둘을 만든다. 스크립트는 이 계정으로 액세스 토큰을 받아
`Authorization: Bearer <토큰>` 헤더로 요청한다.

| 계정 | 비밀번호 | 누구 | 쓰는 곳 |
|---|---|---|---|
| `dev@local.test` | `local-dev-password` | 일반 사용자. 새 DB 에서 id=1 | job 생성, 잔액·목록 조회 |
| `admin@local.test` | `local-admin-password` | 운영자(ADMIN). 새 DB 에서 id=2 | 지급 `POST /api/admin/users/1/grants` |

비밀번호는 저장소에 공개된 로컬 전용 고정값이다. 화면은 <http://localhost:8080/login> 에서 같은 계정으로 들어간다.

토큰은 `POST /auth/token` 이 준다. 수명은 15분이고, 만료되면 다시 받는다. 시드 계정은 앱이 UP 이 된
직후에 생기므로 기동 직후 잠깐은 401 이 난다. `scenarios/lib.sh` 의 `ensure_tokens` 가 재시도와
10분마다 다시 받기를 맡는다. Bearer 요청은 CSRF 검사에서 빠지므로 curl 에 CSRF 토큰이 필요 없다.

결제 없는 자기 충전 API 는 없다. 크레딧은 운영자 지급으로만 생긴다(1회 상한 1,000,000).

```
token() {  # token <이메일> <비밀번호>
  curl -s http://localhost:8080/auth/token -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'
}
ADMIN=$(token admin@local.test local-admin-password)
DEV=$(token dev@local.test local-dev-password)

curl -X POST http://localhost:8080/api/admin/users/1/grants \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"idemKey":"grant-1","amount":10000}'
curl http://localhost:8080/api/users/me/balance -H "Authorization: Bearer $DEV"
```

## 접속

| | URL |
|---|---|
| 애플리케이션 API | http://localhost:8080 |
| Prometheus | http://localhost:9090 (`/targets`, `/graph`, `/alerts`) |

`http://localhost:8080/actuator/prometheus` 는 **404 다.** 관리 포트(8081)는 호스트로
publish 하지 않기 때문이다. 지표를 눈으로 보려면:

```
docker compose -f deploy/observability/docker-compose.yml exec app \
  curl -s http://localhost:8081/actuator/prometheus | grep credit_
```

## 지표와 알람 보기

지표와 알람은 Prometheus 화면에서 본다.

| 화면 | 보는 것 |
|---|---|
| <http://localhost:9090/targets> | `credit_system` 스크레이프가 UP 인지 |
| <http://localhost:9090/alerts> | 알람 규칙 12개의 상태(inactive → pending → firing) |
| <http://localhost:9090/graph> | 식을 넣고 Graph 탭에서 곡선으로 본다 |

`/graph` 에 넣어 볼 식:

```
# 가장 오래 기다린 PENDING job 의 나이(초). 워커가 멈추면 계속 오른다
credit_job_oldest_pending_age_seconds

# 원장 대사 불일치 건수. 0 이어야 한다
credit_ledger_reconciliation_mismatch

# 방어 지점별 최근 5분 증가량
sum by (point, outcome) (increase(credit_defense_total[5m]))

# 회수가 어느 감지기로 일어났나
increase(credit_job_recovery_total[10m])
```

알람 규칙은 [`prometheus/rules/credit.rules.yml`](prometheus/rules/credit.rules.yml) 에 있다.
지금 켜진 알람만 터미널에서 보려면:

```
curl -s http://localhost:9090/api/v1/alerts
```

## 디버깅

```
# DB 들여다보기 (3306 이 publish 되지 않으므로 exec 로 들어간다)
docker compose -f deploy/observability/docker-compose.yml exec mysql \
  mysql -ucredit -pcredit credit_system -e "SELECT status, COUNT(*) FROM jobs GROUP BY status;"

# 앱 로그
docker compose -f deploy/observability/docker-compose.yml logs -f app
```

## 장애 주입 시나리오

`scenarios/` 의 7개 스크립트는 사고를 실제로 심고, **어느 지표가 반응하고 어느 지표가
침묵하는지, 감지까지 몇 초 걸리는지**를 실측한다.

```
# 전체 (30~50분, 마지막에 down -v 까지 한다)
./deploy/observability/scenarios/run-all.sh

# 하나만 (각 스크립트가 시작할 때 down -v → up 을 하므로 단독 실행된다)
./deploy/observability/scenarios/03-worker-stopped.sh
```

| 스크립트 | 심는 사고 | 걸리는 시간 |
|---|---|---|
| `01-worker-crash.sh` | PROCESSING 중인 앱을 SIGKILL 하고 즉시 재기동 | ~2분 |
| `02-heartbeat-lost.sh` | heartbeat ZSET 소실(→`backstop`) / Redis 다운 중 회수(→`backstop_blind`) | ~9분 |
| `03-worker-stopped.sh` | 워커만 정지(`APP_WORKER_ENABLED=false`) | ~9분 |
| `04-scheduler-stopped.sh` | 스케줄러 정지(`APP_SCHEDULING_ENABLED=false`) | ~4분 |
| `05-ledger-corruption.sh` | SQL 로 잔액·원장을 직접 훼손하고 원복 | ~5분 |
| `06-duplicate-storm.sh` | 같은 idemKey 로 100건 동시 요청 | ~2분 |
| `07-external-api-hang.sh` | 외부 생성 API 무한 지연(스텁 600초) | ~9분 |

각 스크립트는 끝에 **기대 vs 관측** 표를 stdout 으로 낸다. 사고 주입에 쓰는 env 는
`docker-compose.yml` 의 `APP_*` 통과 항목이고, 전부 스크립트 안(`restart_app_with`)에서만
export 되므로 호출한 셸에는 남지 않는다.

곡선을 보려면 스크립트를 돌리는 동안 <http://localhost:9090/graph> 에 그 시나리오의 지표를
넣어 두고, 알람이 pending 을 거쳐 firing 으로 넘어가는 것은 <http://localhost:9090/alerts> 에서 본다.
