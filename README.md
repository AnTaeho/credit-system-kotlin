# credit-system-kotlin

AI 생성 서비스의 **크레딧 과금 백엔드**다. 조직(organization)이 크레딧을 충전하고, 이미지 생성을 요청하면 잔액에서 비용을 **hold** 한다. 워커가 job 을 집어 생성 스텁을 호출하고, 성공하면 **confirm**, 실패하면 **환불**한다.

돈이 걸린 시스템이라 "두 번 처리됨", "잔액이 음수가 됨", "돈이 묶인 채 사라짐" 이 전부 사고다. 이 저장소의 대부분은 그 사고를 하나씩 막는 장치이고, 그 장치들이 **어떤 순서로 왜 생겼는지**가 [`STEPS.md`](STEPS.md) 의 학습 브랜치 체인에 남아 있다.

Kotlin / Spring Boot / MySQL / Redis. 원본은 별도 Java 프로젝트이고 이 저장소는 그것을 Kotlin 으로 이식한 뒤 독자적으로 자란 것이다.

---

## 빠른 시작

전제는 Docker(Compose v2)와 JDK 17 이다.

```bash
# 1) 로컬 인프라 — MySQL 8.4 + Redis 7
docker compose up -d

# 2) 앱 — application.yml 의 기본값이 위 컨테이너와 같은 계약이라 설정 없이 붙는다
./gradlew bootRun
```

앱은 8080(공개 API)에 뜬다. 스키마는 부팅할 때 Flyway 가 만든다.

### 조직 먼저 넣기

**조직 생성 API 가 없다.** 모든 API 가 `X-Organization-Id` 헤더로 조직을 지목하는데, 그 조직을 만드는 경로가 SQL 뿐이다(권한·인증은 의도적으로 범위 밖이다 — [로드맵](docs/roadmap.md) step11). 앱이 한 번 떠서 테이블이 생긴 뒤에 넣는다.

```bash
docker compose exec -T mysql mysql -ucredit -pcredit credit_system -e "
  INSERT INTO organizations (id, name, balance, initial_balance, created_at, updated_at)
  VALUES (1, 'demo-org', 0, 0, NOW(6), NOW(6));"
```

관측 스택 쪽에는 같은 일을 하는 스크립트가 있다: [`deploy/observability/scripts/seed.sh`](deploy/observability/scripts/seed.sh) (앱이 UP 이 될 때까지 기다린 뒤 `INSERT` 한다).

### 써 보기

```bash
# 1000 크레딧 충전
curl -X POST http://localhost:8080/api/organizations/me/charge \
  -H 'X-Organization-Id: 1' -H 'Content-Type: application/json' \
  -d '{"idemKey":"charge-1","amount":1000}'

# 생성 요청 — 건당 100 크레딧이 hold 된다
curl -X POST http://localhost:8080/api/jobs \
  -H 'X-Organization-Id: 1' -H 'Content-Type: application/json' \
  -d '{"idemKey":"job-1","prompt":"a cat wearing sunglasses"}'
```

`idemKey` 는 멱등키다. 같은 키로 다시 보내면 새로 처리하지 않고 처음 결과를 돌려준다(응답의 `duplicate` 플래그).

워커가 job 을 집어 스텁을 호출한다. 스텁은 기본 설정에서 3~7초 걸리고 **30% 확률로 실패**한다(`app.stub.failure-rate`). 실패한 job 은 최대 3회까지 재시도되고, 그래도 안 되면 hold 된 금액이 환불된다. 진행 상황은 이렇게 본다.

```bash
curl http://localhost:8080/api/jobs             -H 'X-Organization-Id: 1'
curl http://localhost:8080/api/organizations/me/balance -H 'X-Organization-Id: 1'
curl http://localhost:8080/api/ledger           -H 'X-Organization-Id: 1'
```

원장(`/api/ledger`)이 사실의 기록이다. 잔액은 그 합계로 설명되는 결과값이고, 둘이 어긋나면 대사 배치가 1분마다 알아챈다.

내릴 때는 `docker compose down -v`(데이터까지 버린다).

---

## API

전부 `X-Organization-Id: <조직 id>` 헤더가 필요하다.

| | |
|---|---|
| `POST /api/organizations/me/charge` | 충전. 본문 `{"idemKey","amount"}` |
| `GET /api/organizations/me/balance` | 잔액 |
| `POST /api/jobs` | 생성 요청(= hold). 본문 `{"idemKey","prompt"}` |
| `GET /api/jobs` | 이 조직의 job 목록 |
| `GET /api/ledger` | 이 조직의 원장 |

오류는 `{"code","message"}` 형태다. 잔액 부족·중복 요청은 409, 입력 오류는 400, 없는 조직은 404.

job 단건 조회와 페이징은 아직 없다(로드맵 step11). OpenAPI 문서도 아직 없다.

---

## 테스트

```bash
./gradlew test                    # Docker 필요 — Testcontainers 가 MySQL·Redis 를 띄운다
./gradlew test detekt ktlintCheck # CI 가 PR 에서 돌리는 조합
```

대부분의 테스트는 H2 위에서 빠르게 돌고, 동시성·종료 테스트만 Testcontainers 의 실제 MySQL/Redis 를 쓴다. 마이그레이션이 진짜로 도는지 확인하는 자리도 그쪽이다. Docker 가 없으면 후자가 실패한다.

동시성 테스트는 "여러 요청이 같은 잔액을 동시에 깎으면 어떻게 되는가"를 실제 스레드로 던져서 확인한다. 그래서 전체 실행에는 시간이 좀 걸린다.

---

## 이미지로 실행

`Dockerfile` 은 멀티스테이지라 소스만 있으면 된다. 호스트에 JDK 도, 미리 만든 jar 도 필요 없다.

```bash
docker build -t credit-system-kotlin:local .

# 앱까지 컨테이너로 (MySQL·Redis 와 함께)
docker compose --profile app up -d --build
```

`--profile app` 없이 `docker compose up -d` 하면 인프라만 뜬다. 개발 중에는 앱을 자주 재시작하니까 그쪽이 기본이다.

CI 는 GitHub Actions 다. PR 이면 [`ci.yml`](.github/workflows/ci.yml) 이 `test detekt ktlintCheck` 를 돌리고, `develop` push 나 `v*` 태그면 [`image.yml`](.github/workflows/image.yml) 이 검증을 통과한 뒤 GHCR 로 이미지를 올린다. **아직 실제 러너에서 돈 적은 없다** — 첫 push 에서 고칠 것이 나올 수 있다.

---

## 관측 스택

Prometheus + Grafana + 알람 규칙 + 장애 주입 시나리오가 별도 compose 로 들어 있다. 워커를 죽이고 Redis 를 내리고 원장을 SQL 로 깨서 **어느 지표가 반응하고 어느 지표가 침묵하는지** 실측한 기록이 그 안에 있다.

→ [`deploy/observability/README.md`](deploy/observability/README.md)

앱 자체는 `/actuator/prometheus` 로 도메인 지표를 낸다. `bootRun` 으로 띄우면 8080 에 그대로 붙지만, 컨테이너로 띄울 때는 `MANAGEMENT_SERVER_PORT: 8081` 로 관리 엔드포인트를 공개 API 포트에서 떼어낸다. 그렇게 하면 애플리케이션 포트의 `/actuator/prometheus` 는 404 가 된다.

---

## 문서 지도

| | |
|---|---|
| [`STEPS.md`](STEPS.md) | 학습 브랜치 체인 전체 지도. step0(방어 없음) → step8(운영 기반) |
| [`docs/step0-naive.md`](docs/step0-naive.md) … [`docs/step8-ops.md`](docs/step8-ops.md) | 단계별 상세. 실제 코드 인용, 테스트가 무엇을 단언하는지, 무엇이 남았는지 |
| [`docs/roadmap.md`](docs/roadmap.md) | 앞으로. 지금 무엇이 실서비스 수준이 아닌지의 진단표와 step9~13 |
| [`deploy/observability/README.md`](deploy/observability/README.md) | 관측 스택 띄우기·시나리오 |

읽는 순서를 하나만 고르라면 `STEPS.md` → 관심 가는 단계의 `docs/stepN-*.md` 다.

---

## 설정

로컬 기본값은 `src/main/resources/application.yml` 에 있고, 루트 `docker-compose.yml` 이 띄우는 DB 와 같은 계약이다.

| | 기본값 |
|---|---|
| DB | `jdbc:mysql://localhost:3306/credit_system`, `credit` / `credit` |
| Redis | `localhost:6379` |
| 생성 비용 / 최대 시도 | 100 크레딧 / 3회 |
| 스텁 지연 / 실패율 | 3~7초 / 0.3 |
| heartbeat timeout / 갱신 주기 | 10초 / 5초 |
| 처리 상한(회수·드레인 공통) | 60초 |

환경별로 바꿀 때는 Spring 표준 환경변수로 덮어쓴다 — `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`. `application.yml` 은 건드리지 않는다.

운영은 `prod` 프로파일(`SPRING_PROFILES_ACTIVE=prod`)이다. 이 프로파일의 DB 설정에는 **기본값이 없다** — 환경변수를 빠뜨리면 앱이 로컬 DB 를 향해 조용히 뜨는 대신 부팅에서 죽는다. 스키마는 어느 프로파일에서든 Flyway 가 만들고 Hibernate 는 `validate` 로 확인만 한다.

저장소에 평문 비밀번호는 없지만, compose 의 환경변수는 여전히 평문이다. 진짜 시크릿 관리는 배포 환경이 정해질 때(로드맵 step10) 붙는다.

---

## 알아 둘 것

- **조직 식별이 헤더 하나다.** `X-Organization-Id` 를 그대로 믿는다. 남의 조직 id 를 적으면 남의 크레딧을 쓴다. API 키는 로드맵 step11 이고, 그 이상의 보안은 의도적으로 범위 밖이다
- **생성이 스텁이다.** `GenerationStubClient` 는 `Thread.sleep` + 확률적 실패인 인메모리 시뮬레이션이다. 네트워크 너머로 나가는 것은 step11 이다
- **인스턴스 1대 전제다.** 회수·대사·멱등키 정리 스케줄러에 분산 락이 없다. 2대를 띄우면 겹친다(step10)
- **실서비스 계획은 없다.** 이 저장소의 목적은 "돈이 걸린 시스템에서 무엇이 어떻게 깨지고 무엇으로 막는가"를 코드와 실측으로 남기는 것이다
