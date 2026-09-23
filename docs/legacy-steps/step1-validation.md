# step1-validation — 입력 검증과 예외 계층

`step0-naive`의 무방비 상태에 입력 검증과 예외 계층을 추가한다. 잘못된 요청과 존재하지 않는 자원을 걸러내지만, 동시성 문제는 아직 손대지 않는다.

- 이전 단계: `step0-naive`
- 다음 단계: `step2-atomic-balance`

## 이전 단계의 문제

step0에는 방어가 하나도 없었다. `HoldService`와 `OrganizationService`는 조직을 못 찾으면 `error(...)`로 `IllegalStateException`을 던졌고, 이는 그대로 500으로 튀어나갔다. prompt가 빈 문자열이든, 충전 금액이 음수든 그대로 통과해 `Job`이 만들어지고 잔액이 변경됐다. 잔액이 부족해도 검사 자체가 없어 `deduct(cost)`가 실행되면 잔액이 음수로 떨어졌다. 클라이언트 입장에서는 모든 실패가 구분 없는 500이라 어떤 요청이 잘못됐는지, 재시도해도 되는지 알 수 없었다.

## 무엇이 새로 생겼나

신규 파일:

| 경로 | 역할 |
|---|---|
| `global/exception/BusinessException.kt` | 도메인 예외 공통 부모. `message`를 non-null로 좁힌다 |
| `global/exception/InvalidRequestException.kt` | 입력 검증 실패 (400) |
| `global/exception/OrganizationNotFoundException.kt` | 조직 없음 (404) |
| `global/exception/InsufficientBalanceException.kt` | 잔액 부족 (409) |
| `global/exception/ErrorResponse.kt` | 에러 응답 바디 (`code`, `message`) |
| `global/exception/GlobalExceptionHandler.kt` | `@RestControllerAdvice`로 예외를 HTTP 상태에 매핑 |

수정 파일:

| 경로 | 변경 |
|---|---|
| `job/service/HoldService.kt` | prompt 검증 + 잔액 부족 검사 + `OrganizationFinder.getOrThrow` 사용 |
| `organization/service/OrganizationService.kt` | amount 검증 + `getBalance`·`charge` 모두 `OrganizationFinder.getOrThrow` 사용 |
| `organization/service/OrganizationFinder.kt` | 조직 조회 실패를 `OrganizationNotFoundException`으로 번역하는 컴포넌트 |

예외 계층은 `BusinessException` 아래 3종으로 단순하다.

```
RuntimeException
  └─ BusinessException (abstract, message: String)
       ├─ InvalidRequestException      → 400 Bad Request
       ├─ OrganizationNotFoundException → 404 Not Found
       └─ InsufficientBalanceException  → 409 Conflict
```

여기에 스프링이 던지는 `HttpMessageNotReadableException`(JSON 파싱 실패, 필수 필드 누락)도 400으로 묶는다.

## 핵심 코드 읽기

**`BusinessException`** — `message`를 override해서 non-null로 좁혀둔 게 핵심이다. `RuntimeException.message`는 nullable이라 원래는 핸들러에서 `e.message ?: "..."` 같은 널 처리가 필요한데, 하위 타입이 항상 생성자에서 메시지를 주도록 강제해서 그 코드를 없앴다.

```kotlin
abstract class BusinessException(override val message: String) : RuntimeException(message)
```

**`GlobalExceptionHandler`** — 예외 타입별로 핸들러를 나누고 상태 코드를 매핑한다. `InsufficientBalanceException`과 존재하지 않는 조직 예외는 별도 핸들러지만 로깅 형식은 통일했다.

```kotlin
@ExceptionHandler(InsufficientBalanceException::class)
fun handleInsufficientBalance(e: InsufficientBalanceException): ResponseEntity<ErrorResponse> =
    conflict("INSUFFICIENT_BALANCE", e.message)

@ExceptionHandler(InvalidRequestException::class)
fun handleInvalidRequest(e: InvalidRequestException): ResponseEntity<ErrorResponse> =
    ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", e.message))

@ExceptionHandler(HttpMessageNotReadableException::class)
fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
    log.info("요청 본문 해석 실패: {}", e.message)
    return ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "요청 본문의 형식이 올바르지 않습니다."))
}

@ExceptionHandler(OrganizationNotFoundException::class)
fun handleOrganizationNotFound(e: OrganizationNotFoundException): ResponseEntity<ErrorResponse> {
    log.info("business exception: code=ORGANIZATION_NOT_FOUND, message={}", e.message)
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse("ORGANIZATION_NOT_FOUND", e.message))
}
```

`amount` 필드가 빠진 JSON 본문 같은 케이스는 `InvalidRequestException`이 아니라 스프링이 먼저 `HttpMessageNotReadableException`을 던진다. 그래서 이 핸들러가 따로 필요하다.

**`OrganizationFinder.getOrThrow`** — 조직 조회 실패를 매번 서비스마다 반복하지 않도록 별도 컴포넌트로 뽑았다. `HoldService`와 `OrganizationService`(`getBalance`, `charge` 둘 다) 전부 이걸로 갈아탔다. 조회 실패를 도메인 예외로 번역하는 건 서비스 계층의 정책이라 `OrganizationRepository`가 `global.exception`을 알 필요는 없고, 컴포넌트로 주입 가능해야 테스트에서 갈아끼우기도 쉽다.

```kotlin
@Component
class OrganizationFinder(
    private val organizationRepository: OrganizationRepository
) {

    fun getOrThrow(organizationId: Long): Organization =
        organizationRepository.findByIdOrNull(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
}
```

**`HoldService.requestGeneration`** — step0 대비 diff 형태로 보면 무엇이 추가됐는지 명확하다.

```diff
     fun requestGeneration(organizationId: Long, prompt: String): HoldResult {
+        validateRequest(prompt)
+
         val cost = appProperties.generation.cost
-        val organization = organizationRepository.findByIdOrNull(organizationId)
-            ?: error("조직을 찾을 수 없습니다: organizationId=$organizationId")
+        val organization = organizationFinder.getOrThrow(organizationId)
+        if (organization.balance < cost) {
+            throw InsufficientBalanceException(organization.balance, cost)
+        }
         organization.deduct(cost)
```

검증 로직은 별도 private 함수로 분리했다. prompt는 공백만 있으면 거부하고, 1000자를 넘으면 거부한다(1000자는 허용, 1001자부터 거부 — `HoldServiceTest`의 `prompt의 최대 길이는 허용한다` 테스트가 경계값을 확인한다).

```kotlin
private fun validateRequest(prompt: String) {
    if (prompt.isBlank()) {
        throw InvalidRequestException("prompt는 필수입니다.")
    }
    if (prompt.length > 1000) {
        throw InvalidRequestException("prompt는 1000자를 초과할 수 없습니다.")
    }
}
```

**`OrganizationService`의 amount 검증** — 0 이하는 거부, 상한선은 1,000,000이다.

```kotlin
private fun validateRequest(amount: Long) {
    if (amount <= 0) {
        throw InvalidRequestException("amount는 0보다 커야 합니다.")
    }
    if (amount > MAX_CHARGE_AMOUNT) {
        throw InvalidRequestException("amount는 1,000,000을 초과할 수 없습니다.")
    }
}

companion object {
    private const val MAX_CHARGE_AMOUNT = 1_000_000L
}
```

검증이 예외를 던지는 시점이 `organizationFinder.getOrThrow(organizationId)` 호출보다 앞이라는 점도 눈여겨볼 만하다. 존재하지 않는 조직에 대해 이상한 amount로 요청해도 404가 아니라 400이 먼저 난다 — 입력 자체의 형식 오류를 자원 존재 여부보다 먼저 확인하는 순서다.

## 테스트가 보장하는 것

- `GlobalExceptionHandlerTest` — 세 가지 예외(`InsufficientBalanceException`, `InvalidRequestException`, `OrganizationNotFoundException`)가 각각 409/400/404와 올바른 `code`로 매핑되는지 핸들러를 직접 호출해 확인한다.
- `HoldServiceTest` — 정상 차감(1000 → 900), 잔액 부족 시 예외와 job 미생성, prompt 공백/1001자 초과 시 거부, prompt 정확히 1000자는 허용, 존재하지 않는 조직 요청 시 `OrganizationNotFoundException`을 검증한다.
- `ServiceTransactionRollbackTest` — 잔액 부족으로 hold가 실패하면 `@Transactional` 경계 안에서 job 저장과 잔액 변경이 모두 롤백되는지, 전체 스프링 컨텍스트를 띄운 통합 테스트로 확인한다.
- `OrganizationServiceTest` — 정상 충전, amount ≤ 0 거부, amount > 1,000,000 거부, 존재하지 않는 조직 충전 시 예외를 검증하고 각 실패 케이스에서 잔액이 변하지 않았음을 함께 확인한다.
- `JobApiControllerTest` — `X-Organization-Id` 헤더 없이 호출하면 400, 정상 흐름에서 생성·목록조회가 동작하는지 실제 HTTP 계층까지 통해 확인한다(기존 테스트에 케이스 추가).
- `OrganizationApiControllerTest` — 잔액 조회·충전 정상 흐름, 존재하지 않는 조직 조회 시 404, `amount` 필드가 없는 본문에 400과 `INVALID_REQUEST` 코드가 오고 잔액이 그대로인지 HTTP 계층에서 검증한다.

## 아직 못 막는 것

`HoldService.requestGeneration`을 다시 보자.

```kotlin
val organization = organizationFinder.getOrThrow(organizationId)
if (organization.balance < cost) {
    throw InsufficientBalanceException(organization.balance, cost)
}
organization.deduct(cost)
```

이건 **읽고(read) 검사하고 쓰는(write)** 세 걸음이다. 잔액 100, cost 60인 조직에 동시에 두 요청이 들어오는 상황을 타임라인으로 보면:

```
시각   요청 A                          요청 B
t0     balance 조회 → 100
t1                                     balance 조회 → 100
t2     100 >= 60 → 통과
t3                                     100 >= 60 → 통과
t4     deduct(60) → balance = 40
t5     커밋 (balance = 40)
t6                                     deduct(60) → balance = -20
t7                                     커밋 (balance = -20)
```

두 요청 다 자신이 읽은 시점의 잔액(100)을 기준으로 검사를 통과했다. 검사 자체는 옳았지만, 검사와 반영 사이에 다른 트랜잭션이 끼어들 수 있다는 걸 막지 못했다. 결과는 잔액이 음수가 되는, 이 시스템이 가장 피하려는 사고 그 자체다. `@Transactional`은 각 요청의 원자성은 보장하지만 두 트랜잭션 사이의 격리 수준이 이 경합을 막을 만큼 강하지 않다(기본 격리 수준에서는 각자 자신의 스냅샷을 읽고, DB 락도 명시적으로 걸지 않았다). 애플리케이션 코드에 아무리 `if` 문을 촘촘히 넣어도, 그 사이에 다른 스레드가 끼어들 여지가 있는 한 소용없다.

이 단계에 경합을 재현하는 테스트가 없는 이유도 여기에 있다. 두 스레드가 정확히 이 순서로 인터리빙되는 걸 강제로 재현하려면 스레드 타이밍을 조작해야 하고, 그렇지 않으면 실행할 때마다 통과하거나 실패하는 flaky 테스트가 된다. 버그가 실재해도 테스트로는 안정적으로 잡히지 않는 상태다 — 이 자체가 read-then-write 경합의 성가신 특징이다.

## 다음 단계 예고

`step2-atomic-balance`는 이 문제를 애플리케이션 레벨의 `if` 검사가 아니라 DB 레벨의 조건부 UPDATE로 닫는다. `balance`를 읽고 검사하고 다시 쓰는 대신, `UPDATE organizations SET balance = balance - :amount WHERE id = :id AND balance >= :amount` 형태의 단일 UPDATE 문을 실행하고 영향받은 행 수(0 또는 1)로 성공 여부를 판단한다. 읽기와 쓰기 사이의 틈 자체를 없애 두 트랜잭션이 같은 잔액을 놓고 경쟁할 수 없게 만드는 방식이다. 검사와 반영이 원자적인 하나의 문장이 되므로, 위 타임라인에서 두 번째 UPDATE는 이미 줄어든 잔액을 보고 조건을 만족하지 못해 실패한다.

## 명령어

```bash
./gradlew test
git diff step0-naive step1-validation
git diff step1-validation step2-atomic-balance
```
