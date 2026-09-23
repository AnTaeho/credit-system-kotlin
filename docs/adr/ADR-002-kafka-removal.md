# ADR-002 — Kafka 제거, jobs 테이블을 영속 작업 큐로

- 날짜: 2026-08-17
- 저장소: JAVA (`../credit_system`)
- 커밋: `00b2b34` — `refactor: replace kafka pipeline with db job queue`

---

## 상황

ADR-001 이 남긴 구조는 **하나의 DB 트랜잭션 바깥에 전달 경로가 하나 더 있는** 형태였다.
hold 트랜잭션이 커밋한 뒤 outbox relay 가 Kafka 로 발행하고, 발행이 성공해야 `sent=true` 가 된다.
이 사이의 모든 틈이 ADR-001 결과 표의 다섯 커밋을 만들었다 — 전달 보장, poison message, broker 장애 중 attempt 소모.
스펙 §6.2 가 컨슈머 멱등을 "향후 과제"로 미뤄 둔 자리도 그대로 남아 있었다.
다섯 커밋이 닫은 것은 Kafka 의 오동작이 아니라, 하나의 DB 트랜잭션 바깥으로 전달 경로가 나가면서 생긴 경계들이다.

## 당시 수치

**없음 — 이 시점에 처리량 목표가 존재하지 않았다.**

제거 판단을 적은 `docs/db-job-queue-refactor.md` 에 RPS, TPS, 지연 수치가 한 줄도 없다.
성능·안정성 요구서(`docs/01-requirements.md`)는 **2026-09-22 에 처음 생겼다**(KT `11d071c`) — 제거보다 5주 뒤다.
즉 이 결정은 성능 논증으로 내려진 것이 아니고, 그렇게 각색해서도 안 된다.

측정 대신 **규모**는 남아 있다.

```
$ git show --stat 00b2b34 | tail -1
 34 files changed, 198 insertions(+), 860 deletions(-)
```

사라진 것: `KafkaConsumerConfig`, `KafkaTopicConfig`, `OutboxEntry`, `OutboxRepository`,
`OutboxRelay`, `OutboxWriter`, `GenerationJobMessage`, `build.gradle` 의 Kafka 의존성,
`docker-compose.yml` 의 Kafka 서비스. 새로 생긴 것은 `GenerationJobProcessor`(+52) 뿐이다.

## 결정

Kafka·outbox relay·DLT 를 걷어내고 `jobs` 테이블을 그대로 영속 작업 큐로 쓴다.
당시 근거는 **정합성 단순화** 하나다.

> ## 보장 경계
>
> 크레딧 차감, job 생성, ledger 기록은 하나의 RDB 트랜잭션이다. 작업 큐도 같은 DB에 있으므로 DB와 Kafka 사이의 이중 쓰기·outbox 재발행 문제가 없다. 여러 워커가 있어도 `HOLDING → PROCESSING` 조건부 UPDATE와 attemptNo fencing으로 하나의 시도만 결과를 반영한다. `jobs(status, id)` 인덱스는 배치 폴링의 상태 필터와 ID 정렬을 지원한다.
>
> `HOLDING`은 메시지 발행 실패가 아닌 정상적인 DB 대기열 상태이므로, 경과 시간만으로 실패·환불 처리하지 않는다. worker가 중지된 상태에서 남은 HOLDING job은 worker가 다시 기동되면 처리된다.
>
> Redis는 작업 전달 수단이 아니라 살아있는 처리 작업을 확인하는 heartbeat 저장소로만 남는다. Redis 장애 시 기존 보수적 회수 정책을 유지한다.

— 출처: JAVA `docs/db-job-queue-refactor.md` "보장 경계" 절 (워킹트리)

흐름은 폴링으로 바뀐다.

> 2. `GenerationWorker`가 일정 주기마다 `HOLDING` job을 최대 `app.worker.batch-size`건(기본 20건) 조회하고 조건부 UPDATE로 `PROCESSING` 상태를 선점한다.
> 3. 선점한 작업은 scheduler와 분리된 bounded executor에서 최대 `app.worker.concurrency`건(기본 3건) 실행한다. executor 대기열은 사용하지 않으며, 남은 작업은 DB의 `HOLDING`에 둔다.

— 같은 문서 "처리 흐름"

## 대안

문서에 남은 대안 비교는 없다. `db-job-queue-refactor.md` 는 바뀐 구조와 그 보장 경계만 서술하고,
Kafka 를 유지하면서 이중 쓰기를 닫는 길(예: 트랜잭셔널 outbox + 컨슈머 멱등키)을 비교 대상으로 올리지 않았다.
스펙 §6.2 가 미뤄 둔 컨슈머 멱등 과제는 **해결된 것이 아니라 문제 자체가 사라지는 쪽을 골라 없어졌다.**

## 2026-09-23 에 덧붙인 정량 논증

**이 절은 당시 판단이 아니다.** 요구서가 생긴 뒤(2026-09-22) 같은 결정을 수치 축에서 다시 본 것이며,
위 "결정" 칸의 근거를 사후에 보강하지 않는다.

### 실측이 준 식

> 처리량 상한 = min(concurrency / job소요시간, concurrency / 폴링주기)
>
> batch-size는 이 식에 등장하지 않는다. 필요한 값은 concurrency다.

— 출처: JAVA `docs/develop-report.md` "완료: batch-size 실측 (2026-08-20)"

이 식은 산술이 아니라 **실측에서 읽어낸 것**이다.

> 실측 TPS가 5.8에서 막힌다. 소요시간을 200ms → 50ms로 4배 빠르게 해도, batch-size를 4 → 20으로 5배
> 키워도 움직이지 않는다. 5.8은 `concurrency / 폴링주기 = 3 / 0.5초 = 6.0`의 실측값이다. executor 큐가 0이라
> 한 주기에 concurrency개를 넘기면 그다음은 반드시 거부되고 루프가 `return`한다. **한 폴링 주기가 나눠줄 수
> 있는 최대치는 batch-size가 아니라 concurrency다.**

— 같은 절

### 요구서 목표를 이 식으로 역산하면

요구서 1-2 의 **설계 목표 피크 쓰기 ≈ 1,000 RPS**(산술: 115.7 × 8 = 925.6)를 폴링 잡큐로 소화한다고 두고
두 축 각각의 필요 동시성을 푼다. **아래는 전부 산술이며 실측이 아니다.**

폴링 축 — `처리량 ≤ concurrency / 폴링주기` 에서

```
폴링 500ms 유지:  concurrency ≥ 1,000 × 0.5초 =   500
폴링  50ms 로:    concurrency ≥ 1,000 × 0.05초 =   50
```

job 소요시간 축 — `처리량 ≤ concurrency / job소요시간` 에서.
현재 문서에 존재하는 유일한 job 소요시간 값은 스텁의 3~7초다(요구서 2-3 C-6, `app.stub.min/max-delay-millis`).

```
job 3초: concurrency ≥ 1,000 × 3 = 3,000
job 7초: concurrency ≥ 1,000 × 7 = 7,000
```

필요 동시성은 두 제약의 **max** 다.

```
필요 concurrency = max(500 또는 50, 3,000~7,000) = 3,000 ~ 7,000
```

### 이 숫자가 말하는 것과 말하지 않는 것

- 지배하는 항은 **job 소요시간 축**이다. 3~7초짜리 작업에서는 폴링 축(50~500)이 한 자릿수 배 차이로 아래에 깔린다.
- **전송 수단이 건드릴 수 있는 항은 폴링 축 하나뿐이다.** 다만 ADR-001 이 기록한 구조에서는
  그 항이 사라지지도 않았다 — 폴링이 워커에서 outbox relay 로 자리를 옮겼을 뿐이고,
  주기는 1초로 지금(500ms)보다 오히려 길었다(`@Scheduled(fixedDelayString = "…outbox-relay-interval-millis:1000")`,
  게다가 relay 는 건당 최대 10초 ack 를 기다리며 순차 발행한다). 폴링 항이 실제로 없어지려면
  hold 트랜잭션이 직접 발행해야 하는데, 그것이 바로 `db-job-queue-refactor.md` 의 "보장 경계"가
  없앴다고 말하는 이중 쓰기다.
  `concurrency / job소요시간` 항은 Kafka 에서도 폴링에서도 같다 — 동시에 몇 개를 실제로 실행하느냐의 문제라서다.
  지금의 job 소요시간에서는 구속하는 항이 **전송 수단을 바꿔도 움직이지 않는 쪽**이다.
- 요구서 PERF-07 은 접수율이 워커 처리량을 넘는 상태를 실패가 아니라 정상으로 다룬다 —
  목표는 "깨지지 않고 쌓인다"이고, 적체가 단조 감소하는지를 본다. 위 역산은 그 격차의 크기를 말할 뿐이다.
- **여기까지가 산술로 말할 수 있는 전부다.** 단일 앱·단일 DB 가 그 동시성을 감당하는지,
  1,000 RPS 의 접수 자체(TX-1 커밋)를 소화하는지는 **Phase 3 실측 전에는 모른다.**
  "outbox+폴링이 여유 있게 처리한다"는 실측 후에야 쓸 수 있는 문장이고, 지금 쓸 수 없다.

### 재도입 트리거 (값은 전부 가정)

| # | 트리거 | 값의 근거 | 요구서 연결 |
|---|---|---|---|
| (a) | 폴링 주기를 **X ms** 이하로 내려도 접수 → 선점 지연 p99 가 **Y** 를 넘을 때 | develop-report 가 "30 TPS가 필요하면 `폴링주기 <= concurrency / 30 = 100ms`로 내려야 한다"고 같은 방식으로 값을 잡았다. X 는 그 예시를 출발점으로 두되 **가정**이다 | Tier C **C-6**(워커 처리량 상한 초당 0.43~1.0건) |
| (b) | 같은 이벤트를 소비할 컨슈머 그룹이 **둘 이상** 필요해질 때(팬아웃) | 수치가 아니라 구조 조건이다. 지금은 소비자가 워커 하나뿐이라 토픽이 할 일이 없다 | **Tier C 에 대응 항목 없음** — 팬아웃은 현재 한계 목록에 들어 있지 않다 |
| (c) | 앱 인스턴스를 늘렸을 때 `worker_claim/lost` 비율이 **Z%** 를 넘을 때 | 아래 인용대로 그 축은 측정된 적이 없다. Z 는 **가정** | Tier C **C-5**(앱 1대·DB 1대 전제, 분산 락 없음) |

(c) 의 근거:

> **측정 범위의 한계**: 이 결론은 **단일 인스턴스** 전제다. 다중 인스턴스에서는 `claim` 실패가 `continue`이므로
> 남이 선점한 job을 건너뛰고 더 뒤까지 훑을 수 있어야 하고, 그때 batch-size는 처리량이 아니라 경합 헤드룸으로
> 기능한다. 그 축은 측정하지 않았다(백로그 #9와 같은 이유로 범위 밖). 인스턴스를 늘리면 batch-size 3은
> 재검토 대상이다.

— 출처: JAVA `docs/develop-report.md` "완료: batch-size 실측 (2026-08-20)"

## 결과

**미측정 — Phase 3 이월.**

채워지려면 다음을 재야 한다.

| 무엇을 재면 | 어느 칸이 채워지나 |
|---|---|
| 요구서 Tier A(접수 500 RPS × 10분)의 `POST /api/jobs` p50/p99 | 폴링 잡큐가 접수 경로에 비용을 더하는지 — 접수는 DB 커밋뿐이라 사실상 전송 수단과 무관하다는 예상의 확인 |
| 접수 → `PROCESSING` 선점 지연 p99 | 재도입 트리거 (a) 의 Y 를 가정에서 실측으로 |
| PERF-07 의 `HOLDING` 적체 곡선과 소진 시간 | 위 역산(필요 concurrency 3,000~7,000)이 실제 격차와 맞는지 |
| 앱 2대 이상에서 `worker_claim/lost` 비율 | 재도입 트리거 (c), Tier C C-5, ADR-004 의 단일 인스턴스 전제 |
