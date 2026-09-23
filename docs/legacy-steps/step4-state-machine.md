# step4-state-machine — 시도 번호 기반 상태 전이 CAS

job의 모든 상태 전이를 "지금 상태와 시도 번호가 예상과 같을 때만" 반영되는 조건부 UPDATE로 바꾼다. 워커도 조회한 job을 곧바로 처리하지 않고, 먼저 그 UPDATE로 선점에 성공한 것만 실행에 넘긴다.

- 이전 단계: `step3-idempotency`
- 다음 단계: `step5-recovery`

## 이전 단계의 문제

step3까지의 `GenerationWorker`는 스케줄러 스레드 하나가 순서대로 처리한다는 전제 위에 서 있다.

```kotlin
fun dispatchPendingJobs() {
    val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
    for (job in jobs) {
        jobLifecycleService.startProcessing(job.persistedId)
        jobProcessor.runGeneration(job)
    }
}
```

한 번에 하나씩, 같은 스레드 안에서 `startProcessing` 다음에 `runGeneration`이 이어지니 경합이 끼어들 틈이 없다. 문제는 이 안전함이 "동시에 실행되는 워커가 하나뿐"이라는 우연에 기대고 있다는 점이다. 배치를 스레드 풀로 넘기거나 워커 인스턴스를 여러 개 띄우는 순간, 두 워커가 `findByStatusOrderByIdAsc(HOLDING, ...)`로 같은 job을 동시에 읽어올 수 있다.

더 근본적인 문제는 `Job` 엔티티의 상태 전이 메서드다. step3의 `Job.kt`는 이렇게 되어 있었다.

```kotlin
fun startProcessing() {
    this.status = JobStatus.PROCESSING
    this.updatedAt = Instant.now()
}

fun complete(resultUrl: String) {
    this.status = JobStatus.COMPLETED
    this.resultUrl = resultUrl
    this.updatedAt = Instant.now()
}
```

`fail()`도 같은 모양이다. 이 메서드들에는 조건이 없다. "지금 상태가 무엇이든" 호출하면 그대로 덮어쓴다. JPA dirty checking이 트랜잭션 종료 시점에 변경분을 UPDATE로 반영하는 구조라, 두 워커가 같은 job을 각자의 영속성 컨텍스트에 읽어 들인 뒤 둘 다 `complete()`를 호출하면 두 번의 UPDATE가 순서대로 나갈 뿐 서로를 막을 방법이 없다. 스텁 호출도 두 번 나가고, 원장도 두 번 쌓일 수 있다. step2의 조건부 UPDATE는 잔액에만 적용됐고, step3의 멱등키는 API 요청 중복에만 적용됐다 — job의 상태 전이 자체는 이번 단계 전까지 아무 보호도 받지 못했다.

## 무엇이 새로 생겼나

| 파일 | 역할 |
|---|---|
| `job/domain/Job.kt` | `attemptNo: Int` 필드 추가. `startProcessing`/`complete`/`fail` 메서드 **삭제** — 엔티티가 자기 상태를 스스로 바꿀 권한을 잃는다 |
| `job/repository/JobRepository.kt` | CAS 쿼리 `transitionIfStatusAndAttemptMatch` 신설. `startProcessingIfAttemptMatches`, `failIfProcessing`은 이를 감싼 이름 붙은 default 메서드. `completeIfAttemptMatches`는 resultUrl까지 함께 쓰는 전용 쿼리 |
| `job/service/JobLifecycleService.kt` | `startProcessing` 메서드 삭제(선점은 워커가 직접 리포지토리를 호출). `confirm`/`markFailed`가 CAS 결과 행 수를 확인해 0건이면 조용히 무시하도록 변경 |
| `job/worker/GenerationWorker.kt` | `claim()` 사설 메서드 추가 — 선점 UPDATE가 0건이면 그 job은 건너뛴다. 선점에 성공한 job만 `workerExecutor`로 비동기 위임 |
| `job/worker/WorkerExecutorConfig.kt` (신규) | 고정 크기 `ThreadPoolTaskExecutor` 빈 정의 |
| `job/worker/GenerationJobProcessor.kt` | `confirm(job, resultUrl)`, `markFailed(jobId, attemptNo)` 호출로 변경 — attemptNo를 함께 실어 보낸다 |
| `global/config/WorkerProperties.kt` | `concurrency: Int` 필드 추가 |
| `CreditSystemKotlinApplication.kt` | `@EnableConfigurationProperties`에서 `WorkerProperties` 제거 — `WorkerExecutorConfig`가 자체적으로 `@EnableConfigurationProperties(WorkerProperties::class)`를 선언하므로 애플리케이션 클래스가 더는 알 필요가 없다 |
| `job/dto/JobResponse.kt` | 응답에 `attemptNo` 노출 |
| `application.yml` / `application-test.yml` | `app.worker.concurrency` 설정 추가 |

job 상태 전이 표는 다음과 같다.

| 시작 상태 | 목표 상태 | 호출 메서드 | 호출 위치 |
|---|---|---|---|
| HOLDING | PROCESSING | `startProcessingIfAttemptMatches` | `GenerationWorker.claim` |
| PROCESSING | COMPLETED | `completeIfAttemptMatches` (resultUrl 동시 기록) | `JobLifecycleService.confirm` |
| PROCESSING | FAILED | `failIfProcessing` | `JobLifecycleService.markFailed` |
| (임의) | (임의) | `transitionIfStatusAndAttemptMatch` | 위 세 메서드가 공통으로 위임하는 원형 쿼리 |

## 핵심 코드 읽기

CAS의 원형은 `JobRepository`의 이 쿼리 하나다.

```kotlin
@Transactional
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(
    """
    UPDATE Job j
    SET j.status = :newStatus, j.updatedAt = :now
    WHERE j.id = :jobId AND j.status = :expectedStatus AND j.attemptNo = :attemptNo
    """
)
fun transitionIfStatusAndAttemptMatch(
    @Param("jobId") jobId: Long,
    @Param("newStatus") newStatus: JobStatus,
    @Param("expectedStatus") expectedStatus: JobStatus,
    @Param("attemptNo") attemptNo: Int,
    @Param("now") now: Instant
): Int
```

이름 붙은 래퍼들은 이 원형에 상태 쌍만 고정해 넣은 default 메서드다.

```kotlin
fun startProcessingIfAttemptMatches(jobId: Long, attemptNo: Int, now: Instant): Int =
    transitionIfStatusAndAttemptMatch(jobId, JobStatus.PROCESSING, JobStatus.HOLDING, attemptNo, now)

/** PROCESSING 인 시도만 FAILED 로 내린다. */
fun failIfProcessing(jobId: Long, attemptNo: Int, now: Instant): Int =
    transitionIfStatusAndAttemptMatch(jobId, JobStatus.FAILED, JobStatus.PROCESSING, attemptNo, now)
```

완료 전이만 resultUrl을 같이 써야 해서 별도 쿼리로 분리되어 있다.

```kotlin
@Transactional
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(
    """
    UPDATE Job j
    SET j.status = JobStatus.COMPLETED,
        j.resultUrl = :resultUrl, j.updatedAt = :now
    WHERE j.id = :jobId
      AND j.status = JobStatus.PROCESSING
      AND j.attemptNo = :attemptNo
    """
)
fun completeIfAttemptMatches(
    @Param("jobId") jobId: Long,
    @Param("resultUrl") resultUrl: String,
    @Param("attemptNo") attemptNo: Int,
    @Param("now") now: Instant
): Int
```

이 세 메서드 모두 반환값이 `Int`, 즉 UPDATE가 실제로 몇 행에 반영됐는지다. 0이면 조건이 안 맞았다는 뜻이고 그 결과를 무시하는 것 자체가 동시성 제어다. `GenerationWorker`는 조회한 job을 바로 실행하지 않고, 먼저 선점을 시도한다.

```kotlin
fun dispatchPendingJobs() {
    val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
    for (job in jobs) {
        if (!claim(job)) continue
        workerExecutor.execute { jobProcessor.runGeneration(job) }
    }
}

private fun claim(job: Job): Boolean {
    val updated = jobRepository.startProcessingIfAttemptMatches(
        job.persistedId, job.attemptNo, Instant.now()
    )
    if (updated == 0) {
        log.info("다른 워커가 선점했거나 무효한 작업 무시: jobId={}, attemptNo={}", job.persistedId, job.attemptNo)
        return false
    }
    return true
}
```

선점 UPDATE가 0건이면 다른 워커가 이미 가져간 것이므로 그냥 넘어간다. 선점에 성공한 job만 `workerExecutor.execute { ... }`로 위임하는데, 이 실행기가 `WorkerExecutorConfig`가 만드는 고정 크기 스레드 풀이다.

```kotlin
@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerExecutorConfig {

    @Bean("generationWorkerExecutor")
    fun generationWorkerExecutor(workerProperties: WorkerProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = workerProperties.concurrency
            maxPoolSize = workerProperties.concurrency
            queueCapacity = 0
            setThreadNamePrefix("generation-worker-")
            initialize()
        }
}
```

`corePoolSize == maxPoolSize`로 스레드 수를 고정하고 `queueCapacity = 0`으로 대기열을 두지 않는다 — 동시에 처리할 양만큼만 스레드를 만들고 그 이상은 다음 스케줄 주기로 미룬다. 이 스레드 풀이 도입되면서 "선점에 성공한 job만 넘긴다"는 규칙이 장식이 아니라 필수 조건이 된다.

`JobLifecycleService`는 dirty checking에서 CAS로 완전히 바뀌었다. before(step3):

```kotlin
@Transactional
fun confirm(jobId: Long, resultUrl: String) {
    val job = getJob(jobId)
    job.complete(resultUrl)
    ledgerRepository.save(LedgerEntry.confirm(job.organizationId, jobId))
    log.info("confirm 완료: jobId={}", jobId)
}
```

after(step4):

```kotlin
@Transactional
fun confirm(job: Job, resultUrl: String) {
    val jobId = job.persistedId
    val updated = jobRepository.completeIfAttemptMatches(jobId, resultUrl, job.attemptNo, Instant.now())
    if (updated == 0) {
        log.info("이미 무효화된 시도, confirm 무시: jobId={}, attemptNo={}", jobId, job.attemptNo)
        return
    }
    ledgerRepository.save(LedgerEntry.confirm(job.organizationId, jobId))
    log.info("confirm 완료: jobId={}, attemptNo={}", jobId, job.attemptNo)
}
```

`getJob(jobId)`로 다시 조회해 엔티티를 고쳐 쓰는 대신, 호출자가 들고 있던 `job` 객체의 `attemptNo`를 그대로 CAS 조건에 실어 보낸다. "지금 DB의 상태가 이 job이 기억하는 상태와 같을 때만" 반영하는 것이 CAS의 전부다. `markFailed`도 같은 모양으로 바뀌었다.

`Job.attemptNo`(`@Column(nullable = false) var attemptNo: Int = 0`)는 이번 단계에서는 계속 0에 머문다. 재시도 자체는 아직 없다 — attemptNo를 올리는 코드는 이 브랜치 어디에도 없다. 그런데도 모든 CAS 쿼리와 모든 상태 전이 메서드가 attemptNo를 파라미터로 받는다. 다음 단계에서 재시도가 생겼을 때 그 값을 받을 자리를 미리 파 두는 것이다. 왜 상태만으로는 부족한지 타임라인으로 보면 이렇다.

1. 시도 1이 `attemptNo=0`으로 선점에 성공한다(`status=PROCESSING`). 스텁 호출 도중 응답 없이 멈춘다.
2. (다음 단계의 회수 로직이) 시도 1을 포기하고 `attemptNo`를 1로 올려 시도 2를 다시 선점시킨다. 여전히 `status=PROCESSING`, 이제 `attemptNo=1`. 시도 2는 스텁 호출을 진행 중이다.
3. 멈춰 있던 시도 1이 뒤늦게 깨어나 자신이 쥐고 있던 `attemptNo=0`으로 완료를 시도한다. `WHERE attemptNo = 0` 조건이 현재 DB의 `1`과 어긋나 0건 — 무시된다.
4. 시도 2가 끝나 `attemptNo=1`로 완료를 시도하면 조건이 맞아 반영된다. 진짜 결과가 남는다.

attemptNo 검사가 없어 상태만 봤다면 3번의 조건은 `status=PROCESSING`뿐이었을 것이다. 2번에서 시도 2도 다시 `status=PROCESSING`을 만들어 놓았으니, 이 조건은 시도 1과 시도 2 중 누가 쓴 PROCESSING인지 구별하지 못한다. 그러면 3번에서 시도 1의 낡은 결과가 그대로 반영돼 job이 COMPLETED가 되고, 4번에서 시도 2가 진짜 결과로 완료를 시도해도 이미 PROCESSING이 아니라서 0건 — 진짜 결과가 조용히 버려진다. attemptNo는 "지금 PROCESSING인가"가 아니라 "이 PROCESSING이 내가 시작한 그 시도가 맞는가"를 구별해 준다.

## 테스트가 보장하는 것

- `JobRepositoryTest` (신규): CAS 쿼리 자체를 검증한다. attemptNo가 일치하면 전이되고 불일치하면 0행이며 상태가 그대로 유지됨을, 그리고 완료된 job은 같은 attemptNo로 재시작할 수 없고 `failIfProcessing`은 PROCESSING이 아닌 job에는 손대지 않음을 함께 확인한다.

```kotlin
@Test
fun `attemptNo가 불일치하면 0행이며 상태가 유지된다`() {
    val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

    val updated = jobRepository.startProcessingIfAttemptMatches(job.persistedId, 5, Instant.now())

    assertThat(updated).isZero()
    assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
        .isEqualTo(JobStatus.HOLDING)
}
```

- `JobLifecycleServiceTest`: `confirm`/`markFailed`가 CAS 실패 시 원장도 남기지 않고 상태도 바꾸지 않음을 확인한다. 특히 `완료된 작업의 늦은 실패는 무시한다` 테스트가 위 타임라인의 3번 같은 "이미 종결된 job에 뒤늦게 도착한 쓰기"를 직접 재현한다 — completeIfAttemptMatches로 COMPLETED를 만든 뒤 같은 attemptNo로 markFailed를 호출해도 상태가 COMPLETED로 남는지 검증한다.

- `GenerationJobProcessorTest`: `confirm`이 `job` 객체를(내부의 attemptNo를 포함해), `markFailed`가 `jobId`와 `attemptNo`를 인자로 받아 그대로 전달하는지 검증한다.

- `GenerationWorkerUnitTest`: 선점 실패 시 실행기로 넘기지 않는 것을, 그리고 선점에 성공한 job은 실행기로 넘어가고 배치 안 다른 job의 선점 실패가 나머지 처리를 막지 않는 것을 확인한다.

```kotlin
@Test
fun `다른 워커가 선점한 작업은 외부 처리기로 넘기지 않는다`() {
    doReturn(listOf(job)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
    doReturn(0).whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())

    worker.dispatchPendingJobs()

    verify(jobProcessor, never()).runGeneration(job)
}
```

## 아직 못 막는 것

워커 프로세스가 죽으면 그 job은 PROCESSING에 영원히 남는다. `GenerationWorker.dispatchPendingJobs`가 조회하는 대상은 `findByStatusOrderByIdAsc(JobStatus.HOLDING, ...)` 하나뿐이다 — PROCESSING 상태로 멈춘 job을 다시 찾는 조회도, 오래 머문 PROCESSING을 되돌리는 코드도 이 브랜치 어디에도 없어 그대로 방치된다. FAILED가 된 job도 마찬가지로 끝이다. `markFailed`는 `failIfProcessing`으로 상태를 FAILED로 CAS할 뿐, 재시도를 태우거나 환불 원장을 남기는 코드가 없다. 실제로 `LedgerType`은 `HOLD`, `CONFIRM`, `CHARGE` 세 값뿐이고 REFUND에 해당하는 타입 자체가 존재하지 않는다 — 이미지 생성이 실패하면 hold된 크레딧이 아무에게도 쓰이지 않고 그대로 묶여 있게 된다.

## 다음 단계 예고

step5-recovery는 이 단계가 비워 둔 두 구멍을 메운다. 하나는 회수(recovery) — 오래 PROCESSING에 머문 job을 하트비트나 타임아웃 기준으로 찾아내 attemptNo를 올리고 다시 HOLDING으로 되돌려 재시도를 태우는 것이고, 다른 하나는 재시도가 소진된 job을 최종적으로 실패 처리하며 hold된 크레딧을 환불하는 것이다. attemptNo는 이 단계에서 이미 모든 CAS 쿼리에 배선되어 있으므로, step5는 그 값을 실제로 증가시키는 로직만 얹으면 된다.

## 명령어

```bash
# 전체 테스트 (Docker 필요 — Testcontainers로 MySQL을 띄운다)
./gradlew test
# 이 단계에서 새로 생긴 CAS 테스트만
./gradlew test --tests "com.example.credit_system_kotlin.job.repository.JobRepositoryTest"
# 워커의 선점 로직만 (Docker 불필요, 순수 단위 테스트)
./gradlew test --tests "com.example.credit_system_kotlin.job.worker.GenerationWorkerUnitTest"
# 이전 단계와의 차이 전체
git diff step3-idempotency step4-state-machine
```
