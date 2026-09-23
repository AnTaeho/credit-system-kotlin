# step2-atomic-balance — 원자적 잔액 갱신과 원장

읽고 검사하고 쓰는 애플리케이션 레벨 잔액 로직을 조건부 UPDATE 한 문장으로 접고, 모든 잔액 변동을 별도 원장(ledger)에 남긴다.

- 이전 단계: `step1-validation`
- 다음 단계: `step3-idempotency`

## 이전 단계의 문제

step1의 `HoldService`는 잔액을 지키는 로직을 애플리케이션 코드로 짰다: `Organization`을 읽고, 잔액을 비교하고, 엔티티 필드를 바꿔 dirty checking에 맡긴다. 문제는 이 세 걸음 사이에 틈이 있다는 점이다.

1. 요청 A가 `organizationFinder.getOrThrow(id)`로 잔액 500을 읽는다.
2. 커밋 전인 그 순간, 요청 B도 같은 organization을 읽는다. B의 영속성 컨텍스트에는 여전히 잔액 500이 들어온다.
3. A가 `balance < cost` 검사를 통과하고 `organization.deduct(cost)`로 메모리 상의 필드를 깎는다.
4. B도 자기 객체 기준으로 같은 검사를 통과하고 똑같이 깎는다 — A의 변경은 아직 B의 시야에 없다.
5. 두 트랜잭션이 각각 커밋되면 dirty checking이 각자 UPDATE를 날린다. 결과는 순서에 따라 lost update(한쪽 차감이 사라짐)이거나, 검사 자체가 무의미해져 잔액이 음수로 내려간다.

애플리케이션이 "읽고 비교하고 쓰기"로 잔액을 지키는 한, 두 요청이 같은 순간에 같은 값을 읽으면 둘 다 통과한다. 이건 버그가 아니라 이 구조가 원래 못 막는 경우다.

## 무엇이 새로 생겼나

- `organization/repository/OrganizationRepository.kt` — `deductBalance`/`addBalance` 두 개의 `@Modifying` 조건부 UPDATE 벌크 쿼리 추가. 잔액 검사와 차감/충전이 DB 한 문장으로 합쳐진다.
- `organization/domain/Organization.kt` — `charge(amount)`, `deduct(amount)` 메서드 11줄 삭제. dirty checking으로 잔액을 바꾸는 경로 자체가 사라졌다. 이제 `balance`는 벌크 UPDATE로만 바뀐다.
- `job/service/HoldService.kt` — read-then-write 세 줄이 `deductBalance` 호출 + 0건 시 예외로 교체. `OrganizationRepository`와 `LedgerRepository` 의존성 추가(조회는 여전히 `OrganizationFinder`), hold 성공 시 `LedgerEntry.hold` 기록.
- `organization/service/OrganizationService.kt` — `organization.charge(amount)`가 `addBalance` 벌크 UPDATE로 교체. `OrganizationRepository`와 `LedgerRepository` 의존성 추가(조회는 여전히 `OrganizationFinder`). 충전 성공 시 `LedgerEntry.charge` 기록.
- `job/service/JobLifecycleService.kt` — `confirm()`에 `LedgerEntry.confirm` 기록 추가. (환불 로직은 아직 없다 — `markFailed()`는 상태만 바꾼다.)
- `ledger/domain/LedgerEntry.kt`, `LedgerType.kt` (신규) — HOLD/CONFIRM/CHARGE 세 종류의 원장 엔티티와 그 타입 enum.
- `ledger/repository/LedgerRepository.kt`, `ledger/service/LedgerQueryService.kt` (신규) — organization별 최신순 조회 리포지토리와 조회 서비스.
- `ledger/controller/LedgerApiController.kt`, `ledger/dto/LedgerResponse.kt` (신규) — `GET /api/ledger`와 응답 DTO.
- `job/concurrency/ConcurrentHoldTest.kt`, `ConcurrentChargeTest.kt`, `Concurrently.kt` (신규) — Testcontainers 기반 동시성 테스트와 그 실행 헬퍼. `SharedContainers.kt`는 step1에서 이미 있던 걸 재사용한다.

## 핵심 코드 읽기

**`Organization.kt` — before/after.** step1에는 잔액을 바꾸는 메서드가 엔티티 안에 있었다.

```kotlin
// step1
fun charge(amount: Long) {
    balance += amount
    updatedAt = Instant.now()
}

fun deduct(amount: Long) {
    balance -= amount
    updatedAt = Instant.now()
}
```

step2에서는 이 두 메서드가 통째로 사라졌다. `balance`는 `protected set`이라 엔티티 밖에서 직접 바꿀 수도 없다. 잔액을 바꾸는 유일한 경로는 리포지토리의 벌크 UPDATE뿐이다.

**`OrganizationRepository.kt` — 조건부 UPDATE 전문.**

```kotlin
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(
    """
    UPDATE Organization o
    SET o.balance = o.balance - :amount, o.updatedAt = :now
    WHERE o.id = :id AND o.balance >= :amount
    """
)
fun deductBalance(
    @Param("id") id: Long,
    @Param("amount") amount: Long,
    @Param("now") now: Instant
): Int
```

`WHERE o.id = :id AND o.balance >= :amount`가 핵심이다. "잔액이 충분한지 확인"과 "차감"이 DB 한 문장, 한 번의 row lock 안에서 일어난다. 두 트랜잭션이 동시에 같은 row를 대상으로 이 UPDATE를 실행하면 DB가 순서를 강제한다 — 하나가 끝나야 다른 하나가 최신 잔액을 보고 조건을 재평가한다. 조건을 만족하지 못하면 갱신 건수가 0이 되고, 애초에 balance가 바뀌지 않는다. read-then-write의 "읽은 뒤 쓰기 전까지의 틈"이 사라진 것이다.

`@Modifying(flushAutomatically = true, clearAutomatically = true)`의 두 플래그는 장식이 아니다. 벌크 UPDATE는 JPQL이지만 실행되는 방식은 일반 `save()`와 다르다 — 영속성 컨텍스트를 거치지 않고 SQL을 DB에 직접 쏜다. 이 때문에 두 가지가 어긋날 수 있다.

- `flushAutomatically`가 없으면, 벌크 UPDATE가 실행되는 시점에 같은 트랜잭션 안에서 아직 flush되지 않은 변경이 남아 있을 수 있다. true면 벌크 문장을 쏘기 전에 대기 중인 변경을 먼저 flush해서, DB가 보는 순서와 코드가 실행된 순서를 맞춘다.
- `clearAutomatically`가 없으면, 벌크 UPDATE가 DB row는 바꿔도 영속성 컨텍스트(1차 캐시)에 이미 올라와 있는 `Organization` 객체는 낡은 balance를 그대로 들고 있다. 같은 트랜잭션에서 다시 `findById`로 조회하면 Hibernate는 DB 대신 1차 캐시의 낡은 객체를 돌려준다. `clearAutomatically = true`는 벌크 UPDATE 직후 캐시를 비워서 이후 조회가 반드시 DB를 다시 찌르게 만든다. `deductBalance`/`addBalance` 다음에 `organizationFinder.getOrThrow`로 같은 organization을 다시 읽는 자리(`OrganizationService`, `HoldService`의 실패 경로)가 이미 있으니, 이 보장이 없으면 방금 바뀐 값이 아니라 벌크 UPDATE 이전 값을 돌려줄 위험이 있다.

**`HoldService.kt` — before/after.**

```kotlin
// step1
val organization = organizationFinder.getOrThrow(organizationId)
if (organization.balance < cost) {
    throw InsufficientBalanceException(organization.balance, cost)
}
organization.deduct(cost)

val job = jobRepository.save(Job.hold(organizationId, cost, prompt))
log.info("hold 완료: organizationId={}, jobId={}, cost={}", organizationId, job.persistedId, cost)
return HoldResult(job.persistedId)
```

```kotlin
// step2
private fun deductBalance(organizationId: Long, cost: Long) {
    val updated = organizationRepository.deductBalance(organizationId, cost, Instant.now())
    if (updated == 1) {
        return
    }

    val organization = organizationFinder.getOrThrow(organizationId)
    throw InsufficientBalanceException(organization.balance, cost)
}
```

`deductBalance`가 반환하는 건 갱신된 행 수다. 1이면 성공, 0이면 실패 — "잔액이 부족해서 조건이 안 맞았거나, organization이 아예 없거나" 둘 중 하나다. 어느 쪽인지, 그리고 예외 메시지에 담을 실제 잔액이 얼마인지는 실패했을 때만 별도로 조회해서 알아낸다. 성공 경로에는 읽기 자체가 없다 — 그래서 경합의 여지도 없다.

**`ledger/domain/LedgerEntry.kt`, `LedgerType.kt`.** `LedgerType`은 `HOLD, CONFIRM, CHARGE` 세 값을 갖는 enum이다. `LedgerEntry`는 이 세 값에 대응하는 팩토리만 노출한다.

```kotlin
companion object {

    /** hold 는 잔액을 묶는 차변이라 음수로 기록된다. */
    fun hold(organizationId: Long, jobId: Long, cost: Long): LedgerEntry {
        require(cost > 0) { "hold 원장의 cost는 양수여야 합니다: cost=$cost" }
        return LedgerEntry(organizationId, jobId, LedgerType.HOLD, -cost)
    }

    /** confirm 은 hold 를 확정할 뿐 잔액을 움직이지 않아 금액이 0이다. */
    fun confirm(organizationId: Long, jobId: Long): LedgerEntry =
        LedgerEntry(organizationId, jobId, LedgerType.CONFIRM, 0)

    fun charge(organizationId: Long, amount: Long): LedgerEntry =
        LedgerEntry(organizationId, null, LedgerType.CHARGE, amount)
}
```

생성자가 `private`이라 원장에 남길 수 있는 사건이 이 셋뿐이라는 게 타입으로 강제된다. hold는 잔액에서 빠져나가니 음수, confirm은 이미 hold로 잡아둔 돈을 상태만 바꾸는 것이라 0, charge는 들어오는 돈이라 양수 — 부호 자체가 의미를 전달한다. `balance`는 지금 얼마 있는지만 말해주지만, 원장은 왜 그 값이 됐는지를 순서대로 말해준다.

**`OrganizationService.kt` — 원장 기록.**

```kotlin
@Transactional
fun charge(organizationId: Long, amount: Long): ChargeResponse {
    validateRequest(amount)

    val updated = organizationRepository.addBalance(organizationId, amount, Instant.now())
    if (updated != 1) {
        throw OrganizationNotFoundException(organizationId)
    }

    ledgerRepository.save(LedgerEntry.charge(organizationId, amount))
    log.info("충전 완료: organizationId={}, amount={}", organizationId, amount)

    return ChargeResponse(organizationFinder.getOrThrow(organizationId).balance)
}
```

`addBalance`는 `WHERE o.id = :id`만 걸려 있고 잔액 조건은 없다 — 충전은 위쪽으로만 움직이니 막을 이유가 없다. 갱신 건수가 0이면 organization이 존재하지 않는다는 뜻이라 `OrganizationNotFoundException`을 던진다. 성공하면 `LedgerEntry.charge`를 저장하고, 마지막에 `organizationFinder.getOrThrow`로 잔액을 다시 읽어 응답에 담는다. 이 마지막 조회가 방금 설명한 `clearAutomatically`의 효과를 그대로 받는 자리다.

## 테스트가 보장하는 것

- `LedgerEntryTest` — hold의 amount는 음수, confirm은 0, charge는 jobId가 null, cost가 0 이하인 hold는 `IllegalArgumentException`.
- `HoldServiceTest` — 정상 요청은 job과 ledger를 함께 만들고, 잔액 부족이나 organization 미존재 시 job도 ledger도 남지 않는다.
- `OrganizationServiceTest` — 충전 성공 시 ledger에 CHARGE가 남고, 검증 실패(0 이하, 상한 초과) 시 ledger가 비어 있다.
- `JobLifecycleServiceTest` — confirm 시 CONFIRM 원장이 남는다.
- `ServiceTransactionRollbackTest` — hold가 잔액 부족으로 실패하면 job과 ledger가 같은 트랜잭션 안에서 함께 롤백된다.
- `LedgerApiControllerTest` — `GET /api/ledger`가 최신순으로 내려주고, `X-Organization-Id` 헤더가 없으면 400.
- `OrganizationRepositoryTest` — `deductBalance(org.persistedId, 300L, ...)`을 잔액 100인 organization에 호출하면 `updated`가 `isZero()`이고 `found.balance`는 여전히 `100L`이라고 직접 검증한다(`잔액이 부족하면 0행이 반환되고 잔액이 변하지 않는다`). "갱신 0건 = 잔액 불변"이 애플리케이션 코드가 아니라 DB의 WHERE 절이 지킨 약속임을 이 테스트가 코드로 박아둔다. 잔액이 충분한 경우(1행 갱신)와 `addBalance`로 환불하는 경우도 같은 방식으로 검증한다.

동시성 테스트는 이 단계에서 처음 등장한다. 이전 단계들은 순차 실행되는 `@DataJpaTest`/단위 테스트로 충분했지만, 조건부 UPDATE가 "동시 요청에서도 안전한가"는 실제로 스레드를 여러 개 띄워 같은 row를 두들겨야만 검증할 수 있다. 그래서 이 단계부터 `job/concurrency/` 패키지가 Testcontainers MySQL 컨테이너를 띄우고, 실제 스레드 경합을 결정적으로 재현한다.

`Concurrently.kt`는 그 재현 방법을 하나로 묶은 헬퍼다.

```kotlin
fun runConcurrently(threadCount: Int, action: (Int) -> Unit) {
    val executor = Executors.newFixedThreadPool(threadCount)
    val ready = CountDownLatch(threadCount)
    val start = CountDownLatch(1)
    val done = CountDownLatch(threadCount)

    repeat(threadCount) { idx ->
        executor.submit {
            ready.countDown()
            try {
                start.await()
                action(idx)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                done.countDown()
            }
        }
    }

    ready.await()
    start.countDown()
    done.await(30, TimeUnit.SECONDS)
    executor.shutdown()
}
```

스레드를 그냥 여러 개 띄우기만 하면 먼저 뜬 스레드가 늦게 뜬 스레드보다 먼저 끝나버려 진짜 경합이 안 만들어질 수 있다. `ready` 래치로 모든 스레드가 완전히 준비될 때까지 기다렸다가, `start` 래치 하나로 동시에 출발시킨다 — 그래야 같은 순간에 같은 row를 노리는 상황이 결정적으로 만들어진다.

`ConcurrentHoldTest`는 이 헬퍼로 잔액보다 요청이 많은 상황을 만든다.

```kotlin
@Test
fun `동시에 여러 요청이 들어와도 잔액이 음수가 되지 않는다`() {
    val organization = organizationRepository.save(Organization("acme", 500L))

    val threadCount = 10
    val successCount = AtomicInteger()
    val rejectedCount = AtomicInteger()

    runConcurrently(threadCount) {
        try {
            holdService.requestGeneration(organization.persistedId, "cat")
            successCount.incrementAndGet()
        } catch (e: InsufficientBalanceException) {
            rejectedCount.incrementAndGet()
        }
    }

    assertThat(successCount.get() + rejectedCount.get()).isEqualTo(threadCount)
    assertThat(successCount.get()).isEqualTo(5)
    assertThat(rejectedCount.get()).isEqualTo(5)

    val found = organizationRepository.findById(organization.persistedId).orElseThrow()
    assertThat(found.balance).isEqualTo(0L)
}
```

잔액 500, 생성 비용 100(`application-test.yml`), 스레드 10개 — 정확히 5개만 성공하고 5개는 `InsufficientBalanceException`으로 거부되며, 최종 잔액은 정확히 0이 된다. read-then-write였다면 이 숫자가 매 실행마다 흔들렸을 자리다.

`ConcurrentChargeTest`는 반대 방향, 즉 증가 방향의 경합에서 값이 유실되지 않는지 검증한다.

```kotlin
@Test
fun `동시에 충전해도 증가분이 유실되지 않는다`() {
    val organization = organizationRepository.save(Organization("acme", 10_000L))

    runConcurrently(10) {
        organizationService.charge(organization.persistedId, 300L)
    }

    assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
        .filteredOn { it.type == LedgerType.CHARGE }
        .hasSize(10)

    val found = organizationRepository.findById(organization.persistedId).orElseThrow()
    assertThat(found.balance).isEqualTo(10_000L + 3_000L)
}
```

10개 스레드가 동시에 300씩 충전하면 lost update가 하나라도 있으면 최종 잔액이 13,000보다 작아진다. 여기에 더해 원장 개수(10개)까지 확인해서, 잔액만 맞고 원장 기록이 누락되는 경우까지 잡아낸다.

`GenerationPipelineEndToEndTest`도 이 단계에서 hold→confirm 전체 흐름에 원장이 남는지 확인하는 assertion이 추가됐다.

```kotlin
assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
    .extracting<String> { it.type.name }
    .contains("HOLD", "CONFIRM")
```

## 아직 못 막는 것

조건부 UPDATE가 막는 건 딱 하나, "잔액이 음수가 되는 것"이다. "같은 요청이 두 번 처리되는 것"은 전혀 다른 문제고, 이 단계는 그걸 막지 못한다.

클라이언트가 `POST /api/jobs`를 보냈는데 응답이 오기 전에 타임아웃이 나서 재시도했다고 하자. 서버 입장에서는 첫 번째 요청도 재시도도 그냥 "새로운 hold 요청"이다. 조건이 "잔액 >= 비용"이지 "이 요청을 전에 본 적 있는가"가 아니라서, 잔액만 충분하면 `deductBalance`는 둘 다 통과시킨다. job이 두 개 생기고 잔액이 두 번 깎인다 — 잔액은 한 번도 음수로 내려가지 않았으니 이 단계가 지키기로 한 약속은 지켜졌지만, 사용자는 이미지 한 장을 요청하고 두 장 값을 낸다.

충전도 마찬가지다. 결제 완료 웹훅이 네트워크 문제로 중복 전송되면 `OrganizationService.charge`가 두 번 호출되고 `addBalance`는 두 번 다 성공한다. 요청에는 금액과 organization id 말고 아무 식별자도 없어서, 서버에는 "이미 처리한 이벤트인지" 판단할 근거가 없다.

## 다음 단계 예고

다음 단계에서는 요청 자체에 멱등키(idempotency key)를 도입한다. 클라이언트가 요청마다 고유한 키를 함께 보내고, 서버는 그 키로 "이미 처리한 요청인지"를 판단한다 — 같은 키로 다시 들어오면 새로 처리하는 대신 첫 처리 결과를 그대로 돌려준다. 잔액을 지키는 문제(이번 단계)와 요청을 한 번만 처리하는 문제(다음 단계)는 서로 다른 층위의 문제이고, 조건부 UPDATE는 후자를 대신해주지 않는다.

## 명령어

```bash
# 전체 테스트 (Docker 필요 — Testcontainers가 MySQL 8.4 컨테이너를 띄운다)
./gradlew test

# 동시성 테스트만
./gradlew test --tests "com.example.credit_system_kotlin.job.concurrency.ConcurrentHoldTest"
./gradlew test --tests "com.example.credit_system_kotlin.job.concurrency.ConcurrentChargeTest"

# step1과의 전체 diff
git diff step1-validation step2-atomic-balance
```
