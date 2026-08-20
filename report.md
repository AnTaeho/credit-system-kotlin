# credit_system → Kotlin 이식 결정 사항 리포트

작성일: 2026-08-20
대상: `credit_system` (Java) → `credit-system-kotlin` (Kotlin)

현재 상태: **`src/main` 이식 완료**, 테스트는 순수 단위 계층(8개 파일 / 56 테스트)까지 완료.

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

## B. 아직 결정하지 않은 것 (당신의 판단 필요)

### B-1. 엔티티 `id: Long?` 이 코드 전반에 번지는 문제 ★ 가장 큰 건

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

---

### B-2. 응답 DTO의 nullable `id` — JSON 계약 문제

```kotlin
data class LedgerResponse(val id: Long?, ...)
data class JobResponse(val id: Long?, ...)
```

Java도 `Long id` 라 동작은 동일하지만, **DB에서 읽어온 엔티티는 id가 반드시 있다.**
API 스펙상 `"id": null` 이 나올 수 있는 것처럼 보이는 게 정확하지 않다.
B-1을 (b)나 (c)로 정하면 여기도 함께 닫을 수 있다.

---

### B-3. 엔티티 프로퍼티 스타일 — 캡슐화 vs 간결함

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
잔액을 다루는 도메인이라 현재 방식(캡슐화 유지)을 권하지만, 장황함이 거슬리면 바꿀 수 있다.

> 참고: `allOpen` 플러그인이 `@Entity` 를 open으로 만들기 때문에 `private set` 은 **컴파일 에러**다
> (`Private setters for open properties are prohibited`). `protected set` 만 가능.

---

### B-4. `HeartbeatRegistry` 의 생성자 2개

테스트용 `Clock` 주입을 위해 Java의 구조(주 생성자 + `@Autowired` 보조 생성자)를 그대로 유지했다.

```kotlin
class HeartbeatRegistry internal constructor(..., private val clock: Clock) {
    @Autowired
    constructor(...) : this(..., Clock.systemUTC())
```

Kotlin다운 방식은 기본값 파라미터(`clock: Clock = Clock.systemUTC()`)지만,
그러면 Spring이 생성자를 하나만 보고 `Clock` 빈을 찾다가 실패할 수 있어 검증 없이는 바꾸지 않았다.
**정리하고 싶다면 실제 기동 테스트가 필요하다.**

---

### B-5. 새로 추가한 의존성 2개 — Java에 없던 것

```kotlin
testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")   // 필수에 가까움
```

**왜 필수인가**: Mockito의 `eq()`/`any()` 는 null을 반환하는데, Kotlin은 반환 타입을 non-null로
추론해 null 검사를 삽입하므로 `NullPointerException: eq(...) must not be null` 로 죽는다.
실제로 `GenerationWorkerUnitTest` 7개가 전부 이 에러로 실패했다.

부수 효과로 `` `when` `` 백틱이 `whenever` 로 바뀌어 가독성도 좋아진다.
**의존성을 늘리기 싫다면** 직접 래퍼를 만들 수 있지만 권하지 않는다.

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

### 확인되지 **않은** 것 ⚠️

- **heartbeat 만료 회수 경로** (`markExpiredJobsAsFailed`, `markStalledJobsAsFailed`).
  워커가 처리 도중 죽는 상황이라 재현하지 않았다. 코드는 옮겼지만 실행 검증이 없다.
  → `DeadJobSchedulerTaskTest` 이식 시 커버될 예정.

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
| 순수 단위 | 8 | ✅ 56 테스트 통과 |
| Spring/JPA 통합 | 15 | 다음 차례 |
| 동시성(Testcontainers) | 6 | 대기 |
| 벤치마크 | 10 | **맨 마지막으로 미루기로 합의** |

---

## 부록: 기타 수정한 것

- `application.yml` 의 `logging.level.com.example.credit_system` → `..._kotlin`.
  로거 이름은 점 단위 계층이라 `credit_system_kotlin` 은 `credit_system` 의 하위가 아니어서
  기존 설정은 **아무 로거에도 적용되지 않는 죽은 설정**이었다.
- `Organization` 엔티티에 `@Table(name = "organizations")` 누락 → 테이블명이 `organization` 으로
  생성되던 것을 수정.
- `BaseEntity` 필드가 `private` 이라 `createdAt`/`updatedAt` 을 읽을 수 없던 것을 수정.
- `WorkerProperties` 의 `require` 조건이 뒤집혀 **정상 설정에서 기동 실패**하던 버그 수정.
