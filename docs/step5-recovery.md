# step5-recovery — heartbeat 회수와 재시도 / 최종 환불

워커가 죽어도 job 이 영원히 PROCESSING 에 남지 않도록 heartbeat 로 생사를 감시하고, FAILED 로 떨어진 job 을 재시도하거나 최종적으로 환불해 돈이 묶이지 않게 한다.

- 이전 단계: `step4-state-machine`
- 다음 단계: `step6-resilience`

## 이전 단계의 문제

step4 는 attemptNo 를 곁들인 CAS 로 상태 전이를 안전하게 만들었다. `startProcessingIfAttemptMatches`, `completeIfAttemptMatches`, `failIfProcessing` 모두 "이 attemptNo 를 가진, 이 상태의 job" 이라는 조건이 걸린 조건부 UPDATE 다. 여러 워커가 같은 job 을 동시에 집어도 정확히 하나만 전이에 성공한다.

하지만 이 CAS 들은 전이가 "누군가에 의해 시도되었을 때" 정합성을 지킬 뿐, 전이가 아예 시도되지 않는 상황은 다루지 않는다. job 하나의 생애를 따라가 보면 이렇다.

1. 워커가 `startProcessingIfAttemptMatches` 로 job 을 HOLDING → PROCESSING 으로 가져간다.
2. 워커 프로세스가 (배포, OOM, 네트워크 파티션 등으로) 죽는다.
3. job 은 PROCESSING 에 그대로 남는다. `completeIfAttemptMatches` 도 `failIfProcessing` 도 부를 사람이 없다. 이 job 을 위해 건 hold 는 원장에 이미 HOLD 로 기록되고 조직 잔액에서도 차감된 상태라, job 이 영원히 PROCESSING 이면 이 금액은 confirm 도 refund 도 되지 않고 그냥 묶인다.

설령 워커가 살아서 `markFailed` 로 job 을 FAILED 로 내렸다 해도 상황은 크게 다르지 않다. step4 에는 FAILED 를 다시 집어가는 코드가 없다. FAILED 는 재시도도 안 되고 환불도 안 되는, 그냥 끝인 상태다. 결국 두 경로 모두 같은 곳으로 수렴한다 — **hold 된 크레딧이 조직에도, job 에도 속하지 못한 채 붕 뜬다.**

## 무엇이 새로 생겼나

| 파일 | 역할 |
|---|---|
| `heartbeat/HeartbeatRegistry.kt` | Redis ZSET 에 `jobId:attemptNo` 만료 시각을 주기적으로 갱신/조회 |
| `heartbeat/HeartbeatProperties.kt` | heartbeat 타임아웃/갱신 주기 설정 바인딩 |
| `heartbeat/JobAttempt.kt` | ZSET 멤버 문자열(`"jobId:attemptNo"`)과 값 객체 간 직렬화/파싱 |
| `job/scheduling/DeadJobRecoveryTask.kt` | 만료 회수 → 정체 회수 → 재시도/환불, 세 단계를 주기 실행 |
| `job/domain/JobStatus.kt` | `REFUNDED` 추가 |
| `job/repository/JobRepository.kt` | `refundIfFailed`, `incrementAttemptForRetry`, `findByStatusAndUpdatedAtBeforeOrderByIdAsc` 추가 |
| `job/service/JobLifecycleService.kt` | `retry`, `finalRefund` 추가 |
| `job/worker/GenerationJobProcessor.kt` | 처리 시작/종료에 heartbeat 시작/정지 연동 |
| `ledger/domain/LedgerEntry.kt` / `LedgerType.kt` | `REFUND` 타입과 `LedgerEntry.refund(...)` 팩토리 추가 |
| `global/config/AppProperties.kt` | `processing.timeoutSeconds`(정체 판정 기준), `generation.maxAttempts` 추가 |
| `CreditSystemKotlinApplication.kt` | `HeartbeatProperties` 를 `@EnableConfigurationProperties` 에 등록 |

job 상태 다이어그램은 이렇게 확장된다.

```
HOLDING --(워커가 집음)--> PROCESSING --(성공)--> COMPLETED
                              |
                              |--(생성 실패, 또는 회수됨)--> FAILED
                                                              |
                                                              |--(attemptNo+1 < maxAttempts)--> HOLDING (재시도)
                                                              |
                                                              |--(attemptNo+1 >= maxAttempts)--> REFUNDED (최종)
```

설정 키(`application.yml`):

| 키 | 기본값 | 의미 |
|---|---|---|
| `app.heartbeat.timeout-seconds` | 10 | heartbeat 하나가 이 시간 동안 갱신되지 않으면 만료 |
| `app.heartbeat.refresh-interval-seconds` | 5 | 워커가 heartbeat 를 다시 찍는 주기 |
| `app.processing.timeout-seconds` | 60 | `updatedAt` 이 이보다 오래된 PROCESSING job 을 정체로 간주 |
| `app.generation.max-attempts` | 3 | 이 값에 도달하면 재시도 대신 최종 환불 |
| `app.scheduling.dead-job-scan-interval-millis` | 5000 | `DeadJobRecoveryTask.scan()` 실행 주기 |
| `app.scheduling.worker-interval-millis` | 500 | `GenerationWorker` 폴링 주기(step4 부터 있던 값, 대조용) |

## 핵심 코드 읽기

### 1. `HeartbeatRegistry` — ZSET 갱신과 만료 조회

워커가 job 을 잡으면 `startHeartbeat` 로 스케줄을 걸고, score 는 "지금부터 timeout 초 뒤" 시각으로 계속 미뤄 둔다.

```kotlin
fun startHeartbeat(jobId: Long, attemptNo: Int): ScheduledFuture<*> {
    val attempt = JobAttempt(jobId, attemptNo)
    refreshHeartbeat(attempt)
    val interval = heartbeatProperties.refreshIntervalSeconds
    return executor.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, interval, interval, TimeUnit.SECONDS)
}

private fun refreshHeartbeat(attempt: JobAttempt) {
    val expireAt = Instant.now().epochSecond + heartbeatProperties.timeoutSeconds
    redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
}
```

회수 배치는 "지금 시각보다 score 가 작은" 멤버를 뽑으면 그게 곧 만료된 attempt 다. 살아있는지 개별 조회할 때는 score 가 아직 미래인지만 보면 된다.

```kotlin
fun findExpiredAttempts(): Set<JobAttempt> {
    val now = Instant.now().epochSecond.toDouble()
    val expired = redisTemplate.opsForZSet().rangeByScore(KEY, Double.NEGATIVE_INFINITY, now)
    if (expired.isNullOrEmpty()) {
        return emptySet()
    }
    val attempts = mutableSetOf<JobAttempt>()
    for (member in expired) {
        val attempt = JobAttempt.parse(member) ?: continue
        attempts.add(attempt)
    }
    return attempts
}

fun hasLiveHeartbeat(jobId: Long, attemptNo: Int): Boolean {
    val member = JobAttempt(jobId, attemptNo).toMember()
    val score = redisTemplate.opsForZSet().score(KEY, member)
    return score != null && score > Instant.now().epochSecond
}
```

시각은 항상 `Instant.now()` 를 직접 읽는다.

### 2. `JobAttempt` — ZSET 멤버 직렬화

ZSET 은 member 가 문자열이라 `jobId`와 `attemptNo` 를 하나의 키로 합쳐야 한다.

```kotlin
data class JobAttempt(val jobId: Long, val attemptNo: Int) {

    internal fun toMember(): String = "$jobId$SEPARATOR$attemptNo"

    companion object {
        private const val SEPARATOR = ":"

        internal fun parse(member: String?): JobAttempt? {
            val parts = member?.split(SEPARATOR) ?: return null
            if (parts.size != 2) return null
            val jobId = parts[0].toLongOrNull() ?: return null
            val attemptNo = parts[1].toIntOrNull() ?: return null
            return JobAttempt(jobId, attemptNo)
        }
    }
}
```

attemptNo 를 키에 넣는 이유가 중요하다. job 이 재시도되어 attemptNo 가 올라가면 이전 시도의 heartbeat 멤버(`"9:0"`)는 새 시도(`"9:1"`)와 다른 멤버로 남는다. 옛 시도의 heartbeat 가 우연히 살아있어도 새 시도를 오회수하지 않는다 — `HeartbeatRegistryTest` 의 `stopHeartbeat은 같은 jobId의 다른 attempt heartbeat를 지우지 않는다` 가 이 경계를 짚는다.

### 3. `DeadJobRecoveryTask` — 세 단계 전문

```kotlin
@Scheduled(fixedDelayString = $$"${app.scheduling.dead-job-scan-interval-millis:5000}")
fun scan() {
    markExpiredJobsAsFailed()
    markStalledJobsAsFailed()
    retryOrRefundFailedJobs()
}

private fun markExpiredJobsAsFailed() {
    for (attempt in heartbeatRegistry.findExpiredAttempts()) {
        val updated = jobRepository.failIfProcessing(attempt.jobId, attempt.attemptNo, Instant.now())
        if (updated == 1) {
            log.info("heartbeat 만료로 FAILED 전이: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo)
        }
        heartbeatRegistry.removeHeartbeat(attempt.jobId, attempt.attemptNo)
    }
}

private fun markStalledJobsAsFailed() {
    val cutoff = Instant.now().minusSeconds(appProperties.processing.timeoutSeconds)
    val stalled = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
        JobStatus.PROCESSING, cutoff, PageRequest.of(0, SCAN_BATCH_SIZE)
    )
    for (job in stalled) {
        val jobId = job.persistedId
        if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) {
            continue
        }
        val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
        if (updated == 1) {
            heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
            log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
        }
    }
}

private fun retryOrRefundFailedJobs() {
    val failed: List<Job> = jobRepository.findByStatusOrderByIdAsc(
        JobStatus.FAILED, PageRequest.of(0, SCAN_BATCH_SIZE)
    )
    for (job in failed) {
        if (job.attemptNo + 1 < appProperties.generation.maxAttempts) {
            jobLifecycleService.retry(job)
        } else {
            jobLifecycleService.finalRefund(job)
        }
    }
}
```

`markExpiredJobsAsFailed` 는 Redis 가 이미 "이 attempt 만료됐다"고 알려준 것을 그대로 믿고 DB 를 다시 조회하지 않는다. `markStalledJobsAsFailed` 는 반대로 DB 에서 PROCESSING 목록을 긁은 다음 각 job 에 대해 Redis 에 살아있는 heartbeat 가 있는지 되묻는다 — 방향이 서로 반대다.

### 4. `JobRepository` — 정체 조회와 재시도/환불 CAS

```kotlin
/** FAILED 인 시도만 REFUNDED 로 내린다. */
fun refundIfFailed(jobId: Long, attemptNo: Int, now: Instant): Int =
    transitionIfStatusAndAttemptMatch(jobId, JobStatus.REFUNDED, JobStatus.FAILED, attemptNo, now)

@Transactional
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(
    """
    UPDATE Job j
    SET j.attemptNo = j.attemptNo + 1,
        j.status = JobStatus.HOLDING,
        j.updatedAt = :now
    WHERE j.id = :jobId
      AND j.status = JobStatus.FAILED
      AND j.attemptNo = :expectedAttemptNo
    """
)
fun incrementAttemptForRetry(
    @Param("jobId") jobId: Long,
    @Param("expectedAttemptNo") expectedAttemptNo: Int,
    @Param("now") now: Instant
): Int

fun findByStatusAndUpdatedAtBeforeOrderByIdAsc(status: JobStatus, cutoff: Instant, pageable: Pageable): List<Job>
```

`refundIfFailed` 는 step4 부터 있던 `transitionIfStatusAndAttemptMatch` 를 그대로 재사용한다. 이 조건부 UPDATE 하나가 HOLDING→PROCESSING, PROCESSING→FAILED, FAILED→REFUNDED 를 전부 커버한다 — step4 가 만들어 둔 attemptNo CAS 인프라가 여기서 새 상태(REFUNDED)를 받아들이는 데 코드를 더 늘릴 필요가 없었다는 뜻이다. `incrementAttemptForRetry` 는 새로 필요해진 것이다: 재시도는 상태만 바뀌는 게 아니라 `attemptNo` 자체가 증가해야 하므로 별도 UPDATE 문이 필요하다. WHERE 절의 `attemptNo = :expectedAttemptNo` 는 이미 다른 워커나 회수 배치가 같은 FAILED job 을 먼저 재시도시켰다면 이 UPDATE 는 0건에 그치게 만든다.

### 5. `JobLifecycleService` — 재시도와 최종 환불

```kotlin
@Transactional
fun retry(job: Job) {
    val jobId = job.persistedId
    val updated = jobRepository.incrementAttemptForRetry(jobId, job.attemptNo, Instant.now())
    if (updated == 0) {
        log.info("재시도 투입 경쟁에서 밀림 또는 이미 처리됨: jobId={}, attemptNo={}", jobId, job.attemptNo)
        return
    }
    log.info("재시도 투입: jobId={}, newAttemptNo={}", jobId, job.attemptNo + 1)
}

@Transactional
fun finalRefund(job: Job) {
    val jobId = job.persistedId
    val updated = jobRepository.refundIfFailed(jobId, job.attemptNo, Instant.now())
    if (updated == 0) {
        log.info("이미 늦은 워커가 처리함, 환불 취소: jobId={}, attemptNo={}", jobId, job.attemptNo)
        return
    }

    val orgUpdated = organizationRepository.addBalance(job.organizationId, job.holdAmount, Instant.now())
    check(orgUpdated == 1) {
        "환불 잔액 반영 실패: organization이 존재하지 않음, jobId=$jobId, organizationId=${job.organizationId}"
    }

    ledgerRepository.save(LedgerEntry.refund(job.organizationId, jobId, job.holdAmount))
    log.info(
        "최종 환불 완료: jobId={}, organizationId={}, amount={}",
        jobId, job.organizationId, job.holdAmount
    )
}
```

`finalRefund` 는 순서가 중요하다. 먼저 job 을 REFUNDED 로 CAS 전이시켜(성공해야만 이 요청이 환불을 "차지"한 것) 그다음에 잔액을 되돌리고 원장을 남긴다. `refundIfFailed` 가 0을 반환하면 — 이미 다른 스레드가 같은 job 을 재시도로 돌렸거나 먼저 환불했다는 뜻이므로 — 잔액도, 원장도 건드리지 않고 그대로 반환한다. `ServiceTransactionRollbackTest` 가 확인하듯, 조직이 삭제되어 `addBalance` 가 1건을 반영하지 못하면 `check` 가 던지는 예외로 트랜잭션 전체(REFUNDED 전이 포함)가 롤백된다 — 잔액 없이 REFUNDED 만 남는 상태를 허용하지 않는다.

### 6. `GenerationJobProcessor` — heartbeat 연동

```kotlin
fun runGeneration(job: Job) {
    val jobId = job.persistedId
    val attemptNo = job.attemptNo
    val heartbeatFuture = heartbeatRegistry.startHeartbeat(jobId, attemptNo)
    try {
        val resultUrl = generateOrMarkFailed(job) ?: return
        jobLifecycleService.confirm(job, resultUrl)
    } finally {
        heartbeatRegistry.stopHeartbeat(jobId, attemptNo, heartbeatFuture)
    }
}
```

`try/finally` 로 감싸서, confirm 이 성공하든 markFailed 로 끝나든 심지어 stubClient 호출 도중 처리되지 않은 예외가 터지든 heartbeat 는 반드시 정지된다. 정지가 안 되면 이미 끝난 job 의 heartbeat 가 계속 갱신되어 회수 배치를 영원히 속이게 된다.

### 두 겹 회수가 서로의 무엇을 메우는가

| | heartbeat 만료 (`markExpiredJobsAsFailed`) | `updatedAt` 정체 (`markStalledJobsAsFailed`) |
|---|---|---|
| 판단 근거 | Redis ZSET score | DB `updatedAt` 컬럼 |
| 반응 속도 | 빠르다 (`timeout-seconds` 단위, 기본 10초) | 느리다 (`processing.timeout-seconds`, 기본 60초) |
| 필요 인프라 | Redis 가 살아 있어야 함 | DB 만 있으면 됨 |
| 놓치는 경우 | Redis 자체가 죽으면 아무것도 못 본다 | heartbeat 가 계속 갱신되는 정상 워커까지 다시 훑는 비용이 있어 주기를 짧게 못 잡는다 |
| 이 단계의 실패 시나리오 대응 | 워커 프로세스가 죽어 heartbeat 갱신이 끊긴 경우 즉시 감지 | Redis 장애로 heartbeat 자체를 못 찍었던 시간대의 job 도 결국은 걸러낸다 |

heartbeat 는 "빠르지만 Redis 의존"이고 `updatedAt` 정체 감지는 "느리지만 DB 만 있으면 된다." `markStalledJobsAsFailed` 안에서도 `hasLiveHeartbeat` 를 한 번 더 확인하는 이유가 이 관계를 보여준다 — DB 기준으로는 오래됐어도 Redis 기준으로 아직 살아있는 워커라면(정상적으로 느리게 처리 중인 경우) 정체 판정에서 제외한다. 두 신호가 겹칠 때만 정체로 확정 짓는 게 아니라, 정체 후보를 DB 로 넓게 잡고 heartbeat 로 좁히는 구조다.

## 테스트가 보장하는 것

- `HeartbeatRegistryTest` — ZSET score 계산(`now + timeout`), `findExpiredAttempts`/`hasLiveHeartbeat`/`removeHeartbeat` 각각의 Redis 상호작용, 그리고 `stopHeartbeat은 같은 jobId의 다른 attempt heartbeat를 지우지 않는다` 로 attemptNo 분리를 검증한다. heartbeat 스레드 풀 크기가 `WorkerProperties.concurrency` 를 그대로 따라간다는 것도 확인한다.
- `DeadJobRecoveryTaskTest` — 세 단계를 각각 독립적으로 검증한다. `정체된 PROCESSING job은 heartbeat가 없으면 FAILED로 전이한다`, `정체된 PROCESSING job이라도 live heartbeat가 있으면 회수하지 않는다`, `만료 회수는 findById 재조회 없이 heartbeat가 알려준 attemptNo로 전이한다`(DB 재조회 없이 Redis 가 준 attemptNo 를 그대로 믿는다는 걸 `verify(jobRepository, never()).findById(any())` 로 못 박는다), `실패한 작업이 최대 attempt 미만이면 재시도한다` / `...도달하면 환불한다`, `FAILED 스냅샷을 그대로 넘기고 최신 상태 판정은 조건부 UPDATE에 맡긴다`(배치가 들고 있는 오래된 스냅샷을 그대로 서비스에 넘기고, 최신성 판단은 CAS 의 WHERE 절에 위임한다는 설계를 확인), `FAILED job은 배치 크기만큼만 조회한다` / `PROCESSING 정체 조회도 배치 크기만큼만 가져온다`(한 번에 100건, `SCAN_BATCH_SIZE`).
- `RetryRefundTest` — `app.stub.failure-rate=1.0` 으로 항상 생성이 실패하도록 만든 실제 Spring 컨텍스트에서, `매번 실패하면 재시도를 모두 소진하고 최종적으로 환불된다` 를 end-to-end 로 확인한다. 최종적으로 `job.status == REFUNDED`, `job.attemptNo == 2`(maxAttempts=3 이므로 0→1→2 세 번 시도), 조직 잔액이 원래대로 복구, 원장에 `HOLD` 와 `REFUND` 가 모두 남는지를 `await().atMost(30, TimeUnit.SECONDS)` 로 폴링하며 검증한다.
- `JobRepositoryTest` 신규분 — `updatedAt이 cutoff 이전인 HOLDING job만 조회된다`(정체 조회 쿼리의 시간 필터), `재시도 투입은 FAILED 상태에서만 attemptNo를 증가시킨다`, `refundIfFailed는 FAILED인 job만 REFUNDED로 내린다`, `환불된 작업은 같은 attemptNo로 완료할 수 없다`(REFUNDED 이후 뒤늦은 confirm 시도가 막히는지).
- `JobLifecycleServiceTest` 신규분 — `FAILED 상태의 job은 attemptNo가 증가하고 다시 대기한다`, `FAILED job은 REFUNDED로 전이되고 잔액이 복구된다`, `같은 작업을 두 번 환불해도 잔액과 원장은 한 번만 반영된다`(멱등성), `환불된 작업의 늦은 confirm은 무시한다`.
- `ServiceTransactionRollbackTest` 신규분 — `환불할 조직이 사라졌으면 REFUNDED 전이도 롤백된다`. 삭제된 조직에 환불하려 하면 `finalRefund` 가 예외를 던지고, job 상태는 FAILED 에 그대로 남으며 원장에도 아무것도 남지 않는다.
- `GenerationJobProcessorTest` 신규분 — 성공/실패 각 경로 모두 `heartbeatRegistry.stopHeartbeat` 가 호출되는지 확인한다.
- `LedgerEntryTest` 신규분 — `LedgerEntry.refund` 가 amount 를 양수로 기록하고, 0 이하 amount 는 예외를 던진다는 것.
- `SharedContainers` — Redis 컨테이너(`redis:7-alpine`)가 MySQL 옆에 추가되어, 이 단계부터 컨테이너 기반 테스트는 Redis 도 함께 띄워야 한다. `withReuse(true)` 로 클래스마다 재기동하지 않고, `init` 블록에서 `FLUSHALL` 로 테스트 간 heartbeat 잔여물을 지운다.

## 아직 못 막는 것 — 회수 장치 자체가 깨질 때

1. **Redis 순단 한 번이 대량 오탐으로 번진다.** 이 단계 `HeartbeatRegistry` 에는 `try/catch` 가 한 군데도 없다. `refreshHeartbeat`, `findExpiredAttempts`, `hasLiveHeartbeat` 모두 `redisTemplate.opsForZSet()` 호출을 그대로 두고, 예외가 나면 그대로 위로 전파된다. 이게 만드는 실제 고장 경로는 둘이다.

   - **읽기 쪽**: `findExpiredAttempts()` 나 `hasLiveHeartbeat()` 가 예외를 던지면 `DeadJobRecoveryTask.scan()` 이 그 주기에서 통째로 중단된다. Redis 순단 동안에는 회수가 아예 일어나지 않는다 — 진짜로 죽은 워커의 job 도 그대로 방치된다.
   - **쓰기 쪽이 진짜 문제다**: `refreshHeartbeat` 는 `startHeartbeat` 이 건 `executor.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, interval, interval, TimeUnit.SECONDS)` 위에서 돈다. `ScheduledExecutorService` 의 계약상 고정 주기 작업이 예외를 던지면 그 작업은 그대로 취소되고 다시는 실행되지 않는다. 즉 Redis 가 잠깐 끊긴 그 순간 해당 job 의 heartbeat 갱신 스케줄이 영구히 죽는다 — Redis 가 곧바로 복구되어도 소용없다. 워커는 멀쩡히 처리를 계속하는데 ZSET 의 score 만 시간이 지나 만료되고, 다음 스캔에서 `findExpiredAttempts()` 가 이를 집어 FAILED 로 내린다. Redis 순단 한 번에 그 시점 처리 중이던 job 전부의 heartbeat 스케줄이 이렇게 죽는다 — 이것이 대량 오탐의 실제 경로다.

2. **배치 루프 한복판에서 예외가 나면 그 주기의 나머지 job 이 통째로 처리되지 않는다.** 위 3번의 `retryOrRefundFailedJobs` 를 보면 `for (job in failed) { ... }` 루프 안에 개별 job 단위의 try/catch 가 없다. 목록의 세 번째 job 에서 `finalRefund` 가(가령 조직이 삭제되어) 예외를 던지면, 그 뒤에 남은 job 들은 이번 `scan()` 주기에서 아예 시도조차 되지 않고 넘어간다. `@Scheduled` 는 다음 주기에 처음부터 다시 돌 뿐이라 언젠가는 처리되겠지만, 한 개의 나쁜 job 이 매 주기 앞쪽에서 반복해서 예외를 던지면 뒤쪽 job 들이 계속 뒤로 밀릴 수 있다.

3. **잔액과 원장이 어긋나도 아무도 모른다.** `finalRefund` 가 잔액 반영과 원장 기록을 한 트랜잭션 안에서 하긴 하지만, 이 둘이 실제로 항상 일치하는지 별도로 대사(reconcile)하는 장치는 없다. 버그나 마이그레이션, 수동 개입으로 어긋나도 감지할 방법이 이 단계까지는 없다.

## 다음 단계 예고

step6-resilience 는 회수 장치 자체의 취약점을 다룬다. `HeartbeatRegistry` 의 모든 Redis 호출을 try/catch 로 감싸 갱신 스케줄이 예외로 죽지 않게 막고, 마지막 실패 시각을 기록해 그로부터 timeout 이 지나기 전까지는 만료 판정을 보류하며(조회 자체가 실패하면 "살아 있음"으로 간주) 무엇보다 배치 루프에서 항목 하나의 예외가 나머지를 막지 못하도록 항목 단위로 예외를 격리하고, 잔액과 원장이 실제로 맞는지 주기적으로 대사하는 절차를 추가한다.

## 명령어

```bash
# 전체 테스트 (Docker 필요 — MySQL + Redis 컨테이너가 함께 뜬다)
./gradlew test

# 이 단계에서 추가된 회수 관련 테스트만
./gradlew test --tests "com.example.credit_system_kotlin.job.scheduling.DeadJobRecoveryTaskTest"
./gradlew test --tests "com.example.credit_system_kotlin.heartbeat.HeartbeatRegistryTest"
./gradlew test --tests "com.example.credit_system_kotlin.job.concurrency.RetryRefundTest"

# 로컬 실행 — 이 단계부터 Redis가 필요하다 (MySQL만으로는 부팅되지 않는다)
./gradlew bootRun

# step4와의 전체 diff
git diff step4-state-machine step5-recovery
```
