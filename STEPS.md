# 단계별 학습 브랜치

이 저장소의 크레딧 시스템을 **방어 로직이 하나도 없는 상태**에서 시작해
지금까지 열 단계로 나눠 놓은 브랜치들이다.

각 단계는 그 자체로 컴파일되고 테스트가 통과하는 실행 가능한 상태다.
테스트도 그 단계에 실제로 존재하는 방어만 검증하도록 맞춰져 있다.

히스토리는 `step0 → step7` 순방향 선형이고, `step8-ops` 는 step7 을 머지한 `develop` 위에,
`step9-auth` 는 step8 을 머지한 `develop` 위에, `step11-external` 은 step9 를 머지한 `develop` 위에 올라 있다
(step10 배포는 보류했다 — [`docs/roadmap.md`](docs/roadmap.md) step10 절).
한 겹씩 이렇게 본다:

```
git diff step0-naive step1-validation
git log --oneline step0-naive..step7-observability
git log --oneline develop..step8-ops
git log --oneline step8-ops..step9-auth
git log --oneline step9-auth..step11-external
```

`main` 은 실제 개발 히스토리를 담은 브랜치다. 동작과 방어 장치는 `step6-resilience` 와 같지만
코드가 글자 그대로 같지는 않다 — step 브랜치 쪽은 엔티티의 불변 필드를 생성자에 `val` 로 선언하고,
`ChargeService` 와 `OrganizationQueryService` 를 `OrganizationService` 하나로 합쳐 두었다.

step0~step6 이 **사고를 막는** 이야기라면, step7 부터는 **밖에서 보이게 만들고 운영 가능하게 만드는**
이야기다. step6 까지만 읽어도 도메인 방어의 논지는 다 들어온다.

각 브랜치에는 그 단계까지의 **상세 문서**가 `docs/` 아래에 하나씩 들어 있다.
`step3-idempotency` 를 체크아웃하면 `docs/step0-naive.md` 부터 `docs/step3-idempotency.md` 까지 읽을 수 있다.
이 파일은 전체 지도이고, `docs/` 쪽이 단계별 상세다 — 실제 코드 인용, 테스트가 무엇을 단언하는지,
그 단계의 코드를 어떻게 깨뜨릴 수 있는지가 거기 있다.

---

## 도메인 한 줄 요약

조직(organization)이 크레딧을 **충전**하고, 이미지 생성을 **요청**하면 잔액에서 비용을 **hold** 한다.
워커가 job 을 집어 생성 스텁을 호출하고, 성공하면 **confirm**, 실패하면 **환불**한다.

돈이 걸린 시스템이라 "두 번 처리됨", "잔액이 음수가 됨", "돈이 묶인 채 사라짐" 이 전부 사고다.
아래 단계들은 그 사고를 하나씩 막아 나가는 순서다.

---

## step0-naive — 방어 없는 순수 비즈니스 로직

동작하는 것: 충전, 요청, hold, 워커 생성, 결과 기록.

없는 것: **전부.**

- 입력을 검증하지 않는다. 음수 충전도, 빈 prompt 도 그대로 들어간다
- 잔액이 모자라도 그냥 깎는다 → **잔액이 음수가 된다**
- 예외 계층이 없다. 조직이 없으면 `IllegalStateException` 이 그대로 올라가 500 이 나간다
- 생성에 실패하면 hold 된 돈이 묶인 채 끝난다

볼 곳: `job/service/HoldService.kt` — 열 줄 남짓이다. 여기서 출발한다.

---

## step1-validation — 입력 검증과 예외 계층

새로 생긴 것:

- `global/exception/` — `BusinessException` 아래 `InvalidRequestException`,
  `InsufficientBalanceException`, `OrganizationNotFoundException`
- `GlobalExceptionHandler` — `@RestControllerAdvice` 로 400 / 404 / 409 를 제대로 내려준다
- amount 검증(0 초과, 100만 이하), prompt 검증(공백 아님, 1000자 이하)
- 잔액 부족 검사

**아직 못 막는 것 — 여기가 이 단계의 핵심이다.**

```kotlin
val organization = organizationRepository.getOrThrow(organizationId)
if (organization.balance < cost) {          // ← 읽고
    throw InsufficientBalanceException(...)
}
organization.deduct(cost)                    // ← 쓴다
```

읽기와 쓰기가 두 걸음이다. 두 요청이 같은 잔액을 읽으면 둘 다 검사를 통과한다.
검사를 붙였는데도 잔액은 여전히 음수가 될 수 있다. **애플리케이션 레벨 검사로는 경합을 못 막는다.**

---

## step2-atomic-balance — 원자적 잔액 갱신과 원장

새로 생긴 것:

- 조건부 UPDATE. 읽기와 쓰기를 DB 한 문장으로 합친다

  ```sql
  UPDATE Organization o
  SET o.balance = o.balance - :amount
  WHERE o.id = :id AND o.balance >= :amount
  ```

  갱신 건수가 0이면 잔액이 모자랐다는 뜻이다. 검사와 차감 사이에 틈이 없다
- `ledger/` 패키지 — 모든 잔액 변동을 `HOLD` / `CONFIRM` / `CHARGE` 원장으로 남긴다.
  잔액은 결과값일 뿐이고, 원장이 사실의 기록이다

볼 곳: `organization/repository/OrganizationRepository.kt` 의 `deductBalance`,
`job/service/HoldService.kt` 의 `deductBalance` 헬퍼(0건 → 예외).

**아직 못 막는 것**: 같은 요청을 두 번 보내면 두 번 처리된다.
네트워크가 끊겨 클라이언트가 재시도하면 충전이 두 번 먹히고 job 이 두 개 생긴다.

---

## step3-idempotency — 멱등키로 중복 요청 막기

새로 생긴 것:

- `IdempotencyKey` 엔티티 + `(organizationId, idemKey)` 유니크 제약
- 원장에도 `(organizationId, idemKey)` 유니크 제약 — 충전 쪽 중복 방어
- API 에 `idemKey` 가 등장한다. 응답에 `duplicate` 플래그가 붙는다
- `DuplicateRequestInProgressException` — 선점한 요청이 아직 jobId 를 붙이기 전에 들어온 재시도
- `GlobalExceptionHandler` 가 `DataIntegrityViolationException` 의 유니크 위반을 409 로 번역한다

핵심은 **애플리케이션의 조회 검사가 아니라 DB 유니크 제약이 최종 방어선**이라는 것이다.
"먼저 조회해서 없으면 INSERT" 는 그 사이에 다른 요청이 끼어들 수 있다.
유니크 제약이 있으면 진 쪽은 예외를 맞고, 그 예외를 정상 경로로 번역하면 된다.

볼 곳: `job/service/HoldService.kt`, `job/concurrency/DuplicateIdemKeyTest.kt`.

**아직 못 막는 것**: 워커가 스케줄러 스레드에서 순차로 한 건씩 처리한다.
병렬로 돌리는 순간 여러 워커가 같은 job 을 집는다.

---

## step4-state-machine — 시도 번호 기반 상태 전이 CAS

새로 생긴 것:

- `Job.attemptNo` — 몇 번째 시도인지
- 모든 상태 전이가 조건부 UPDATE 가 된다

  ```sql
  UPDATE Job j SET j.status = :newStatus
  WHERE j.id = :jobId AND j.status = :expectedStatus AND j.attemptNo = :attemptNo
  ```

  기대한 상태와 시도 번호가 맞을 때만 바뀐다. compare-and-swap 이다
- `WorkerExecutorConfig` — 고정 크기 스레드 풀. 워커가 진짜로 병렬이 된다
- 워커는 **선점(claim)** 후에야 executor 에 넘긴다. 선점에 실패하면(0건) 다른 워커가 이미 가져간 것이다

왜 시도 번호가 필요한가: 상태만 보면 부족하다.
1번 시도가 멈춘 줄 알고 2번 시도를 띄웠는데 1번이 뒤늦게 깨어나 `COMPLETED` 를 쓰면
2번의 결과가 덮인다. 시도 번호가 있으면 **늦게 도착한 쓰기는 조용히 무시**된다.

볼 곳: `job/repository/JobRepository.kt` 의 `transitionIfStatusAndAttemptMatch`,
`job/worker/GenerationWorker.kt` 의 `claim`.

**아직 못 막는 것**: 워커 프로세스가 죽으면 job 이 `PROCESSING` 에 영원히 남는다.
`FAILED` 가 된 job 은 재시도도 환불도 안 된다. 돈이 묶인 채 끝난다.

---

## step5-recovery — heartbeat 회수와 재시도 / 최종 환불

새로 생긴 것:

- `heartbeat/` — 처리 중인 워커가 Redis ZSET 에 주기적으로 만료 시각을 갱신한다.
  워커가 죽으면 갱신이 멈추고 만료된다
- `DeadJobRecoveryTask` — 5초마다 세 가지를 한다
  1. heartbeat 만료된 시도를 `FAILED` 로 내린다
  2. heartbeat 도 없이 `PROCESSING` 으로 오래 정체된 job 을 `FAILED` 로 내린다
  3. `FAILED` job 을 재시도하거나(`attemptNo + 1`), 최대 시도를 넘겼으면 최종 환불한다
- `JobStatus.REFUNDED`, `LedgerType.REFUND`

heartbeat 와 timeout 두 겹인 이유: heartbeat 는 빠르지만 Redis 에 의존한다.
`updatedAt` 기반 정체 감지는 느리지만 DB 만 있으면 된다. 서로의 구멍을 메운다.

볼 곳: `job/scheduling/DeadJobRecoveryTask.kt`, `heartbeat/HeartbeatRegistry.kt`.

**아직 못 막는 것 — 회수 장치 자체가 깨질 때다.**

- Redis 가 잠깐 죽으면 모든 heartbeat 조회가 실패한다.
  그러면 **멀쩡히 살아 있는 워커의 job 이 전부 만료로 판정**되어 회수된다. 대량 오탐이다
- 배치 루프 한복판에서 예외가 나면 그 주기의 나머지 job 이 통째로 처리되지 않는다
- 잔액과 원장이 어긋나도 아무도 모른다

---

## step6-resilience — 인프라 장애 내성과 감사 (도메인 방어의 완성본)

새로 생긴 것:

- **항목 단위 예외 격리** — `DeadJobRecoveryTask` 의 세 단계와 각 항목이 따로 `try/catch` 된다.
  한 건의 실패가 같은 주기의 나머지를 막지 못한다
- **Redis 실패는 회수를 멈춘다** — heartbeat 조회가 실패하면 예외가 그대로 올라오고,
  위 격리가 그 주기 또는 그 항목만 건너뛴다. 판단 근거를 잃었을 때
  **아무것도 안 하는 쪽으로 기운다**. 갱신(`refreshHeartbeat`)만 예외를 삼키는데,
  주기 실행 스레드가 죽으면 안 되기 때문이다
- **`LedgerReconciliationTask`** — 1분마다 `initialBalance + 원장 합계 == 현재 잔액` 을 대사한다.
  틀리면 `ERROR` 로그를 남긴다. 막는 게 아니라 **어긋났음을 알아채는** 장치다
- **`IdempotencyKeyCleanupTask`** — 멱등키는 무한히 쌓인다. 새벽 2시에 보존 기간 지난 것을 지운다
- **설정 불변식** — `HeartbeatProperties` 의 `require(refreshInterval < timeout)` 같은 것.
  잘못된 설정으로 뜨느니 부팅에서 죽는 게 낫다
- 워커/프로세서의 예외 방어 — executor 위임이 거부되면 선점을 되돌리고,
  결과 반영에 실패하면 timeout 회수에 맡긴다

이 단계의 관점: **방어 로직도 코드다. 그것도 깨진다.**
깨졌을 때 무엇이 일어나야 하는지까지 정해 둔 것이 마지막 겹이다.

여기서도 남는 것: Redis 가 **복구된 직후**다. 장애 동안 갱신되지 못한 heartbeat 는
전부 만료로 보이므로, 갱신보다 회수 스캔이 먼저 돌면 살아 있는 job 이 회수될 수 있다.
`updatedAt` 정체 스캔이 60초를 기다리는 것과 달리 heartbeat 만료는 10초라 창이 짧다.

---

## step7-observability — 도메인 지표 관측

여기서부터는 사고를 **막는** 이야기가 아니라 **보이게 만드는** 이야기다.
step2~6 이 만든 방어 장치들은 잘 막고 있는지, 얼마나 자주 막고 있는지, 아니면 아예 안 돌고 있는지를
밖에서 알 수 없었다. 이 도메인의 진짜 사고는 CPU 40%, 에러율 0%로 서버가 완벽히 건강한 채로 난다.

새로 생긴 것:

- **관측 대상을 네 계층으로 나눈다** — L0 돈(묶인 금액과 나이), L1 불변식(대사), L2 흐름(적체),
  L3 방어 발동(몇 번 막았나). L3 이 특히 서버 지표로 대체 불가능하다.
  attemptNo 가 낡은 세대의 confirm 을 무효화한 사건은 HTTP 200 이고 에러 로그도 지연도 없다.
  `credit_defense_total{point="confirm",outcome="stale"}` 이 오르는 것만이 유일한 흔적이다
- **`observability/` 패키지** — 도메인·스케줄러 코드는 `LedgerReconciliationCompleted`,
  `DefenseTriggered` 같은 **순수 값 이벤트**만 발행하고, Micrometer 를 아는 것은 이 패키지뿐이다.
  "무엇이 일어났나"를 바꾸는 사람과 "그걸 어떻게 세나"를 바꾸는 사람이 서로의 코드를 안 건드린다
- **노출 경계와 카디널리티 가드** — 관리 포트를 떼면 애플리케이션 포트의 `/actuator/prometheus` 가 404 가 된다.
  레지스트리 수준의 시계열 상한. 조직 id 같은 식별자를 태그로 다는 것을 코드가 거부한다
- **docker-compose 관측 스택** — Prometheus + Grafana + 알람 규칙. `deploy/observability/`
- **장애 주입 9개 시나리오** — 워커를 죽이고 스케줄러를 끄고 Redis 를 내리고 원장을 SQL 로 깬다.
  매번 세 가지를 잰다: 어느 지표가 반응했나, **어느 지표가 침묵했나**, 감지까지 몇 초 걸렸나

이 단계의 관점: **막는 장치는 말이 없다. 말하게 만들어야 장치가 된다.**
그리고 실측이 기존 코드의 결함 셋을 드러냈다 — staleness 규칙이 "한 번도 안 돎"(-1)을 못 잡던 것,
`updatedAt` 백스톱이 사실은 Redis 에 의존하던 것, `worker_claim/applied` 가 처리량이 아니던 것.
셋 다 고쳤다. **관측 장치 자신의 고장이 사고보다 나쁠 수 있다**는 게 첫 번째가 남긴 교훈이다.

여기서도 남는 것: L2 흐름이 비어 있어 대시보드는 "막혔다"까지만 말하고 "어디서"는 말하지 않는다.
Alertmanager 가 없어 알람은 누가 보고 있을 때만 알려준다. 그리고 한 대짜리 실험이라
`worker_claim/lost`, `confirm/stale` 같은 경쟁 지표는 전부 0 인 채다 — 두 인스턴스가 필요하다.

---

## step8-ops — 운영 기반: 마이그레이션·설정·이미지·CI

여기까지의 저장소는 **내 노트북에서만** 살아 있었다. 스키마는 Hibernate 가 부팅마다 알아서 맞췄고,
DB 비밀번호는 `application.yml` 에 평문이었고, 이미지는 "먼저 `./gradlew bootJar` 를 돌려라"가
전제였다. 도메인 코드는 거의 건드리지 않는 단계다.

새로 생긴 것:

- **Flyway** — `V1__baseline.sql` 은 지금 스키마를 "개선하지 않고" 그대로 옮긴 것이다.
  `SHOW CREATE TABLE` 을 받아 적었더니 `status`·`type` 이 `VARCHAR` 가 아니라 **네이티브 `ENUM`** 이었다.
  `ddl-auto: update` → `validate` 로 바뀌면서, 이제 enum 값 하나 추가가 스키마 변경이 됐다
- **프로파일 분리** — 로컬 기본값은 루트 compose 와 같은 계약(`credit_system`/`credit`/`credit`).
  `application-prod.yml` 의 DB 설정에는 **기본값을 일부러 안 줬다**. 환경변수를 빠뜨리면
  로컬 DB 를 향해 조용히 뜨는 대신 부팅에서 죽는다 — step6 의 설정 불변식과 같은 판단이다
- **루트 `Dockerfile`(멀티스테이지)과 `docker-compose.yml`** — 빌드가 이미지 안에서 돈다.
  compose 는 기본으로 인프라만 띄우고 앱은 `--profile app` 뒤에 숨겼다
- **GitHub Actions** — PR 은 `test detekt ktlintCheck`, `develop` push·`v*` 태그는 GHCR.
  `workflow_run` 대신 `workflow_call` + `needs` 를 쓴 이유가 `image.yml` 헤더에 있다
  (`workflow_run` 은 워크플로가 기본 브랜치에 있어야만 뜨는데 통합 브랜치가 `develop` 이다)

이 단계의 관점: **잘못된 상태로 조용히 뜨느니 부팅에서 죽는 게 싸다.**
운영 프로파일이 기본값을 안 주는 것도, `ddl-auto` 가 `validate` 인 것도 같은 판단이다 —
틀린 설정이나 틀린 스키마를 안고 도는 시간을 0 으로 만든다.

여기서도 남는 것: **CI 가 실제로 돈 적이 없다**(push 전이라 러너에서 초록을 본 적이 없다).
스케줄러 셋에 분산 락이 없어 2대를 띄우면 겹친다. 배포는 여전히 진행 중 job 을 죽이고
step5 의 회수가 받는다 — graceful shutdown 은 배포 환경이 생기는 step10 의 몫이다.
H2 를 쓰는 대다수 테스트는 마이그레이션을 타지 않는다.

---

## step9-auth — 개인 사용자와 인증

step8 까지는 **요청이 스스로 밝힌 신원을 그대로 믿었다.** 모든 API 가 조직 id 헤더의 숫자를
계정으로 썼고, 충전 API 는 결제 확인 없이 잔액을 더했다. 인터넷에 올리는 순간(step10) 누구나 남의 크레딧을
쓰고 자기 크레딧을 찍어낼 수 있는 상태였다. 이 단계는 그 문을 닫고, 브라우저로 한 바퀴를 돌게 만든다.

새로 생긴 것:

- **조직 → 개인 사용자** — Flyway V2 가 `organizations` 를 `users` 로 옮긴다. 이름만 바꾸고 동작은 그대로다.
  지표 이름(`..._orgs`)은 step7 의 실측 기록과 이어지도록 일부러 두었다
- **구글 로그인 + 허용 목록** — Spring Security `oauth2Login`(세션). `email_verified` 이고 허용 목록 안이어야
  들어온다. 신원은 구글 `sub`, 첫 로그인 때 사용자 행이 생긴다. 컨트롤러는 인증 주체(`CurrentUser`)만 받고,
  요청에서 사용자 id 를 받는 핸들러가 없다는 것을 테스트가 리플렉션으로 전수 검사한다
- **결제 없는 충전 삭제 → 운영자 지급** — 자기 충전 API 가 사라지고 `POST /api/admin/users/{userId}/grants`
  (운영자 전용, 멱등, 1회 상한) 하나가 남는다. 원장 유형 `ADMIN_GRANT`(V3) — 네이티브 `ENUM` 에 값 하나를
  더하는 것이 step8 이 예고한 대로 마이그레이션이 됐다
- **job 단건 조회와 커서 페이징** — 남의 job 과 없는 job 은 둘 다 404(존재 여부를 흘리지 않는다).
  목록은 `{items, nextCursor}`
- **사용자별 속도 제한 — 넣었다가 뺐다** — `POST /api/jobs` 에 토큰 버킷을 걸었다가 같은 단계에서 전부 되돌렸다.
  총량은 잔액이, AI 호출 동시성은 워커 수가 이미 막고, 선결제 잔고를 지키는 진짜 벽은 step12 의 일일 원가 상한이다.
  근거는 [`docs/step9-auth.md`](docs/step9-auth.md) 의 D 절
- **최소 화면** — Thymeleaf 서버 렌더링. 로그인·홈·job·원장·운영자 지급. CSRF 는 켜고 CSP 로 인라인 스크립트를 막는다
- **개발 로그인** — `X-Dev-User: <email>` 헤더(요청 단위, CSRF 면제)와 `/login` 화면의 세션 개발 로그인(CSRF 적용).
  local·test 프로필 전용이고, prod 에서 켜져 있으면 기동을 거부한다. 관측 스택의 스크립트도 이걸로 옮겼다

이 단계의 관점: **신원은 요청이 말하는 것이 아니라 서버가 확인한 것이다.** 헤더 숫자를 믿던 코드를
인증 주체만 믿게 바꾸자, 요청에서 사용자 id 를 받는 곳이 운영자 지급 하나로 줄었고 그 하나는 테스트가 이름으로 붙잡고 있다.
돈이 생기는 경로도 같은 원리로 운영자 한 명에게만 남겼다.

브라우저 한 바퀴와 실제 구글 계정 로그인은 2026-09-20 둘 다 확인했다.
미인증 POST 는 401 이 아니라 CSRF 403 으로 막힌다. Redis 가 죽으면 health 가 DOWN 이 된다는 것이 CI 첫 실패로
드러났다 — step10 의 health 재설계 몫이다. 요청 속도 제한은 없다 — 총량은 잔액, 동시성은 워커 수가 막고
나머지는 step12 의 일일 원가 상한 몫으로 넘겼다.

## step11-external — 외부 호출 안전화

step9 까지 이 서비스의 "외부 호출"은 `Thread.sleep` 이었다. **느려질 수도, 죽을 수도,
영원히 돌아오지 않을 수도 없는 외부**라서 타임아웃도 재시도 간격도 한 번도 검증된 적이 없었다.
진짜 Claude 를 붙이기 전에(step12) 그 경계를 만들고, 가짜 생성기로 최악의 경우를 실제로 재현해 재는 단계다.

새로 생긴 것:

- **생성 클라이언트 경계와 호출 타임아웃** — `GenerationClient` 포트와 스텁 어댑터. 타임아웃(기본 20초)은
  호출자가 아니라 **경계의 구현이 소유한다**. 인터럽트 플래그를 남긴 채 풀로 돌아가는 함정이 구조적으로 없다.
  타임아웃은 생성 실패와 같은 경로로 흘러가고 로그에서만 구분된다
- **백스톱 절대 상한** — 워커가 hang 이면 종지기는 워커 상태를 보지 않고 계속 갱신하므로 heartbeat 가
  영원히 LIVE 다. 두 그물이 모두 놓치고 돈이 영구히 묶였다. `app.processing.absolute-timeout-seconds`(기본 300)가
  "아무리 살아 있다고 우겨도 이만큼 지났으면 내린다"는 마지막 그물이다. 감지자는 `HARD_CAP`
- **재시도 지수 backoff** — Flyway V5 의 `jobs.next_attempt_at`. `base * multiplier^(재시도-1)`,
  기본 10초·4배라 시도 상한 3 에서는 10초와 40초다. 외부가 죽었을 때 상한 3회를 몇 초 만에 태우지 않는다
- **드레인(graceful shutdown)** — step8 에서 만들어 보관해 둔 구현을 살렸다. 웹서버 → 스케줄러·워커풀 →
  드레인 → heartbeat 순으로 내려간다. 문은 플래그가 아니라 **락**이라, "닫힌 뒤엔 새 선점이 없다"가
  상태가 아니라 구조로 보장된다. compose 의 `stop_grace_period` 를 드레인 상한보다 크게 잡아야 효과가 난다
- **상관 ID 와 구조화 로그** — 요청 ID(HTTP 한 번)와 job 식별자를 나눠 MDC 에 넣고, prod 만 JSON(ECS).
  프롬프트는 로그·예외 메시지에서 걷어내고 길이만 남겼다 — 예외 메시지는 로그로 번져 나간다
- **관측** — 07 을 "지연 → 타임아웃"으로 다시 쓰고 진짜 무응답을 08 로 새로 만들었다(시나리오 8개).
  알람 둘(`CreditHardCapRecovery`, `CreditWorkerSlotsExhausted`)과 슬롯 게이지 패널

이 단계의 관점: **상한이 없는 기다림은 기다림이 아니라 누수다.** 그리고 모든 구멍이 같은 값으로 막히지는 않는다 —
돈은 절대 상한이 풀지만 **워커 슬롯은 아무도 돌려주지 못한다**. 깨울 수단이 없기 때문이다. 그래서 이 단계는
막지 못한 것을 지표 하나(`credit_worker_slots_free`)와 알람 하나로 **보이게** 만드는 데까지가 결론이다.
[`docs/step11-external.md`](docs/step11-external.md) 의 한계 절에 그 목록이 있다.

---

## 추천 학습 순서

1. `git checkout step0-naive` 하고 `HoldService`, `OrganizationService`, `GenerationWorker` 를 읽는다.
   전체가 몇 백 줄이라 한 번에 들어온다
2. 각 단계로 넘어가기 전에 **"지금 이 코드를 어떻게 깨뜨릴 수 있을까"** 를 먼저 생각해 본다
3. 그다음 `git diff stepN stepN+1` 로 실제로 어떻게 막았는지 본다
4. 테스트 diff 도 같이 본다. 그 단계가 무엇을 보장한다고 주장하는지가 테스트에 적혀 있다

```
git diff step0-naive step1-validation
git diff step1-validation step2-atomic-balance
git diff step2-atomic-balance step3-idempotency
git diff step3-idempotency step4-state-machine
git diff step4-state-machine step5-recovery
git diff step5-recovery step6-resilience
git diff step6-resilience step7-observability
git diff develop step8-ops
git diff step8-ops step9-auth
```

step6 까지가 "사고를 어떻게 막는가"의 전부다. 거기서 멈춰도 논지는 닫힌다.
step7~step9 는 그 위에 얹은 다른 종류의 이야기라, 관심이 관측·운영·인증 쪽이면 바로 건너뛰어도 된다.

특정 파일 하나가 어떻게 자랐는지 따라가려면:

```
git log -p --follow step0-naive..step7-observability -- src/main/kotlin/com/example/credit_system_kotlin/job/service/HoldService.kt
```

## 실행

`step8-ops` 부터는 인프라를 루트 compose 가 띄운다.

```
docker compose up -d      # MySQL 8.4 + Redis 7
./gradlew bootRun         # application.yml 의 기본값이 위 컨테이너와 같은 계약이다
docker compose down -v
```

`step9-auth` 부터는 로그인이 필요하다. 로컬은 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 으로 띄우고
개발 로그인(`X-Dev-User` 헤더 또는 `/login` 의 폼)을 쓴다. 크레딧은 운영자 지급으로 얻는다.
step8 이하는 사용자(조직)를 만드는 API 가 없어서 첫 요청 전에 SQL 로 하나 넣어야 한다.
띄우는 법과 curl 예시는 [`README.md`](README.md) 에 있다.

step7 이하의 브랜치를 체크아웃했다면 루트 compose 가 없으므로 MySQL·Redis 를 직접 준비한다
(step5 부터 Redis 가 필요하다).

테스트는 어느 단계에서든 같다.

```
./gradlew test                    # Docker 필요 (Testcontainers 가 MySQL·Redis 를 띄운다)
./gradlew test detekt ktlintCheck # step8 의 CI 가 PR 에서 돌리는 조합
```
