# 03. 배포 전 체크리스트

배포 직전에 위에서 아래로 한 번 훑는 목록이다. 각 줄은 **체크박스 + 근거 경로**로 되어 있고,
근거 없는 줄은 쓰지 않았다. 체크된 줄은 전부 그 파일을 열어 확인한 것이다.

## 이 문서를 읽는 법

**경로 규약.** 요구 지시문은 불변식 테스트 자리를 `test/invariants/` 라고 적었지만, 이 프로젝트는
Gradle 표준 배치를 쓰므로 실제 경로는 `src/test/kotlin/com/example/credit_system_kotlin/invariants/`
다. 아래에서 `invariants/` 라고 줄여 쓰면 그 디렉터리를 가리킨다.

**빈 체크박스는 두 종류다. 섞어 읽으면 안 된다.**

| 표기 | 뜻 |
|---|---|
| **미실행** | 지금 저장소에서 확인할 수 있는데 아무도 하지 않았다. 사람이 할 일이 남아 있다 |
| **배포 시 확인** | 여기서는 확인할 수 없다. 운영 환경에 붙어야 판정된다 |

**체크된 줄도 "목표 달성"을 뜻하지 않는다.** 체크는 "그 항목을 확인했고 상태를 안다"는 뜻이다.
목표를 못 지킨 항목(PERF-01·02 등)은 체크하되 실측값과 실패 사실을 같은 줄에 적었다.

**근거로 쓴 측정.** 2026-09-23 하루의 다섯 런이다. 환경 고정값은
`load/results/2026-09-23-tier-a.md` 의 "측정 환경" 표가 기준이고 나머지 두 문서가 같은 값을 쓴다.

---

## 0. 배포 차단 항목 — 여기서 걸리면 배포가 멈춘다

- [ ] **운영 DB 서버에 `log_bin_trust_function_creators=1` 이 켜져 있다** — **배포 시 확인**.
  V6 마이그레이션이 `ledger_entries` 에 `BEFORE UPDATE`/`BEFORE DELETE` 트리거를 만든다
  (`src/main/resources/db/migration/V6__ledger_entries_append_only.sql`).
  이 플래그가 없고 앱 계정에 `SUPER` 도 없으면 **`CREATE TRIGGER` 가 `ERROR 1419` 로 실패하고
  Flyway 가 멈춰 배포 자체가 진행되지 않는다.** 실행으로 확인된 사실이다 —
  `docs/02-design.md` 1-4 "INV-06 강제 장치 — 실행으로 확인한 것 (2026-09-23)" 의 5행 표.
- [x] 로컬·측정 환경에는 이 플래그가 켜져 있다 — 세 군데를 다 열어 확인했다:
  `docker-compose.yml:31`, `load/docker-compose.load.yml` 의 mysql `command`,
  `src/test/kotlin/com/example/credit_system_kotlin/job/concurrency/SharedContainers.kt:51`.
- [ ] 플래그를 못 켜는 운영 DB 라면 대안을 골랐다 — **배포 시 확인**. 선택지 셋과 각각의 대가는
  `docs/02-design.md` 1-4 의 (a)/(b)/(c) 표. 이 문서의 권고는 (a) 서버 플래그다.
- [ ] **예상 피크 접수율이 150 RPS 아래다** — **배포 시 확인**. 실측 상한이 150 RPS 이고
  그 위에서는 꼬리가 먼저 무너진다(2절). 근거는
  `load/results/2026-09-23-scenarios-02-03-04-and-capacity.md` 1절 용량 계단 표.

---

## 1. 불변식 (INV-01 ~ INV-08)

테스트는 `invariants/` 에 **파일 10개 + 측정 도구 1개**(`PostLoadConsistencyCheck`, `@Tag("post-load")`
라서 `./gradlew test` 에서 제외되고 `./gradlew postLoadCheck` 로만 돈다 — `build.gradle.kts:128-146`),
`@Test` 는 **테스트 17건 + 측정 도구 4건 = 21건**이다.

### INV-01 — 잔액은 음수가 되지 않는다

- [x] 자동화 테스트가 있다 — `invariants/BalanceNeverNegativeTest.kt` (잔액 100 · cost 1 ·
  **동시 1,000 요청**, 성공 정확히 100건 · 잔액 0).
- [x] 부하 중에도 0 이었다 — **세 런(01·03·04) 접수 약 61만 건** 뒤 음수 잔액 0
  (`load/results/2026-09-23-scenarios-02-03-04-and-capacity.md` 5절).
  02 핫 계정 런은 `postLoadCheck` 를 돌리지 않아 같은 표에서 네 칸이 모두 `—` 다.
  (그 문서의 초판 합계 "724,000+"는 02 를 포함해 근거보다 넓었고, 2026-09-23 에
  **625,173건**(01·03·04 + INV-03 런)으로 정정됐다.)
- [x] 게이지로 감시된다 — `credit_invariant_negative_balance_orgs`, 알람
  `CreditNegativeBalanceOrgs`(P1, `for: 0m`), `deploy/observability/prometheus/rules/credit.rules.yml`.

### INV-02 — 원장 합계와 잔액이 일치한다 (세 검사)

- [x] 세 검사가 **불일치를 실제로 잡는지** 역방향으로 증명돼 있다 —
  `invariants/ReconciliationDetectsMismatchTest.kt` (3건, 일부러 깨뜨리고 각 검사가 1 이상을 내는지).
  0건은 검사가 눈이 멀었을 때도 나오는 값이라 이 테스트가 없으면 "mismatch 0" 이 무의미하다.
- [x] 부하 직후 실제 DB 에 대고 돌릴 수단이 있다 — `invariants/PostLoadConsistencyCheck.kt`,
  `./gradlew postLoadCheck` (`build.gradle.kts:136`).
- [x] 약 61만 건의 차감·환불 뒤 세 검사 전부 0 이었다(01·03·04. 02 는 미실행) —
  `load/results/2026-09-23-scenarios-02-03-04-and-capacity.md` 5절,
  `load/results/2026-09-23-tier-a.md` "부하 직후 정합성".
- [x] 셋 다 P1 알람이 걸려 있다 — `CreditLedgerReconciliationMismatch`, `CreditJobsWithoutHold`,
  `CreditUnsettledTerminalJobs` (`credit.rules.yml`).

### INV-03 — 같은 `(userId, idemKey)` 는 차감 정확히 1회

- [x] 단위·통합 테스트가 있다 — `invariants/IdempotencyUnderConcurrencyTest.kt`
  (100 병렬, 응답을 네 갈래로 분류해 합계까지 단언).
- [x] **부하 중 검증이 끝났다** — `load/results/2026-09-23-knob-and-inv03.md` 3절.
  `connection-timeout` 을 250ms 로 낮춰 5xx 41,795건·같은 idemKey 재시도 10,767건을 만들었고,
  `jobs` = `idempotency_keys` = `HOLD` 원장 = **15,456행으로 정확히 일치**, 중복 차감 0.

  > 각주. `docs/01-requirements.md` 4절의 각주 [^3] 는 아직 "부하 중 대량 중복 키 상황에서의
  > 검증은 미측정"이라고 적고, `load/results/2026-09-23-scenarios-02-03-04-and-capacity.md`
  > 4절·6절·7절도 "네 런 모두 미검증"이라고 적는다. **둘 다 낡았다** — 그 뒤에 돈
  > knob-and-inv03 런이 검증했다. 이 체크리스트는 실측 기준으로 적는다.

### INV-04a — 사용자에게서 나가는 돈은 정확히 1회

- [x] C3c·C4 크래시 지점 주입 테스트가 있다 — `invariants/CrashPointInjectionTest.kt` (2건).
- [x] 한계가 문서화돼 있다 — 트랜잭션·스레드 수준 재현이지 **프로세스 사망이 아니다**.
  실제 `kill -9` 증거는 `deploy/observability/scenarios/01-worker-crash.sh` (같은 파일 KDoc).
- [x] 환불 경로가 부하에서 실제로 탔다 — 시나리오 03(실패율 0.3)에서 재시도·환불 경로가 돌았고
  대사는 0이었다 (`...scenarios-02-03-04-and-capacity.md` 3절·5절).

### INV-04b — 중복 외부 호출은 상한 있는 지표다

- [x] 세는 장치가 있다 — `observability/ExternalCallMetrics.kt`
  (`credit.generation.external.calls`, `credit.generation.external.duplicate.calls`).
- [x] 상한 테스트가 있다 — `invariants/ExternalCallBudgetTest.kt`
  (job 당 호출 3 = `max-attempts`, 중복 2, 종결 뒤 증가 없음).
- [x] 실측값이 있다 — 외부 호출 328건 중 중복 98건(30%), **job 당 상한 초과 0**
  (`...scenarios-02-03-04-and-capacity.md` 3절).

  > 각주. `docs/01-requirements.md` 4절 INV-04b 행은 "관측 수단 없음(2026-09-23 확정)"으로
  > 남아 있다. 수단은 만들어졌고 값도 나왔다 — 요구서 쪽이 낡았다.

### INV-05 — 미결로 묶인 돈은 T=17분 안에 풀린다

- [x] 정상 경로의 상한이 산술대로임을 재는 테스트가 있다 —
  `invariants/OutstandingMoneyBoundTest.kt` (축소 설정으로 식이 맞는지 검증).
- [x] **상한이 없는 경로가 테스트로 고정돼 있다** — `invariants/HangSlotStarvationTest.kt`.
  hang 은 워커 슬롯을 돌려주지 않아 `credit.worker.slots.free` 가 0 이 되면 디스패처가 조회조차
  하지 않고, 뒤에 줄 선 job 의 T 에는 상한이 없다. 이 테스트는 **깨진 것을 그대로 단언한다.**
- [ ] INV-05 를 통과로 바꿨다 — **미실행**. 닫으려면 멈춘 워커 스레드를 인터럽트로 회수하는
  구조 변경(`TaskExecutor.execute` → `submit` + `(jobId, attemptNo) → Future` 레지스트리)이
  필요하고, 실제 HTTP 클라이언트가 인터럽트에 반응하는지도 모른다 —
  `docs/02-design.md` 2절 gap 표 INV-05 행. **현재 상태는 실패다.**
- [x] 슬롯 고갈을 사람에게 알리는 알람이 있다 — `CreditWorkerSlotsExhausted`
  (`credit_worker_slots_free == 0`, `for: 5m`) 와 `CreditHardCapRecovery` (`credit.rules.yml`).

### INV-06 — `ledger_entries` 는 삽입만 된다

- [x] DB 장치가 있다 — `V6__ledger_entries_append_only.sql` 의 트리거 2개 + `SIGNAL '45000'`.
- [x] 증명 테스트가 있다 — `invariants/LedgerAppendOnlyTest.kt` (4건, 실제 MySQL Testcontainers
  위에서만 성립. H2 는 Hibernate 가 스키마를 만들어 마이그레이션을 거치지 않는다).
- [x] 앱 코드가 이 제약을 우회하지 않는다 — 대사 테스트조차 `deleteAll()` 정리를 못 써서
  기준선 증감 방식으로 바꿨다(`ReconciliationDetectsMismatchTest` KDoc). 장치가 실제로 문다.
- [ ] 운영 DB 에서 트리거 생성이 성공했다 — **배포 시 확인**. 0절 참조.

  > 각주. `docs/01-requirements.md` 4절 INV-06 행은 "실패 / 테스트 없음", `docs/02-design.md`
  > 2절 gap 표 INV-06 행은 "Docker 미기동으로 실행 확인 불가"로 남아 있다. 같은 설계 문서의
  > 1-4 절이 실행으로 확인했고 V6 와 테스트가 들어왔다 — 요구서와 gap 표 쪽이 낡았다.

### INV-07 — 어느 지점에서 죽어도 재기동 후 세 검사 0건

- [x] C1~C11 을 **어디서 덮는지의 표**가 코드로 있다 — `invariants/CrashPointCoverageTest.kt`
  KDoc + 2건.
- [ ] INV-07 전체가 통과다 — **미실행**. C2·C3a·C3b 는 프로세스 kill / 스레드 경계 주입이
  필요해 in-JVM 으로 **재현 불가**다(같은 파일 KDoc). 집계 규칙(실패 > 미측정 > 통과)에 따라
  INV-07 의 현재 상태는 **미측정**이다 — `docs/01-requirements.md` 4-6.
- [x] 프로세스 사망 판 증거가 별도로 있다 — `deploy/observability/scenarios/01-worker-crash.sh`
  외 8개 장애 주입 스크립트.

### INV-08 — 외부 완전 장애 10분, Σ환불 = Σhold

- [x] 축소판 테스트가 있다 — `invariants/TotalOutageRefundTest.kt` (N=20).
- [ ] 요구서가 말한 규모(10분 장애, N ≤ 120)로 쟀다 — **미실행**. 전체 `./gradlew test` 예산
  안에 들어가려면 장애 구간을 초 단위로 줄여야 하고, 그러면 N 도 함께 줄여야 결과가 나온다
  (같은 파일 KDoc, `docs/01-requirements.md` 4-7).

---

## 2. 성능 (PERF-01 ~ PERF-07)

### 운영 상한

- [x] **실측 상한은 접수 150 RPS 다** — 50/100/150 통과, 200 에서 p99 가 46배 튀고(19 → 876ms)
  250 에서 p50 도 무너진다(373ms). `...scenarios-02-03-04-and-capacity.md` 1절.
- [x] 포화가 **꼬리 → 중앙값 → 붕괴** 순서로 온다는 것이 실측돼 있다 — 200 RPS 에서
  p50 은 3.45ms 로 멀쩡한데 p99 는 이미 876ms 다(같은 절). 경보 지표 선택의 근거다(5절).
- [x] 이 상한을 DAU 로 환산해 뒀다 — 접수 기준 약 162,000 / **처리 기준 약 51,800**.
  실질 용량은 작은 쪽이고 구속하는 것은 접수가 아니라 워커 처리량이다(같은 절).

### 항목별

- [x] **PERF-01** 접수 p50 < 20ms / p99 < 100ms — **500 RPS 에서 실패**
  (p50 5,368ms · p99 11,728ms, `load/results/2026-09-23-tier-a.md`). **150 RPS 에서는 통과**
  (p50 3.35ms · p99 19.08ms). 두 사실을 같이 읽어야 한다.
- [x] **PERF-02** 조회 p99 < 30ms — **500 RPS 에서 실패**(11,718ms), 150 RPS 에서 통과(10.29ms).
  같은 두 파일.
- [x] **PERF-03** 외부 지연 무관성 — **부분 통과**. 외부를 60배(50~100ms → 3~7초) 느리게 해도
  접수 p50 은 +8%, 그러나 p99 는 +70%. 원인은 외부 호출이 아니라 **외부 실패가 만드는 DB 작업**
  (`markFailed`/`retry`/`finalRefund`)이 접수와 같은 커넥션 풀 10개를 쓰는 것이다
  (`...scenarios-02-03-04-and-capacity.md` 3절).
- [ ] **PERF-04** 원장 1억 행에서 최근 내역 조회 p99 < 50ms — **미실행**. 시드 스크립트는
  `load/seed/seed-ledger.sh` 에 있다. 절차는 대용량 시드 → `EXPLAIN` 으로 실행 계획 확인 →
  지연 측정 순이다(`docs/01-requirements.md` 3절 PERF-04). 계획을 보기 전에는 인덱스가
  부족한지 판정할 수 없다 — InnoDB 세컨더리 인덱스가 PK 를 접미로 갖기 때문이다.
- [x] **PERF-05** 핫 계정 악화 3배 미만 — **통과**. 핫 ÷ 균등 p99 가 포화에서 0.99배,
  비포화(100 RPS)에서 0.92배. **두 부하에서 각각 재서** 포화의 큐잉에 가려진 것이 아님을
  확인했다(같은 문서 2절).
- [x] **PERF-06** 3,000 RPS 생존 — **부분 통과**. 불변식 위반 0 · 5xx 0, 판정 구간 p50 6.39ms
  회복, 그러나 p99 239.68ms 로 목표(100ms) 초과(같은 문서 4절).
- [x] **PERF-07** 적체가 깨지지 않고 쌓인다 — **통과**. 부하 후 단조 감소 5.85건/초, 게이지로
  관측됨(`load/results/2026-09-23-tier-a.md`). 152,291건 소진에 약 7.1시간 추정이며
  **완전 소진은 미측정**이다.
- [x] 워커 처리량의 구속항이 무엇인지 안다 — 폴링 주기다.
  `min(concurrency/job소요, concurrency/폴링주기) = min(30~60, 6.0) = 6.0건/초`, 실측 5.85.
  스텁을 20~140배 빠르게 해도 6/초에서 막힌다(같은 파일, ADR-004 공식 재현).
- [x] "설정 두 줄로 처리량을 올린다"가 부하 중에는 듣지 않는다는 것이 실측돼 있다 —
  폴링 50ms · concurrency 50 으로 바꿔도 **슬롯 50개 중 43~49개가 내내 비었고**, 접수는 오히려
  21% 느려졌다(257 → 203 RPS). 디스패처가 커넥션을 못 잡기 때문이다.
  **순서는 ① 커넥션 풀·DB 용량 → ② 워커 노브**다 (`load/results/2026-09-23-knob-and-inv03.md` 1절).

### 병목 판정 — 배포 후 튜닝 순서의 근거

- [x] 5.4초의 정체가 **커넥션 대기가 아니다**는 것이 실측으로 정정됐다 —
  `hikaricp_connections_acquire_seconds` 평균 **104ms**, timeout 0. 클라이언트가 본 지연의 2%다.
  실제 경로는 `DB 트랜잭션 10.4ms → 커넥션 10개로 초당 약 960 트랜잭션 → 톰캣 200 스레드가
  그 처리율에 묶임 → 나머지는 accept 큐 대기`다 (`load/results/2026-09-23-knob-and-inv03.md` 2절).
  따라서 **풀만 10 → 30 으로 늘려도 DB 처리율이 그대로면 지연도 그대로다.**

  > 각주. `load/results/2026-09-23-tier-a.md` 의 "무엇이 먼저 포화됐나" 절은 아직
  > "대기열 190 → 요청당 19 순번 → p50 5.4초"라는 취소된 인과를 담고 있다. 그 문서는 정정되지
  > 않았다. 이 체크리스트는 knob 문서의 정정판을 쓴다.

---

## 3. DB · 스키마

- [x] `ledger_entries` 가 삽입만 되는 테이블이다 — V6 트리거 2개 + `LedgerAppendOnlyTest`. 1절 참조.
- [ ] 그 전제인 `log_bin_trust_function_creators=1` 이 운영 DB 에도 켜져 있다 — **배포 시 확인**.
  **0절의 배포 차단 항목이다.** 없으면 마이그레이션이 `ERROR 1419` 로 실패해 배포가 멈춘다.
- [x] 마이그레이션이 V1~V6 이고 Hibernate 는 검증만 한다 —
  `src/main/resources/db/migration/`, `spring.jpa.hibernate.ddl-auto: validate`
  (`application.yml`, `application-prod.yml` 에서 다시 명시).
- [x] 인덱스 현황을 안다 —
  `jobs (user_id, id)` 복합(V4), `jobs (status, id)`(V1), `ledger_entries` 는
  `uk_ledger_user_idem (user_id, idem_key)` 유니크 + **`idx_ledger_user_id (user_id)` 단일 컬럼**,
  `idempotency_keys` 는 `uk_idempotency_user_key` + `idx_idem_created_at`.
- [ ] 원장 커서 페이징에 인덱스가 충분한지 판정했다 — **미실행**. 쿼리는
  `WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?` 인데 인덱스는 단일 컬럼이다. 같은 모양의
  `jobs` 쿼리에는 V4 가 복합 인덱스를 붙였다. **PERF-04 의 `EXPLAIN` 없이는 부족한지 알 수 없다**
  (`docs/02-design.md` 2절 gap 표 PERF-04 행).
- [ ] `jobs.next_attempt_at` 에 인덱스가 있다 — **미실행**(없다). 디스패처 조회의
  `IS NULL OR <=` 조건은 행을 읽어야 판정된다 — `docs/01-requirements.md` 2-3 C-7,
  `V5__jobs_next_attempt_at.sql` 머리말.
- [x] 멱등키 보존이 7일이고 정리 배치가 있다 — `app.idempotency.retention-days: 7`,
  `app.scheduling.idempotency-cleanup-cron: "0 0 2 * * *"` (Asia/Seoul),
  `job/scheduling/IdempotencyKeyCleanupTask.kt`.
- [ ] 7일 보존이 운영 규모에서 감당되는지 쟀다 — **미실행**. 일 1천만 접수면
  `idempotency_keys` 에 7천만 행이 상주한다(`docs/01-requirements.md` 2-3 C-4,
  `docs/02-design.md` 1-3 "7천만 행 상주 — 그 규모에서 이 구조가 감당되는지는 미측정이다").
- [x] 원장 파티셔닝·아카이빙 장치가 **없다**는 것을 안다 — `docs/01-requirements.md` 2-3 C-1,
  `docs/02-design.md` 1-5. 연 73억 행 산술이 근거다.

---

## 4. 운영 장치

### 있는 것

- [x] **미결 회수(reaper)** 주기와 값 — `job/scheduling/DeadJobRecoveryTask.kt:34`,
  `app.scheduling.dead-job-scan-interval-millis` **기본 5,000ms**(어노테이션 기본값.
  `application.yml` 에는 없다). 판정 기준은 `application.yml` 의
  `app.heartbeat.timeout-seconds: 10` / `refresh-interval-seconds: 5`,
  `app.processing.timeout-seconds: 60`, `absolute-timeout-seconds: 300`.
- [x] 절대 상한이 **돈만 풀고 슬롯은 못 푼다**는 한계를 안다 — `credit.rules.yml` 의
  `CreditHardCapRecovery` 주석. 슬롯은 재기동으로만 돌아온다.
- [x] **대사 배치** 주기 — `app.scheduling.reconciliation-interval-millis: 60000`,
  `ledger/scheduling/LedgerReconciliationTask.kt:24`.
- [x] **대사 수동 트리거 수단이 있다** — `./gradlew postLoadCheck` (`build.gradle.kts:136`,
  `invariants/PostLoadConsistencyCheck.kt`). 부하 직후 그 DB 에 대고 INV-01·INV-02 를 즉시 돌린다.
  워커·스케줄러를 끄고 돌아 대상 DB 를 스스로 바꾸지 않는다.
- [x] **도메인 스냅샷** 주기 — `app.scheduling.snapshot-interval-millis: 15000`,
  `global/scheduling/DomainSnapshotTask.kt:36`.
- [x] **워커 디스패치** 주기 — `app.scheduling.worker-interval-millis` **기본 500ms**
  (`job/worker/GenerationWorker.kt:53`, 어노테이션 기본값), `batch-size: 3`, `concurrency: 3`.
- [x] **드레인·graceful shutdown 이 있다** — `server.shutdown: graceful`,
  `spring.lifecycle.timeout-per-shutdown-phase: ${app.processing.timeout-seconds:60}s`
  (`application.yml`), 워커 드레인은 `job/worker/GenerationWorkerLifecycle.kt:66`
  (`WorkerDrainGate` 로 디스패치를 닫고 진행 중 job 을 같은 상한까지 기다린다).
  HTTP 유입이 끊긴 뒤에 워커 드레인이 시작되도록 phase 가 나뉘어 있다. 배경은 ADR-007.
- [x] 재시도 정책을 안다 — `app.generation.max-attempts: 3`, backoff `base 10s × multiplier 4`
  (실제로 쓰이는 값은 10초·40초 두 번), `timeout-seconds: 20`.

### 없는 것 — 이것도 배포 전에 알고 있어야 한다

- [x] **백프레셔가 없다.** 3,000 RPS 를 요구해도 거절하지 않고 전부 200 으로 받고 5~11초
  기다리게 한다. 서버 지표로는 성공률 100%이고 사용자는 끊긴다
  (`...scenarios-02-03-04-and-capacity.md` 4절). 적체 상한도 접수 측 제동도 없다 —
  `docs/02-design.md` 2절 gap 표 PERF-07 행("보이지만 아무것도 하지 않는다", 닫는 Tier B).
- [x] **사용자별 속도 제한이 없다.** 2026-09-19 도입 → 2026-09-20 제거, 하루 만이다.
  제거 판단에 수치는 없었고 비교된 것은 유지 비용의 항목 수다 —
  `docs/adr/ADR-006-per-user-rate-limit-added-and-removed.md`.
- [x] **일일 원가 상한이 없다.** ADR-006 이 인용한 `docs/step9-auth.md` 는 "진짜 벽은 step12 의
  일일 원가 상한"이라고 적는데 그 장치가 코드에 없다 —
  `grep -rin "원가|daily|budget|cost-limit|spend" src/main/kotlin` 가 맞히는 것은 주석 3줄뿐이고
  (`ExternalCallMetrics.kt:37`, `GenerationJobProcessor.kt:54`, `GenerationClient.kt:15`)
  전부 다른 맥락이다. 근거: `docs/adr/ADR-006-per-user-rate-limit-added-and-removed.md`.
- [x] 분산 락이 없다 — 스케줄러 5종이 전부 단일 프로세스 가정이다
  (`docs/01-requirements.md` 2-3 C-5). 앱을 2대 이상 띄우면 중복 실행된다.

---

## 5. 관측과 알람

알람 규칙 파일은 `deploy/observability/prometheus/rules/credit.rules.yml` 이고,
스크레이프 설정은 `deploy/observability/prometheus/prometheus.yml`
(job `credit_system`, 대상 `app:8081`, 5초 간격)다. 대시보드는
`deploy/observability/grafana/dashboards/credit-domain.json`.

### 계기판이 살아 있는지부터 본다

- [x] **`credit_snapshot_staleness_seconds > 45` 알람이 있다** — `CreditSnapshotStale`(P2,
  `for: 1m`). 15초 주기의 3배에서 유도한 값이고, **`< 0` 을 `or` 로 함께 건다** — 초기값 `-1`
  ("아직 한 번도 안 찍힘")에서는 `> 45` 가 영원히 거짓이라 가장 나쁜 경우에 침묵한다.
- [x] **이게 울리면 다른 게이지는 전부 무효라는 것이 실측으로 확인됐다** — Tier A 에서
  staleness 407초일 때 게이지는 18,890, DB 직접 카운트는 140,424였다. **7.4배 과소 보고**다.
  원인은 구조다 — `DomainSnapshotTask` 가 접수와 같은 Hikari 풀 10개를 쓴다
  (`load/results/2026-09-23-tier-a.md` "관측이 먼저 죽는다").
- [x] 대사 쪽에도 같은 모양의 알람이 있다 — `CreditReconciliationStale`
  (`> 180` or `< 0`, 60초 주기의 3배).
- [x] 스크레이프 자체가 끊긴 것을 잡는 알람이 있다 — `CreditSystemDown`(`up == 0`, `for: 30s`).

### P1 — 불변식. 유예 없음

- [x] 네 개 다 `for: 0m` 으로 걸려 있다 — `CreditLedgerReconciliationMismatch`,
  `CreditNegativeBalanceOrgs`, `CreditJobsWithoutHold`, `CreditUnsettledTerminalJobs`.
  등식이지 SLO 가 아니므로 "1건 정도는 괜찮다"가 성립하지 않는다.

### 배포 전에 **추가해야** 하는 알람

- [ ] **`hikaricp_connections_pending > 20`** — **미실행**. `credit.rules.yml` 에 이 규칙이
  없다(`grep hikari deploy/` 무결과). 붕괴 쪽 값만 있다 — 500 RPS 에서 pending 이 첫 샘플부터
  190~195 로 고정이었다(`load/results/2026-09-23-tier-a.md` 포화 표). **150 RPS 통과 구간의
  pending 값은 결과 파일에 없다** — 용량 계단 표에 Hikari 열이 없다. 알람을 걸 때 그 구간에서
  한 번 읽어 20 이 맞는 선인지 확인해야 한다.
- [ ] **접수 p99 > 100ms** — **미실행**. 규칙 없음. PERF-01 의 목표치 그대로이고,
  150 RPS 에서 19ms · 200 RPS 에서 876ms 이므로 이 선은 **150~200 RPS 사이의 무릎을 짚는다**
  (`...scenarios-02-03-04-and-capacity.md` 1절). 지표는 `http_server_requests_seconds`
  (Spring MVC 기본, `deploy/observability/scenarios/*.sh` 가 `http_server_requests_seconds_count`
  를 실제로 쿼리하므로 노출은 확인됨. 단 **분위수 히스토그램이 켜져 있는지는 미확인** —
  `application.yml` 에 `management.metrics.distribution.percentiles-histogram` 설정이 없다).

### 경보에 쓰면 안 되는 지표 — 실측으로 확정된 것

- [x] **p50 과 평균을 쓰지 않는다.** 200 RPS 에서 p50 은 3.45ms 로 멀쩡한데 p99 는 이미 876ms 다.
  p50 이 무너지는 것은 250 RPS 부터다 — 그때는 이미 붕괴다
  (`...scenarios-02-03-04-and-capacity.md` 1절 "무릎의 모양").
- [x] **성공률을 건강 지표로 쓰지 않는다.** 3,000 RPS 스파이크에서 5xx 0건·전송오류 0건이었고
  사용자는 6초를 기다렸다. 백프레셔가 없어서 전부 받기 때문이다(같은 문서 4절).
- [x] 현재 규칙 파일에 p50·평균·성공률 기반 알람이 하나도 없다 — `credit.rules.yml` 전문 확인.

### 실측으로 이름이 확인된 메트릭

- [x] 결과 파일에 **verbatim 으로 나온 것**: `credit_hold_outstanding_count`,
  `credit_snapshot_staleness_seconds`, `credit_generation_external_calls_total`,
  `credit_generation_external_duplicate_calls_total`,
  `hikaricp_connections_active` / `_pending` / `_acquire_seconds_{sum,max}` / `_timeout_total`.
  Hikari 지표는 요구서 2-2 가 "노출 미확인"으로 남긴 항목인데 **노출이 확인됐다**
  (`load/results/2026-09-23-knob-and-inv03.md` 2절).
- [x] 알람 규칙이 이름으로 쓰고 있는 것: `credit_ledger_reconciliation_mismatch`,
  `credit_invariant_negative_balance_orgs`, `credit_invariant_jobs_without_hold`,
  `credit_invariant_unsettled_terminal_jobs`, `credit_job_oldest_pending_age_seconds`,
  `credit_ledger_reconciliation_staleness_seconds`, `credit_job_recovery_total{detector=...}`,
  `credit_defense_total{point=...,outcome=...}`, `credit_worker_slots_free`
  (소스는 `observability/DomainSnapshotMetrics.kt`, `DefenseMetrics.kt`,
  `WorkerSlotMetrics.kt`, `LedgerReconciliationMetrics.kt`).
- [ ] 이 규칙 파일이 **운영 Prometheus 에 실제로 로드된다** — **배포 시 확인**.
  지금의 `deploy/observability/` 는 step7 의 관측 실습용 compose 스택이고, Alertmanager 도
  붙어 있지 않다(`credit.rules.yml` 머리말: "라우팅(Slack/PagerDuty)은 이번 범위 밖").
  **규칙이 fire 돼도 아무에게도 안 간다.**

---

## 6. Tier C 진입 신호 — 언제 이 구조를 버려야 하는가

`docs/01-requirements.md` 2-3 의 한계 후보를 **감지 가능한 형태**로 바꾼 것이다.
게이지가 없는 항목은 SQL 한 줄로 적었다. 없는 것을 있다고 적지 않았다.

| # | 한계 | 감지 수단 | 임계 |
|---|---|---|---|
| C-1 | 원장이 한 테이블에 쌓인다(연 73억 행 산술) | 게이지 없음. `SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_NAME='ledger_entries'` | 1억 행 — PERF-04 의 측정 지점이자 파티셔닝 검토선 |
| C-2 | 원장 커서 페이징 인덱스가 단일 컬럼 | `EXPLAIN` 의 `Extra` 에 `Using filesort` 출현 | 출현하면 즉시 |
| C-3 | 대사 자체가 부하가 된다 | `credit_ledger_reconciliation_duration_seconds` **(유도 — 소스는 `LedgerReconciliationMetrics.kt` 의 `credit.ledger.reconciliation.duration` 이고 규칙 파일에도 결과 파일에도 없다)**, 그리고 `CreditReconciliationStale` 알람 | 1주기가 60초 주기에 근접 = staleness 알람이 먼저 운다 |
| C-4 | 멱등키 7천만 행 상주 | 게이지 없음. `SELECT COUNT(*) FROM idempotency_keys` 또는 같은 `TABLE_ROWS` | 정리 배치가 하루치를 못 지우기 시작하는 지점 |
| C-5 | 앱 1대 전제, 분산 락 없음 | 감지 수단 없음 — 2대를 띄우는 순간 스케줄러가 중복 실행된다 | 인스턴스 수 > 1 이면 즉시 |
| C-6 | 워커 처리량 상한(실측 5.85건/초) | `credit_hold_outstanding_count` 의 **기울기**. 부하 후 단조 감소하지 않으면 접수율이 처리량을 넘긴 것 | 24시간 이동 평균이 감소하지 않으면 |
| C-7 | `jobs.next_attempt_at` 인덱스 없음 | 디스패처 조회의 `EXPLAIN` — 인덱스 없이 행을 읽는다 | `jobs` 행 수 증가에 디스패치 지연이 비례하기 시작하면 |
| — | 접수 피크 RPS | `rate(http_server_requests_seconds_count{uri="/api/jobs"}[1m])` | **150 RPS**(실측 상한) |
| — | 선점 경합 손실 | `credit_defense_total{point="worker_claim",outcome="lost"}` / `{outcome="applied"}` | 낮아야 한다. 오르면 디스패처 겹침을 의심한다. **0 이 정상값은 아니다** — 회수가 `attemptNo` 를 올린 낡은 세대의 CAS 도 같은 `LOST` 로 세어진다(`GenerationWorker.kt:87`, `DefenseTriggered.kt` 의 `LOST` 주석) |

- [x] 위 표의 메트릭 이름을 전부 소스 또는 규칙 파일에서 확인했다.
- [ ] C-1·C-4 를 게이지로 승격했다 — **미실행**. 지금은 사람이 SQL 을 쳐야 안다.
- [ ] 접수 피크 RPS 알람을 걸었다 — **미실행**. 5절의 "접수 p99" 항목과 같은 자리다.

---

## 7. 이 체크리스트가 덮지 못하는 것

정직하게 적는다. 아래는 배포 전에 이 문서로 확인할 수 없는 것들이다.

- **다중 인스턴스.** 모든 측정과 모든 장치가 앱 1대 전제다. 스케줄러 5종에 분산 락이 없어
  2대를 띄우면 대사·스냅샷·회수·정리·디스패치가 전부 중복 실행된다
  (`docs/01-requirements.md` 2-3 C-5).
- **실제 외부 API.** 잰 것은 전부 `GenerationStubClient` 다. 실패율·지연 분포·타임아웃 거동이
  실제 API 와 같다는 보장이 없다. 특히 INV-04b 의 전제 — "외부 API 에 멱등키가 없다" — 는
  **가정**이다(`docs/01-requirements.md` 4-3).
- **운영 DB 설정 검증.** `log_bin_trust_function_creators`, `max_connections`,
  `innodb_buffer_pool_size` 는 전부 측정용 compose 의 값이다. 요구서 PERF-06 은
  `max_connections=151` 을 전제로 썼는데 측정 오버레이는 200 을 썼다 —
  둘 다 운영 값이 아니다.
- **PERF-04.** 원장 1억 행 조회는 재지 않았다. 인덱스가 부족한지조차 모른다(3절).
- **운영 인증 경로.** 부하는 전부 `X-Dev-User` 개발 로그인 헤더로 쳤다
  (`docs/01-requirements.md` 1-4). 운영은 구글 OAuth 세션 쿠키이고, **그 경로의 요청당 비용은
  측정되지 않았다.** 150 RPS 상한은 개발 로그인 경로에서 나온 값이다.
- **CPU 를 나눠 쓰는 1대에서의 값.** 앱 JVM · MySQL · k6 가 같은 Apple M4 한 대를 나눠 썼고
  상한의 합이 호스트를 넘는다(`load/README.md` 0-B, `load/docker-compose.load.yml` 머리말).
  기계가 나뉘면 숫자가 달라진다.
- **완전 소진.** 152,291건 적체의 소진은 7.1시간 **추정**이고 실제로 기다리지 않았다.
- **알람의 도달.** Alertmanager 가 없다. 규칙이 fire 되는 것까지가 현재 범위다(5절 마지막 항목).
- **INV-05 의 hang 경로, INV-07 의 C2·C3a·C3b, INV-08 의 10분 규모.** 1절의 빈 칸들이다.
