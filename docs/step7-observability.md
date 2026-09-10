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
| 4 | 노출 경계와 카디널리티 가드 — 관리 포트 분리, `/actuator/prometheus` 를 누구에게 열지, 레지스트리 수준의 시계열 상한 | — | 완료 |
| 5 | docker-compose 관측 스택 — Prometheus + Grafana 를 띄워 스크레이프·대시보드·알람 규칙(P1/P2)을 코드로 남긴다 | — | 완료 |
| 6 | 장애 주입 — 워커·스케줄러·Redis 를 실제로 죽이고 원장을 손으로 깨서, 어느 지표가 반응하고 어느 지표가 침묵하는지 확인한다 | — | 완료 |

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

## 4단계 — 노출 경계와 카디널리티 가드

1~3단계는 지표를 만드는 단계였다. 이 단계는 아무 지표도 만들지 않는다. **이미 만든 지표를 누구에게 보여줄 것인가**, 그리고 **앞으로 지표가 늘어날 때 시계열 폭발을 어디서 막을 것인가** 두 가지를 정한다.

### 논지: `/actuator/prometheus` 는 시스템 내부의 전문(全文)이다

지금까지 만든 응답 하나를 다시 보자.

```
credit_invariant_negative_balance_orgs{application="credit_system"} 0.0
credit_hold_outstanding_amount{application="credit_system"} 12300.0
credit_defense_total{application="credit_system",outcome="rejected",point="hold_balance"} 9.0
```

각각 "잔액이 음수인 조직이 몇 개인가", "지금 묶여 있는 돈이 얼마인가", "잔액 부족으로 몇 건이 거절됐는가"를 말한다. 이건 운영자를 위한 정보이지 사용자를 위한 정보가 아니다. 묶인 크레딧 총액은 서비스 규모를 그대로 드러내고, `rejected` 카운터의 추세는 고객사의 잔액 사정을 드러낸다. 인증도 없다.

그런데 지금 이 엔드포인트는 공개 API(`/api/jobs`, `/api/organizations/me/charge`)와 **같은 8080 포트**에 열려 있다. 공개 API 를 노출하려면 8080 을 열어야 하고, 8080 을 여는 순간 `/actuator/prometheus` 도 같이 열린다. 경로만 다를 뿐 도달 가능성은 완전히 같다.

이걸 애플리케이션 코드로 막는 방법(Spring Security 로 `/actuator/**` 에 인증을 거는 것)도 있지만, 이 단계가 택한 답은 다르다. **노출 경계는 애플리케이션 설정이 아니라 배포 결정이다.**

관리 엔드포인트를 별도 포트로 옮기고, 그 포트를 컨테이너 네트워크 밖으로 publish 하지 않으면 된다. 그러면 인증 코드도, 필터 체인도, 잘못 설정될 여지도 없다. 컨테이너 네트워크 안에 있는 Prometheus 만 `app:8081` 에 닿고, 호스트에서는 애초에 그런 포트가 존재하지 않는다. 5단계의 `docker-compose.yml` 이 이 결정의 실물이다.

### 왜 `application.yml` 이 아니라 배포 쪽에서 정하는가

`management.server.port: 8081` 을 `application.yml` 에 쓰면 간단하다. 그렇게 하지 않았다.

포트를 나눌지 말지는 **그 애플리케이션이 어디에 놓이느냐**에 달린 결정이다. 로컬에서 `./gradlew bootRun` 으로 띄우고 `curl localhost:8080/actuator/prometheus` 로 지표를 확인하는 사람에게 포트 분리는 순수한 불편이다 — 로컬에는 격리할 네트워크 경계 자체가 없기 때문이다. 반대로 compose 나 쿠버네티스에 올라가면 분리는 필수다. 같은 코드가 두 곳에 다 놓이는데, `application.yml` 은 한 값만 가질 수 있다.

그래서 `application.yml` 은 기본값(포트 분리 없음)을 유지하고, 분리는 배포 쪽에서 환경변수로 준다:

```yaml
# deploy/observability/docker-compose.yml
app:
  environment:
    MANAGEMENT_SERVER_PORT: 8081
  ports:
    - "8080:8080"      # 8081 은 없다
```

`src/main` 은 이번 단계에서 `MetricsCardinalityConfig.kt` 신규 파일 하나 외에 한 글자도 바뀌지 않았다. 노출 경계가 바뀌었는데 애플리케이션 코드가 안 바뀐 것이 이 결정의 요점이다.

이 선택의 대가는 "설정 파일만 봐서는 경계가 있는지 알 수 없다"는 것이고, 그래서 `ManagementPortBoundaryTest` 가 필요하다. 경계는 코드에 없지만, 경계가 **성립한다는 사실**은 테스트로 코드에 남는다.

### 카디널리티 가드는 두 겹이다

2단계에서 이미 카디널리티를 한 번 막았다. `DefensePoint`/`DefenseOutcome` 을 enum 으로 두어 태그 값 집합이 컴파일 타임에 닫히게 한 것이다. 그건 **코드 수준** 가드다. 하지만 그 가드는 오늘 존재하는 계측에만 걸린다. 내일 누가 이런 코드를 새로 짜면 enum 은 아무 말도 하지 않는다:

```kotlin
Counter.builder("credit.job.created").tag("organizationId", orgId.toString()).register(registry)
```

이 한 줄이 조직 수만큼의 시계열을 만든다. 컴파일도 되고, 테스트도 통과하고, 리뷰에서 놓치기도 쉽다. 사고는 조직이 100개일 때가 아니라 10만 개가 됐을 때 터진다.

4단계가 그 아래 계층을 깐다. **레지스트리 수준** 가드는 미터가 등록되는 마지막 문에서 판정하므로, 어떤 코드가 어떻게 짜였든 반드시 통과해야 한다. 2단계 문서가 멱등키를 두고 쓴 구조와 같다 — "1차 멱등 조회(애플리케이션)로 빠르게 걸러내고, 최종 판정은 DB 유니크 제약이 한다." 여기서도 enum 이 빠르게 걸러내고, 최종 판정은 레지스트리가 한다. 위층 가드는 사람이 지켜야 성립하고, 아래층 가드는 사람이 잊어도 성립한다.

### 파일별 변경 목록

| 파일 | 신규/수정 | 무엇을, 왜 |
|---|---|---|
| `observability/MetricsCardinalityConfig.kt` | 신규 | `MeterFilter` 빈 5개. 식별자 태그 거부, `credit.` 접두 미터의 태그 값 상한 3종, 전체 미터 수 상한 |
| `observability/MetricsCardinalityConfigTest.kt` | 신규 | `SimpleMeterRegistry` 순수 단위 테스트 5개 + 실제 컨텍스트 배선 확인 3개 |
| `observability/ManagementPortBoundaryTest.kt` | 신규 | 관리 포트를 분리한 상태에서 애플리케이션 포트의 `/actuator/prometheus` 가 404 임을 못 박는다 |
| `deploy/observability/docker-compose.yml` | 신규(5단계) | `MANAGEMENT_SERVER_PORT=8081` 을 주고 8080 만 publish 한다. 노출 경계의 실물 |

`application.yml` 은 건드리지 않았다. 이 단계의 결정 중 하나가 "여기에 쓰지 않는다"였기 때문이다.

### 핵심 코드 읽기

#### 식별자 태그 거부 — 거부하되 죽이지 않는다

```kotlin
@Bean
fun denyIdentifierTagsMeterFilter(): MeterFilter = object : MeterFilter {

    private val warnedOnce = ConcurrentHashMap.newKeySet<String>()

    override fun accept(id: Meter.Id): MeterFilterReply {
        val offendingKey = FORBIDDEN_TAG_KEYS.firstOrNull { id.getTag(it) != null }
            ?: return MeterFilterReply.NEUTRAL
        if (warnedOnce.add("${id.name}/$offendingKey")) {
            log.warn(
                "식별자 태그가 붙은 미터를 거부했다: name={}, tag={}. " +
                    "개별 식별은 로그의 몫이다 — 지표에 붙이면 카디널리티가 폭발한다",
                id.name, offendingKey
            )
        }
        return MeterFilterReply.DENY
    }
}
```

`organizationId`, `organization_id`, `jobId`, `job_id`, `idemKey`, `idem_key` 여섯 키를 막는다. 카멜과 스네이크를 둘 다 적는 이유는 Micrometer 태그 키 표기가 코드마다 갈리기 때문이다 — 한쪽만 막으면 다른 쪽으로 새어 나간다.

**거부된 미터에 대해 Micrometer 는 예외 대신 noop 미터를 돌려준다.** 그래서 `counter.increment()` 를 부르는 도메인 코드는 아무 일도 없었다는 듯 계속 돈다. 2단계의 원칙 그대로다 — 사전 등록에 없는 조합이 들어와도 예외를 던지는 대신 세면서 경고했던 것과 같은 판단이다. 관측 가드가 도메인 호출을 죽이면, 관측을 켠 것이 사고의 원인이 된다.

`warnedOnce` 는 중복 억제다. 이 필터는 미터를 등록하려 할 때마다 불리므로, 같은 이름으로 반복 호출되는 계측이 있으면 로그가 초당 수백 줄로 쏟아진다. 이름+태그키 조합당 한 번만 경고한다.

#### 태그 값 상한 — 왜 32인가

```kotlin
@Bean
fun creditPointTagLimitMeterFilter(): MeterFilter =
    MeterFilter.maximumAllowableTags(CREDIT_PREFIX, "point", MAX_TAG_VALUES, MeterFilter.deny())
```

`point`, `outcome`, `detector` 세 태그 키에 각각 같은 필터를 건다. 지금 실제 값은 **point 7개, outcome 7개, detector 2개**다(2단계의 14개 조합이 이 값들의 조합이다).

32는 "enum 이 지금의 네 배로 커져도 걸리지 않는" 값이다. 방어 지점이 7개에서 20개, 30개로 늘어나는 것은 시스템이 커지면 있을 수 있는 일이고, 그때마다 상한을 만지게 하면 가드가 방해물이 된다. 반면 32를 넘는 값이 관측된다면 그건 enum 이 커진 것이 아니라 **누군가 자유 문자열을 태그에 넣었다**는 뜻이다 — 예외 메시지, 사용자 입력, ID 같은 것들. 그 순간이 정확히 막아야 할 순간이다.

상한에 걸린 태그 **값**만 거부되고, 이미 등록된 32개는 그대로 산다. 즉 사고가 나도 기존 대시보드와 알람은 계속 작동한다.

#### 전체 미터 수 상한 — 2000의 근거

```kotlin
@Bean
fun maximumMetricsMeterFilter(): MeterFilter = MeterFilter.maximumAllowableMetrics(MAX_METERS)
```

앞의 두 필터를 우회하는 길이 있다. 태그가 아니라 **미터 이름 자체**에 식별자를 박는 것이다(`credit.job.12345.duration`). 이건 태그 필터로는 못 잡는다. 마지막 그물이 필요하다.

기준값을 실측했다. 5단계 스택을 띄워 실제 MySQL·Redis·Tomcat 위에서 돈 애플리케이션의 `/actuator/prometheus` 는 이랬다:

```
$ docker compose ... exec app curl -s http://localhost:8081/actuator/prometheus \
    | awk '/^# TYPE/{t++} !/^#/{s++} END{print "TYPE(미터 이름) =", t, " 시계열 =", s}'
TYPE(미터 이름) = 100  시계열 = 305
```

미터 이름 100개, 시계열 305개다. 이 중 도메인 지표는 20개 남짓이고 나머지는 전부 JVM·Tomcat·Hikari·Logback 기본 미터다. 2000은 실측치의 6배 이상 여유를 두면서도, "시계열이 수천으로 늘었다"는 명백한 이상은 잡는 값이다. 근거 없는 숫자를 피하려면 이 실측이 먼저 있어야 한다 — 기준값을 모르면 어떤 상한도 임의의 숫자다.

#### `ManagementPortBoundaryTest` — 경계가 존재한다는 증거

```kotlin
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"]
)
class ManagementPortBoundaryTest @Autowired constructor(
    private val restTemplate: TestRestTemplate
) {

    @LocalServerPort
    private var serverPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    @Test
    fun `애플리케이션 포트에서는 prometheus 엔드포인트에 닿을 수 없다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$serverPort/actuator/prometheus",
            String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `관리 포트에서는 prometheus 엔드포인트가 도메인 지표를 노출한다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$managementPort/actuator/prometheus",
            String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("credit_defense_total")
        assertThat(response.body).contains("credit_job_oldest_pending_age_seconds")
    }
}
```

`management.server.port=0` 은 "임의의 빈 포트에 관리 컨텍스트를 따로 띄우라"는 뜻이다. `@LocalManagementPort` 가 그 실제 포트를 받아 온다. **404 단언이 이 테스트의 전부다.** 200 도 아니고 401/403 도 아니다 — 그 경로가 애플리케이션 포트에는 아예 존재하지 않는다는 뜻이다.

컨텍스트가 `RANDOM_PORT` 에 `management.server.port` 프로퍼티까지 달라서 다른 `@SpringBootTest` 와 컨텍스트 캐시를 공유하지 않는다. 테스트 전체 시간이 조금 늘지만, 경계를 실제 소켓 두 개로 확인하는 값이 그보다 크다. MockMvc 로는 이 단언을 쓸 수 없다 — MockMvc 에는 포트가 없기 때문이다.

### 테스트가 보장하는 것

- **`MetricsCardinalityConfigTest` (순수 단위, `SimpleMeterRegistry`)** — `organizationId` 태그를 단 카운터를 등록하면 `registry.find(...)` 로 찾을 수 없다(거부됐다). 금지 키 6개 전부에 대해 레지스트리가 비어 있다. **거부된 미터에 `increment()` 를 해도 예외가 없고 `count()` 는 0 이다** — 이 단언이 "가드가 도메인을 죽이지 않는다"를 지킨다. `point` 태그 값을 32개까지 넣으면 32개가 등록되고, 33번째를 넣어도 여전히 32개다.
- **정상 지표는 통과한다** — `point`/`outcome` 만 단 `credit.defense` 카운터는 그대로 등록된다. 가드가 지나치게 세면 아무 에러 없이 대시보드만 비는데, 그건 알람이 울리지 않는 종류의 사고다.
- **`MetricsCardinalityWiringTest` (`@SpringBootTest`)** — 실제 컨텍스트의 `MeterRegistry` 에서 `credit.defense` 카운터가 **정확히 14개** 보인다. 즉 필터가 붙은 뒤에도 2단계의 사전 등록이 온전하다. 같은 레지스트리에 `organizationId` 태그 미터를 넣으려 하면 거부된다 — 필터가 실제로 배선됐다는 뜻이다.
- **`ManagementPortBoundaryTest`** — 두 포트가 서로 다르고, 애플리케이션 포트는 404, 관리 포트는 200 이며 `credit_defense_total` 과 `credit_job_oldest_pending_age_seconds` 를 담고 있다. 누가 `management.server.port` 설정을 되돌리면 첫 두 테스트가 동시에 깨진다.

### 직접 확인하는 방법

로컬(`./gradlew bootRun`)은 포트가 그대로 하나다:

```
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/actuator/prometheus
# 200
```

5단계 스택(관리 포트 분리 + publish 안 함)에서는:

```
$ curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/actuator/prometheus
404
$ curl -s -o /dev/null -w "%{http_code}\n" -H "X-Organization-Id: 1" localhost:8080/api/jobs
200
```

**같은 포트에서 공개 API 는 200 이고 액추에이터는 404 다.** 8081 은 호스트에 바인딩되지 않았으므로 `localhost:8081` 은 아예 연결이 거부된다. 지표를 눈으로 보려면 컨테이너 안으로 들어가야 한다:

```
$ docker compose -f deploy/observability/docker-compose.yml exec app \
    curl -s http://localhost:8081/actuator/prometheus | grep -E "^credit_(hold|invariant)"
credit_hold_outstanding_amount{application="credit_system"} 0.0
credit_hold_outstanding_count{application="credit_system"} 0.0
credit_invariant_jobs_without_hold{application="credit_system"} 0.0
credit_invariant_negative_balance_orgs{application="credit_system"} 0.0
credit_invariant_unsettled_terminal_jobs{application="credit_system"} 0.0
```

카디널리티 가드가 살아 있는지는 로그로 확인한다. 금지 태그를 단 미터를 등록하려 하면 필터가 WARN 을 한 번 남기고(이름+태그키 조합당 한 번) 미터는 등록되지 않는다:

```
식별자 태그가 붙은 미터를 거부했다: name=credit.custom, tag=organizationId.
개별 식별은 로그의 몫이다 — 지표에 붙이면 카디널리티가 폭발한다
```

그리고 `/actuator/prometheus` 에 `credit_custom` 이라는 이름은 나타나지 않는다. 이 두 가지를 `MetricsCardinalityConfigTest` 와 `MetricsCardinalityWiringTest` 가 각각 단언한다.

### 이 단계에서 남는 것

- **경계는 배포에만 있다.** `application.yml` 만 보면 이 시스템에 노출 경계가 있는지 알 수 없다. 누가 쿠버네티스로 옮기면서 8081 을 Service 에 그대로 노출하면 경계는 사라지고, `ManagementPortBoundaryTest` 는 여전히 초록이다. 테스트가 지키는 것은 "포트를 나누면 실제로 갈라진다"이지 "배포가 그 포트를 안 연다"가 아니다.
- **인증은 여전히 없다.** compose 네트워크 안에 들어온 무언가는 `app:8081` 을 자유롭게 읽는다. 네트워크 경계 하나에 의존하는 구조이고, 다중 테넌트 클러스터라면 이것으로 부족하다.
- **`/actuator/health` 도 같이 옮겨갔다.** 관리 포트를 나누면 헬스체크도 그 포트로 간다. 5단계의 컨테이너 healthcheck 가 `localhost:8081/actuator/health` 를 부르는 이유이고, 동시에 트레이드오프이기도 하다(5단계 참고).
- **가드는 등록을 막을 뿐 이름 설계를 대신하지 않는다.** `maximumAllowableMetrics` 에 걸리면 그 시점 이후의 **모든** 새 미터가 거부된다. 즉 상한에 닿는 순간 정상 지표도 함께 못 들어온다 — 그물이지 해법이 아니다.
- **5단계에서 이 결정의 실물을 만든다.** 관리 포트를 publish 하지 않는 compose 를 짜고, Prometheus 가 컨테이너 네트워크 안에서 `app:8081` 을 긁게 한다.

---

## 5단계 — docker-compose 관측 스택

1~4단계가 만든 것은 전부 애플리케이션 안에 있다. 지표는 문자열로 떠 있고, 알람 기준은 문서의 표에만 있고, "이 값이 이렇게 움직이면 사고"라는 판단은 사람 머릿속에만 있다. 이 단계는 그 전부를 **저장소 안의 파일**로 옮긴다. 스크레이프 설정, 알람 규칙, 대시보드가 코드가 되면 리뷰할 수 있고, 되돌릴 수 있고, 무엇보다 **실제로 돌려서 틀렸는지 확인할 수 있다.**

### 구성

```
deploy/observability/
  docker-compose.yml                                   # 서비스 5개
  Dockerfile                                           # 앱 이미지 (bootJar 를 COPY)
  prometheus/prometheus.yml                            # 스크레이프 설정
  prometheus/rules/credit.rules.yml                    # 알람 규칙 10개
  grafana/provisioning/datasources/prometheus.yml      # 데이터소스 (uid: prometheus)
  grafana/provisioning/dashboards/dashboards.yml       # 대시보드 프로비저너
  grafana/dashboards/credit-domain.json                # 대시보드 4행
  scripts/seed.sh                                      # 조직 1개 INSERT
  scripts/smoke.sh                                     # 충전 + job + 중복/잔액부족 유발
  README.md                                            # 띄우는 법 한 페이지
```

서비스는 `mysql`(8.4), `redis`(7), `app`, `prometheus`(v3.1.0), `grafana`(11.5.1) 다섯 개다.

### 포트 publish 규칙 — 4단계 결정의 실물

```yaml
  app:
    environment:
      MANAGEMENT_SERVER_PORT: 8081
    ports:
      # 8080 만 호스트로 나간다. 8081 은 compose 네트워크 안에서만 보이고,
      # Prometheus 가 app:8081 로 긁는다. 호스트에서는 /actuator/* 에 닿을 수 없다.
      - "8080:8080"
```

세 가지 규칙이 있고 각각 이유가 다르다.

| 서비스 | publish | 이유 |
|---|---|---|
| `app` | **8080 만** | 8081(관리 포트)을 내보내지 않는 것이 4단계 노출 경계 그 자체다. Prometheus 는 compose 네트워크 안에서 `app:8081` 을 긁는다 |
| `mysql`, `redis` | **하지 않음** | 이 개발 머신은 로컬 3306/6379 에 이미 MySQL/Redis 가 떠 있다. publish 하면 포트 충돌로 스택이 아예 안 뜬다. 디버깅은 `docker compose exec mysql mysql ...` 로 한다 |
| `prometheus`, `grafana` | 9090, 3000 | 사람이 브라우저로 봐야 하는 것들이다 |

`mysql`/`redis` 를 안 여는 결정에는 부수 효과가 하나 더 있다. 스택 안의 DB 는 호스트의 어떤 도구로도 실수로 건드릴 수 없다 — 로컬 MySQL 에 연결한 채로 `TRUNCATE` 를 치는 종류의 사고가 구조적으로 불가능해진다.

### 앱 이미지 — 멀티스테이지 빌드를 쓰지 않은 이유

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
# 글롭(build/libs/*.jar)을 쓰면 -plain.jar 까지 잡힌다. 실행 가능한 fat jar 이름을 못 박는다.
COPY build/libs/credit-system-kotlin-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

컨테이너 안에서 Gradle 을 돌리는 멀티스테이지 빌드가 "정석"이지만, 여기서는 세 가지 이유로 피했다.

1. **느리다.** 이미지를 다시 만들 때마다 의존성을 새로 받는다. 관측 스택은 고치고 다시 띄우기를 반복하는 물건이다.
2. **JDK 문제를 컨테이너 안으로 끌고 들어온다.** 이 저장소는 detekt 때문에 이미 "Gradle 데몬 JDK 26 / 프로젝트 toolchain 17" 분리를 안고 있다(`build.gradle.kts` 주석 참고). 이걸 이미지 안에서 재현할 이유가 없다.
3. **목적이 다르다.** 이 이미지는 배포물이 아니라 관측 실험대다. 빌드 재현성은 호스트의 `./gradlew` 가 이미 담당한다.

대가는 **`./gradlew bootJar` 를 먼저 돌려야 한다**는 것이고, `README.md` 첫 줄에 그렇게 적혀 있다. jar 이름을 글롭이 아니라 정확한 이름으로 쓴 것은 `build/libs/` 에 `credit-system-kotlin-0.0.1-SNAPSHOT.jar` 와 `credit-system-kotlin-0.0.1-SNAPSHOT-plain.jar` 두 개가 있어서다 — 글롭은 `-plain.jar`(fat jar 가 아니라 클래스만 든 jar)를 잡을 수 있고, 그러면 `no main manifest attribute` 로 죽는다.

`build.context` 는 저장소 루트(`../..`)다. 그래야 `build/libs/` 가 빌드 컨텍스트에 들어온다.

### 기동 순서 — `depends_on` 과 healthcheck

```yaml
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
```

`depends_on` 만 쓰면 "컨테이너가 시작됐다"까지만 보장된다. MySQL 컨테이너는 시작 후 초기화에 10~20초가 더 걸리므로, 앱이 그 사이에 붙으려다 죽는다. `condition: service_healthy` 로 mysql 의 `mysqladmin ping` 과 redis 의 `redis-cli ping` 이 통과할 때까지 기다린다.

앱 자신의 healthcheck 는 관리 포트를 부른다:

```yaml
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8081/actuator/health"]
```

`eclipse-temurin:17-jre` 는 Ubuntu 기반이라 `curl` 과 `wget` 이 둘 다 들어 있다(`docker run --rm eclipse-temurin:17-jre which curl` 로 확인). 컨테이너 **안에서** 부르는 것이므로 8081 을 publish 하지 않은 것과 충돌하지 않는다.

### Prometheus

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

rule_files:
  - /etc/prometheus/rules/*.yml

scrape_configs:
  - job_name: credit_system
    metrics_path: /actuator/prometheus
    static_configs:
      # 8080 이 아니라 8081 이다. 관리 포트는 compose 네트워크 안에서만 보인다.
      - targets: ["app:8081"]
```

**Alertmanager 는 붙이지 않는다.** 알람을 Slack 이나 PagerDuty 로 라우팅하는 것은 이 시스템의 문제가 아니라 조직의 문제다 — 누가 당번인지, 어느 채널로 갈지, 야간에 전화를 걸지는 코드로 정할 수 없다. 이번 범위는 **규칙 자체를 코드로 남기고, Prometheus `/alerts` 페이지에서 fire 되는 것을 확인하는 데까지**다. 라우팅은 붙일 곳이 정해졌을 때 Alertmanager 하나를 추가하면 되고, 규칙 파일은 그대로 쓰인다.

`scrape_interval: 5s` 는 프로덕션 기준으로는 짧다(보통 15~30초). 도메인 스냅샷 주기가 15초라 5초로 긁으면 같은 값을 세 번 보게 되는데, 그래도 5초로 둔 이유는 6단계 때문이다 — 워커를 죽였을 때 `oldest_pending_age` 가 오르는 곡선을 촘촘히 봐야 한다. 대가는 TSDB 사용량이 3배가 되는 것이고, 실험용 스택이라 감수한다.

### 알람 규칙

| 규칙명 | 식 | severity | for | 왜 이 기준인가 |
|---|---|---|---|---|
| `CreditLedgerReconciliationMismatch` | `credit_ledger_reconciliation_mismatch > 0` | P1 | 0m | 잔액 = 최초 잔액 + 원장 합계 는 SLO 가 아니라 등식이다. 1건도 허용값이 아니므로 유예도 없다 |
| `CreditNegativeBalanceOrgs` | `credit_invariant_negative_balance_orgs > 0` | P1 | 0m | 조건부 UPDATE 의 잔액 가드가 뚫렸다는 뜻. 차감 경로 전체를 의심해야 한다 |
| `CreditJobsWithoutHold` | `credit_invariant_jobs_without_hold > 0` | P1 | 0m | job 은 있는데 돈이 안 묶였다. 무료로 처리되는 job 이 있다는 뜻 |
| `CreditUnsettledTerminalJobs` | `credit_invariant_unsettled_terminal_jobs > 0` | P1 | 0m | 종결됐는데 CONFIRM/REFUND 원장이 없다. 묶인 돈이 공중에 뜬 상태 |
| `CreditPipelineStalled` | `credit_job_oldest_pending_age_seconds > 300` | P2 | 1m | 스텁 지연(3~7초)에 재시도 3회를 더해도 300초에 닿지 않는다. `for: 1m` 은 스냅샷 한 주기(15초)의 흔들림을 걸러낸다 |
| `CreditReconciliationStale` | `credit_ledger_reconciliation_staleness_seconds > 180` | P2 | 0m | 대사 주기 60초의 3배. 게이지 자체가 시간의 함수라 `for` 가 필요 없다 |
| `CreditSnapshotStale` | `credit_snapshot_staleness_seconds > 45` | P2 | 0m | 스냅샷 주기 15초의 3배. 이게 울리면 L0/L1 게이지 전부가 낡은 값이다 |
| `CreditBackstopRecovery` | `increase(credit_job_recovery_total{detector="backstop"}[10m]) > 0` | P2 | 0m | heartbeat 가 잡았어야 할 것을 최후 방어선이 잡았다. 1건도 정상이 아니다 |
| `CreditSystemDown` | `up{job="credit_system"} == 0` | P2 | 30s | 이게 0 이면 **다른 모든 규칙이 침묵한다.** `for: 30s` 는 재시작·배포로 인한 순간 실패를 거른다 |
| `CreditRetryExhaustionRateHigh` | `increase(credit_defense_total{point="final_refund",outcome="applied"}[1h]) / increase(credit_defense_total{point="hold_balance",outcome="applied"}[1h]) > 0.1` | P3 | 10m | hold 를 통과한 요청 중 10% 넘게 재시도를 다 쓰고 환불로 끝났다. 추세라서 호출하지 않고 다음 근무일에 본다 |

P1 네 개에 `for: 0m` 을 준 것이 이 표의 핵심이다. 보통 알람에는 유예를 준다 — 순간적인 스파이크로 사람을 깨우지 않기 위해서다. 불변식에는 그 논리가 적용되지 않는다. `mismatch > 0` 은 "지금 값이 높다"가 아니라 **"등식이 깨진 상태를 대사가 목격했다"**이고, 그건 1초든 10분이든 똑같이 사고다.

규칙 파일의 P1 블록은 이렇게 생겼다:

```yaml
  - name: credit-invariants
    rules:
      - alert: CreditLedgerReconciliationMismatch
        expr: credit_ledger_reconciliation_mismatch > 0
        for: 0m
        labels:
          severity: P1
        annotations:
          summary: "원장 대사 불일치 {{ $value }}건"
          description: "잔액 = 최초 잔액 + 원장 합계 가 깨진 조직이 있다. LedgerReconciliationTask 의 ERROR 로그에서 organizationId 를 찾아라."
```

`description` 에 **다음 행동**을 적었다. 1단계에서 "집계는 메트릭이, 개별 식별은 로그가 맡는다"고 정한 결과가 여기로 온다 — 알람에는 조직 ID 가 없으므로, 알람 문구가 로그를 가리켜야 한다. 그러지 않으면 새벽에 깬 사람이 어디를 봐야 할지 모른다.

### 대시보드 구성의 근거

`grafana/dashboards/credit-domain.json` 은 문서의 4계층을 그대로 행(row)으로 옮겼다. 순서와 패널 종류에 각각 이유가 있다.

**1행 — L1 불변식을 맨 위에 둔다.** stat 패널 4개(mismatch, negative_balance_orgs, jobs_without_hold, unsettled_terminal_jobs)이고 0 이면 초록, 1 이상이면 배경 전체가 빨강이다. 대시보드를 여는 사람의 첫 질문은 "지금 사고가 났나"이지 "추세가 어떤가"가 아니다. 이 네 칸이 전부 초록이면 나머지는 천천히 봐도 된다.

**왜 시계열이 아니라 stat 인가.** 시계열 패널은 "값이 어떻게 변해왔는가"를 보여주는 도구다. 불변식에는 추세가 없다 — 참이거나 거짓이다. `jobs_without_hold` 가 어제 3이었다가 오늘 1이 된 것은 "좋아지는 중"이 아니라 여전히 사고다. 3단계 문서의 표현을 그대로 쓰면, **이 지표군에서는 0 이 목표값이고 0 이 아닌 것이 뉴스다.** 그 성질에는 큰 숫자 하나와 배경색이 맞는다.

**2행 — L0 돈.** `oldest_pending_age_seconds` 시계열에 300초 임계선을 빨간 선으로 그렸고, 옆에 `outstanding_amount` 와 `outstanding_count` 를 같은 패널에 겹쳤다. 이 둘은 반드시 같이 봐야 한다 — 건수는 그대로인데 금액만 오르면 큰 job 이 묶인 것이고, 둘 다 오르면 적체다. 여기는 추세가 의미를 갖는 자리라 시계열이 맞다.

**3행 — L3 방어 발동은 `rate` 로 그린다.** 누적 카운터를 그대로 그리면 영원히 우상향하는 계단만 보인다. "지금까지 총 몇 번 막았나"는 대시보드를 볼 때 궁금한 것이 아니다. 궁금한 건 **"지금 얼마나 자주 막고 있나"**이고, 그건 `rate(credit_defense_total[1m])` 이다. `point`+`outcome` 으로 stack 해서 어느 지점이 전체 방어량의 얼마를 차지하는지 한눈에 보이게 했고, 별도 패널로 `idem_key` 의 `app_hit` vs `db_unique`, `credit_job_recovery_total` 의 `heartbeat` vs `backstop` 을 따로 뺐다. 이 두 쌍은 **비율 자체가 신호**여서다 — db_unique 비중이 커지면 경합이 심해진 것이고, backstop 이 0 이 아니면 heartbeat 가 새는 것이다. 합쳐 그리면 그 비율이 안 보인다.

**4행 — 메타.** 두 staleness 와 `up` 을 맨 아래 둔다. 이건 도메인 지표가 아니라 **관측 장치 자신의 건강**이다. 위의 세 행이 전부 초록인 두 가지 이유가 있는데, 하나는 "정말 정상"이고 다른 하나는 "지표가 얼어붙었다"이다. 4행이 그 둘을 구분한다.

JSON 의 datasource 참조는 전부 `{"type": "prometheus", "uid": "prometheus"}` 이고, 이 uid 는 `provisioning/datasources/prometheus.yml` 이 고정한다. uid 를 바꾸면 패널 전부가 "Datasource not found" 가 된다.

### 직접 확인하는 방법 — 실제로 띄운 기록

아래는 이 스택을 실제로 올려서 받은 출력이다.

**1. jar 를 만들고 2. 스택을 올린다**

```
$ ./gradlew bootJar
$ docker compose -f deploy/observability/docker-compose.yml up -d --build
 Container observability-redis-1 Healthy
 Container observability-mysql-1 Healthy
 Container observability-app-1 Started
 Container observability-prometheus-1 Started
 Container observability-grafana-1 Started
```

**3. 전부 뜰 때까지 기다린다**

```
$ docker compose -f deploy/observability/docker-compose.yml ps
SERVICE      STATUS
app          Up 25 seconds (healthy)
grafana      Up 24 seconds
mysql        Up 30 seconds (healthy)
prometheus   Up 25 seconds
redis        Up 30 seconds (healthy)
```

**4. 노출 경계 확인 — 같은 포트, 다른 결과**

```
$ curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/actuator/prometheus
404
$ curl -s -o /dev/null -w "%{http_code}\n" -H "X-Organization-Id: 1" localhost:8080/api/jobs
200
```

공개 API 는 열려 있고 액추에이터는 없다. 4단계가 말한 경계가 실물로 존재한다.

**5. Prometheus 가 관리 포트를 긁고 있다**

```
$ curl -s 'localhost:9090/api/v1/targets' | ...
http://app:8081/actuator/prometheus  up  {'instance': 'app:8081', 'job': 'credit_system'}
```

**6. 규칙 10개가 전부 로드됐다**

```
$ curl -s 'localhost:9090/api/v1/rules' | ...
group: credit-invariants 4
    CreditLedgerReconciliationMismatch P1 for=0s   inactive
    CreditNegativeBalanceOrgs          P1 for=0s   inactive
    CreditJobsWithoutHold              P1 for=0s   inactive
    CreditUnsettledTerminalJobs        P1 for=0s   inactive
group: credit-pipeline 5
    CreditPipelineStalled              P2 for=60s  inactive
    CreditReconciliationStale          P2 for=0s   inactive
    CreditSnapshotStale                P2 for=0s   inactive
    CreditBackstopRecovery             P2 for=0s   inactive
    CreditSystemDown                   P2 for=30s  inactive
group: credit-trends 1
    CreditRetryExhaustionRateHigh      P3 for=600s inactive
total rules: 10
```

10개 전부 `inactive` 다 — 정상 상태에서 fire 되는 규칙이 하나도 없어야 한다는 것 자체가 확인 항목이다. 규칙을 잘못 쓰면(예: `>=` 를 `>` 대신) 가만히 있어도 울린다.

**7. Grafana 가 프로비저닝됐다**

```
$ curl -s localhost:3000/api/health
{"database": "ok", "version": "11.5.1", ...}

$ curl -s localhost:3000/api/datasources/uid/prometheus | ...
Prometheus prometheus http://prometheus:9090

$ curl -s 'localhost:3000/api/search?tag=credit' | ...
credit-domain  크레딧 도메인 관측  /d/credit-domain/d90bde0
```

여기서 하나 걸렸다. 처음에 `?query=credit` 로 검색했더니 빈 배열이 왔다 — Grafana 의 `query` 는 **제목** 검색인데 이 대시보드 제목이 한국어("크레딧 도메인 관측")여서 매칭되지 않은 것이다. 프로비저닝은 정상이었고 검색어가 틀렸다. `?tag=credit` 나 `?type=dash-db` 로 보면 나온다.

**8. seed → smoke → 실제 값**

```
$ ./deploy/observability/scripts/seed.sh
앱이 UP 이 될 때까지 대기한다...
앱 UP (1회 시도)
조직 id=1 을 넣는다...
id  name      balance  initial_balance
1   seed-org  0        0

$ ./deploy/observability/scripts/smoke.sh
== 1000 크레딧 충전
  charge   HTTP 200 {"balance":1000,"duplicate":false}
== job 5건 생성 (건당 100)
  job1     HTTP 200 {"jobId":1,"duplicate":false}
  ...
  job5     HTTP 200 {"jobId":5,"duplicate":false}
== 같은 idemKey 로 한 번 더 (idem_key/app_hit 유발)
  job1-재  HTTP 200 {"jobId":1,"duplicate":true}
== 잔액을 넘겨 요청 (hold_balance/rejected 유발)
  job6     HTTP 200 {"jobId":6,"duplicate":false}
  ...
  job10    HTTP 200 {"jobId":10,"duplicate":false}
  job11    HTTP 409 {"code":"INSUFFICIENT_BALANCE","message":"잔액이 부족합니다. balance=0, required=100"}
  job12    HTTP 409 {"code":"INSUFFICIENT_BALANCE","message":"잔액이 부족합니다. balance=0, required=100"}
```

워커가 다 처리하고 나면(`app.stub.failure-rate: 0.3` 이라 일부는 실패→재시도→환불로 흐른다) Prometheus 에서 본 방어 카운터는 이랬다:

```
$ curl -s --get localhost:9090/api/v1/query --data-urlencode 'query=credit_defense_total' | ...
  point=idem_key      outcome=app_hit     1
  point=idem_key      outcome=db_unique   0
  point=hold_balance  outcome=applied     10
  point=hold_balance  outcome=rejected    9
  point=worker_claim  outcome=applied     44
  point=worker_claim  outcome=lost        0
  point=confirm       outcome=applied     9
  point=confirm       outcome=stale       0
  point=mark_failed   outcome=applied     4
  point=mark_failed   outcome=stale       0
  point=retry_claim   outcome=applied     3
  point=retry_claim   outcome=lost        0
  point=final_refund  outcome=applied     1
  point=final_refund  outcome=raced       0
```

DB 최종 상태는 `COMPLETED 9, REFUNDED 1` 이었고 잔액은 100 이었다. 숫자들이 서로 맞는지 읽어 보자.

- `hold_balance/applied 10` + `rejected 9` — hold 를 10건 잡았고 9건은 잔액 부족으로 막혔다(rejected 9 중 7건은 이 실행 전에 스크립트를 고치며 낸 요청분이다. 카운터는 앱 생애 동안 누적된다).
- `mark_failed/applied 4` → `retry_claim/applied 3` → `final_refund/applied 1`. 스텁이 4번 실패했고, 그중 3번은 재시도로 살아났고, 1번은 시도를 다 써서 최종 환불로 끝났다. `4 = 3 + 1` 이 맞는다. 환불된 100 크레딧이 최종 잔액 100 이다.
- `confirm/applied 9` — 완료된 job 9건. `COMPLETED 9` 와 맞는다.
- 모든 `stale`, `lost`, `raced`, `db_unique` 가 0 — 이 트래픽에는 경쟁 상태가 없었다. 6단계에서 워커를 두 개 띄우거나 죽여서 이 칸들을 움직이게 하는 것이 목표다.

**`worker_claim/applied 44` 가 눈에 걸린다.** job 10건 + 재시도 3건 = 13번이면 될 텐데 44다. 로그를 세어 보니 답이 나왔다:

```
$ docker compose ... logs app | grep -c "executor 위임 실패"
31
```

`31 + 13 = 44` 다. `GenerationWorker` 는 job 을 선점(`worker_claim/applied`)한 뒤 executor 에 넘기는데, 워커 풀(`app.worker.concurrency: 3`)이 꽉 차 있으면 `execute` 가 거부된다. 그러면 `rollbackToHolding` 으로 job 을 HOLDING 으로 되돌리고, 다음 폴링(500ms)에서 같은 job 을 다시 선점한다. **선점 성공 카운터는 "처리된 job 수"가 아니라 "선점이라는 동작이 성공한 횟수"였다.**

이건 버그가 아니라 설계대로의 동작이지만, 지표만 보던 사람은 몰랐을 사실이다. HTTP 응답에도, ERROR 로그에도 안 남는다(위임 실패는 WARN 이다). 카운터가 예상과 어긋난 덕에 발견했고, 이게 2단계에서 말한 "이 줄이 유일한 흔적이다"의 실제 사례다. 다만 대시보드에서 `worker_claim/applied` 를 처리량으로 읽으면 안 된다는 뜻이기도 하다 — 문서에 남긴다.

L0·L1 게이지는 처리가 끝난 뒤 이랬다:

```
  credit_hold_outstanding_count               0
  credit_hold_outstanding_amount              0
  credit_job_oldest_pending_age_seconds       0
  credit_invariant_negative_balance_orgs      0
  credit_invariant_jobs_without_hold          0
  credit_invariant_unsettled_terminal_jobs    0
  credit_ledger_reconciliation_mismatch       0
  credit_ledger_reconciliation_checked        1
  credit_ledger_reconciliation_staleness_seconds  9.466
  credit_snapshot_staleness_seconds               9.293
```

처리 중간에 찍힌 값은 달랐다 — `outstanding_count 4`, `outstanding_amount 400`, `oldest_pending_age 16` 이었다. 묶였다가 풀리는 곡선이 실제로 그려진다는 뜻이다. staleness 두 개가 -1 이 아니라는 것은 대사와 스냅샷이 실제로 돌고 있다는 뜻이고(테스트 프로파일의 -1 과 대비된다), `up == 1` 이며 fire 중인 알람은 0개였다.

**9. 정리**

```
$ docker compose -f deploy/observability/docker-compose.yml down -v
$ docker compose -f deploy/observability/docker-compose.yml ps
NAME   IMAGE   COMMAND   SERVICE   CREATED   STATUS   PORTS
```

반드시 내려야 한다. Testcontainers 를 쓰는 테스트와 자원·포트를 두고 다툰다.

### 트레이드오프

- **Alertmanager 가 없다.** 규칙은 Prometheus `/alerts` 에서 fire 되지만 아무 데도 안 간다. 즉 이 스택은 **"누가 보고 있을 때만"** 알려준다. 진짜 온콜은 라우팅이 있어야 성립하고, 그건 조직이 정할 문제라 코드로 남기지 않았다.
- **앱 healthcheck 가 관리 포트에 의존한다.** `/actuator/health` 는 관리 포트로 옮겨갔으므로 healthcheck 도 8081 을 부른다. 관리 컨텍스트가 못 뜨면 애플리케이션 포트가 멀쩡히 요청을 처리하고 있어도 컨테이너는 unhealthy 가 되고, `depends_on: service_healthy` 를 건 것들이 줄줄이 막힌다. "관측 장치의 고장이 서비스의 고장으로 번지는" 구조인데, 관측 포트가 안 뜨면 어차피 사고를 못 보므로 그 상태를 정상으로 치지 않기로 했다.
- **`scrape_interval: 5s` 는 비싸다.** 15초 대비 TSDB 쓰기와 저장량이 3배다. 지표 305개 × 12/분이면 로컬에서는 아무 문제가 없지만, 인스턴스가 수십 개인 환경에 그대로 옮기면 안 되는 값이다.
- **비밀번호가 파일에 박혀 있다.** `application.yml` 의 `an902318` 을 compose 가 env 로 덮어쓰지만 그 env 도 평문이다. 로컬 실험 스택이라 그대로 뒀다 — 실제 배포라면 시크릿 관리가 별도로 필요하다.
  (step8-A·D 이후에는 `application.yml` 에서 평문 비밀번호가 사라지고 로컬 기본값 `credit`/`credit` 만 남았다. 관측 스택도 같은 계약을 쓴다. compose 의 env 가 평문인 것은 그대로다.)
- **데이터가 휘발된다.** MySQL·Prometheus·Grafana 모두 명명 볼륨을 두지 않았다. `down -v` 하면 전부 사라지고, 매번 `seed.sh` 부터 다시 한다. 실험대로서는 오히려 이쪽이 낫다(상태가 남으면 이전 실행의 카운터가 다음 실험을 오염시킨다 — 실제로 위의 `rejected 9` 가 그 사례다).

### 이 단계에서 남는 것

- **지표가 처음으로 밖에 나갔다.** 긁는 주체(Prometheus), 저장하는 곳(TSDB), 그리는 곳(Grafana), 판단 기준(규칙 10개)이 전부 저장소 안의 파일이 됐다. 3단계에서 "노출은 되지만 아직 아무도 긁어가지 않는다"고 남긴 것이 여기서 닫힌다.
- **하지만 전부 정상 상태에서만 봤다.** 위의 출력은 모두 "아무 사고도 없을 때 지표가 이렇게 보인다"이다. 알람 10개 중 실제로 fire 되는 것을 본 것은 하나도 없다. **규칙이 옳은지는 아직 모른다** — 문법이 맞고 로드된다는 것만 확인했다.
- **L2 흐름은 여전히 비어 있다.** 상태별 job 수 분포와 단계별 소요 시간이 없어서, 대시보드는 "막혔다"까지만 말하고 "어디서 막혔는지"는 말하지 않는다.
- **6단계는 장애 주입이다.** 이 스택 위에서 워커를 죽이고, 스케줄러를 멈추고, Redis 를 내리고, 원장을 손으로 깨서 **어느 지표가 반응하고 어느 지표가 침묵하는지** 본다. `oldest_pending_age` 가 300초를 넘어 `CreditPipelineStalled` 가 실제로 fire 되는 것, 원장을 깼을 때 `mismatch` 가 오르는 것, Redis 를 죽였을 때 `backstop` 회수가 오르는 것 — 여기까지 확인해야 이 규칙들이 종이가 아니라 장치가 된다. 그리고 침묵하는 지표를 찾는 것이 더 중요하다. 사고가 났는데 아무 값도 안 움직인다면, 그 자리가 다음에 만들어야 할 지표다.

---

## 6단계 — 장애 주입

여기까지 이 문서가 쓴 문장은 대부분 **주장**이었다. "워커가 죽으면 `oldest_pending_age` 가 오른다", "Redis 가 새면 `backstop` 이 오른다", "원장이 깨지면 `mismatch` 가 1 이 된다". 전부 코드를 읽고 낸 결론이고, 한 번도 확인한 적이 없다. 5단계는 규칙 10개가 **로드된다**는 것까지만 확인했다 — 문법이 맞고 `inactive` 라는 것. 규칙이 **옳은지**는 모른다.

6단계는 사고를 실제로 심는다. 워커를 죽이고, 스케줄러를 끄고, Redis 를 내리고, 원장을 SQL 로 깨고, 같은 요청을 100건 동시에 던지고, 외부 API 를 영원히 안 돌아오게 만든다. 그리고 매번 세 가지를 잰다 — **어느 지표가 반응했나, 어느 지표가 침묵했나, 감지까지 몇 초 걸렸나.** 앞 챕터들이 TPS 와 P99 로 증명한 것과 같은 방식이다. 숫자가 없으면 주장이다.

**침묵 쪽이 더 중요하다.** 3번(워커 정지)과 7번(외부 API 무한 지연) 시나리오에서는 HTTP 5xx 가 0, `up` 이 1, CPU 가 8.65%, 2단계의 방어 카운터 열넷이 전부 정지한 채로 사고가 진행된다. 인프라 대시보드를 아무리 들여다봐도 그 화면에서는 아무 일도 일어나지 않는다. 이 두 장면이 이 챕터 전체가 존재하는 이유고, 그래서 각 시나리오마다 **침묵해야 할 지표 목록**을 반응해야 할 지표 목록과 같은 비중으로 검증한다.

### 파일별 변경 목록

| 파일 | 변경 |
|---|---|
| `deploy/observability/docker-compose.yml` | `APP_SCHEDULING_ENABLED`·`APP_WORKER_ENABLED`·`APP_STUB_*` 5개 env 통과 추가. 기본값은 `application.yml` 과 같다 |
| `deploy/observability/prometheus/rules/credit.rules.yml` | `CreditSnapshotStale`·`CreditReconciliationStale` 수정 — `or (x < 0)` 추가, `for: 1m`. **장애 주입이 찾은 구멍이다** |
| `deploy/observability/scenarios/lib.sh` | 공용 함수 — `prom`/`alerts`/`wait_until`/`fresh_stack`/`restart_app_with`/`kill_app`/`report` |
| `deploy/observability/scenarios/01`~`07-*.sh` | 시나리오 7개. 각각 독립 실행 가능하고 끝에 기대 vs 관측 표를 낸다 |
| `deploy/observability/scenarios/run-all.sh` | 7개를 순서대로 돌리고 마지막에 `down -v` |
| `deploy/observability/README.md` | 시나리오 실행법 한 절 |

**애플리케이션 코드는 한 줄도 건드리지 않았다.** `git diff --stat -- src` 가 비어 있다. 사고를 심는 손잡이는 전부 이미 있던 설정(`app.worker.enabled`, `app.scheduling.enabled`, `app.stub.*`)이고, compose 가 그걸 env 로 통과시켜 줄 뿐이다.

### 사고를 심는 방법 — 손잡이가 이미 있었다

```yaml
      APP_SCHEDULING_ENABLED: ${APP_SCHEDULING_ENABLED:-true}
      APP_WORKER_ENABLED: ${APP_WORKER_ENABLED:-true}
      APP_STUB_FAILURE_RATE: ${APP_STUB_FAILURE_RATE:-0.3}
      APP_STUB_MIN_DELAY_MILLIS: ${APP_STUB_MIN_DELAY_MILLIS:-3000}
      APP_STUB_MAX_DELAY_MILLIS: ${APP_STUB_MAX_DELAY_MILLIS:-7000}
```

Spring 의 relaxed binding 이 `APP_WORKER_ENABLED` 를 `app.worker.enabled` 로 받는다. `${VAR:-기본값}` 형태라 아무것도 지정하지 않으면 5단계까지와 완전히 같은 스택이 뜬다 — **장애 주입 장치가 평상시 동작을 바꾸지 않는 것**이 조건이었다.

한 가지 제약이 있다. `GenerationWorker` 는 `@ConditionalOnExpression`, 스케줄러·스냅샷·대사 태스크는 `@ConditionalOnProperty` 로 걸려 있다. **이건 빈 등록 시점의 판정이라 런타임에 끌 수 없다.** 워커만 죽이려면 프로세스를 다시 띄워야 하고, 그래서 `restart_app_with` 는 `docker compose up -d --force-recreate app` 을 한다. 부수 효과로 **모든 카운터가 0 으로 리셋된다**(Micrometer 카운터는 프로세스 메모리에 산다). 각 시나리오는 재기동 이후에 기준선을 잡는다.

`@ConditionalOnExpression("${app.scheduling.enabled:true} and ${app.worker.enabled:true}")` 때문에 **스케줄러를 끄면 워커도 같이 사라진다.** 4번 시나리오가 3번보다 강한 사고인 이유다.

### 실측 방법

```bash
# wait_until "설명" 타임아웃초 'shell 조건'
#   조건이 참이 될 때까지 2초 간격으로 폴링하고 걸린 초를 출력한다.
#   이 초가 곧 감지 지연 실측값이다.
wait_until "recovery{heartbeat} >= 1" 120 \
  '[ "$(prom_num "credit_job_recovery_total{detector=\"heartbeat\"}")" != "0" ]'
```

감지 지연은 사람이 스톱워치를 누르는 것이 아니라 **Prometheus 에 그 값이 실제로 보이기까지**를 잰다. 즉 도메인 주기(스냅샷 15초 / 대사 60초 / 회수 스캔 5초) + 스크레이프 지연(최대 5초) + 알람 `for` 가 전부 포함된 숫자다. 운영자가 실제로 알게 되는 시각이 그거라서 그렇게 쟀다.
### 결과 매트릭스

아래는 `run-all.sh` 한 번의 실행에서 나온 값이다. 감지 초는 **사고를 심은 시각부터 Prometheus 에 그 값이 보일 때까지**, 알람 초는 거기서 `firing` 이 될 때까지 더 걸린 시간이다.

| # | 심은 사고 | 반응한 지표 (값 / 감지) | fire 된 알람 | 침묵한 지표 |
|---|---|---|---|---|
| 1 | PROCESSING 중 SIGKILL → 즉시 재기동 | `recovery{heartbeat}` **3** / SIGKILL 후 **11초**, 재기동 후 6초. `oldest_pending_age` 6 → 0 | 없음 (회수가 알람 임계 전에 끝났다) | `recovery{backstop}` 0, 불변식 4종 0, 5xx 0 |
| 2 A | 앱 SIGKILL + Redis 컨테이너/익명 볼륨 교체 | `recovery{backstop}` **3** / SIGKILL 후 **70초**, 재기동 후 60초 | `CreditBackstopRecovery` (+4초) | **`recovery{heartbeat}` 0** — 잡을 엔트리가 사라졌다. 불변식 4종 0 |
| 2 B | 앱 생존, Redis 90초 정지 (PROCESSING 3건 심음) | **아무것도 반응하지 않았다.** 로그만: 단계 ERROR 7회, 항목 WARN 21회 | 없음 | **`recovery{heartbeat}` 0 AND `recovery{backstop}` 0** ← 설계 결함. Redis 복구 2초 후 backstop 3 |
| 3 | 워커만 정지 (`APP_WORKER_ENABLED=false`) | `outstanding_count` **5**, `amount` **500**, `oldest_pending_age` 13→374 단조 증가 | `CreditPipelineStalled` / job 생성 후 **385초** | 방어 카운터 14종 중 사고 관련 **전부 0**(`worker_claim` 0, `confirm` 0), `recovery` 0, 5xx 0, `up` 1, 불변식 0 |
| 4 (수정 전) | 스케줄러 정지 (`APP_SCHEDULING_ENABLED=false`) | 없음. staleness 두 개가 **-1 에 고정** | **없음 — 2분 동안 하나도 안 울렸다** | `oldest_pending_age` **0 에 얼어붙음**(DB 실제 121초), `outstanding_count` 0(실제 3) |
| 4 (수정 후) | 같은 사고 | staleness 두 개 -1 | `CreditSnapshotStale` + `CreditReconciliationStale` / 재기동 후 **76초** | 위와 동일 |
| 5 (a) | `balance = balance - 1` | `ledger_reconciliation_mismatch` 0 → **1** / **25초** | `CreditLedgerReconciliationMismatch` (+6초) | 방어 카운터 합 31 → **31**(증가 0), `up` 1, 5xx 0 |
| 5 (b) | `balance = -1` | `invariant_negative_balance_orgs` 0 → **1** / **11초** | `CreditNegativeBalanceOrgs` (+0초) | 같음 |
| 5 (c) | HOLD 원장 1행 DELETE | `invariant_jobs_without_hold` 0 → **1** / **14초** | `CreditJobsWithoutHold` (+0초) | `unsettled_terminal_jobs` 0 (CONFIRM 은 남아 있다) |
| 5 원복 | 전부 되돌림 | 불변식 3종 14초, mismatch 14초 만에 0 복귀 | 전부 resolve (+0초) | — |
| 6 | 같은 idemKey 100건 동시 | `idem_key{app_hit}` **90** + `{db_unique}` **9** = **99**, `hold_balance{applied}` **+1**, 잔액 정확히 **-100** | 없음 | `hold_balance{rejected}` 0, 불변식 4종 0 |
| 7 | 스텁 지연 600초 | `oldest_pending_age` 28→373 단조 증가, `outstanding_count` 3 | `CreditPipelineStalled` / job 생성 후 **379초** | **`recovery{heartbeat}` 0 AND `{backstop}` 0(옳다)**, `confirm` 0, `mark_failed` 0, `worker_claim` **정확히 3**, 5xx 0, CPU 8.65%, `up` 1 |

**기대와 달랐던 것 넷.**

1. **2 A 에서 `docker compose restart redis` 로는 ZSET 이 안 지워졌다.** `redis:7` 이 종료 시 RDB 를 저장하고, 그 `/data` 가 이미지의 `VOLUME` 선언 때문에 익명 볼륨에 붙어 있어서 `--force-recreate` 로도 남는다. `--renew-anon-volumes` 까지 줘야 지워졌다. 5단계 문서의 "명명 볼륨이 없으니 데이터가 휘발된다"는 문장이 Redis 에 대해서는 틀렸다.
2. **4번에서 알람이 하나도 안 울렸다.** 예상은 `CreditSnapshotStale` 이 45초 뒤 울리는 것이었다. staleness 초깃값이 -1 이라 `> 45` 가 영원히 거짓이다. **규칙을 고쳤다.**
3. **1번에서 `retry_claim/applied 6` 인데 `mark_failed/applied 3` 이었다.** heartbeat 회수는 `mark_failed` 를 거치지 않고 `failIfProcessing` 을 직접 부르기 때문이다. 재시도 횟수를 `mark_failed` 로 세면 크래시로 인한 재시도를 놓친다.
4. **6번에서 `db_unique` 9건이 200 이 아니라 409 를 받았다.** 유니크 위반이 트랜잭션 밖으로 나온 뒤 `GlobalExceptionHandler` 에서 `DUPLICATE_IN_PROGRESS` 로 변환되는 설계대로의 동작이다. 멱등성이 "항상 같은 응답"이 아니라 "부작용이 한 번만"으로 구현돼 있다는 뜻이고, 카운터 두 개가 그 경계를 보여준다.

### 시나리오별 상세
#### 1번 — 워커 크래시 (`01-worker-crash.sh`)

```
# job 6건 → PROCESSING 확인 → SIGKILL → 즉시 재기동
create_jobs 6
docker compose -f deploy/observability/docker-compose.yml kill -s SIGKILL app
docker compose -f deploy/observability/docker-compose.yml start app
```

```
   T+0    job 6건 생성
   T+3    PROCESSING = 1:0,2:0,3:0  (전체: HOLDING=3 PROCESSING=3)
   T+3    앱 SIGKILL
   T+8    앱 UP — 크래시로부터 5초
   T+14   heartbeat 회수 감지 — 앱 UP 이후 6초 / SIGKILL 이후 11초
   T+14   회수 직후 지표: recovery{heartbeat}=3 backstop=0 oldest_pending_age=6
   T+39   전부 종결 — COMPLETED=6, 잔액 9400
```

**SIGKILL 부터 회수까지 11초.** heartbeat timeout 이 10초이므로 이론적 하한에 거의 붙었다. 앱이 죽어 있는 동안에도 Redis 의 만료 시각은 흐르고 있어서, 재기동한 앱의 첫 스캔이 그 자리에서 세 건을 다 집어냈다.

`backstop` 은 0 이다. 이게 정상이다 — 백스톱은 `updatedAt` 이 60초 넘게 안 움직인 PROCESSING 을 잡는데, heartbeat 가 11초 만에 처리했으므로 60초에 닿을 일이 없다. **두 탐지기의 순서가 실측으로 확인된 셈이다.**

`retry_claim/applied 6` 인데 `mark_failed/applied 3` 이다. 6 = heartbeat 로 회수된 3건 + 스텁이 실패시킨 3건이고, `mark_failed` 3 은 뒤쪽만 센 것이다. **heartbeat 회수는 `mark_failed` 를 거치지 않고 `failIfProcessing` 을 직접 부르기 때문**이다. 재시도 횟수를 `mark_failed` 로 세면 크래시로 인한 재시도를 빠뜨린다.

`hold_balance/applied 0` 도 눈여겨볼 값이다. hold 는 재기동 **전에** 다 잡혔고 카운터는 프로세스와 함께 죽었다. 사고 조사에서 절대값을 믿으면 안 되는 이유가 이거다.

#### 2번 — heartbeat 소실 (`02-heartbeat-lost.sh`)

**A. ZSET 을 날린다.**

```
create_jobs 4                        # PROCESSING 3건 확보
docker compose kill -s SIGKILL app
docker compose up -d --force-recreate --renew-anon-volumes redis
docker compose start app
```

`--renew-anon-volumes` 가 필요했던 것이 첫 번째 예상 밖이다. 처음에는 `docker compose restart redis` 로 썼는데 ZSET 이 그대로 3개였다. 두 겹의 이유가 있었다.

1. `redis:7` 은 SIGTERM 을 받으면 RDB 를 `/data` 에 저장하고, 재기동 때 그대로 로드한다.
2. 그 `/data` 는 이미지가 `VOLUME` 으로 선언한 경로여서 Docker 가 **익명 볼륨**을 만들어 붙인다. `--force-recreate` 로 컨테이너를 새로 만들어도 익명 볼륨은 물려받는다.

5단계 문서에 "명명 볼륨을 두지 않았으니 데이터가 휘발된다"고 썼는데, **Redis 에 대해서는 그 문장이 틀렸다.** 익명 볼륨까지 갈아엎어야 비로소 기억을 잃는다.

```
   T+0    Redis ZSET: 3 개
   T+0    앱 SIGKILL
   T+4    Redis ZSET: 0 개 (소실 확인)
   T+10   앱 UP — SIGKILL 이후 10초
   T+70   backstop 회수 감지 — 앱 UP 이후 60초 (backstop=3, heartbeat=0)
   T+74   firing 알람: [CreditBackstopRecovery]
```

**heartbeat 는 0, backstop 이 3.** 1번 시나리오와 정확히 반대다. 탐지기가 잡을 엔트리 자체가 사라졌으므로 `findExpiredAttempts` 는 빈 집합을 돌려주고, 아무 일도 없었다는 듯이 지나간다. 회수는 `updatedAt` 이 60초를 넘긴 뒤에야 일어났고, **1번의 11초 대비 여섯 배 가까이 느리다.** 이 차이가 `CreditBackstopRecovery` 를 P2 알람으로 둔 이유다 — 백스톱이 도는 것은 결과적으로 복구가 됐다는 뜻이면서 동시에 **탐지가 여섯 배 느려졌다**는 뜻이다.

**B. Redis 가 죽어 있는 동안.** 여기가 이 시나리오의 본론이다.

```
restart_app_with APP_WORKER_ENABLED=false          # 회수 경로만 남긴다
create_jobs 3
UPDATE jobs SET status='PROCESSING', updated_at = NOW(6) - INTERVAL 120 SECOND WHERE status='HOLDING';
docker compose stop redis                          # 90초
```

```
   T+8    PROCESSING 행 3건을 updated_at=120초 전으로 심었다 (백스톱 대상)
   T+8    Redis 정지
   T+19   backstop=0 heartbeat=0 PROCESSING=3
   ... (10초 간격으로 9회, 전부 같은 값)
   T+100  backstop=0 heartbeat=0 PROCESSING=3
   T+101  로그 'heartbeat 만료 회수 단계 실패' 7회 / 'PROCESSING 정체 job 회수 실패' 21회
   T+101  Redis 재시작
   T+103  Redis 복구 2초 만에 backstop=3
```

**회수 대상이 눈앞에 세 건 있는데 90초 동안 한 건도 회수하지 못했다.** 로그 수가 정확히 맞아떨어진다 — 회수 주기 7번 × job 3건 = WARN 21회. 매 주기마다 세 건을 전부 시도했고 전부 예외로 끝났다.

```
WARN c.e.c.j.scheduling.DeadJobRecoveryTask : PROCESSING 정체 job 회수 실패: jobId=6, attemptNo=0
```

그리고 Redis 를 되살리자 **2초** 만에 3건이 회수됐다. 심어 둔 행은 처음부터 유효한 회수 대상이었다는 뜻이고, 막고 있던 것은 오직 Redis 였다는 뜻이다.

90초 동안 `credit_job_recovery_total` 은 두 라벨 모두 0 이었다. **지표만 보는 사람에게 이 90초는 "회수할 것이 없는 평온한 시간"과 똑같이 생겼다.** 자세한 것은 아래 "장애 주입이 찾은 것"에 적는다.
#### 3번 — 워커 정지 (`03-worker-stopped.sh`)

```
restart_app_with APP_WORKER_ENABLED=false   # API·스케줄러·회수는 그대로 산다
create_jobs 5
```

```
   T+30   count=5 amount=500 age=13  pending알람=[ ]                     firing=[ ]
   T+121  count=5 amount=500 age=103 pending알람=[ ]                     firing=[ ]
   T+211  count=5 amount=500 age=209 pending알람=[ ]                     firing=[ ]
   T+302  count=5 amount=500 age=299 pending알람=[ ]                     firing=[ ]
   T+332  count=5 amount=500 age=329 pending알람=[CreditPipelineStalled] firing=[ ]
   T+385  CreditPipelineStalled firing — job 생성 이후 385초
```

**곡선이 이보다 깨끗할 수 없다.** `age` 가 30초마다 30씩 오르고 `count` 는 5에 고정이다. 300초를 넘긴 T+332 에 알람이 `pending` 으로 들어가고, `for: 1m` 을 채운 T+385 에 `firing` 이 됐다. 실측 385초 = 임계 300초 + 유예 60초 + 스냅샷/스크레이프 지연 25초. **설계값과 실측이 25초 안에 맞는다.**

같은 시각의 침묵 쪽이 이 시나리오의 본론이다.

```
     credit_defense_total{point=worker_claim, outcome=applied} 0
     credit_defense_total{point=confirm,      outcome=applied} 0
     credit_defense_total{point=mark_failed,  outcome=applied} 0
     credit_defense_total{point=retry_claim,  outcome=applied} 0
     credit_job_recovery_total{detector=heartbeat} 0
     credit_job_recovery_total{detector=backstop}  0
     invariant negative=0 jobs_without_hold=0 unsettled=0 mismatch=0
     up=1  5xx=0
```

2단계가 만든 카운터 열넷 중 움직인 것은 `hold_balance/applied 5` 하나뿐이고, 그건 job **생성** 때 오른 것이라 사고와 무관하다. **사고가 6분 넘게 진행되는 동안 방어 카운터는 트래픽이 없는 새벽 시간대와 구분되지 않았다.** 불변식 넷도 전부 0 이다 — 돈이 잘못된 것이 아니라 **아무 일도 일어나지 않고 있을 뿐**이므로 당연하다. 등식은 여전히 참이다.

`up` 은 1 이고 5xx 는 0 이다. API 는 계속 200 을 돌려주고 있었다. **인프라 대시보드에서 이 6분은 완벽하게 건강하다.**

3단계 문서가 "`oldest_pending_age` 는 원리상 카운터로 만들 수 없다 — 일어나지 않은 일에는 증가시킬 지점이 없다"고 쓴 문장이 여기서 실측으로 바뀐다. 카운터 열넷이 전부 0 인 화면과 게이지 하나가 385초까지 오른 화면이 같은 시각의 같은 시스템이다.
#### 4번 — 스케줄러 정지 (`04-scheduler-stopped.sh`)

```
restart_app_with APP_SCHEDULING_ENABLED=false
create_jobs 3
```

`@ConditionalOnExpression("${app.scheduling.enabled:true} and ${app.worker.enabled:true}")` 때문에 **워커까지 같이 사라진다.** 스냅샷·대사·회수·워커가 한꺼번에 없어지는, 3번보다 한 단계 위의 사고다.

**규칙 수정 전 — 2분 동안 알람이 하나도 울리지 않았다.**

```
     CreditSnapshotStale        expr=credit_snapshot_staleness_seconds > 45   for=0s
     CreditReconciliationStale  expr=credit_ledger_reconciliation_staleness_seconds > 180  for=0s

   T+16   snapshot_staleness=-1 recon_staleness=-1 age=0 count=0 firing=[ ]
   T+31   snapshot_staleness=-1 recon_staleness=-1 age=0 count=0 firing=[ ]
   ...
   T+122  snapshot_staleness=-1 recon_staleness=-1 age=0 count=0 firing=[ ]
```

**규칙 수정 후 — 같은 사고, 76초 만에 두 개가 firing.**

```
     CreditSnapshotStale        expr=(credit_snapshot_staleness_seconds > 45) or (credit_snapshot_staleness_seconds < 0)   for=60s
     CreditReconciliationStale  expr=(... > 180) or (... < 0)                                                             for=60s

   T+61   snapshot_staleness=-1 ... firing=[ ]
   T+76   snapshot_staleness=-1 ... firing=[CreditReconciliationStale CreditSnapshotStale]
```

76초 = `for: 60s` + 평가 주기와 기동 시간 16초. 자세한 것은 아래 "장애 주입이 찾은 것"에 적는다.

**게이지가 얼어붙는다는 것이 무슨 뜻인지가 이 시나리오의 나머지 절반이다.**

```
   게이지 oldest_pending_age = 0초  /  DB 실제 미결 나이 = 121초
   게이지 outstanding_count  = 0    /  DB 실제 미결      = 3
   snapshot_cycles_total = 0
```

**대시보드는 "미결 0건, 최고 나이 0초"를 그리고 있었다.** 실제로는 3건이 121초째 묶여 있었다. 3번 시나리오에서는 같은 게이지가 사고를 잡아냈는데, 4번에서는 그 게이지가 사고의 일부가 됐다 — **게이지를 갱신하는 주체가 사고에 같이 죽었기 때문**이다. 이게 5단계에서 "4행(메타)이 나머지 세 행의 초록을 두 가지로 구분한다"고 쓴 것의 실물이다. 정말 정상인가, 지표가 얼어붙었나. 그 판단은 오직 staleness 만 할 수 있고, staleness 규칙이 틀리면 아무도 못 한다.
#### 5번 — 원장 훼손 (`05-ledger-corruption.sh`)

앞의 넷은 프로세스를 죽였다. 이번에는 **아무것도 죽이지 않고 데이터만 깬다.** job 5건이 정상적으로 끝난 뒤(COMPLETED 4, REFUNDED 1, 잔액 9600) DB 에 직접 세 가지를 심는다.

```sql
-- (a) 원장 없이 1 크레딧을 증발시킨다
UPDATE organizations SET balance = balance - 1 WHERE id = 1;
-- (b) 잔액 가드가 뚫린 상태를 만든다
UPDATE organizations SET balance = -1 WHERE id = 1;
-- (c) 어떤 job 의 HOLD 원장을 지운다 — 돈을 안 묶고 처리된 job 이 된다
DELETE FROM ledger_entries WHERE type = 'HOLD' AND job_id = 1;
```

| 훼손 | 반응한 지표 | 감지 | fire 된 알람 |
|---|---|---|---|
| (a) balance −1 | `credit_ledger_reconciliation_mismatch` 0 → **1** | **25초** | `CreditLedgerReconciliationMismatch` (+6초) |
| (b) balance = −1 | `credit_invariant_negative_balance_orgs` 0 → **1** | **11초** | `CreditNegativeBalanceOrgs` (+0초) |
| (c) HOLD 1행 삭제 | `credit_invariant_jobs_without_hold` 0 → **1** | **14초** | `CreditJobsWithoutHold` (+0초) |

감지 시간이 셋 다 그 지표를 만드는 주기와 정확히 일치한다. (a)는 대사 주기 60초의 한복판에 떨어져 25초, (b)(c)는 스냅샷 주기 15초 안쪽이라 11·14초다. **주기가 곧 감지 지연의 상한이라는 것이 실측으로 확인된다** — (a)를 15초 안에 잡고 싶으면 대사 주기를 줄이는 것 외에 방법이 없고, 그건 전체 스캔 쿼리의 비용과 맞바꾸는 결정이다.

`unsettled_terminal_jobs` 만 0 으로 남았다. (c)로 지운 것은 HOLD 이고 CONFIRM 은 그대로 있어서, "종결됐는데 정산 원장이 없다"는 조건에는 걸리지 않는다. **네 불변식이 서로 다른 것을 재고 있다는 증거**이고, 하나로 합치면 안 되는 이유다.

침묵 쪽:

```
     credit_job_recovery_total{detector=heartbeat} 0
     credit_job_recovery_total{detector=backstop}  0
     up=1  5xx=0
   방어 카운터 합 (훼손 전 → 후)   31 → 31
```

**방어 카운터 열넷의 합이 31 에서 31 로, 정확히 하나도 움직이지 않았다.** 당연하다 — 방어 장치는 조건부 UPDATE 를 통과하지 못한 쓰기를 세는데, 여기서는 SQL 이 그 장치를 우회해서 들어갔다. 애플리케이션은 이 세 사건을 **겪지 않았다.** 겪지 않은 일은 셀 수 없다.

그래서 이 사고에는 **주기적으로 등식을 확인하는 장치가 반드시 따로 있어야 한다.** 2단계의 카운터(사건 기반)와 1·3단계의 게이지(상태 기반)를 둘 다 만든 이유가 여기서 갈린다. 이벤트 기반 관측만으로는 이 화면을 절대 만들 수 없다.

**원복까지 확인했다.**

```
   T+0    HOLD 원장 재삽입 + balance=9600 복구
   [ 14s] 불변식 3종이 전부 0 — 참
   [ 14s] mismatch 가 0 — 참
   [  0s] firing 알람 0개 — 참
```

알람이 뜨는 것만큼 **꺼지는 것**도 확인 항목이다. 게이지 기반 알람은 상태가 돌아오면 스스로 resolve 되어야 하고(카운터 기반인 `CreditBackstopRecovery` 는 `increase(...[10m])` 이라 10분을 기다려야 한다), 실제로 그렇게 동작했다.
#### 6번 — 중복 폭풍 (`06-duplicate-storm.sh`)

```
seq 1 100 | xargs -P 100 -I{} curl -s -X POST localhost:8080/api/jobs \
  -H 'X-Organization-Id: 1' -H 'Content-Type: application/json' \
  -d '{"idemKey":"storm-...","prompt":"duplicate storm"}'
```

같은 idemKey 로 100건을 진짜 동시에 던진다. 클라이언트 재시도 폭주나 버튼 연타의 극단이다.

```
     HTTP   91 200
     HTTP    9 409
   잔액 20000 → 19900 (차이 100)
   job 행 1건, HOLD 원장 1건

     credit_defense_total{point=idem_key,     outcome=app_hit}   90
     credit_defense_total{point=idem_key,     outcome=db_unique}  9
     credit_defense_total{point=hold_balance, outcome=applied}    1
     credit_defense_total{point=hold_balance, outcome=rejected}   0
```

**90 + 9 = 99.** 100건 중 하나만 job 을 만들었고 나머지 99건은 전부 막혔다. 잔액은 정확히 100 만 줄었고, job 행도 HOLD 원장도 1건씩이다. 두 번 돌려도 90/9 로 같았다.

이 시나리오의 값은 **`app_hit` 과 `db_unique` 의 비율**에 있다. 2단계 문서가 "이 두 쌍은 비율 자체가 신호"라고 쓴 것이 여기서 숫자가 된다.

- `app_hit 90` — 애플리케이션 레벨 조회에서 이미 있는 것을 발견해 막았다. 값싼 방어다.
- `db_unique 9` — **애플리케이션 조회를 통과한 9건**이 유니크 제약에서 부딪혔다. 조회와 INSERT 사이의 창에 다른 요청이 들어온 경우다.

정상 트래픽에서는 `db_unique` 가 0 이었다(5단계 smoke, 1~5번 시나리오 전부). 이 카운터가 오르는 것은 **동시성이 애플리케이션 레벨 체크의 창을 실제로 뚫고 있다**는 뜻이고, 그 비율이 커지면 낙관적 체크만으로는 부족하다는 신호다. 100건 동시에서 9%였다.

`db_unique` 9건이 받은 응답은 200(duplicate=true)이 아니라 **409 `DUPLICATE_IN_PROGRESS`** 다. 설계대로다 — 유니크 위반은 `@Transactional` 밖으로 나온 뒤 `GlobalExceptionHandler` 가 잡으므로 이미 롤백이 끝난 시점이고, 그 자리에서 "성공"인 척할 수 없다. 클라이언트는 재시도하면 되고, 그때는 `app_hit` 경로로 200 을 받는다. **멱등성이 "항상 같은 응답"이 아니라 "부작용이 한 번만"으로 구현돼 있다는 사실이 카운터 두 개로 드러난다.**

`hold_balance/rejected` 는 0 이다. 잔액이 충분했으므로 이 폭풍은 돈 방어선까지 가지도 않았다. 불변식 넷도 전부 0 — 100건이 동시에 부딪혔는데 등식은 한 번도 깨지지 않았다.
#### 7번 — 외부 생성 API 무한 지연 (`07-external-api-hang.sh`)

```
restart_app_with APP_STUB_MIN_DELAY_MILLIS=600000 APP_STUB_MAX_DELAY_MILLIS=600000
create_jobs 3
```

워커는 살아 있고, job 을 정상적으로 선점했고, heartbeat 도 5초마다 정확히 갱신하고 있다. 그저 외부 API 가 돌아오지 않는다.

```
   T+30   age=28  count=3 hb=0 bs=0 zset=3 firing=[ ]
   T+121  age=118 count=3 hb=0 bs=0 zset=3 firing=[ ]
   T+212  age=208 count=3 hb=0 bs=0 zset=3 firing=[ ]
   T+302  age=298 count=3 hb=0 bs=0 zset=3 firing=[ ]
   T+363  age=358 count=3 hb=0 bs=0 zset=3 firing=[ ]
   T+379  CreditPipelineStalled firing — job 생성 이후 379초

   DB: PROCESSING=3  |  Redis heartbeats ZSET: 3 개
   observability-app-1  CPU 8.65%  MEM 17.49%
```

**`zset=3` 이 6분 내내 유지된다.** 이게 이 시나리오의 핵심이다. 회수 장치는 두 탐지기 모두 조용한데, **그게 옳다.** heartbeat 가 살아 있다는 것은 이 job 의 소유자가 살아 있다는 뜻이고, 소유자가 살아 있는 job 을 회수하면 같은 요청을 두 번 처리하게 된다. `recovery{heartbeat}=0`, `recovery{backstop}=0` 은 회수 장치가 **정확히 설계대로 판단한 결과**다.

`markStalledJobsAsFailed` 는 60초가 지난 T+60 이후 매 5초마다 이 세 건을 후보로 집어 올렸고, `hasLiveHeartbeat` 가 매번 `true` 를 돌려줘 그냥 넘어갔다. **2번 시나리오 B 와 정확히 같은 코드 경로인데 결과가 반대다** — 거기서는 이 호출이 예외를 던져 넘어갔고, 여기서는 참을 돌려줘서 넘어갔다. 지표만 보면 두 상황이 **똑같이 0** 이다. 이 두 시나리오를 나란히 놓는 것이 "회수 시도 실패 카운터가 필요하다"는 결론의 근거다.

침묵의 목록이 이 시스템에서 가장 긴 시나리오이기도 하다.

| 무엇 | 값 | 왜 침묵하는가 |
|---|---|---|
| `confirm/applied` | 0 | 확정할 job 이 없다. 아직 안 끝났으니까 |
| `mark_failed/applied` | 0 | 실패도 안 했다. 스텁이 아직 판정을 안 내렸다 |
| `retry_claim`, `final_refund` | 0 | 실패가 없으니 재시도도 환불도 없다 |
| `recovery{heartbeat/backstop}` | 0 | heartbeat 가 살아 있다. 회수하지 않는 것이 옳다 |
| `http 5xx` | 0 | 요청은 이미 200 으로 끝났다. 지연은 워커 스레드 안에 있다 |
| `up` | 1 | 관리 포트도 healthcheck 도 정상이다 |
| CPU / MEM | 8.65% / 17.49% | 스레드 3개가 잠들어 있을 뿐이다. 부하가 없다 |
| 불변식 4종 | 0 | 등식은 참이다. 돈은 정확히 묶여 있다 |

**서버 지표로 볼 수 있는 모든 칸이 정상이다.** CPU 도, 메모리도, 에러율도, 응답 시간도, 프로세스 생존도. 그런데 300 크레딧이 6분째 묶여 있고 아무도 그걸 풀 예정이 없다.

움직인 것은 딱 하나, `credit_job_oldest_pending_age_seconds` 다. 30초마다 30씩, 흔들림 없이. **이 시나리오가 3단계에서 "지표를 하나만 남기고 다 지워야 한다면 이걸 남긴다"고 쓴 문장의 증명이다.**

`worker_claim/applied` 가 정확히 **3** 인 것도 함께 확인됐다. job 3건 = 워커 동시성 3 이라 executor 가 한 번도 거부하지 않았고, 5단계에서 발견한 churn 이 사라졌다. 이 카운터가 job 수와 같아지는 조건이 실측으로 확인된 셈이다.
### 장애 주입이 찾은 것

주장을 실측으로 바꾸는 것이 목적이었는데, 실제로 돌려 보니 **주장 자체가 틀린 자리가 세 군데** 나왔다. 이게 6단계를 하는 이유다.

#### 1. staleness 규칙의 구멍 — `-1` 에서는 `> 45` 가 절대 참이 되지 않는다 (고쳤다)

4번 시나리오의 규칙 수정 전 실행이 이 구멍의 전부다. **스케줄러가 꺼진 채로 기동하면 관측 장치가 통째로 없는 상태인데, 알람은 2분 동안 하나도 울리지 않았다.**

```
   T+16   snapshot_staleness=-1 recon_staleness=-1 age=0 count=0 firing=[ ]
   ...
   T+122  snapshot_staleness=-1 recon_staleness=-1 age=0 count=0 firing=[ ]
```

3단계에서 staleness 초깃값을 0 이 아니라 **-1** 로 둔 것은 옳은 결정이었다. "아직 한 번도 안 돌았다"와 "방금 돌았다"는 다른 상태이고, 0 은 그 둘을 구분하지 못한다. 문제는 그 결정을 **알람 식이 따라가지 않았다**는 것이다. `credit_snapshot_staleness_seconds > 45` 는 -1 에서 거짓이다. 즉 이 규칙은 "스냅샷이 돌다가 멈춘" 경우만 잡고, **"스냅샷이 한 번도 안 돈" 더 나쁜 경우는 놓친다.**

같은 시점의 게이지는 이렇게 보였다:

```
   게이지 oldest_pending_age = 0초 / DB 실제 미결 나이 = 121초
   게이지 outstanding_count  = 0 / DB 실제 미결 = 3
   snapshot_cycles_total = 0
```

**대시보드는 "미결 0건, 최고 나이 0초"라는, 존재하지 않는 완벽한 정상 화면을 그리고 있었다.** 3단계 문서가 "반쯤 채운 스냅샷은 관측이 없는 것보다 나쁘다 — 없으면 아무도 안 믿지만, 있으면 사람들이 믿는다"고 쓴 그 상황이 실제로 재현됐고, 그걸 막으라고 만든 staleness 알람이 침묵했다.

고친 식은 이렇다:

```yaml
      - alert: CreditSnapshotStale
        expr: (credit_snapshot_staleness_seconds > 45) or (credit_snapshot_staleness_seconds < 0)
        for: 1m
```

`for: 1m` 은 기동 유예다. 정상 기동에서도 첫 스냅샷이 찍히기 전까지 잠깐 -1 이므로, 유예가 없으면 재배포마다 알람이 깜빡인다. 스냅샷 주기가 15초라 1분이면 네 번의 기회를 준 셈이다. `CreditReconciliationStale` 도 같은 모양으로 고쳤다(`> 180 or < 0`, `for: 1m`).

> 5단계의 알람 규칙 표에는 이 둘이 `for: 0m` 으로 적혀 있다. 그건 그 시점의 기록이고, 이 단계에서 바뀌었다.

#### 2. Redis 가 죽어 있는 동안 백스톱도 죽는다 (기록만 했다 → 후속 1 에서 고쳤다)

step6 이 "Redis 장애에 대비한 최후 방어선"으로 만든 것이 `updatedAt` 백스톱이다. 문서에도 코드 주석에도 그렇게 써 있다. 2번 시나리오 B 가 그 문장이 틀렸음을 보여준다.

```kotlin
private fun recoverStalled(job: Job) {
    try {
        val jobId = job.persistedId
        if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) {   // ← Redis 를 부른다
            return
        }
        ...
    } catch (e: RuntimeException) {
        log.warn("PROCESSING 정체 job 회수 실패: ...", e)                  // ← 여기로 삼켜진다
    }
}
```

`hasLiveHeartbeat` 은 `refreshHeartbeat` 과 달리 예외를 삼키지 않는다. Redis 가 없으면 던지고, 항목 단위 `catch` 가 그걸 받아 WARN 한 줄을 남기고 넘어간다. 같은 주기의 `markExpiredJobsAsFailed` 는 `findExpiredAttempts` 에서 이미 던져 단계 단위 `catch` 로 빠진다. **결과적으로 Redis 가 죽으면 두 탐지기가 동시에 죽는다.** 백스톱은 Redis 장애를 대비한 것이 아니라 **Redis 에 의존하는** 장치였다.

실측이 이걸 딱 잘라 보여준다 — 90초 동안 18번 스캔했고, 회수는 0건이었으며, Redis 를 되살리자 4초 만에 3건이 회수됐다.

**고치지 않았다.** 관측이 코드 결함을 드러내는 것까지가 이번 단계의 범위고, 고치는 방향에는 트레이드오프가 있어서 따로 판단해야 한다. 가능한 수정은 `hasLiveHeartbeat` 의 예외를 "살아 있음"이 아니라 **"알 수 없음"**으로 다뤄 `updatedAt` 만으로 회수를 결정하는 것인데, 그러면 **Redis 순단 중에 멀쩡히 일하고 있는 job 을 죽은 것으로 오판**할 수 있다(60초 넘게 걸리는 정상 job 이 있으면 실제로 그렇게 된다). 지금 코드는 "회수를 놓치는 쪽"으로, 수정안은 "멀쩡한 job 을 죽이는 쪽"으로 기운다. 어느 쪽이 나은지는 확정/환불의 멱등성이 어디까지 보장되는지에 달렸고, 그건 다음 챕터의 문제다.

지표 관점에서 중요한 것은 따로 있다. **이 사고에서 `credit_job_recovery_total` 은 어느 라벨도 오르지 않는다.** `backstop` 이 오르면 "heartbeat 가 샜다"를 알 수 있지만, 둘 다 0 인 것은 "사고가 없었다"와 구분되지 않는다. 이 사고를 지표로 잡으려면 **회수 시도 실패 카운터**(`credit_job_recovery_failed_total{reason="heartbeat_unavailable"}` 같은)가 있어야 하고, 지금은 없다. `oldest_pending_age` 가 오르는 것만이 유일한 간접 흔적이다. 없는 지표를 찾는 것이 이 단계의 절반이었고, 이게 그 답이다.

> **→ 후속 1 에서 고쳤다.** 회수 시도 실패 카운터는 결국 만들지 않았다 — 회수가 실패하는 대신 `backstop_blind` 로 **성공하면서** 기록되기 때문에 불필요해졌다. 아래 [후속 1](#후속-1--백스톱을-redis-없이도-돌게-한다) 참고.

#### 3. `worker_claim/applied` 는 처리량이 아니다 (5단계에서 발견, 여기서 재확인)

5단계에서 `worker_claim/applied` 가 job 수의 3~4배로 나온 이유를 밝혔다 — executor 풀이 꽉 차면 `execute` 가 거부되고, `rollbackToHolding` 으로 HOLDING 으로 되돌린 뒤 다음 폴링(500ms)에서 같은 job 을 다시 선점한다. 6단계 실측이 이 해석을 두 방향에서 확인했다.

| 시나리오 | 상황 | `worker_claim/applied` | job 수 |
|---|---|---|---|
| 07 (외부 API 무한 지연) | job 3건, 워커 동시성 3 — 풀이 넘치지 않는다 | **3** | 3 |
| 05 (원장 훼손) | job 5건이 3~7초짜리 스텁을 통과 | **19** | 5 |
| 01 (워커 크래시) | job 6건 + 재시도 | **11** | 6 |

07 에서 정확히 3 이 나온 것이 결정적이다. **경합이 없으면 선점 카운터는 job 수와 정확히 같다.** 즉 이 카운터는 "선점이라는 동작이 성공한 횟수"이고, 처리량으로 읽으면 안 된다. 대시보드의 `rate(credit_defense_total)` 패널에서 `worker_claim` 만 유난히 높은 것은 사고가 아니라 **executor 포화의 신호**로 읽어야 한다.

> **→ 후속 2 에서 고쳤다.** 카운터의 해석을 바꾸는 대신 코드를 바꿨다 — 빈 슬롯 수만큼만 읽고 선점하니 헛선점 자체가 사라졌다. 아래 [후속 2](#후속-2--빈-슬롯만큼만-선점한다) 참고.
### 포트폴리오 스크린샷 가이드

Grafana(<http://localhost:3000>, 대시보드 `credit-domain`)를 열어 둔 채 스크립트를 돌리면 곡선이 실시간으로 그려진다. 시간 범위는 **Last 15 minutes**, 자동 새로고침 **5s** 로 두면 된다.

| 시나리오 | 언제 찍나 | 어느 패널 | 무엇이 보여야 하나 |
|---|---|---|---|
| 01 | SIGKILL 후 20~40초 | 3행 "죽은 job 회수 (heartbeat vs backstop)" | `heartbeat` 만 한 번 튀고 `backstop` 은 바닥에 붙어 있다 |
| 02 A | 앱 재기동 후 60~90초 | 같은 패널 | 이번에는 **`backstop` 만** 튄다. 01 과 나란히 놓으면 두 탐지기의 역할이 보인다 |
| 02 B | Redis 정지 후 60~90초 | 같은 패널 + 2행 나이 | 두 선 모두 바닥. 그런데 사고는 진행 중이다 |
| 03 | job 생성 후 6~7분 | 2행 전체 + 3행 전체 | 나이 곡선만 우상향, 방어 발동율 패널은 완전히 평평 |
| 04 (수정 전) | 재기동 후 2분 | 4행 "staleness" + 1행 stat | staleness 두 선이 **-1 에 붙어 있고** 알람은 하나도 없다 |
| 04 (수정 후) | 재기동 후 90초 | Prometheus `/alerts` | `CreditSnapshotStale`, `CreditReconciliationStale` 이 빨갛게 firing |
| 05 | (b)(c) 주입 후 15초 | 1행 stat 4칸 | 세 칸이 빨강, 한 칸(정산 안 된 종결 job)만 초록 |
| 06 | 폭풍 직후 | 3행 "멱등 방어의 역할 분담" | `app_hit` 과 `db_unique` 두 선이 같이 튄다 |
| 07 | job 생성 후 6~7분 | 2행 나이 + 3행 방어 발동율 | 위는 직선 상승, 아래는 완전히 평평 |

포트폴리오에 넣기 좋은 장면 셋을 고른다면 이렇다.

**(a) 05 의 1행 stat 패널이 빨갛게 바뀌는 순간, 나머지 전부 정상.** `05-ledger-corruption.sh` 를 돌리고 `(c)` 단계가 지난 직후에 대시보드 전체가 한 화면에 들어오게 찍는다. 위 네 칸 중 셋이 빨강인데, 3행 방어 발동율은 평평하고 4행 `up` 은 1 이다.
> 이 장면이 증명하는 문장: **"에러율 0%, 응답 시간 정상, HTTP 200 인 채로 돈이 사라진다. 인프라 대시보드로는 이 화면을 만들 수 없다."**

**(b) 07 의 `oldest_pending_age` 우상향 곡선과 그 아래 방어 발동율 패널의 평평함.** `07-external-api-hang.sh` 를 돌리고 6분쯤 지난 시점에 2행과 3행이 세로로 함께 보이게 찍는다. 위 패널의 빨간 300초 임계선을 곡선이 통과하는 순간이 들어가면 가장 좋다.
> 이 장면이 증명하는 문장: **"카운터 다섯 개가 전부 침묵하는 사고를, 게이지 하나가 단조 증가로 잡아낸다. 일어나지 않은 일에는 증가시킬 지점이 없기 때문이다."**

**(c) 02 의 회수 패널에서 `backstop` 만 튀는 장면.** `02-heartbeat-lost.sh` 의 A 구간, 앱 재기동 후 60~90초. 01 을 돌려 찍은 같은 패널(=`heartbeat` 만 튄다)과 나란히 두면 한 쌍이 된다.
> 이 장면이 증명하는 문장: **"같은 회수 지표를 detector 라벨로 쪼갠 이유가 여기 있다. 합쳐 그렸다면 두 사고가 같은 그림이 됐을 것이다."**

### 트레이드오프

- **재기동이 곧 카운터 리셋이다.** `credit_defense_total` 은 프로세스 메모리에 산다. 워커·스케줄러를 끄고 켜는 시나리오는 전부 재기동을 수반하므로, 사고 전후의 카운터를 직접 비교할 수 없다. 그래서 스크립트는 재기동 **이후**에 기준선을 잡는다. 프로덕션에서 `rate()`/`increase()` 를 쓰면 Prometheus 가 리셋을 알아서 처리하지만, 이 실험에서는 절대값을 봐야 할 때가 있어 문서에 리셋 시점을 같이 적었다.
- **시나리오 2번 B 는 PROCESSING 행을 SQL 로 심는다.** 앱이 살아 있는 채로 Redis 만 죽이면 워커 스레드도 살아 있어서 회수 대상이 자연히 생기지 않는다. "소유자가 사라진 job" 을 재현하려면 심는 수밖에 없었다. 이건 5번 시나리오와 같은 종류의 인위성이고, 대신 Redis 를 되살렸을 때 같은 행이 4초 만에 회수되는 것으로 심은 상태가 유효했음을 확인한다.
- **알람은 여전히 아무 데도 안 간다.** Alertmanager 가 없으므로 `firing` 은 Prometheus `/alerts` 페이지에서만 보인다. 스크립트가 API 로 폴링해 확인하는 것이 사람이 그 페이지를 보고 있는 것을 대신한다.
- **한 대짜리 실험이다.** `worker_claim/lost`, `confirm/stale`, `retry_claim/lost` 는 이번에도 전부 0 이었다. 이 칸들은 **두 인스턴스가 같은 job 을 두고 경쟁해야** 움직인다. compose 에 앱을 두 개 띄우는 것은 이번 범위 밖으로 뒀다 — 포트와 heartbeat 키를 나누는 설정이 더 필요하다.
- **전체 실행에 45분 걸린다.** `for: 1m` 을 채우고 300초 임계를 넘기려면 실제로 그만큼 기다려야 한다. 시간을 줄이려면 임계값을 낮춘 별도 규칙 파일이 필요한데, 그러면 "운영에 쓸 규칙을 그대로 검증한다"는 성질을 잃는다. 기다리는 쪽을 택했다.

### 이 챕터가 남기는 것

step7 전체를 닫는다.

**네 계층 중 L2 는 여전히 비어 있다.** L0(묶인 돈), L1(불변식), L3(방어 발동)은 지표·대시보드·알람이 다 있는데, L2(흐름)에는 상태별 job 수 분포도, 단계별 소요 시간 히스토그램도 없다. 그래도 이 단계를 닫는 이유는 **`oldest_pending_age` 하나가 L2 가 답해야 할 질문의 절반을 이미 답하기 때문**이다. 3번과 7번 시나리오에서 확인했듯 파이프라인이 어디서 막히든 이 게이지는 같은 방향으로 오른다. L2 가 추가로 주는 것은 **"어디서"** 이고, 그건 사고를 **감지**한 다음에 필요한 정보다. 감지가 먼저고 진단이 다음이다. 지금 대시보드는 "막혔다"까지 말하고, "HOLDING 에서 막혔는지 PROCESSING 에서 막혔는지"는 `docker compose exec mysql` 한 줄이 답한다 — 새벽 세 시에 그 한 줄을 치는 것이 나쁘긴 하지만, 지표가 없어서 사고를 **놓치는** 것과는 급이 다르다.

**관측이 드러낸 기존 코드의 결함 세 개.** 전부 관측 장치를 붙이고 실제로 흔들어 봤기 때문에 나왔다.

| # | 무엇 | 어디서 나왔나 | 상태 |
|---|---|---|---|
| 1 | staleness 규칙이 `-1`(한 번도 안 돎)을 못 잡는다 | 4번 시나리오 | **고쳤다** — `or (x < 0)`, `for: 1m` |
| 2 | `updatedAt` 백스톱이 Redis 에 의존한다 | 2번 시나리오 B | **고쳤다 (후속 1)** — `HeartbeatState.UNKNOWN` + `backstop_blind` |
| 3 | `worker_claim/applied` 가 처리량이 아니다 | 5단계, 6단계 07 에서 확정 | **고쳤다 (후속 2)** — 빈 슬롯 수만큼만 선점 + `worker_claim/rolled_back` |

1번은 **관측 장치 자신의 결함**이고 2번은 **복구 장치의 결함**이라는 점이 다르다. 1번이 더 무섭다 — 2번은 알람이 안 울려도 `oldest_pending_age` 가 오르지만, 1번은 그 게이지 자체가 0 에 얼어붙은 채 "정상"을 그린다. **관측 장치의 고장이 사고보다 나쁠 수 있다**는 것이 이 챕터의 마지막 교훈이다.

**다음에 할 것.**

1. **L2 흐름 지표.** `credit_job_status_count{status}` 게이지와 HOLDING→PROCESSING→종결 단계별 소요 시간 Timer. 3번과 7번 시나리오를 구분할 수 있게 된다(지금 둘은 `oldest_pending_age` 만 보면 같은 그림이다).
2. ~~**회수 시도 실패 카운터.**~~ **(후속 1 에서 처리)** — 만들지 않는 쪽으로 처리했다. 회수가 실패하지 않고 `backstop_blind` 로 성공하므로 셀 실패가 남지 않는다.
3. ~~**백스톱 맹점 수정.**~~ **(후속 1 에서 처리)** — "알 수 없음"으로 다루기로 했고, 오탐은 감수하되 라벨로 드러내기로 했다. "멱등성 보강 선행"이라는 조건은 attemptNo CAS 를 빠뜨린 과대평가였다.
4. ~~**`worker_claim` churn 수정.**~~ **(후속 2 에서 처리)** — 풀 여유를 확인하는 쪽을 택했다. 큐를 두는 안은 선점과 실행 사이의 창을 오히려 늘려서 버렸다.
5. **Alertmanager 라우팅.** 규칙 10개는 코드로 남았지만 여전히 아무 데도 안 간다. 붙일 채널이 정해지면 컨테이너 하나로 끝난다.

---

## 후속 1 — 백스톱을 Redis 없이도 돌게 한다

6단계 장애 주입이 찾아낸 결함 2번을 고친다. 6단계에서는 "고치지 않았다 — 기록만"이라고 썼는데, 그때 미룬 이유가 다시 보니 틀렸다. 정정도 같이 한다.

### 결함 — 최후 방어선이 자기가 방어하려던 것에 의존했다

step6 이 `updatedAt` 백스톱을 만든 명분은 "Redis 장애를 대비해 DB 로 한 번 더 스캔한다"였다. 코드는 그렇지 않았다.

`DeadJobRecoveryTask.recoverStalled` 는 PROCESSING 이 60초 넘게 정체된 job 을 회수하기 전에 `heartbeatRegistry.hasLiveHeartbeat(jobId, attemptNo)` 로 "혹시 살아 있나"를 물었다. 그 조회가 Redis 를 부르고, `hasLiveHeartbeat` 는 `refreshHeartbeat` 와 달리 예외를 삼키지 않았다. Redis 가 없으면 던지고, 항목 단위 `catch` 가 WARN 한 줄을 남기고 넘어간다. 같은 주기의 heartbeat 스캔은 `findExpiredAttempts` 에서 이미 던져 단계 단위 `catch` 로 빠진다. **Redis 가 죽으면 두 겹의 탐지기가 동시에 죽었다.**

2번 시나리오 B 의 실측이 그대로 보여준다.

```
   T+8    PROCESSING 행 3건을 updated_at=120초 전으로 심었다 (백스톱 대상)
   T+8    Redis 정지
   T+19   backstop=0 heartbeat=0 PROCESSING=3
   ... (10초 간격 9회, 전부 같은 값)
   T+100  backstop=0 heartbeat=0 PROCESSING=3
   T+101  로그 'heartbeat 만료 회수 단계 실패' 7회 / 'PROCESSING 정체 job 회수 실패' 21회
   T+103  Redis 복구 2초 만에 backstop=3
```

회수 대상 세 건이 눈앞에 있는데 90초 동안 0건. Redis 를 되살리자 곧바로 3건. **막고 있던 것은 오직 Redis 였다.** 그리고 그 90초 동안 `credit_job_recovery_total` 은 두 라벨 모두 0 이어서, 지표만 보는 사람에게는 "회수할 것이 없는 평온한 시간"과 똑같이 생겼다.

### 수정 — `Boolean` 을 3상태로 바꾼다

핵심은 **"heartbeat 가 없다"와 "heartbeat 저장소를 못 봤다"는 다른 사실**이라는 것이다. `Boolean` 은 둘을 표현할 수 없다.

```kotlin
enum class HeartbeatState { LIVE, ABSENT, UNKNOWN }
```

- `LIVE` — 조회에 성공했고 만료되지 않은 score 가 있다
- `ABSENT` — 조회에 성공했고 없거나 이미 만료됐다
- `UNKNOWN` — Redis 에 닿지 못했다. 살아 있는지 아닌지 알 수 없다

`false` 하나로 뭉치면 Redis 장애가 heartbeat 부재로 둔갑하고, 예외로 뭉치면 Redis 장애가 회수 자체를 막는다. **판단은 조회하는 쪽이 아니라 호출자가 한다.** 그래서 `heartbeatState` 는 Redis 예외를 삼키되 삼킨 사실을 값으로 돌려준다.

회수 판정은 이렇게 갈린다.

| heartbeat | 회수 | detector | 뜻 |
|---|---|---|---|
| `LIVE` | 하지 않는다 | — | 워커가 살아 있다 |
| `ABSENT` | 한다 | `backstop` | heartbeat 가 있어야 했는데 없었다 — heartbeat 누수 신호 |
| `UNKNOWN` | **한다** | `backstop_blind` | 저장소가 안 보여 `updatedAt` 만 믿었다 — Redis 장애 신호, **오탐 가능** |

`UNKNOWN` 에서 회수하는 쪽을 택한 이유는 단순하다. 이 백스톱의 존재 이유가 "heartbeat 저장소가 죽어도 돈이 묶인 채 방치되지 않는다"인데, 저장소가 안 보인다고 회수를 멈추면 **장치가 스스로를 부정한다.**

### 왜 오탐을 감수해도 되는가 — 6단계 판단의 정정

6단계 문서는 이렇게 썼다.

> 수정안은 "멀쩡한 job 을 죽이는 쪽"으로 기운다. 어느 쪽이 나은지는 확정/환불의 멱등성이 어디까지 보장되는지에 달렸고, 그건 다음 챕터의 문제다.

**이 문장은 과대평가였다.** step4 의 attemptNo CAS 가 이미 오탐의 비용을 돈에서 분리해 놓았는데, 그걸 계산에 넣지 않았다.

오탐이 실제로 일어나는 경로를 끝까지 따라가 보면 이렇다.

1. Redis 순단 중, 60초 넘게 진행 중인 **살아 있는** job(attempt N)이 정체 스캔에 걸린다
2. `heartbeatState` 가 `UNKNOWN` → `failIfProcessing(N)` 이 1행 → FAILED
3. 재시도로 attempt N+1 이 되고 워커 B 가 새로 처리한다
4. 원래 워커 A 가 뒤늦게 끝나 `confirm` 을 부른다 → `completeIfAttemptMatches` 의 `WHERE status = PROCESSING AND attemptNo = :N` 이 어긋나 **0행**
5. A 는 `credit_defense_total{point="confirm",outcome="stale"}` 을 올리고 물러난다

돈의 관점에서 hold 는 1회, confirm 은 1회(N+1). **잔액 불변식은 깨지지 않는다.** 남는 비용은 워커 A 가 이미 태워 버린 **외부 생성 API 호출 1회**와 결과가 한 세대 늦어지는 지연이다.

두 선택지를 나란히 놓으면 이렇게 된다.

| | 수정 전 (`UNKNOWN` = 회수 안 함) | 수정 후 (`UNKNOWN` = 회수) |
|---|---|---|
| Redis 장애 중 죽은 job | **방치된다.** 돈이 묶인 채 Redis 복구까지 대기 | 60초 안에 회수되고 재시도로 넘어간다 |
| Redis 장애 중 살아 있는 job | 영향 없음 | **오탐 가능.** FAILED 로 내려가고 재시도된다 |
| 오탐의 돈 비용 | — | **없다.** attemptNo CAS 가 뒤늦은 confirm 을 0행으로 막는다 |
| 오탐의 실제 비용 | — | 외부 API 호출 1회 낭비 + 결과 지연 |
| 지표에 남는가 | **안 남는다.** 두 라벨 모두 0 = "평온"과 구분 불가 | `backstop_blind` 가 오르고, 오탐이면 `confirm/stale` 이 함께 오른다 |

**"돈이 묶인 채 아무도 모른다" 와 "외부 호출 1회를 낭비하고 그 사실이 지표에 남는다" 의 비교다.** 후자가 낫다. "확정/환불 멱등성 보강이 선행돼야 한다"던 조건은 이미 step4 에서 충족돼 있었다 — 그 챕터를 쓰고도 6단계에서 그걸 세지 못한 것이 이번에 바로잡은 판단이다.

### `removeHeartbeat` 도 예외를 삼킨다

같은 수정에서 `removeHeartbeat` 의 예외도 삼키게 바꿨다(WARN 만 남긴다). 회수가 끝난 **뒤의 뒷정리**라 실패해도 상태 전이는 이미 일어났고, 지우지 못하고 남은 ZSET 엔트리는 나중에 `findExpiredAttempts` 가 다시 집어 온다. 그때 `failIfProcessing` 이 0행을 돌려주므로 아무 일도 일어나지 않고 조용히 소거된다. **무해한 잔여물**이지 재시도해야 할 실패가 아니다.

`findExpiredAttempts` 는 **그대로 던지게 뒀다.** 그 단계는 본질적으로 Redis 단계다 — Redis 가 없으면 할 수 있는 일이 없고, 단계 단위 `catch` 가 받아 이번 주기만 건너뛰는 것이 정확한 동작이다.

### 파일별 변경 목록

| 파일 | 변경 |
|---|---|
| `heartbeat/HeartbeatRegistry.kt` | `HeartbeatState` enum 추가. `hasLiveHeartbeat` → `heartbeatState` (Redis 예외를 `UNKNOWN` 으로). `removeHeartbeat` 가 예외를 삼킨다 |
| `job/event/JobRecovered.kt` | `RecoveryDetector.BACKSTOP_BLIND` 추가. 세 값의 뜻을 KDoc 으로 |
| `job/scheduling/DeadJobRecoveryTask.kt` | `recoverStalled` 가 3상태로 분기. `UNKNOWN` 이면 WARN 을 남기고 회수 |
| `prometheus/rules/credit.rules.yml` | `CreditBackstopBlindRecovery` (P2) 추가 — 규칙 10개 → 11개 |
| `scenarios/02-heartbeat-lost.sh` | B 구간 기대를 "0건(결함)" 에서 "`backstop_blind` ≥ 1" 로 뒤집음 |
| `HeartbeatRegistryTest` / `DeadJobRecoveryTaskTest` / `DefenseMetricsTest` | 아래 참고 |

`DefenseMetrics` 는 **손대지 않았다.** 사전 등록이 `RecoveryDetector.entries` 를 도니 새 값이 자동으로 0 으로 깔린다. 대시보드 JSON 도 `sum by (detector) (...)` 라 새 라벨을 알아서 그린다. **enum 하나 추가에 관측 코드가 따라오지 않는 것**이 2단계에서 "enum 이 카디널리티 가드다"라고 쓴 설계가 실제로 값을 낸 자리다.

### 핵심 코드 읽기

**전 — 조회가 판단까지 해 버린다**

```kotlin
fun hasLiveHeartbeat(jobId: Long, attemptNo: Int): Boolean {
    val member = JobAttempt(jobId, attemptNo).toMember()
    val score = redisTemplate.opsForZSet().score(KEY, member)   // Redis 가 없으면 던진다
    return score != null && score > Instant.now().epochSecond
}
```

**후 — 조회는 사실만 돌려주고, 판단은 호출자가 한다**

```kotlin
fun heartbeatState(jobId: Long, attemptNo: Int): HeartbeatState {
    val member = JobAttempt(jobId, attemptNo).toMember()
    val score = try {
        redisTemplate.opsForZSet().score(KEY, member)
    } catch (e: RuntimeException) {
        log.warn("heartbeat 조회 실패, UNKNOWN 으로 처리: jobId={}, attemptNo={}", jobId, attemptNo, e)
        return HeartbeatState.UNKNOWN
    }
    return if (score != null && score > Instant.now().epochSecond) {
        HeartbeatState.LIVE
    } else {
        HeartbeatState.ABSENT
    }
}
```

**전 — `true` 면 물러나고, 예외면 통째로 건너뛴다**

```kotlin
private fun recoverStalled(job: Job) {
    try {
        val jobId = job.persistedId
        if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) return
        ...
    } catch (e: RuntimeException) {
        log.warn("PROCESSING 정체 job 회수 실패: ...", e)   // ← Redis 장애가 여기로 삼켜졌다
    }
}
```

**후 — `LIVE` 에서만 물러난다**

```kotlin
val state = heartbeatRegistry.heartbeatState(jobId, job.attemptNo)
if (state == HeartbeatState.LIVE) {
    return
}
if (state == HeartbeatState.UNKNOWN) {
    log.warn("heartbeat 저장소에 닿지 않아 updatedAt 만으로 회수: jobId={}, attemptNo={}", jobId, job.attemptNo)
}
val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
if (updated == 1) {
    log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
    eventPublisher.publishEvent(JobRecovered(jobId, job.attemptNo, detectorFor(state)))
    heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)   // ← 이벤트 발행 뒤로 옮겼다
}
```

`removeHeartbeat` 를 이벤트 발행 **뒤로** 옮긴 것은 사소해 보이지만 의도가 있다. 뒷정리가 이벤트 발행을 막으면 안 된다 — 지금은 예외를 삼키니 순서가 무의미하지만, 순서 자체가 "회수 사실을 알리는 것이 먼저고 청소는 나중"이라는 우선순위를 코드로 적어 둔 것이다.

### 새 알람 규칙

```yaml
      - alert: CreditBackstopBlindRecovery
        expr: increase(credit_job_recovery_total{detector="backstop_blind"}[10m]) > 0
        for: 0m
        labels:
          severity: P2
        annotations:
          summary: "blind backstop 회수 {{ $value }}건"
          description: "heartbeat 저장소에 닿지 않아 updatedAt 만으로 회수했다. Redis 를 확인하라. 이 회수는 오탐일 수 있으며, 오탐이면 credit_defense_total{point=\"confirm\",outcome=\"stale\"} 이 함께 오른다."
```

`CreditBackstopRecovery` 와 굳이 나눈 이유는 **원인이 다르기 때문**이다. `backstop` 은 "heartbeat 가 샜다"(Redis 는 살아 있는데 엔트리가 없다), `backstop_blind` 는 "Redis 가 죽었다". 대응이 다르므로 라벨도 알람도 갈라야 한다. description 에 `confirm/stale` 을 같이 보라고 적은 것은 **오탐 여부를 확인하는 방법**이 그것뿐이기 때문이다.

그리고 6단계가 "이 사고를 잡을 지표가 없다, `credit_job_recovery_failed_total{reason}` 이 필요하다"고 썼던 항목은 **만들지 않았다.** 이 수정 뒤로는 회수가 **실패하지 않는다** — Redis 가 안 보여도 `backstop_blind` 로 성공하고, 그 성공이 곧 사고의 기록이다. **없는 지표를 추가하는 대신 실패를 성공으로 바꾸는 것이 답이었다.**

### 테스트가 보장하는 것

| 테스트 | 보장 |
|---|---|
| `HeartbeatRegistryTest` | 미래 score → `LIVE`, 없음/만료 → `ABSENT`, Redis 예외 → **`UNKNOWN` 이고 예외가 새지 않는다** |
| 〃 | `removeHeartbeat` 와 `stopHeartbeat` 이 Redis 예외를 삼킨다 |
| 〃 | `findExpiredAttempts` 는 여전히 **전파한다** (의도적으로 다르다) |
| `DeadJobRecoveryTaskTest` | `LIVE` → `failIfProcessing` 호출 안 됨 / `ABSENT` → `BACKSTOP` / `UNKNOWN` → `BACKSTOP_BLIND` |
| 〃 | `removeHeartbeat` 가 던져도 `JobRecovered` 는 발행된다 |
| `DefenseMetricsTest` | `backstop_blind` 가 이벤트 없이도 0 으로 사전 등록돼 있다 |

전체 177개(6단계의 175개 + 추가 2개), 전부 통과.

### 02 재실행 실측

`./gradlew bootJar` 후 `02-heartbeat-lost.sh` 를 그대로 다시 돌렸다. A 구간은 회귀 확인, B 구간이 이번 수정의 실증이다.

**B — Redis 정지 11초 만에 3건 회수.**

```
   T+9    PROCESSING 행 3건을 updated_at=120초 전으로 심었다 (백스톱 대상)
   T+9    Redis 정지
   [ 11s] recovery{backstop_blind} >= 1 — 참
   T+20   blind 회수 감지 — Redis 정지 이후 11초
   [  8s] CreditBackstopBlindRecovery firing — 참
   T+28   firing 알람: [CreditBackstopBlindRecovery CreditBackstopRecovery]
   T+38   blind=3 backstop=0 heartbeat=0 PROCESSING=0 HOLDING=3
   ... (10초 간격 6회, 전부 같은 값)
   T+90   Redis 다운 90초 결산: blind=3 backstop=0 heartbeat=0
   T+90     로그 'heartbeat 만료 회수 단계 실패' 10회 / 'updatedAt 만으로 회수' 3회
   T+120  Redis 복구 30초 후: blind=3 backstop=0 heartbeat=0 — COMPLETED=4 HOLDING=3
   [  6s] 미결 job(HOLDING+PROCESSING) 0건 — 참
   T+132  B 종료 — COMPLETED=7
```

**11초.** 같은 사고, 같은 심은 행, 같은 90초인데 수정 전에는 0건이었다. 11초의 내역은 스캔 주기 5초 + Prometheus 스크레이프 5초이고, `updatedAt` 이 이미 타임아웃(60초)을 넘긴 행을 심었으므로 실질 감지는 **다음 스캔 주기 한 번**이다. 자연 발생 사고라면 여기에 `processing.timeout-seconds`(60초)가 더 붙는다.

```
WARN c.e.c.j.scheduling.DeadJobRecoveryTask : heartbeat 저장소에 닿지 않아 updatedAt 만으로 회수: jobId=6, attemptNo=0
```

읽어야 할 숫자가 셋 더 있다.

- **`backstop=0`, `heartbeat=0`.** 회수는 전부 `backstop_blind` 로만 기록됐다. 라벨이 원인을 정확히 가리킨다 — heartbeat 가 샌 것이 아니라 Redis 가 죽었다.
- **`'updatedAt 만으로 회수'` WARN 이 정확히 3회.** 심은 행 수와 같다. 수정 전 같은 자리의 WARN 은 21회(주기 7번 × 3건)였다 — **매 주기 재시도하며 실패하던 것이, 한 번에 성공하고 끝났다.** `'단계 실패'` ERROR 10회는 여전히 남는데, 그건 `findExpiredAttempts` 를 일부러 던지게 둔 heartbeat 스캔 쪽이고 의도한 동작이다.
- **Redis 복구 후 추가 회수 0건.** 수정 전에는 복구 직후 3건이 몰렸다(=그때까지 못 하고 있었다는 증거). 이번에는 복구해도 아무 일이 없다 — **이미 다 했기 때문이다.** 회수된 job 은 HOLDING 으로 돌아가 있었고, 워커를 켜자 6초 만에 전부 COMPLETED 로 종결됐다(`COMPLETED=7`, 불변식 4종 합 0).

한 가지 함정을 여기서 또 밟았다. 워커를 켠 뒤 종결을 `credit_hold_outstanding_count == 0` 으로 확인했더니 **재기동 직후 0초 만에 "참"** 이 나왔다. 실제로는 PROCESSING 3건이 돌고 있었고, 게이지가 첫 스냅샷 전의 초깃값 0 이었을 뿐이다. 4번 시나리오가 찾은 그 구멍과 정확히 같은 모양이라 스크립트를 DB 직접 조회로 바꿨다. **"재기동 직후의 0 은 값이 아니다"** 는 규칙은 알람뿐 아니라 검증 스크립트에도 적용된다.

**A — 회귀 없음.** ZSET 을 날린 A 구간은 Redis 가 살아 있으므로 `heartbeatState` 가 `ABSENT` 를 돌려주고, 이전과 똑같이 `backstop` 으로 잡힌다.

| 항목 | 기대 | 관측 |
|---|---|---|
| A `recovery{backstop}` | ≥ 1 | **3** (앱 UP 이후 55초) |
| A `recovery{heartbeat}` | 0 | 0 |
| A `recovery{backstop_blind}` | **0 (회귀 확인)** | **0** |
| B Redis 다운 `recovery{backstop_blind}` | ≥ 1 | **3 (+11초)** |
| B Redis 다운 `recovery{backstop}` / `{heartbeat}` | 0 | 0 / 0 |
| B `CreditBackstopBlindRecovery` | firing | firing (+8초) |
| B `'updatedAt 만으로 회수'` WARN | 3회 | 3회 |
| B Redis 복구 후 추가 회수 | 없음 | blind=3 backstop=0 (변화 없음) |
| B 워커 재개 후 종결 | 종결 | 6초 / `COMPLETED=7` |
| invariant 4종 합 | 0 | 0 |

**01 도 함께 돌려 회귀를 봤다.** 워커 크래시는 Redis 가 멀쩡한 사고이므로 heartbeat 탐지기가 그대로 잡아야 한다.

```
   recovery{detector=heartbeat}     >= 1      3   (앱 UP 이후 8초 / SIGKILL 이후 13초)
   recovery{detector=backstop}      0         0
   credit_job_recovery_total{'detector': 'backstop_blind'}   0
   firing 알람                      없음      [ ]
```

`backstop_blind` 는 0 으로 깔린 채 한 번도 오르지 않았고, 알람도 침묵했다. **새 라벨이 정상 경로를 오염시키지 않는다**는 것이 이 줄의 뜻이다.

### 남는 것

**`UNKNOWN` 이 길게 지속되면 정상 job 이 재시도 루프에 들어간다.** Redis 가 오래 죽어 있고 `processing.timeout-seconds`(60초)를 넘기는 job 이 많으면, 살아 있는 job 들이 매 주기 회수 대상이 된다. `generation.max-attempts`(3)가 있어 무한하지는 않지만 **소진하면 최종 환불로 끝난다** — 즉 Redis 장기 장애 + 긴 job 조합에서는 정상 처리될 job 이 환불로 종결될 수 있다. 이건 이번 수정이 **감수하기로 한** 위험이지 놓친 것이 아니다. 완화책 둘을 예고만 해 둔다.

1. **`UNKNOWN` 연속 주기 상한.** N주기 연속 `UNKNOWN` 이면 blind 회수를 중단하고 별도 알람만 올린다 — "Redis 순단"과 "Redis 장기 장애"를 다르게 다루는 것이다.
2. **`processing.timeout-seconds` 를 실제 분포에 맞춘다.** 지금 60초는 스텁(3~7초) 기준이고, 실제 생성 API 의 p99 를 재서 다시 잡아야 오탐 확률 자체가 내려간다.

### 포트폴리오 서술 정정

포트폴리오 7페이지에 이렇게 써 있다.

> 저장소 장애를 대비해 DB 의 `updatedAt` 으로 한 번 더 스캔한다

**이 문장은 이 수정 뒤에야 참이 된다.** 6단계 이전 코드에서는 백스톱이 Redis 를 참조했으므로 "저장소 장애를 대비해"가 성립하지 않았다. 고쳐 쓴다면 이렇게 쓰는 편이 정확하고, 이 프로젝트에서 실제로 배운 것도 그쪽이다.

> heartbeat 저장소가 죽으면 조회 결과를 `UNKNOWN` 으로 다루고, DB 의 `updatedAt` 만으로 회수한다. 살아 있는 job 을 오판할 수 있지만 시도 번호 CAS 가 잔액 불변식을 지키므로 비용은 외부 호출 1회이며, 그 오판은 `backstop_blind` 카운터에 남는다.

**"장애를 대비했다"보다 "장애 때 무엇을 포기하고 무엇을 지키는지"가 설계를 말한다.**

---

## 후속 2 — 빈 슬롯만큼만 선점한다

6단계 장애 주입이 찾아낸 결함 3번을 고친다. 이번에는 정정할 판단이 아니라 **관측 결함으로 분류한 것이 사실은 코드 결함이었다**는 재분류다.

### 결함 — 카운터가 이상한 게 아니라 코드가 헛돌고 있었다

`worker_claim/applied` 가 job 수와 안 맞는 것을 5단계에서 발견하고 6단계에서 재확인했다. 세 번의 실측이 같은 방향을 가리켰다.

| 실측 | job 수 + 재시도 | `worker_claim/applied` | 배수 |
|---|---|---|---|
| 5단계 smoke | 10 + 3 = **13** | **44** | 3.4배 |
| 6단계 05 (원장 훼손) | **5** | **19** | 3.8배 |
| 6단계 07 (외부 API 무한 지연, 경합 없음) | **3** | **3** | 1.0배 |

07 이 정확히 3 이었다는 것이 진단의 핵심이었다. **풀이 넘치지 않으면 배수가 1 이다.** 즉 초과분은 전부 executor 포화 구간에서 나온다.

포화 구간의 한 주기는 이렇게 돌았다.

```
   findByStatusOrderByIdAsc(HOLDING, PageRequest.of(0, 3))   ← batchSize 만큼 읽고
   startProcessingIfAttemptMatches(...)  → 1행                ← 선점 UPDATE (worker_claim/applied +1)
   workerExecutor.execute { ... }        → TaskRejectedException
   rollbackToHoldingIfProcessing(...)    → 1행                ← 롤백 UPDATE
   return                                                     ← 이번 주기 중단
   ... 500ms 뒤 같은 job 을 다시 선점
```

5단계 실측의 `44 = 13 + 31` 에서 31 이 이 헛선점 횟수였고, 그 31 은 `"executor 위임 실패"` WARN 로그 수와 정확히 같았다.

### 왜 이걸 코드 결함으로 다시 분류했나

6단계 문서는 이걸 "설계대로의 동작이지만 대시보드에서 처리량으로 읽으면 안 된다"고 정리하고 해석 규칙으로 남겼다. 다시 보니 그 결론은 **지표의 관점에서만 옳았다.**

step6 이 만든 "거부되면 선점을 되돌린다"는 안전망 자체는 옳다. 되돌리지 않으면 job 이 PROCESSING 에 갇혀 timeout 회수를 기다려야 하니까. 문제는 그 안전망이 **예외 경로가 아니라 매 주기의 정상 경로**로 쓰이고 있었다는 것이다. 워커 풀이 꽉 찬 동안 500ms 마다 **DB UPDATE 두 번**(선점 + 롤백)이 아무 일도 하지 않고 돈다. 처리량이 높을수록 풀은 더 오래 꽉 차 있으므로, **부하가 클수록 헛도는 쓰기가 늘어나는** 모양이다. 지표가 이상하게 읽히는 것은 그 낭비의 **증상**이었지 원인이 아니었다.

관측을 붙인 값이 여기 있다. 이 낭비는 HTTP 응답에도, ERROR 로그에도, 최종 상태에도 안 남는다. 카운터 하나가 예상과 어긋난 것이 유일한 흔적이었다.

### 수정 — 받아 줄 만큼만 읽는다

선점부터 하고 executor 가 받아 주길 기대하는 대신, **빈 슬롯을 먼저 세고 그만큼만 읽는다.**

```kotlin
fun interface WorkerSlots {
    fun free(): Int
}
```

풀 구현을 디스패처에 노출하지 않으려고 좁은 인터페이스로 끊었다. 구현은 한 줄이다 — `maxPoolSize - activeCount`.

`free <= 0` 이면 **DB 조회조차 하지 않고 즉시 돌아온다.** 넘길 곳이 없으면 읽어도 쓸 데가 없으므로, 헛선점뿐 아니라 포화 구간의 폴링 SELECT 도 같이 사라진다. `free > 0` 이면 `PageRequest.of(0, minOf(batchSize, free))` 로 읽는다. `batchSize` 는 여전히 **상한**으로 의미가 있다 — 슬롯이 아무리 많아도 한 주기에 그 이상 읽지 않는다.

### 경쟁 조건이 없는 이유

`activeCount` 는 순간값이다. 읽고 나서 쓰는 사이에 값이 바뀔 수 있는데도 왜 안전한가.

**이 풀에 task 를 넣는 스레드가 하나뿐이기 때문이다.** `@Scheduled` 디스패처만 `execute` 를 부르고, `fixedDelay` 는 이전 실행이 끝난 뒤 다음을 잡으므로 디스패처가 둘 겹치지도 않는다. 다른 스레드는 task 를 **끝내면서 `activeCount` 를 줄이기만** 한다.

따라서 주기 시작에 읽은 `free` 는 그 주기 동안 **과소평가일 수는 있어도 과대평가일 수 없다.** 다른 스레드가 그 사이에 슬롯을 채가는 일이 없다. 한 주기에 `free` 개 이하만 넘기면 `maxPoolSize` 를 넘을 수 없다.

`activeCount` 가 새 스레드 기동 직후 잠깐 낮게 읽히는 창이 있어도 결론은 같다. 우리가 제한하는 것은 "풀의 상태"가 아니라 **"이번 주기에 우리가 넘긴 수"** 자체이기 때문이다. 낮게 읽히면 `free` 가 커져 한 번 더 넘기려 하는데, 그 스레드는 이미 실행 중인 task 를 세고 있으므로 실제로는 더 적은 자리가 남은 상태다 — 이 경우가 유일하게 거부로 이어질 수 있는 창이고, 그래서 안전망을 지웠다면 안 됐다.

### 안전망은 남기되, 타면 소리를 내게 했다

거부 → 롤백 경로는 **지우지 않았다.** executor shutdown 중 거부처럼 슬롯 계산과 무관하게 거부가 나는 경우가 남아 있고, 안전망이 없으면 그때 job 이 PROCESSING 에 갇힌다.

대신 의미가 바뀌었다. **이제 이 경로가 타는 것은 정상 동작이 아니라 "슬롯 계산이 틀렸다"는 신호다.** 신호는 지표로 나가야 한다.

```kotlin
/** 선점했지만 executor 가 받지 못해 HOLDING 으로 되돌렸다.
 *  후속 2 이후 이 값이 오르면 슬롯 계산에 구멍이 있다는 뜻이다. */
ROLLED_BACK
```

`DefenseOutcome` 에 값 하나를 더하고 `VALID_COMBINATIONS` 의 `WORKER_CLAIM` 에 얹었다. 유효 조합은 14개에서 **15개**가 됐다. 2단계가 "enum 이 카디널리티 가드다"라고 쓴 대로, 태그 값의 집합은 여전히 컴파일 타임에 닫혀 있다.

**로그가 이미 있는데 왜 카운터를 더하나.** WARN 로그는 사람이 `grep` 을 칠 때만 존재한다. 5단계에서 31회를 세는 데 실제로 `grep -c` 가 필요했다. 이 값이 0 이 아닌 것은 알람이 걸릴 만한 사실이고, 알람은 시계열에만 걸린다.

### 지표의 의미 변화

| | 수정 전 | 수정 후 |
|---|---|---|
| `worker_claim/applied` 의 뜻 | 선점 UPDATE 가 성공한 횟수 (헛선점 포함) | **executor 에 실제로 넘긴 job 수 = 처리량** |
| 기대값 | 없다. 풀 포화 정도에 따라 job 수의 1~4배 | **`hold_balance/applied` + `retry_claim/applied`** |
| 대시보드에서 튈 때 | executor 포화의 간접 신호 | 처리량 증가 |
| `worker_claim/rolled_back` | (없음) | 0 이어야 정상. 0 이 아니면 슬롯 계산 결함 |

재시도된 job 은 다시 HOLDING 으로 돌아가 다시 선점되므로 "job 수 + 재시도 수"가 기댓값이다. 재시도 수는 `retry_claim/applied` 다 — `mark_failed/applied` 가 아니다. 최종 환불로 끝난 실패는 HOLDING 으로 돌아가지 않아 다시 선점되지 않기 때문이다(`mark_failed = retry_claim + final_refund`).

### 파일별 변경 목록

| 파일 | 변경 |
|---|---|
| `job/worker/WorkerExecutorConfig.kt` | `fun interface WorkerSlots` 추가. `workerSlots` 빈 = `maxPoolSize - activeCount` |
| `job/worker/GenerationWorker.kt` | `free <= 0` 이면 조회 없이 return. `PageRequest.of(0, minOf(batchSize, free))`. 거부 시 `ROLLED_BACK` 발행 |
| `global/event/DefenseTriggered.kt` | `DefenseOutcome.ROLLED_BACK` 추가 |
| `observability/DefenseMetrics.kt` | `WORKER_CLAIM` 유효 조합에 `ROLLED_BACK` — 14개 → 15개 |
| `GenerationWorkerUnitTest` / `WorkerSlotsTest` / `DefenseMetricsTest` / `MetricsCardinalityConfigTest` | 아래 참고 |
| `deploy/observability/prometheus/rules/credit.rules.yml` | `CreditWorkerClaimRolledBack`(P2) 추가 — `rolled_back` 이 10분 안에 1건이라도 오르면 울린다. 규칙 11개 → 12개 |

`WorkerProperties` 와 `application.yml` 은 **손대지 않았다.** `batchSize` 의 의미가 "한 주기에 읽는 수"에서 "한 주기에 읽는 수의 상한"으로 좁아졌을 뿐 설정 값은 그대로다. 대시보드 JSON 은 그대로다 — `sum by (point, outcome)` 이라 새 라벨이 알아서 그려진다. 알람 규칙은 하나 늘었다: "`rolled_back` 은 0 이어야 정상"이 이 수정의 전제이므로, 전제가 깨지는 순간을 사람이 대시보드에서 우연히 발견하게 두지 않고 `CreditWorkerClaimRolledBack` 이 울리게 했다.

### 핵심 코드 읽기

**전 — 선점하고 나서 받아 주길 기대한다**

```kotlin
@Scheduled(fixedDelayString = "...")
fun dispatchPendingJobs() {
    val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
    for (job in jobs) {
        if (!claim(job)) continue          // ← 풀 상태와 무관하게 선점한다
        if (!dispatch(job)) return         // ← 거부되면 롤백하고 이번 주기 중단
    }
}
```

**후 — 받아 줄 만큼만 읽는다**

```kotlin
@Scheduled(fixedDelayString = "...")
fun dispatchPendingJobs() {
    val free = workerSlots.free()
    if (free <= 0) {
        return                             // ← 넘길 곳이 없으면 조회조차 하지 않는다
    }
    val jobs = jobRepository.findByStatusOrderByIdAsc(
        JobStatus.HOLDING, PageRequest.of(0, minOf(batchSize, free))
    )
    for (job in jobs) {
        if (!claim(job)) continue
        if (!dispatch(job)) return         // ← 안전망으로 남는다. 타면 ROLLED_BACK 이 오른다
    }
}
```

`for` 루프의 모양은 그대로다. 바뀐 것은 **루프에 들어가는 원소 수의 상한**뿐이고, 그 한 줄이 헛선점을 없앤다.

```kotlin
@Bean
fun workerSlots(
    @Qualifier("generationWorkerExecutor") executor: ThreadPoolTaskExecutor
): WorkerSlots = WorkerSlots { executor.maxPoolSize - executor.activeCount }
```

### 테스트가 보장하는 것

| 테스트 | 보장 |
|---|---|
| `GenerationWorkerUnitTest` | `free == 0` → `findByStatusOrderByIdAsc` 가 **호출되지 않는다**. 선점도 없다 |
| 〃 | `free == 2, batchSize == 3` → `PageRequest` 의 `pageSize` 가 **2** (`argumentCaptor` 로 잡는다) |
| 〃 | `free == 5, batchSize == 3` → `pageSize` 가 **3**. `batchSize` 가 상한으로 남는다 |
| 〃 | 거부 → 롤백 경로가 `WORKER_CLAIM/ROLLED_BACK` 을 발행한다 |
| 〃 | 기존 단언 전부 유지 — 슬롯 무제한을 넘겨 예전과 같은 조건에서 돌린다 |
| `WorkerSlotsTest` | 실제 `ThreadPoolTaskExecutor`(concurrency 2)를 latch 로 막으면 `free() == 0`, 풀면 다시 2 |
| `DefenseMetricsTest` | `worker_claim/rolled_back` 이 이벤트 없이도 0 으로 사전 등록돼 있다. 조합 15개 |
| `MetricsCardinalityConfigTest` | 카디널리티 가드가 걸린 실제 컨텍스트에서도 15개가 그대로 보인다 |

전체 181개(후속 1 의 177개 + 추가 4개), 전부 통과.

`WorkerSlotsTest` 만 실제 스레드 풀을 쓴다. `maxPoolSize - activeCount` 는 한 줄이지만 틀리면 조용히 망가진다 — 항상 0 이면 워커가 아무것도 안 하고, 항상 양수면 수정 전으로 돌아간다. 타이밍 의존을 피하려고 `CountDownLatch` 로 task 를 붙잡고 `Awaitility` 로 기다린다.

### 실측 — 헛선점이 사라졌다

`./gradlew bootJar` 후 스택을 올려 `seed.sh` + `smoke.sh` 를 5단계와 똑같이 돌렸다.

**1. smoke (5단계와 같은 트래픽)**

```
  point=hold_balance  outcome=applied      10
  point=retry_claim   outcome=applied       1
  point=worker_claim  outcome=applied      11
  point=worker_claim  outcome=lost          0
  point=worker_claim  outcome=rolled_back   0
  point=confirm       outcome=applied      10
  point=mark_failed   outcome=applied       1
  point=final_refund  outcome=applied       0
```

```
$ docker compose ... logs app | grep -c "executor 위임 실패"
0
```

`10 + 1 = 11`. **기댓값과 정확히 같다.** 5단계 같은 자리는 13 이어야 할 것이 44 였고 위임 실패 로그가 31회였다. DB 최종 상태는 `COMPLETED 10`, 잔액 0, 불변식 4종 전부 0, fire 중인 알람 없음.

**2. 포화 상황** — 2000 크레딧을 더 충전하고 job 20건을 동시에(백그라운드 20개 `curl`) 밀어 넣었다. 워커 동시성은 3 이므로 투입 직후 `HOLDING 20` 이 그대로 쌓였고, 풀은 처리 내내 꽉 차 있었다. 이게 5단계에서 헛선점 31회가 나온 바로 그 조건이다.

```
  point=hold_balance  outcome=applied      30      ← smoke 10 + burst 20
  point=retry_claim   outcome=applied       9
  point=worker_claim  outcome=applied      39
  point=worker_claim  outcome=rolled_back   0
  point=confirm       outcome=applied      30
  point=mark_failed   outcome=applied       9
```

```
$ docker compose ... logs app | grep -c "executor 위임 실패"
0
```

`30 + 9 = 39`. **풀이 내내 포화였는데도 배수가 정확히 1 이다.**

| 항목 | 수정 전 (5단계) | 수정 후 smoke | 수정 후 포화 |
|---|---|---|---|
| job 수 + 재시도 | 13 | 11 | 39 |
| `worker_claim/applied` | **44** | **11** | **39** |
| 배수 | 3.4배 | **1.0배** | **1.0배** |
| `worker_claim/rolled_back` | (없던 지표) | **0** | **0** |
| `"executor 위임 실패"` 로그 | **31회** | **0회** | **0회** |
| 불변식 4종 / 대사 불일치 | 0 | 0 | 0 |
| firing 알람 | 없음 | 없음 | 없음 |

포화 구간에서 `free <= 0` 인 주기가 조회조차 하지 않았다는 것은 로그로 보이지 않는다. 그러나 위임 실패 0회는 **선점한 job 을 전부 executor 가 받았다**는 뜻이고, 동시성 3 인 풀이 20건을 처리하는 동안 매 주기 3건씩 읽었다면 위임 실패가 반드시 났을 것이다. 지표 하나가 두 사실을 같이 증명한다.

끝나고 `down -v` 로 내렸다.

### 남는 것

**`batchSize` 가 `concurrency` 보다 크면 의미가 없다.** `min(batchSize, free)` 이고 `free <= concurrency` 이므로, `batchSize > concurrency` 인 설정은 아무 효과가 없는 값이다(지금 설정은 3, 3 이라 마침 같다). `WorkerProperties` 의 `init` 에 `require(batchSize <= concurrency)` 같은 불변식을 넣을 수 있는데 **넣지 않았다.** 두 값은 원래 다른 것을 뜻하고(한 번에 읽을 양 / 동시에 처리할 양), 지금은 우연히 상한 관계가 생겼을 뿐이다. 나중에 큐를 두면 다시 갈라진다. **의미가 겹치는 순간의 스냅샷을 설정 불변식으로 굳히면 나중에 푸는 비용이 더 크다.**

**여러 인스턴스면 슬롯은 인스턴스별이다.** `WorkerSlots` 는 자기 JVM 의 풀만 본다. 인스턴스 A 와 B 가 각각 3자리를 비워 두고 같은 HOLDING job 을 읽으면 DB 에서 부딪히는데, 그건 이 수정이 없애려던 헛선점이 아니라 **원래 있어야 할 경쟁**이고 `startProcessingIfAttemptMatches` 의 CAS 가 그대로 처리한다. 진 쪽은 `worker_claim/lost` 로 남는다. 즉 이 수정 뒤에도 **`applied` 는 처리량, `lost` 는 인스턴스 간 경합**으로 각각 읽히고, 두 값이 섞이지 않는다는 것이 이번 수정이 만든 상태다. 단일 인스턴스인 지금 `lost` 는 계속 0 이다.

**큐를 두는 안은 버렸다.** `queueCapacity` 를 늘리면 거부는 사라지지만, 선점(PROCESSING 전이)과 실제 실행 사이의 창이 큐 길이만큼 벌어진다. 그 창에서 프로세스가 죽으면 PROCESSING 인 채 아무도 처리하지 않는 job 이 큐 길이만큼 생기고, `processing.timeout-seconds` 회수를 기다려야 한다. **거부를 없애는 대신 회수 대상을 늘리는 거래**라서, 슬롯을 세는 쪽이 낫다.

---

## 후속 3 — 장애 주입 버튼 패드

### 왜 만들었나 — 가만히 있으면 아무 지표도 안 쌓인다

스택을 올리고 Grafana 를 열면 빈 화면이 나온다. 이 챕터의 지표는 전부 **도메인이 움직여야
생기는 것**이라, 트래픽도 사고도 없으면 볼 것이 없다. 6단계에서 만든 시나리오 스크립트가
그 문제를 푼 방식은 "사고를 심고 끝까지 달린 다음 표를 내는 것"이었다. 실측에는 그게 맞다.

그런데 대시보드를 앞에 두고 곡선이 꺾이는 걸 보고 싶을 때는 그 형태가 안 맞는다. 스크립트는
`fresh_stack` 으로 시작해서 정해진 초만큼 기다리고 끝난다 — **중간에 멈춰서 들여다볼 자리가
없다.** 사고를 심은 상태로 3분이든 10분이든 두고 보다가 원할 때 되돌리고 싶은데, 그러려면
`docker compose kill`, `restart_app_with`, `mysql -e "UPDATE ..."` 를 매번 손으로 쳐야 한다.

그래서 **버튼 하나 = 사고 하나**인 패드를 만들었다. 누르면 즉시 심고, 원하는 만큼 두고 보다가,
짝이 되는 복구 버튼을 누른다. 그리고 화면이 **그 사고에 반응해야 할 지표와 침묵해야 할 지표를
같이 보여준다** — 6단계 결과 매트릭스를 문서에서 꺼내 버튼 옆에 놓은 것이다. 이 챕터에서
매번 반복한 문장이 "무엇이 안 울렸는지가 무엇이 울렸는지만큼 중요하다"인데, 그 문장은 표에
있을 때보다 버튼 옆에 있을 때 훨씬 잘 읽힌다.

### 왜 앱 밖인가

패드를 앱 안의 엔드포인트로 만드는 안이 먼저 떠오른다. 버렸다. 이유가 둘이다.

1. **사고 대부분이 앱이 자기 자신에게 할 수 없는 일이다.** SIGKILL, env 를 바꿔 재기동,
   Redis 컨테이너 교체, SQL 로 원장 훼손 — 전부 프로세스 밖의 손이 필요하다. 앱 안에 두면
   "재기동" 버튼이 자기가 실행 중인 프로세스를 갈아엎어야 한다.
2. **앱이 죽은 상태에서도 패드는 살아 있어야 한다.** 09 카드(앱 정지)의 요점이 `up == 0` 과
   "다른 모든 지표의 침묵"인데, 패드가 앱 안에 있으면 그 화면을 볼 수가 없다.

그리고 앱 안에 두면 장애 주입 코드가 프로덕션 아티팩트에 들어간다. 4단계에서 노출 경계를
`application.yml` 이 아니라 배포 쪽에서 정한 것과 같은 판단이다 — **운영 도구는 운영 쪽에 둔다.**

### 설계

**버튼 하나 = 사고 하나(원자적).** 시나리오 통째 실행 버튼은 만들지 않았다. `02-heartbeat-lost.sh`
는 A(ZSET 소실)와 B(Redis 다운 중 회수) 두 사고를 순서대로 심는 9분짜리 스크립트인데, 패드에서는
`SIGKILL → 기억 소거 → 재기동` 하나와 `Redis 정지` / `죽은 PROCESSING 심기` / `Redis 복구` 셋으로
갈라져 있다. 한 버튼 안에서 여러 사고가 겹치면 어느 지표가 무엇에 반응한 건지 화면에서 읽을 수 없다.

**사고 버튼은 복구 버튼을 가진다.** 워커 정지 ↔ 재개, Redis 정지 ↔ 복구, 지연 600초 ↔ 기본값,
실패율 100% ↔ 0.3, 원장 훼손 ↔ 원복. 05 만 원복이 까다로운데, 훼손 전 잔액과 지운 HOLD 행을
`.state/` 에 남기고 원복 버튼이 그걸 읽는다. 패드는 스크립트와 달리 **눌러 놓고 오래 두는 물건**
이라 원복 정보를 셸 변수에 들고 있을 수가 없다. 상태 파일이 없으면 잔액을
`initial_balance + SUM(ledger)` 로 재계산한다 — 대사가 검사하는 등식 그 자체다.

**한 소스 원칙.** docker·mysql·curl 조작은 전부 `faultpad/actions.sh` 가 하고, 그 파일은
시나리오와 같은 `scenarios/lib.sh` 를 source 한다. 파이썬은 HTTP 서빙·프로세스 관리·프록시만
한다. 스크립트와 버튼이 다른 코드로 같은 사고를 심으면 둘 중 하나는 반드시 낡는다. 02A 의
`--renew-anon-volumes` 함정 같은 것이 두 군데에 적혀 있으면 한 군데는 언젠가 틀린다.

**허용 목록.** `/api/run` 은 `catalog.json` 의 버튼에 선언된 액션만 받는다. 숫자 파라미터는
정수와 범위를 검증하고, env 는 catalog 에 선언된 **조합 전체가 일치할 때만** 통과한다.
브라우저가 보낸 문자열이 셸에 닿는 경로는 없다. `catalog.json` 이 화면의 데이터이면서 동시에
서버의 허용 목록이라는 게 이 설계의 핵심이다 — 화면에 없는 버튼은 누를 수도 없다.

**env 집합 추적.** `restart_app_with` 는 넘긴 env 만 export 한다. 즉 워커를 끈 뒤 스텁 지연을
걸면 워커가 다시 켜진다. 그래서 서버가 현재 `APP_*` 다섯 개의 집합을 메모리에 들고 있다가,
env 를 바꾸는 액션마다 **집합 전체**를 `actions.sh restart_app KEY=VAL ...` 로 넘긴다. 초깃값은
compose 기본값이다. 그리고 `state` 가 컨테이너의 실제 env(`$DC exec -T app env | grep ^APP_`)도
읽어 와서, 서버 메모리와 다르면 상태 띠에 그 차이를 표시한다 — 패드 밖에서 누가 손댔다는 뜻이다.

**null 과 0 을 구분한다.** `/api/query` 는 결과가 없는 쿼리에 `null` 을 돌려주고, 화면은 그걸
`없음(시계열 없음)` 으로 그린다. 0 으로 뭉개면 "그 시계열이 아예 없다"와 "값이 0 이다"가 같아진다.
4번 시나리오가 찾은 함정("스냅샷이 안 돌면 게이지가 0 에 얼어붙는다")이 정확히 그 구분의 문제였다.
같은 이유로 상태 띠는 게이지가 아니라 **DB 를 직접 읽은 미결 수와 최고 나이**를 같이 보여준다.

### 파일별 변경 목록

| 파일 | 변경 | 내용 |
|---|---|---|
| `deploy/observability/faultpad/server.py` | 신규 | python3 표준 라이브러리만. 정적 서빙, `actions.sh` 실행(동시 1개), Prometheus 프록시, env 집합 추적, 허용 목록 검증 |
| `deploy/observability/faultpad/actions.sh` | 신규 | `scenarios/lib.sh` 를 source. 19개 액션. `state` 는 JSON 한 줄을 낸다 |
| `deploy/observability/faultpad/catalog.json` | 신규 | 카드 12개. 서버의 허용 목록이자 화면의 데이터 |
| `deploy/observability/faultpad/index.html` | 신규 | 단일 파일(인라인 CSS/JS). 외부 리소스 0 — 오프라인에서도 뜬다 |
| `deploy/observability/faultpad/README.md` | 신규 | 띄우는 법 한 페이지 |
| `deploy/observability/README.md` | 수정 | "장애 주입 버튼 패드" 절 추가 |
| `.gitignore` | 수정 | `deploy/observability/faultpad/.state/` |

### 카드표

기댓값은 전부 6단계 결과 매트릭스와 후속 1 실측에서 그대로 옮겼다. **근거 없는 칸은 08 하나뿐이고,
화면과 이 표에 "예상(미실측)" 이라고 적혀 있다.**

| 카드 | 심는 사고 | 반응해야 할 지표 | 침묵해야 할 지표 | 알람 | 근거 |
|---|---|---|---|---|---|
| 준비/트래픽 | (사고 아님) 스택·충전·job N건·잔액부족 N건·smoke | `hold_balance{applied}` / `{rejected}` | 불변식 4종 | 없음 | 5단계 smoke |
| 01 | SIGKILL → 즉시 재기동 | `recovery{heartbeat}` = 죽을 때 PROCESSING 수, SIGKILL 후 11초 | `recovery{backstop}` 0, 불변식, 5xx | 없음(임계 전에 끝난다) | 매트릭스 1 |
| 02A | SIGKILL → Redis 기억 소거 → 재기동 | `recovery{backstop}` 3, SIGKILL 후 70초 | `recovery{heartbeat}` 0 — 잡을 엔트리가 없다 | `CreditBackstopRecovery` +4초 | 매트릭스 2A |
| 02B | Redis 정지 / 죽은 PROCESSING 심기 / 복구 | `recovery{backstop_blind}` 심은 뒤 ~11초 + WARN 로그 | `backstop` 0, `heartbeat` 0 | `CreditBackstopBlindRecovery`, 오탐이면 `confirm{stale}` | 후속 1 재실행 |
| 03 | 워커 정지 (`APP_WORKER_ENABLED`) | `outstanding_count` 고정, `oldest_pending_age` 단조 증가 | 방어 카운터 전부, `recovery`, 5xx 0, `up` 1 | `CreditPipelineStalled` job 생성 후 385초 | 매트릭스 3 |
| 04 | 스케줄러 정지 (`APP_SCHEDULING_ENABLED`) | staleness 둘 **-1 고정** | `oldest_pending_age`·`outstanding_count` **0 에 얼어붙는다** | `CreditSnapshotStale` + `CreditReconciliationStale` 재기동 후 76초 | 매트릭스 4(수정 후) |
| 05 | (a) balance −1 (b) balance = −1 (c) HOLD 원장 1행 삭제 | mismatch 25초 / negative 11초 / jobs_without_hold 14초 | 방어 카운터 합 불변(31→31), `up` 1, 5xx 0 | P1 셋, `for: 0m` 이라 즉시 | 매트릭스 5 |
| 06 | 같은 idemKey N건 동시 | `idem_key{app_hit}` + `{db_unique}` = N−1, `hold_balance{applied}` +1, 잔액 −100 | `hold_balance{rejected}` 0 | 없음(방어가 동작한 것이다) | 매트릭스 6 |
| 07 | 스텁 지연 600초 | `oldest_pending_age` 단조 증가, `worker_claim{applied}` = job 수 | `recovery{heartbeat}` 0 **그리고** `{backstop}` 0 — 회수하지 않는 게 옳다 | `CreditPipelineStalled` 379초 | 매트릭스 7 |
| 08 | 스텁 실패율 1.0 | **예상(미실측)** `mark_failed` = job 수 × 3, `retry_claim` = job 수 × 2, `final_refund` = job 수, 잔액 원복 | 예상(미실측) 불변식 0, 5xx 0 | `CreditRetryExhaustionRateHigh`(P3, 비율 > 0.1 이 10분) | **없음.** `application.yml` 의 `max-attempts=3` 에서 유추 |
| 09 | 앱 SIGKILL 후 방치 | `up{credit_system}` 0 | 다른 모든 지표 — 스크레이프 자체가 없다 | `CreditSystemDown` `for: 30s` | 5단계 알람 규칙 |

### 구현하며 만난 함정

**`restart_app_with` 는 넘긴 env 만 export 한다.** 스크립트에서는 한 시나리오가 손잡이 하나만
쓰니까 문제가 안 됐는데, 패드는 워커 정지와 스텁 지연을 겹쳐 걸 수 있어야 한다. 서버가 집합
전체를 들고 있게 만든 것이 이 함정의 값이다. 실행 로그에 매번 다섯 개가 전부 찍히는 것도 그래서다.

**게이지를 상태 띠에 그대로 쓸 수 없다.** 4번 시나리오의 함정이 패드에서 그대로 재현된다 —
재기동 직후 `credit_hold_outstanding_count` 는 0 인데 그건 "미결이 없다"가 아니라 "아직 아무도
안 셌다"이다. 그래서 상태 띠의 미결 수는 게이지가 아니라 `mysql_q` 로 직접 센 값이고, 04 카드는
게이지와 DB 값을 나란히 놓는다.

**Redis 가 죽으면 앱의 헬스체크도 DOWN 이 된다.** 02B 검증 중 `state` 가 `app_up: false` 를
냈다. 앱 프로세스는 멀쩡히 살아서 blind 회수를 돌리고 있었는데, `/actuator/health` 의 Redis
헬스 인디케이터가 DOWN 이라 종합 상태가 DOWN 이 된 것이다. 5단계에서 "헬스체크가 관리 포트에
의존한다"는 트레이드오프를 적어 뒀는데, **의존성 헬스 인디케이터까지 묶여 있다**는 건 그때
안 적었다. 패드 화면에서는 `app` 점이 빨간데 `recovery{backstop_blind}` 는 오르는 그림이 되고,
그 조합 자체가 정보다 — "앱이 죽은 게 아니라 Redis 가 죽었다"를 두 칸이 같이 말한다.

**동시에 하나만 실행해야 한다.** `stack_up` 이 도는 중에 `charge` 를 누르면 seed 도 안 끝난
DB 에 요청이 간다. 서버가 실행 중이면 409 를 내고 화면은 버튼을 전부 잠근다.

### 검증에서 실제로 본 값

`./gradlew bootJar` 후 패드만 띄우고, 브라우저 대신 curl 로 전 과정을 돌렸다. 아래는 그 실행의 값이다.

**허용 목록.** 없는 액션(`rm -rf /`) 400, 버튼이 아닌 `state` 400, 선언 안 된 env 조합
(`APP_WORKER_ENABLED=false; rm -rf /`) 400, 정수 아닌 파라미터(`"5; ls"`) 400, 범위 밖(`n=9999`) 400.
`stack_up` 실행 중 `charge` 는 **409**.

**스택이 안 떠 있을 때의 `/api/state`** — 세 컨테이너 전부 `false`, 나머지 값은 `null`. JSON 은 나온다.
`/api/query` 와 `/api/alerts` 는 Prometheus 가 없을 때 502 가 아니라 `{"error": ...}` 200 을 냈다.

**06 중복 폭풍 100건.**

```
   HTTP   97 200
   HTTP    3 409
   T+0    잔액 9400 → 9300 (차이 100)
   T+0    job 행 총 7건, HOLD 원장 총 7건
   T+12   idem_key app_hit=96 db_unique=3 (합 99 — 기대 99)
   T+12   hold_balance rejected=0 (기대 0)
```

합 99 는 6단계와 같고, 내역은 90+9 가 아니라 **96+3** 이었다. 매트릭스에 "둘의 비율은 매번
다르다"고 적어 둔 그대로다. 409 의 수(3)와 `db_unique`(3)가 정확히 같은 것도 다시 확인됐다.

**05 (b) 음수 잔액 → 원복.**

```
   훼손 전 balance=9400 를 .state/balance.txt 에 저장했다
   T+5s   negative_balance_orgs=1   firing=[]
   T+10s  negative_balance_orgs=1   firing=[CreditLedgerReconciliationMismatch, CreditNegativeBalanceOrgs]
   (원복)
   T+10s  negative=0 mismatch=1     firing=[Mismatch, NegativeBalanceOrgs]
   T+15s  negative=0 mismatch=1     firing=[Mismatch]
   T+55s  negative=0 mismatch=0     firing=[Mismatch]
   T+60s  negative=0 mismatch=0     firing=[]
```

감지 5초 이내, 알람 10초 이내. 원복은 불변식 10초 / mismatch 55초(대사 주기 60초) / 알람
resolve 60초. 6단계의 11초·25초와 같은 크기다. `balance = -1` 하나가 mismatch 도 같이 깨는데,
등식(`잔액 = 최초 잔액 + 원장 합계`)이 함께 무너지므로 맞는 동작이다.

**env 토글.** `restart_app APP_WORKER_ENABLED=false` 를 누르자 로그에 다섯 개가 전부 찍혔고
(`APP_SCHEDULING_ENABLED=true APP_STUB_FAILURE_RATE=0.3 APP_STUB_MAX_DELAY_MILLIS=7000
APP_STUB_MIN_DELAY_MILLIS=3000 APP_WORKER_ENABLED=false`), `state` 의 `container_env` 가
`APP_WORKER_ENABLED: "false"` 로 바뀌었으며 `env_drift` 는 비어 있었다. 되돌리는 것도 같았다.

**02B blind 회수.**

```
   HOLDING 3건을 updated_at=120초 전의 PROCESSING 으로 바꿨다 (현재 PROCESSING=3)
   T+5s   blind=2 backstop=0 heartbeat=0  firing=[]
   T+10s  blind=3 backstop=0 heartbeat=0  firing=[CreditBackstopBlindRecovery]
```

심은 뒤 **10초**에 3건 전부. 후속 1 의 11초와 같다. `backstop` 과 `heartbeat` 는 끝까지 0 —
라벨이 원인을 정확히 가리킨다. 이 구간에서 `state` 는 `redis_up: false`, `heartbeat_zset: null`,
`app_up: false`(위의 헬스 인디케이터 함정)를 냈다.

**01 크래시 회수.**

```
   T+0    PROCESSING = 8:2,12:0,13:0
   T+0    Redis ZSET: 3 개
   앱 SIGKILL (15:15:08)
   T+6    앱 UP
   T+5s   heartbeat=0 backstop=0 age=89
   T+10s  heartbeat=3 backstop=0 age=89
   T+20s  heartbeat=3 backstop=0 age=0
```

죽을 때 PROCESSING 3건 → `recovery{heartbeat}` **정확히 3**, `backstop` 0.
SIGKILL 로부터 감지까지 약 16초(재기동 6초 포함), `oldest_pending_age` 는 89 로 튀었다가 0 으로.

**09 앱 정지.** `up` 0, `CreditSystemDown` 이 **pending 25초 → firing 35초**(`for: 30s`).
상태 띠의 pending 칸이 그동안 노란색으로 차 있었다.

**null 과 0.** 같은 응답 안에서 `credit_job_recovery_total{detector="backstop"}` 은 `"0"`,
없는 지표는 `null` 로 나왔다. 화면은 후자만 `없음(시계열 없음)` 으로 그린다.

끝나고 `stack_down`(= `down -v`)으로 내리고 패드 서버도 껐다. `.state/` 는 원복 버튼이
소비해서 비어 있다.

### 남는 것

**08 은 미실측이다.** 스텁 실패율 100% 시나리오는 6단계에 스크립트가 없었고 이번에도 재지
않았다. 카드의 숫자는 `application.yml` 의 `generation.max-attempts=3` 과 재시도 경로에서
유추한 값이라 화면·문서 양쪽에 "예상(미실측)" 이라고 적어 뒀다. 배수의 근거는 `DeadJobRecoveryTask.retryOrRefund` 의
`attemptNo + 1 < maxAttempts` 조건이다 — attemptNo 0, 1 에서 실패하면 재시도(2회), 2 에서 실패하면 최종 환불(1회)이고,
실패 보고 자체는 세 번 다 `mark_failed` 를 지난다. `CreditRetryExhaustionRateHigh`
는 `for: 10m` 이라 실측하려면 최소 10분을 버려야 해서, 다음에 스크립트로 만들 때 같이 잰다.

**03·07 의 385초/379초는 이번에 다시 재지 않았다.** env 토글이 컨테이너에 반영되는 것까지만
확인했다. 알람 임계가 300초 + `for: 60s` 라 검증 한 번에 7분 가까이 드는데, 이 값들은 6단계에서
이미 두 번 잰 것이고 패드가 바꾼 것은 사고를 심는 손잡이뿐이라 다시 재지 않았다.

**패드는 조직 id=1 만 안다.** `lib.sh` 의 `ORG_HEADER` 가 고정이고 `state` 의 잔액도 id=1 이다.
멀티 조직 사고(한 조직의 훼손이 다른 조직 지표에 안 섞이는지)는 이 패드로 못 만든다.

**되돌릴 수 없는 버튼이 하나 있다.** 02A(기억 소거)는 Redis 익명 볼륨을 갈아엎으므로 복구
버튼이 없다. 사고 자체가 "기억이 사라졌다"라서 되돌릴 것이 없는 게 맞지만, 다른 카드와 달리
짝이 없다는 점은 화면에서 보이지 않는다.

---

## 명령어

```
# 이 브랜치에서 전체 테스트 실행 (Docker 필요 — MySQL + Redis Testcontainers, 181개 테스트)
./gradlew test

# 정적 분석
./gradlew ktlintCheck
./gradlew detekt

# 도메인/서비스/스케줄러 코드가 Micrometer 를 모르는지 직접 확인
grep -rl "io.micrometer" src/main/kotlin
# (observability/ 아래 네 파일만 나와야 한다)

# 지표 하나만 골라 실제 배선까지 확인
./gradlew test --tests "com.example.credit_system_kotlin.observability.PrometheusEndpointTest"

# 방어 카운터 단위 테스트
./gradlew test --tests "com.example.credit_system_kotlin.observability.DefenseMetricsTest"

# 진짜 경쟁 상태에서 카운터 합이 맞는지 (Docker 필요)
./gradlew test --tests "com.example.credit_system_kotlin.observability.DefenseMetricsConcurrencyTest"

# 스냅샷 게이지 — 쿼리가 실제 DB 상태와 맞는지, 게이지 갱신이 맞는지
./gradlew test --tests "com.example.credit_system_kotlin.global.scheduling.DomainSnapshotTaskTest"
./gradlew test --tests "com.example.credit_system_kotlin.observability.DomainSnapshotMetricsTest"

# 카디널리티 가드 — 식별자 태그 거부, 태그 값 상한, 실제 컨텍스트 배선
./gradlew test --tests "com.example.credit_system_kotlin.observability.MetricsCardinalityConfigTest"
./gradlew test --tests "com.example.credit_system_kotlin.observability.MetricsCardinalityWiringTest"

# 노출 경계 — 애플리케이션 포트의 /actuator/prometheus 가 404 인지
./gradlew test --tests "com.example.credit_system_kotlin.observability.ManagementPortBoundaryTest"

# 대시보드 JSON 문법
python3 -m json.tool deploy/observability/grafana/dashboards/credit-domain.json > /dev/null

# 알람 규칙 문법 (스택이 떠 있을 때)
docker compose -f deploy/observability/docker-compose.yml exec prometheus \
  promtool check rules /etc/prometheus/rules/credit.rules.yml

# 장애 주입 시나리오 문법
bash -n deploy/observability/scenarios/*.sh

# 장애 주입 버튼 패드 문법 (후속 3)
bash -n deploy/observability/faultpad/actions.sh
python3 -m py_compile deploy/observability/faultpad/server.py
python3 -m json.tool deploy/observability/faultpad/catalog.json > /dev/null
```

관측 스택을 띄우고 내리는 명령은 [`deploy/observability/README.md`](../deploy/observability/README.md) 에 있다.

```
# 요약
./gradlew bootJar
docker compose -f deploy/observability/docker-compose.yml up -d --build
./deploy/observability/scripts/seed.sh
./deploy/observability/scripts/smoke.sh
docker compose -f deploy/observability/docker-compose.yml down -v
```

6단계의 장애 주입은 스크립트가 스택을 올리고 내리는 것까지 한다.

```
# 7개 전부 (30~50분, 마지막에 down -v 까지)
./gradlew bootJar
./deploy/observability/scenarios/run-all.sh

# 하나만
./deploy/observability/scenarios/03-worker-stopped.sh
```

후속 3 의 버튼 패드는 스택을 올려 두고(또는 패드의 `스택 올리기` 버튼으로 올리고) 옆에 띄운다.
Grafana 를 같이 열어 두고 버튼을 누르며 곡선을 보는 용도다.

```
./gradlew bootJar
python3 deploy/observability/faultpad/server.py            # http://127.0.0.1:8090
python3 deploy/observability/faultpad/server.py --port 8099
```
