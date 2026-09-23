# step6-resilience — 인프라 장애 내성과 감사

step5-recovery 가 만든 회수 장치(heartbeat, timeout, 재시도/환불) 자체가 깨졌을 때 무엇이 일어나야 하는지를 정한다. 코드 내용은 `main` 과 같다 — 이 브랜치가 체인의 완성본이다.

- 이전 단계: `step5-recovery`
- 다음 단계: 없음 (체인의 끝, 내용상 `main` 과 같다)

## 이전 단계의 문제

step5 는 두 겹의 회수 장치를 만들었다. heartbeat 만료로 죽은 워커의 job 을 빠르게 잡고, `updatedAt` 기반 정체 감지로 heartbeat 마저 없는 경우를 느리게 잡는다. 둘 다 "회수 로직이 정상 동작한다"는 전제 위에 서 있다. 그 전제가 깨지는 네 가지 경로가 이 단계의 출발점이다.

**1. Redis 순단 → 대량 오탐, 그것도 즉시가 아니라 timeout 이 지난 뒤에.** step5 의 `HeartbeatRegistry` 에는 try/catch 가 한 군데도 없다. Redis 호출이 던지는 예외가 그대로 위로 새어나가고, 이게 두 가지 확정적인 결과를 낳는다.

읽기 쪽: `findExpiredAttempts()` 의 예외가 `DeadJobRecoveryTask.scan()` 을 그 주기에서 통째로 죽인다 — Redis 순단 동안에는 회수가 아예 일어나지 않는다. 조회 실패가 곧바로 만료 판정이 되는 게 아니다.

쓰기 쪽 — 대량 오탐의 진짜 경로. `refreshHeartbeat` 는 `startHeartbeat` 이 건 `executor.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, ...)` 위에서 돈다. `ScheduledExecutorService` 계약상 주기 작업이 예외를 던지면 그 작업은 취소되고 다시는 실행되지 않는다. Redis 가 끊긴 순간 처리 중이던 job 전부의 heartbeat 갱신 스케줄이 영구히 죽는다.

```
T+0s    Redis 순단. 워커 A·B·C 는 멀쩡히 job 을 처리 중이다
T+0s    refreshHeartbeat 가 예외 → scheduleAtFixedRate 작업이 취소된다. 세 job 모두 갱신이 영구히 멈춘다
T+0s    같은 순간 scan() 도 findExpiredAttempts() 예외로 중단. 이 주기에는 회수가 아예 없다
T+2s    Redis 복구. 하지만 죽은 갱신 스케줄은 되살아나지 않는다
T+10s   ZSET 의 score 가 만료 시각을 지난다
T+10s~  스캔이 정상 재개되고, 살아 있는 A·B·C 의 job 을 만료로 판정해 FAILED 로 내린다
```

문제는 조회 실패를 만료로 오해하는 것만이 아니라, 방어 코드 자신이 인프라 예외에 무방비라 조용히 기능을 잃는다는 것이다.

**2. 배치 루프 전체가 한 예외에 막힌다.** step5 의 `DeadJobRecoveryTask.scan()` 은 `markExpiredJobsAsFailed()` → `markStalledJobsAsFailed()` → `retryOrRefundFailedJobs()` 를 그냥 순서대로 호출했다. 어느 한 단계, 혹은 그 안의 job 한 건이 예외를 던지면 그 주기의 나머지는 통째로 건너뛴다. 매번 같은 문제 있는 항목에서 멈추면 나머지 job 은 영원히 처리되지 않는다.

**3. 잔액과 원장이 어긋나도 아무도 모른다.** step2 부터 모든 잔액 변동은 조건부 UPDATE + 원장 기록으로 이루어져, 이론상 `balance` 와 원장 합계는 항상 일치해야 한다. 하지만 마이그레이션 실수, 수동 DB 조작, 미처 발견 못한 버그가 그 사이에 끼어들 수 있다. step5 까지는 이 둘이 어긋나도 감지할 방법이 없었다.

**4. 멱등키가 무한히 쌓인다.** `IdempotencyKey` 는 한 번 쓰이고 나면 다시 조회되지 않는데, 지우는 장치가 없어 테이블이 계속 자란다.

## 무엇이 새로 생겼나

`git diff --stat step5-recovery step6-resilience` 기준:

- `heartbeat/HeartbeatRegistry.kt` — `refreshHeartbeat` 를 try/catch 로 감싸 주기 갱신 스케줄이 예외로 죽지 않게 한다
- `heartbeat/HeartbeatProperties.kt` — `require(refreshInterval < timeout)` 등 설정 불변식 추가
- `global/config/WorkerProperties.kt` — `require(batchSize >= 1 && concurrency >= 1)` 추가
- `global/config/AppProperties.kt` — `Idempotency(retentionDays)` 하위 설정 + 불변식 추가
- `job/scheduling/DeadJobRecoveryTask.kt` — 세 단계와 각 항목을 개별 try/catch 로 격리
- `job/scheduling/IdempotencyKeyCleanupTask.kt` (신규) — 보존 기간 지난 멱등키 배치 삭제. 매일 새벽 2시(`app.scheduling.idempotency-cleanup-cron`), 보존 기간은 `app.idempotency.retention-days`(기본 7일)
- `job/repository/IdempotencyKeyRepository.kt` — `findIdsCreatedBefore`, `deleteByIdIn` 추가
- `job/domain/IdempotencyKey.kt` — `createdAt` 에 인덱스(`idx_idem_created_at`) 추가, 정리 쿼리가 이 컬럼을 스캔하기 때문
- `job/repository/JobRepository.kt` — `rollbackToHoldingIfProcessing` 추가 (선점 롤백용)
- `job/worker/GenerationWorker.kt` — executor 위임 실패 시 선점 롤백 + 이번 주기 중단
- `job/worker/GenerationJobProcessor.kt` — 생성 중 예기치 못한 런타임 예외도 FAILED 처리, 결과 반영 실패는 흡수
- `organization/domain/Organization.kt` — `initialBalance` 필드 추가 (대사 쿼리의 기준값)
- `ledger/repository/LedgerRepository.kt` — `findBalanceChecksAfter` 대사 쿼리 추가
- `ledger/dto/LedgerBalanceCheck.kt` (신규) — 대사 결과 DTO
- `ledger/scheduling/LedgerReconciliationTask.kt` (신규) — 1분마다(`app.scheduling.reconciliation-interval-millis`, 기본 60000ms) `initialBalance + 원장합 == balance` 대사, 불일치 시 ERROR 로그
- `application.yml` — 위 설정 키들의 기본값 추가

## 핵심 코드 읽기

### 1. `HeartbeatRegistry` — `refreshHeartbeat` 만 예외를 삼킨다

step6 이 여기 붙이는 것은 `refreshHeartbeat` 하나에 대한 try/catch 뿐이다. **step5**:

```kotlin
private fun refreshHeartbeat(attempt: JobAttempt) {
    val expireAt = Instant.now().epochSecond + heartbeatProperties.timeoutSeconds
    redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
}
```

**step6**:

```kotlin
private fun refreshHeartbeat(attempt: JobAttempt) {
    val expireAt = Instant.now().epochSecond + heartbeatProperties.timeoutSeconds
    try {
        redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
    } catch (e: RuntimeException) {
        log.warn("heartbeat 갱신 실패: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo, e)
    }
}
```

`refreshHeartbeat` 는 `scheduleAtFixedRate` 위에서 도는 주기 작업이라, step5 처럼 예외가 새어나가면 그 작업 자체가 영구히 취소된다. 이 메서드만은 예외를 삼켜야 한다 — 이번 갱신 한 번을 놓치더라도 다음 주기에 다시 시도할 기회를 남겨야 하기 때문이다.

`findExpiredAttempts`, `hasLiveHeartbeat`, `removeHeartbeat` 는 반대로 try/catch 가 없다. Redis 예외는 그대로 호출자에게 전파된다. 이 셋은 반복 스케줄 위에서 도는 게 아니라 `DeadJobRecoveryTask` 가 그때그때 호출하는 코드라, 예외를 여기서 삼켜 잘못된 값(빈 집합, `true`)으로 둔갑시키기보다 위로 흘려보내 `DeadJobRecoveryTask` 쪽의 단계별 try/catch(아래 2절)가 "이번 주기는 건너뛴다"로 처리하게 맡기는 편이 실패를 숨기지 않는다. `removeUnparseableMember` 도 마찬가지로 try/catch 없이 예외가 전파된다 — 깨진 멤버 하나를 지우다 Redis 가 끊기면 그 사실이 그대로 드러나야 한다.

### 2. `DeadJobRecoveryTask` — 단계 격리와 항목 격리

```kotlin
@Scheduled(fixedDelayString = $$"${app.scheduling.dead-job-scan-interval-millis:5000}")
fun scan() {
    try { markExpiredJobsAsFailed() }
    catch (e: RuntimeException) { log.error("heartbeat 만료 회수 단계 실패, 이번 주기 건너뜀", e) }
    try { markStalledJobsAsFailed() }
    catch (e: RuntimeException) { log.error("PROCESSING 정체 회수 단계 실패, 이번 주기 건너뜀", e) }
    try { retryOrRefundFailedJobs() }
    catch (e: RuntimeException) { log.error("FAILED job 재검토 단계 실패, 이번 주기 건너뜀", e) }
}
```

각 단계 안에서도 항목 하나씩 감싼다:

```kotlin
/** 한 건의 실패가 같은 주기의 나머지를 막지 않도록 항목 단위로 격리한다. */
private fun recoverExpired(attempt: JobAttempt) {
    try {
        val updated = jobRepository.failIfProcessing(attempt.jobId, attempt.attemptNo, Instant.now())
        if (updated == 1) log.info("heartbeat 만료로 FAILED 전이: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo)
        heartbeatRegistry.removeHeartbeat(attempt.jobId, attempt.attemptNo)
    } catch (e: RuntimeException) {
        log.warn("heartbeat 만료 job 회수 실패", e)
    }
}
```

`recoverStalled`, `retryOrRefund` 도 동일한 형태다. 단계 수준 격리는 "이 배치 안의 다른 단계"를, 항목 수준 격리는 "같은 단계 안의 다른 job"을 지킨다. 두 겹이 겹쳐야 한 조직의 문제(예: 조직 행 잠김으로 환불 UPDATE 가 타임아웃)가 다른 조직의 정상 job 처리를 막지 못한다.

### 3. `LedgerReconciliationTask` / `LedgerBalanceCheck` — 대사

`LedgerRepository.findBalanceChecksAfter` 는 조직마다 `balance`, `initialBalance`, 원장 합계(`COALESCE(SUM(l.amount), 0L)`)를 한 번에 묶어 `LedgerBalanceCheck` DTO 로 뽑는 JPQL 이다(`o.id > :lastId` 로 커서 페이지네이션):

```kotlin
@Query("""
    SELECT new com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck(
        o.id, o.balance, o.initialBalance, COALESCE(SUM(l.amount), 0L))
    FROM Organization o
    LEFT JOIN LedgerEntry l ON l.organizationId = o.id
    WHERE o.id > :lastId
    GROUP BY o.id, o.balance, o.initialBalance
    ORDER BY o.id
""")
fun findBalanceChecksAfter(@Param("lastId") lastId: Long, pageable: Pageable): List<LedgerBalanceCheck>
```

```kotlin
private fun isBalanceConsistent(balanceCheck: LedgerBalanceCheck): Boolean {
    val expected = balanceCheck.initialBalance + balanceCheck.ledgerSum
    if (expected == balanceCheck.balance) return true
    log.error(
        "원장 대사 불일치 발견: organizationId={}, balance={}, expected={}, initialBalance={}, ledgerSum={}, diff={}",
        balanceCheck.organizationId, balanceCheck.balance, expected,
        balanceCheck.initialBalance, balanceCheck.ledgerSum, balanceCheck.balance - expected
    )
    return false
}
```

`reconcile()` 은 `lastId` 커서로 100건씩(`RECONCILE_BATCH_SIZE`) 페이지네이션하며 조직 전체를 훑고, 항목 하나가 예외를 던져도 다음 조직으로 넘어간다(같은 try/catch 패턴). `Organization.initialBalance` 는 조직 생성 시점의 잔액을 보존한 값이다(step6 이전엔 `balance` 만 있어 최초값을 되짚을 수 없었다). "지금 잔액이 최초 잔액 + 그 뒤 모든 원장 합계와 같은가"가 대사의 전부다. `LedgerReconciliationTaskTest` 의 "배치 크기를 넘는 조직도 모두 검사한다" 테스트는 205개 조직 중 120번째의 불일치만 정확히 잡아내는 것으로 이를 확인한다.

여기서도 **막는 것이 아니라 알아채는 것**이다. 불일치를 발견해도 `balance` 나 원장을 고치는 코드는 없다. ERROR 로그만 남긴다 — 자동 교정은 원인 파악 없이 데이터를 더 망칠 위험이 있어, 사람이 로그를 보고 판단하도록 남겨둔 것으로 읽힌다.

### 4. `IdempotencyKeyCleanupTask`

```kotlin
@Scheduled(
    cron = $$"${app.scheduling.idempotency-cleanup-cron:0 0 2 * * *}",
    zone = $$"${app.scheduling.timezone:Asia/Seoul}"
)
fun cleanup() {
    val cutoff = Instant.now().minus(appProperties.idempotency.retentionDays, ChronoUnit.DAYS)
    var deletedCount = 0
    do {
        val ids = idempotencyKeyRepository.findIdsCreatedBefore(cutoff, PageRequest.of(0, CLEANUP_BATCH_SIZE))
        if (ids.isEmpty()) break
        try {
            deletedCount += idempotencyKeyRepository.deleteByIdIn(ids)
        } catch (e: RuntimeException) {
            log.warn("멱등키 정리 배치 삭제 실패, 이번 주기 중단", e)
            break
        }
    } while (ids.size == CLEANUP_BATCH_SIZE)
}
```

500개씩(`CLEANUP_BATCH_SIZE`) 배치로 조회하고 지운다. 주석대로("보존 기간이 7일이라 하루 한 번이면 충분하다. 트래픽이 한산한 시각에 돌려 삭제 락이 멱등키 INSERT 와 부딪힐 여지를 줄인다") 새벽 2시로 잡혀 있다. 배치 삭제가 실패하면 그 주기를 중단하고 다음 날 다시 시도한다 — 여기서도 한 실패가 무한 재시도로 이어지지 않는다.

### 5. `HeartbeatProperties` — 설정 불변식

```kotlin
init {
    require(timeoutSeconds >= 1) { "heartbeat timeout-seconds는 1 이상이어야 합니다: $timeoutSeconds" }
    require(refreshIntervalSeconds >= 1) { "heartbeat refresh-interval-seconds는 1 이상이어야 합니다: $refreshIntervalSeconds" }
    require(refreshIntervalSeconds < timeoutSeconds) {
        "heartbeat refresh-interval-seconds는 timeout-seconds보다 작아야 합니다. " +
            "그렇지 않으면 갱신 주기가 돌아오기 전에 heartbeat가 만료되어 살아있는 job이 회수됩니다: " +
            "refresh-interval-seconds=$refreshIntervalSeconds, timeout-seconds=$timeoutSeconds"
    }
}
```

`refreshIntervalSeconds >= timeoutSeconds` 인 설정은 heartbeat 가 갱신되기 전에 이미 만료 판정이 나버려 정상 워커의 job 이 계속 회수된다. 이건 런타임에 우연히 드러나는 버그가 아니라 설정값만 보고 정적으로 알 수 있는 모순이므로 `require` 로 부팅 시점에 막는다. 잘못된 설정으로 뜬 채 운영되다 사고를 내는 것보다 부팅 실패가 훨씬 싸다.

### 6. `GenerationWorker` — executor 거부 시 선점 되돌리기

```kotlin
private fun dispatch(job: Job): Boolean {
    return try {
        workerExecutor.execute { jobProcessor.runGeneration(job) }
        true
    } catch (e: RuntimeException) {
        log.warn("생성 작업 executor 위임 실패, 이번 주기 중단: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        rollbackToHolding(job)
        false
    }
}

private fun rollbackToHolding(job: Job) {
    try { jobRepository.rollbackToHoldingIfProcessing(job.persistedId, job.attemptNo, Instant.now()) }
    catch (e: RuntimeException) { log.error("선점 롤백 실패, timeout 회수 대기: jobId={}, attemptNo={}", job.id, job.attemptNo, e) }
}
```

`claim()` 이 이미 job 을 `PROCESSING` 으로 옮겨놨는데 `workerExecutor.execute(...)` 가 (스레드 풀 포화 등으로) 거부되면, 아무도 처리하지 않는 채 `PROCESSING` 에 멈춰 있게 된다. `rollbackToHoldingIfProcessing` 은 `PROCESSING` 인 시도만 다시 `HOLDING` 으로 돌리는 조건부 UPDATE 라 다음 워커 주기가 다시 집어갈 수 있다. 롤백 자체가 실패해도 예외를 삼키고 로그만 남긴다 — 결국 timeout 기반 정체 회수가 그 job 을 잡아준다. dispatch 가 실패하면 `dispatchPendingJobs()` 루프도 그 지점에서 멈춘다 — 실행기가 이미 포화 상태라는 신호이므로 같은 배치의 나머지를 계속 밀어넣기보다 다음 주기로 미루는 쪽을 택한다.

### 7. `GenerationJobProcessor` — 결과 반영 실패 처리

```kotlin
private fun generateOrMarkFailed(job: Job): String? =
    try {
        stubClient.generate(job.prompt)
    } catch (_: StubGenerationException) {
        jobLifecycleService.markFailed(job.persistedId, job.attemptNo)
        null
    } catch (e: RuntimeException) {
        log.error("생성 중 예기치 못한 예외 발생: jobId={}, attemptNo={}", job.persistedId, job.attemptNo, e)
        jobLifecycleService.markFailed(job.persistedId, job.attemptNo)
        null
    }

/** 결과 반영에 실패하면 job 은 PROCESSING 으로 남아 정체 회수 대상이 된다. */
private fun confirm(job: Job, resultUrl: String) {
    try {
        jobLifecycleService.confirm(job, resultUrl)
    } catch (e: RuntimeException) {
        log.error("생성 결과 반영 실패, timeout 회수 대기: jobId={}, attemptNo={}", job.persistedId, job.attemptNo, e)
    }
}
```

생성 자체가 예상한 실패(`StubGenerationException`)든 예기치 못한 런타임 예외든 `markFailed` 로 수렴시켜 `DeadJobRecoveryTask` 의 재시도/환불 경로에 태운다. 반대로 생성은 성공했는데 `confirm`(job 을 `COMPLETED` 로 옮기는 쓰기)이 실패하면 `markFailed` 를 부르지 않는다. job 이 이미 완료된 생성 결과를 갖고 `PROCESSING` 에 남아 있는데 상태를 섣불리 되돌리면 이미 끝난 작업을 재시도해 스텁을 다시 부르는 낭비가 생기기 때문이다. step5 의 timeout 정체 회수가 뒤늦게 이 상태를 잡아 재시도든 환불이든 결정하게 맡긴다. 실패 종류에 따라 다른 안전한 착지점으로 보낸다는 원칙이 여기 있다.

## 테스트가 보장하는 것

- `HeartbeatRegistryTest` — `RedisConnectionFailureException` 을 주입해 Redis 장애를 시뮬레이션한다. `refreshHeartbeat가 Redis 예외를 삼키고 갱신 스레드를 죽이지 않는다`는 `zSetOperations.add` 가 매번 예외를 던지는데도 `startHeartbeat` 이 반환한 `future` 가 계속 살아 있고(`isDone` 이 `false`) `add` 가 여러 차례 재시도됨을 확인한다. 반대로 `findExpiredAttempts는 Redis 예외를 전파한다`, `hasLiveHeartbeat는 Redis 예외를 전파한다`, `removeHeartbeat는 Redis 예외를 전파한다`, `stopHeartbeat은 removeHeartbeat가 Redis 예외를 던지면 전파한다`는 `assertThatThrownBy` 로 예외가 그대로 호출자까지 올라오는 것을 확인한다:

  ```kotlin
  whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
      .thenThrow(RedisConnectionFailureException("redis down"))

  assertThatThrownBy { registry.findExpiredAttempts() }
      .isInstanceOf(RedisConnectionFailureException::class.java)
  ```

- `LedgerReconciliationTaskTest` — `잔액이 원장과 어긋나면 ERROR로 경보한다`: 원장에는 500 charge 만 있는데 `organizationRepository.addBalance(org.persistedId, 999L, ...)` 로 잔액을 강제로 어긋나게 만들고, `ListAppender` 로 `ERROR` 로그가 정확히 한 건(`organizationId=...` 포함) 남는지 확인한다. `배치 크기를 넘는 조직도 모두 검사한다`(205개 조직, 커서 페이지네이션)도 여기 있다.

- `HeartbeatPropertiesTest` — 경계값까지 확인한다: `refresh interval이 timeout과 같아도 거부한다`(`HeartbeatProperties(10, 10)` → `IllegalArgumentException`)로 `<` 이지 `<=` 가 아님을, `timeout이 1 미만이면 거부한다` / `refresh interval이 1 미만이면 거부한다` 로 각각의 최솟값을 확인한다.

- `GenerationWorkerUnitTest` — `executor가 거부하면 선점을 롤백하고 이번 주기를 중단한다`: `TaskExecutor { throw TaskRejectedException("pool exhausted") }` 를 직접 주입한 뒤

  ```kotlin
  verify(jobRepository).rollbackToHoldingIfProcessing(eq(1L), eq(0), any<Instant>())
  verify(jobRepository, never()).startProcessingIfAttemptMatches(eq(2L), any<Int>(), any<Instant>())
  ```

  롤백이 호출되는 것과 두 번째 job 의 선점 시도 자체가 일어나지 않는 것(주기 중단)을 함께 확인한다. `executor 위임과 롤백이 모두 실패해도 예외가 새어나가지 않는다`는 이중 실패에서도 스케줄러 스레드가 죽지 않는 것을 확인한다.

이 외에 `DeadJobRecoveryTaskTest` 는 단계/항목 격리(한 job 의 환불 실패가 나머지를 막지 않음, 한 단계의 예외가 다음 단계를 막지 않음)를, `IdempotencyKeyCleanupTaskTest` 는 보존 기간 경계와 510개 배치 삭제를, `GenerationJobProcessorTest` 는 예기치 못한 런타임 예외의 FAILED 처리와 결과 반영 실패 시 `PROCESSING` 유지를, `JobRepositoryTest` 는 `rollbackToHoldingIfProcessing` 이 `PROCESSING` 인 시도만 건드리는 것을 검증한다.

## 여기서도 남는 것

- **대사는 감지일 뿐 교정하지 않는다.** `LedgerReconciliationTask` 는 불일치를 발견해도 `balance` 나 원장을 고치지 않는다. 발견 이후는 사람의 몫이다.
- **단일 인스턴스 스케줄러 전제.** `DeadJobRecoveryTask`, `LedgerReconciliationTask`, `IdempotencyKeyCleanupTask`, `GenerationWorker` 모두 `@Scheduled` 로만 되어 있고 분산 락이 없다. 인스턴스를 여러 개 띄우면 같은 주기에 여러 인스턴스가 같은 작업을 중복 실행한다. CAS 는 중복 실행 자체를 안전하게 만들지만, 대사·정리 로그가 인스턴스 수만큼 중복되는 것은 그대로 남는다.
- **생성 호출이 스텁이라 외부 API 실패 모드가 없다.** `GenerationStubClient` 는 `app.stub.failure-rate` 로 확률적으로 `StubGenerationException` 을 던지는 인메모리 시뮬레이션이다. 타임아웃, 429 rate limit, 부분 응답 같은 실제 외부 API 실패 형태는 다루지 않는다.
- **Redis 복구 직후 갱신되지 못한 heartbeat 가 만료로 보이는 구간은 보호되지 않는다.** Redis 순단 동안 `refreshHeartbeat` 는 예외를 삼키고 다음 주기를 기다릴 뿐 그 사이 갱신하지 못한 score 를 되돌리지 않는다. Redis 가 복구된 시점에 마지막으로 기록된 score 가 이미 과거라면, 워커는 멀쩡히 살아서 job 을 처리 중이어도 `findExpiredAttempts`/`hasLiveHeartbeat` 는 그 워커를 만료로 판정한다. 이 구간에서 살아있는 job 이 오탐으로 회수될 수 있다는 위험은 남는다.
- **멱등키 정리는 크론 실행 실패 자체를 감시하지 않는다.** 새벽 2시에 애플리케이션이 재기동 중이었다면 그날 정리는 건너뛰고 다음 날까지 기다린다. 별도 알림은 없다.

## 전체 되짚기

| 단계 | 막은 사고 |
|---|---|
| step0-naive | (없음) 잔액이 음수가 되고, 예외가 그대로 500 으로 샌다 |
| step1-validation | 잘못된 입력과 잔액 부족을 애플리케이션 레벨에서 거른다 |
| step2-atomic-balance | 읽기-쓰기 사이 경합으로 잔액이 음수가 되는 것을 조건부 UPDATE 로 막는다 |
| step3-idempotency | 같은 요청의 중복 처리(중복 충전, 중복 job 생성)를 유니크 제약으로 막는다 |
| step4-state-machine | 여러 워커가 같은 job 을 동시에 집거나, 늦게 도착한 쓰기가 최신 상태를 덮는 것을 CAS 로 막는다 |
| step5-recovery | 워커가 죽어 job 이 영원히 멈추거나 돈이 묶인 채 끝나는 것을 heartbeat + timeout 회수로 막는다 |
| step6-resilience | 회수 장치 자체(Redis, 배치 루프)가 깨졌을 때 대량 오탐이나 전체 정지로 번지는 것을 막고, 잔액-원장 불일치를 알아챈다 |

관통하는 원칙:

- **경합은 DB 한 문장으로 닫는다.** 읽고 판단하고 쓰는 세 단계를 조건부 UPDATE 하나로 합치면 그 사이에 끼어들 틈이 없어진다(step2, step4).
- **최종 방어선은 유니크 제약이다.** 애플리케이션의 사전 조회는 그 사이에 다른 요청이 끼어드는 것을 막지 못한다. DB 가 진 쪽에게 예외를 던지게 하고, 그 예외를 정상 흐름으로 번역한다(step3).
- **늦게 도착한 쓰기는 CAS 로 무시한다.** 상태와 시도 번호를 함께 검사하면, 먼저 시작했지만 나중에 끝난 시도의 결과가 최신 상태를 덮지 못한다(step4).
- **막는 것과 알아채는 것은 다른 도구다.** 조건부 UPDATE 와 유니크 제약은 사고 자체를 막는다. `LedgerReconciliationTask` 는 사고가 이미 일어났는지 알아챌 뿐 고치지 않는다. 방어 로직도 코드이고 그것도 깨질 수 있다는 것을 받아들이면, 막는 장치 옆에 알아채는 장치를 따로 둬야 한다는 결론이 나온다(step6).

## 명령어

```
# 이 브랜치에서 테스트 전체 실행 (Docker 필요 — MySQL + Redis, 26개 테스트 클래스 124개 테스트)
./gradlew test

# step5 대비 이 단계가 무엇을 더했는지
git diff step5-recovery step6-resilience

# main 과 소스 코드가 같다는 것을 직접 확인 (문서 파일만 다르다)
git diff main step6-resilience --stat

# step0 부터 여기까지 커밋 히스토리
git log --oneline step0-naive..step6-resilience
```
