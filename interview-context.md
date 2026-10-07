# 크레딧 시스템 — 면접 대비 컨텍스트

---

## 1. 프로젝트 개요

**하는 일.** 사용자가 크레딧을 써서 이미지 생성을 요청하면, 요청 시점에 비용을 잔액에서 떼어 묶고(hold), 백그라운드 워커가 생성기를 호출해 성공하면 확정(confirm), 실패하면 재시도하다 상한을 넘으면 전액 환불(refund)한다. 돈이 걸린 비동기 파이프라인에서 "두 번 처리됨·잔액 음수·돈이 묶인 채 방치됨"을 막는 것이 설계의 중심이다.

**사용자 흐름.** 구글 로그인(허용 목록 이메일만) → 운영자가 크레딧 지급 → `POST /api/jobs {idemKey, prompt}` → `jobId` 수신 → `GET /api/jobs/{id}` 폴링으로 상태·결과 확인 → 원장 조회(`/api/ledger`). 화면은 Thymeleaf 서버 렌더링(`templates/*.html`).

**기술 스택** (`build.gradle.kts`, `docker-compose.yml`)

| 항목 | 값 |
|---|---|
| 언어 | **Kotlin 2.3.21**, JVM toolchain 17. Java 아님 |
| 프레임워크 | Spring Boot 4.1.0 (webmvc, data-jpa, data-redis, security + oauth2-client, actuator, thymeleaf) |
| DB | MySQL 8.4 (compose·Testcontainers), 스키마는 Flyway V1~V4, `ddl-auto: validate` |
| Redis | `redis:7`, heartbeat 저장소로만 사용. `timeout: 2s`, `connect-timeout: 2s` (`application.yml`) |
| 외부 이미지 API | **없음.** `GenerationStubClient`가 `Thread.sleep(3000~7000ms)` 후 30% 확률로 예외, 성공 시 가짜 URL 반환 |
| 관측 | Micrometer + Prometheus, 알람 규칙 12개(`deploy/observability/prometheus/rules/credit.rules.yml`) |
| 정적 검사/CI | detekt, ktlint, GitHub Actions `./gradlew test detekt ktlintCheck` (`.github/workflows/ci.yml`) |

**배포 구조.** 서버 1대를 전제로 한다(roadmap 결정 13: "실사용자가 한 명이라 2대를 띄울 이유가 없다. 스케줄러 겹침 실측과 ShedLock은 하지 않는다"). 아직 실배포 전이다(step10 보류). 이미지는 멀티스테이지 `Dockerfile`, 포트 8080(API)·8081(액추에이터, 관리 포트 분리). 동시 실행을 가정한 설정은 없고, 대신 모든 상태 전이가 조건부 UPDATE라 다중 인스턴스에서도 이중 처리는 막힌다는 것이 설계 논지다(결정 1).

---

## 2. 도메인 모델과 스키마

출처: `db/migration/V1__baseline.sql` ~ `V4__jobs_user_id_index.sql`, 엔티티 `@Table`.

| 테이블 | 핵심 컬럼 | 인덱스·제약 |
|---|---|---|
| `users` | `balance BIGINT`, `initial_balance BIGINT`, `email`, `google_sub` | UK `uk_users_email(email)`, UK `uk_users_google_sub(google_sub)` |
| `jobs` | `user_id`, `hold_amount`, `prompt VARCHAR(1000)`, `status ENUM(...)`, `attempt_no INT`, `result_url`, `created_at`, `updated_at` | **`idx_jobs_status_id (status, id)`**, `idx_jobs_user_id (user_id, id)` (V4) |
| `ledger_entries` | `user_id`, `job_id NULL`, `type ENUM('ADMIN_GRANT','CHARGE','CONFIRM','HOLD','REFUND')`, `amount`, `idem_key NULL` | **UK `uk_ledger_user_idem (user_id, idem_key)`**, `idx_ledger_user_id(user_id)` |
| `idempotency_keys` | `user_id`, `idem_key VARCHAR(100)`, `job_id NULL`, `created_at` | **UK `uk_idempotency_user_key (user_id, idem_key)`**, `idx_idem_created_at(created_at)` |

- **status 인덱스: 있음.** `(status, id)` 복합. 워커의 `WHERE status='HOLDING' ORDER BY id LIMIT n`에 맞춘 모양이다(`Job` `@Table(indexes)`).
- **원장 유니크의 한계.** `(user_id, idem_key)`인데 HOLD·CONFIRM·REFUND 행은 `idem_key`가 NULL이다(`LedgerEntry.hold/confirm/refund`가 `null`을 넘김). MySQL 유니크는 NULL을 여러 개 허용하므로 이 제약은 ADMIN_GRANT·CHARGE의 중복만 막는다. **`(job_id, type)` 유니크는 없다.**
- FK 제약은 어느 테이블에도 없다.
- `status`·`type`은 `@Enumerated(STRING)`이 MySQL에서 만든 네이티브 ENUM이다. 값을 추가하면 `ALTER ... MODIFY`가 필요하다(V3가 실제 사례).
- 계정 단위는 원래 조직이었고 V2에서 `organizations → users`로 이름만 옮겼다(roadmap 결정 8).

---

## 3. 핵심 처리 흐름

| # | 단계 | 위치 |
|---|---|---|
| 1 | 요청 수신, 인증 주체에서 userId 추출 | `JobApiController.create` → `CurrentUser` |
| 2 | 검증(idemKey 1~100자, prompt 1~1000자) | `HoldService.validateRequest`, `validateIdemKey` |
| 3 | 멱등키 조회 → 있으면 기존 jobId 반환 | `idempotencyKeyRepository.findByUserIdAndIdemKey` |
| 4 | 멱등키 INSERT (유니크로 동시 중복 차단) | `idempotencyKeyRepository.save` |
| 5 | 잔액 조건부 차감(hold) | `HoldService.deductBalance` → `UserRepository.deductBalance` |
| 6 | job 생성(HOLDING, attemptNo=0), 멱등키에 jobId 연결, HOLD 원장(−cost) | `jobRepository.save`, `attachJobId`, `ledgerRepository.save` |
| 7 | 500ms마다 빈 슬롯 수만큼 HOLDING 조회 | `GenerationWorker.dispatchPendingJobs` |
| 8 | 선점 CAS: HOLDING→PROCESSING | `GenerationWorker.claim` → `JobRepository.startProcessingIfAttemptMatches` |
| 9 | 워커 풀에 위임, heartbeat 시작 | `workerExecutor.execute`, `GenerationJobProcessor.runGeneration` |
| 10 | 외부 생성 호출 | `GenerationStubClient.generate` |
| 11a | 성공: COMPLETED 전이 + CONFIRM 원장(0원) | `JobLifecycleService.confirm` |
| 11b | 실패: PROCESSING→FAILED | `JobLifecycleService.markFailed` |
| 12 | 5초마다 FAILED 재검토: 재시도(attemptNo+1, HOLDING) 또는 최종 환불 | `DeadJobRecoveryTask.retryOrRefund` → `JobLifecycleService.retry / finalRefund` |

2~6은 한 TX(`HoldService.requestGeneration`), 7·9·10은 TX 없음, 8·11·12의 전이는 각자 짧은 TX다(4절).

hold 시점에 잔액이 이미 빠지므로 confirm은 잔액을 건드리지 않는다. refund만 `addBalance`로 되돌린다.

---

## 4. 트랜잭션 경계

**`@Transactional` 전수 목록** (`grep` 결과. 전부 기본 전파 `REQUIRED`, `rollbackFor` 지정은 **한 곳도 없음**)

| 계층 | 위치 |
|---|---|
| 서비스(쓰기) | `HoldService.requestGeneration`, `JobLifecycleService.confirm / markFailed / retry / finalRefund`, `UserService.grant` |
| 서비스(읽기, `readOnly = true`) | `UserService.getBalance`, `JobQueryService.findByUser / findOne`, `LedgerQueryService` |
| 리포지토리 메서드 | `JobRepository.completeIfAttemptMatches`, `transitionIfStatusAndAttemptMatch`, `incrementAttemptForRetry`, `IdempotencyKeyRepository.deleteByIdIn` |

리포지토리 메서드에 직접 붙인 것이 요점이다. 이 네 개는 서비스 TX 없이 스케줄러 스레드가 바로 부르는 경로(선점, 선점 롤백, 회수 전이, 멱등키 정리)가 있어서, 단건 UPDATE가 자체 TX로 커밋되도록 했다. 반대로 `UserRepository.deductBalance / addBalance`, `IdempotencyKeyRepository.attachJobId`에는 자체 TX가 없고, 호출자는 전부 서비스 TX 메서드다(`HoldService.requestGeneration`, `JobLifecycleService.finalRefund`, `UserService.grant`).

**요청 처리 한 TX(TX-A)에 묶이는 것** (`HoldService.requestGeneration`): 멱등키 조회 → 멱등키 INSERT → 잔액 차감 UPDATE → job INSERT → 멱등키 jobId UPDATE → HOLD 원장 INSERT. 잔액 부족 예외가 나면 멱등키까지 롤백된다(`ServiceTransactionRollbackTest` 첫 테스트).

**워커 메서드에 TX가 없는가: 없다.** `GenerationWorker.dispatchPendingJobs`, `GenerationJobProcessor.runGeneration` 모두 어노테이션 없음. `open-in-view: false`.

**외부 호출이 TX 밖인가: 밖이다.** `runGeneration` → `generateOrMarkFailed` → `stubClient.generate`는 TX 없는 빈 안에서 불리고, 결과 반영만 별도 빈 `JobLifecycleService`의 TX 메서드로 넘어간다.

```kotlin
// GenerationJobProcessor.runGeneration
val heartbeatFuture = heartbeatRegistry.startHeartbeat(jobId, attemptNo)
try {
    val resultUrl = generateOrMarkFailed(job) ?: return   // 외부 호출, TX 없음
    confirm(job, resultUrl)                              // JobLifecycleService.confirm (TX-B)
} finally {
    heartbeatRegistry.stopHeartbeat(jobId, attemptNo, heartbeatFuture)
}
```

**self-invocation 회피 구조.** 스케줄러(`GenerationWorker`) → 실행 조정자(`GenerationJobProcessor`, TX 없음) → 상태 전이 서비스(`JobLifecycleService`, TX 있음)로 빈을 셋으로 나눴다. TX 메서드는 항상 다른 빈에서 프록시를 거쳐 호출된다. `HoldService` 내부의 private 메서드 호출은 이미 같은 TX 안이라 문제가 없다.

---

## 5. 동시성과 멱등성 제어

**job 선점** (`JobRepository.transitionIfStatusAndAttemptMatch`, `startProcessingIfAttemptMatches`가 래핑)

```sql
UPDATE Job j SET j.status = :newStatus, j.updatedAt = :now
WHERE j.id = :jobId AND j.status = :expectedStatus AND j.attemptNo = :attemptNo
-- 선점: newStatus=PROCESSING, expectedStatus=HOLDING
```

affected rows 확인: **있음.** `GenerationWorker.claim`에서 `if (updated == 0)`이면 LOST 이벤트를 내고 건너뛴다. 조회(SELECT)와 선점(UPDATE)이 분리돼 있어도 UPDATE의 WHERE가 상태를 다시 확인하므로 낙관적 CAS로 동작한다.

**잔액 차감** (`UserRepository.deductBalance`)

```sql
UPDATE User u SET u.balance = u.balance - :amount, u.updatedAt = :now
WHERE u.id = :id AND u.balance >= :amount
```

0행이면 `REJECTED` 이벤트 후 `InsufficientBalanceException` → 409 `INSUFFICIENT_BALANCE`(`GlobalExceptionHandler`). 읽고-계산하고-쓰는 대신 DB 한 문장으로 조건 검사와 차감을 합쳤다. 사용자 행이 hot row가 되는 한계는 roadmap 5절에 스스로 적어 두었다.

**멱등키 흐름** (`HoldService.requestGeneration`)
1. `findByUserIdAndIdemKey` 조회 → 있으면 `jobId`가 채워져 있으면 `HoldResult(jobId, duplicate=true)`로 **기존 결과 반환(HTTP 200)**. `jobId`가 null이면 `DuplicateRequestInProgressException` → 409.
2. 없으면 INSERT. 동시에 들어온 두 요청이 둘 다 조회를 통과하면 뒤쪽 INSERT가 `uk_idempotency_user_key` 위반 → `DataIntegrityViolationException` → 핸들러가 UNIQUE 종류만 골라 **409 `DUPLICATE_IN_PROGRESS`**.
3. 신규 성공도 200 + `duplicate=false`다(201 아님).

정리하면 순차 재시도는 기존 결과 반환, 동시 중복은 409 에러. `jobId == null` 분기는 jobId 연결이 같은 TX 안에서 끝나므로 커밋된 행에서는 보이지 않는다. MySQL 격리 수준에서 이 분기에 실제로 도달하는 경로가 있는지는 **확인 불가**다.

운영자 지급(`UserService.grant`)은 멱등키 테이블 대신 원장 자체를 쓴다: `ledgerRepository.findByUserIdAndIdemKey` 1차 조회, `uk_ledger_user_idem` 2차 방어.

**사용하지 않는 것:** `SELECT ... FOR UPDATE`, `SKIP LOCKED`, `@Lock`, `@Version`, 분산락(Redisson 등) 전부 **없음**(`grep` 결과 0건). 모든 경합을 조건부 UPDATE의 affected rows와 유니크 제약으로 해결한다.

---

## 6. job 상태 머신

`JobStatus`: `HOLDING, PROCESSING, COMPLETED, FAILED, REFUNDED`. 모든 전이는 `status`와 `attemptNo`를 WHERE에 함께 거는 CAS다.

| 전이 | 조건(WHERE) | 메서드 | 주체 |
|---|---|---|---|
| (생성) → HOLDING, attempt 0 | 잔액 차감 성공 | `Job.hold` | 요청 TX |
| HOLDING → PROCESSING | status=HOLDING, attempt 일치 | `startProcessingIfAttemptMatches` | 디스패처 |
| PROCESSING → HOLDING | status=PROCESSING, attempt 일치 | `rollbackToHoldingIfProcessing` | executor 위임 실패 시(안전망) |
| PROCESSING → COMPLETED | status=PROCESSING, attempt 일치 | `completeIfAttemptMatches` | 워커 |
| PROCESSING → FAILED | status=PROCESSING, attempt 일치 | `failIfProcessing` | 워커(생성 실패) / 회수 태스크 |
| FAILED → HOLDING, attempt+1 | status=FAILED, attempt 일치, `attemptNo + 1 < maxAttempts` | `incrementAttemptForRetry` | 회수 태스크 |
| FAILED → REFUNDED | status=FAILED, attempt 일치, 위 조건 불충족 | `refundIfFailed` | 회수 태스크 |

- 재시도 필드: `jobs.attempt_no`. 최대값 `app.generation.max-attempts: 3` → 시도 번호 0·1·2의 **3회** 실행 후 환불(`RetryRefundTest`가 `attemptNo == 2`, `REFUNDED` 단언).
- 최종 실패 상태: **REFUNDED**가 종결. FAILED는 "재시도/환불 대기" 중간 상태이고 돈이 아직 묶여 있다(`JobRepository.countByStatusNotIn` 주석).
- 재시도에 backoff가 없다. FAILED가 되면 다음 5초 스캔에서 바로 HOLDING으로 돌아간다(roadmap "실서비스라면 막히는 것" 표).

---

## 7. 장애 대응 장치

**정체된 PROCESSING 회수** (`DeadJobRecoveryTask.scan`, `fixedDelay` 기본 **5000ms**, 어노테이션 기본값에만 있고 yml에는 없음). 한 주기에 세 단계를 각자 try/catch로 격리해 돈다.
1. heartbeat 만료: Redis ZSET에서 score ≤ now인 멤버를 꺼내 `failIfProcessing(jobId, attemptNo)`.
2. 백스톱: `status=PROCESSING AND updatedAt < now − 60s`(`app.processing.timeout-seconds: 60`), 100건씩. heartbeat가 **LIVE면 건너뛰고**, ABSENT 또는 **UNKNOWN(Redis 조회 실패)이면 회수**한다. Redis가 죽어도 돈이 묶이지 않게 하려는 선택이고, 오탐은 attemptNo CAS가 막는다는 근거가 주석에 있다(`recoverStalled` KDoc).
3. FAILED 100건 재시도 또는 환불.

**Redis heartbeat** (`HeartbeatRegistry`)

| 항목 | 값 |
|---|---|
| 자료구조 | Sorted Set 하나, 키 `heartbeats` |
| 멤버 | `"{jobId}:{attemptNo}"` (`JobAttempt.toMember`) |
| score | 만료 시각(epoch 초) = now + `timeout-seconds` **10** |
| 갱신 주기 | `refresh-interval-seconds` **5**, `scheduleAtFixedRate` |
| 갱신 주체 | **별도 스레드.** `Executors.newScheduledThreadPool(concurrency=3)`. 워커 스레드와 무관하게 돈다 |
| 키 TTL | 없음. 만료는 score 비교로 판정하고, 정리는 `stopHeartbeat`/회수 후 `ZREM` |

`HeartbeatProperties` init이 `refresh < timeout`을 강제한다. 조회 결과는 `LIVE / ABSENT / UNKNOWN` 세 값인데, "heartbeat 없음"과 "저장소를 못 봄"을 구분하려는 의도다(`HeartbeatState` KDoc).

**attemptNo(fencing token)**
- 증가 시점: **재시도 투입 한 곳뿐**(`incrementAttemptForRetry`, FAILED→HOLDING). 선점할 때는 증가하지 않는다. 토큰의 수명 단위는 "시도 한 번"이다.
- WHERE에 쓰는 쿼리: `transitionIfStatusAndAttemptMatch`(래퍼 4개: 선점·실패·선점 롤백·환불), `completeIfAttemptMatches`, `incrementAttemptForRetry`. 총 3개 쿼리, 6개 호출 경로.
- 효과: 회수된 뒤 늦게 끝난 워커의 confirm은 `status=PROCESSING AND attemptNo=N`이 안 맞아 0행 → `CONFIRM/STALE` 이벤트만 남기고 무시(`JobLifecycleService.confirm`).

**결과 저장과 confirm 원장.** `JobLifecycleService.confirm` 한 TX 안에서 `completeIfAttemptMatches`가 1행일 때만 `LedgerEntry.confirm` INSERT. 같은 TX다. 중복 방지는 **CAS 하나에 기댄다**. DB 제약(`(job_id,type)` 유니크)은 없다. 테스트 `JobLifecycleServiceTest."같은 attempt의 confirm을 두 번 호출해도 원장은 한 번만 기록된다"`가 이 경로를 확인한다. confirm TX가 실패하면 job은 PROCESSING으로 남는다. heartbeat는 `finally`에서 지워지므로 백스톱이 `updatedAt`(선점 시각) 기준 60초가 지난 뒤, 즉 confirm 실패 후 최대 60초 안에 FAILED로 돌려 재생성한다. 예전의 confirm 재시도는 "정합성은 정체 회수가 지킨다, 대신 만든 결과를 버리고 재생성하는 대가를 알고 고른다"는 이유로 걷어냈다(커밋 `a6ada3e`).

**외부 API 타임아웃: 해당 코드 없음.** HTTP 클라이언트가 없고 stub은 `Thread.sleep`이라 connect/read 타임아웃 설정이 존재하지 않는다. 타임아웃과 hang 대책은 roadmap step11(미머지)로 미뤄져 있다.

**스케줄러와 워커 풀**

| 항목 | 값 | 출처 |
|---|---|---|
| 디스패처 주기 | 500ms fixedDelay | `GenerationWorker` 어노테이션 기본값 |
| 한 번에 가져가는 건수 | `min(batch-size 3, 빈 슬롯)` | `app.worker.batch-size`, `WorkerSlots.free()` |
| 워커 풀 | core=max=`concurrency` **3**, `queueCapacity 0` | `WorkerExecutorConfig` |
| `@Scheduled` 풀 | `spring.task.scheduling.pool.size: 4` | `application.yml` |
| 대사 / 스냅샷 / 멱등키 정리 | 60000ms / 15000ms / cron `0 0 2 * * *` (Asia/Seoul), 보존 7일 | `application.yml` |

빈 슬롯만큼만 선점하는 이유(포화 시 "선점 → 거부 → 롤백"이 매 주기 헛도는 문제)와, 디스패처가 하나라 `free`가 과대평가될 수 없다는 논증이 `dispatchPendingJobs` KDoc에 있다.

**HikariCP: 설정 없음.** main의 yml 어디에도 `hikari` 항목이 없어 라이브러리 기본값이 적용된다. 테스트 프로파일만 `connection-timeout: 5000`(`src/test/resources/application-test.yml`).

**종료 처리.** `server.shutdown: graceful`은 웹 요청에만 적용된다. 워커 executor의 드레인(`waitForTasksToCompleteOnShutdown`)은 설정되지 않았고, 드레인 구현은 `step8-b-drain-archive` 브랜치에 보존한 채 step10으로 미뤘다(roadmap 변경 이력 2026-09-14).

---

## 8. 크레딧 원장과 정합성 검증

| 유형 | 부호 | 생성 위치 |
|---|---|---|
| HOLD | −cost (`require(cost > 0)`) | `HoldService` |
| CONFIRM | **0** ("hold를 확정할 뿐 잔액을 움직이지 않는다") | `JobLifecycleService.confirm` |
| REFUND | +holdAmount | `JobLifecycleService.finalRefund` |
| ADMIN_GRANT | +amount, idemKey 필수, 1회 상한 1,000,000 | `UserService.grant` |
| CHARGE | +amount, idemKey 필수 | 팩토리만 남음. 호출 경로 없음(결제 없는 충전 API는 step9-C에서 삭제) |

단식 원장이다. 잔액 컬럼과 원장이 병존하고, 둘을 대사로 맞춘다.

**정합성 검증 코드: 있음.**
- `LedgerReconciliationTask.reconcile` (60초): 사용자별 `balance == initialBalance + SUM(amount)` 검사, 100명씩 keyset 페이징.

```sql
SELECT new ...LedgerBalanceCheck(o.id, o.balance, o.initialBalance, COALESCE(SUM(l.amount), 0L))
FROM User o LEFT JOIN LedgerEntry l ON l.userId = o.id
WHERE o.id > :lastId GROUP BY o.id, o.balance, o.initialBalance ORDER BY o.id
```

- `DomainSnapshotTask.takeSnapshot` (15초): 음수 잔액 사용자 수, HOLD 원장 없는 job 수(`countJobsWithoutHoldEntry`), COMPLETED인데 CONFIRM 없음·REFUNDED인데 REFUND 없음(`countUnsettledTerminalJobs`), 미결 hold 건수·금액·최고령.
- 결과는 이벤트 → Micrometer 게이지 → 알람(`CreditLedgerReconciliationMismatch`, `CreditNegativeBalanceOrgs`, `CreditJobsWithoutHold`, `CreditUnsettledTerminalJobs` 등).

한계: CONFIRM이 0원이라 원장만으로 "어디서 어디로 갔는지"를 증명할 수 없다. 복식부기·available/held 분리는 roadmap 결정 2~5, step13(미착수)의 계획이다.

---


## 9. 한 줄 요약

**가장 강한 설계 결정 3개**
1. 모든 돈·상태 변경을 **조건부 UPDATE + affected rows 분기**로 통일하고 락·버전 컬럼 없이 경합을 끝냈다. 0행은 전부 방어 이벤트로 세어 지표가 된다.
2. **attemptNo fencing token**으로 좀비 워커의 늦은 confirm·실패 보고를 무효화해, 회수가 틀려도 돈은 안전하다.
3. 외부 호출을 TX 밖에 두고, 회수를 **heartbeat + updatedAt 백스톱의 이중 구조**로 만들어 Redis가 죽어도 돈이 묶이지 않는다.

**가장 약한 부분 3개**
1. **hang 대책 부재.** 외부 호출 타임아웃이 없고, heartbeat가 별도 스레드라 멈춘 워커를 영원히 LIVE로 본다. 돈과 워커 슬롯이 함께 묶인다.
2. **DB 제약이 CAS를 받쳐 주지 않는다.** `(job_id, type)` 유니크·FK가 없고, CONFIRM이 0원인 단식 원장이라 대사도 이중 confirm을 잡지 못한다.
3. **외부 연동이 stub뿐이고 운영 설정이 비어 있다.** HikariCP·backoff·워커 드레인이 없고 실배포 전이라, 장애 대응 수치는 전부 로컬 실측이다.
