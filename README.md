# credit-system-kotlin

AI 생성 서비스의 **크레딧 과금 백엔드**다. 사용자가 크레딧을 받아 두고, 이미지 생성을 요청하면 잔액에서 비용을 **hold** 한다. 워커가 job 을 집어 생성 스텁을 호출하고, 성공하면 **confirm**, 실패하면 **환불**한다.

돈이 걸린 시스템이라 "두 번 처리됨", "잔액이 음수가 됨", "돈이 묶인 채 사라짐" 이 전부 사고다. 이 저장소의 대부분은 그 사고를 하나씩 막는 장치이고, 그 장치들이 **어떤 순서로 왜 생겼는지**가 [`STEPS.md`](STEPS.md) 의 학습 브랜치 체인에 남아 있다.

Kotlin / Spring Boot / MySQL / Redis. 원본은 별도 Java 프로젝트이고 이 저장소는 그것을 Kotlin 으로 이식한 뒤 독자적으로 자란 것이다.

---

## 빠른 시작

전제는 Docker(Compose v2)와 JDK 17 이다.

```bash
# 1) 로컬 인프라 — MySQL 8.4 + Redis 7
docker compose up -d

# 2) 앱 — local 프로필(개발 로그인). DB·Redis 는 application.yml 의 기본값이 위 컨테이너와 같은 계약이라 설정 없이 붙는다
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

앱은 8080(공개 API·화면)에 뜬다. local 프로필은 관리 엔드포인트(액추에이터)를 8081 로 뗀다. 스키마는 부팅할 때 Flyway 가 만든다.

### 로그인

모든 API 와 화면은 로그인이 필요하다. 운영에서는 구글 로그인 + 이메일 허용 목록이고, 로컬에서는 구글 자격증명 없이 **개발 로그인**을 쓴다(`local` 프로필 전용 — prod 에서 켜면 기동이 거부된다). 허용 목록은 [`application-local.yml`](src/main/resources/application-local.yml) 의 두 계정이다.

| 이메일 | 권한 |
|---|---|
| `dev@local.test` | 일반 사용자 |
| `admin@local.test` | 운영자(`ROLE_ADMIN`) — 크레딧 지급 |

- **브라우저:** <http://localhost:8080/login> 의 개발 로그인 폼에 이메일을 넣는다. 세션 로그인이고 CSRF 가 적용된다
- **curl:** `X-Dev-User: <email>` 헤더 한 줄. 그 요청 하나에만 인증이 걸리고 CSRF 검사에서 빠진다

사용자 행은 첫 로그인 때 생긴다. 사용자 생성 API 도, 미리 넣어 둘 SQL 도 없다.

### 크레딧 얻기 — 운영자 지급

결제 없는 자기 충전은 없다(실제 결제 충전은 [로드맵](docs/roadmap.md) step14). 그전까지 크레딧은 **운영자 지급**으로만 생긴다. 브라우저에서는 운영자로 로그인해 `/admin` 화면의 지급 폼을 쓴다.

```bash
# 일반 사용자로 한 번 요청해 사용자 행을 만들고 id 를 확인한다 (새 DB 면 보통 1)
curl http://localhost:8080/api/users/me/balance -H 'X-Dev-User: dev@local.test'
docker compose exec -T mysql mysql -ucredit -pcredit credit_system -e "SELECT id, email FROM users;"

# 운영자가 그 사용자에게 1000 크레딧 지급 (1회 상한 1,000,000)
curl -X POST http://localhost:8080/api/admin/users/1/grants \
  -H 'X-Dev-User: admin@local.test' -H 'Content-Type: application/json' \
  -d '{"idemKey":"grant-1","amount":1000}'
```

### 써 보기

```bash
# 생성 요청 — 건당 100 크레딧이 hold 된다
curl -X POST http://localhost:8080/api/jobs \
  -H 'X-Dev-User: dev@local.test' -H 'Content-Type: application/json' \
  -d '{"idemKey":"job-1","prompt":"a cat wearing sunglasses"}'
```

`idemKey` 는 멱등키다. 같은 키로 다시 보내면 새로 처리하지 않고 처음 결과를 돌려준다(응답의 `duplicate` 플래그).

워커가 job 을 집어 스텁을 호출한다. 스텁은 기본 설정에서 3~7초 걸리고 **30% 확률로 실패**한다(`app.stub.failure-rate`). 실패한 job 은 최대 3회까지 재시도되고, 그래도 안 되면 hold 된 금액이 환불된다. 진행 상황은 이렇게 본다.

```bash
curl http://localhost:8080/api/jobs/1           -H 'X-Dev-User: dev@local.test'
curl http://localhost:8080/api/jobs             -H 'X-Dev-User: dev@local.test'
curl http://localhost:8080/api/users/me/balance -H 'X-Dev-User: dev@local.test'
curl http://localhost:8080/api/ledger           -H 'X-Dev-User: dev@local.test'
```

브라우저라면 로그인 뒤 홈(`/`)에서 요청하고, 진행 중인 job 은 화면이 알아서 폴링한다.

원장(`/api/ledger`)이 사실의 기록이다. 잔액은 그 합계로 설명되는 결과값이고, 둘이 어긋나면 대사 배치가 1분마다 알아챈다.

내릴 때는 `docker compose down -v`(데이터까지 버린다).

---

## API

전부 로그인이 필요하다. 사용자는 인증 주체에서 꺼내므로 요청에 사용자 id 를 적는 곳이 없다(운영자 지급의 대상 id 만 예외).

| | |
|---|---|
| `GET /api/users/me/balance` | 내 잔액 |
| `POST /api/jobs` | 생성 요청(= hold). 본문 `{"idemKey","prompt"}`. 사용자별 분당 10건 |
| `GET /api/jobs/{id}` | 내 job 하나. 남의 것·없는 것은 둘 다 404 |
| `GET /api/jobs?cursor=&size=` | 내 job 목록. `{items, nextCursor}`, id 내림차순, `size` 기본 20·최대 100 |
| `GET /api/ledger?cursor=&size=` | 내 원장. 형식은 위와 같다 |
| `POST /api/admin/users/{userId}/grants` | 운영자 지급. 본문 `{"idemKey","amount"}`. 운영자 전용 |

오류는 `{"code","message"}` 형태다. 입력 오류 400, 미인증 401, 권한 부족·CSRF 토큰 없음 403, 없는 job 404, 잔액 부족·중복 요청 409, 속도 제한 429(`Retry-After`). 세션으로 로그인한 쓰기 요청은 CSRF 토큰이 필요하다(화면이 알아서 싣는다).

OpenAPI 문서는 아직 없다.

---

## 테스트

```bash
./gradlew test                    # Docker 필요 — Testcontainers 가 MySQL·Redis 를 띄운다
./gradlew test detekt ktlintCheck # CI 가 PR 에서 돌리는 조합
```

대부분의 테스트는 H2 위에서 빠르게 돌고, 동시성 테스트만 Testcontainers 의 실제 MySQL/Redis 를 쓴다. 마이그레이션이 진짜로 도는지 확인하는 자리도 그쪽이다. Docker 가 없으면 후자가 실패한다.

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

CI 는 GitHub Actions 다. PR 이면 [`ci.yml`](.github/workflows/ci.yml) 이 `test detekt ktlintCheck` 를 돌리고, `develop` push 나 `v*` 태그면 [`image.yml`](.github/workflows/image.yml) 이 검증을 통과한 뒤 GHCR 로 이미지를 올린다. 둘 다 실제 러너에서 돌았다(PR #1·#2, step8 머지 후 GHCR push).

---

## 관측 스택

Prometheus + Grafana + 알람 규칙 + 장애 주입 시나리오가 별도 compose 로 들어 있다. 워커를 죽이고 Redis 를 내리고 원장을 SQL 로 깨서 **어느 지표가 반응하고 어느 지표가 침묵하는지** 실측한 기록이 그 안에 있다.

→ [`deploy/observability/README.md`](deploy/observability/README.md)

앱 자체는 `/actuator/prometheus` 로 도메인 지표를 낸다. 관리 포트를 따로 줄 때만(local 프로필과 관측 스택은 8081) 그 포트의 `health`·`prometheus` 가 인증 없이 열리고, 애플리케이션 포트에는 액추에이터가 없다. 관리 포트를 따로 주지 않고 띄우면 액추에이터는 전부 막힌다.

---

## 문서 지도

| | |
|---|---|
| [`STEPS.md`](STEPS.md) | 학습 브랜치 체인 전체 지도. step0(방어 없음) → step9(인증) |
| [`docs/step0-naive.md`](docs/step0-naive.md) … [`docs/step9-auth.md`](docs/step9-auth.md) | 단계별 상세. 실제 코드 인용, 테스트가 무엇을 단언하는지, 무엇이 남았는지 |
| [`docs/roadmap.md`](docs/roadmap.md) | 앞으로. 지금 무엇이 실서비스 수준이 아닌지의 진단표와 step8~14 의 결정·완료 기록 |
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
| 처리 상한(회수) | 60초 |
| 로그인 허용 목록 / 운영자 | 비어 있음(아무도 못 들어온다). local 프로필은 `dev@local.test` / `admin@local.test` |
| job 접수 속도 제한 | 사용자별 분당 10 |
| 운영자 지급 1회 상한 | 1,000,000 |

환경별로 바꿀 때는 Spring 표준 환경변수로 덮어쓴다 — `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`. `application.yml` 은 건드리지 않는다.

### 구글 로그인

구글 로그인을 쓰려면 구글 OAuth 클라이언트(리디렉션 URI `http://<호스트>/login/oauth2/code/google`)와 허용 목록이 필요하다.

| 환경변수 | 뜻 |
|---|---|
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 구글 OAuth 클라이언트 |
| `APP_AUTH_ALLOWEDEMAILS` | 로그인 허용 이메일, 쉼표로 구분 |
| `APP_AUTH_ADMINEMAILS` | 운영자 이메일. 허용 목록의 부분집합이어야 한다 |

뒤의 둘은 속성(`app.auth.allowed-emails`)의 대시가 빠진 이름이다 — Spring 완화 바인딩의 규칙이다. 로컬에서 구글 두 값을 안 주면 자리표시 기본값으로 앱은 뜨고 구글 로그인만 실패한다.

운영은 `prod` 프로파일(`SPRING_PROFILES_ACTIVE=prod`)이다. 이 프로파일의 DB·구글 설정에는 **기본값이 없다** — 환경변수를 빠뜨리면 앱이 로컬 DB 를 향해 조용히 뜨는 대신 부팅에서 죽는다. 허용 목록이 비었거나 개발 로그인이 켜져 있어도 기동을 거부한다. 스키마는 어느 프로파일에서든 Flyway 가 만들고 Hibernate 는 `validate` 로 확인만 한다.

저장소에 평문 비밀번호는 없지만, compose 의 환경변수는 여전히 평문이다. 진짜 시크릿 관리는 배포 환경이 정해질 때(로드맵 step10) 붙는다.

---

## 알아 둘 것

- **로그인할 수 있는 사람은 허용 목록뿐이다.** 공개 가입이 없다. 실사용자가 나 한 명인 서비스다([로드맵](docs/roadmap.md) 결정 9)
- **크레딧은 운영자 지급으로만 생긴다.** 결제 충전은 step14 다
- **생성이 스텁이다.** `GenerationStubClient` 는 `Thread.sleep` + 확률적 실패인 인메모리 시뮬레이션이다. 네트워크 너머로 나가는 것은 step11 이다
- **인스턴스 1대 전제다.** 회수·대사·멱등키 정리 스케줄러에 분산 락이 없다. 실사용자가 한 명이라 서버도 1대로 운영하기로 했다(로드맵 결정 13)
- **내가 실제로 쓰는 개인 서비스로 가는 중이다.** 원화로 앱 내 크레딧을 충전(mock)하고, 그 크레딧으로 Claude API 요청을 산다(로드맵 v4). 돈이 걸린 시스템에서 무엇이 깨지고 무엇으로 막는지를 코드와 실측으로 남기는 원칙은 그대로다
