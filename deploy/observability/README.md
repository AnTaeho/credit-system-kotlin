# 관측 스택 (Prometheus + Grafana)

step7 5단계에서 만든 로컬 관측 스택이다. 논지·지표 해석·알람 기준의 상세는
[`docs/step7-observability.md`](../../docs/step7-observability.md) 의 4·5단계를 봐라.

## 전제

- Docker (Compose v2)
- **먼저 jar 를 만들어야 한다.** 이미지는 호스트가 만든 fat jar 를 COPY 할 뿐이다.

```
./gradlew bootJar
```

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
