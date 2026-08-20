# credit_system → Kotlin 이식 결정 사항 리포트

작성일: 2026-08-20
대상: `credit_system` (Java) → `credit-system-kotlin` (Kotlin)

현재 상태: **이식 완료.** `src/main` 과 테스트 4개 계층을 모두 옮겼다.
`test` 태스크 28개 클래스 / 137 테스트 통과 + `benchmark` 태스크 2개 실행 확인.

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
의존성 한 줄보다 비싸다. 이식 이후 `DeadJobSchedulerTaskTest`(11개),
`WorkerBatchSizeBenchmark` 도 전부 이 라이브러리를 쓰고 있어 실사용 근거도 늘었다.

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
| 순수 단위 | 9 | ✅ 56 테스트 통과 |
| Spring/JPA 통합 | 13 | ✅ 72 테스트 통과 (+ 컨트롤러 1개 신규 = 73) |
| 동시성(Testcontainers) | 6 | ✅ 5 테스트 통과 (실제 MySQL 8.4 / Redis 7) |
| 벤치마크 | 10 | ✅ 이식 완료. `benchmark` 태스크로 실측 확인 |

`test` 태스크 합계 28개 클래스 / 137 테스트 통과. **남은 이식 대상 없음.**

### 벤치마크 실행 방법

```bash
./gradlew benchmark                       # 기본값
./gradlew benchmark -Dbench.requests=300 -Dbench.concurrency=1,8 \
                    -Dbench.window-seconds=2 -Dbench.durations=200 -Dbench.batch-sizes=1,4
```

`build.gradle.kts` 의 `benchmark` 태스크가 `bench.` 로 시작하는 시스템 프로퍼티를
테스트 JVM으로 넘겨준다. Docker 데몬이 필요하다.

`@Tag("benchmark")` 가 붙은 `BalanceStrategyBenchmark` / `WorkerBatchSizeBenchmark` 만
이 태스크에서 돌고 `test` 에서는 제외된다. **`BenchmarkHarnessTest` 는 태그가 없어**
평소 `test` 에 포함된다 — 하네스 자체를 in-memory 가짜 전략으로 검증하는 순수 단위 테스트라
Java 원본에서도 그렇게 되어 있다.

### 동시성 계층 실행 조건

**Docker 데몬이 떠 있어야 한다.** 꺼져 있으면 이 5개는 컨테이너를 못 띄우고 실패한다.
컨테이너는 `withReuse(true)` 라 한 번 뜨면 테스트 실행 사이에 살아남는다.

테스트마다 별도 데이터베이스를 쓴다 (`concurrent_charge`, `concurrent_hold`,
`duplicate_idem_key`, `pipeline_e2e`, `retry_refund`). 서로 격리되므로
Java 원본처럼 `@AfterEach` 정리가 없어도 간섭하지 않는다.

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

## 부록 4: 벤치마크에서 Java와 다르게 한 것

**1. `OptimisticLockStrategy` 의 3갈래 결과를 enum으로 바꿨다.**
Java는 한 번의 시도 결과를 `Boolean` 의 `true`(성공) / `false`(버전 충돌, 재시도) /
`null`(잔액 부족, 즉시 포기)로 구분했다. Kotlin에서 `Boolean?` 를 트랜잭션 콜백 밖으로
흘리면 세 갈래가 전혀 읽히지 않아 `AttemptResult { SUCCESS, CONFLICT, INSUFFICIENT }` 로
바꿨다. **분기 조건과 재시도 횟수 계산은 원본과 동일하다.**

**2. `seedBacklog` 의 INSERT에서 `result_url` 컬럼을 뺐다.**
Java는 그 자리에 `null` 을 넘겼는데, Kotlin에서 `jdbcTemplate.batchUpdate` 는
`List<Array<Any>>` (원소 non-null)를 요구한다. `result_url` 은 nullable 컬럼이라
INSERT 목록에서 빼면 그대로 NULL 이 들어가므로 결과가 같고, 언체크 캐스트도 피할 수 있다.

**3. `queryForObject` 에 `Long::class.javaObjectType` 를 쓴다.**
Kotlin에서 `Long::class.java` 는 **primitive `long.class`** 로, Spring JDBC가 기대하는
박싱 타입이 아니다. 이건 벤치마크만의 문제가 아니라 Kotlin + Spring JDBC 전반의 함정이다.

**4. `DeductStrategy.name()` 을 `val name` 으로 바꿨다.** Kotlin 프로퍼티 관례를 따랐다.

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
