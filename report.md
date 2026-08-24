# credit_system → Kotlin 이식 결정 사항 리포트

작성일: 2026-08-20
대상: `credit_system` (Java) → `credit-system-kotlin` (Kotlin)

현재 상태: **이식 완료.** `src/main` 과 테스트 4개 계층을 모두 옮겼다.
`./gradlew test` 27개 클래스 / 133 테스트 통과.

> 2026-08-20 갱신: 이 시점부터 git 저장소로 관리한다 (`5d39be6` 이 이식 작업본 첫 커밋).

---

## A. 이미 적용한 결정 (되돌릴 수 있음)

### A-1. 예외 계층에 `BusinessException` 도입 — Java에 없던 클래스 ⚠️

```kotlin
abstract class BusinessException(override val message: String) : RuntimeException(message)
```

**왜**: `Throwable.message` 는 `String?` 이라, 핸들러에서 `e.message ?: "기본값"` 을 써야 했다.
그런데 예외 5개 모두 생성자에서 메시지를 확정하므로 그 `?:` 는 **절대 실행되지 않는 죽은 코드**였고,
메시지 문구가 예외 클래스와 핸들러 두 곳에 중복되어 드리프트 위험이 있었다.

**효과**: 핸들러의 `?:` 4곳 제거, 문구 중복 제거.

**남은 판단**: `StubGenerationException` 은 상속시키지 **않았다**.
내부에서 잡혀서 HTTP 응답으로 변환되지 않기 때문. "BusinessException = 핸들러가 소비하는 예외"라는
경계를 유지할지, 아니면 "메시지가 보장된 도메인 예외 전부"로 넓힐지는 취향.

---

### A-2. `Optional<T>` → `T?` (리포지토리 반환 타입)

```kotlin
// 변경 전 (Java 그대로)
fun findByOrganizationIdAndIdemKey(...): Optional<LedgerEntry>
if (existing.isPresent) { ... }

// 변경 후
fun findByOrganizationIdAndIdemKey(...): LedgerEntry?
if (existing != null) { ... }
```

**왜**: `Optional` 은 Java에 null 안전성이 없어서 만든 우회로. Kotlin에는 `?` 가 있어 이중 장치가 된다.
Spring Data가 Kotlin nullable 반환 타입을 정식 지원한다.

**적용 범위**: 직접 선언한 쿼리 메서드만. `JpaRepository.findById` 는 상속분이라 여전히 `Optional`.
→ **결과적으로 코드에 두 스타일이 공존한다.** (`findById(...).orElseThrow { }` vs `?: throw`)

---

### A-3. 예외 생성자 nullable 닫기

```kotlin
class OrganizationNotFoundException(organizationId: Long)   // Long? → Long
class InvalidRequestException(message: String)              // String? → String
```
호출부가 전부 non-null이었다. 무의미한 `"존재하지 않는 organization: null"` 메시지를 원천 차단.

---

### A-4. 요청 DTO의 `idemKey` non-null 화 — **API 응답이 바뀐 유일한 지점** ⚠️⚠️

```kotlin
data class ChargeRequest(val idemKey: String, val amount: Long)       // String? → String
data class JobCreateRequest(val idemKey: String, val prompt: String)
```

**대가**: JSON에 필드가 아예 없으면 Jackson이 서비스 도달 전에 거부한다.
이를 받기 위해 `HttpMessageNotReadableException` 핸들러를 새로 추가했다.

| 요청 | Java 원본 | 현재 |
|---|---|---|
| `{"amount":300}` (필드 누락) | `idemKey는 필수입니다.` | `요청 본문의 형식이 올바르지 않습니다.` |
| `{"idemKey":"  ", ...}` (공백) | 동일 | 동일 ✅ |
| 그 외 검증 | 동일 | 동일 ✅ |

코드(`INVALID_REQUEST`)와 상태(400)는 같고 **메시지 문구만 다르다.**

**되돌리는 방법 2가지**
1. `val idemKey: String = ""` 처럼 기본값 부여 → Jackson이 빈 문자열로 채워 서비스 검증까지 도달, 원본 메시지 복구.
   대신 `""` 라는 가짜 기본값이 DTO에 남는다.
2. `String?` 로 되돌리기 → A-5가 함께 되살아난다.

**Bean Validation(`@field:NotBlank`)은 일부러 쓰지 않았다.** 검증이 DTO로 가면
`ChargeService.charge()` 를 직접 호출할 때 검증이 사라지는데, Java 원본은 서비스 계층에서
검증해 **어느 경로로 들어오든** 보호되고 테스트도 그 전제로 작성돼 있다.

---

### A-5. `validateRequest` 가 값을 반환하던 문제 — A-4로 자동 해소

`idemKey` 가 nullable이던 시절, 검증 통과 후에도 컴파일러가 non-null임을 몰라서
검증 함수가 `String` 을 반환하게 했었다. A-4로 타입을 닫으면서 Java 원본과 같은 `void` 형태로 복귀.

---

## B. 결정 사항 (B-1~B-5 모두 확정)

### B-1. 엔티티 `id: Long?` 이 코드 전반에 번지는 문제 — **(b)로 확정** ✅

JPA 특성상 `persist` 전에는 id가 없어 `Long?` 이 불가피하다. 문제는 이게 밖으로 새어나간다는 점.

**현재 대응**: `!!` 대신 진입점에서 한 번 확정. `src/main` 에 **8곳**.

```kotlin
val jobId = requireNotNull(job.id) { "저장되지 않은 job은 confirm할 수 없습니다." }
```

| 파일 | 개수 |
|---|---|
| `job/service/JobLifecycleService.kt` | 3 |
| `job/worker/GenerationWorker.kt` | 2 |
| `job/service/HoldService.kt` | 1 |
| `job/worker/GenerationJobProcessor.kt` | 1 |
| `scheduler/DeadJobSchedulerTask.kt` | 1 |

**선택지**

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| (a) | **현행 유지** | 명시적, 실패 시 원인이 분명 | 8곳 반복 |
| (b) | 엔티티에 non-null 접근자 추가<br>`val persistedId: Long get() = requireNotNull(id)` | 호출부가 깔끔 | 엔티티에 `id` / `persistedId` 두 개가 공존해 혼란 |
| (c) | 백킹 필드를 `_id` 로 숨기고 `val id: Long` 만 노출 | 호출부에서 nullable이 완전히 사라짐 | "저장 여부"를 밖에서 못 물어봄, 구조 변경 큼 |
| (d) | 리포지토리 파라미터를 `Long?` 로 열기 | Java와 100% 동일, 변경 0 | nullable이 리포지토리 시그니처까지 번짐 |

> 8곳이면 (b)를 검토할 만한 양. 다만 (a)도 충분히 방어 가능.

**테스트 이식 중 취한 임시 조치**: 통합 테스트에서 같은 문제가 훨씬 많이 나와,
`src/test/.../support/PersistedId.kt` 에 확장 프로퍼티로 한 번만 확정했다.

```kotlin
val Organization.persistedId: Long get() = requireNotNull(id) { "저장되지 않은 Organization" }
```

이름을 (b)의 `persistedId` 와 일부러 똑같이 맞췄다. B-1을 (b)/(c)로 정하면
**이 파일만 지우면 되고 호출부는 손대지 않아도 된다.** (a)로 정하면 그대로 두면 된다.
즉 B-1 결정은 아직 열려 있고, 테스트 이식이 그 결정을 앞당겨 잠그지는 않았다.

---

### B-1 선택지별 실제 변경 범위 (2026-08-20 측정)

(b)와 (c)는 스파이크 브랜치에서 **실제로 적용해 전체 테스트를 돌려 본** 수치다.
둘 다 137개 테스트가 모두 통과했다. (d)는 시그니처 추적으로만 판단했다.

| | main 변경 | test 변경 | 전체 테스트 | 숨은 비용 |
|---|---|---|---|---|
| (a) 현행 | 0 | 0 | 137 ✅ | `requireNotNull` 8곳 + 테스트 헬퍼 유지 |
| **(b) 엔티티 접근자** | 9파일 +20 −8 | 17파일 **−38 (삭제만)** | 137 ✅ | `id` / `persistedId` 공존 |
| **(c) 백킹 필드 은닉** | 9파일 +24 −16 | 20파일 +159 −197 | 137 ✅ | 아래 함정 3개 |
| (d) 리포지토리 개방 | — | — | 미측정 | 8곳 중 3곳은 **해결 불가** |

**(b)의 핵심**: 테스트 173개 호출부가 **한 글자도 바뀌지 않는다.** 이름을 미리 맞춰 둔 덕에
test 쪽 변경은 `import` 16줄과 `PersistedId.kt` 삭제뿐이다.

**(c)에서 실제로 밟은 함정 3개**

1. **`@Column(name = "id")` 가 필수다.** 빼면 컬럼명이 `_id` 로 생성된다
   (H2 DDL로 확인). 테스트는 `ddl-auto: create-drop` 이라 그래도 통과하지만
   **기존 MySQL 스키마와 어긋난다.** 조용히 통과하는 게 더 위험하다.
2. **`ReflectionTestUtils.setField(job, "id", ...)` 가 깨진다.** 7곳/3파일.
   컴파일은 통과하고 **실행 시점에** `Could not find field 'id'` 로 19개가 무더기로 실패했다.
3. **`job.id` 를 읽는 것 자체가 예외를 던질 수 있게 된다.** catch 블록 안의
   `log.warn(..., job.id, ...)` 5곳이 여기 해당한다. 지금은 전부 DB에서 읽은 job이라
   안전하지만, **에러 핸들러가 원래 예외를 가리며 터질 수 있는 자리**가 5곳 생긴다.
   (a)/(b)에서는 nullable이라 `null` 로 찍히고 끝난다.

반면 JPQL은 손댈 필요가 없었다. 필드가 `_id` 여도 Hibernate가 선행 언더스코어를 떼고
속성명을 `id` 로 잡아 `WHERE j.id = :jobId` 가 그대로 동작한다.

**(d)가 안 되는 이유**: jobId가 리포지토리로만 흘러가는 자리는 8곳 중 5곳뿐이다.
나머지 3곳은 non-null을 요구하는 곳으로 이어져 `requireNotNull` 이 그대로 남는다.

| 자리 | 막는 대상 |
|---|---|
| `HoldService` | `HoldResult(jobId: Long)` — API 응답 DTO |
| `GenerationJobProcessor` | `HeartbeatRegistry.startHeartbeat(jobId: Long)` → `JobAttempt` (Redis 멤버 문자열) |
| `DeadJobSchedulerTask` | `hasLiveHeartbeat(jobId: Long)` / `removeHeartbeat(jobId: Long)` |

게다가 `WHERE j.id = :jobId` 에 null이 들어가면 예외가 아니라 **0행 매칭으로 조용히 지나간다.**

**참고**: `id` 가 null인지 묻는 코드는 main·test 통틀어 **0곳**이다.
(c)가 잃는 "저장 여부를 밖에서 묻는" 기능을 지금 쓰는 데는 없다.

**B-2 연동**: (b)와 (c) 모두 `JobResponse.from` / `LedgerResponse.from` 이 non-null id를
넘길 수 있게 되므로 B-2를 함께 닫을 수 있다.

---

### → 결정: **(b) 엔티티 접근자** (2026-08-20 적용 완료)

```kotlin
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
var id: Long? = null
    protected set

/**
 * persist 이후에만 유효한 id. 저장된 엔티티를 다루는 자리에서는 이쪽을 쓴다.
 * `id` 는 JPA가 persist 전 상태를 표현해야 해서 nullable로 남아 있을 뿐이다.
 */
val persistedId: Long
    get() = requireNotNull(id) { "아직 저장되지 않은 Job입니다." }
```

엔티티 4개에 넣었고, main 호출부 8곳의 `requireNotNull(job.id)` 가 `job.persistedId` 가 되면서
**`src/main` 에 `requireNotNull(...id)` 는 0곳**이 되었다.
테스트는 `support/PersistedId.kt` 를 지우고 import 16줄을 지운 것이 전부다 —
**호출부 173곳은 한 글자도 바뀌지 않았다.** 이식 때 이름을 미리 맞춰 둔 값을 여기서 받았다.

**(c)를 택하지 않은 이유**: 최종 형태는 (c)가 더 깔끔하지만, 치르는 값이 나쁘다.
측정에서 드러난 함정 3개가 **전부 컴파일러가 잡아주지 않는 종류**다 —
스키마 불일치는 테스트가 통과해버리고, `setField` 는 런타임에만 깨지고,
`job.id` 읽기 예외는 장애 상황(catch 블록)에서만 터진다.
잔액을 다루는 도메인에서 "에러 핸들러가 원래 예외를 가리며 터질 수 있는 자리" 5곳은 싸지 않다.

**남은 대가**: 엔티티에 `id`(nullable)와 `persistedId`(non-null)가 공존한다.
어느 쪽을 써야 하는지는 위 KDoc으로 안내한다. 나중에 (c)로 옮기고 싶어지면
그때 이 문서의 측정치를 그대로 쓰면 된다.

---

### B-2. 응답 DTO의 nullable `id` — **닫음** ✅

```kotlin
data class LedgerResponse(val id: Long?, ...)
data class JobResponse(val id: Long?, ...)
```

Java도 `Long id` 라 동작은 동일하지만, **DB에서 읽어온 엔티티는 id가 반드시 있다.**
API 스펙상 `"id": null` 이 나올 수 있는 것처럼 보이는 게 정확하지 않았다.

**→ B-1 (b)와 함께 닫았다** (2026-08-20).

```kotlin
data class JobResponse(val id: Long, ...)      // Long? → Long
data class LedgerResponse(val id: Long, ...)   // Long? → Long

fun from(job: Job) = JobResponse(job.persistedId, ...)
```

`LedgerResponse.jobId` 는 **nullable로 남겼다.** CHARGE 원장은 job과 무관해서
실제로 null이고, 이건 타입이 사실을 정확히 말하고 있는 경우다.

직렬화 결과는 달라지지 않는다 — 원래도 null이 나온 적이 없다.
바뀐 것은 **타입이 그 사실을 말하게 된 것**뿐이다.

---

### B-3. 엔티티 프로퍼티 스타일 — **현행 유지로 확정** ✅

현재는 Java의 Lombok `@Getter` + setter 없음을 재현하려고 `protected set` 을 쓴다.
`src/main` 전체에 **25곳**.

```kotlin
// 현재 — 필드당 3줄
@Column(nullable = false)
var balance: Long = balance
    protected set

// 대안 — 생성자 프로퍼티, 1줄
class Organization(
    @Column(nullable = false) var balance: Long,
    ...
)
```

**대안의 대가**: 생성자 프로퍼티에는 `protected set` 을 못 쓴다 → 외부에서 `org.balance = 999` 가 가능해진다.

**→ 현행 유지로 확정** (2026-08-20).

잔액과 job 상태를 다루는 도메인에서 **외부에서 아무나 `balance` / `status` 를 대입할 수 있게 되는 것**이
줄어드는 줄 수보다 훨씬 비싸다. 상태 전이는 전부 조건부 UPDATE(`transitionIfStatusAndAttemptMatch` 등)로
리포지토리에서만 일어나야 하는데, setter가 열리면 그 규율을 타입이 더 이상 지켜 주지 못한다.

장황함(25곳 × 3줄)은 인정하지만, 그건 **읽을 때만 드는 비용**이고
setter 개방은 **틀리게 쓸 수 있게 되는 비용**이다. 후자가 크다.

> 참고: `allOpen` 플러그인이 `@Entity` 를 open으로 만들기 때문에 `private set` 은 **컴파일 에러**다
> (`Private setters for open properties are prohibited`). `protected set` 만 가능.

---

### B-4. `HeartbeatRegistry` 의 생성자 2개 — **현행 유지** ✅

테스트용 `Clock` 주입을 위해 Java의 구조(주 생성자 + `@Autowired` 보조 생성자)를 그대로 유지했다.

```kotlin
class HeartbeatRegistry internal constructor(..., private val clock: Clock) {
    @Autowired
    constructor(...) : this(..., Clock.systemUTC())
```

Kotlin다운 방식은 기본값 파라미터(`clock: Clock = Clock.systemUTC()`)지만,
그러면 Spring이 생성자를 하나만 보고 `Clock` 빈을 찾다가 실패할 수 있어 검증 없이는 바꾸지 않았다.

**→ 현행 유지.** 원래 "실제 기동 테스트가 필요하다"고 적어 뒀는데, 그 검증은 이후
테스트 이식으로 자연히 채워졌다 — `CreditSystemKotlinApplicationTests` 가 컨텍스트를 띄우고,
`GenerationPipelineEndToEndTest` / `RetryRefundTest` 는 **실제 Redis 위에서 워커와 스케줄러를
돌리며 `HeartbeatRegistry` 를 Spring이 생성한 빈으로 사용한다.** 현재 구조가 동작하는 것은 확인됐다.

바꿀 이유가 없어졌으므로 Java와 같은 형태를 그대로 둔다.

---

### B-5. Java에 없던 의존성 — **현행 유지로 확정** ✅

```kotlin
testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")   // 필수에 가까움
```

**왜 필수인가**: Mockito의 `eq()`/`any()` 는 null을 반환하는데, Kotlin은 반환 타입을 non-null로
추론해 null 검사를 삽입하므로 `NullPointerException: eq(...) must not be null` 로 죽는다.
실제로 `GenerationWorkerUnitTest` 7개가 전부 이 에러로 실패했다.

부수 효과로 `` `when` `` 백틱이 `whenever` 로 바뀌어 가독성도 좋아진다.

**→ 현행 유지로 확정** (2026-08-20). 직접 래퍼를 만들 수도 있지만,
Mockito 매처의 null 반환을 Kotlin 타입 시스템에 맞추는 코드를 손으로 관리하는 쪽이
의존성 한 줄보다 비싸다. `GenerationWorkerUnitTest`, `GenerationJobProcessorTest`,
`HeartbeatRegistryTest`, `DeadJobSchedulerTaskTest` 4개 파일이 쓰고 있다.

#### 부기: 실제로 Java에 없는 의존성은 6개다

제목이 "2개"였지만 대조해 보니 다음 6개다.

제목이 "2개"였지만 대조해 보니 6개였고, 그중 3줄은 **미사용이라 지웠다** (2026-08-20).

| 의존성 | 성격 | 현재 |
|---|---|---|
| `mockito-kotlin` | **의도한 추가.** 위 사유 | 유지 |
| `kotlin-reflect` | Kotlin + Spring 필수. 선택의 여지 없음 | 유지 |
| `kotlin-test-junit5` | Kotlin 프로젝트 생성 시 기본 | 유지 |
| `spring-boot-starter-validation` (+ `-test`) | Initializr 기본값, 미사용 | **삭제** |
| `spring-boot-h2console` | Initializr 기본값, 미사용 | **삭제** |

삭제한 둘은 Spring Initializr가 붙여 준 것이지 이식 과정에서 고른 게 아니다.
Bean Validation은 A-4에서 **의도적으로 쓰지 않기로** 했고
(`jakarta.validation` / `@Valid` / `@field:NotBlank` 사용처 0곳),
`h2console` 도 `application.yml` 에 설정 항목이 0곳이었다.

삭제 후 확인한 것:

- `./gradlew clean test` 137개 통과 (컨텍스트 로딩·웹 컨트롤러·동시성 포함)
- `hibernate-validator` / `jakarta.validation` 이 **전이 의존으로도 들어오지 않는다** —
  즉 선언만 지운 게 아니라 실제로 클래스패스에서 빠졌다
- H2 드라이버(`com.h2database:h2`)는 그대로다. 테스트 DB라 필요하고,
  지운 것은 콘솔 UI 오토컨피그(`spring-boot-h2console`)일 뿐이다

Java 원본에도 없던 것들이라 이식 충실도와는 무관하다.

---

## C. 검증 상태

### 실행으로 확인된 것

| 항목 | 방법 |
|---|---|
| 스키마 4개 테이블 | MySQL `DESCRIBE` — 컬럼/인덱스/유니크/enum/길이 제약 일치 |
| 충전 API | 정상·중복·검증실패·미존재 조직 |
| job 생성 → 워커 처리 | HOLD → PROCESSING → COMPLETED, 원장·잔액 반영 |
| **재시도** | job이 2회 실패 후 `attemptNo=2` 에서 성공 |
| **환불** | `failure-rate=1.0` 강제 → 3회 소진 → REFUNDED, 잔액 복구 |
| 원장 대사 | `checkedCount=1, mismatchCount=0` |
| 단위 테스트 | 56개 통과 |
| **동시 충전/hold/멱등키** | 실제 MySQL 8.4 컨테이너에서 10스레드 경쟁 |
| **파이프라인 E2E·재시도 환불** | 실제 MySQL + Redis 위에서 워커·스케줄러 구동 |

### 확인되지 **않은** 것 ⚠️

- ~~**heartbeat 만료 회수 경로**~~ → **해소됨.** `DeadJobSchedulerTaskTest` 11개를 이식해
  `markExpiredJobsAsFailed` / `markStalledJobsAsFailed` 를 모두 덮었다.
  (실제 워커가 죽는 상황을 재현한 게 아니라 mock 기반이다. 다만 `RetryRefundTest` 가
  실제 MySQL·Redis 위에서 재시도 소진 → 환불까지 돌아가는 것을 확인하므로,
  스케줄러·워커·heartbeat가 맞물리는 끝단은 덮였다.)

---

## D. 앞으로 걸릴 것으로 예상되는 문제

### D-1. 옮길 수 없는 테스트가 나온다 (A-4의 결과)

```java
// ChargeServiceTest
chargeService.charge(organization.getId(), null, 300L)   // Kotlin에서 컴파일 불가
```

`idemKey` 를 non-null로 닫았으므로 **null을 넘기는 테스트는 컴파일 자체가 안 된다.**
Kotlin에서는 타입 시스템이 그 경우를 막았으니 테스트가 불필요해진 것이기도 하다.

**선택지**: (1) 해당 테스트를 삭제하고 "컴파일 타임에 보장됨"으로 간주,
(2) 컨트롤러 레벨 테스트로 옮겨 400 응답을 검증, (3) A-4를 되돌린다.

**→ (2)로 처리했다.** `ChargeServiceTest` 의 null 테스트는 옮기지 않고(주석으로 사유를 남김),
`OrganizationApiControllerTest` 에 `idemKey 필드가 없는 본문은 400으로 거부된다` 를 새로 추가했다.
Java에 없던 유일한 테스트다. 되돌리려면 이 테스트를 지우면 된다.

### D-2. 테스트 픽스처의 `null` 인자

Java 테스트가 `new AppProperties(null, stub, null, null)` 처럼 안 쓰는 설정에 null을 넘긴다.
프로덕션 코드를 nullable로 열지 않기 위해 **기본값을 채우는 헬퍼**로 대체했다.

```kotlin
// AppPropertiesFixture.kt (테스트 전용)
fun appProperties(
    generation: AppProperties.Generation = AppProperties.Generation(100L, 3),
    stub: AppProperties.Stub = AppProperties.Stub(0.0, 0, 0),
    ...
): AppProperties
```

같은 패턴이 남은 통합 테스트에서도 계속 필요하다.

---

## E. 남은 작업

| 계층 | 파일 | 상태 |
|---|---|---|
| 순수 단위 | 9 | ✅ 55 테스트 통과 (F-2로 1개 삭제) |
| Spring/JPA 통합 | 13 | ✅ 72 테스트 통과 (+ 컨트롤러 1개 신규 = 73) |
| 동시성(Testcontainers) | 6 | ✅ 5 테스트 통과 (실제 MySQL 8.4 / Redis 7) |
| ~~벤치마크~~ | ~~10~~ | **이식했다가 삭제** (아래) |

합계 27개 클래스 / 132 테스트 통과. **남은 이식 대상 없음.**

### 벤치마크는 삭제했다 (2026-08-20)

Java 원본의 벤치마크 10파일(`BalanceStrategyBenchmark`, `WorkerBatchSizeBenchmark`,
`BenchmarkHarness` 와 잔액 차감 전략 3종 등)을 이식해 실제 MySQL에서 한 번 돌려 본 뒤,
**이 프로젝트에 필요하지 않다고 판단해 지웠다.**

같이 없앤 것: `build.gradle.kts` 의 `benchmark` 태스크,
`test` 태스크의 `excludeTags("benchmark")`.
의존성은 하나도 지우지 않았다 — awaitility·mockito-kotlin·testcontainers는
모두 다른 테스트가 쓰고 있다.

되살리려면 커밋 `10932b4` (`test: 벤치마크를 이식한다`)에서 꺼내면 된다.
그 커밋 시점에는 두 벤치마크 모두 실제 MySQL 8.4에서 정상 동작했다.

측정으로 확인했던 것 (기록용):

- 잔액 차감 3전략 중 `optimistic-lock` 은 동시성이 오르면 무너진다.
  동시성 100에서 5,000건 중 2,190건만 성공, 재시도 140,749회, p99 1.07초.
  `conditional-update` / `pessimistic-lock` 은 100까지 유지된다.
  **셋 다 최종 잔액은 한 번도 어긋나지 않았다.**
- 워커는 `min(batch-size, concurrency)` 가 한 주기 처리량 상한이라
  batch-size를 concurrency 이상으로 키워도 소용이 없다.
  Java 원본의 `perf: size the worker batch to what one poll can dispatch` 와 같은 결론.

### 동시성 계층 실행 조건

**Docker 데몬이 떠 있어야 한다.** 꺼져 있으면 이 5개는 컨테이너를 못 띄우고 실패한다.
컨테이너는 `withReuse(true)` 라 한 번 뜨면 테스트 실행 사이에 살아남는다.

테스트마다 별도 데이터베이스를 쓴다 (`concurrent_charge`, `concurrent_hold`,
`duplicate_idem_key`, `pipeline_e2e`, `retry_refund`). 서로 격리되므로
Java 원본처럼 `@AfterEach` 정리가 없어도 간섭하지 않는다.

---

## F. 전체 코드 점검 (2026-08-20)

이식이 끝난 뒤 `src/main` 1,719줄과 `src/test` 2,830줄을 전부 읽고,
기계적 스윕(컴파일 경고, 미사용 import, 심볼 참조 수)도 함께 돌렸다.

**죽은 코드는 사실상 없었다.** 리포지토리 메서드 14개 전부 호출되고,
DTO·예외 클래스 전부 참조되며, `catch (e:)` 22곳 모두 `e` 를 실제로 쓴다.
컴파일 경고 0건. 대신 아래 세 가지가 나왔다.

### F-1. 스모크 테스트가 로컬 개발 MySQL에 붙고 있었다 🔴

`CreditSystemKotlinApplicationTests` 는 Spring 테스트 30개 중
**유일하게 `@ActiveProfiles("test")` 가 없었다.** Initializr가 만들어준 파일이
그대로 딸려 온 것이다(탭 들여쓰기가 남아 있던 것도 그 흔적).

실행해서 확인한 결과:

```
Database JDBC URL [jdbc:mysql://localhost:3306/credit_system_k]
GenerationWorker : 워커 처리량 상한: batch-size=3, concurrency=3, ...
```

H2가 아니라 `application.yml` 의 개발용 MySQL에 `ddl-auto: update` 로 붙고,
워커와 스케줄러까지 기동해 그 DB를 폴링하고 있었다.
지금까지 통과한 건 개발 머신에 MySQL·Redis가 떠 있었기 때문이고,
깨끗한 환경이나 CI에서는 `./gradlew test` 가 여기서 깨진다.

`@ActiveProfiles("test")` 를 붙여 해소했다. 이후 로그는 `jdbc:h2:mem:credit_test`.

지우는 선택지도 있었다 — 다른 `@SpringBootTest` 20여 개가 이미 컨텍스트 기동을
증명하므로 엄밀히는 중복이다. 13줄이고 관례적이라 남겼다.

### F-2. `LedgerEntry` 팩토리의 nullable 파라미터를 좁혔다

B-1·B-2와 같은 결의 정리다. **팩토리 파라미터만** 좁혔고,
엔티티 필드와 private 생성자는 nullable 그대로 두었다 —
CHARGE 원장은 `jobId` 가 진짜로 없고, 나머지 타입은 `idemKey` 가 진짜로 없기 때문이다.

| | 전 | 후 |
|---|---|---|
| `of(...)` | `jobId: Long?` | `jobId: Long` |
| `charge(...)` | `idemKey: String?` | `idemKey: String` |

`charge` 의 검증도 `require(!idemKey.isNullOrBlank())` → `require(idemKey.isNotBlank())`.

D-1과 같은 일이 또 일어났다: **`charge에 idemKey가 null이면 예외가 발생한다`
테스트가 컴파일 불가가 되어 삭제**했다. 런타임에 검증하던 걸 컴파일러가 대신
막아주게 된 것이다. 공백 검증 테스트는 여전히 런타임 책임이라 남아 있다.
(133 → 132 테스트)

`LedgerReconciliationTaskTest` 가 `of(..., null, ...)` 로 넘기던 자리는 `1L` 로 바꿨다.
그 테스트는 원장 금액 합계만 검증하므로 jobId 값은 판정에 영향이 없다.

### F-3. 동시성 테스트의 latch 하네스를 헬퍼로 묶었다

`ConcurrentChargeTest` / `ConcurrentHoldTest` / `DuplicateIdemKeyTest` 가
"ready / start / done" 3-latch 패턴을 25줄씩 그대로 복사해 쓰고 있었다.
`Concurrently.kt` 의 `runConcurrently(threadCount) { idx -> ... }` 하나로 묶어 약 50줄을 없앴다.

**동작이 완전히 똑같은 순수 리팩터링이다.** `done.await(30, TimeUnit.SECONDS)` 의
반환값을 무시하는 것도 원본 그대로 두었다.

각 테스트가 기대하는 도메인 예외(`DataIntegrityViolationException`,
`InsufficientBalanceException`, `DuplicateRequestInProgressException`)는
서로 달라서 헬퍼가 잡지 않는다. 호출하는 쪽 블록에 그대로 남겼다 —
"어떤 실패를 정상으로 보는가"가 각 테스트의 핵심 단언이기 때문이다.

### F-4. 함께 정리한 자잘한 것

- `GenerationWorkerUnitTest` 의 미사용 import `org.mockito.kotlin.anyOrNull`
- `ErrorResponse` 의 빈 본문 `) { }` → `)`
- 파일 끝 개행 누락 4건 (`WorkerExecutorConfig`, `WorkerProperties`,
  `ErrorResponse`, `StubGenerationException`)
- `GenerationJobProcessor.confirmWithRetry` 가 nullable인 `job.id` 를 쓰던 2곳 →
  `job.persistedId`. `runGeneration` 은 이미 `val jobId = job.persistedId` 를
  만들어 두는데 private 함수 안에서만 옛 방식이 남아 있었다 (B-1 (b) 취지와 어긋남)

### F-5. 그대로 두기로 한 것

- **`confirmWithRetry` 의 `throw lastFailure ?: IllegalStateException(...)`** —
  루프가 최소 1회 돌므로 elvis 오른쪽은 실제로 도달 불가하다. 그래도
  `lastFailure` 가 nullable이라 컴파일러가 요구하고, `!!` 보다 낫다.
- **리포지토리의 들쭉날쭉해 보이는 `@Transactional`** — 의도적이다.
  스케줄러·워커가 직접 부르는 메서드(`JobRepository` 전부, `deleteByIdIn`)에만 붙어 있고,
  항상 `@Transactional` 서비스 안에서만 불리는 것(`deductBalance`, `addBalance`,
  `attachJobId`)에는 없다. 호출 경로를 전부 따라가 확인했다.
- **`HeartbeatRegistry` 의 생성자 2개** — B-4에서 이미 확정.
- **`application.yml` 의 DB 비밀번호** — Java 원본과 같은 값이고 로컬 개발용이다.
  다만 이번에 git 히스토리에 들어갔다는 점은 알고 있어야 한다.

### 점검에서 헛짚은 것

`AppPropertiesFixture.kt` 가 참조 0으로 잡혀 죽은 파일처럼 보였다.
파일명과 심볼명(`appProperties`)이 달라서 생긴 착시였고 실제로는 6곳에서 쓴다.

---

## G. 가벼운 재점검 (2026-08-21)

F 점검 다음 날 `src/main` 을 다시 훑었다. **새로 나온 건 F가 흘린 잔재 3건뿐**이고,
죽은 코드·미사용 import·파일 끝 개행 누락은 0건이었다.
`--rerun-tasks` 로 강제 재컴파일해도 경고 0건, `cleanTest test` 132개 전부 통과.

### G-1. `ChargeService` 의 맨 `orElseThrow()` — 예외 계층을 닫았다

충전 성공 직후 잔액을 다시 읽는 자리만 `orElseThrow()` 였다.
같은 함수 14줄 위(중복 감지 분기)는 `OrganizationNotFoundException` 인데 여기만 갈렸다.

```
- val balance = organizationRepository.findById(organizationId).orElseThrow().balance
+ val balance = organizationRepository.findById(organizationId)
+     .orElseThrow { OrganizationNotFoundException(organizationId) }
+     .balance
```

**도달 불가능한 자리다** — 바로 위 `addBalance` 가 1을 반환했으니 organization 은 반드시 있다.
그래도 고친 이유는, 도달했을 때 `NoSuchElementException` 이 나고
`GlobalExceptionHandler` 에 핸들러가 없어 500으로 나가기 때문이다.
A-1·A-3에서 예외 계층을 닫은 것과 짝을 맞췄다.

Java 원본 `ChargeService.java:48` 도 맨 `orElseThrow()` 라, 이식 충실성 자체는 원래 맞았다.

### G-2. `job.id` 6곳 중 **1곳만** 고쳤다 — 나머지 5곳은 현행이 옳다

F-4가 `GenerationJobProcessor` 의 `job.id` 만 `persistedId` 로 바꾸고
`GenerationWorker`·`DeadJobSchedulerTask` 는 지나쳤다. 6곳이 남아 있었다.

따라가 보니 **6곳 중 5곳이 `catch` 블록 안**이었다
(`GenerationWorker:74,86,99`, `DeadJobSchedulerTask:80,97`).
`persistedId` 는 `requireNotNull` 이라 던질 수 있고,
하필 `GenerationWorker.claim` 은 `job.persistedId` 호출(65행) 자체가
그 catch 로 잡혀 오는 경로다. **catch 안에서 다시 `persistedId` 를 부르면
로그를 찍으려다 스케줄러 루프를 깨뜨린다.** nullable `job.id` 가 여기서는 옳은 선택이다.

정상 경로에 있던 건 `GenerationWorker:68`(`updated == 0` 분기) 하나뿐이고,
그 자리는 65행의 `persistedId` 가 이미 성공한 뒤라 던질 수 없다. 여기만 바꿨다.

**B-1 (b)의 "nullable id 를 쓰지 않는다"는 무조건이 아니다.
예외 경로의 로깅에는 던지지 않는 `job.id` 를 쓴다** — 이 예외를 여기 명시해 둔다.

### G-3. `catch (e: StubGenerationException)` 의 `e` 미사용

F가 "`catch (e:)` 22곳 모두 `e` 를 실제로 쓴다"고 적었는데 **여기 한 곳은 틀렸다.**
stub 실패는 예상된 실패라 로깅 없이 `markFailed` 만 하고 빠지는 자리다.

`catch (_: StubGenerationException)` 으로 바꿔 "일부러 안 쓴다"를 문법으로 드러냈다.
Java 에는 `_` 가 없어 원본이 어쩔 수 없이 `e` 를 남겨 둔 자리다.

### G-4. 그대로 두기로 한 것

- **`application.yml` 의 `spring.application.name: credit_system`** — 사용자 판단으로 유지.
  부록에서 `logging.level...credit_system` → `_kotlin` 으로 고친 것과 짝이 안 맞는 잔재지만,
  죽은 설정은 아니라 실제로 로그에 `[credit_system]` 으로 찍힌다(테스트 로그로 확인).
  Java 원본과 이름이 같아 **두 앱을 나란히 띄우면 로그에서 구분되지 않는다**는 점은 알고 있어야 한다.
  배포 환경 이름에 영향이 갈 수 있어 건드리지 않았다.
- **`CreditSystemKotlinApplication.kt:16` 의 탭 들여쓰기** — 코드베이스에 남은 유일한 탭이고,
  F-1이 Initializr 흔적의 표식으로 지목했던 바로 그것이다. 1줄이라 그냥 뒀다.
  (`build.gradle.kts` 도 탭이지만 파일 전체가 일관되어 별개다.)
- **`application.yml` 의 DB 비밀번호** — F-5 기록대로 여전하다. 상태 변화 없음.

---

## H. 코틀린 관용성 점검 (2026-08-21)

G까지는 "이식이 정확한가"를 봤다. H는 렌즈를 바꿔 **"Java를 그대로 옮겨서
코틀린이 주는 걸 안 쓰고 있는 자리"** 를 찾았다. `src/main` 1,722줄 전부 + 기계적 스윕.

### 이미 잘 되어 있던 것 (근거)

- **`!!` 0건, `lateinit` 0건.** B-1·B-2에서 nullable을 타입으로 닫기로 한 게
  말로만 남지 않았다. `persistedId` 접근자가 그 자리를 메우고 있다.
- **로거 24곳 전부 파일 최상위 `private val log`.** `companion object` 안에 넣는
  Java 흉내가 아니다.
- `return try { } catch { }` 를 식으로 쓰는 것(`GenerationWorker.claim`),
  단일식 함수 `=`, `isNullOrEmpty()`, `toLongOrNull()`, elvis + early return — 관용적이다.
- 엔티티의 `protected set` 스타일은 **B-3 확정 사항이라 건드리지 않았다.**

### H-1. `Optional` 을 코드베이스에서 몰아냈다

A-2에서 `Optional<T>` → `T?` 로 정했는데, 고친 건 커스텀 쿼리 메서드뿐이고
`JpaRepository` 에서 상속받는 `findById` 는 `Optional` 그대로였다. 4곳.

```kotlin
- organizationRepository.findById(organizationId)
-     .orElseThrow { OrganizationNotFoundException(organizationId) }
+ organizationRepository.findByIdOrNull(organizationId)
+     ?: throw OrganizationNotFoundException(organizationId)
```

`org.springframework.data.repository.findByIdOrNull` — spring-data-commons 4.1.0 에
들어 있는 코틀린 확장이다(`javap` 로 확인).

`HoldService:84`, `ChargeService:28,43`, `OrganizationApiController:25`.

값 하나만 꺼내 쓰는 `ChargeService` 두 곳은 전체를 괄호로 감싸는 대신
안전 호출을 앞세우는 쪽이 읽기 낫다. `balance: Long` 이 non-null 이라 의미는 같다.

```kotlin
val balance = organizationRepository.findByIdOrNull(organizationId)?.balance
    ?: throw OrganizationNotFoundException(organizationId)
```

이제 `grep -rn "orElseThrow\|Optional" src/main` 이 **0건**이다. A-2가 비로소 끝났다.

### H-2. do-while 의 `var` 호이스팅 2곳 — 코틀린은 이게 필요 없다

```kotlin
var checks: List<LedgerBalanceCheck>   // ← 조건절에서 보려고 밖으로 끌어올림
do {
    checks = ledgerRepository.findBalanceChecksAfter(...)
} while (checks.size == RECONCILE_BATCH_SIZE)
```

Java 는 do-while 조건절이 본문 스코프를 못 봐서 변수를 밖으로 빼야 한다.
**코틀린은 본문에서 선언한 `val` 을 조건절에서 볼 수 있다.**
(이 프로젝트에 스크래치 파일을 넣어 실제로 컴파일해 확인했고, 확인 후 삭제했다.)

선언줄을 없애고 루프 안에서 `val` 로 선언했다. `var` → `val` 이 되고 스코프도 좁아진다.
`LedgerReconciliationTask`(`checks`), `IdempotencyKeyCleanupTask`(`ids`).
`lastId`·`deletedCount` 같은 누적 변수는 `var` 그대로다.

### H-3. cause 체인 순회 → `generateSequence`

`GlobalExceptionHandler.isUniqueConstraintViolation` 이 `var cause` + `while` 로
예외 cause 체인을 훑고 있었다. cause 체인은 `generateSequence` 의 교과서적 사례다.

```kotlin
generateSequence(e) { it.cause }
    .filterIsInstance<ConstraintViolationException>()
    .firstOrNull()
    ?.kind == ConstraintViolationException.ConstraintKind.UNIQUE
```

의미는 그대로다 — **처음 만난** `ConstraintViolationException` 의 `kind` 만 보고,
UNIQUE 가 아니면 뒤를 더 뒤지지 않는다. 하나도 없으면 `false`.

### H-4. `WorkerExecutorConfig` → `apply {}`

`executor.` 가 6번 반복되던 걸 `ThreadPoolTaskExecutor().apply { }` 한 덩어리 +
단일식 함수로 바꿨다. `initialize()` 는 반드시 마지막이다.

### H-5. `setThreadNamePrefix` 는 프로퍼티로 못 바꾼다 — 헛짚었다

H-4를 하면서 "`setThreadNamePrefix(...)` 만 자바식 호출이라 스타일이 섞였으니
`threadNamePrefix = ...` 로 바꾸자"고 판단했다. **틀렸다. 컴파일 에러다.**

```
e: WorkerExecutorConfig.kt:18:13 'val' cannot be reassigned.
```

이유를 파고 보니:

| 클래스 | getter | setter |
|---|---|---|
| `CustomizableThreadCreator` | `getThreadNamePrefix()` | `setThreadNamePrefix(String)` |
| `ExecutorConfigurationSupport` | — | `setThreadNamePrefix(String)` **override** |

`ExecutorConfigurationSupport` 가 `threadNamePrefixSet` 플래그를 세우려고
**setter 만 오버라이드**한다. 그래서 getter/setter 가 서로 다른 클래스에 흩어지고,
**코틀린은 둘이 같은 클래스에 있을 때만 가변 프로퍼티로 합성한다.**
결과적으로 `threadNamePrefix` 는 읽기 전용 `val` 로 노출되고, 쓰려면
`setThreadNamePrefix(...)` 를 직접 불러야 한다.

처음에 `CustomizableThreadCreator` 만 `javap` 로 보고 "쌍이 멀쩡하다"고 단정한 게 화근이었다.
**자바 상속 계층에서 getter/setter 합성 여부를 판단할 때는 상속 사슬 전체를 봐야 한다.**
`apply {}` 블록 안에 프로퍼티 대입 3개와 setter 호출 1개가 섞여 있는 건 지금이 최선이다.

> 부기: `grep -rn '\.set[A-Z]' src/main` 은 이제 0건으로 나오지만 착시다.
> `apply {}` 안이라 수신자 점이 없을 뿐, `setThreadNamePrefix(...)` 호출은 그대로 있다.

### H-6. 그 밖에 보고 안 고친 것

- **`HeartbeatRegistry:66` 의 `HashSet<JobAttempt>()` → `mutableSetOf()`** 는 고쳤다.
  main 에 남아 있던 유일한 java.util 컬렉션 직접 생성이었다.
  다만 **주변 for 루프는 그대로 뒀다** — `else` 분기에 부수효과(`removeUnparseableMember`)가
  있어서 `mapNotNull` 로 바꾸면 오히려 나빠진다.
- **`JobAttempt.parse` 의 `indexOf`/`substring`** — `split` 로 바꿀 수 있지만 지금이 더 명확하다.
- **`DeadJobSchedulerTask.scan()` 의 try/catch 3연속** — 헬퍼로 묶을 수 있으나
  세 메시지가 각각 달라 이득이 작다.
- **`validateRequest` 의 if/throw 사슬** — `require` 는 `IllegalArgumentException` 을 던진다.
  여기는 `InvalidRequestException` 이 나가야 하므로 if/throw 가 맞다.
- **120자 넘는 줄 20개** — ktlint·detekt 가 안 붙어 있어 강제 규칙 자체가 없다.
  붙일지는 별도 판단거리로 남긴다.

---

## I. ktlint 도입 (2026-08-21)

H-6에서 "120자 넘는 줄 20개인데 강제 규칙이 없다"고 남긴 걸 처리했다.
**그 20개라는 숫자부터 틀렸다는 걸 이번에 알았다 — I-6 참고.**

- 플러그인: `org.jlleitschuh.gradle.ktlint` 14.2.0
- ktlint 본체: 1.8.0
- `check` 태스크가 `ktlintCheck` 에 의존한다(플러그인 기본값). 즉 `./gradlew check` 로 잡힌다.
- 검사 범위는 `src/main`, `src/test`, 그리고 **빌드 스크립트까지** 3개다.

### I-1. 규칙 범위를 "위생 검사"로 좁혔다 — 실측으로 정했다

붙이기 전에 코드 스타일 3안의 위반 건수를 실제로 재 봤다.

| 안 | 위반 | 성격 |
|---|---:|---|
| `ktlint_official` (max 140) | **355** | 코드베이스 전반을 다시 짜야 함 |
| `intellij_idea` 기본값 (max 120) | **185** | 자동수정이 59파일 +441/−267 을 바꾸고도 수렴 안 함 |
| **위생 규칙만 (채택)** | **17** | 전부 진짜 문제, 30줄 안팎으로 해소 |

> 처음엔 위생 규칙안의 위반을 10건으로 셌는데 실제로는 17건이었다.
> `ktlintCheck` 는 `ktlintMainSourceSetCheck` 가 실패하면 거기서 빌드를 멈춘다.
> `--continue` 를 안 붙여 **`src/test` 소스셋이 아예 검사되지 않은 채로 집계**한 것이다.
> 소스셋별 위반을 셀 때는 `--continue` 를 붙여라.

`intellij_idea` 기본값으로 `ktlintFormat` 을 실제로 돌려봤더니 이런 변형이 나왔다:

```kotlin
- class LedgerReconciliationTask(
-     private val ledgerRepository: LedgerRepository
- ) {
+ class LedgerReconciliationTask(private val ledgerRepository: LedgerRepository) {
```

**린터의 값어치는 여기서 대량 리포맷이 아니라 앞으로의 드리프트를 막는 데 있다.**
이미 A~H를 거치며 의도적으로 다듬어 둔 코드를 도구 취향으로 다시 흔들 이유가 없다.
그래서 "사람이 정하는 것"과 "기계가 지켜야 하는 것"을 갈라, 전자를 껐다.

### I-2. 끈 규칙과 그 이유

| 규칙 | 이유 |
|---|---|
| `package-name` | 패키지가 `credit_system_kotlin` 이다. **자동 수정 불가**이고, JPQL 문자열에 FQ 패키지명이 박혀 있어(`com.example.credit_system_kotlin.job.domain.JobStatus.PROCESSING`) 개명은 위험하다. 47건 |
| `class-signature`, `function-signature` | 선언을 몇 줄로 쪼갤지는 사람이 정한다. B-3에서 확정한 엔티티 스타일과도 충돌한다 |
| `argument-list-wrapping` | 한국어 로그 인자를 의미 단위로 묶어 둔 걸 인자마다 한 줄로 흩뜨린다 |
| `trailing-comma-on-call-site`, `trailing-comma-on-declaration-site` | 이 코드베이스는 후행 콤마를 쓰지 않는다 |
| `enum-wrapping`, `function-expression-body` | 취향 |

**켜 둔 것**: `indent`, `max-line-length`(120), `no-wildcard-imports`, `import-ordering`,
`final-newline`, `spacing-between-declarations-with-annotations`, 그 밖의 공백·줄바꿈 위생 규칙.

실제로 무엇이 잡히는지는 **일부러 위반을 넣어 확인했다**(확인 후 복구):

| 넣은 것 | 결과 |
|---|---|
| 탭 들여쓰기 | 잡힘 (`indent`) |
| ASCII 123자 줄 | 잡힘 (`max-line-length`) |
| `import java.time.*` | 잡힘 (`no-wildcard-imports`) |
| `import java.util.*` | **안 잡힘** — ktlint 기본 허용 목록(`packages_to_use_import_on_demand`)에 들어 있다 |
| 임포트 순서 뒤바꿈 | 잡힘 (`import-ordering`) |
| 미사용 임포트 2개 | **안 잡힘** — `NoUnusedImportsRule` 이 룰셋 jar 에는 있지만 발동하지 않는다 |

> **미사용 임포트는 이 설정으로 안 잡힌다.** 코틀린 컴파일러도 경고하지 않으므로
> 여전히 IDE나 사람 눈에 의존한다. F-4에서 미사용 import 를 손으로 찾아낸 상황이 반복될 수 있다.
> → **J 절에서 detekt 로 해결했다.**

### I-3. 해소한 위반 17건

| 자리 | 내용 |
|---|---|
| `CreditSystemKotlinApplication.kt:16` | **G-4에서 "1줄이라 그냥 뒀다"고 넘긴 Initializr 탭.** 린터가 바로 잡아냈다 |
| `AppProperties.kt:31,32` / `GenerationWorker.kt:42,43` / `RedisOutageGate.kt:55` | 문자열을 `+` 로 이어붙일 때 이어지는 줄을 4칸 더 들여쓴 자리 (24 → 20, 20 → 16) |
| `IdempotencyKey.kt:16` | `@Table` 의 `uniqueConstraints` 121자 |
| `JobResponse.kt:17` | `JobResponse(...)` 생성자 호출 130자 |
| `IdempotencyKeyRepository.kt:17` | `@Query` JPQL 124자. **다른 리포지토리 3개가 이미 쓰는 삼중따옴표 여러 줄 형태로 맞췄다** |

추가로 **`src/test` 에서 7건** — `@Mock lateinit var` 선언들이 빈 줄 없이 붙어 있던 자리다
(`spacing-between-declarations-with-annotations`). 테스트 4개 파일에 빈 줄 7개가 들어갔다.

들여쓰기 7건과 테스트 7건은 `ktlintFormat` 이 자동 수정했고,
120자 초과 3건은 자동 수정이 안 돼 손으로 줄바꿈했다.

### I-4. 빌드 스크립트도 검사 대상에 넣었다

`build.gradle.kts` 는 Initializr 가 만든 탭 들여쓰기였다. `.kts` 를 검사에서 빼는 대신
**4칸 공백으로 변환해 검사 대상에 그대로 뒀다.** 이제 프로젝트에 탭이 한 곳도 없다.

### I-5. `.editorconfig` 가 새로 생겼다

ktlint 설정은 전부 `.editorconfig` 에 있다(플러그인이 아니라 ktlint 본체가 읽는다).
`[*]` 섹션에 `charset`/`end_of_line`/`insert_final_newline`/`trim_trailing_whitespace` 도 함께 뒀다 —
IDE 와 린터가 같은 파일을 보게 하려는 것이다. `.gitattributes` 의 `eol=lf` 와도 어긋나지 않는다.

> 주의: 규칙을 더 끄고 싶어질 때 `.editorconfig` 를 손대는 것으로 위반을 없애지 마라.
> I-1의 판단(위생 규칙은 기계가 지킨다)이 무너진다.

### I-6. `awk length` 로 줄 길이를 세면 안 된다 — H-6이 틀렸다

H-6에 "120자 넘는 줄 20개"라고 적었다. **틀렸다. 실제로는 3줄이다.**

`awk 'length>120'` 이 로케일에 따라 **바이트**를 센다. 한글은 UTF-8 에서 글자당 3바이트라
한국어 로그 메시지가 3배로 부풀려진다. ktlint 는 **문자**를 센다.

같은 커밋(`a8c1368`)의 `src/main` 을 두 방식으로 세어 보면:

| 기준 | 결과 |
|---|---|
| 120 **바이트** 초과 (`awk length`) | 20줄 |
| 120 **문자** 초과 (ktlint) | **3줄** |

한국어가 섞인 코드베이스에서 줄 길이를 잴 때는 문자 단위로 세야 한다.
`python3` 의 `len(line)` 이나 ktlint 자체를 쓰는 게 맞다.
"터미널에서 넓어 보이는 것"과도 다르다 — 한글은 표시 폭이 2칸이라 눈으로도 과대평가된다.

---

## J. detekt 도입 (2026-08-21)

I-2에 "미사용 임포트는 ktlint 로 안 잡힌다"고 남긴 걸 처리했다. **detekt 는 그 한 가지를
메우려고 붙였다.**

### J-1. 먼저 ktlint 로 되는지부터 확인했다 — 안 된다

`NoUnusedImportsRule` 이 ktlint 룰셋 jar 에 분명히 들어 있어서,
`.editorconfig` 에 `ktlint_standard_no-unused-imports = enabled` 로 명시해 강제해 봤다.
미사용 임포트 2개를 넣고 돌렸더니 **0건**. 룰이 발동하지 않는다.
ktlint 로는 길이 없다는 걸 확인하고 detekt 로 넘어갔다.

### J-2. detekt Gradle 플러그인은 이 환경에서 못 쓴다 — 3겹으로 막힌다

`io.gitlab.arturbosch.detekt` 1.23.8(최신 안정판)을 붙였더니 순서대로 이렇게 막혔다.

**(1) Kotlin 버전 불일치**

```
detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21.
```

detekt 자신의 컨피규레이션에서만 Kotlin 을 2.0.21 로 되돌려 해소했다(공식 회피책).

**(2) JVM 타깃 26**

```
java.lang.IllegalArgumentException: 26
    at KotlinEnvironmentUtilsKt.createKotlinCoreEnvironment(KotlinEnvironmentUtils.kt:61)
```

이 머신의 실행 JDK 가 **26** 이다(Gradle 데몬도 26). 61행은
`KotlinCoreEnvironment.createForProduction(...)` 이고, **detekt 가 물고 있는
Kotlin 2.0.21 컴파일러가 JDK 26 런타임을 모른다.**

`jvmTarget = "17"`, `jdkHome` 을 JDK 17 로 지정 — **둘 다 소용없었다.**
태스크 속성은 제대로 `17` 로 들어가는데도 같은 예외가 난다.
문제는 detekt 가 분석 대상에 쓰는 타깃이 아니라 **detekt 자신이 돌고 있는 JVM** 이기 때문이다.
플러그인의 `Detekt` 태스크는 Gradle 데몬 안에서 인프로세스로 돌고,
1.23.8 의 그 태스크에는 `javaLauncher` 속성이 없다.

실제로 `-Dorg.gradle.java.home=<JDK 21>` 로 **데몬 전체를 내리면 즉시 동작한다.**
원인은 확정됐지만, 린터 하나 붙이자고 프로젝트의 모든 빌드·테스트가 도는 JVM 을
통째로 내리는 건 대가가 너무 크다.

**(3) 그래서 플러그인을 버리고 `detekt-cli` 를 `JavaExec` 로 분리했다**

```kotlin
val detekt by tasks.registering(JavaExec::class) {
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    javaLauncher.set(detektLauncher)   // JDK 17
    ...
}
```

`JavaExec` 는 `javaLauncher` 를 받는다. **데몬은 26 그대로 두고 detekt 만 17 에서 돈다.**
JDK 17 툴체인은 이 프로젝트가 이미 컴파일에 쓰고 있어서(`java { toolchain { 17 } }`)
새로 요구되는 게 없다.

여기서도 Kotlin 이 2.3.21 로 올라와 `ClassNotFoundException:
KotlinExpressionParsing$Precedence` 가 났고, `detektCli` 컨피규레이션에만
`useVersion("2.0.21")` 을 걸어 해소했다.

`tasks.named("check") { dependsOn(detekt) }` 로 물려서 `./gradlew check` 하나로 잡힌다.

### J-3. 규칙 범위 — I-1과 같은 원칙

기본 설정 그대로면 **115건**이 나온다. 내용을 보니 **전부 A~I 에서 이미 근거를 남기고
확정한 것들이었다.**

| 규칙 | 건수 | 이미 내린 결정 |
|---|---:|---|
| `PackageNaming` | 78 | 패키지 언더스코어. I-2와 같은 이유로 개명 불가 |
| `TooGenericExceptionCaught` | 19 | 스케줄러·워커의 `catch (e: RuntimeException)` 는 F-5에서 의도적이라고 확인 |
| `MagicNumber` | 4 | 검증 상한(100자·1000자)은 쓰이는 자리에 두는 편이 낫다 |
| `ReturnCount` | 3 | `JobAttempt.parse` 의 이른 return 은 H-6에서 현행 유지로 확정 |
| `ThrowsCount` | 3 | `validateRequest` 의 검증 사슬. `require` 는 `IllegalArgumentException` 이라 못 쓴다(H-6) |
| `UseCheckOrError` | 1 | `lastFailure ?: IllegalStateException` 은 F-5에서 확정 |
| `LoopWithTooManyJumpStatements` | 1 | `IdempotencyKeyCleanupTask` 의 `break` 2개는 의도적 |
| 그 밖 | 6 | `SwallowedException`, `NestedBlockDepth`, `EmptyFunctionBlock`, `SpreadOperator` |

**도구가 이미 끝난 논의를 매번 다시 열게 둘 이유가 없다.** 룰셋 단위로 끄고
미사용 코드 계열만 켜서 **115 → 0** 으로 맞췄다. 설정은 `detekt.yml` 에 있고
규칙마다 왜 껐는지 주석으로 적어 뒀다. 범위를 넓히고 싶으면 룰셋을 하나씩 켜면 된다.

> 함정: `ReturnCount` 와 `ThrowsCount` 는 이름만 보고 `complexity` 룰셋으로 착각했는데
> 실제로는 **`style` 소속**이다. 엉뚱한 룰셋에 적으면 detekt 가
> `Property 'complexity>ReturnCount' is misspelled or does not exist` 로 거절한다.
> 그리고 룰셋 단위 `active: false` 는 `style` 처럼 개별 규칙을 켜야 하는 룰셋에는
> 쓸 수 없어서, 그 안의 불필요한 규칙은 개별로 꺼야 한다.

### J-4. 무엇이 잡히는지 확인했다

일부러 죽은 코드를 넣고 돌려 봤다(확인 후 복구):

| 넣은 것 | 결과 |
|---|---|
| 미사용 임포트 2개 | **잡힘** `[UnusedImports]` ← 이걸 하려고 붙였다 |
| 안 읽는 private 프로퍼티 | 잡힘 `[UnusedPrivateProperty]` |
| 안 부르는 private 함수 | 잡힘 `[UnusedPrivateMember]` |
| 안 쓰는 함수 파라미터 | 잡힘 `[UnusedParameter]` |

### J-5. 알고 있어야 할 것

- **detekt 1.23.8 은 Kotlin 2.0.21 프런트엔드로 소스를 파싱한다.** 이 프로젝트는 2.3.21 이다.
  지금 코드에는 2.1+ 전용 문법이 없어 문제가 없지만, 새 문법을 쓰기 시작하면 깨질 수 있다.
- detekt 2.x 가 나오면 이 회피책 3개(Kotlin 핀 고정 2곳 + JavaExec 분리)를 걷어내고
  플러그인으로 돌아갈 수 있는지 다시 보라.
- 타입 해석은 쓰지 않는다(`--classpath` 미지정). 타입 해석이 필요한 규칙은 동작하지 않는다.
  미사용 임포트·미사용 private 코드에는 필요 없다.

---

## K. confirm 재시도 예외 범위를 좁혔다 (2026-08-21)

`GenerationJobProcessor.confirmWithRetry` 는 `catch (e: RuntimeException)` 으로
모든 런타임 예외를 재시도 대상으로 삼고 있었다. 재시도가 있는 이유부터 다시 짚어야 한다.
`generate()` 는 비싸고 `confirm()` 은 싸다. confirm 한 번 삐끗했다고 바로 포기하면
job이 FAILED 로 떨어지고, 스케줄러가 attemptNo+1 로 재시도할 때 **생성을 처음부터 다시 한다.**
이미 손에 쥔 `resultUrl` 은 버려지고 외부 호출 비용을 다시 문다. confirm 실패 빈도가 낮아도
잃는 게 비대칭으로 크기 때문에, 몇 번 더 찔러보는 쪽이 기대값이 맞는다.

문제는 "몇 번 더 찔러본다"의 대상을 `RuntimeException` 전체로 잡아 버리면,
재시도해도 결과가 똑같은 예외 — 예를 들어 `DataIntegrityViolationException` 같은
제약 위반이나 그 밖의 프로그래밍 오류 — 까지 3회를 꽉 채워 돈다는 점이다.
`CONFIRM_RETRY_DELAY_MILLIS` 200ms 를 두 번 자니 400ms 를 날린 뒤에야 포기하는데,
그 400ms 는 아무것도 바꾸지 않는다. 재시도는 "다시 하면 성공할 수도 있는" 예외에만 값어치가 있다.

### 고른 세 예외

- `org.springframework.dao.TransientDataAccessException` — 락 경합, 데드락, 쿼리 타임아웃
  계열의 상위 타입이다. Spring 이 리포지토리 경계에서 번역해 던지는 예외라
  하위 타입 이름은 몰라도 이 상위 타입으로 잡으면 그 계열을 전부 커버한다.
- `org.springframework.dao.RecoverableDataAccessException` — 이름 그대로
  커넥션을 복구하면 재시도할 수 있는 경우를 가리키는 타입이다.
- `org.springframework.transaction.CannotCreateTransactionException` — 커넥션 풀 고갈.
  이 프로젝트에서 가장 현실적으로 일어날 법한 재시도 케이스라고 판단해 넣었다.

세 타입 모두 Spring Framework 7.0.8 에 그대로 존재함을 확인했다.

`org.springframework.transaction.TransactionSystemException` 은 의도적으로 뺐다.
커밋 자체가 실패했다는 뜻인데, 커밋 실패는 대개 제약 위반이나 트리거 오류처럼
데이터 상태에서 비롯된 문제라 다시 커밋한다고 통과하지 않는다. 일시적 예외가 아니다.

판정은 `isRetryable` private 함수로 뽑았다. cause 체인은 순회하지 않는다 —
Spring 이 이미 리포지토리 경계에서 예외를 번역해 던지므로, 여기서 잡히는 타입 자체가
번역된 타입이고 cause 를 파고들 이유가 없다.

### 재시도 대상이 아니면 즉시 던진다

Kotlin 은 multi-catch 가 없어서 `catch (e: RuntimeException)` 은 그대로 두고,
잡은 직후 `isRetryable(e)` 가 false 면 sleep 없이 바로 `throw e` 한다.
이때 `log.error` 로 재시도하지 않는다는 사실을 남긴다 — 안 그러면 나중에 로그만 보고
"왜 3번 안 돌았지" 하고 헤매게 된다. 재시도 대상이면 기존 그대로 `lastFailure` 에 담고
`log.warn` 을 남긴 뒤 다음 시도로 넘어간다.

바깥쪽 `runGeneration` 의 로그 문구도 손댔다. "생성 결과 반영 재시도 소진, timeout 회수 대기"는
재시도를 다 썼다는 것을 전제로 하는데, 이제 재시도 없이 1회 만에 즉시 실패하는 경로도
같은 자리로 들어온다. "생성 결과 반영 실패, timeout 회수 대기"로 바꿔 재시도 여부를
단정하지 않게 했다. 바깥 `catch (e: RuntimeException)` 자체는 최후 방어선이라 넓은 채로 뒀다 —
여기서 더 좁히면 `confirmWithRetry` 가 재던진 어떤 예외를 취급 못 하고 새어나갈 위험이 생긴다.

### 이번 변경이 얹히는 기존 설계

재시도 중에도 heartbeat 는 살아 있다. `stopHeartbeat` 는 `runGeneration` 의 `finally` 에 있어
`confirmWithRetry` 가 몇 번을 돌든 끝나야 불린다. 재시도 최대 대기 시간(200ms × 최대 2회 =
400ms)은 heartbeat timeout 보다 훨씬 짧아 재시도 중에 heartbeat 가 끊길 걱정은 없다.

3회를 다 소진해도(또는 이번처럼 즉시 포기해도) `markFailed` 는 부르지 않는다.
DB 접근이 흔들리는 상황에서 그 결과를 DB 에 다시 쓰겠다는 것 자체가 모순이라, job 은
PROCESSING 상태로 남겨 두고 `DeadJobSchedulerTask.markStalledJobsAsFailed` 의 정체 회수에
맡긴다. 이건 기존 설계지 이번에 바꾼 게 아니지만, 재시도 대상을 좁히는 이유를 이해하려면
같이 봐야 해서 맥락으로 남겨 둔다.

고정 200ms 백오프는 그대로 뒀다. 지수 백오프 같은 걸 넣을까 고민했지만, 어차피 최대 3회
한정이라 실질적인 부하 영향이 없다. 백오프 전략을 바꿀 값어치가 없는 곳이다.

### 테스트

기존 테스트 2건이 `IllegalStateException("database unavailable")` 로 재시도를 검증하고
있었는데, 이제 이 타입은 재시도 대상이 아니라서 그대로 두면 깨진다.
`결과 반영이 실패해도 재시도가 성공하면 결과를 살린다` 는
`CannotCreateTransactionException("connection pool exhausted")` 로 바꿔 커넥션 풀 고갈
시나리오를 그대로 표현했고, `결과 반영 재시도를 모두 소진하면 FAILED로 바꾸지 않고
PROCESSING을 유지한다` 는 `QueryTimeoutException("lock wait timeout")`
(`TransientDataAccessException` 의 구체 하위 타입)으로 바꿨다.

새로 `결과 반영 실패가 재시도 대상이 아니면 즉시 포기하고 PROCESSING을 유지한다` 를
추가해 `DataIntegrityViolationException` 을 던지면 `confirm` 이 정확히 1회만 불리고,
`markFailed` 는 불리지 않으며, `heartbeatRegistry.stopHeartbeat` 는 정상적으로 불리는지
확인했다.

---

## L. 예외 처리가 본 흐름을 덮던 것을 걷어냈다 (2026-08-21)

`runGeneration` 안에 try가 3겹으로 중첩돼 있었다. "heartbeat 켜고 → 생성하고 →
확정하고 → heartbeat 끈다"는 본 흐름이 눈에 보이질 않았다. 문제는 예외 처리량
자체가 아니었다 — try/catch 다섯 개가 파일 전체에 퍼져 있는 건 이 작업 이후에도
크게 다르지 않다. 문제는 **본 흐름이 예외 처리 안에 파묻혀 있었다**는 것이다.
`runGeneration` 을 읽으려면 바깥 try, 그 안의 생성 try, 그 안의 confirm try를
동시에 눈에 담아야 본문이 뭘 하는지 알 수 있었다.

해법의 핵심은 `confirmWithRetry` 가 예외를 던지지 않고 최종 실패까지 스스로
처리하게 바꾼 것이다. 지금까지는 재시도를 다 써도 마지막 예외를 던졌고,
`runGeneration` 이 그걸 받아 `log.error` 를 남기는 구조였다. 그런데 최종 실패를
어떻게 로그로 남길지 판단하는 데 필요한 정보 — 몇 번째 시도였는지, 재시도
가능한 예외였는지 — 는 전부 `confirmWithRetry` 안에 이미 있다. 그 정보를
예외 하나에 실어 호출부로 올려보낸 뒤 호출부가 다시 로그를 남기는 건 우회였다.
**실패를 처리할 정보를 다 가진 쪽이 처리하면 호출부가 깨끗해진다.** `confirmWithRetry`
가 최종 실패 시점에 바로 `log.error` 를 남기고 조용히 반환하니, `runGeneration`
쪽의 try/catch가 통째로 사라졌다. 재시도 가능 여부(`isRetryable`)와 재시도
횟수(3회)·간격(200ms)은 전혀 손대지 않았다 — 이번 리팩터링은 그 판정 로직을
감싸는 껍데기 구조만 바꿨다.

생성 단계도 같은 원리로 `generateOrMarkFailed(job): String?` 로 뽑았다. 기존
두 catch 절 — `StubGenerationException`(로그 없이 조용히) 과 그 밖의
`RuntimeException`(`log.error` 후) — 은 하나로 합치지 않고 그대로 옮겼다.
예상된 실패와 이상 신호를 타입으로 구분하는 게 이 코드의 의도이기 때문이다.
`markFailed` 를 부르고 `null` 을 돌리는 이 함수의 계약은 "생성에 성공하면
resultUrl, 실패하면 FAILED로 기록하고 null" 이라는 KDoc 한 줄로 밝혔다. 그
계약 덕분에 `runGeneration` 본문에서는 `generateOrMarkFailed(job) ?: return`
한 줄로 "실패했고 기록도 끝났다"는 상태 전체가 사라진다.

이 구조 변경으로 `confirmWithRetry` 의 `var lastFailure: RuntimeException?` 와
루프 끝의 `throw lastFailure ?: IllegalStateException(...)` 이 통째로 없어졌다.
F-5에서 "elvis 오른쪽은 실제로 도달 불가한데 `lastFailure` 가 nullable이라
컴파일러가 요구해서 남겨 둔다"고 적어 둔 바로 그 코드다. F-5의 그 판단은
당시로선 맞았다 — 예외를 던지는 구조를 유지하는 한 nullable 변수와 도달 불가한
분기는 불가피했다. 하지만 이번에 함수가 예외를 던지지 않는 구조로 바뀌면서
`lastFailure` 를 들고 있을 이유 자체가 사라졌고, 그 자리에 남아 있던 어색한
elvis 분기도 문제째로 없어졌다. F-5 항목은 지우지 않고 그대로 둔다 — 그 시점
판단의 기록으로 남긴다.

최종 실패 로그는 한 줄로 합치면서 사유를 파라미터로 넘기게 했다. K에서 재시도
대상이 아닌 예외에 `log.error("...재시도 대상이 아니어서 즉시 포기...")` 를 따로
남긴 이유는 "로그만 보고 왜 3번 안 돌았는지 헤매지 않게" 하려던 것이었는데,
이번에 종료 판정이 `!retryable || attempt == CONFIRM_MAX_ATTEMPTS` 한 자리로
합쳐지면서 그 구분이 로그에서 사라질 뻔했다. `시도=1/3` 만 봐도 일찍 멈춘 건
알 수 있지만 왜 멈췄는지는 알 수 없다. 그래서 `실패(재시도 소진)` /
`실패(재시도 대상 아닌 예외)` 로 사유를 찍는다. K의 의도는 그대로 살리고
로그 줄 수만 둘에서 하나로 줄인 셈이다.

동작은 하나도 바뀌지 않았다. 재시도 최대 3회, 간격 200ms, 재시도 대상 예외
판정, 최종 실패해도 `markFailed` 를 부르지 않고 job을 PROCESSING 으로 남겨
정체 회수에 맡기는 것, `heartbeatRegistry.stopHeartbeat` 가 반드시 불리는 것,
실패 시 예외 객체를 `log.error` 마지막 인자로 넘겨 스택트레이스를 보존하는
것까지 전부 그대로다. 그 증거로 `GenerationJobProcessorTest.kt` 를 한 줄도
고치지 않고 기존 6건이 그대로 통과한다.

---

## M. confirmWithRetry 의 제어 흐름과 로그를 갈라냈다 (2026-08-21)

L에서 `runGeneration` 의 본 흐름은 드러냈지만 `confirmWithRetry` 자체는 손대지
않고 그대로 두었다. 남은 문제가 그 안에 있었다. `for` → `try` → `catch` → `if`
→ 로그 인자까지 들여쓰기가 5겹이었고, 종료 지점이 3개인데 각각 4~6줄짜리 로그
블록을 달고 있어서 26줄 중 13줄이 로그였다. 제어 흐름과 로그가 뒤엉켜 "언제
멈추는가"라는 규칙 자체가 코드에서 안 읽혔다.

해법은 두 가지였다. 첫째, **예외를 경계에서 값으로 바꿨다.** `confirmOnce` 가
try/catch를 통째로 삼키고 "성공이면 null, 실패면 그 예외"를 돌려주게 하니
재시도 루프에서 try/catch가 사라지고 평범한 제어 흐름만 남았다. 예외를 제어
흐름으로 쓰던 자리를 nullable 반환값으로 옮긴 것이다. 둘째, 종료 사유를
문자열 인자로 받는 `giveUp(job, attempt, reason, failure)` 하나로 3개 종료
지점의 로그를 모았다. 지점마다 자기 로그 블록을 들고 있을 이유가 없었다.

루프 본문은 이렇게 됐다.

```kotlin
for (attempt in 1..CONFIRM_MAX_ATTEMPTS) {
    val failure = confirmOnce(job, resultUrl) ?: return
    if (!isRetryable(failure)) {
        return giveUp(job, attempt, "재시도 대상 아닌 예외", failure)
    }
    if (attempt == CONFIRM_MAX_ATTEMPTS) {
        return giveUp(job, attempt, "재시도 소진", failure)
    }
    logRetrying(job, attempt, failure)
    if (!awaitBeforeRetry()) {
        return giveUp(job, attempt, "재시도 대기 중 인터럽트", failure)
    }
}
```

세 종료 지점을 처음에는 `if (조건) return giveUp(...)` 한 줄씩으로 썼다가
중괄호를 넣어 풀었다. 한 줄로 붙이면 들여쓰기 깊이는 2겹으로 유지되지만
조건·`return`·호출·인자 네 가지가 한 줄에 몰려 줄 자체가 빽빽해진다.
depth를 줄이자고 시작한 일인데 줄 안에서 같은 밀도 문제를 다시 만드는 셈이다.
깊이 한 겹을 내주고 각 줄을 단순하게 두는 쪽이 낫다 — 여기서 줄이려던 건
들여쓰기 숫자 자체가 아니라 한 번에 눈에 담아야 하는 양이었다.

L 시점에 `!retryable || attempt == CONFIRM_MAX_ATTEMPTS` 로 합쳐 뒀던 복합
조건은 여기서 다시 두 줄로 쪼갰다. 되돌린 게 아니라 의도적인 재설계다 —
조건마다 종료 사유가 다르므로 각 줄이 자기 사유를 직접 들고 있는 편이 읽힌다.
"재시도 대상이 아니라서 멈췄다"와 "횟수를 다 써서 멈췄다"를 한 줄의 불리언
연산 뒤에 감추는 대신, 줄 수를 하나 늘리는 대가로 그 둘을 눈에 보이는 별개의
문장으로 만들었다. 줄 수는 늘었지만 읽는 비용은 줄었다. `return giveUp(...)`
는 Unit 반환 함수를 `return` 에 실어 종료와 사유 기록을 한 문장으로 묶는
코틀린 관용구라 `giveUp(...); return` 두 줄로 풀지 않았다.

인터럽트로 멈추는 세 번째 종료 지점도 이제 `failure`(직전 confirm 시도의
실패 예외)를 로그에 함께 넘긴다. 기존에는 인터럽트 자체에는 예외가 없으니
메시지만 남기고 스택트레이스가 없었는데, 세 종료 지점이 같은 `giveUp` 을
쓰면서 자연히 직전 실패의 스택트레이스가 항상 붙게 됐다. 정보가 느는 방향의
변화라 그대로 받아들였다.

결과로 `confirmWithRetry` 의 최대 들여쓰기는 5겹에서 3겹(`for` → `if` → 문장)
으로 줄었고, 26줄이던 본문이 반으로 줄었다. 파일 안에 try는 여전히 3개 있지만
(`confirmOnce`, `generateOrMarkFailed`, `awaitBeforeRetry`) 셋 다 5줄 안팎의
독립된 함수 안에 하나씩만 있어 서로 중첩되지 않는다. 재시도 횟수·간격
·`isRetryable` 판정은 전혀 손대지 않았고, 최종 실패해도 `markFailed` 를 부르지
않고 job을 PROCESSING 으로 남겨 정체 회수에 맡기는 동작도 그대로다. 그 증거로
`GenerationJobProcessorTest.kt` 를 한 줄도 고치지 않고 기존 6건이 그대로
통과한다.

---

## N. scheduler 봉지를 해체했다 (2026-08-24)

"코드가 전체적으로 복잡해졌다"는 진단에서 출발해 **패키지 배치**부터 손봤다.
로직은 건드리지 않았다.

### N-0. 먼저: 워킹 트리에 회귀 2건이 잠들어 있었다 ⚠️

재배치를 시작하기 전에 커밋되지 않은 변경을 확인했더니, "미사용 코드 정리"로
보이는 두 변경이 실은 하중을 받는 코드를 걷어낸 것이었다.

**(1) `DeadJobSchedulerTask` 반복문 안의 `try/catch` 제거**

세 반복문에서 항목 단위 `catch (e: RuntimeException)` 이 사라져 있었다.
`scan()` 의 단계별 catch 는 남아 있었지만 그건 층위가 다르다 —
**job 한 건이 터지면 같은 주기의 나머지 job 전체가 처리되지 않는다.**

이 회귀는 조용하지 않았다. 이미 그 동작을 못 박은 테스트 2개가 깨져 있었다:

- `한 job의 환불 실패가 같은 주기의 나머지 job을 막지 않는다`
- `heartbeat 만료 회수 실패가 나머지 만료 job을 막지 않는다`

**(2) `HeartbeatRegistry` 의 `@Autowired` 보조 생성자 제거** — 이쪽이 위험했다

```kotlin
@Component
class HeartbeatRegistry internal constructor(
    private val redisTemplate: StringRedisTemplate,
    private val appProperties: AppProperties,
    workerProperties: WorkerProperties,
    private val clock: Clock              // ← 테스트가 MutableClock 을 밀어넣는 자리
) {
    @Autowired                            // ← 이게 지워져 있었다
    constructor(
        redisTemplate: StringRedisTemplate,
        appProperties: AppProperties,
        workerProperties: WorkerProperties
    ) : this(redisTemplate, appProperties, workerProperties, Clock.systemUTC())
```

호출부가 없으니 미사용으로 보인다. 그런데 이건 **Spring 이 쓰는 진입점**이다.
지우면 Spring 은 남은 주 생성자를 골라 `Clock` 빈을 찾다가 실패한다:

```
No qualifying bean of type 'java.time.Clock' available
  → heartbeatRegistry 생성 실패
  → generationJobProcessor 생성 실패
  → ApplicationContext 전체 실패
```

`@SpringBootTest` 16개가 통째로 죽었다. **컴파일은 통과한다** — 컨텍스트를 띄워야
드러나는 종류의 회귀다. 정적 분석과 컴파일러가 둘 다 놓치는 자리라는 걸 남겨둔다.

→ 둘 다 되돌렸다(`9d5ec6d`). 단 (1)은 `try/catch` 를 반복문 안에 그대로 넣지 않고
반복문 본문을 항목 1건짜리 함수(`recoverExpired`, `recoverStalled`, `retryOrRefund`)로
빼낸 뒤 그 안에 뒀다. 격리는 되살리고 중첩은 얕게 남긴다.

### N-1. `scheduler` 는 역할이 아니라 실행 방식으로 묶인 봉지였다

| 파일 | 실제 관심사 |
|---|---|
| `HeartbeatRegistry`, `JobAttempt`, `RedisOutageGate` | 스케줄러가 아니다. Redis 기반 생존신호 **인프라** |
| `DeadJobSchedulerTask` | job 회수·재시도 |
| `IdempotencyKeyCleanupTask` | 멱등키(job) 정리 |
| `LedgerReconciliationTask` | 원장 대사 |

공통점은 "스케줄로 돈다"뿐이다. 그건 실행 방식이지 역할이 아니다.
`HeartbeatRegistry` 는 아예 스케줄로 돌지도 않는다 — 워커가 직접 부른다.

**대가는 패키지 순환이었다:**

```
job.worker.GenerationJobProcessor  →  scheduler.HeartbeatRegistry
scheduler.DeadJobSchedulerTask     →  job.service.JobLifecycleService
                                      job.repository.JobRepository
```

job 과 scheduler 가 서로를 문다.

### N-2. 재배치

```
heartbeat/            ← HeartbeatRegistry, JobAttempt, RedisOutageGate
job/scheduling/       ← DeadJobRecoveryTask, IdempotencyKeyCleanupTask
ledger/scheduling/    ← LedgerReconciliationTask
scheduler/            삭제
```

`heartbeat` 는 어느 도메인도 모르는 인프라라 최상위에 독립시켰다.
배치 작업은 각자 자기 도메인 밑으로 내려보냈다.

**결과: 의존이 한 방향이 됐다.** `heartbeat` 는 아무도 참조하지 않고,
`job` 과 `ledger` 가 각각 `heartbeat` 를 참조한다.

테스트 헬퍼 `MutableClock` 은 `HeartbeatRegistryTest`·`RedisOutageGateTest`
둘만 쓰므로 `heartbeat` 로 같이 갔다. Kotlin `internal` 은 모듈 단위라
패키지가 갈려도 가시성 수정자를 손댈 일이 없었다.

### N-3. `DeadJobSchedulerTask` → `DeadJobRecoveryTask`

`job.scheduling` 에 놓이면 이름의 "Scheduler" 가 패키지와 겹쳐 아무것도
말해주지 않는다. 이 클래스가 실제로 하는 일은 죽은 job 회수다.

### N-4. 함정: JPQL 문자열에 박힌 FQ 패키지명

리포지토리 쿼리에 FQ 패키지명이 **문자열로** 들어 있다. 컴파일러가 안 잡아준다.

```kotlin
SET j.status = com.example.credit_system_kotlin.job.domain.JobStatus.PROCESSING   // JobRepository, 6곳
SELECT new com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck(        // LedgerRepository, 1곳
```

이번엔 `job.domain` 과 `ledger.dto` 가 이동 대상이 아니라 무사했다.
**앞으로 이 두 패키지를 옮기면 런타임에야 터진다.** `.editorconfig` 의
`ktlint_standard_package-name = disabled` 주석에도 같은 경고가 있다.

### N-5. 남겨둔 것 — 이번 범위 밖

패키지 배치와 별개인 **계층·중복** 문제라 이번에 손대지 않았다.

1. **컨트롤러 3개가 전부 리포지토리를 직접 찌른다.**
   `JobApiController → JobRepository`, `OrganizationApiController → OrganizationRepository`,
   `LedgerApiController → LedgerRepository`. 쓰기 경로에는 서비스가 있는데 조회 경로에만 없다.
2. **`global` 이 잡동사니 서랍이다.** 세 도메인의 예외와 모든 설정이 한자리에.
   `StubGenerationException` 은 `job.stub` 에서 나 `job.worker` 에서 잡혀 죽는
   내부 예외인데 전역에 앉아 있다.
3. **`idemKey` 검증이 `HoldService`·`ChargeService` 에 복붙돼 있다.**
   공백 검사와 100자 상한이 두 벌이다.

### N-6. 검증

```
./gradlew cleanTest test ktlintCheck detekt
→ 133 tests, 실패 0, 에러 0
grep -rn "credit_system_kotlin.scheduler" src/   → 0건
```

이동 커밋의 diff 는 `package`·`import`·클래스명 세 종류뿐이다(23삽입/19삭제).
`git mv` 로 옮겨 히스토리도 이어진다.

---

## 부록: 기타 수정한 것

- `application.yml` 의 `logging.level.com.example.credit_system` → `..._kotlin`.
  로거 이름은 점 단위 계층이라 `credit_system_kotlin` 은 `credit_system` 의 하위가 아니어서
  기존 설정은 **아무 로거에도 적용되지 않는 죽은 설정**이었다.
- `Organization` 엔티티에 `@Table(name = "organizations")` 누락 → 테이블명이 `organization` 으로
  생성되던 것을 수정.
- `BaseEntity` 필드가 `private` 이라 `createdAt`/`updatedAt` 을 읽을 수 없던 것을 수정.
- `WorkerProperties` 의 `require` 조건이 뒤집혀 **정상 설정에서 기동 실패**하던 버그 수정.
- **`spring-boot-restclient` 의존성 누락** (2026-08-20). Java 쪽 `build.gradle` 에는 있는데
  이식 때 빠졌다. `TestRestTemplate` 이 `RestTemplateBuilder` 를 찾지 못해
  `@SpringBootTest(RANDOM_PORT)` 컨텍스트가 통째로 뜨지 않았다. 컨트롤러 테스트 3개를
  이식하기 전까지는 아무도 이 경로를 쓰지 않아 드러나지 않았다.

---

## 부록 4: 벤치마크 이식에서 배운 것 (코드는 삭제됨)

벤치마크 코드 자체는 지웠지만, 이식하며 밟은 함정 중 **Kotlin 일반에 해당하는 것**만 남긴다.

**`queryForObject` 에는 `Long::class.javaObjectType` 을 써야 한다.**
Kotlin에서 `Long::class.java` 는 **primitive `long.class`** 라
Spring JDBC가 기대하는 박싱 타입이 아니다. 지금 이 저장소에 `JdbcTemplate` 사용처는
없지만, 나중에 쓰게 되면 반드시 걸리는 자리다.

**`jdbcTemplate.batchUpdate` 는 `List<Array<Any>>` (원소 non-null)를 요구한다.**
Java처럼 `null` 을 섞으려면 언체크 캐스트가 필요하다.
nullable 컬럼이면 INSERT 목록에서 빼는 편이 깨끗하다.

나머지(전략 인터페이스를 `val name` 으로 바꾼 것, 3갈래 결과를 `Boolean?` 대신
enum으로 바꾼 것)는 삭제된 코드에만 해당하므로 함께 지웠다.
필요하면 커밋 `10932b4` 에 그대로 있다.

---

## 부록 3: 동시성 테스트에서 Java와 다르게 한 것

**1. `SharedContainers` 를 abstract class 상속 → `object` 로 바꿨다.**
Java는 static 필드/초기화 블록을 하위 클래스가 상속해 공유했는데,
Kotlin은 companion object 멤버가 그런 식으로 상속되지 않는다.
`object SharedContainers` 하나를 두고 각 테스트가
`SharedContainers.registerDatabase(registry, "db이름")` 을 직접 부른다.
컨테이너는 이 object에 처음 접근할 때 뜨므로 지연 초기화 시점은 사실상 같다.

`@DynamicPropertySource` 는 static 메서드여야 하므로
`companion object` + `@JvmStatic` 이 필요하다.

**2. 컨테이너 타입에 self-type 제네릭 문제가 있다.**

```kotlin
// GenericContainer<SELF extends GenericContainer<SELF>> 를 Kotlin에서 그대로 쓰면
// 빌더 메서드 반환 타입이 Nothing 이 되어 이후 코드가 도달 불가로 취급된다.
private class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image)
```

SELF를 묶어 줄 구체 하위 클래스를 하나 두는 것이 정석이다.
MySQL 쪽은 testcontainers 2.x 에 제네릭이 없는
`org.testcontainers.mysql.MySQLContainer` 가 새로 생겨서 그걸 썼다.
Java가 쓰던 `org.testcontainers.containers.MySQLContainer<?>` 도 아직 남아 있지만
Kotlin에서는 새 쪽이 훨씬 깔끔하다.

**3. 래치 보일러플레이트는 일부러 합치지 않았다.**
세 테스트가 `ready/start/done` 3단 래치를 거의 같은 모양으로 반복한다.
헬퍼로 묶을 수 있지만, **그 래치 자체가 이 테스트들이 검증하려는 대상**이라
눈에 보이게 두는 편이 낫고 Java 원본과 1:1로 대조하기도 쉽다.

---

## 부록 2: 테스트를 쓸 때 걸린 함정

**준비용 엔티티를 프로퍼티 초기화 자리에서 save하면 안 된다.**

```kotlin
// 이렇게 하면 롤백되지 않는다
class SomeTest @Autowired constructor(private val repo: OrganizationRepository) {
    private val organization = repo.save(Organization("acme", 1000L))   // ✗
}
```

Spring의 테스트 트랜잭션은 인스턴스 생성이 아니라 `@BeforeEach` 직전에 열린다.
프로퍼티 초기화는 그보다 먼저 실행되므로 `save` 가 트랜잭션 **밖에서** 커밋되고,
`@DataJpaTest` 의 롤백이 이 행을 되돌리지 못한다.

Java는 `@BeforeEach` 로 쓸 수밖에 없어서 이 문제가 없었는데, Kotlin에서 생성자 주입 +
프로퍼티 초기화로 줄이려다 실제로 `HoldServiceTest` 가 organization 8건을 흘렸다.
같은 컨텍스트를 공유하는 `LedgerReconciliationTaskTest` 가 **전체 조직을 대사**하므로
그대로 뒀으면 거기서 터졌을 것이다.

→ 엔티티 준비는 `lateinit var` + `@BeforeEach`. DB를 건드리지 않는 협력 객체
(`HoldService(...)`, `ListAppender()`) 는 프로퍼티 초기화 자리에 둬도 된다.
