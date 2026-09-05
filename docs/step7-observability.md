# step7-observability — 도메인 지표 관측 · ①단계: 원장 대사 지표 승격

step6-resilience 가 만든 원장 대사(`LedgerReconciliationTask`)는 이미 "잔액과 원장이 어긋났는가"를 매 주기 계산하고 있었다. 문제는 그 계산 결과가 로그 한 줄로 끝난다는 것이다. 이 단계는 그 계산 결과를 Prometheus 로 긁어갈 수 있는 지표로 끌어올린다.

- 이전 단계: `step6-resilience`
- 이번 단계: `step7-observability` ① (전체 6단계 중 첫 번째. 도메인 서비스 계측·스냅샷 게이지·docker-compose·장애 주입은 이후 단계다)

## 왜 이 단계인가

서버 지표(CPU, RPS, 에러율, 응답 시간)는 "서버가 살아있다"만 말해준다. 이 도메인의 진짜 사고는 서버가 CPU 40%, 에러율 0%로 완벽히 건강한 채로 난다 — 잔액이 음수가 되거나, 같은 요청이 이중으로 차감되거나, hold 로 묶인 돈이 확정도 환불도 되지 않은 채 유실되는 식이다. 이런 사고는 인프라 대시보드에 아무 흔적도 남기지 않는다.

`LedgerReconciliationTask` 는 매 주기 `initialBalance + 원장합 == balance` 를 계산해서 이걸 이미 알고 있었다. 하지만 결과를 `log.error` 로만 뱉었다. 로그는 누군가 grep 하지 않으면 존재하지 않는 것과 같다. 알람도, 대시보드도, 추세도 걸 수 없다. ①단계는 이 계산 결과를 지표로 끌어올려서 "계산은 하고 있었지만 아무도 못 보던 것"을 처음으로 보이게 만든다.

## 설계 원칙: 왜 이벤트로 분리했나

`LedgerReconciliationTask` 에 `MeterRegistry` 를 직접 주입하고 그 자리에서 `registry.gauge(...)` 를 부르는 게 제일 짧은 길이었을 것이다. 그렇게 하지 않았다.

도메인/스케줄러 코드가 Micrometer 를 알게 되면, 관측 방식을 바꾸는 결정(Prometheus 에서 다른 백엔드로, 혹은 지표 이름 체계를 바꾸는 것)이 도메인 코드를 건드리는 결정이 되어 버린다. 반대로 지금처럼 분리해 두면:

- `LedgerReconciliationTask` 는 "대사 한 주기가 이렇게 끝났다(`LedgerReconciliationCompleted`)"는 사실만 말한다. 이 이벤트는 `checkedCount`, `mismatchCount`, `duration`, `completedAt` 네 개의 순수 값만 들고, `io.micrometer` 를 import 하지 않는다.
- 세는 책임은 `observability` 패키지의 `LedgerReconciliationMetrics` 가 전담한다. 여기가 Micrometer 를 아는 유일한 곳이다.

이렇게 나누면 "대사가 어떻게 동작하는가"를 바꾸는 사람과 "대사 결과를 어떻게 관측하는가"를 바꾸는 사람이 서로의 코드를 건드리지 않는다. 검증 방법도 그대로 갈린다 — `LedgerReconciliationTaskTest` 는 이벤트가 올바른 값으로 발행되는지만 보고, `LedgerReconciliationMetricsTest` 는 `MeterRegistry` 만 가지고 순수 단위 테스트로 게이지/카운터/타이머 갱신을 검증한다. 둘 다 Spring 컨텍스트나 스케줄러를 몰라도 된다.

## 파일별 변경 목록

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

## 지표 표

| Micrometer 이름 | Prometheus 에서 보이는 이름 | 타입 | 의미 | 알람 기준 |
|---|---|---|---|---|
| `credit.ledger.reconciliation.mismatch` | `credit_ledger_reconciliation_mismatch` | Gauge | 마지막 대사 주기의 불일치 조직 수 | **P1, 즉시 호출.** 1건도 허용하지 않는다 — SLO 가 아니라 불변식이 깨졌다는 신호다 |
| `credit.ledger.reconciliation.checked` | `credit_ledger_reconciliation_checked` | Gauge | 마지막 주기에 검사한 조직 수 | 알람 없음. mismatch 를 해석할 분모(추세 확인용) |
| `credit.ledger.reconciliation.cycles` | `credit_ledger_reconciliation_cycles_total` | Counter | 완료한 대사 주기의 누적 수 | 알람 없음. `rate()` 로 대사가 계속 도는지 눈으로 확인하는 용도 |
| `credit.ledger.reconciliation.duration` | `credit_ledger_reconciliation_duration_seconds{_count,_sum}` / `_max` | Timer | 대사 한 주기 소요 시간 | 알람 없음. 조직 수 증가에 따른 대사 시간 추세 관찰용 |
| `credit.ledger.reconciliation.staleness` | `credit_ledger_reconciliation_staleness_seconds` | Gauge (초) | 마지막 성공 대사로부터 흐른 시간 | **P2.** 대사 주기(`app.scheduling.reconciliation-interval-millis`, 기본 60초)의 3배, 즉 180초를 넘으면 대사 자체가 멈춘 것으로 본다 |

Counter 는 Micrometer 가 Prometheus 로 내보낼 때 `_total` 접미사를, Timer 는 `_seconds`(base unit) 에 `_count`/`_sum`/`_max` 세 시계열을 붙인다. 뒤의 "직접 확인하는 방법" 절의 실제 출력에서 이 변형을 그대로 볼 수 있다.

`mismatch` 와 `staleness` 가 서로 다른 방향의 사고를 잡는다는 점이 중요하다. `mismatch` 는 "대사가 돌고 있고, 뭔가 어긋난 걸 발견했다"는 신호다. `staleness` 는 "대사 자체가 멈춰서 어긋난 게 있어도 아무도 모른다"는 정반대의 신호다. 대사 태스크가 죽으면 `mismatch` 는 마지막 값에 얼어붙어 조용히 0 을 보여줄 수 있다 — `staleness` 없이는 이 침묵이 "문제 없음"인지 "관측 자체가 죽음"인지 구분할 수 없다.

## 핵심 코드 읽기

### `LedgerReconciliationTask` — 이벤트 발행 지점만 얹는다

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

### `LedgerReconciliationMetrics` — Micrometer 를 아는 유일한 곳

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

## Gauge 등록의 함정

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

## 카디널리티

이번 지표 다섯 개 모두 태그가 없다(공통 태그 `application=credit_system` 제외). 특히 `organizationId` 는 절대 태그로 넣지 않았다.

Prometheus 는 태그(레이블)의 조합마다 별도의 시계열을 만든다. 조직이 수천, 수만 개로 늘어나는 시스템에서 `organizationId` 를 레이블로 붙이면 지표 하나가 조직 수만큼의 시계열로 뻥튀기된다 — 이게 카디널리티 폭발이고, Prometheus 서버의 메모리와 쿼리 성능을 실질적으로 무너뜨릴 수 있는 흔한 사고 원인이다.

이 지표들의 목적은 애초에 "전체 시스템에 지금 사고가 있는가"라는 집계 질문에 답하는 것이다. "어느 조직이 문제인가"는 다른 질문이고, 이미 다른 도구가 답을 갖고 있다 — `LedgerReconciliationTask.isBalanceConsistent` 의 ERROR 로그는 `organizationId`, `balance`, `expected`, `diff` 를 전부 남긴다. 알람이 울리면(mismatch > 0) 그 순간 로그를 검색해서 어느 조직인지 찾는 흐름이다: **집계는 메트릭이, 개별 식별은 로그가 맡는다.**

## 테스트가 보장하는 것

- **`LedgerReconciliationTaskTest`** — 기존 로그 기반 단언(일치 시 무경보, 불일치 시 ERROR 1건, 배치 경계 등)은 전부 그대로 유지된다. 여기에 `일치 2건 불일치 1건이면 이벤트로 checkedCount 3 mismatchCount 1을 발행한다` 테스트를 추가했다. 조직 3개(2개는 잔액·원장 일치, 1개는 원장 없이 잔액만 증가)를 만들고 `task.reconcile()` 을 호출한 뒤, mock 한 `ApplicationEventPublisher` 에 `argumentCaptor` 로 잡힌 이벤트의 `checkedCount == 3`, `mismatchCount == 1` 을 검증한다. 태스크 생성자에 `ApplicationEventPublisher` 가 추가되어 깨졌던 컴파일은 mock 주입으로 고쳤다.

- **`LedgerReconciliationMetricsTest`** — `SimpleMeterRegistry` 와 (기존 API 로는 값을 되돌릴 수 없는 `Clock.fixed` 대신) 직접 시각을 앞으로 당길 수 있는 테스트 전용 `FixedMutableClock` 을 써서 Spring 컨텍스트 없이 순수 단위 테스트로 검증한다:
  - 이벤트 하나를 넣으면 `mismatch`/`checked` 게이지가 그 이벤트의 값이 된다.
  - 이벤트를 두 번 넣으면 게이지는 **마지막 이벤트의 값**으로 덮이고, `cycles` 카운터는 정확히 2가 된다.
  - 이벤트가 오기 전 `staleness` 는 `-1.0` 이고, 이벤트가 온 뒤 시계를 30초 앞당기면 `staleness` 가 `30.0` 을 반환한다.
  - `duration` 타이머가 이벤트의 `duration`(250ms)을 그대로 기록한다(`count() == 1`, `totalTime(MILLISECONDS) == 250.0`).

- **`PrometheusEndpointTest`** — `@SpringBootTest` + `MockMvc` 로 `/actuator/prometheus` 를 실제로 호출해서 응답 본문에 `credit_ledger_reconciliation_mismatch` 가 언더스코어 형태로 들어있는지 확인한다. 이 테스트는 지표 등록이 부트 과정 전체(설정 → 빈 생성 → actuator 엔드포인트 등록)를 거쳐 실제로 노출되는 것까지 검증한다는 점에서 앞의 두 테스트와 성격이 다르다 — 단위 테스트는 "로직이 맞다"를, 이 테스트는 "배선이 맞다"를 보장한다.

## 직접 확인하는 방법

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

## 이 단계에서 남는 것

- **①은 L1(불변식) 한 겹만 덮는다.** 원장 대사가 지키는 것은 "잔액 = 최초 잔액 + 원장 합계" 라는 데이터 정합성 불변식이다. 이번 단계가 지표로 승격한 것은 이 불변식이 깨졌는지 여부뿐이다.
- **방어 발동 지표(L3)는 다음 단계다.** step2~step6 이 만든 조건부 UPDATE, 유니크 제약, CAS, heartbeat 회수 같은 방어 장치들이 실제로 몇 번 발동했는지(예: 잔액 부족으로 거절된 횟수, 유니크 제약으로 막힌 중복 요청 수, timeout 회수 횟수)는 아직 지표가 없다. 이런 장치가 "얼마나 자주 실제로 막고 있는지" 를 보려면 도메인 서비스 계측이 필요하고, 이는 이번 범위 밖이다.
- **미결 hold 나이(L0)도 다음 단계다.** 확정도 환불도 되지 않은 채 오래 떠 있는 hold 가 있는지(돈이 묶인 채 방치되는 상황)는 스냅샷 게이지가 필요한 영역이고, 아직 손대지 않았다.
- **여전히 감지일 뿐 교정하지 않는다.** step6 의 원칙이 그대로 이어진다 — `mismatch` 게이지가 0 이 아니어도 자동으로 아무것도 고치지 않는다. 사람이 알람을 보고 대응하는 구조다. 이번 단계가 바꾼 것은 "그 알아챔이 로그 grep 이 아니라 지표와 알람 규칙으로 자동화됐다"는 것뿐이다.

## 명령어

```
# 이 브랜치에서 전체 테스트 실행 (Docker 필요 — MySQL + Redis Testcontainers, 130개 테스트)
./gradlew test

# 정적 분석
./gradlew ktlintCheck
./gradlew detekt

# 도메인/스케줄러 코드가 Micrometer 를 모르는지 직접 확인
grep -r "io.micrometer" src/main/kotlin/com/example/credit_system_kotlin/ledger/
# (아무 결과도 없어야 한다)

# 지표 하나만 골라 실제 배선까지 확인
./gradlew test --tests "com.example.credit_system_kotlin.observability.PrometheusEndpointTest"
```
