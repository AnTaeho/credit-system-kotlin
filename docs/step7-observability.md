# step7-observability — 도메인 지표 관측

서버 지표(CPU, RPS, 에러율, 응답 시간)는 "서버가 살아있다"만 말해준다. 이 도메인의 진짜 사고는 서버가 CPU 40%, 에러율 0%로 완벽히 건강한 채로 난다 — 잔액이 음수가 되거나, 같은 요청이 이중으로 차감되거나, hold 로 묶인 돈이 확정도 환불도 되지 않은 채 유실되는 식이다. 인프라 대시보드는 이 중 어느 것도 보여주지 않는다.

step2~step6 은 이런 사고를 막는 장치를 이미 다 만들어 두었다. 조건부 잔액 차감, 멱등키, attemptNo CAS, heartbeat 회수, 원장 대사. 문제는 이 장치들이 **말이 없다는 것**이다. 잘 막고 있는지, 얼마나 자주 막고 있는지, 아니면 아예 안 돌고 있는지 밖에서는 알 수 없다. step7 은 코드를 새로 짜는 단계가 아니라, 이미 코드 안에 있는 사실들을 밖에서 볼 수 있는 자리로 끌어내는 단계다.

## 무엇을 관측할 것인가 — 네 계층

지표를 아무거나 다 붙이면 대시보드는 늘고 판단은 흐려진다. 이 시스템에서 볼 가치가 있는 것을 네 계층으로 나눠 두고, 각 단계가 어느 계층을 덮는지 명시한다.

| 계층 | 무엇 | 질문 | 예 |
|---|---|---|---|
| **L0 돈** | 잔액과 hold 의 절대 상태 | "지금 묶인 돈이 얼마이고, 얼마나 오래 묶여 있나" | 미결 hold 건수·최고 나이 |
| **L1 불변식** | 깨지면 안 되는 등식 | "잔액 = 최초 잔액 + 원장 합계 가 성립하나" | 원장 대사 불일치 수 |
| **L2 흐름** | 파이프라인의 처리량과 적체 | "job 이 흘러가고 있나, 어디서 막혔나" | 상태별 job 수, 대기 시간 |
| **L3 방어 발동** | 방어 장치가 실제로 몇 번 막았나 | "잔액 부족으로 몇 건이 거절됐고, 낡은 세대의 쓰기가 몇 번 무효화됐나" | 방어 카운터 |

L3 이 특히 서버 지표로 대체 불가능하다. attemptNo 가 낡은 세대의 confirm 을 무효화한 사건은 HTTP 200 이고, 에러 로그도 없고, 지연도 늘지 않는다. `credit_defense_total{point="confirm",outcome="stale"}` 이 오르는 것만이 그 일이 일어났다는 유일한 흔적이다.

## 6단계 로드맵

| 단계 | 내용 | 덮는 계층 | 상태 |
|---|---|---|---|
| 1 | 원장 대사 지표 승격 — 이미 계산 중이던 대사 결과를 Gauge/Counter/Timer 로 노출 | L1 | 완료 |
| 2 | 방어 발동 카운터 — 조건부 UPDATE 의 `updated == 0` 분기를 세는 이벤트로 승격 | L3 | 완료 |
| 3 | 상태 스냅샷 게이지 — 미결 hold 의 건수·금액·나이와 불변식 3종을 주기적으로 찍는다 | L0·L1 | 완료 |
| 4 | 노출 경계와 카디널리티 가드 — 관리 포트 분리, `/actuator/prometheus` 를 누구에게 열지, 레지스트리 수준의 시계열 상한 | — | 예정 |
| 5 | docker-compose 관측 스택 — Prometheus + Grafana 를 띄워 스크레이프·대시보드·알람 규칙(P1/P2)을 코드로 남긴다 | — | 예정 |
| 6 | 장애 주입 — 워커·스케줄러·Redis 를 실제로 죽이고 원장을 손으로 깨서, 어느 지표가 반응하고 어느 지표가 침묵하는지 확인한다 | — | 예정 |

---

## 1단계 — 원장 대사 지표 승격

step6-resilience 가 만든 원장 대사(`LedgerReconciliationTask`)는 이미 "잔액과 원장이 어긋났는가"를 매 주기 계산하고 있었다. 문제는 그 계산 결과가 로그 한 줄로 끝난다는 것이다. 이 단계는 그 계산 결과를 Prometheus 로 긁어갈 수 있는 지표로 끌어올린다.

- 이전 단계: `step6-resilience`
- 이번 단계: `step7-observability` 1단계 (전체 6단계 중 첫 번째. 도메인 서비스 계측·스냅샷 게이지·docker-compose·장애 주입은 이후 단계다)

### 왜 이 단계인가

서버 지표(CPU, RPS, 에러율, 응답 시간)는 "서버가 살아있다"만 말해준다. 이 도메인의 진짜 사고는 서버가 CPU 40%, 에러율 0%로 완벽히 건강한 채로 난다 — 잔액이 음수가 되거나, 같은 요청이 이중으로 차감되거나, hold 로 묶인 돈이 확정도 환불도 되지 않은 채 유실되는 식이다. 이런 사고는 인프라 대시보드에 아무 흔적도 남기지 않는다.

`LedgerReconciliationTask` 는 매 주기 `initialBalance + 원장합 == balance` 를 계산해서 이걸 이미 알고 있었다. 하지만 결과를 `log.error` 로만 뱉었다. 로그는 누군가 grep 하지 않으면 존재하지 않는 것과 같다. 알람도, 대시보드도, 추세도 걸 수 없다. 1단계는 이 계산 결과를 지표로 끌어올려서 "계산은 하고 있었지만 아무도 못 보던 것"을 처음으로 보이게 만든다.

### 설계 원칙: 왜 이벤트로 분리했나

`LedgerReconciliationTask` 에 `MeterRegistry` 를 직접 주입하고 그 자리에서 `registry.gauge(...)` 를 부르는 게 제일 짧은 길이었을 것이다. 그렇게 하지 않았다.

도메인/스케줄러 코드가 Micrometer 를 알게 되면, 관측 방식을 바꾸는 결정(Prometheus 에서 다른 백엔드로, 혹은 지표 이름 체계를 바꾸는 것)이 도메인 코드를 건드리는 결정이 되어 버린다. 반대로 지금처럼 분리해 두면:

- `LedgerReconciliationTask` 는 "대사 한 주기가 이렇게 끝났다(`LedgerReconciliationCompleted`)"는 사실만 말한다. 이 이벤트는 `checkedCount`, `mismatchCount`, `duration`, `completedAt` 네 개의 순수 값만 들고, `io.micrometer` 를 import 하지 않는다.
- 세는 책임은 `observability` 패키지의 `LedgerReconciliationMetrics` 가 전담한다. 여기가 Micrometer 를 아는 유일한 곳이다.

이렇게 나누면 "대사가 어떻게 동작하는가"를 바꾸는 사람과 "대사 결과를 어떻게 관측하는가"를 바꾸는 사람이 서로의 코드를 건드리지 않는다. 검증 방법도 그대로 갈린다 — `LedgerReconciliationTaskTest` 는 이벤트가 올바른 값으로 발행되는지만 보고, `LedgerReconciliationMetricsTest` 는 `MeterRegistry` 만 가지고 순수 단위 테스트로 게이지/카운터/타이머 갱신을 검증한다. 둘 다 Spring 컨텍스트나 스케줄러를 몰라도 된다.

### 파일별 변경 목록

| 파일 | 신규/수정 | 무엇을, 왜 |
|---|---|---|
| `build.gradle.kts` | 수정 | `spring-boot-starter-actuator`, `io.micrometer:micrometer-registry-prometheus` 추가. 버전은 Spring Boot BOM 이 관리한다 |
| `src/main/resources/application.yml` | 수정 | `management.endpoints.web.exposure.include: health,info,prometheus` 로 필요한 엔드포인트만 열고, `management.metrics.tags.application: credit_system` 공통 태그를 붙인다 |
| `ledger/event/LedgerReconciliationCompleted.kt` | 신규 | 대사 한 주기의 결과를 담는 불변 이벤트. `checkedCount`, `mismatchCount`, `duration`, `completedAt` 네 필드뿐이고 Micrometer 를 모른다 |
| `ledger/scheduling/LedgerReconciliationTask.kt` | 수정 | 시작 시각을 재고, 기존 `log.info` 뒤에 `eventPublisher.publishEvent(LedgerReconciliationCompleted(...))` 를 얹는다. 대사 로직·로그는 그대로다 |
| `observability/LedgerReconciliationMetrics.kt` | 신규 | `@EventListener` 로 이벤트를 받아 Gauge/Counter/Timer 5종을 갱신하는 유일한 컴포넌트 |
| `global/config/ClockConfig.kt` | 신규 | `Clock.systemUTC()` 를 빈으로 노출해, 테스트에서 `Clock` 을 갈아끼워 staleness 를 결정적으로 검증할 수 있게 한다 |
| `ledger/scheduling/LedgerReconciliationTaskTest.kt` | 수정 | 생성자 변경(`ApplicationEventPublisher` 추가)에 맞춰 mock 을 넘기도록 고치고, 발행된 이벤트의 `checkedCount`/`mismatchCount` 를 검증하는 테스트를 추가 |
| `observability/LedgerReconciliationMetricsTest.kt` | 신규 | `SimpleMeterRegistry` 로 게이지/카운터/타이머 갱신을 순수 단위 테스트로 검증 |
| `observability/PrometheusEndpointTest.kt` | 신규 | `@SpringBootTest` + MockMvc 로 `/actuator/prometheus` 응답에 지표가 실제로 노출되는지 확인 |
| `docs/step7-observability.md` | 신규 | 이 문서 |

### 지표 표

| Micrometer 이름 | Prometheus 에서 보이는 이름 | 타입 | 의미 | 알람 기준 |
|---|---|---|---|---|
| `credit.ledger.reconciliation.mismatch` | `credit_ledger_reconciliation_mismatch` | Gauge | 마지막 대사 주기의 불일치 조직 수 | **P1, 즉시 호출.** 1건도 허용하지 않는다 — SLO 가 아니라 불변식이 깨졌다는 신호다 |
| `credit.ledger.reconciliation.checked` | `credit_ledger_reconciliation_checked` | Gauge | 마지막 주기에 검사한 조직 수 | 알람 없음. mismatch 를 해석할 분모(추세 확인용) |
| `credit.ledger.reconciliation.cycles` | `credit_ledger_reconciliation_cycles_total` | Counter | 완료한 대사 주기의 누적 수 | 알람 없음. `rate()` 로 대사가 계속 도는지 눈으로 확인하는 용도 |
| `credit.ledger.reconciliation.duration` | `credit_ledger_reconciliation_duration_seconds{_count,_sum}` / `_max` | Timer | 대사 한 주기 소요 시간 | 알람 없음. 조직 수 증가에 따른 대사 시간 추세 관찰용 |
| `credit.ledger.reconciliation.staleness` | `credit_ledger_reconciliation_staleness_seconds` | Gauge (초) | 마지막 성공 대사로부터 흐른 시간 | **P2.** 대사 주기(`app.scheduling.reconciliation-interval-millis`, 기본 60초)의 3배, 즉 180초를 넘으면 대사 자체가 멈춘 것으로 본다 |

Counter 는 Micrometer 가 Prometheus 로 내보낼 때 `_total` 접미사를, Timer 는 `_seconds`(base unit) 에 `_count`/`_sum`/`_max` 세 시계열을 붙인다. 뒤의 "직접 확인하는 방법" 절의 실제 출력에서 이 변형을 그대로 볼 수 있다.

`mismatch` 와 `staleness` 가 서로 다른 방향의 사고를 잡는다는 점이 중요하다. `mismatch` 는 "대사가 돌고 있고, 뭔가 어긋난 걸 발견했다"는 신호다. `staleness` 는 "대사 자체가 멈춰서 어긋난 게 있어도 아무도 모른다"는 정반대의 신호다. 대사 태스크가 죽으면 `mismatch` 는 마지막 값에 얼어붙어 조용히 0 을 보여줄 수 있다 — `staleness` 없이는 이 침묵이 "문제 없음"인지 "관측 자체가 죽음"인지 구분할 수 없다.

### 핵심 코드 읽기

#### `LedgerReconciliationTask` — 이벤트 발행 지점만 얹는다

**step6 (이전)**:

```kotlin
@Scheduled(fixedDelayString = $$"${app.scheduling.reconciliation-interval-millis:60000}")
fun reconcile() {
    var checkedCount = 0
    var mismatchCount = 0
    var lastId = 0L
    do {
        val checks = ledgerRepository.findBalanceChecksAfter(lastId, PageRequest.of(0, RECONCILE_BATCH_SIZE))
        for (balanceCheck in checks) {
            try {
                if (!isBalanceConsistent(balanceCheck)) {
                    mismatchCount++
                }
                checkedCount++
            } catch (e: RuntimeException) {
                log.warn("원장 대사 항목 처리 실패: organizationId={}", balanceCheck.organizationId, e)
            }
            lastId = balanceCheck.organizationId
        }
    } while (checks.size == RECONCILE_BATCH_SIZE)
    log.info("원장 대사 주기 완료: checkedCount={}, mismatchCount={}", checkedCount, mismatchCount)
}
```

**step7 (이번)**:

```kotlin
class LedgerReconciliationTask(
    private val ledgerRepository: LedgerRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Scheduled(fixedDelayString = $$"${app.scheduling.reconciliation-interval-millis:60000}")
    fun reconcile() {
        val startedAt = Instant.now()
        var checkedCount = 0
        var mismatchCount = 0
        var lastId = 0L
        do {
            // ... 대사 루프는 그대로 ...
        } while (checks.size == RECONCILE_BATCH_SIZE)
        log.info("원장 대사 주기 완료: checkedCount={}, mismatchCount={}", checkedCount, mismatchCount)
        val completedAt = Instant.now()
        eventPublisher.publishEvent(
            LedgerReconciliationCompleted(
                checkedCount = checkedCount,
                mismatchCount = mismatchCount,
                duration = Duration.between(startedAt, completedAt),
                completedAt = completedAt
            )
        )
    }
```

대사 로직, 항목 단위 try/catch, ERROR 로그는 한 글자도 바뀌지 않았다. 시작 시각을 재는 한 줄과, 기존 `log.info` 뒤에 이벤트 발행 한 번이 붙었을 뿐이다. `LedgerReconciliationTask` 전체를 봐도 `io.micrometer` import 는 없다.

#### `LedgerReconciliationMetrics` — Micrometer 를 아는 유일한 곳

```kotlin
@Component
class LedgerReconciliationMetrics(
    registry: MeterRegistry,
    private val clock: Clock
) {
    private val mismatchCount = AtomicLong(0)
    private val checkedCount = AtomicLong(0)
    private val lastCompletedAt = AtomicReference<Instant?>(null)

    private val cyclesCounter: Counter = Counter.builder(CYCLES_METRIC)
        .description("완료한 원장 대사 주기의 누적 수")
        .register(registry)

    private val durationTimer: Timer = Timer.builder(DURATION_METRIC)
        .description("원장 대사 한 주기에 걸린 시간")
        .register(registry)

    init {
        Gauge.builder(MISMATCH_METRIC, mismatchCount) { it.get().toDouble() }
            .description("마지막 대사 주기에서 발견된 불일치 조직 수. 0이 아니면 즉시 사고다")
            .register(registry)

        Gauge.builder(CHECKED_METRIC, checkedCount) { it.get().toDouble() }
            .description("마지막 대사 주기에서 검사한 조직 수")
            .register(registry)

        Gauge.builder(STALENESS_METRIC, this) { it.stalenessSeconds() }
            .description("마지막 성공 대사로부터 흐른 시간(초). 대사 자체가 멈춘 것을 탐지한다")
            .baseUnit("seconds")
            .register(registry)
    }

    @EventListener
    fun onReconciliationCompleted(event: LedgerReconciliationCompleted) {
        mismatchCount.set(event.mismatchCount.toLong())
        checkedCount.set(event.checkedCount.toLong())
        cyclesCounter.increment()
        durationTimer.record(event.duration)
        lastCompletedAt.set(event.completedAt)
    }

    private fun stalenessSeconds(): Double {
        val last = lastCompletedAt.get() ?: return -1.0
        return Duration.between(last, clock.instant()).toMillis() / MILLIS_PER_SECOND
    }
}
```

`@EventListener` 메서드 자체는 짧다 — 값을 필드에 그대로 쓰고, 카운터를 올리고, 타이머에 기록할 뿐이다. 지표 등록(`Gauge.builder(...).register(...)`)은 전부 생성자/`init` 블록에 있고, 이벤트가 몇 번 오든 다시 실행되지 않는다.

### Gauge 등록의 함정

Micrometer 의 `Gauge` 는 카운터·타이머와 달리 값을 내부에 들고 있지 않는다. 매 스크레이프마다 등록된 상태 객체를 **약한 참조(`WeakReference`)** 로 들여다보고 값을 계산한다. 강한 참조가 어디에도 안 남아 있으면 GC 가 그 상태 객체를 수거하고, 이후 스크레이프는 `NaN` 을 뱉는다. 실제로 이런 코드를 쓰면 조용히 깨진다:

```kotlin
// 하지 말아야 할 것 — 이벤트가 올 때마다 새로 등록
@EventListener
fun onReconciliationCompleted(event: LedgerReconciliationCompleted) {
    val value = AtomicLong(event.mismatchCount.toLong())  // 지역 변수
    Gauge.builder(MISMATCH_METRIC, value) { it.get().toDouble() }.register(registry)
    // value 를 아무도 강하게 들고 있지 않다 → 이 메서드가 끝나면 GC 대상
}
```

`value` 는 메서드가 끝나는 순간 강한 참조를 잃는다. GC 가 언제 도는지는 보장이 없으므로, 다음 스크레이프까지는 우연히 살아남아 값이 보일 수도 있고, 그 다음엔 `NaN` 이 될 수도 있다 — 재현이 안 되는 버그로 남는다. 게다가 매 이벤트마다 새 게이지를 `register` 하면 같은 이름의 메타(설명, 태그)를 가진 미터가 레지스트리에 계속 새로 등록되려 시도하는 부작용도 있다.

이번 구현은 `mismatchCount`, `checkedCount` 를 **컴포넌트의 필드**로 두고, 그 필드를 상태 객체로 `Gauge.builder(..., mismatchCount) { ... }` 에 넘긴다. `LedgerReconciliationMetrics` 자체가 Spring 싱글턴 빈이라 애플리케이션이 살아있는 한 GC 되지 않고, 그 필드도 함께 살아있다. `staleness` 게이지는 상태 객체로 `this`(컴포넌트 자신)를 넘기는데, 이유는 같다 — 싱글턴 빈이 곧 강한 참조의 근원이다.

**왜 `staleness` 의 초기값이 0 이 아니라 -1 인가.** 0 은 "방금 막 성공적으로 대사를 마쳤다"는 뜻이다. 애플리케이션이 막 떠서 대사가 아직 한 번도 안 돌았는데 값이 0 이면, 알람 규칙 입장에서는 "방금 성공"과 "한 번도 안 돎"을 구분할 방법이 없다. -1 은 존재할 수 없는 음수 경과 시간이라 "아직 기준점이 없다"는 뜻을 명확히 구분해 표현한다. 실제로 아래 "직접 확인하는 방법" 절의 출력에서 대사가 아직 돌지 않은 상태의 값이 `-1.0` 인 것을 볼 수 있다.

### 카디널리티

이번 지표 다섯 개 모두 태그가 없다(공통 태그 `application=credit_system` 제외). 특히 `organizationId` 는 절대 태그로 넣지 않았다.

Prometheus 는 태그(레이블)의 조합마다 별도의 시계열을 만든다. 조직이 수천, 수만 개로 늘어나는 시스템에서 `organizationId` 를 레이블로 붙이면 지표 하나가 조직 수만큼의 시계열로 뻥튀기된다 — 이게 카디널리티 폭발이고, Prometheus 서버의 메모리와 쿼리 성능을 실질적으로 무너뜨릴 수 있는 흔한 사고 원인이다.

이 지표들의 목적은 애초에 "전체 시스템에 지금 사고가 있는가"라는 집계 질문에 답하는 것이다. "어느 조직이 문제인가"는 다른 질문이고, 이미 다른 도구가 답을 갖고 있다 — `LedgerReconciliationTask.isBalanceConsistent` 의 ERROR 로그는 `organizationId`, `balance`, `expected`, `diff` 를 전부 남긴다. 알람이 울리면(mismatch > 0) 그 순간 로그를 검색해서 어느 조직인지 찾는 흐름이다: **집계는 메트릭이, 개별 식별은 로그가 맡는다.**

### 테스트가 보장하는 것

- **`LedgerReconciliationTaskTest`** — 기존 로그 기반 단언(일치 시 무경보, 불일치 시 ERROR 1건, 배치 경계 등)은 전부 그대로 유지된다. 여기에 `일치 2건 불일치 1건이면 이벤트로 checkedCount 3 mismatchCount 1을 발행한다` 테스트를 추가했다. 조직 3개(2개는 잔액·원장 일치, 1개는 원장 없이 잔액만 증가)를 만들고 `task.reconcile()` 을 호출한 뒤, mock 한 `ApplicationEventPublisher` 에 `argumentCaptor` 로 잡힌 이벤트의 `checkedCount == 3`, `mismatchCount == 1` 을 검증한다. 태스크 생성자에 `ApplicationEventPublisher` 가 추가되어 깨졌던 컴파일은 mock 주입으로 고쳤다.

- **`LedgerReconciliationMetricsTest`** — `SimpleMeterRegistry` 와 (기존 API 로는 값을 되돌릴 수 없는 `Clock.fixed` 대신) 직접 시각을 앞으로 당길 수 있는 테스트 전용 `FixedMutableClock`(3단계에서 `DomainSnapshotMetricsTest` 도 쓰게 되어 `src/test/.../support/` 로 옮겼다) 을 써서 Spring 컨텍스트 없이 순수 단위 테스트로 검증한다:
  - 이벤트 하나를 넣으면 `mismatch`/`checked` 게이지가 그 이벤트의 값이 된다.
  - 이벤트를 두 번 넣으면 게이지는 **마지막 이벤트의 값**으로 덮이고, `cycles` 카운터는 정확히 2가 된다.
  - 이벤트가 오기 전 `staleness` 는 `-1.0` 이고, 이벤트가 온 뒤 시계를 30초 앞당기면 `staleness` 가 `30.0` 을 반환한다.
  - `duration` 타이머가 이벤트의 `duration`(250ms)을 그대로 기록한다(`count() == 1`, `totalTime(MILLISECONDS) == 250.0`).

- **`PrometheusEndpointTest`** — `@SpringBootTest` + `MockMvc` 로 `/actuator/prometheus` 를 실제로 호출해서 응답 본문에 `credit_ledger_reconciliation_mismatch` 가 언더스코어 형태로 들어있는지 확인한다. 이 테스트는 지표 등록이 부트 과정 전체(설정 → 빈 생성 → actuator 엔드포인트 등록)를 거쳐 실제로 노출되는 것까지 검증한다는 점에서 앞의 두 테스트와 성격이 다르다 — 단위 테스트는 "로직이 맞다"를, 이 테스트는 "배선이 맞다"를 보장한다.

### 직접 확인하는 방법

테스트 프로파일(H2, `app.scheduling.enabled=false`, 대사가 아직 한 번도 안 돈 상태)에서 `/actuator/prometheus` 를 호출하면 다음이 그대로 보인다(`PrometheusEndpointTest` 를 통해 실제로 확인한 응답):

```
# HELP credit_ledger_reconciliation_checked 마지막 대사 주기에서 검사한 조직 수
# TYPE credit_ledger_reconciliation_checked gauge
credit_ledger_reconciliation_checked{application="credit_system"} 0.0
# HELP credit_ledger_reconciliation_cycles_total 완료한 원장 대사 주기의 누적 수
# TYPE credit_ledger_reconciliation_cycles_total counter
credit_ledger_reconciliation_cycles_total{application="credit_system"} 0.0
# HELP credit_ledger_reconciliation_duration_seconds 원장 대사 한 주기에 걸린 시간
# TYPE credit_ledger_reconciliation_duration_seconds summary
credit_ledger_reconciliation_duration_seconds_count{application="credit_system"} 0
credit_ledger_reconciliation_duration_seconds_sum{application="credit_system"} 0.0
# HELP credit_ledger_reconciliation_duration_seconds_max 원장 대사 한 주기에 걸린 시간
# TYPE credit_ledger_reconciliation_duration_seconds_max gauge
credit_ledger_reconciliation_duration_seconds_max{application="credit_system"} 0.0
# HELP credit_ledger_reconciliation_mismatch 마지막 대사 주기에서 발견된 불일치 조직 수. 0이 아니면 즉시 사고다
# TYPE credit_ledger_reconciliation_mismatch gauge
credit_ledger_reconciliation_mismatch{application="credit_system"} 0.0
# HELP credit_ledger_reconciliation_staleness_seconds 마지막 성공 대사로부터 흐른 시간(초). 대사 자체가 멈춘 것을 탐지한다
# TYPE credit_ledger_reconciliation_staleness_seconds gauge
credit_ledger_reconciliation_staleness_seconds{application="credit_system"} -1.0
```

`staleness` 가 `-1.0` 인 것이 바로 "대사가 아직 한 번도 안 돈 상태"의 정상 신호다. `mismatch`/`checked` 는 아직 이벤트가 없어 초깃값 0 을 보여준다.

실제 서버를 띄우고(`app.scheduling.enabled=true` 인 기본 프로파일, MySQL/Redis 필요) 대사가 최소 한 번 돈 뒤라면:

```
curl localhost:8080/actuator/prometheus | grep credit_ledger
```

`cycles_total` 이 1 이상으로 오르고, `staleness_seconds` 가 0 근처의 작은 양수(마지막 대사 이후 흐른 초)로 바뀌며, 대사 주기(기본 60초)가 지나기 전까지 계속 증가하다가 다음 주기가 돌면 다시 0 근처로 리셋되는 톱니 모양을 그리는 것을 볼 수 있다.

### 이 단계에서 남는 것

- **1단계은 L1(불변식) 한 겹만 덮는다.** 원장 대사가 지키는 것은 "잔액 = 최초 잔액 + 원장 합계" 라는 데이터 정합성 불변식이다. 이번 단계가 지표로 승격한 것은 이 불변식이 깨졌는지 여부뿐이다.
- **방어 발동 지표(L3)는 다음 단계다.** (→ 2단계에서 구현했다) step2~step6 이 만든 조건부 UPDATE, 유니크 제약, CAS, heartbeat 회수 같은 방어 장치들이 실제로 몇 번 발동했는지(예: 잔액 부족으로 거절된 횟수, 유니크 제약으로 막힌 중복 요청 수, timeout 회수 횟수)는 아직 지표가 없다. 이런 장치가 "얼마나 자주 실제로 막고 있는지" 를 보려면 도메인 서비스 계측이 필요하고, 이는 이번 범위 밖이다.
- **미결 hold 나이(L0)도 다음 단계다.** (→ 3단계에서 구현했다) 확정도 환불도 되지 않은 채 오래 떠 있는 hold 가 있는지(돈이 묶인 채 방치되는 상황)는 스냅샷 게이지가 필요한 영역이고, 아직 손대지 않았다.
- **여전히 감지일 뿐 교정하지 않는다.** step6 의 원칙이 그대로 이어진다 — `mismatch` 게이지가 0 이 아니어도 자동으로 아무것도 고치지 않는다. 사람이 알람을 보고 대응하는 구조다. 이번 단계가 바꾼 것은 "그 알아챔이 로그 grep 이 아니라 지표와 알람 규칙으로 자동화됐다"는 것뿐이다.
---

## 2단계 — 방어 발동 카운터

### 논지: 0행이 곧 방어 장치의 가동 기록이다

이 코드베이스의 방어 장치는 전부 같은 모양이다. 조건부 UPDATE 를 날리고, 영향 행 수를 보고, `updated == 0` 이면 물러난다. 조건부 잔액 차감(step2), attemptNo CAS(step4), 워커 선점, 재시도 경쟁, 늦은 워커의 환불 취소가 전부 이 한 가지 패턴이다. 포트폴리오 원문에 이미 이렇게 써 있다:

> 모든 경우에 패자는 예외 없이 0행을 받고 물러납니다.

그렇다면 **그 0행을 세면 그게 곧 방어 장치의 가동 기록이다.** 지표를 위해 새 개념을 만들 필요가 없다. 이미 코드 곳곳에 흩어져 있던 `if (updated == 0)` 분기를 이벤트 발행 지점으로 승격하기만 하면 된다. 이번 단계에서 새로 생긴 도메인 개념은 하나도 없다.

패자만 세지 않는다는 것이 설계의 절반이다. `applied` 를 함께 세지 않으면 분모가 없어 비율을 만들 수 없다. `sum by (point)` 가 그 지점을 통과한 전체 시도 수가 되도록 두 결과를 모두 발행한다. "confirm 이 100번 중 3번 무효화됐다"와 "confirm 이 3번 무효화됐다"는 전혀 다른 이야기다.

### 훅 지점

| point | 파일 · 메서드 | 조건부 UPDATE | applied | 막힌 outcome |
|---|---|---|---|---|
| `HOLD_BALANCE` | `HoldService.deductBalance()` | `organizationRepository.deductBalance` | `APPLIED` | `REJECTED` (이어서 `InsufficientBalanceException`) |
| `IDEM_KEY` | `HoldService.requestGeneration()` | (조회 방어) `existing != null` | — | `APP_HIT` |
| `IDEM_KEY` | `GlobalExceptionHandler.handleDataIntegrityViolation()` | (DB 유니크 제약) | — | `DB_UNIQUE` |
| `WORKER_CLAIM` | `GenerationWorker.claim()` | `startProcessingIfAttemptMatches` | `APPLIED` | `LOST` |
| `CONFIRM` | `JobLifecycleService.confirm()` | `completeIfAttemptMatches` | `APPLIED` | `STALE` |
| `MARK_FAILED` | `JobLifecycleService.markFailed()` | `failIfProcessing` | `APPLIED` | `STALE` |
| `RETRY_CLAIM` | `JobLifecycleService.retry()` | `incrementAttemptForRetry` | `APPLIED` | `LOST` |
| `FINAL_REFUND` | `JobLifecycleService.finalRefund()` | `refundIfFailed` | `APPLIED` | `RACED` |

회수 장치는 성격이 달라 별도 이벤트(`JobRecovered`)로 뺐다. 방어는 "막았다"이고 회수는 "이미 죽은 것을 되살렸다"라서, 같은 지표에 섞으면 해석이 엉킨다.

| detector | 파일 · 메서드 | 발행 조건 |
|---|---|---|
| `HEARTBEAT` | `DeadJobRecoveryTask.recoverExpired()` | `failIfProcessing` 가 1행일 때만 |
| `BACKSTOP` | `DeadJobRecoveryTask.recoverStalled()` | `failIfProcessing` 가 1행일 때만 |

### 파일별 변경 목록

| 파일 | 신규/수정 | 무엇을, 왜 |
|---|---|---|
| `global/event/DefenseTriggered.kt` | 신규 | `DefensePoint`(7종)·`DefenseOutcome`(7종) enum 과 두 값만 담는 불변 이벤트. Micrometer 를 모른다 |
| `job/event/JobRecovered.kt` | 신규 | `RecoveryDetector`(2종) enum 과 `jobId`·`attemptNo`·`detector` 를 담는 이벤트 |
| `observability/DefenseMetrics.kt` | 신규 | 두 이벤트를 받아 카운터를 올리는 유일한 컴포넌트. 생성자에서 유효 조합 전부를 0으로 사전 등록한다 |
| `job/service/HoldService.kt` | 수정 | 생성자에 `ApplicationEventPublisher` 추가. 멱등 조회 분기와 `deductBalance` 의 두 갈래에 이벤트 발행을 얹는다 |
| `job/service/JobLifecycleService.kt` | 수정 | 생성자에 publisher 추가. `confirm`/`markFailed`/`retry`/`finalRefund` 네 메서드의 0행 분기와 성공 경로에 발행을 얹는다 |
| `job/worker/GenerationWorker.kt` | 수정 | 생성자에 publisher 추가. `claim()` 의 선점 성공/실패 두 갈래에 발행을 얹는다 |
| `job/scheduling/DeadJobRecoveryTask.kt` | 수정 | 생성자에 publisher 추가. 두 회수 경로의 `updated == 1` 안에서만 `JobRecovered` 를 발행한다 |
| `global/exception/GlobalExceptionHandler.kt` | 수정 | 생성자에 publisher 추가. 유니크 위반으로 판정한 분기에서 `IDEM_KEY/DB_UNIQUE` 를 발행한다 |
| `src/test/.../support/RecordingEventPublisher.kt` | 신규 | 발행된 이벤트를 그대로 모으는 테스트용 publisher. 여러 테스트가 공유한다 |
| `observability/DefenseMetricsTest.kt` | 신규 | `SimpleMeterRegistry` 로 태그·사전 등록·소문자 변환을 검증하는 순수 단위 테스트 |
| `observability/DefenseMetricsConcurrencyTest.kt` | 신규 | Testcontainers MySQL 위에서 진짜 경쟁을 만들고 카운터 합이 시도 수와 맞는지 확인 |
| `HoldServiceTest` / `JobLifecycleServiceTest` / `GenerationWorkerUnitTest` / `DeadJobRecoveryTaskTest` / `GlobalExceptionHandlerTest` | 수정 | 생성자 변경에 맞춰 fake publisher 를 넘기고, 지점별 발행을 검증하는 테스트를 추가. 기존 단언은 전부 유지 |

### 지표 표

| Micrometer 이름 | Prometheus 에서 보이는 이름 | 타입 | 태그 |
|---|---|---|---|
| `credit.defense` | `credit_defense_total` | Counter | `point`, `outcome` |
| `credit.job.recovery` | `credit_job_recovery_total` | Counter | `detector` |

`(point, outcome)` 조합별로 이 숫자가 오르면 무슨 뜻인가:

| 조합 | 뜻 | 오르면 |
|---|---|---|
| `hold_balance` / `applied` | 잔액 차감이 성공했다 | 정상 처리량. 나머지 지표의 분모다 |
| `hold_balance` / `rejected` | UPDATE 시점에 `balance < cost` 였다 | 잔액 부족 거절. 급증하면 충전 안내나 요금제 설계를 봐야 한다 |
| `idem_key` / `app_hit` | 1차 방어(애플리케이션 조회)가 중복을 잡았다 | 클라이언트 재시도가 정상 동작 중이라는 뜻 |
| `idem_key` / `db_unique` | 조회를 통과한 요청을 DB 유니크 제약이 잡았다 | 진짜 동시 요청이 있었다는 뜻. 조회와 삽입 사이의 틈으로 들어온 건이다 |
| `worker_claim` / `applied` | 워커가 job 을 선점했다 | 워커 처리량 |
| `worker_claim` / `lost` | 다른 워커가 먼저 선점했거나 무효한 세대였다 | 워커 수 대비 job 수가 적을 때 자연히 오른다. 급증은 워커 과다 스케일아웃 신호 |
| `confirm` / `applied` | 생성 결과가 확정됐다 | 성공 처리량 |
| `confirm` / `stale` | 낡은 세대의 confirm 이 무효화됐다 | **step4 의 attemptNo 가 실제로 일했다는 증거.** 0이 아니면 죽었다 살아난 워커가 늦게 결과를 들고 왔다는 뜻이다 |
| `mark_failed` / `applied` | 실패 전이가 반영됐다 | 실패율의 분자 |
| `mark_failed` / `stale` | 낡은 세대의 실패 보고가 무효화됐다 | `confirm/stale` 과 같은 원인, 반대 결과 |
| `retry_claim` / `applied` | 재시도 투입에 성공했다 | 재시도량 |
| `retry_claim` / `lost` | 같은 FAILED job 을 두 스캐너가 동시에 집어 한쪽이 밀렸다 | 스캐너가 여러 인스턴스로 도는 환경에서 자연히 오른다 |
| `final_refund` / `applied` | 최종 환불이 반영됐다 | 돈이 실제로 되돌아간 건수. `hold_balance/rejected` 와 함께 L0 로 이어진다 |
| `final_refund` / `raced` | 환불 직전에 늦은 워커가 먼저 job 을 확정해서 환불을 취소했다 | **늦은 워커가 이겼다.** 이 값이 오른다는 것은 "환불했는데 결과도 나왔다"는 이중 지급을 막은 기록이다 |

### 해석 가이드

**`credit_job_recovery_total{detector="backstop"} > 0` 은 그 자체로 신호다.** backstop 은 `updatedAt` 이 timeout 을 넘긴 PROCESSING job 을 스캔해서 잡는 2차 감지 장치다. 정상이라면 Redis heartbeat 의 TTL 만료(1차)가 먼저 잡아야 한다. backstop 이 잡았다는 것은 heartbeat 가 그 job 을 놓쳤다는 뜻이고, Redis 키가 새고 있거나 워커가 heartbeat 갱신을 못 하고 있다는 뜻이다. **이중 감지 장치가 자기 자신의 건강을 재는 지표가 된다** — 뒷줄이 공을 잡는 횟수가 앞줄의 수비력을 말해준다.

**`confirm/stale > 0`** 은 사고가 아니라 방어가 일한 기록이다. 다만 이 숫자가 꾸준히 오른다면 워커가 자주 죽거나 멈춘다는 뜻이므로 `credit_job_recovery_total` 과 함께 봐야 한다.

**`final_refund/raced`** 는 가장 아슬아슬한 경로다. 환불 결정을 내린 순간과 실제 UPDATE 사이에 늦은 워커가 job 을 COMPLETED 로 바꿔 버린 경우다. 이 값이 0이 아니라는 것은 "환불과 확정이 실제로 경쟁하고 있다"는 뜻이고, 그 경쟁을 조건부 UPDATE 가 매번 이겨서 이중 지급이 안 났다는 뜻이다.

**`db_unique` 대 `app_hit` 의 비율**은 두 겹 멱등 방어의 역할 분담을 보여준다. `app_hit` 이 대부분이라면 중복의 대부분이 시간차를 두고 들어오는 클라이언트 재시도라는 뜻이다. `db_unique` 비중이 크다면 조회와 삽입 사이의 좁은 틈을 뚫고 들어올 만큼 진짜 동시 요청이 많다는 뜻이고, 그때는 1차 조회가 사실상 일을 못 하고 DB 가 다 받아내고 있는 것이다.

### 트레이드오프: 0행은 이유를 말하지 않는다

`hold_balance` 의 outcome 이 `rejected` 하나뿐인 것은 게을러서가 아니다. 구분할 수 없어서다.

조건부 UPDATE 의 0행은 "UPDATE 를 실행한 시점에 `balance < cost` 였다"는 뜻 하나뿐이다. 처음부터 잔액이 부족했는지, 아니면 동시에 들어온 다른 요청들이 먼저 빼가서 그 순간 부족해졌는지 — SQL 은 그 차이를 말해주지 않는다. 실패 후 `organizationFinder.getOrThrow` 로 조직을 재조회하지만, 그건 예외 메시지에 현재 잔액을 담기 위한 것이지 원인을 가르는 수단이 아니다. 재조회해도 여전히 "부족" 상태일 뿐이다.

**원자성을 얻으면 관측 해상도를 잃는다.** 확인(`balance >= cost`)과 실행(`balance = balance - cost`)을 한 문장에 넣어서 경쟁 조건을 없앤 대가로, 왜 막혔는지를 DB 가 말해주지 않게 됐다. 이걸 되찾으려면 SELECT 로 먼저 확인하고 UPDATE 하는 방식으로 돌아가야 하는데, 그건 step2 가 없앤 바로 그 버그다. 관측 해상도보다 정확성이 중요하므로 현행을 유지하고, 대신 이 한계를 문서에 남긴다.

### `@EventListener` 인가 `@TransactionalEventListener` 인가

`DefenseMetrics` 는 평범한 `@EventListener` 를 쓴다. `@TransactionalEventListener(phase = AFTER_COMMIT)` 는 쓰지 않는다.

"조건부 UPDATE 가 0행을 돌려받았다"는 사실은 그 트랜잭션이 커밋되든 롤백되든 참이다. 방어 장치는 이미 발동했고, 그 발동을 세는 것이 목적이다. AFTER_COMMIT 을 걸면 롤백된 트랜잭션의 이벤트를 조용히 버리게 되는데, **유니크 위반처럼 롤백되는 경우가 오히려 가장 세고 싶은 사건**이다. 관측이 필요한 순간에 관측이 사라지는 설계는 쓸 수 없다.

실제 발행 지점들을 하나씩 보면 이 선택이 더 분명해진다:

- `IDEM_KEY/DB_UNIQUE` 는 `@Transactional` 서비스 밖으로 예외가 튀어나온 뒤 `GlobalExceptionHandler` 에서 발행된다. 이미 롤백이 끝난 시점이라 AFTER_COMMIT 리스너였다면 이 이벤트는 애초에 도달조차 못 한다.
- 나머지 0행 분기는 전부 `return` 으로 끝나서 그 뒤에 추가 쓰기가 없다. 롤백될 것 자체가 없다.
- `applied` 쪽은 트랜잭션이 정상 커밋되는 경로다. 커밋 직전에 세든 직후에 세든 결과가 같다.

### enum 이 카디널리티 가드다

`DefensePoint` 와 `DefenseOutcome` 은 String 이 아니라 enum 이다. 태그로 나갈 값의 집합이 컴파일 타임에 닫혀 있다는 뜻이고, 그래서 시계열 수의 상한이 코드를 읽는 것만으로 확정된다 — 지금은 14개다.

Prometheus 는 태그 조합마다 별도 시계열을 만든다. 만약 `point` 가 자유 문자열이었다면 누군가 `"confirm:job-1234"` 같은 값을 넣는 순간 시계열이 job 수만큼 불어난다. 카디널리티 폭발은 대개 이렇게 "한 번만 더 자세히 보고 싶어서" 일어난다. enum 은 그 유혹을 타입 시스템 차원에서 차단한다.

같은 이유로 `JobRecovered` 는 `jobId` 와 `attemptNo` 를 이벤트에 담되 **태그로는 쓰지 않는다.** 이 값들은 리스너의 DEBUG 로그로만 나간다. 집계는 지표가, 개별 식별은 로그가 맡는다 — 1단계에서 `organizationId` 에 대해 내린 결정과 같다.

### 왜 모든 조합을 0으로 미리 등록하는가

`registry.counter(name, tags...)` 로 이벤트가 올 때 lazy 등록해도 동작은 한다. 하지만 그러면 **한 번도 발생하지 않은 조합은 스크레이프 응답에 아예 없다.**

Prometheus 는 "없는 시계열"과 "값이 0인 시계열"을 다르게 다룬다. `rate(credit_defense_total{point="confirm",outcome="stale"}[5m]) > 0` 같은 알람 규칙은 시계열이 존재하지 않으면 결과가 비어서 평가 자체가 성립하지 않는다. 대시보드 패널도 "No data" 를 그린다. 정작 사고가 나서 첫 이벤트가 발생하는 순간에야 시계열이 생기는데, 그 시점이 바로 알람이 이미 작동하고 있어야 할 시점이다.

그래서 `DefenseMetrics` 는 생성자에서 유효 조합 전부를 `Counter.builder(...).tags(...).register(registry)` 로 0에 깔아 둔다. 유효 조합은 위 훅 지점 표의 조합만이다 — `hold_balance` 는 `applied`/`rejected` 두 개뿐이고, `idem_key` 는 `app_hit`/`db_unique` 두 개뿐이다. `hold_balance` 에 `stale` 같은 조합은 코드상 발생하지 않으므로 만들지 않는다. 14개 조합 × 1개 지표 + 2개 detector 가 전부다.

이벤트로 들어온 조합이 사전 등록 목록에 없다면 그건 코드가 바뀌었는데 목록이 안 따라온 것이다. 이때 `IllegalArgumentException` 을 던지면 관측 코드가 도메인 흐름을 죽인다 — 지표를 붙이다가 결제를 망가뜨리는 것은 어떤 경우에도 남는 장사가 아니다. 그래서 조용히 등록해서 세고, WARN 로그를 남긴다. 로그가 고치라고 알려주는 동안 서비스는 계속 돈다.

### 핵심 코드 읽기

#### `HoldService.deductBalance` — 두 갈래 모두 센다

**step7-1 (이전)**:

```kotlin
private fun deductBalance(organizationId: Long, cost: Long) {
    val updated = organizationRepository.deductBalance(organizationId, cost, Instant.now())
    if (updated == 1) {
        return
    }

    val organization = organizationFinder.getOrThrow(organizationId)
    throw InsufficientBalanceException(organization.balance, cost)
}
```

**step7-2 (이번)**:

```kotlin
private fun deductBalance(organizationId: Long, cost: Long) {
    val updated = organizationRepository.deductBalance(organizationId, cost, Instant.now())
    if (updated == 1) {
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.HOLD_BALANCE, DefenseOutcome.APPLIED))
        return
    }
    eventPublisher.publishEvent(DefenseTriggered(DefensePoint.HOLD_BALANCE, DefenseOutcome.REJECTED))

    val organization = organizationFinder.getOrThrow(organizationId)
    throw InsufficientBalanceException(organization.balance, cost)
}
```

조건부 UPDATE 도, 분기 조건도, 던지는 예외도 그대로다. 이미 있던 갈림길에 발행 두 줄이 얹혔을 뿐이다. `REJECTED` 를 예외를 던지기 **전에** 발행하는 것이 중요하다 — 예외가 어디까지 올라가서 어떻게 처리되든, 방어가 발동했다는 사실은 이미 기록됐다.

#### `JobLifecycleService.confirm` — 낡은 세대의 흔적을 남긴다

**step7-1 (이전)**:

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

**step7-2 (이번)**:

```kotlin
@Transactional
fun confirm(job: Job, resultUrl: String) {
    val jobId = job.persistedId
    val updated = jobRepository.completeIfAttemptMatches(jobId, resultUrl, job.attemptNo, Instant.now())
    if (updated == 0) {
        log.info("이미 무효화된 시도, confirm 무시: jobId={}, attemptNo={}", jobId, job.attemptNo)
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.CONFIRM, DefenseOutcome.STALE))
        return
    }
    eventPublisher.publishEvent(DefenseTriggered(DefensePoint.CONFIRM, DefenseOutcome.APPLIED))
    ledgerRepository.save(LedgerEntry.confirm(job.organizationId, jobId))
    log.info("confirm 완료: jobId={}, attemptNo={}", jobId, job.attemptNo)
}
```

이 `log.info` 한 줄이 지금까지 이 사건의 유일한 흔적이었다. 로그는 누군가 grep 해야만 존재하는 것과 같다. 같은 자리에서 카운터가 오르면, 이제 이 사건은 추세와 알람 규칙을 걸 수 있는 값이 된다.

#### `DeadJobRecoveryTask.recoverStalled` — 2차 감지 장치가 자기 자신을 잰다

**step7-1 (이전)**:

```kotlin
private fun recoverStalled(job: Job) {
    try {
        val jobId = job.persistedId
        if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) {
            return
        }
        val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
        if (updated == 1) {
            heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
            log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
        }
    } catch (e: RuntimeException) {
        log.warn("PROCESSING 정체 job 회수 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
    }
}
```

**step7-2 (이번)**:

```kotlin
private fun recoverStalled(job: Job) {
    try {
        val jobId = job.persistedId
        if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) {
            return
        }
        val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
        if (updated == 1) {
            heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
            log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
            eventPublisher.publishEvent(
                JobRecovered(jobId, job.attemptNo, RecoveryDetector.BACKSTOP)
            )
        }
    } catch (e: RuntimeException) {
        log.warn("PROCESSING 정체 job 회수 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
    }
}
```

발행이 `updated == 1` **안에** 있는 것이 핵심이다. 스캔에 걸렸지만 회수 UPDATE 가 0행을 받은 경우(그 사이 다른 경로가 상태를 바꾼 경우)는 회수한 것이 아니므로 세면 안 된다. 이 카운터는 "몇 건을 스캔했나"가 아니라 "몇 건을 실제로 되살렸나"를 세야 heartbeat 누수의 척도가 된다.

#### `DefenseMetrics` — Micrometer 를 아는 유일한 곳

```kotlin
@Component
class DefenseMetrics(
    private val registry: MeterRegistry
) {

    private val defenseCounters = ConcurrentHashMap<Pair<DefensePoint, DefenseOutcome>, Counter>()
    private val recoveryCounters = ConcurrentHashMap<RecoveryDetector, Counter>()

    init {
        for ((point, outcomes) in VALID_COMBINATIONS) {
            for (outcome in outcomes) {
                defenseCounters[point to outcome] = registerDefenseCounter(point, outcome)
            }
        }
        for (detector in RecoveryDetector.entries) {
            recoveryCounters[detector] = registerRecoveryCounter(detector)
        }
    }

    @EventListener
    fun onDefenseTriggered(event: DefenseTriggered) {
        defenseCounters.computeIfAbsent(event.point to event.outcome) { (point, outcome) ->
            log.warn("사전 등록되지 않은 방어 조합: point={}, outcome={}", point, outcome)
            registerDefenseCounter(point, outcome)
        }.increment()
    }
```

`Counter` 는 1단계의 `Gauge` 와 달리 값을 레지스트리 안에 들고 있어서 약한 참조 문제가 없다. 그래도 `Counter` 인스턴스를 맵에 캐시해 두는데, 이유는 GC 가 아니라 **이벤트마다 레지스트리를 태그로 검색하는 비용을 피하기 위해서**다. 방어 지점은 요청 경로 한복판이라 발행 빈도가 높다.

태그 값은 `point.name.lowercase()` 로 만든다. Kotlin enum 상수는 `HOLD_BALANCE` 처럼 대문자 스네이크지만, Prometheus 레이블 값의 관례는 소문자다. 변환을 리스너 한 곳에 두면 도메인 쪽은 enum 그대로 쓰면 된다.

### 테스트가 보장하는 것

- **서비스 단위 (`@DataJpaTest`)** — 각 0행 분기가 올바른 `(point, outcome)` 을 발행하는지 실제 DB(H2)에 붙여서 확인한다. `HoldServiceTest` 는 정상 hold 가 `HOLD_BALANCE/APPLIED` 를, 잔액 부족이 `HOLD_BALANCE/REJECTED` 를 발행한 **뒤** 예외를 던지는지, 같은 idemKey 재요청이 `IDEM_KEY/APP_HIT` 를 발행하는지 본다. `JobLifecycleServiceTest` 는 네 메서드 각각을 두 번씩 호출해 첫 번째는 `APPLIED`, 두 번째는 `STALE`/`LOST`/`RACED` 가 나오는 것을 확인한다 — 같은 스냅샷으로 두 번 호출하는 것이 곧 경쟁 상황의 결정적 재현이다.

- **워커·스케줄러 단위 (Mockito)** — `GenerationWorkerUnitTest` 는 `startProcessingIfAttemptMatches` 가 1/0을 돌려줄 때 각각 `WORKER_CLAIM/APPLIED`, `WORKER_CLAIM/LOST` 가 나오는지 본다. `DeadJobRecoveryTaskTest` 는 heartbeat 만료 회수가 `HEARTBEAT`, `updatedAt` 정체 회수가 `BACKSTOP` 을 발행하는지, 그리고 **회수 UPDATE 가 0행이면 아무것도 발행하지 않는지**까지 단언한다. 마지막 것이 없으면 "스캔에 걸린 건수"와 "실제 회수 건수"가 섞여도 테스트가 통과해 버린다.

- **`DefenseMetricsTest` (`SimpleMeterRegistry`)** — 이벤트 하나가 해당 태그 조합만 +1 하고 다른 조합은 0으로 남는지, 컴포넌트 생성 직후 14개 조합 전부가 이벤트 없이 조회되는지(`credit.defense{point=confirm,outcome=stale}` 가 0으로 존재한다), 태그 값이 전부 소문자인지, 사전 등록되지 않은 조합이 와도 예외 없이 등록되어 세어지는지를 본다.

- **`DefenseMetricsConcurrencyTest` (`@SpringBootTest` + Testcontainers MySQL)** — 이 단계의 결정타다. 잔액이 10건 중 4건만 감당하는 상태에서 10건을 동시에 밀어 넣고, `hold_balance/applied` 증가분이 정확히 4, `rejected` 증가분이 6, 둘의 합이 10인 것을 확인한다. 동시에 조직 잔액이 정확히 0인 것도 본다 — **카운터와 실제 돈이 같은 이야기를 해야 한다.** 두 번째 테스트는 같은 idemKey 로 10건을 동시에 밀어 넣고 `idem_key/app_hit + idem_key/db_unique == 9`, `hold_balance/applied == 1` 을 확인한다. `db_unique` 는 서비스 밖에서 발행되므로, 서비스에서 새어 나온 `DataIntegrityViolationException` 을 `GlobalExceptionHandler` 에 그대로 넘긴다 — DispatcherServlet 이 하는 것과 같은 호출이다.

**함정: `MeterRegistry` 는 Spring 컨텍스트의 싱글턴이다.** 컨텍스트를 공유하는 다른 테스트가 이미 올려 둔 카운트가 그대로 남아 있고, JUnit 의 실행 순서는 보장되지 않는다. 절대값을 단언하면 혼자 돌릴 때는 통과하고 전체 실행에서는 깨지는 플레이키 테스트가 된다. 그래서 테스트 시작 시점의 값을 찍어 두고 **증가분만** 단언한다. 지표는 애플리케이션 수명 동안 누적되는 값이라는 성질이 테스트에서는 그대로 함정이 된다.

### 직접 확인하는 방법

```
curl localhost:8080/actuator/prometheus | grep credit_defense
```

아래는 테스트 프로파일에서 `/actuator/prometheus` 를 실제로 호출해 받은 응답이다. 먼저 **이벤트가 한 번도 발생하지 않은 상태**:

```
# HELP credit_defense_total 방어 장치가 가동한 횟수. outcome=applied 는 통과, 나머지는 막힌 시도다
# TYPE credit_defense_total counter
credit_defense_total{application="credit_system",outcome="app_hit",point="idem_key"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="confirm"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="final_refund"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="hold_balance"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="mark_failed"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="retry_claim"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="worker_claim"} 0.0
credit_defense_total{application="credit_system",outcome="db_unique",point="idem_key"} 0.0
credit_defense_total{application="credit_system",outcome="lost",point="retry_claim"} 0.0
credit_defense_total{application="credit_system",outcome="lost",point="worker_claim"} 0.0
credit_defense_total{application="credit_system",outcome="raced",point="final_refund"} 0.0
credit_defense_total{application="credit_system",outcome="rejected",point="hold_balance"} 0.0
credit_defense_total{application="credit_system",outcome="stale",point="confirm"} 0.0
credit_defense_total{application="credit_system",outcome="stale",point="mark_failed"} 0.0
# HELP credit_job_recovery_total 죽은 job 을 FAILED 로 회수한 횟수. detector=backstop 이 0이 아니면 heartbeat 누수다
# TYPE credit_job_recovery_total counter
credit_job_recovery_total{application="credit_system",detector="backstop"} 0.0
credit_job_recovery_total{application="credit_system",detector="heartbeat"} 0.0
```

**14개 조합이 전부 0으로 보인다는 것이 사전 등록의 결과다.** lazy 등록이었다면 이 응답에는 `credit_defense_total` 이라는 이름 자체가 없었을 것이다.

같은 엔드포인트를 몇 건의 방어 발동 뒤에 다시 호출하면:

```
credit_defense_total{application="credit_system",outcome="app_hit",point="idem_key"} 1.0
credit_defense_total{application="credit_system",outcome="applied",point="confirm"} 1.0
credit_defense_total{application="credit_system",outcome="applied",point="final_refund"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="hold_balance"} 3.0
credit_defense_total{application="credit_system",outcome="applied",point="mark_failed"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="retry_claim"} 0.0
credit_defense_total{application="credit_system",outcome="applied",point="worker_claim"} 1.0
credit_defense_total{application="credit_system",outcome="db_unique",point="idem_key"} 0.0
credit_defense_total{application="credit_system",outcome="lost",point="retry_claim"} 0.0
credit_defense_total{application="credit_system",outcome="lost",point="worker_claim"} 0.0
credit_defense_total{application="credit_system",outcome="raced",point="final_refund"} 0.0
credit_defense_total{application="credit_system",outcome="rejected",point="hold_balance"} 1.0
credit_defense_total{application="credit_system",outcome="stale",point="confirm"} 1.0
credit_defense_total{application="credit_system",outcome="stale",point="mark_failed"} 0.0
credit_job_recovery_total{application="credit_system",detector="backstop"} 0.0
credit_job_recovery_total{application="credit_system",detector="heartbeat"} 1.0
```

`hold_balance` 는 `applied=3`, `rejected=1` 로 총 4건의 시도가 그 지점을 통과했다는 것을 보여준다. `confirm/stale=1` 은 낡은 세대의 confirm 이 한 번 무효화됐다는 뜻이다 — 이 사건은 HTTP 응답에도, 에러 로그에도 남지 않는다. 이 줄이 유일한 흔적이다.

물어볼 만한 질문 몇 개를 PromQL 로 옮기면 이렇게 된다:

```
# 잔액 부족 거절 비율
sum(rate(credit_defense_total{point="hold_balance",outcome="rejected"}[5m]))
  / sum(rate(credit_defense_total{point="hold_balance"}[5m]))

# 낡은 세대 쓰기 무효화가 실제로 일어나고 있나
sum(rate(credit_defense_total{outcome="stale"}[5m])) by (point)

# heartbeat 가 새고 있나 (0이 아니면 조사)
sum(rate(credit_job_recovery_total{detector="backstop"}[15m]))

# 멱등 방어의 역할 분담
sum(rate(credit_defense_total{point="idem_key"}[5m])) by (outcome)
```

### 이 단계에서 남는 것

- **카운터는 "코드가 불렸다"만 센다.** `credit_defense_total` 이 올랐다는 것은 그 자리를 지나갔다는 뜻이다. 반대로 코드가 **안 불린** 사고는 이 지표로 잡히지 않는다. 워커가 통째로 죽어서 `confirm` 이 영영 호출되지 않으면 `confirm/applied` 도 `confirm/stale` 도 안 오른다. 그냥 조용하다. 지표를 보면 "아무 일도 없음"과 구분이 안 된다.
- **그래서 3단계 스냅샷 게이지가 필요하다.** "지금 PROCESSING 인 job 이 몇 개이고 가장 오래된 것이 몇 초째인가", "미결 hold 가 몇 건이고 최고 나이가 얼마인가" — 이건 이벤트를 세서는 알 수 없고, 주기적으로 현재 상태를 찍어야 알 수 있다. 1단계의 `staleness` 게이지가 "대사 자체가 멈춘 것"을 잡았던 것과 정확히 같은 구조의 문제다. 흐름(카운터)과 상태(게이지)는 서로를 대체하지 못한다.
- **L2 흐름은 여전히 비어 있다.** 상태별 job 수와 단계별 소요 시간(hold → 선점 → 확정)은 아직 지표가 없다. 어디서 막혔는지를 보려면 필요하다.
- **여전히 감지일 뿐 교정하지 않는다.** step6 부터 이어지는 원칙 그대로다. `final_refund/raced` 가 올라도 자동으로 아무것도 되돌리지 않는다. 방어 장치는 이미 옳게 동작했고, 지표는 그것이 동작했다는 사실만 알린다.

## 3단계 — 상태 스냅샷 게이지

### 논지: 없는 것은 셀 수 없다

2단계가 만든 카운터는 **"코드가 불렸다"만 센다.** `credit_defense_total{point="confirm",outcome="applied"}` 이 올랐다는 것은 누군가 그 자리를 지나갔다는 뜻이다. 뒤집으면, 코드가 **안 불린** 사고는 이 지표로 절대 잡히지 않는다.

워커 프로세스가 통째로 죽었다고 하자. confirm 은 영영 호출되지 않는다. `confirm/applied` 도 안 오르고 `confirm/stale` 도 안 오른다. 카운터는 그냥 조용하다. 그런데 트래픽이 없는 새벽에도 카운터는 똑같이 조용하다 — **"안 오름"과 "사고"가 지표상 구분되지 않는다.** 없는 것은 셀 수 없기 때문이다.

그래서 **실행을 세는 지표(카운터)와 상태를 재는 지표(스냅샷 게이지)는 다른 물건이다.** 스냅샷은 주기적으로 DB 에 "지금 이 상태인 row 가 몇 개냐"를 직접 묻는다. 어떤 코드 경로가 불렸는지와 무관하게 상태 자체를 본다. **사고 탐지는 반드시 이쪽이어야 하고, 카운터는 사고가 난 뒤 원인을 좁히는 진단용이다.** 1단계의 `staleness` 게이지가 "대사 자체가 멈춘 것"을 잡았던 것과 정확히 같은 구조인데, 이번엔 관측 장치가 아니라 도메인 상태 전체에 그 구조를 적용한다.

### 미결(pending)의 정의

미결은 **`status NOT IN (COMPLETED, REFUNDED)`** 다. 즉 HOLDING, PROCESSING, **그리고 FAILED** 가 전부 미결이다.

FAILED 를 미결에 넣는 것이 이 정의의 핵심이다. FAILED 는 종결이 아니다 — 재시도를 기다리거나 최종 환불을 기다리는 중이고, 그 job 의 돈은 hold 된 채 **여전히 묶여 있다.** 이 지표군이 재는 것은 "파이프라인이 어느 상태 이름에 머무는가"가 아니라 "돈이 풀렸는가"다. 돈이 실제로 풀리는 상태는 COMPLETED(확정)와 REFUNDED(환불) 둘뿐이고, 그래서 미결의 정의도 그 둘의 여집합이다.

### `createdAt` 인가 `updatedAt` 인가

`oldestPendingAgeSeconds` 는 **`createdAt` 기준**이다. `updatedAt` 이 아니다.

이 값이 답하려는 질문은 "돈이 얼마나 오래 묶여 있나"(L0)이지 "현재 상태에 얼마나 머물렀나"가 아니다. 재시도로 HOLDING → PROCESSING → FAILED → HOLDING 을 몇 번 돌아도, 그 job 이 처음 hold 한 돈은 생성 순간부터 한 번도 풀린 적이 없다.

`updatedAt` 기준이었다면 **재시도가 나이를 0 으로 리셋한다.** 재시도가 계속 실패해서 영원히 도는 job — 정확히 가장 잡고 싶은 상황 — 은 매 재시도마다 나이가 0 으로 돌아가서 지표상 영원히 젊다. 상태 전이가 활발할수록 지표가 침묵하는 구조는 쓸 수 없다. `createdAt` 은 상태 전이가 건드리지 않는 값이라 그 리셋이 원천적으로 불가능하다.

### 0 인가 -1 인가

미결이 하나도 없을 때 `oldestPendingAgeSeconds` 는 **0** 이다. 1단계의 `staleness` 에서 초기값을 -1 로 둔 것과 반대 선택인데, 이유가 있다.

`staleness` 에서 0 은 "방금 성공적으로 대사를 마쳤다"와 "한 번도 안 돌았다"를 구분하지 못했다. 그래서 존재할 수 없는 음수를 "기준점 없음"에 배정해야 했다.

여기서는 그 모호함이 없다. `oldest_pending_age = 0` 은 곧 **"묶인 돈이 없다"** 이고, 그건 정상 상태를 정확히 뜻한다. `outstanding_count` 가 0 이면 나이도 0 인 것이 자연스럽고, 알람 규칙(`> 300`)도 그대로 성립한다. 굳이 -1 을 쓰면 오히려 "정상"에 특별한 상수를 배정하는 꼴이 된다.

다만 이 절의 지표군에도 -1 이 하나 있다. `credit.snapshot.staleness` 다. 그건 도메인 상태가 아니라 **관측 장치 자신의 건강**을 재는 값이라 1단계과 같은 이유로 -1 에서 시작한다.

### 파일별 변경 목록

| 파일 | 신규/수정 | 무엇을, 왜 |
|---|---|---|
| `global/event/DomainSnapshotTaken.kt` | 신규 | 한 시점의 도메인 상태 6개 값 + `duration`/`takenAt` 을 담는 불변 이벤트. Micrometer 를 모른다 |
| `global/scheduling/DomainSnapshotTask.kt` | 신규 | 15초마다 쿼리 6개를 날려 이벤트 하나를 발행하는 스케줄러. `Clock` 빈을 주입받아 나이를 계산한다 |
| `observability/DomainSnapshotMetrics.kt` | 신규 | 이벤트를 받아 Gauge 7종 + Counter + Timer 를 갱신하는 유일한 컴포넌트 |
| `job/repository/JobRepository.kt` | 수정 | 스냅샷용 조회 5개 추가(파생 쿼리 1 + JPQL 4). 기존 전이 쿼리는 한 글자도 건드리지 않았다 |
| `organization/repository/OrganizationRepository.kt` | 수정 | `countByBalanceLessThan` 파생 쿼리 한 줄 추가 |
| `src/main/resources/application.yml` | 수정 | `app.scheduling.snapshot-interval-millis: 15000` 한 줄. `AppProperties` 에는 넣지 않았다 — `reconciliation-interval-millis` 와 같이 `@Scheduled` 의 `${...:15000}` 로만 읽힌다 |
| `src/test/.../support/FixedMutableClock.kt` | 신규 | 1단계의 `LedgerReconciliationMetricsTest` 안에 private 으로 있던 테스트용 시계를 꺼내 공유한다. `RecordingEventPublisher` 와 같은 자리다 |
| `global/scheduling/DomainSnapshotTaskTest.kt` | 신규 | `@DataJpaTest` + 실제 리포지토리 + 고정 시계로 여섯 값이 실제 DB 상태와 맞는지 확인 |
| `observability/DomainSnapshotMetricsTest.kt` | 신규 | `SimpleMeterRegistry` 로 게이지 갱신·덮어쓰기·staleness 를 검증하는 순수 단위 테스트 |
| `observability/PrometheusEndpointTest.kt` | 수정 | `credit_job_oldest_pending_age_seconds` 노출 단언 한 줄 추가 |

### 지표 표

| Micrometer 이름 | Prometheus 에서 보이는 이름 | 타입 | 의미 | 알람 기준 |
|---|---|---|---|---|
| `credit.hold.outstanding.count` | `credit_hold_outstanding_count` | Gauge | 미결 job 수 | 알람 없음. 추세와 분모용 |
| `credit.hold.outstanding.amount` | `credit_hold_outstanding_amount` | Gauge | 미결 job 에 묶인 크레딧 합 | **추세.** 절대 임계값이 아니라 평소 대비 급증을 본다 |
| `credit.job.oldest.pending.age` | `credit_job_oldest_pending_age_seconds` | Gauge (초) | 가장 오래된 미결 job 의 나이 | **P2.** 300초 초과면 파이프라인이 어딘가에서 멈춘 것으로 본다 |
| `credit.invariant.negative.balance.orgs` | `credit_invariant_negative_balance_orgs` | Gauge | `balance < 0` 인 조직 수 | **P1, 즉시 호출.** SLO 가 아니라 불변식이다 |
| `credit.invariant.jobs.without.hold` | `credit_invariant_jobs_without_hold` | Gauge | HOLD 원장이 없는 job 수 | **P1, 즉시 호출** |
| `credit.invariant.unsettled.terminal.jobs` | `credit_invariant_unsettled_terminal_jobs` | Gauge | 종결됐는데 정산 원장이 없는 job 수 | **P1, 즉시 호출** |
| `credit.snapshot.cycles` | `credit_snapshot_cycles_total` | Counter | 완료한 스냅샷 주기 수 | 알람 없음. `rate()` 로 눈으로 확인 |
| `credit.snapshot.duration` | `credit_snapshot_duration_seconds{_count,_sum}` / `_max` | Timer | 스냅샷 한 주기 소요 | 알람 없음. 전체 스캔 쿼리의 비용 추세 관찰용 |
| `credit.snapshot.staleness` | `credit_snapshot_staleness_seconds` | Gauge (초) | 마지막 성공 스냅샷 이후 경과. 초기값 -1 | **P2.** 스냅샷 주기(15초)의 3배인 45초를 넘으면 스냅샷 자체가 멈춘 것 |

불변식 3종의 알람 기준이 "0 이 아니면"인 것은 게으른 임계값 설정이 아니다. **SLO 와 불변식은 다르다.** 응답 시간 p99 는 넘길 수도 있는 목표지만, "잔액은 음수가 될 수 없다"는 넘길 수 있는 목표가 아니라 이 시스템이 참이라고 주장하는 명제다. 1건이라도 어긋났다면 그건 성능 저하가 아니라 코드나 데이터가 틀렸다는 뜻이고, 임계값을 논할 여지가 없다. 1단계의 `mismatch` 게이지와 같은 성격이다.

### `oldest_pending_age` 가 단일 최중요 지표인 이유

이 절에서 지표를 하나만 남기고 다 지워야 한다면 `credit_job_oldest_pending_age_seconds` 를 남긴다. 파이프라인이 **어디서** 멈추든 이 숫자 하나가 무한히 오르기 때문이다.

| 사고 | 2단계의 카운터는 | `oldest_pending_age` 는 |
|---|---|---|
| 워커 프로세스가 전부 죽음 | `worker_claim/*`, `confirm/*` 전부 **침묵**. 트래픽 없는 시간대와 구분 불가 | HOLDING job 이 선점되지 않은 채 늙는다 → **무한 증가** |
| 스케줄러(회수·재시도)가 죽음 | `job_recovery_total` 침묵. FAILED job 이 재시도되지 않아도 카운터엔 흔적 없음 | FAILED job 이 미결에 남아 늙는다 → **무한 증가** |
| Redis 가 죽어 heartbeat 가 안 붙음 | `recovery/heartbeat` 침묵, `recovery/backstop` 이 뒤늦게 오를 수도 있고 아닐 수도 | PROCESSING job 이 회수되지 못한 채 늙는다 → **무한 증가** |
| 스텁 생성 API 가 무한 지연 | `confirm/*` 도 `mark_failed/*` 도 안 오름. 에러율 0%, 응답 시간 정상 | PROCESSING job 이 계속 늙는다 → **무한 증가** |
| DB 커넥션 풀 고갈로 confirm 이 실패 | `confirm/applied` 가 안 오르지만 그게 "안 왔다"인지 "실패했다"인지 모름 | 확정되지 못한 job 이 늙는다 → **무한 증가** |

왼쪽 열의 다섯 가지 사고가 **전부 카운터의 침묵으로 나타난다.** 침묵은 정상과 구분되지 않는다. 오른쪽은 다섯 가지 모두에서 같은 방향으로 단조 증가한다 — 원인은 다르지만 증상이 하나다.

그리고 이 지표는 **원리상 카운터로 만들 수 없다.** 카운터는 무언가가 일어날 때 오른다. 여기서 재는 것은 "미결 job 이 아무 코드도 안 불리는 채로 늙어가는 것", 즉 **아무 일도 일어나지 않고 있다는 사실**이다. 일어나지 않은 일에는 증가시킬 지점이 없다. 시간을 재는 주체가 job 바깥(주기적 스냅샷)에 있어야만 성립한다.

### 부분 스냅샷 금지

`LedgerReconciliationTask` 는 항목 단위로 `try/catch` 를 걸어 한 조직의 실패가 주기 전체를 죽이지 않게 한다. `DomainSnapshotTask` 는 정반대로 간다 — **쿼리 하나라도 실패하면 주기 전체를 버리고 이벤트를 발행하지 않는다.**

두 태스크의 산출물 성격이 다르기 때문이다. 대사는 "몇 건 검사했고 몇 건 어긋났다"는 누적 집계라, 일부를 못 봤어도 나머지 결과는 여전히 참이다. 스냅샷은 **한 시점의 일관된 그림**이다. `outstanding_count` 는 읽었는데 `oldest_pending_age` 쿼리가 실패했다고 앞의 것만 반영하면, 게이지는 "미결 3건, 최고 나이 0초"라는 존재하지 않는 상태를 그린다. 반쯤 채운 스냅샷은 관측이 없는 것보다 나쁘다 — 없으면 아무도 안 믿지만, 있으면 사람들이 믿는다.

이벤트를 발행하지 않으면 게이지는 직전 값에 얼어붙는데, 이 침묵은 `credit_snapshot_staleness_seconds` 가 대신 드러낸다. 게이지가 얼었다는 사실 자체를 별도 지표가 말해주므로, 부분 발행으로 억지로 값을 채울 이유가 없다.

예외는 `log.error` 를 남기고 삼킨다. `@Scheduled` 메서드가 예외를 던져도 스케줄 자체는 유지되지만, 명시적으로 잡아 "이번 주기는 발행하지 않았다"를 로그에 남기는 편이 나중에 staleness 알람을 받았을 때 원인을 바로 찾게 해준다.

### 비용

쿼리 6개가 훑는 범위는 두 부류로 갈린다.

| 쿼리 | 훑는 범위 | 비용 |
|---|---|---|
| `countByStatusNotIn` | `idx_jobs_status_id` 로 미결 상태 3개의 인덱스 구간 | 미결 수에 비례 |
| `sumHoldAmountByStatusNotIn` | 같은 구간 + `holdAmount` 조회 | 미결 수에 비례 |
| `findOldestCreatedAtByStatusNotIn` | 같은 구간 + `createdAt` 조회 | 미결 수에 비례 |
| `countByBalanceLessThan` | `organizations` 전체 | 조직 수에 비례(작다) |
| `countJobsWithoutHoldEntry` | **`jobs` 전체** + 원장 NOT EXISTS | job 총수에 비례 |
| `countUnsettledTerminalJobs` | **`jobs` 전체** + 원장 NOT EXISTS ×2 | job 총수에 비례 |

앞의 세 개는 싸다. **미결 집합은 정상 운영에서 작게 유지되기 때문이다** — 파이프라인이 돌면 job 은 COMPLETED/REFUNDED 로 빠져 인덱스 구간에서 사라진다. 미결이 커지는 상황은 곧 사고이고, 그때는 쿼리가 무거워지는 것보다 알람이 울리는 것이 먼저다.

뒤의 두 개는 **전체 스캔이다.** 불변식 검사라 "지금 미결인 것"만 봐서는 답이 안 나온다 — 이미 COMPLETED 된 job 의 정산 원장이 빠진 것을 찾는 것이 목적이기 때문이다. 대사 성격의 쿼리를 15초 주기에 얹은 셈인데, 지금은 job 수가 작아 문제가 없고 단순함을 우선했다.

**한계와 다음 조치:** job 수가 수백만이 되면 이 두 쿼리는 15초 주기를 감당하지 못한다. 그때는 (a) 대사 태스크 쪽(1분 주기)으로 옮기거나, (b) `id > lastId` 커서 배치로 나눠 한 주기에 일부 구간만 훑고 결과를 누적하는 방식으로 바꿔야 한다. `LedgerReconciliationTask` 가 이미 (b) 를 쓰고 있으므로 옮길 자리는 정해져 있다. `credit_snapshot_duration_seconds` 를 붙여 둔 것이 그 시점을 알아채기 위한 것이다.

### 핵심 코드 읽기

#### `DomainSnapshotTask` — 여섯 값을 한 번에 읽고 한 번에 발행한다

```kotlin
@Scheduled(fixedDelayString = $$"${app.scheduling.snapshot-interval-millis:15000}")
fun takeSnapshot() {
    val startedAt = clock.instant()
    try {
        val outstandingHoldCount = jobRepository.countByStatusNotIn(PENDING_EXCLUDED_STATUSES)
        val outstandingHoldAmount = jobRepository.sumHoldAmountByStatusNotIn(PENDING_EXCLUDED_STATUSES)
        val oldestCreatedAt = jobRepository.findOldestCreatedAtByStatusNotIn(PENDING_EXCLUDED_STATUSES)
        val negativeBalanceOrgs = organizationRepository.countByBalanceLessThan(0L)
        val jobsWithoutHold = jobRepository.countJobsWithoutHoldEntry()
        val unsettledTerminalJobs = jobRepository.countUnsettledTerminalJobs()

        val takenAt = clock.instant()
        val oldestPendingAgeSeconds = oldestCreatedAt
            ?.let { Duration.between(it, takenAt).seconds }
            ?: 0L

        log.info("도메인 스냅샷 완료: ...")
        eventPublisher.publishEvent(DomainSnapshotTaken(...))
    } catch (e: RuntimeException) {
        log.error("도메인 스냅샷 주기 실패, 이번 주기는 발행하지 않는다", e)
    }
}

companion object {
    private val PENDING_EXCLUDED_STATUSES = listOf(JobStatus.COMPLETED, JobStatus.REFUNDED)
}
```

`Instant.now()` 가 아니라 주입받은 `clock.instant()` 를 쓴다. 1단계에서 `ClockConfig` 를 만든 이유가 여기서 한 번 더 쓰인다 — 테스트에서 시계를 고정해야 `oldestPendingAgeSeconds` 를 `90` 같은 정확한 값으로 단언할 수 있다. 벽시계를 쓰면 "대략 90초쯤"이라는 느슨한 단언밖에 못 한다.

미결의 정의가 `PENDING_EXCLUDED_STATUSES` 라는 **여집합 목록 하나**로만 표현되는 것도 의도적이다. 상태가 하나 늘어날 때(예: CANCELLED) 그것이 미결인지 아닌지를 한 자리에서만 결정하면 된다. 미결 목록을 나열했다면 새 상태는 조용히 집계에서 빠졌을 것이다 — 그리고 빠진 것은 셀 수 없다.

#### JPQL — 전부 H2(MODE=MySQL)와 MySQL 양쪽에서 돈다

```kotlin
@Query(
    """
    SELECT MIN(j.createdAt) FROM Job j
    WHERE j.status NOT IN :statuses
    """
)
fun findOldestCreatedAtByStatusNotIn(@Param("statuses") statuses: Collection<JobStatus>): Instant?

@Query(
    """
    SELECT COUNT(j) FROM Job j
    WHERE NOT EXISTS (
        SELECT 1 FROM LedgerEntry l
        WHERE l.jobId = j.id AND l.type = LedgerType.HOLD
    )
    """
)
fun countJobsWithoutHoldEntry(): Long
```

네이티브 쿼리를 쓰지 않은 것이 제약이자 안전장치다. 테스트는 H2, 운영은 MySQL 이라 네이티브 SQL 은 한쪽에서만 검증된다. JPQL 로 묶어 두면 테스트가 통과한 쿼리가 운영에서도 같은 의미로 번역된다. 반환 타입을 `Instant?` 로 둔 것도 같은 맥락이다 — 미결이 없으면 `MIN` 은 `NULL` 이고, 그 null 을 0 으로 바꾸는 결정은 SQL 이 아니라 태스크의 Kotlin 코드가 한다. "미결 없음 = 나이 0" 은 도메인 판단이지 쿼리의 기본값이 아니다.

#### `DomainSnapshotMetrics` — 최중요 게이지

```kotlin
// 이 단계의 단일 최중요 지표다. 워커가 죽든, 스케줄러가 죽든, Redis 가 죽든,
// 스텁 API 가 무한히 지연되든 파이프라인이 멈추면 이 값 하나가 무한히 오른다.
// 카운터로는 만들 수 없는 지표다 — 아무 코드도 안 불리는 채로 늙어가는 것을 재기 때문이다.
Gauge.builder(OLDEST_PENDING_AGE_METRIC, oldestPendingAgeSeconds) { it.get().toDouble() }
    .description("가장 오래된 미결 job 의 나이(초). createdAt 기준이라 재시도로 리셋되지 않는다")
    .baseUnit("seconds")
    .register(registry)
```

`AtomicLong` 을 컴포넌트 필드로 두고 생성자에서 한 번만 등록하는 것은 1단계의 Gauge 함정과 같은 이유다. 태그는 붙이지 않는다 — `organizationId` 를 넣고 싶은 유혹이 가장 큰 지표가 바로 이것("어느 조직의 job 이 묶여 있나")인데, 그건 로그의 일이다.

### 테스트가 보장하는 것

- **`DomainSnapshotTaskTest` (`@DataJpaTest`, 실제 리포지토리, 고정 시계)** — 여섯 값이 실제 DB 상태와 맞는지 확인한다. job 이 없으면 전부 0 이고 나이도 0 이다. HOLDING 1건(100 크레딧, 90초 전 생성)·COMPLETED 1건·REFUNDED 1건을 넣으면 `outstandingHoldCount == 1`, `outstandingHoldAmount == 100`, `oldestPendingAgeSeconds == 90` 이다 — 종결된 둘이 빠진다는 뜻이다. **FAILED 도 미결에 포함된다**는 것은 별도 테스트로 못 박았고, "300초 전에 생성돼 세 번 상태가 바뀐 FAILED job" 과 "10초 전 HOLDING" 을 같이 두면 나이가 `300` 이 나오는 테스트가 `createdAt` 기준 선택을 지킨다. `updatedAt` 기준으로 바꾸면 이 테스트만 깨진다.

- **불변식 3종** — 잔액을 음수로 만든 조직 1개면 `negativeBalanceOrgs == 1`, HOLD 원장 없이 저장한 job 이면 `jobsWithoutHold == 1`, CONFIRM 원장 없는 COMPLETED job 이면 `unsettledTerminalJobs == 1` 이고 CONFIRM 을 넣으면 0 이 된다. REFUNDED/REFUND 쪽도 같은 방식으로 확인한다.

- **부분 스냅샷 금지** — 리포지토리를 mock 으로 갈아 끼워 앞의 세 쿼리는 값을 돌려주고 네 번째(`countJobsWithoutHoldEntry`)에서 예외를 던지게 한 뒤, **이벤트가 하나도 발행되지 않는 것**을 단언한다. 앞 세 값을 이미 읽었다는 점이 중요하다 — "실패하면 아무것도 안 한다"가 아니라 "절반을 읽었어도 버린다"를 검증하는 것이다.

- **`DomainSnapshotMetricsTest` (`SimpleMeterRegistry`)** — 이벤트 하나로 게이지 6개가 갱신되고, 두 번째 이벤트로 전부 마지막 값에 덮이며, `cycles` 카운터는 정확히 2 가 된다. `duration` 타이머가 이벤트의 250ms 를 그대로 기록하고, `staleness` 는 이벤트 전 `-1.0` 이었다가 이벤트 후 시계를 30초 당기면 `30.0` 이 된다.

- **함정: `createdAt` 과 `status` 는 `protected set` 이다.** 테스트에서 job 을 과거로 밀거나 상태를 바꿀 때 세터를 부를 수 없다. 상태는 프로덕션과 같은 전이 메서드(`startProcessingIfAttemptMatches` → `failIfProcessing` → `refundIfFailed`)를 실제로 호출해서 만든다 — 이러면 "테스트가 만든 상태"와 "코드가 만드는 상태"가 같다는 것이 덤으로 보장된다. `createdAt` 만 `ReflectionTestUtils.setField` 로 민다(`IdempotencyKeyCleanupTaskTest` 가 쓰던 방법 그대로). 리플렉션은 시간을 조작할 때만 쓰고, 상태 전이에는 쓰지 않는다.

- **`FixedMutableClock` 을 `support/` 로 꺼냈다.** 1단계에서 `LedgerReconciliationMetricsTest` 안의 private 클래스였는데, 3단계의 두 테스트가 같은 시계를 필요로 해서 `RecordingEventPublisher` 옆으로 옮겼다. 1단계의 기존 단언은 그대로다.

### 직접 확인하는 방법

```
curl localhost:8080/actuator/prometheus | grep -E "credit_hold|credit_job_oldest|credit_invariant|credit_snapshot"
```

아래는 테스트 프로파일(H2, `app.scheduling.enabled=false`, 스냅샷이 아직 한 번도 안 돈 상태)에서 `/actuator/prometheus` 를 실제로 호출해 받은 응답이다:

```
# HELP credit_hold_outstanding_amount 미결 job 에 묶여 있는 크레딧 합계
# TYPE credit_hold_outstanding_amount gauge
credit_hold_outstanding_amount{application="credit_system"} 0.0
# HELP credit_hold_outstanding_count 미결 job 수. COMPLETED/REFUNDED 가 아닌 모든 job 이며 FAILED 도 포함한다
# TYPE credit_hold_outstanding_count gauge
credit_hold_outstanding_count{application="credit_system"} 0.0
# HELP credit_invariant_jobs_without_hold HOLD 원장이 없는 job 수. 0이 아니면 즉시 사고다
# TYPE credit_invariant_jobs_without_hold gauge
credit_invariant_jobs_without_hold{application="credit_system"} 0.0
# HELP credit_invariant_negative_balance_orgs 잔액이 음수인 조직 수. 0이 아니면 즉시 사고다
# TYPE credit_invariant_negative_balance_orgs gauge
credit_invariant_negative_balance_orgs{application="credit_system"} 0.0
# HELP credit_invariant_unsettled_terminal_jobs 종결됐는데 정산 원장이 없는 job 수. 0이 아니면 즉시 사고다
# TYPE credit_invariant_unsettled_terminal_jobs gauge
credit_invariant_unsettled_terminal_jobs{application="credit_system"} 0.0
# HELP credit_job_oldest_pending_age_seconds 가장 오래된 미결 job 의 나이(초). createdAt 기준이라 재시도로 리셋되지 않는다
# TYPE credit_job_oldest_pending_age_seconds gauge
credit_job_oldest_pending_age_seconds{application="credit_system"} 0.0
# HELP credit_snapshot_cycles_total 완료한 도메인 스냅샷 주기의 누적 수
# TYPE credit_snapshot_cycles_total counter
credit_snapshot_cycles_total{application="credit_system"} 0.0
# HELP credit_snapshot_duration_seconds 도메인 스냅샷 한 주기에 걸린 시간
# TYPE credit_snapshot_duration_seconds summary
credit_snapshot_duration_seconds_count{application="credit_system"} 0
credit_snapshot_duration_seconds_sum{application="credit_system"} 0.0
# HELP credit_snapshot_duration_seconds_max 도메인 스냅샷 한 주기에 걸린 시간
# TYPE credit_snapshot_duration_seconds_max gauge
credit_snapshot_duration_seconds_max{application="credit_system"} 0.0
# HELP credit_snapshot_staleness_seconds 마지막 성공 스냅샷으로부터 흐른 시간(초). 스냅샷 자체가 멈춘 것을 탐지한다
# TYPE credit_snapshot_staleness_seconds gauge
credit_snapshot_staleness_seconds{application="credit_system"} -1.0
```

**값이 전부 0 이고 `staleness` 만 -1 인 이 출력이 오히려 이 지표군의 성질을 잘 보여준다.** 2단계의 카운터는 0 이 "아직 아무 일도 없었다"는 애매한 뜻이었지만, 여기서 0 은 **"묶인 돈이 없고 불변식이 전부 성립한다"** 는 명확한 정상 선언이다. 이 지표군에서는 **0 이 목표값이고, 0 이 아닌 것이 뉴스다.** 그리고 `staleness = -1` 은 "그 정상 선언이 언제 찍힌 것인지 아직 모른다"는 뜻이라, 두 값을 함께 봐야 비로소 "지금 정상"이라고 말할 수 있다.

실제 서버를 띄우고(`app.scheduling.enabled=true`, MySQL/Redis 필요) job 을 몇 건 밀어 넣으면 `credit_hold_outstanding_count` 와 `credit_hold_outstanding_amount` 가 오르고, 워커가 처리를 끝내면 다시 0 으로 내려온다. 워커를 죽여 놓고 job 을 넣으면 `credit_job_oldest_pending_age_seconds` 가 15초마다 계속 증가하는 것을 볼 수 있다 — 5단계의 장애 주입에서 이 곡선을 직접 그린다.

PromQL 로 옮기면 이렇게 된다:

```
# 파이프라인이 멈췄나 (P2)
credit_job_oldest_pending_age_seconds > 300

# 불변식이 깨졌나 (P1, 셋 중 하나라도)
credit_invariant_negative_balance_orgs > 0
  or credit_invariant_jobs_without_hold > 0
  or credit_invariant_unsettled_terminal_jobs > 0

# 관측 자체가 멈췄나 (P2)
credit_snapshot_staleness_seconds > 45

# 묶인 돈의 추세
credit_hold_outstanding_amount
```

### 이 단계에서 남는 것

- **L0·L1·L3 이 갖춰졌다.** 1단계이 L1(원장 불변식), 2단계가 L3(방어 발동), 3단계이 L0(묶인 돈과 그 나이)에 더해 L1 을 세 개 더 얹었다. L2(흐름 — 상태별 job 수 분포와 단계별 소요 시간)는 여전히 비어 있는데, 지금 지표들로 "멈췄다"는 알 수 있고 "어느 단계에서 얼마나 느린가"는 아직 모른다.
- **노출은 되지만 아직 아무도 긁어가지 않는다.** 지금까지 만든 지표는 전부 `/actuator/prometheus` 에 문자열로 떠 있을 뿐이다. 스크레이프하는 주체도, 저장하는 곳도, 그리는 대시보드도 없다. 알람 규칙은 이 문서의 표에만 있다.
- **4단계는 노출 경계와 카디널리티 가드다.** 어떤 엔드포인트를 누구에게 열 것인가(`/actuator/prometheus` 를 공개 포트에 두면 시스템 내부가 그대로 노출된다), 그리고 앞으로 태그가 늘어날 때 시계열 상한을 어떻게 지킬 것인가를 정한다.
- **5단계에서 Prometheus/Grafana 를 붙인다.** docker-compose 로 스크레이프·저장·대시보드·알람 규칙을 코드로 남기고, Redis·MySQL 을 실제로 죽여 이 지표들이 사고를 어떻게 그리는지 확인한다. `oldest_pending_age` 가 실제로 무한히 오르는 곡선을 보는 것이 그 단계의 목표다.
- **여전히 감지일 뿐 교정하지 않는다.** step6 부터 이어지는 원칙 그대로다. `credit_invariant_negative_balance_orgs` 가 1 이 되어도 자동으로 아무것도 고치지 않는다.

---

## 명령어

```
# 이 브랜치에서 전체 테스트 실행 (Docker 필요 — MySQL + Redis Testcontainers, 164개 테스트)
./gradlew test

# 정적 분석
./gradlew ktlintCheck
./gradlew detekt

# 도메인/서비스/스케줄러 코드가 Micrometer 를 모르는지 직접 확인
grep -rl "io.micrometer" src/main/kotlin
# (observability/ 아래 세 파일만 나와야 한다)

# 지표 하나만 골라 실제 배선까지 확인
./gradlew test --tests "com.example.credit_system_kotlin.observability.PrometheusEndpointTest"

# 방어 카운터 단위 테스트
./gradlew test --tests "com.example.credit_system_kotlin.observability.DefenseMetricsTest"

# 진짜 경쟁 상태에서 카운터 합이 맞는지 (Docker 필요)
./gradlew test --tests "com.example.credit_system_kotlin.observability.DefenseMetricsConcurrencyTest"

# 스냅샷 게이지 — 쿼리가 실제 DB 상태와 맞는지, 게이지 갱신이 맞는지
./gradlew test --tests "com.example.credit_system_kotlin.global.scheduling.DomainSnapshotTaskTest"
./gradlew test --tests "com.example.credit_system_kotlin.observability.DomainSnapshotMetricsTest"
```