# ADR-001 — Kafka 도입

- 날짜: 2026-07-04
- 저장소: JAVA (`../credit_system`)
- 커밋: `073163c`(outbox relay → Kafka 발행), `9f571a2`(Redis heartbeat + `@KafkaListener` 워커)

## 상황

Kafka 는 검토 끝에 고른 것이 아니라 **최초 설계 스펙이 처음부터 규정한 것**이다.
스펙 `2026-07-04-credit-system-design.md` 는 §3 아키텍처 개요에서 컴포넌트 목록에 이미 Kafka 를 박아 두었다.

> - **Web** (Thymeleaf + REST API) — 로그인, 대시보드, 생성 요청/충전 API
> - **OutboxRelay** (`@Scheduled(fixedDelay=1000)`) — `outbox` 테이블에서 `sent=false` 행을 폴링해 Kafka로 발행 후 `sent=true` 처리
> - **GenerationWorker** (`@KafkaListener`) — 큐 메시지를 소비해 stub 이미지 생성 호출 → confirm/fail 처리
> - **DeadJobSchedulerTask** (`@Scheduled(fixedDelay=5000)`) — Redis heartbeat 만료 감지 + `FAILED` job 재시도/최종 환불
>
> 인프라는 `docker-compose.yml`로 MySQL + Kafka + Redis를 로컬에 띄운다. H2는 테스트에서만 사용한다(운영/개발은 MySQL).

— 출처: JAVA `git show e55ac09:docs/superpowers/specs/2026-07-04-credit-system-design.md` §3

§6.2 는 전달 경로와 함께 **컨슈머 멱등을 "향후 과제"로 미루는 판단**을 적고 있다.
이 한 문장이 뒤에 ADR-002 로 이어지는 비용의 뿌리다.

> ### 6.2 Outbox → Kafka
>
> `OutboxRelay`가 `@Scheduled(fixedDelay=1000)`으로 `sent=false` 행을 폴링 → `KafkaTemplate.send("generation-jobs", payload)` 성공 시 `sent=true` UPDATE. 재전송 시 컨슈머 측 idempotent 처리는 원안의 "향후 과제"이며, attempt_no 조건부 UPDATE가 사실상 그 역할을 겸하므로 별도 처리하지 않는다.

— 같은 문서 §6.2

§6.3 은 워커를 `@KafkaListener` 로 못 박았다.

> ### 6.3 Worker (`@KafkaListener`)
>
> ```
> consume(message: {jobId, orgId, attemptNo, prompt})
>   redis.zadd("heartbeats", jobId, now+timeout)
>   UPDATE job SET status=PROCESSING WHERE id=? AND attempt_no=?   (0행이면 즉시 리턴 — 무효 메시지)
>   try:
>     result = stubClient.generate(prompt)   // Thread.sleep(min~max) + 확률적 실패(failure-rate)
>     confirmService.confirm(jobId, attemptNo, result)
>   catch:
>     failureService.markFailed(jobId, attemptNo)
>   finally:
>     redis.zrem("heartbeats", jobId)
> ```

— 같은 문서 §6.3

## 당시 수치

**없음 — 측정하지도, 추정하지도 않았다.**

스펙 전문(§0~§9)에 RPS 목표, 이벤트 발행량 추정, 처리량 계산이 **한 줄도 없다.**
§0 이 밝힌 목적은 "포트폴리오/연구용 프로젝트… 핵심 어필 포인트는 **크레딧 차감/환불의 정확성**(동시성 안전성)"이고,
파티션 3 이나 폴링 1초 같은 숫자에 근거가 붙어 있지 않다.
즉 Kafka 는 부하 추정으로 정당화된 적이 없다.

## 결정

outbox 테이블 → `@Scheduled` relay → Kafka 토픽 → `@KafkaListener` 워커.
제거 직전 시점의 실제 설정값은 이렇다.

> ```yaml
>   kafka:
>     bootstrap-servers: localhost:9092
>     producer:
>       key-serializer: org.apache.kafka.common.serialization.StringSerializer
>       value-serializer: org.apache.kafka.common.serialization.StringSerializer
>     consumer:
>       group-id: generation-worker
>       auto-offset-reset: earliest
>       key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
>       value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
> ```
> ```yaml
>   kafka:
>     topic: generation-jobs
>     partitions: 3
> ```

— 출처: JAVA `git show 00b2b34^:src/main/resources/application.yml`

relay 는 1초 고정 지연 폴링, 발행 ack 를 10초까지 기다린다.

> ```java
>     /** 미발송 outbox를 Kafka에 전달하고 성공 건을 표시한다. */
>     @Scheduled(fixedDelayString = "${app.scheduling.outbox-relay-interval-millis:1000}")
>     public void relay() {
>         List<OutboxEntry> pending = outboxRepository.findBySentFalseOrderByIdAsc();
>         for (OutboxEntry entry : pending) {
>             try {
>                 kafkaTemplate.send(appProperties.kafka().topic(), entry.getJobId().toString(), entry.getPayload())
>                         .get(10, TimeUnit.SECONDS);
>                 outboxRepository.markSent(entry.getId());
> ```

— 출처: JAVA `git show 00b2b34^:src/main/java/com/example/credit_system/outbox/service/OutboxRelay.java`

정리하면: 토픽 `generation-jobs`, 파티션 3, 컨슈머 그룹 `generation-worker`,
outbox relay 폴링 1초, 발행 ack 대기 10초.

## 대안

**스펙에 Kafka 대안 검토 없음.** 스펙이 명시적으로 비교한 것은 세 가지뿐이고, 셋 다 메시지 전달 수단이 아니다.

- §2 — JPA `@Version` 대신 수동 조건부 UPDATE. 원문:

  > 이 낙관적 락은 **JPA `@Version`을 쓰지 않고**, `@Modifying @Query`로 직접 작성한 조건부 UPDATE의 반영 row 수(0/1)로 판정한다. JPA의 `OptimisticLockException` 기반 방식은 예외 처리 흐름이 원안의 "0행이면 재시도/실패 분기" 시맨틱과 다르므로 채택하지 않는다.

- §7 — 화면 갱신에 SSE 대신 클라이언트 폴링("핵심 어필 포인트는 크레딧 로직이지 이벤트 인프라가 아니므로, 폴링이 더 가볍고 목적에 부합한다")
- §8 — mock 프레임워크 대신 H2 / Testcontainers / EmbeddedKafka 3계층

§9 "미해결 / 향후 과제"에도 전달 수단 재검토 항목은 없다. DB 잡큐는 **검토되고 기각된 것이 아니라 거론되지 않았다.**

## 결과

Kafka 가 만들어낸 문제를 고치는 데 후속 커밋 다섯이 들었다.
이것이 ADR-002 가 말하는 "비용"의 실증이다. 두 건은 Kafka 외 변경이 섞인 커밋이라 그 몫을 따로 표시한다.

| 커밋 | 날짜 | 제목 | Kafka 몫 | 규모 |
|---|---|---|---|---|
| `42ccb4d` | 2026-07-05 | fix: guarantee outbox delivery with broker ack and reap stale HOLDING jobs | 발행이 broker ack 를 받아야 `sent=true` 로 넘어가게 고침. 발행 실패로 영영 `HOLDING` 에 남는 job 을 회수하는 스캔을 함께 추가 | 12 files, +242/−9 |
| `b503e6b` | 2026-07-05 | fix: repair docker-compose Kafka so local dev actually works | 전부 Kafka. 로컬 개발이 돌지 않던 compose 수리 | 2 files, +28/−17 |
| `d3cbe73` | 2026-07-06 | fix: isolate poison messages to DLT and reap stale PROCESSING jobs | `KafkaConsumerConfig` 신설(+31), DLT 토픽(`KafkaTopicConfig` +10), poison message 격리. PROCESSING 정체 회수는 별건 | 12 files, +222/−6 |
| `89d0ecb` | 2026-07-06 | chore: demote H2 to test scope, align Kafka partition/concurrency settings | 파티션 수와 컨슈머 동시성을 맞춤(`KafkaTopicConfig`, `GenerationWorker`, yml). **H2 스코프 강등은 Kafka 와 무관** | 9 files, +23/−13 |
| `3478b8d` | 2026-07-15 | fix: prevent attempt burn on broker outage, heartbeat leak, and input bounds | broker 장애로 발행이 실패했을 때 재시도 횟수가 소모되지 않게 함(`DeadJobSchedulerTask`, `OutboxEntry`, `OutboxWriter`). **heartbeat leak·입력 경계(ChargeService·Organization)는 별건** | 15 files, +84/−26 |

다섯 커밋이 각각 다른 실패 모드를 닫았다 — 전달 보장, 로컬 기동, poison message, 파티션·동시성 정렬, broker 장애 중 attempt 소모.
