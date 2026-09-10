# 관측 스택 (Prometheus + Grafana)

step7 5단계에서 만든 로컬 관측 스택이다. 논지·지표 해석·알람 기준의 상세는
[`docs/step7-observability.md`](../../docs/step7-observability.md) 의 4·5단계를 봐라.

## 전제

- Docker (Compose v2)

앱 이미지는 저장소 루트의 `Dockerfile`(멀티스테이지)로 만들어진다. 빌더 스테이지가
이미지 안에서 `bootJar` 까지 돌리므로 호스트에 JDK 도, 미리 만든 jar 도 필요 없다.
(step8-C 이전에는 `./gradlew bootJar` 를 먼저 돌려야 했다.)

호스트의 3306(MySQL)·6379(Redis)는 건드리지 않는다 — 스택의 MySQL/Redis 는 포트를
publish 하지 않고 compose 네트워크 안에서만 산다.

## 올리기 / 내리기

```
# 올리기 (저장소 루트에서)
docker compose -f deploy/observability/docker-compose.yml up -d --build

# 상태 확인 — 5개 서비스가 전부 healthy/running 이 될 때까지
docker compose -f deploy/observability/docker-compose.yml ps

# 내리기 (볼륨까지)
docker compose -f deploy/observability/docker-compose.yml down -v
```

## 데이터 넣기

```
# 조직 id=1 생성 (조직 생성 API 가 없어서 SQL 로 넣는다)
./deploy/observability/scripts/seed.sh

# 충전 + job 생성 + 중복/잔액부족 유발, 마지막에 방어 카운터 출력
./deploy/observability/scripts/smoke.sh
```

## 접속

| | URL |
|---|---|
| 애플리케이션 API | http://localhost:8080 |
| Prometheus | http://localhost:9090 (`/targets`, `/alerts`) |
| Grafana | http://localhost:3000 (익명 Viewer 허용, 쓰기는 admin/admin) |

`http://localhost:8080/actuator/prometheus` 는 **404 다.** 관리 포트(8081)는 호스트로
publish 하지 않기 때문이다. 지표를 눈으로 보려면:

```
docker compose -f deploy/observability/docker-compose.yml exec app \
  curl -s http://localhost:8081/actuator/prometheus | grep credit_
```

## 디버깅

```
# DB 들여다보기 (3306 이 publish 되지 않으므로 exec 로 들어간다)
docker compose -f deploy/observability/docker-compose.yml exec mysql \
  mysql -uroot -pan902318 credit_system -e "SELECT status, COUNT(*) FROM jobs GROUP BY status;"

# 앱 로그
docker compose -f deploy/observability/docker-compose.yml logs -f app
```

## 장애 주입 시나리오 (step7 6단계)

`scenarios/` 의 7개 스크립트는 사고를 실제로 심고, **어느 지표가 반응하고 어느 지표가
침묵하는지, 감지까지 몇 초 걸리는지**를 실측한다. 해석과 실측값은
[`docs/step7-observability.md`](../../docs/step7-observability.md) 의 6단계에 있다.

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

Grafana 로 곡선을 보려면 스크립트를 돌리는 동안 <http://localhost:3000> 의
`credit-domain` 대시보드를 열어 둔다. 어느 시점에 어느 패널을 봐야 하는지는 문서 6단계의
"포트폴리오 스크린샷 가이드" 절에 있다.

## 장애 주입 버튼 패드 (step7 후속 3)

시나리오 스크립트는 사고를 심고 끝까지 달린 다음 표를 내는 물건이다. **누르고 나서 곡선을
보고 싶을 때**는 다른 게 필요하다. `faultpad/` 가 그거다 — 버튼 하나가 사고 하나이고,
사고 버튼마다 짝이 되는 복구 버튼이 있다. 화면은 카드마다 **반응해야 할 지표(실측 초까지)와
침묵해야 할 지표**를 나란히 놓는다.

```
python3 deploy/observability/faultpad/server.py     # http://127.0.0.1:8090
```

전제는 Docker 와 python3 뿐이다(표준 라이브러리만 쓴다 — 설치할 것이 없다). 서버는 **127.0.0.1
에만 바인드하고**, `catalog.json` 에 선언된 액션만 실행한다. docker·mysql·curl 조작은 전부
`faultpad/actions.sh` 가 하고, 그 파일은 시나리오와 같은 `scenarios/lib.sh` 를 source 한다 —
스크립트와 버튼이 다른 코드로 같은 사고를 심으면 둘 중 하나는 반드시 낡는다.

스택은 패드의 `스택 올리기 (fresh)` 버튼으로 올려도 되고 미리 올려 둬도 된다. 상세는
[`faultpad/README.md`](faultpad/README.md), 설계 근거는
[`docs/step7-observability.md`](../../docs/step7-observability.md) 의 `## 후속 3` 에 있다.
