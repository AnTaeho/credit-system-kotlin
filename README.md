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

# 2) 앱 — local 프로필(시드 계정 둘). DB·Redis 는 application.yml 의 기본값이 위 컨테이너와 같은 계약이라 설정 없이 붙는다
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

앱은 8080(공개 API·화면)에 뜬다. local 프로필은 관리 엔드포인트(액추에이터)를 8081 로 뗀다. 스키마는 부팅할 때 Flyway 가 만든다.

### 로그인

모든 API 와 화면은 로그인이 필요하다. 이메일과 비밀번호로 로그인하고, 누구나 <http://localhost:8080/signup> 에서 가입할 수 있다. 가입하면 항상 일반 사용자이고 잔액은 0 이다.

`local` 프로필은 기동할 때 계정 둘을 만들어 둔다([`application-local.yml`](src/main/resources/application-local.yml) 의 `app.auth.seed-accounts`). 비밀번호는 저장소에 공개된 로컬 전용 값이다.

| 이메일 | 비밀번호 | 권한 |
|---|---|---|
| `dev@local.test` | `local-dev-password` | 일반 사용자 |
| `admin@local.test` | `local-admin-password` | 운영자(`ROLE_ADMIN`) — 크레딧 지급 |

- **브라우저:** <http://localhost:8080/login> 에서 로그인한다. 로그인은 쿠키 두 개로 유지된다. 액세스 JWT(15분)와 리프레시 토큰(14일)이고, 액세스가 만료되면 서버가 리프레시로 조용히 갱신한다. 세션은 없다. 쓰기 요청에는 CSRF 토큰이 필요하다(화면이 알아서 싣는다)
- **curl:** `POST /auth/token` 으로 액세스 토큰을 받아 `Authorization: Bearer` 헤더에 싣는다. 이 요청은 CSRF 검사에서 빠진다. 토큰은 15분 뒤 만료되므로 그때 다시 받는다

```bash
token() {
  curl -s http://localhost:8080/auth/token -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'
}
DEV=$(token dev@local.test local-dev-password)
ADMIN=$(token admin@local.test local-admin-password)
```

운영자는 가입으로 만들 수 없다. 운영자 계정은 `app.auth.seed-accounts` 에 적어 두면 기동할 때 만들어진다. 같은 이메일로 먼저 가입한 사람이 있어도 기동 뒤에는 설정의 역할과 비밀번호로 덮인다.

### 크레딧 얻기 — 운영자 지급

결제 없는 자기 충전은 없다(실제 결제 충전은 [로드맵](docs/roadmap.md) step14). 그전까지 크레딧은 **운영자 지급**으로만 생긴다. 브라우저에서는 운영자로 로그인해 `/admin` 화면의 지급 폼을 쓴다.

```bash
# 지급할 사용자의 id 를 확인한다 (새 DB 면 dev@local.test 가 1)
docker compose exec -T mysql mysql -ucredit -pcredit credit_system -e "SELECT id, email, role FROM users;"

# 운영자가 그 사용자에게 1000 크레딧 지급 (1회 상한 1,000,000)
curl -X POST http://localhost:8080/api/admin/users/1/grants \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"idemKey":"grant-1","amount":1000}'
```

### 써 보기

```bash
# 생성 요청 — 건당 100 크레딧이 hold 된다
curl -X POST http://localhost:8080/api/jobs \
  -H "Authorization: Bearer $DEV" -H 'Content-Type: application/json' \
  -d '{"idemKey":"job-1","prompt":"a cat wearing sunglasses"}'
```

`idemKey` 는 멱등키다. 같은 키와 같은 내용으로 다시 보내면 새로 처리하지 않고 처음 결과를 돌려준다(응답의 `duplicate` 플래그). 같은 키로 다른 내용을 보내면 409 로 거절한다.

워커가 job 을 집어 스텁을 호출한다. 스텁은 기본 설정에서 3~7초 걸리고 **30% 확률로 실패**한다(`app.stub.failure-rate`). 실패한 job 은 최대 3회까지 재시도되고, 그래도 안 되면 hold 된 금액이 환불된다. 진행 상황은 이렇게 본다.

```bash
curl http://localhost:8080/api/jobs/1           -H "Authorization: Bearer $DEV"
curl http://localhost:8080/api/jobs             -H "Authorization: Bearer $DEV"
curl http://localhost:8080/api/users/me/balance -H "Authorization: Bearer $DEV"
curl http://localhost:8080/api/ledger           -H "Authorization: Bearer $DEV"
```

브라우저라면 로그인 뒤 홈(`/`)에서 요청하고, 진행 중인 job 은 화면이 알아서 폴링한다.

원장(`/api/ledger`)이 사실의 기록이다. 잔액은 그 합계로 설명되는 결과값이고, 둘이 어긋나면 대사 배치가 1분마다 알아챈다.

내릴 때는 `docker compose down -v`(데이터까지 버린다).

---

## API

`/api` 아래는 전부 로그인이 필요하다. 사용자는 인증 주체에서 꺼내므로 요청에 사용자 id 를 적는 곳이 없다(운영자 지급의 대상 id 만 예외).

| | |
|---|---|
| `POST /auth/token` | 액세스 토큰 받기. 본문 `{"email","password"}`, 응답 `{"accessToken","expiresInSeconds"}`. 로그인 없이 부른다 |
| `GET /api/users/me/balance` | 내 잔액 |
| `POST /api/jobs` | 생성 요청(= hold). 본문 `{"idemKey","prompt"}` |
| `GET /api/jobs/{id}` | 내 job 하나. 남의 것·없는 것은 둘 다 404 |
| `GET /api/jobs?cursor=&size=` | 내 job 목록. `{items, nextCursor}`, id 내림차순, `size` 기본 20·최대 100 |
| `GET /api/ledger?cursor=&size=` | 내 원장. 형식은 위와 같다 |
| `POST /api/admin/users/{userId}/grants` | 운영자 지급. 본문 `{"idemKey","amount"}`. 운영자 전용 |

오류는 `{"code","message"}` 형태다. 입력 오류 400, 미인증 401, 권한 부족·CSRF 토큰 없음 403, 없는 job 404, 잔액 부족·중복 요청·멱등키 재사용 409. 쿠키로 로그인한 쓰기 요청은 CSRF 토큰이 필요하다(화면이 알아서 싣는다). Bearer 헤더로 부르는 요청은 필요 없다.

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

Prometheus + 알람 규칙 + 장애 주입 시나리오가 별도 compose 로 들어 있다. 지표와 알람은 Prometheus 화면에서 본다. 워커를 죽이고 Redis 를 내리고 원장을 SQL 로 깨서 **어느 지표가 반응하고 어느 지표가 침묵하는지** 실측한 기록이 그 안에 있다.

→ [`deploy/observability/README.md`](deploy/observability/README.md)

앱 자체는 `/actuator/prometheus` 로 도메인 지표를 낸다. 관리 포트를 따로 줄 때만(local 프로필과 관측 스택은 8081) 그 포트의 `health`·`prometheus` 가 인증 없이 열리고, 애플리케이션 포트에는 액추에이터가 없다. 관리 포트를 따로 주지 않고 띄우면 액추에이터는 전부 막힌다.

---

## 바깥에 기대는 것

이 앱이 자기 프로세스 밖에서 기대는 것은 넷이다. 각각이 끊겼을 때 돈이 어떻게 되는지를 기준으로 적는다.

| 바깥 | 무엇을 맡기나 | 닿는 방법 | 끊기면 |
|---|---|---|---|
| **MySQL 8.4** | 잔액, 원장, job, 멱등키, 리프레시 토큰. 사실의 전부다 | JDBC `3306`. 스키마는 기동할 때 Flyway 가 맞춘다 | 서비스가 선다. 요청은 실패하고 기동도 되지 않는다. 스케줄러는 그 주기를 실패로 남기고 다음 주기에 다시 돈다. 돈은 마지막 커밋 상태 그대로다 |
| **Redis 7** | 워커가 살아 있다는 표시(ZSET `heartbeats`) 하나. 세션도 캐시도 두지 않는다 | `6379`, 연결·명령 타임아웃 2초 | 서비스는 계속 돈다. 하트비트 갱신은 경고 로그만 남기고, 죽은 job 회수는 DB 의 `updatedAt` 60초 기준으로 내려간다(`backstop_blind`). 살아 있는 job 을 잘못 회수해도 `attemptNo` 가 달라 돈은 한 번만 움직인다 |
| **생성 호출** | 결과물을 만드는 일 | 지금은 프로세스 안의 스텁이다. 네트워크로 나가지 않는다. 3~7초 뒤 30% 확률로 실패하고, 성공하면 존재하지 않는 주소(`https://stub-images.local/…`)를 돌려준다 | 실패하면 재시도 뒤 환불로 끝난다. **돌아오지 않으면 돈이 묶인다.** 호출에 타임아웃이 없고, 워커가 살아 하트비트를 계속 보내므로 회수도 일어나지 않는다. 알아챌 길은 가장 오래된 미결 나이가 5분을 넘을 때 뜨는 `CreditPipelineStalled` 뿐이다 |
| **Prometheus** | 지표를 모으고 알람을 낸다 | Prometheus 가 관리 포트(`8081`)의 `/actuator/prometheus` 를 5초마다 긁는다. 앱은 Prometheus 를 모른다 | 서비스와 돈은 그대로다. 사고가 나도 알람이 오지 않는다 |

넷 말고는 없다. 로그인은 예전에 구글에 기댔지만 지금은 앱 안에서 끝난다. 메일도 결제도 붙어 있지 않다.

**헬스체크가 Redis 를 본다.** `/actuator/health` 는 DB 와 Redis 를 함께 확인한다. Redis 만 죽어도 `DOWN` 이 되고, compose 의 앱 헬스체크가 이 주소를 본다. 돈 처리는 계속 도는데 컨테이너는 unhealthy 로 보이는 구간이 생긴다.

**들어오는 쪽.** 공개 포트는 `8080` 하나다(API 와 화면). 관리 포트 `8081` 은 compose 네트워크 밖으로 내보내지 않는 것이 경계다. 그 포트의 `health`·`prometheus` 는 로그인 없이 열린다.

**알람 규칙**은 [`deploy/observability/prometheus/rules/credit.rules.yml`](deploy/observability/prometheus/rules/credit.rules.yml) 에 있다.

| 알람 | 뜻 |
|---|---|
| `CreditLedgerReconciliationMismatch` | 잔액이 원장 합계와 어긋난 사용자가 있다 |
| `CreditNegativeBalanceOrgs` · `CreditJobsWithoutHold` · `CreditUnsettledTerminalJobs` | 있어서는 안 되는 상태가 DB 에 있다(음수 잔액, HOLD 없는 job, 끝났는데 정산 원장이 없는 job) |
| `CreditPipelineStalled` | 가장 오래된 미결 job 이 5분을 넘겼다. 워커가 멈췄거나 생성 호출이 돌아오지 않는다 |
| `CreditBackstopRecovery` · `CreditBackstopBlindRecovery` | 하트비트가 놓친 job 을 `updatedAt` 으로 회수했다. 뒤쪽은 Redis 를 못 본 채 회수한 것이다 |
| `CreditReconciliationStale` · `CreditSnapshotStale` | 대사나 스냅샷 자체가 멈췄다 |
| `CreditSystemDown` | Prometheus 가 앱을 30초째 긁지 못한다 |
| `CreditRetryExhaustionRateHigh` | 한 시간 동안 접수의 10% 넘게 환불로 끝났다 |
| `CreditWorkerClaimRolledBack` | 워커가 선점한 job 을 풀에 넘기지 못해 되돌렸다 |

알람을 받아 사람에게 보내는 곳(Alertmanager, 메신저)은 아직 붙어 있지 않다. 규칙은 Prometheus 화면에서만 보인다.

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
| 액세스 토큰 / 리프레시 토큰 수명 | 15분 / 14일 |
| 시드 계정 | 없음. local 프로필은 `dev@local.test` / `admin@local.test` |
| 운영자 지급 1회 상한 | 1,000,000 |

환경별로 바꿀 때는 환경변수로 덮어쓴다. `application.yml` 은 건드리지 않는다.

| 환경변수 | 뜻 |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | MySQL 접속 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | Redis 접속 |
| `SPRING_PROFILES_ACTIVE` | `local`(시드 계정, 관리 포트 8081) 또는 `prod` |
| `MANAGEMENT_SERVER_PORT` | 관리 포트. 따로 주지 않으면 액추에이터가 전부 막힌다 |
| `APP_WORKER_ENABLED` / `APP_SCHEDULING_ENABLED` | 워커와 스케줄러를 끈다. 런타임에 바꿀 수 없어 재기동이 필요하다 |
| `APP_STUB_FAILURE_RATE` / `APP_STUB_MIN_DELAY_MILLIS` / `APP_STUB_MAX_DELAY_MILLIS` | 생성 스텁의 실패율과 지연 |

### 로그인 설정

| 환경변수 | 뜻 |
|---|---|
| `APP_AUTH_JWT_SECRET` | 액세스 JWT 서명 키(HS256, 32바이트 이상) |
| `APP_AUTH_COOKIESECURE` | 로그인 쿠키에 `Secure` 를 붙일지. 운영은 `true` |
| `APP_AUTH_SEEDACCOUNTS_0_EMAIL` / `_PASSWORD` / `_ROLE` | 기동할 때 맞출 계정. 운영자 계정을 만드는 유일한 길이다. 번호를 늘려 여러 개 적는다 |

뒤의 둘은 속성(`app.auth.cookie-secure`, `app.auth.seed-accounts[0].email`)의 대시가 빠진 이름이다 — Spring 완화 바인딩의 규칙이다. 로컬에서 서명 키를 안 주면 `application.yml` 에 적힌 로컬 전용 기본값을 쓴다.

운영은 `prod` 프로파일(`SPRING_PROFILES_ACTIVE=prod`)이다. 이 프로파일의 DB 설정과 JWT 서명 키에는 **기본값이 없다** — 환경변수를 빠뜨리면 앱이 로컬 DB 를 향해 조용히 뜨는 대신 부팅에서 죽는다. 서명 키가 저장소에 공개된 로컬 기본값과 같거나 쿠키 `Secure` 가 꺼져 있어도 기동을 거부한다. 스키마는 어느 프로파일에서든 Flyway 가 만들고 Hibernate 는 `validate` 로 확인만 한다.

저장소에 있는 비밀번호는 local 프로필의 시드 계정 둘과 compose 의 DB 계정뿐이고, 둘 다 로컬 전용이다. 진짜 시크릿 관리는 배포 환경이 정해질 때(로드맵 step10) 붙는다.

---

## 알아 둘 것

- **누구나 가입할 수 있다.** 이메일 인증, 가입·로그인 시도 제한, 비밀번호 재설정은 없다. 가입해도 잔액은 0 이고 크레딧은 운영자 지급으로만 생기므로 돈이 새지는 않는다
- **로그아웃해도 이미 나간 액세스 토큰은 만료(15분)까지 유효하다.** 서버가 끊는 것은 리프레시 토큰이다. 브라우저에서는 쿠키가 지워져 쓰이지 않는다
- **크레딧은 운영자 지급으로만 생긴다.** 결제 충전은 step14 다
- **생성이 스텁이다.** `GenerationStubClient` 는 `Thread.sleep` + 확률적 실패인 인메모리 시뮬레이션이다. 네트워크 너머로 나가는 것은 step11 이다
- **인스턴스 1대 전제다.** 회수·대사·멱등키 정리 스케줄러에 분산 락이 없다. 실사용자가 한 명이라 서버도 1대로 운영하기로 했다(로드맵 결정 13)
- **내가 실제로 쓰는 개인 서비스로 가는 중이다.** 원화로 앱 내 크레딧을 충전(mock)하고, 그 크레딧으로 Claude API 요청을 산다(로드맵 v4). 돈이 걸린 시스템에서 무엇이 깨지고 무엇으로 막는지를 코드와 실측으로 남기는 원칙은 그대로다
