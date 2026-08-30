# 단계별 학습 브랜치

이 저장소의 크레딧 시스템을 **방어 로직이 하나도 없는 상태**에서 시작해
**완성본**까지 일곱 단계로 나눠 놓은 브랜치들이다.

각 단계는 그 자체로 컴파일되고 테스트가 통과하는 실행 가능한 상태다.
테스트도 그 단계에 실제로 존재하는 방어만 검증하도록 맞춰져 있다.

히스토리는 `step0 → step6` 순방향 선형이다. 한 겹씩 이렇게 본다:

```
git diff step0-naive step1-validation
git log --oneline step0-naive..step6-resilience
```

`main` 은 실제 개발 히스토리를 담은 브랜치다. 동작과 방어 장치는 `step6-resilience` 와 같지만
코드가 글자 그대로 같지는 않다 — step 브랜치 쪽은 엔티티의 불변 필드를 생성자에 `val` 로 선언하고,
`ChargeService` 와 `OrganizationQueryService` 를 `OrganizationService` 하나로 합쳐 두었다.

각 브랜치에는 그 단계까지의 **상세 문서**가 `docs/` 아래에 하나씩 들어 있다.
`step3-idempotency` 를 체크아웃하면 `docs/step0-naive.md` 부터 `docs/step3-idempotency.md` 까지 읽을 수 있다.
이 파일은 전체 지도이고, `docs/` 쪽이 단계별 상세다 — 실제 코드 인용, 테스트가 무엇을 단언하는지,
그 단계의 코드를 어떻게 깨뜨릴 수 있는지가 거기 있다.

---

## 도메인 한 줄 요약

조직(organization)이 크레딧을 **충전**하고, 이미지 생성을 **요청**하면 잔액에서 비용을 **hold** 한다.
워커가 job 을 집어 생성 스텁을 호출하고, 성공하면 **confirm**, 실패하면 **환불**한다.

돈이 걸린 시스템이라 "두 번 처리됨", "잔액이 음수가 됨", "돈이 묶인 채 사라짐" 이 전부 사고다.
아래 단계들은 그 사고를 하나씩 막아 나가는 순서다.

---

## step0-naive — 방어 없는 순수 비즈니스 로직

동작하는 것: 충전, 요청, hold, 워커 생성, 결과 기록.

없는 것: **전부.**

- 입력을 검증하지 않는다. 음수 충전도, 빈 prompt 도 그대로 들어간다
- 잔액이 모자라도 그냥 깎는다 → **잔액이 음수가 된다**
- 예외 계층이 없다. 조직이 없으면 `IllegalStateException` 이 그대로 올라가 500 이 나간다
- 생성에 실패하면 hold 된 돈이 묶인 채 끝난다

볼 곳: `job/service/HoldService.kt` — 열 줄 남짓이다. 여기서 출발한다.

---

## step1-validation — 입력 검증과 예외 계층

새로 생긴 것:

- `global/exception/` — `BusinessException` 아래 `InvalidRequestException`,
  `InsufficientBalanceException`, `OrganizationNotFoundException`
- `GlobalExceptionHandler` — `@RestControllerAdvice` 로 400 / 404 / 409 를 제대로 내려준다
- amount 검증(0 초과, 100만 이하), prompt 검증(공백 아님, 1000자 이하)
- 잔액 부족 검사

**아직 못 막는 것 — 여기가 이 단계의 핵심이다.**

```kotlin
val organization = organizationRepository.getOrThrow(organizationId)
if (organization.balance < cost) {          // ← 읽고
    throw InsufficientBalanceException(...)
}
organization.deduct(cost)                    // ← 쓴다
```

읽기와 쓰기가 두 걸음이다. 두 요청이 같은 잔액을 읽으면 둘 다 검사를 통과한다.
검사를 붙였는데도 잔액은 여전히 음수가 될 수 있다. **애플리케이션 레벨 검사로는 경합을 못 막는다.**

---

## step2-atomic-balance — 원자적 잔액 갱신과 원장

새로 생긴 것:

- 조건부 UPDATE. 읽기와 쓰기를 DB 한 문장으로 합친다

  ```sql
  UPDATE Organization o
  SET o.balance = o.balance - :amount
  WHERE o.id = :id AND o.balance >= :amount
  ```

  갱신 건수가 0이면 잔액이 모자랐다는 뜻이다. 검사와 차감 사이에 틈이 없다
- `ledger/` 패키지 — 모든 잔액 변동을 `HOLD` / `CONFIRM` / `CHARGE` 원장으로 남긴다.
  잔액은 결과값일 뿐이고, 원장이 사실의 기록이다

볼 곳: `organization/repository/OrganizationRepository.kt` 의 `deductBalance`,
`job/service/HoldService.kt` 의 `deductBalance` 헬퍼(0건 → 예외).

**아직 못 막는 것**: 같은 요청을 두 번 보내면 두 번 처리된다.
네트워크가 끊겨 클라이언트가 재시도하면 충전이 두 번 먹히고 job 이 두 개 생긴다.

---

## step3-idempotency — 멱등키로 중복 요청 막기

새로 생긴 것:

- `IdempotencyKey` 엔티티 + `(organizationId, idemKey)` 유니크 제약
- 원장에도 `(organizationId, idemKey)` 유니크 제약 — 충전 쪽 중복 방어
- API 에 `idemKey` 가 등장한다. 응답에 `duplicate` 플래그가 붙는다
- `DuplicateRequestInProgressException` — 선점한 요청이 아직 jobId 를 붙이기 전에 들어온 재시도
- `GlobalExceptionHandler` 가 `DataIntegrityViolationException` 의 유니크 위반을 409 로 번역한다

핵심은 **애플리케이션의 조회 검사가 아니라 DB 유니크 제약이 최종 방어선**이라는 것이다.
"먼저 조회해서 없으면 INSERT" 는 그 사이에 다른 요청이 끼어들 수 있다.
유니크 제약이 있으면 진 쪽은 예외를 맞고, 그 예외를 정상 경로로 번역하면 된다.

볼 곳: `job/service/HoldService.kt`, `job/concurrency/DuplicateIdemKeyTest.kt`.

**아직 못 막는 것**: 워커가 스케줄러 스레드에서 순차로 한 건씩 처리한다.
병렬로 돌리는 순간 여러 워커가 같은 job 을 집는다.

---

## step4-state-machine — 시도 번호 기반 상태 전이 CAS

새로 생긴 것:

- `Job.attemptNo` — 몇 번째 시도인지
- 모든 상태 전이가 조건부 UPDATE 가 된다

  ```sql
  UPDATE Job j SET j.status = :newStatus
  WHERE j.id = :jobId AND j.status = :expectedStatus AND j.attemptNo = :attemptNo
  ```

  기대한 상태와 시도 번호가 맞을 때만 바뀐다. compare-and-swap 이다
- `WorkerExecutorConfig` — 고정 크기 스레드 풀. 워커가 진짜로 병렬이 된다
- 워커는 **선점(claim)** 후에야 executor 에 넘긴다. 선점에 실패하면(0건) 다른 워커가 이미 가져간 것이다

왜 시도 번호가 필요한가: 상태만 보면 부족하다.
1번 시도가 멈춘 줄 알고 2번 시도를 띄웠는데 1번이 뒤늦게 깨어나 `COMPLETED` 를 쓰면
2번의 결과가 덮인다. 시도 번호가 있으면 **늦게 도착한 쓰기는 조용히 무시**된다.

볼 곳: `job/repository/JobRepository.kt` 의 `transitionIfStatusAndAttemptMatch`,
`job/worker/GenerationWorker.kt` 의 `claim`.

**아직 못 막는 것**: 워커 프로세스가 죽으면 job 이 `PROCESSING` 에 영원히 남는다.
`FAILED` 가 된 job 은 재시도도 환불도 안 된다. 돈이 묶인 채 끝난다.

---

## step5-recovery — heartbeat 회수와 재시도 / 최종 환불

새로 생긴 것:

- `heartbeat/` — 처리 중인 워커가 Redis ZSET 에 주기적으로 만료 시각을 갱신한다.
  워커가 죽으면 갱신이 멈추고 만료된다
- `DeadJobRecoveryTask` — 5초마다 세 가지를 한다
  1. heartbeat 만료된 시도를 `FAILED` 로 내린다
  2. heartbeat 도 없이 `PROCESSING` 으로 오래 정체된 job 을 `FAILED` 로 내린다
  3. `FAILED` job 을 재시도하거나(`attemptNo + 1`), 최대 시도를 넘겼으면 최종 환불한다
- `JobStatus.REFUNDED`, `LedgerType.REFUND`

heartbeat 와 timeout 두 겹인 이유: heartbeat 는 빠르지만 Redis 에 의존한다.
`updatedAt` 기반 정체 감지는 느리지만 DB 만 있으면 된다. 서로의 구멍을 메운다.

볼 곳: `job/scheduling/DeadJobRecoveryTask.kt`, `heartbeat/HeartbeatRegistry.kt`.

**아직 못 막는 것 — 회수 장치 자체가 깨질 때다.**

- Redis 가 잠깐 죽으면 모든 heartbeat 조회가 실패한다.
  그러면 **멀쩡히 살아 있는 워커의 job 이 전부 만료로 판정**되어 회수된다. 대량 오탐이다
- 배치 루프 한복판에서 예외가 나면 그 주기의 나머지 job 이 통째로 처리되지 않는다
- 잔액과 원장이 어긋나도 아무도 모른다

---

## step6-resilience — 인프라 장애 내성과 감사 (완성본)

새로 생긴 것:

- **항목 단위 예외 격리** — `DeadJobRecoveryTask` 의 세 단계와 각 항목이 따로 `try/catch` 된다.
  한 건의 실패가 같은 주기의 나머지를 막지 못한다
- **Redis 실패는 회수를 멈춘다** — heartbeat 조회가 실패하면 예외가 그대로 올라오고,
  위 격리가 그 주기 또는 그 항목만 건너뛴다. 판단 근거를 잃었을 때
  **아무것도 안 하는 쪽으로 기운다**. 갱신(`refreshHeartbeat`)만 예외를 삼키는데,
  주기 실행 스레드가 죽으면 안 되기 때문이다
- **`LedgerReconciliationTask`** — 1분마다 `initialBalance + 원장 합계 == 현재 잔액` 을 대사한다.
  틀리면 `ERROR` 로그를 남긴다. 막는 게 아니라 **어긋났음을 알아채는** 장치다
- **`IdempotencyKeyCleanupTask`** — 멱등키는 무한히 쌓인다. 새벽 2시에 보존 기간 지난 것을 지운다
- **설정 불변식** — `HeartbeatProperties` 의 `require(refreshInterval < timeout)` 같은 것.
  잘못된 설정으로 뜨느니 부팅에서 죽는 게 낫다
- 워커/프로세서의 예외 방어 — executor 위임이 거부되면 선점을 되돌리고,
  결과 반영에 실패하면 timeout 회수에 맡긴다

이 단계의 관점: **방어 로직도 코드다. 그것도 깨진다.**
깨졌을 때 무엇이 일어나야 하는지까지 정해 둔 것이 마지막 겹이다.

여기서도 남는 것: Redis 가 **복구된 직후**다. 장애 동안 갱신되지 못한 heartbeat 는
전부 만료로 보이므로, 갱신보다 회수 스캔이 먼저 돌면 살아 있는 job 이 회수될 수 있다.
`updatedAt` 정체 스캔이 60초를 기다리는 것과 달리 heartbeat 만료는 10초라 창이 짧다.

---

## 추천 학습 순서

1. `git checkout step0-naive` 하고 `HoldService`, `OrganizationService`, `GenerationWorker` 를 읽는다.
   전체가 몇 백 줄이라 한 번에 들어온다
2. 각 단계로 넘어가기 전에 **"지금 이 코드를 어떻게 깨뜨릴 수 있을까"** 를 먼저 생각해 본다
3. 그다음 `git diff stepN stepN+1` 로 실제로 어떻게 막았는지 본다
4. 테스트 diff 도 같이 본다. 그 단계가 무엇을 보장한다고 주장하는지가 테스트에 적혀 있다

```
git diff step0-naive step1-validation
git diff step1-validation step2-atomic-balance
git diff step2-atomic-balance step3-idempotency
git diff step3-idempotency step4-state-machine
git diff step4-state-machine step5-recovery
git diff step5-recovery step6-resilience
```

특정 파일 하나가 어떻게 자랐는지 따라가려면:

```
git log -p --follow step0-naive..step6-resilience -- src/main/kotlin/com/example/credit_system_kotlin/job/service/HoldService.kt
```

## 실행

각 단계마다:

```
./gradlew test        # Docker 필요 (Testcontainers)
./gradlew bootRun     # MySQL 필요, step5 부터는 Redis 도 필요
```
