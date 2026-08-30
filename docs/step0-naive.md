# step0-naive — 방어 없는 순수 비즈니스 로직

충전, hold, 워커 생성, confirm/fail 로 이어지는 크레딧 시스템의 정상 경로만 구현한 출발점이다. 입력 검증도, 잔액 부족 검사도, 예외 계층도, 멱등키도, 동시성 제어도 없다.

- 이전 단계: 없음 (체인의 출발점)
- 다음 단계: `step1-validation`

## 이 단계가 하는 일

1. **충전**: `POST /api/organizations/me/charge` 로 조직 잔액을 늘린다. `OrganizationService.charge`가 `Organization`을 찾아 `charge(amount)`를 호출할 뿐이다.
2. **요청/hold**: `POST /api/jobs` 로 이미지 생성을 요청하면 `HoldService.requestGeneration`이 정해진 비용(`app.generation.cost`, 기본 100)만큼 조직 잔액을 먼저 깎고(`deduct`), `Job`을 `HOLDING` 상태로 저장한다. 비용을 개별 요청 단위로 받는 게 아니라 서버 설정값 하나로 고정되어 있다.
3. **워커 pick**: `GenerationWorker`가 `@Scheduled`로 주기적(기본 500ms)으로 깨어나 `HOLDING` 상태 Job을 id 오름차순으로 배치 크기(`app.worker.batch-size`, 기본 3)만큼 읽는다. 각 Job에 대해 `JobLifecycleService.startProcessing`으로 `PROCESSING` 전이시킨 뒤 `GenerationJobProcessor.runGeneration`을 순서대로 호출한다.
4. **생성**: `GenerationJobProcessor`는 `GenerationStubClient.generate(prompt)`를 호출한다. 스텁은 설정된 지연 시간만큼 `Thread.sleep` 한 뒤 `failureRate` 확률로 `StubGenerationException`을 던지거나 가짜 `resultUrl`을 반환한다.
5. **confirm/fail**: 생성이 성공하면 `JobLifecycleService.confirm`이 Job을 `COMPLETED`로 바꾸고 `resultUrl`을 채운다. 실패하면 `markFailed`가 `FAILED`로 바꾼다 — 이 단계에는 환불이 없으므로 실패해도 이미 깎인 잔액은 그대로 묶여 있다.

각 클래스의 역할은 뚜렷하게 나뉜다. `OrganizationService`/`HoldService`/`JobLifecycleService`/`JobQueryService`는 트랜잭션 경계를 갖는 도메인 서비스, `GenerationWorker`는 스케줄러, `GenerationJobProcessor`는 워커가 집은 Job 하나를 생성 스텁에 넘기고 결과를 서비스에 반영하는 조정자, `GenerationStubClient`는 실제 이미지 생성 API를 흉내 내는 순수 스텁이다.

## 코드 지도

```
src/main/kotlin/com/example/credit_system_kotlin/
├── CreditSystemKotlinApplication.kt   # @EnableScheduling, AppProperties/WorkerProperties 등록
├── global/
│   ├── config/AppProperties.kt        # app.generation.cost, app.stub.* 바인딩
│   ├── config/WorkerProperties.kt     # app.worker.enabled/batch-size 바인딩
│   └── domain/BaseEntity.kt           # createdAt/updatedAt 공통 컬럼
├── job/
│   ├── controller/JobApiController.kt # POST/GET /api/jobs
│   ├── domain/Job.kt                  # HOLDING→PROCESSING→COMPLETED/FAILED
│   ├── domain/JobStatus.kt            # 상태 enum
│   ├── dto/HoldResult.kt              # jobId만 담아 응답
│   ├── dto/JobCreateRequest.kt        # prompt
│   ├── dto/JobResponse.kt             # 목록 조회 응답
│   ├── repository/JobRepository.kt    # 상태별/조직별 조회 쿼리 메서드
│   ├── service/HoldService.kt         # 잔액 차감 + Job 생성
│   ├── service/JobLifecycleService.kt # 상태 전이 3종
│   ├── service/JobQueryService.kt     # 조직별 Job 목록
│   ├── stub/GenerationStubClient.kt   # 지연+확률 실패 스텁
│   ├── stub/StubGenerationException.kt
│   ├── worker/GenerationJobProcessor.kt # Job 1건을 스텁에 넘기고 결과 반영
│   └── worker/GenerationWorker.kt     # 스케줄러, HOLDING 배치 pick
└── organization/
    ├── controller/OrganizationApiController.kt # 잔액 조회/충전
    ├── domain/Organization.kt         # charge/deduct
    ├── dto/BalanceResponse.kt, ChargeRequest.kt, ChargeResponse.kt
    ├── repository/OrganizationRepository.kt    # JpaRepository 그대로
    └── service/OrganizationService.kt  # 잔액 조회/충전
```

## 핵심 코드 읽기

**`HoldService.requestGeneration`** — 이 단계의 핵심이자 가장 취약한 지점이다.

```kotlin
@Transactional
fun requestGeneration(organizationId: Long, prompt: String): HoldResult {
    val cost = appProperties.generation.cost
    val organization = organizationRepository.findByIdOrNull(organizationId)
        ?: error("조직을 찾을 수 없습니다: organizationId=$organizationId")
    organization.deduct(cost)

    val job = jobRepository.save(Job.hold(organizationId, cost, prompt))
    log.info("hold 완료: organizationId={}, jobId={}, cost={}", organizationId, job.persistedId, cost)
    return HoldResult(job.persistedId)
}
```

`prompt`가 비어 있어도, `organizationId`가 존재하지 않아도, 잔액이 `cost`보다 적어도 이 메서드는 멈추지 않는다. 조직을 못 찾으면 `error(...)`로 `IllegalStateException`을 던질 뿐이고, 잔액 확인 없이 `deduct`부터 실행한다. `@Transactional`이 있으니 예외가 나면 롤백은 되지만, 잔액이 음수가 되는 경우 자체를 막는 코드는 없다.

**`Organization.deduct`** — 방어가 전혀 없는 순수 연산.

```kotlin
fun deduct(amount: Long) {
    balance -= amount
    updatedAt = Instant.now()
}
```

`charge`도 대칭적으로 단순하다. 음수 `amount`를 막는 검사가 없어서 `charge(-1000)`은 그대로 잔액을 깎는 충전이 된다.

**`Job` 상태 전이** — 상태는 존재하지만 전이 규칙(가드)은 없다.

```kotlin
fun startProcessing() {
    status = JobStatus.PROCESSING
    updatedAt = Instant.now()
}

fun complete(resultUrl: String) {
    status = JobStatus.COMPLETED
    this.resultUrl = resultUrl
    updatedAt = Instant.now()
}

fun fail() {
    status = JobStatus.FAILED
    updatedAt = Instant.now()
}
```

각 메서드는 현재 상태가 무엇이든 확인하지 않고 그냥 덮어쓴다. `COMPLETED`인 Job에 다시 `fail()`을 불러도 조용히 `FAILED`가 된다. CAS(compare-and-set)나 상태 머신 검증이 없다는 뜻이다.

**`GenerationWorker.dispatchPendingJobs`** — 동시성 제어가 없는 pick.

```kotlin
@Scheduled(fixedDelayString = "\${app.scheduling.worker-interval-millis:500}")
fun dispatchPendingJobs() {
    val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
    for (job in jobs) {
        jobLifecycleService.startProcessing(job.persistedId)
        jobProcessor.runGeneration(job)
    }
}
```

단일 인스턴스, 단일 스레드 스케줄러라 이 단계에서는 우연히 문제가 드러나지 않는다. 하지만 조회에 락(`SELECT ... FOR UPDATE`)이 없으므로 워커를 여러 인스턴스로 띄우면 같은 Job을 동시에 집을 수 있다.

**`GenerationJobProcessor.runGeneration`** — 성공/실패 분기.

```kotlin
fun runGeneration(job: Job) {
    val resultUrl = generateOrMarkFailed(job) ?: return
    jobLifecycleService.confirm(job.persistedId, resultUrl)
}

private fun generateOrMarkFailed(job: Job): String? =
    try {
        stubClient.generate(job.prompt)
    } catch (_: StubGenerationException) {
        jobLifecycleService.markFailed(job.persistedId)
        null
    }
```

`StubGenerationException` 외의 예외(예: DB 접근 실패)는 잡지 않고 그대로 위로 던져진다. 그 경우 `startProcessing`으로 이미 `PROCESSING`이 된 Job은 그 상태로 멈춘 채 남는다 — 아무도 재시도하지 않는다.

## 테스트가 보장하는 것

- `OrganizationServiceTest` — 충전하면 잔액이 늘어난다.
- `HoldServiceTest` — hold 요청이 잔액을 차감하고 Job을 만든다. prompt 1000자까지는 허용됨을 확인한다(상한 검증이 아니라 "이 길이는 통과한다"는 사실 확인).
- `JobLifecycleServiceTest` — `startProcessing`/`confirm`/`markFailed` 각각 올바른 상태로 전이한다.
- `GenerationStubClientTest` — `failureRate=0`이면 항상 성공, `failureRate=1`이면 항상 `StubGenerationException`을 던진다.
- `GenerationJobProcessorTest` — 스텁 성공 시 `confirm` 호출, 실패 시 `markFailed` 호출을 mock으로 검증한다.
- `GenerationWorkerUnitTest` — `HOLDING` Job들을 id 순서대로, `startProcessing`→`runGeneration` 순서로 처리한다.
- `JobApiControllerTest` / `OrganizationApiControllerTest` — HTTP 계층까지 포함한 정상 흐름(생성 후 목록 조회, 잔액 조회 후 충전)이 동작한다.
- `GenerationPipelineEndToEndTest` — 실제 MySQL 컨테이너로 hold부터 confirm까지 전체 파이프라인이 스케줄러를 통해 실제로 완료됨을 `awaitility`로 확인한다.

이 단계의 테스트는 모두 **정상 입력, 정상 상태에서의 정상 경로**만 검증한다. 잘못된 입력, 경쟁 상태, 중복 요청, 존재하지 않는 조직을 다루는 테스트는 하나도 없다.

## 이 코드를 깨뜨리는 법

**잔액보다 큰 요청 → 잔액이 음수가 된다.** `HoldService.requestGeneration`은 `organization.deduct(cost)`를 잔액 확인 없이 실행한다.

```bash
curl -X POST http://localhost:8080/api/organizations/me/charge \
  -H "X-Organization-Id: 1" -H "Content-Type: application/json" -d '{"amount": 50}'

curl -X POST http://localhost:8080/api/jobs \
  -H "X-Organization-Id: 1" -H "Content-Type: application/json" -d '{"prompt": "a cat"}'
# cost=100 > balance=50 인데도 200 OK, 잔액은 -50이 된다
```

**음수 충전 → 잔액을 몰래 깎는 충전.** `Organization.charge`는 `balance += amount`뿐이라 `amount`가 음수여도 막지 않는다.

```bash
curl -X POST http://localhost:8080/api/organizations/me/charge \
  -H "X-Organization-Id: 1" -H "Content-Type: application/json" -d '{"amount": -1000}'
```

**빈 prompt → 빈 문자열로 Job이 저장된다.** `HoldService`에 입력 검증이 없으므로 `prompt: ""`도 그대로 `HOLDING` Job이 되어 워커가 스텁에 빈 문자열을 넘긴다.

```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "X-Organization-Id: 1" -H "Content-Type: application/json" -d '{"prompt": ""}'
```

**없는 조직 id → 500과 스택트레이스.** `organizationRepository.findByIdOrNull(organizationId) ?: error(...)`는 `IllegalStateException`을 던지고, 이를 잡는 예외 핸들러가 없으므로 Spring 기본 500 응답(내부 메시지 노출)이 나간다.

```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "X-Organization-Id: 999999" -H "Content-Type: application/json" -d '{"prompt": "a cat"}'
```

**같은 요청 두 번 → 두 번 다 차감된다.** 멱등키가 없으므로 네트워크 재시도나 더블 클릭으로 같은 요청이 두 번 오면 `HoldService`는 두 개의 별도 Job을 만들고 잔액을 두 번 깎는다. 클라이언트 입장에서는 "한 번 요청했는데 두 번 과금됨"으로 보인다.

**생성 실패 → 잔액이 묶인 채 사라진다.** `app.stub.failure-rate`(기본 0.3)에 걸려 `StubGenerationException`이 나면 `GenerationJobProcessor`는 Job을 `FAILED`로 바꿀 뿐, 환불 로직이 없다. 차감된 `cost`는 어디에도 돌아오지 않는다. 30% 확률로 이 경로를 타므로 로컬에서 몇 번만 반복해도 재현된다.

## 다음 단계 예고

`step1-validation`은 이 단계에 입력 검증과 예외 계층을 얹는다. `prompt` 공백/길이 검사, 잔액 부족 검사, 조직 조회 실패를 각각 명시적인 예외(`InvalidRequestException`, `InsufficientBalanceException`, `OrganizationNotFoundException` 등)로 구분하고, `GlobalExceptionHandler`가 이를 일관된 에러 응답으로 변환한다. `error(...)`로 뭉뚱그려 500을 내던 지점들이 이때부터 의미 있는 4xx로 바뀐다. 다만 잔액 음수 자체를 막는 것과 동시 요청에 대한 원자성(잔액이 진짜로 안전하게 차감되는 것)은 `step2-atomic-balance`의 몫이고, 중복 요청 차단은 `step3-idempotency`가 다룬다.

## 명령어

```bash
# 이 브랜치 테스트 실행 (Docker 필요 — GenerationPipelineEndToEndTest 가 Testcontainers MySQL 을 띄운다)
./gradlew test

# 로컬 실행 (application.yml의 MySQL 접속 정보 확인 필요)
./gradlew bootRun

# step1이 무엇을 더하는지 전체 diff로 보기
git diff step0-naive step1-validation

# 특정 파일만 비교
git diff step0-naive step1-validation -- src/main/kotlin/com/example/credit_system_kotlin/job/service/HoldService.kt
```
