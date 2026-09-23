# step3-idempotency — 멱등키로 중복 요청 막기

같은 요청을 두 번 보내도 한 번만 처리되도록, 조회가 아니라 DB 유니크 제약을 최종 방어선으로 삼아 멱등키를 도입한다.

- 이전 단계: `step2-atomic-balance`
- 다음 단계: `step4-state-machine`

## 이전 단계의 문제

`step2-atomic-balance` 는 `WHERE balance >= :amount` 조건부 UPDATE로 "여러 요청이 동시에 잔액을 깎을 때 잔액이 음수로 내려가는" 경합을 닫았다. 이 방어는 서로 다른 요청들이 같은 자원을 다툴 때만 작동한다. **같은 요청이 두 번 온 경우**는 애초에 경합이 아니라서 걸러지지 않는다.

시나리오: 클라이언트가 `POST /api/jobs` 로 생성을 요청하면 서버는 잔액을 차감하고 job을 만들어 응답까지 정상 완료한다. 그런데 응답이 클라이언트에 닿기 전에 네트워크가 끊겨 클라이언트의 HTTP 라이브러리가 타임아웃으로 판단하고 **똑같은 요청을 재시도**한다. 서버 입장에서는 organizationId도 prompt도 같은 완전히 새로운 요청이라서 "아까 그 요청"이라고 알아볼 방법이 없다. 결과: 잔액이 두 번 깎이고 job이 두 개 생겨 이미지도 두 번 생성된다. 충전(`OrganizationService`) 쪽은 더 위험하다 — 결제 게이트웨이의 webhook이나 "충전하기" 버튼이 중복 호출되면 실제로 결제한 금액보다 잔액이 더 올라간다. step2는 잔액이 내려가는 경합만 막았지, 잘못 올라가는 중복은 대상이 아니었다.

이 단계가 막아야 하는 것은 "동시에 온 서로 다른 요청 사이의 경합"이 아니라 **"같은 논리적 요청이 두 번 이상 도착하는 것"** 이다.

## 무엇이 새로 생겼나

| 파일 | 역할 |
|---|---|
| `job/domain/IdempotencyKey.kt`, `job/repository/IdempotencyKeyRepository.kt` | 멱등키 엔티티(유니크 제약) + 조회/`jobId` 부착용 벌크 UPDATE |
| `job/service/HoldService.kt` | 조회 → 저장 → hold → jobId 부착 흐름으로 재구성 |
| `ledger/domain/LedgerEntry.kt`, `ledger/repository/LedgerRepository.kt` | CHARGE에 `idemKey` 컬럼(유니크 제약) + `findByOrganizationIdAndIdemKey` |
| `organization/service/OrganizationService.kt` | 충전에도 동일하게 조회 후 저장하는 흐름 적용 |
| `global/validation/IdemKeys.kt` | `idemKey` 공백/길이(100자) 검증을 한 곳으로 모음 |
| `global/exception/DuplicateRequestInProgressException.kt` | 선점한 요청이 아직 jobId를 붙이기 전에 들어온 재시도용 예외 |
| `global/exception/GlobalExceptionHandler.kt` | `DataIntegrityViolationException` → 409 번역 추가 |
| `job/dto/JobCreateRequest.kt`, `organization/dto/ChargeRequest.kt` | 요청 본문에 `idemKey` 추가 |
| `job/dto/HoldResult.kt`, `organization/dto/ChargeResponse.kt` | 응답에 `duplicate` 플래그 추가 |

두 개의 유니크 제약이 이 단계의 핵심이다.

```kotlin
// job/domain/IdempotencyKey.kt
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_idempotency_org_key", columnNames = ["organizationId", "idemKey"])
    ]
)
class IdempotencyKey(

    @Column(nullable = false)
    val organizationId: Long,

    @Column(nullable = false, length = 100)
    val idemKey: String

) {
    ...
    var jobId: Long? = null
        protected set
}
```

```kotlin
// ledger/domain/LedgerEntry.kt
@Table(
    name = "ledger_entries",
    indexes = [Index(name = "idx_ledger_org_id", columnList = "organizationId")],
    uniqueConstraints = [UniqueConstraint(name = "uk_ledger_org_idem", columnNames = ["organizationId", "idemKey"])]
)
```

job 생성은 별도 `idempotency_keys` 테이블로 막고, 충전은 원장 자체(`ledger_entries`)에 `idemKey` 컬럼을 얹어 막는다. job 쪽이 별도 테이블인 이유는 `jobId`를 나중에 채워 넣어야 하기 때문이다 — hold 시점엔 아직 job이 없다. 충전은 그런 후속 연결이 필요 없어서 원장 한 줄로 끝난다.

## 핵심 코드 읽기

### "조회 후 INSERT" 사이의 틈

애플리케이션 레벨에서 "먼저 조회해서 없으면 저장한다"는 코드는 아무리 촘촘히 짜도 그 사이에 다른 스레드가 끼어들 수 있다.

```
A: SELECT ... WHERE organizationId=1 AND idemKey='x'  → 없음
B: SELECT ... WHERE organizationId=1 AND idemKey='x'  → 없음 (A가 아직 커밋 전)
A: INSERT INTO idempotency_keys (...)                 → 성공
B: INSERT INTO idempotency_keys (...)                 → 유니크 제약 위반
```

조회(SELECT)는 이 틈을 막지 못한다. 막는 것은 **유니크 인덱스**다. 진 쪽(B)은 애플리케이션 코드가 뭘 하든 상관없이 DB가 강제로 `DataIntegrityViolationException`을 던진다. 이 단계의 설계는 이 예외를 예외적인 실패가 아니라 "중복 요청"이라는 정상 분기로 번역하는 데 집중한다.

### `HoldService` — 조회, 선점, 부착

```kotlin
@Transactional
fun requestGeneration(organizationId: Long, idemKey: String, prompt: String): HoldResult {
    validateRequest(idemKey, prompt)

    val existing = idempotencyKeyRepository.findByOrganizationIdAndIdemKey(organizationId, idemKey)
    if (existing != null) {
        return resolveDuplicateRequest(existing)
    }

    idempotencyKeyRepository.save(IdempotencyKey(organizationId, idemKey))

    val cost = appProperties.generation.cost
    deductBalance(organizationId, cost)
    val job = jobRepository.save(Job.hold(organizationId, cost, prompt))
    val jobId = job.persistedId

    attachIdemKeyToJob(organizationId, idemKey, jobId)

    ledgerRepository.save(LedgerEntry.hold(organizationId, jobId, cost))
    return HoldResult(jobId, false)
}
```

`idempotencyKeyRepository.save(...)`를 잔액 차감보다 **먼저** 호출하는 순서가 중요하다. 멱등키 행부터 최대한 빨리 확보해서, 유니크 인덱스가 "이 idemKey는 내가 처리 중"이라는 선언 역할을 하게 만든다. job은 아직 없으므로 이 시점의 `jobId`는 `null`이고, job이 실제로 만들어진 뒤에야 벌크 UPDATE로 채워 넣는다.

```kotlin
private fun resolveDuplicateRequest(existing: IdempotencyKey): HoldResult {
    val jobId = existing.jobId ?: throw DuplicateRequestInProgressException()
    return HoldResult(jobId, true)
}

private fun attachIdemKeyToJob(organizationId: Long, idemKey: String, jobId: Long) {
    val attached = idempotencyKeyRepository.attachJobId(organizationId, idemKey, jobId)
    check(attached == 1) { "idempotency key에 jobId 연결 실패: ..." }
}
```

`resolveDuplicateRequest`가 다루는 경우는 두 가지다.

- **`jobId`가 채워져 있는 경우** — 이미 완전히 처리된 요청의 재시도다. 정상적인 중복 응답(`duplicate=true`)으로 같은 `jobId`를 돌려준다.
- **`jobId`가 아직 `null`인 경우** — 먼저 온 요청이 멱등키 행은 확보했지만 job을 만들고 jobId를 붙이는 단계까지는 못 간 상태에서, 같은 idemKey로 또 다른 요청(대개 재시도)이 끼어든 경우다. 알려줄 `jobId`가 없으니 정상 응답을 만들 수 없어 `DuplicateRequestInProgressException`을 던져 409로 돌려보낸다. `DuplicateIdemKeyTest`의 주석이 이 경우를 정확히 짚는다 — *"선점한 쪽이 아직 jobId를 붙이기 전에 들어온 요청. 정상 경로다."*

`attachJobId`는 `IdempotencyKeyRepository`의 벌크 UPDATE다.

```kotlin
// job/repository/IdempotencyKeyRepository.kt
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(
    """
    UPDATE IdempotencyKey k
    SET k.jobId = :jobId
    WHERE k.organizationId = :organizationId AND k.idemKey = :idemKey
    """
)
fun attachJobId(organizationId: Long, idemKey: String, jobId: Long): Int
```

### `GlobalExceptionHandler` — DB 예외를 HTTP 응답으로 번역

```kotlin
@ExceptionHandler(DataIntegrityViolationException::class)
fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
    if (isUniqueConstraintViolation(e)) {
        return conflict("DUPLICATE_IN_PROGRESS", "동일한 요청이 동시에 처리 중입니다. 잠시 후 다시 시도해주세요.")
    }
    log.error("무결성 제약 위반: {}", e.message, e)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse("DATA_INTEGRITY_VIOLATION", "요청을 처리할 수 없습니다."))
}

private fun isUniqueConstraintViolation(e: Throwable): Boolean =
    generateSequence(e) { it.cause }
        .filterIsInstance<ConstraintViolationException>()
        .firstOrNull()
        ?.kind == ConstraintViolationException.ConstraintKind.UNIQUE
```

여기가 "DB 유니크 제약이 최종 방어선"이라는 원칙이 코드로 드러나는 지점이다. `HoldService`도 `OrganizationService`도 유니크 제약 위반을 자기 손으로 잡지 않는다. 컨트롤러 밖으로 예외가 그대로 던져지면 `@RestControllerAdvice`가 가로채, 원인 체인을 타고 내려가 그게 유니크 제약(`ConstraintKind.UNIQUE`)인지 아닌지를 가른다. 유니크 제약이면 409 `DUPLICATE_IN_PROGRESS`, 그 외(예: NOT NULL)는 500 — 후자는 정상적인 동시 요청이 아니라 진짜 버그라서 구분해 둔다.

### `OrganizationService` — 원장 유니크 위반

```kotlin
@Transactional
fun charge(organizationId: Long, idemKey: String, amount: Long): ChargeResponse {
    validateRequest(idemKey, amount)

    val existing = ledgerRepository.findByOrganizationIdAndIdemKey(organizationId, idemKey)
    if (existing != null) {
        val balance = organizationFinder.getOrThrow(organizationId).balance
        return ChargeResponse(balance, true)
    }

    val updated = organizationRepository.addBalance(organizationId, amount, Instant.now())
    if (updated != 1) {
        throw OrganizationNotFoundException(organizationId)
    }

    ledgerRepository.save(LedgerEntry.charge(organizationId, idemKey, amount))
    ...
}
```

`HoldService`와 달리 "선점 행을 먼저 만든다"는 중간 단계가 없다. 조회 후 곧바로 잔액을 올리고 원장에 `idemKey`를 실어 저장한다. 그래서 여기서 유니크 제약에 걸리는 경합은 `jobId` 부착 이전 같은 중간 상태가 아니라, 그냥 `ledgerRepository.save(...)`가 `DataIntegrityViolationException`을 던지는 형태로 나타난다. `@Transactional`이 메서드 전체를 감싸므로 이 시점에 예외가 나면 방금 실행한 `addBalance`까지 통째로 롤백된다 — 잔액만 올라가고 원장 기록은 실패하는 반쪽짜리 상태는 생기지 않는다.

### `IdemKeys` — 검증 한 곳으로 모으기

```kotlin
// global/validation/IdemKeys.kt
const val IDEM_KEY_MAX_LENGTH = 100

fun validateIdemKey(idemKey: String) {
    if (idemKey.isBlank()) {
        throw InvalidRequestException("idemKey는 필수입니다.")
    }
    if (idemKey.length > IDEM_KEY_MAX_LENGTH) {
        throw InvalidRequestException("idemKey는 ${IDEM_KEY_MAX_LENGTH}자를 초과할 수 없습니다.")
    }
}
```

`HoldService`와 `OrganizationService` 둘 다 `validateRequest` 맨 앞에서 호출한다. 여기서 걸리면 `IdempotencyKey`나 `LedgerEntry` 저장 자체가 일어나지 않으므로, 유니크 제약과는 별개로 "애초에 형식이 이상한 idemKey"를 걸러내는 앞단 방어선이다.

## 테스트가 보장하는 것

- `IdempotencyKeyRepositoryTest` — 같은 `(organizationId, idemKey)` 저장은 `DataIntegrityViolationException`, 조직이 다르면 통과, `attachJobId`가 실제로 `jobId`를 채우는 것을 `@DataJpaTest`로 확인한다.
- `HoldServiceTest` — 정상 요청, 동일 idemKey 재요청 시 같은 jobId·잔액 불변, idemKey 검증 실패 시 관련 리포지토리 카운트가 모두 0으로 유지되는 것을 확인한다.
- `OrganizationServiceTest` — 같은 idemKey로 두 번 충전해도 잔액과 CHARGE 원장이 한 번만 생기고, 다른 idemKey면 각각 반영되는 것을 확인한다.
- `GlobalExceptionHandlerTest` — 유니크 제약 위반(`ConstraintKind.UNIQUE`)은 409, 무관한 위반(NOT NULL)은 500으로 갈리는 것을 `ConstraintViolationException`을 직접 만들어 확인한다.
- `OrganizationApiControllerTest` — HTTP 레벨에서 같은 idemKey로 두 번 충전하면 두 번째 응답이 `duplicate=true`이고 잔액은 그대로인 것, `idemKey` 필드가 없는 JSON은 400인 것을 확인한다.
- `DuplicateIdemKeyTest` — 실제 MySQL(Testcontainers)에 대고 같은 idemKey로 10개 스레드를 동시에 출발시키는 진짜 동시성 시나리오다.

```kotlin
runConcurrently(10) {
    try {
        holdService.requestGeneration(organization.persistedId, idemKey, "cat")
    } catch (e: DuplicateRequestInProgressException) {
        // 선점한 쪽이 아직 jobId를 붙이기 전에 들어온 요청. 정상 경로다.
    }
}

assertThat(jobRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).hasSize(1)
assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).hasSize(1)
assertThat(found.balance).isEqualTo(10_000L - 100L)
```

10번 요청 중 몇 개가 예외로 끝나든 상관하지 않는다. 예외 종류가 아니라 **결과 상태**를 단언한다 — job, ledger 항목, 잔액 차감이 정확히 한 번만 일어났는지. 같은 방식의 `ConcurrentChargeTest`는 반대로 `DataIntegrityViolationException`을 잡아 무시하는데, 충전 쪽은 선점 단계가 없어 경합이 곧바로 DB 예외로 나타나기 때문이다. 두 테스트가 서로 다른 예외를 잡는다는 사실 자체가, 같은 문제라도 구현 순서에 따라 관측되는 실패 모양이 다를 수 있음을 보여준다.

## 아직 못 막는 것

멱등키는 "같은 요청이 두 번 온다"만 막는다. "여러 워커가 같은 job을 동시에 집어가는 것"은 범위 밖이다. 지금 워커 코드를 보면 왜 아직은 괜찮은지, 뭘 전제로 하는지가 보인다.

```kotlin
// job/worker/GenerationWorker.kt (batchSize는 workerProperties.batchSize)
@Scheduled(fixedDelayString = "\${app.scheduling.worker-interval-millis:500}")
fun dispatchPendingJobs() {
    val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
    for (job in jobs) {
        jobLifecycleService.startProcessing(job.persistedId)
        jobProcessor.runGeneration(job)
    }
}
```

`@Scheduled(fixedDelayString = ...)`는 이전 실행이 끝난 뒤에야 다음 실행이 시작됨을 보장하므로 `dispatchPendingJobs()`는 자기 자신과는 절대 동시에 돌지 않는다. `findByStatusOrderByIdAsc`로 가져온 `HOLDING` job을 `for` 루프로 한 건씩 순차 처리하는 것도, 이 메서드를 실행하는 스케줄러 스레드가 오직 하나뿐이라는 전제 위에서만 안전하다. 이 전제는 인스턴스를 두 대 이상 띄우는(수평 확장) 순간 깨진다. `findByStatusOrderByIdAsc`는 그냥 읽기 쿼리이고 조회한 job을 "내가 가져간다"고 표시할 잠금·클레임 절차가 없어서, 두 인스턴스가 거의 동시에 같은 `HOLDING` 목록을 읽으면 같은 job을 각자 자기 것인 줄 알고 두 번 처리한다. 스텁 생성 API가 두 번 호출되고 `confirm`도 두 번 시도된다 — 멱등키는 이 흐름에 전혀 관여하지 않는다.

## 다음 단계 예고

step4에서는 job이 상태 머신을 명시적으로 갖고, 워커가 job을 집을 때 `attemptNo` 같은 버전 컬럼을 조건으로 건 CAS(compare-and-swap) UPDATE로 `HOLDING → PROCESSING` 전이를 시도한다. 이 UPDATE가 영향받은 행 수 1을 반환할 때만 그 워커가 job의 소유권을 가져간 것으로 간주한다. 여러 워커가 동시에 같은 job을 읽어도 상태 전이를 먼저 성공시키는 쪽만 하나이므로 나머지는 자연히 걸러진다 — 이번 단계의 "조회가 아니라 원자적 쓰기가 방어선"이라는 원칙을 job의 생명주기 전이에도 그대로 적용하는 것이다.

## 명령어

```bash
# 전체 테스트 (Docker 필요 - Testcontainers로 MySQL을 띄운다)
./gradlew test

# 이 단계에서 새로 생긴 동시성/멱등키 테스트만
./gradlew test --tests "*DuplicateIdemKeyTest" --tests "*IdempotencyKeyRepositoryTest"

# 같은 idemKey로 두 번 요청 -> 두 번째 응답은 duplicate=true, 잔액은 한 번만 차감
BODY='{"idemKey":"demo-key-1","prompt":"a cat wearing sunglasses"}'
for i in 1 2; do curl -X POST localhost:8080/api/jobs -H "X-Organization-Id: 1" -H "Content-Type: application/json" -d "$BODY"; done

git diff step2-atomic-balance step3-idempotency  # step2 대비 전체 diff
```
