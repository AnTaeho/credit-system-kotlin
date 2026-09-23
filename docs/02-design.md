# 02. 설계와 현재 구현의 gap

> 본 설계 문서는 초기 구현 이후 정식화(retrofit)되었다. 즉 이 문서는 구현을 지시한 적이 없고,
> `docs/01-requirements.md`(이하 **요구서**)를 만족하려면 **어떻게 설계했어야 하는가**를
> 뒤늦게 적은 것이다. 이후 단계부터는 설계가 구현을 선행한다.

작성 시점: 2026-09-23. 작성 브랜치: `req-retrofit`(tip `441de28`).

이 문서가 답하는 질문은 하나다 —
**"요구서를 만족하려면 처음부터 어떻게 설계했어야 하고, 현재 구현은 그것과 어디가 다른가."**

## 0. 이 문서의 규율

1. **없는 측정을 지어내지 않는다.** 설계 논증에 쓰는 숫자는 전부 셋 중 하나로 표기한다 —
   **설정값 산술** / **인용한 실측** / **가정**.
2. 설계안은 설계안이라고 쓴다. "이렇게 하면 빨라진다"는 측정 결과가 아니다.
3. 사실 주장에는 인벤토리 절 번호 또는 `파일:메서드` 를 단다. 인벤토리에 없으면 코드를 열어 확인하고
   확인 방법을 적는다.
4. ADR 은 **번호로만** 가리킨다. 내용은 `docs/adr/` 에 있고 여기서 복제하지 않는다.

| 번호 | 제목 |
|---|---|
| ADR-001 | Kafka 도입 |
| ADR-002 | Kafka 제거 |
| ADR-003 | 낙관적 락 재시도 → 조건부 UPDATE |
| ADR-004 | batch-size 20 → 3 |
| ADR-005 | confirm 재시도 제거 |
| ADR-006 | 사용자별 속도 제한 도입 후 제거 |
| ADR-007 | 드레인 보관 후 복원 |

---

## 1. 설계 결정 다섯

### 1-1. 잔액 갱신 전략

**현재.** 조건부 UPDATE 다. `user/repository/UserRepository.kt:deductBalance` 의 JPQL 은

```
UPDATE User u SET u.balance = u.balance - :amount, u.updatedAt = :now
WHERE u.id = :id AND u.balance >= :amount
```

이고, 0 행이면 `HoldService.deductBalance` 가 `InsufficientBalanceException` 을 던진다
(인벤토리 3-1 S1 4번). 이 형태를 택한 경위는 **ADR-003**.

#### 대안 넷을 이 스키마·이 요구서로 비교한다

| 안 | 이 스키마에서의 모양 | 이 요구서에서의 값 | 이 요구서에서의 대가 |
|---|---|---|---|
| (A) 조건부 UPDATE (현행) | `WHERE id=? AND balance >= ?` 한 줄 | 왕복 1회. 잔액 부족이 **락을 잡기 전 조건**으로 표현돼 INV-01(음수 불가)이 DB 한 줄로 강제된다 | 락 구간이 "UPDATE 한 줄"이 아니라 **커밋까지 전부**다 (아래 참조) |
| (B) 비관적 락 `SELECT ... FOR UPDATE` | 없음 — **현재 코드에 한 곳도 없다** | 읽은 값으로 애플리케이션이 판정하므로 복잡한 정책(등급별 한도 등)을 표현할 수 있다 | 왕복 2회. 락 구간이 (A)보다 **길어진다** — SELECT 시점부터 커밋까지다. PERF-05 의 40ms 예산에 불리하다 |
| (C) 낙관적 락 + 재시도 | 현재 코드에 없다. **ADR-003** 이 다루는 대안이다 | 경합이 드물면 락이 없다 | PERF-05 는 **한 행에 초당 25건**을 가정한다. 경합이 드문 전제가 성립하지 않는 분포이고, 재시도가 곧 추가 트랜잭션이라 Hikari 10 을 더 빨리 태운다. 이 요구서에서는 부적합하다 |
| (D) 원장 append + 캐시 잔액 | `docs/roadmap.md` 결정 2(2026-09-10) 방향. **미구현**(인벤토리 3-3) | 대사 검사(INV-02)가 곧 잔액 계산이 된다. 잔액 컬럼은 파생값이 되고 `initialBalance` 가 사라진다 | 잔액 조회가 집계가 된다. 원장 73억 행(요구서 1-2 정정 1) 위에서 사용자별 `SUM` 은 PERF-02(조회 p99 < 30ms)와 정면으로 부딪힌다. 캐시 잔액을 쓰면 다시 (A)의 문제로 돌아온다 |

**(B) 가 현재 코드에 없다는 것은 확인한 사실이다.**
`grep -rn "FOR UPDATE\|LockModeType\|@Lock" src/main` → 일치 0건 (2026-09-23 실행).

**세 전략을 비교하려던 벤치마크가 JAVA 저장소에 남아 있다.**
`src/test/java/com/example/credit_system/benchmark/BalanceStrategyBenchmark.java` 와
`OptimisticLockStrategy.java`(JAVA `HEAD` 기준 `git grep -il optimistic` 로 확인).
설계 문서는 `cee4bf8`(2026-08-02, "docs: add balance strategy benchmark design")이고,
그 커밋 본문이 전제 하나를 적어 두었다 — **"HikariCP 풀 크기를 최대 동시성 이상으로 고정하는 것이
전제 조건이다. 기본값 10에 동시성 100을 걸면 커넥션 대기가 락 대기로 오인된다."**
이 경고는 이 문서 1-2 의 커넥션 예산 산술, 요구서 2-2 의 "HikariCP 커넥션 대기" 관측 항목과
같은 것을 가리킨다. **KT 저장소에는 이 벤치마크가 이식되지 않았고**(`src/test` 아래 없음),
실행 결과도 이 저장소에 없다.

`docs/roadmap.md` 결정 3(available / held 분리)과 결정 5(감시 불변식 세 개)는 (D) 와 짝을 이루는
**다섯 번째 안**이고 역시 미구현이다. 지금 `users` 에는 `balance` 와 `initial_balance` 두 컬럼만 있고
(`V1__baseline.sql`), `held` 는 컬럼이 아니라 **job 상태로부터의 파생값**이다
(`DomainSnapshotTask` 의 미결 금액 게이지, 인벤토리 2-1 스케줄러 표).

#### PERF-05 관점 — 진짜 논점은 "락 구간이 커밋까지"라는 것이다

요구서 PERF-05 가 계산한 핫 행 락 점유 예산은 **40ms/건**이다
(**산술**: 500 RPS × 5% = 25건/s, 1,000ms ÷ 25 = 40ms).

조건부 UPDATE 한 줄은 빠르다. 그러나 **InnoDB 의 행 락은 문장이 끝날 때가 아니라 커밋할 때 풀린다.**
TX-1 은 그 UPDATE 뒤에 아직 세 가지를 더 한다(인벤토리 3-1 S1 5~7번):

```
4. deductBalance          ← 여기서 핫 행에 X 락이 걸린다
5. jobs INSERT
6. idempotency_keys UPDATE (attachJobId)
7. ledger_entries INSERT
   COMMIT                 ← 여기서야 락이 풀린다
```

즉 **핫 계정의 실효 락 점유는 "UPDATE 한 줄"이 아니라 "UPDATE 부터 커밋까지"** 이고,
그 안에 INSERT 2회·UPDATE 1회와 커밋 fsync 가 들어 있다.

**설계안: 잔액 UPDATE 를 트랜잭션의 마지막으로 옮긴다.**

```
3'. idempotency_keys INSERT
5'. jobs INSERT
6'. attachJobId
7'. ledger_entries INSERT
4'. deductBalance          ← 락 구간이 여기서 시작
    COMMIT                 ← 여기서 끝. 사이에 남는 것은 커밋뿐
```

- **얻는 것:** 핫 행 점유가 "UPDATE + 커밋"으로 줄어든다. 40ms 예산에 유리하다.
- **잃는 것:** 잔액 부족 판정이 뒤로 밀린다. 앞의 INSERT 세 개가 전부 **롤백 대상**이 되고,
  잔액 부족이 흔한 상황(요구서 1-5 의 시드 부족, 운영에서는 잔고 소진 직전)에서는
  실패 경로가 지금보다 비싸진다. 지금은 잔액 부족이 **가장 싼 실패**인데 그것이 뒤집힌다.
- **구현상의 함정:** `deductBalance` 는 `@Modifying(flushAutomatically = true)` 다.
  JPA 쓰기 지연 때문에 자동으로 순서가 재배치되지는 않는다 — 순서는 **호출 순서를 바꿔야** 바뀐다.
- **이것은 설계안이지 측정 결과가 아니다.** 어느 쪽이 빠른지는 PERF-05 실측 전에는 모른다.

**락 구간에는 바닥이 있다.** 인벤토리 5절이 확인한 MySQL 설정은
`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`, `log_bin=1` 이다. 커밋 하나가
디스크 fsync 를 동반한다. 따라서 문장 순서를 어떻게 바꿔도 **핫 행 점유의 하한은 커밋 1회의 fsync** 다.
이 하한이 40ms 안에 들어가는지는 미측정이다. (fsync 가 커밋 구간에 들어간다는 것은
InnoDB·binlog 커밋 순서에 대한 **가정**이며, 이 호스트에서 재본 값이 아니다.)

#### INV-02 관점 — (D) 로 가면 대사가 잔액 계산이 된다

현재 공식은 `balance == initialBalance + Σledger` 다
(`ledger/scheduling/LedgerReconciliationTask.kt:isBalanceConsistent`, 인벤토리 3-3).
요구서 합의 3 이 이 현행 공식을 검증 대상으로 고정했고, 공식 교체를 Phase 2 로 넘겼다.

(D) 로 가면 `initialBalance` 가 사라지고 공식이 `잔액 − Σ원장 == 0` 이 된다 —
**대사 검사와 잔액 계산이 같은 식이 된다.** 이것이 결정 2 의 요지다.

그런데 그 방향은 지금 **미구현**이다. `User.initialBalance` 는 살아 있고
(`user/domain/User.kt`, `V1__baseline.sql` 의 `initial_balance BIGINT NOT NULL`),
V1~V5 어디에도 제거 문장이 없다(인벤토리 3-3).
**gap 표 필수 행이다.**

---

### 1-2. 외부 호출과 트랜잭션 경계 — hold / capture / release

이 절이 이 문서의 핵심이다.

#### 먼저 사실 — "처음부터 그렇게 설계했어야 한다"의 답이 "이미 그렇게 되어 있다"인 항목

이 시스템은 이미 **hold → capture(confirm) → release(refund)** 2단계 모델이다.

| 단계 | 이 코드에서의 이름 | 트랜잭션 | 원장 |
|---|---|---|---|
| hold | `HoldService.requestGeneration` | TX-1 | `HOLD`(−cost) |
| capture | `JobLifecycleService.confirm` | TX-2 | `CONFIRM`(0) |
| release | `JobLifecycleService.finalRefund` | TX-5 | `REFUND`(+amount) |

(인벤토리 2-1 트랜잭션 경계, 3-1 S1·S5·S6, `LedgerType` 의 HOLD/CONFIRM/REFUND)

그리고 **외부 호출은 다섯 트랜잭션 어디에도 들어 있지 않다.**
`GenerationJobProcessor.runGeneration` 에 `@Transactional` 이 없고, `generationClient.generate` 는
워커 스레드에서 트랜잭션 밖으로 나간다(인벤토리 2-1 트랜잭션 경계, 3-1 S4).

요구서 PERF-03 이 "구조적으로 통과가 예상된다"고 적은 근거가 이것이다(요구서 3-1).

#### 왜 외부 호출을 트랜잭션 안에 두면 안 되는가 — 커넥션 예산 산술

아래는 전부 **설정값 산술**이며 실측이 아니다. 출발점은 인벤토리 5절의
"HikariCP 설정 없음 → 기본 최대 10" 이다.

```
Hikari 최대 커넥션 = 10

외부 호출을 트랜잭션 안에서 기다린다면, 커넥션 1개가 그 시간만큼 묶인다.

외부 p50 200ms (요구서 1-1 가정)  → 10 ÷ 0.2초 = 초당 50건
외부 p99 2s   (요구서 1-1 가정)  → 10 ÷ 2초   = 초당 5건
현재 스텁 3~7초 (평균 5초, application.yml) → 10 ÷ 5초 = 초당 2건
```

요구서 Tier A 의 접수 목표는 **500 RPS** 다. 자릿수가 다르다 — 가장 낙관적인 p50 200ms 에서도
목표의 10분의 1이다.

**여기에 인증 트랜잭션 몫이 더 붙는다.** 요구서 1-4 가 확정한 대로 접수 1건은
**트랜잭션 2개**를 쓴다(개발 로그인 인증 트랜잭션 + TX-1). 둘은 순차이고, 외부 대기는
TX-1 쪽에만 들어간다. 인증 쪽은 `findByEmail` SELECT 한 번과 커밋이라 커넥션 점유가 밀리초
단위다 — 요청당 커넥션-초는 `0.2초 + ε` 이 되고 **자릿수는 바뀌지 않는다.**
그 `ε` 이 얼마인지는 미측정이다(요구서 부록 B "인증 구간의 지연 기여").

**이것이 PERF-03 이 "통과 예상"인 이유이자, 그 통과가 무엇을 증명하는지다.**
PERF-03 이 통과하면 그것은 "우리가 빠르다"가 아니라
**"접수 경로가 외부 지연과 자원을 공유하지 않는다"** 는 구조의 증명이다.
반대로 PERF-03 이 실패하면 어딘가에서 둘이 자원을 공유하고 있다는 뜻이고,
가장 먼저 볼 곳은 커넥션 풀과 스케줄러 풀이다.

#### 진짜 빈 자리 — capture 전 외부 호출 멱등키

요구서 INV-04("외부 호출이 성공한 job 에 대해 정확히 1회만 과금·1회만 외부 호출")의
현재 상태는 **실패**다. 위반 경로는 둘이다(인벤토리 3-2, 요구서 4-3).

- **C3c** — 호출이 전송된 뒤 응답 전에 죽는다. 부작용이 일어났는지를 **시스템이 알 수 없다.**
  호출이 닿았는지를 기록하는 자리가 코드에 없고 남는 것은 로그뿐이다.
- **C4** — 호출 성공 직후 `confirm` 전에 죽는다. `resultUrl` 은 DB 에 없다.
  heartbeat 만료로 약 10~15초 뒤 FAILED → 재시도 → **외부 호출이 중복 발생**한다.

**설계안 (1) — 외부 요청에 멱등키를 실어 보낸다. 키 후보 둘의 차이가 설계 결정이다.**

| 키 | 재시도 때 무슨 일이 일어나나 | 외부에 요구하는 것 |
|---|---|---|
| `jobId` (단일 키) | 시도 1·2·3 이 **같은 키**를 보낸다. 외부가 중복을 흡수한다 — **이것이 원하는 것이다** | 외부가 **결과를 보관**해야 한다. "앞 시도의 결과를 뒤 시도가 받는다"는 뜻이기 때문이다. 보관 기간이 우리 재시도 창(최악 ≈16.1분, 요구서 4-4)보다 길어야 한다 |
| `jobId:attemptNo` | 시도마다 **다른 키**다. 중복 호출이 그대로 난다 — INV-04 를 닫지 못한다 | 없음. 대신 관측용으로만 쓸모가 있다(어느 시도가 어느 호출인지 로그에서 잇는다) |

즉 INV-04 를 닫는 키는 `jobId` 다. `jobId:attemptNo` 는 **닫지 못한다.**
이 둘을 구분하지 않으면 "멱등키를 붙였다"고 말하면서 아무것도 못 막는다.

**설계안 (2) — reconcile-before-retry.**
재시도를 보내기 전에 "그 키가 이미 처리됐는지"를 외부에 조회하는 단계를 둔다.
이 단계는 C3c 를 닫는 유일한 수단이다 — C3c 는 "호출이 닿았는지를 모른다"는 상태이고,
그것을 아는 방법은 외부에 물어보는 것뿐이다.
우리 쪽에는 이 조회 결과를 기록할 자리도 필요하다(지금은 `jobs` 에도 원장에도 그 칸이 없다).

**검증 불가 gap — 지금은 실을 자리조차 없다. 코드로 확인했다.**

`job/generation/GenerationClient.kt` 는 `fun interface` 이고 메서드가 하나뿐이다:

```kotlin
fun generate(prompt: String): String
```

멱등키를 실을 파라미터가 없다. 그리고 유일한 구현인
`job/generation/stub/GenerationStubClient.kt:generate` 는 프롬프트만 받아
`UUID.randomUUID()` 로 결과 URL 을 만든다 — **키를 기억하는 자리가 없다.**
같은 키로 두 번 불러도 다른 URL 이 나온다.

따라서 이 설계는 **인터페이스 변경 + 스텁 확장 없이는 Phase 3 에서 검증할 수 없다.**
필요한 변경은 최소 셋이다:
(i) `generate(prompt, idempotencyKey)` 로 시그니처 확장,
(ii) 스텁이 키 → 결과를 기억하고 같은 키에 같은 결과를 돌려주기,
(iii) 스텁에 "이 키가 처리됐는지" 조회 경로 추가(reconcile-before-retry 를 재현하려면 필요).
`GenerationClient` 의 KDoc 이 "사용량·원가를 돌려줘야 하는 시점에 이 시그니처를 바꾼다"고
이미 적어 두었으므로, 그 변경과 함께 가는 것이 자연스럽다.

**실제 외부 API 가 멱등키를 지원하는지 — 저장소 안의 주장과 검증 상태가 다르다.**

- `docs/roadmap.md` 결정 12(2026-09-19)는 **"Claude API에는 멱등키가 없다"** 고 단정한다.
  출처는 인용돼 있지 않다.
- 실제 연결은 없다. `GenerationClient` 의 구현은 스텁 하나뿐이고 SDK 의존성도 없다
  (인벤토리 2-1 외부 경계). 따라서 이 단정은 **이 저장소에서 검증된 적이 없다.**
- 즉 정직한 표현은 이렇다 — **"저장소의 결정 로그는 없다고 단정하지만, 확인한 적은 없다."**

**요구서와 결정 12 가 충돌한다 — 이 문서는 고르지 않는다.**

결정 12 는 같은 자리에서 **중복 외부 호출을 운영자 손실로 받아들인다**고 적는다:
"타임아웃이 난 요청도 저쪽에서는 처리·과금됐을 수 있다. 이 원가는 운영자 손실이다."
그런데 요구서 INV-04 는 **"정확히 1회만 외부 호출"** 을 불변식으로 요구한다.

- 결정 12 를 따르면 INV-04 는 **불변식이 아니라 지표**가 되어야 한다
  (요구서 4-3 의 "서비스 손실은 있다"를 손실 지표로 관측하는 쪽).
- INV-04 를 따르면 결정 12 의 "손실을 떠안는다"를 **좁혀야** 한다
  (외부가 멱등키를 지원하는 만큼만 떠안는다).

**충돌은 2026-09-23 에 닫혔다 — 요구서 쪽이 바뀌었다.**
가정하는 외부 API(지연 3~7초)는 **멱등키를 지원하지 않는 것으로 고정**했다
(실제 API 를 붙이지 않으므로 조사 결과가 아니라 가정이다). 그 경계에서는 C3c 가
원리적으로 닫히지 않으므로, 요구서 INV-04 를 둘로 쪼갰다(요구서 4-3) —
**INV-04a**(사용자 과금 정확히 1회)는 불변식으로 남고, **INV-04b**(외부 호출 중복)는
job 당 `max-attempts` 3회를 상한으로 갖는 **손실 지표**가 된다. 결정 12 와 일치한다.

따라서 위의 설계안 (1)·(2)와 `GenerationClient` 시그니처 확장은 **지금 하지 않는다.**
외부가 멱등키를 지원하는 API 로 바뀌면 그때 INV-04b 를 불변식으로 되돌리는 설계로
이 절을 그대로 쓸 수 있도록 남겨 둔다. Phase 3 이 이 절에서 실제로 만드는 것은
**중복 호출을 세는 계측**뿐이다.

---

### 1-3. 멱등성 키 저장소와 TTL

**현재.**

| 항목 | 값 | 근거 |
|---|---|---|
| 테이블 | `idempotency_keys` | `V1__baseline.sql` |
| 유니크 | `uk_idempotency_user_key (user_id, idem_key)` | 인벤토리 3-5 |
| 보조 인덱스 | `idx_idem_created_at (created_at)` | 인벤토리 3-5 |
| 보존 | `app.idempotency.retention-days: 7` | `application.yml` |
| 삭제 | 매일 새벽 2시 cron, `Asia/Seoul` | 인벤토리 2-1 스케줄러 표 |
| 삭제 방식 | `findIdsCreatedBefore(cutoff, 500건)` → `deleteByIdIn` 반복, 배치 실패 시 그 주기 중단 | `job/scheduling/IdempotencyKeyCleanupTask.kt:cleanup`, `CLEANUP_BATCH_SIZE = 500` |

#### 설계 질문은 하나다 — 7일의 근거가 있는가

**있다. 다만 설계 문서가 아니라 커밋 메시지 한 단락이다.**

`git log -S "retention-days" --all` 로 도입 지점을 찾았다. KT 저장소에서는 첫 커밋 `5d39be6`
(2026-08-20, Java → Kotlin 이식본 고정)에 이미 들어 있고, 원본은 JAVA 저장소
**`24bbfd4`(2026-08-19, "feat: expire idempotency keys on a retention window")** 다.
그 커밋 본문이 근거를 적고 있다(원문 발췌·번역):

> 빠르게 지우는 것이 위험한 쪽이다 — 키가 사라지면 같은 `idem_key` 를 든 재시도가
> 새 요청으로 읽히고 조직에 이중 과금된다. 그래서 창을 의도적으로 넉넉하게 잡는다.
> job 은 `max-attempts` 안에 끝나므로 초~분 단위이고, 7일은 어떤 그럴듯한 클라이언트
> 재시도에도 넉넉한 여유를 남긴다.

**이 근거의 성격을 정확히 적는다.**

- **있는 것:** 방향의 논거(짧게 잡는 쪽이 위험하다)와 비교 대상(job 수명은 초~분).
- **없는 것:** 왜 7인가에 대한 **산술**. 3일도 30일도 같은 논거를 만족한다.
  **용량 쪽 계산은 아예 없다** — 이 값이 테이블 크기에 미치는 영향을 따진 흔적이 없다.
- 코드 주석(`IdempotencyKeyCleanupTask`)의 "보존 기간이 7일이라 하루 한 번이면 충분하다"는
  **cron 주기의 근거**이지 7일 자체의 근거가 아니다. `docs/legacy-steps/step6-resilience.md:174` 도 같은 문장을
  옮긴 것이다.

즉 **"근거는 있으나 용량 산술이 아니고, 설계 문서로 남아 있지 않다."**

#### 7천만 행 상주 — 그 규모에서 이 구조가 감당되는지는 미측정이다

요구서 Tier C 의 C-4 가 계산한 값을 인용한다: 일 1천만 접수면
`idempotency_keys` 에 **7천만 행이 상주**한다(**산술** 10,000,000 × 7).

그 규모에서 cron 한 번이 지워야 하는 양을 **산술**로 이어 붙이면:

```
하루 삭제 대상 = 7일 전 하루치 = 10,000,000 행
배치 크기      = 500
필요 배치 수   = 10,000,000 ÷ 500 = 20,000 회
```

한 번의 cron 이 `SELECT ... LIMIT 500` + `DELETE ... WHERE id IN (...)` 를 **2만 번** 반복한다.
`idx_idem_created_at` 만으로 이것이 새벽 시간 안에 끝나는지, 그동안의 삭제 락이
접수 경로의 INSERT 와 얼마나 부딪히는지는 **미측정이다.**
(`IdempotencyKeyCleanupTaskTest` 는 510건 규모로만 돈다 — 인벤토리 4-2.)
참고로 배치 하나가 실패하면 그 주기가 **중단**된다(코드 확인). 2만 배치 중 하나만 실패해도
그날치가 남고 다음 날 두 배가 된다.

#### 대안 셋 — 각각 무엇을 포기하는가

| 대안 | 얻는 것 | 포기하는 것 |
|---|---|---|
| **(a) `created_at` RANGE 파티션 + `DROP PARTITION`** | 삭제가 2만 배치에서 **DDL 한 번**이 된다. 삭제 락 경합이 사라진다 | 유니크 키를 `(user_id, idem_key, created_at)` 으로 바꿔야 한다 — 1-5 와 **같은 함정**이다. `created_at` 이 DATETIME(6) 이라 사실상 행마다 다르므로 `(user_id, idem_key)` 제약이 **무력화**된다. 즉 INV-03 의 2차 방어가 사라진다 |
| **(b) TTL 단축 (예: 7일 → 1일)** | 상주 행이 7천만 → 1천만. 하루 삭제량은 그대로 | `24bbfd4` 가 경고한 바로 그 위험을 키운다. 키가 사라진 뒤의 재시도는 **새 요청으로 읽혀 이중 과금**된다. 안전한 하한은 "클라이언트 재시도 창"인데 그 값이 이 저장소에 정의돼 있지 않다 |
| **(c) Redis 이전** | TTL 이 저장소의 기능이 된다 — cron·배치·삭제 락이 통째로 사라진다. 조회도 빨라진다 | **INV-03 의 강도를 낮춘다** — 아래 |

**(c) 를 고를 때 반드시 적어야 하는 것 — INV-03 의 2차 방어가 사라진다.**

지금 멱등성은 **2중**이다.

1. 1차: 애플리케이션 조회. `HoldService` 가 `findByUserIdAndIdemKey` 로 먼저 본다(인벤토리 3-1 S1 2번).
2. 2차: **DB 유니크 제약** `uk_idempotency_user_key (user_id, idem_key)`.
   1차와 2차 사이의 경합 창에서 두 요청이 동시에 통과해도, INSERT 하나가 제약에 걸려 죽는다.
   같은 구조가 지급 경로에도 있다 — `user/service/UserService.grant` 는
   원장의 `uk_ledger_user_idem (user_id, idem_key)` 를 2차 방어로 쓴다(인벤토리 3-1 지급 흐름).

Redis 로 옮기면 **2차가 없어진다.** Redis `SET NX` 는 단일 키에 대해 원자적이지만,
"키 선점"과 "잔액 차감 + job INSERT + 원장 INSERT"가 **한 트랜잭션이 아니게** 된다.
지금은 TX-1 이 실패하면 멱등키 INSERT 도 함께 롤백되는데(인벤토리 3-2 C1,
`ServiceTransactionRollbackTest` "잔액 부족으로 hold 가 실패하면 선점한 멱등 키도 롤백된다"),
Redis 에 선점한 키는 **롤백되지 않는다.** 보상 삭제를 손으로 써야 하고, 그 보상이 실패하는
경로가 새로 생긴다.

**따라서 (c) 는 "성능 개선"이 아니라 INV-03 의 강도를 내리는 거래다.** 그 거래를 할지는
7천만 행에서 (a)·(b) 가 실제로 부족하다는 측정이 나온 다음에 판단할 일이다.

---

### 1-4. 미결 회수(reaper)

**현재.** `job/scheduling/DeadJobRecoveryTask.kt:scan` 이 5초마다 세 단계를 순서대로 돈다
(인벤토리 3-1 S6). 요구서가 남긴 gap 은 둘이다.

#### gap 1 — INV-05: 목표 T=10분 vs 현재 최악 ≈16.1분

요구서 4-4 가 계산한 최악 경로(hang)는 **950~966초 ≈ 15.8~16.1분**이다. 목표는 10분이다.
설계안은 두 갈래이고 **산술로만** 갈린다.

**(a) `absolute-timeout-seconds` 를 내려 T=10분 안에 넣는다.**

요구서 4-4 의 식에서 `A = app.processing.absolute-timeout-seconds` 를 미지수로 두면:

```
3 × A + base-seconds(10) + base × multiplier(40) + 3 × scan(5) + 2 × dispatch(0.5) ≤ 600

3A + 10 + 40 + 15 + 1 ≤ 600
3A + 66            ≤ 600
3A                 ≤ 534
A                  ≤ 178초
```

즉 **`absolute-timeout-seconds: 300` 을 178 이하로 내려야 T=10분이 성립한다.**
(전부 **설정값 산술**이다.)

두 가지 제약이 더 붙는다.

- **하한이 있다.** `A` 는 `app.processing.timeout-seconds`(60, 정체 스캔 컷오프)보다 **충분히 커야**
  한다. 두 값이 가까워지면 `HARD_CAP` 경로와 `BACKSTOP` 경로가 구분되지 않는다
  (인벤토리 3-1 S6 2번의 `detectorFor` 표: LIVE 는 `pastHardCap` 일 때만 회수된다).
  따라서 실효 구간은 대략 `60 < A ≤ 178` 이다.
- **대가: 정상인데 느린 job 을 더 자주 잘못 회수한다.** `HARD_CAP` 은 "heartbeat 는 살아 있는데
  너무 오래 걸린다"를 죽이는 장치다(인벤토리 3-2 C7). 300 → 178 이면 그 판정이 40% 빨라진다.
  다만 **가정** 위에서 보면 여유는 여전히 크다 — 클라이언트가 소유한 타임아웃은
  요구서 1-1 가정으로 5초, 현재 스텁 설정으로 20초다(`app.generation.timeout-seconds: 20`).
  178초는 그 9~36배다. 정상 호출이 178초를 넘는 일은 "구현이 제 타임아웃을 못 지킨 경우"뿐이고,
  그것이 바로 `HARD_CAP` 이 잡으라고 만든 상황이다(`GenerationClient` KDoc).

**(b) T 를 17분으로 올린다 — 요구서를 고친다.**

현재 설정의 최악이 966초이므로 **T = 17분**(1,020초)이면 여유 54초로 성립한다.
비용은 코드가 아니라 **약속**이다 — "묶인 돈은 17분 안에 풀린다"를 받아들일 수 있는지의 문제다.
요구서 4-4 가 이미 "목표 T=10분과 현재 최악을 맞추는 방법"을 Phase 2 항목으로 남겼고,
이 문서는 **두 갈래를 계산해 놓을 뿐 고르지 않는다.** 고르는 것은 사용자다.

**그리고 (a)·(b) 어느 쪽도 아래 gap 2 를 닫지 못한다.**

#### gap 2 — hang 슬롯이 회수되지 않는다. 회수는 돈만 풀고 스레드는 영영 묶인다

요구서 4-4 와 인벤토리 3-2 C7 이 적은 사실이다. `HARD_CAP` 이 job 을 PROCESSING 에서 풀고
돈을 재시도·환불로 보내지만, **멈춘 워커 스레드는 돌아오지 않는다.**
`app.worker.concurrency: 3` 이므로 hang job 한 건의 세 번째 시도가 마지막 슬롯을 먹고,
그 뒤 `workerSlots.free() <= 0` 이라 디스패처는 조회조차 하지 않는다
(`GenerationWorker.dispatchCycle` — 코드 확인: `if (free <= 0) return`).
그 뒤에 줄 선 job 들의 T 에는 **상한이 없다.**

**설계안: 워커 스레드를 인터럽트한다.**

이 설계가 **이 코드에서 가능한 구조인지를 코드가 답한다.** 세 가지를 확인했다.

1. **인터럽트는 전달된다.** `job/generation/stub/GenerationStubClient.kt:hangForever` 는
   `hangLatch.await()` 를 `try`로 감싸고 `InterruptedException` 을 잡아
   `Thread.currentThread().interrupt()` 로 플래그를 되살린 뒤
   `IllegalStateException("stub hang 중 인터럽트 발생")` 으로 바꿔 던진다.
   같은 처리가 `sleep` 에도 있다. 즉 **스텁을 상대로는 인터럽트가 실제로 스레드를 깨운다.**
   그 예외는 `GenerationJobProcessor.generateOrMarkFailed` 의
   `catch (e: RuntimeException)` 에 잡혀 `markFailed` 로 간다 — 깨끗하게 끝난다.

2. **보너스로 ZSET 고아 멤버도 닫힌다.** `GenerationJobProcessor.runGeneration` 은
   `finally { heartbeatRegistry.stopHeartbeat(...) }` 를 가진다(코드 확인).
   지금 hang 에서는 이 `finally` 에 영영 닿지 못해 갱신 스레드가 5초마다 ZSET 을 다시 쓴다
   (인벤토리 3-2 C7 "회수 뒤에도 남는 것 (2) ZSET 고아 멤버가 5초마다 다시 써진다").
   인터럽트로 스레드를 깨우면 `finally` 가 돌아 **그 고아 멤버도 함께 사라진다.**
   즉 인터럽트 한 수로 C7 의 잔여 두 가지가 모두 닫힌다.

3. **그러나 실제 외부 HTTP 호출이 인터럽트에 반응하는지는 모른다.** 이것은
   클라이언트 구현에 달렸다. 소켓 read 에 묶인 스레드는 인터럽트 플래그만 세워지고
   깨어나지 않는 경우가 흔하다. 현재 구현은 스텁 하나뿐이므로(인벤토리 2-1 외부 경계)
   **이 저장소에는 답이 없다. 모른다고 적는다.** 진짜 클라이언트가 붙을 때
   "인터럽트로 끊기는가, 소켓 타임아웃으로만 끊기는가"를 확인해야 한다.

**구조 변경이 필요하다 — 지금은 인터럽트를 보낼 손잡이가 없다. 코드 확인.**

`GenerationWorker.dispatch` 는 이렇게 되어 있다:

```kotlin
workerExecutor.execute { jobProcessor.runGeneration(job) }
```

`workerExecutor` 는 `@Qualifier("generationWorkerExecutor")` 로 주입된
**`org.springframework.core.task.TaskExecutor`** 이고, `execute` 의 반환 타입은 `void` 다.
즉 **`Future` 를 받지도 않고 버린다.** 인터럽트를 보내려면 `Future.cancel(true)` 가 필요한데
취소할 핸들 자체가 없다.

필요한 변경은 둘이다.

- 주입 타입을 `AsyncTaskExecutor`(또는 `ExecutorService`)로 바꿔 `submit` 의 `Future` 를 받는다.
  실제 빈은 이미 `ThreadPoolTaskExecutor` 다(인벤토리 2-1 워커 실행)이므로 빈 정의는 그대로 둘 수 있다.
- **`(jobId, attemptNo) → Future` 레지스트리**를 둔다. 인터럽트를 보내는 쪽은
  `DeadJobRecoveryTask` 의 스케줄러 스레드이고, `Future` 를 만드는 쪽은 디스패처 스레드다.
  둘이 다른 스레드이므로 동시성 안전한 맵이어야 하고, `runGeneration` 의 `finally` 에서
  자기 항목을 지워야 한다(안 지우면 이번엔 `Future` 가 샌다).

**순서에 함정이 하나 있다.** `generateOrMarkFailed` 는 예외를 잡으면
`markFailed(job.persistedId, job.attemptNo)` 를 **자기가 들고 있던 옛 `attemptNo`** 로 부른다.
회수가 이미 `HARD_CAP` 으로 FAILED 를 찍고 `retry` 로 `attemptNo` 를 올린 뒤라면
이 CAS 는 0 행이 된다(무해하지만 아무 일도 안 한다). 따라서 설계는
**"인터럽트를 먼저 보내고, 그 job 이 스스로 FAILED 로 내려오기를 기다린 뒤 회수 판정"** 이
깨끗하다. 지금 `scan` 의 3단계 순서(인벤토리 3-1 S6)에 인터럽트 단계를 어디에 끼울지가
그 설계의 실제 내용이 된다.

---

#### INV-06 강제 장치 — 실행으로 확인한 것 (2026-09-23)

매뉴얼 문면이 모호했던 부분을 실제로 돌려 확정했다.
환경은 인벤토리 5절과 같다: 이미지 `mysql:8.4`(8.4.11), `log_bin=1`,
`log_bin_trust_function_creators=0`(둘 다 이미지 기본값), 앱 계정 `credit` 에
`GRANT ALL PRIVILEGES ON credit_system.*`(= `docker-compose.yml`·`SharedContainers` 와 같은 계약).

| # | 시도 | 결과 |
|---|---|---|
| 1 | `credit` 계정으로 `CREATE TRIGGER ... BEFORE UPDATE ... SIGNAL` | **실패** — `ERROR 1419 (HY000): You do not have the SUPER privilege and binary logging is enabled` |
| 2 | `GRANT SET_ANY_DEFINER ON *.* TO 'credit'@'%'` 후 재시도 | **실패** — 같은 `ERROR 1419`. MySQL 8.4 의 이 동적 권한으로는 풀리지 않는다 |
| 3 | `SET GLOBAL log_bin_trust_function_creators = 1` 후 재시도 | **성공** — 트리거 2개 생성됨 |
| 4 | 3의 상태에서 삽입만 되는지 검증 | `INSERT` 성공. `UPDATE` 와 `DELETE` 는 각각 **`ERROR 1644 (45000): ledger_entries is append-only`**, 행은 그대로 남았다 |
| 5 | trust 플래그를 0으로 되돌리고 `GRANT SUPER ON *.* TO 'credit'@'%'` 후 재시도 | **성공** — SUPER 로도 생성된다 |

**따라서 트리거 안은 성립한다. 단 마이그레이션 하나로 끝나지 않고 서버 설정 또는 권한이 함께 간다.**
선택지는 셋이고 각각의 대가가 다르다.

| 선택지 | 바꿔야 하는 곳 | 대가 |
|---|---|---|
| (a) `log_bin_trust_function_creators=1` | `docker-compose.yml` 의 MySQL `command`, 운영 DB 설정, `SharedContainers` 의 컨테이너 `command` | 서버 전역 플래그다. 트리거뿐 아니라 **저장 함수 전체**의 바이너리 로그 안전성 검사를 끈다 |
| (b) 앱 계정에 `SUPER` | 계정 권한 | 앱 계정이 서버 전역 권한을 갖는다. (a)보다 넓다 |
| (c) 마이그레이션만 별도 권한 계정으로 | Flyway 자격증명 분리(`spring.flyway.user`) | 계정이 하나 늘고 배포 절차가 복잡해진다. 대신 앱 계정 권한은 그대로다 |

Phase 3 에서 하나를 골라야 한다. **이 문서의 권고는 (a)** 다 — 이 프로젝트에는 저장 함수가
없고(`grep -rn "CREATE FUNCTION" src/main/resources/db/migration` 일치 없음), 바꾸는 곳이
설정 파일 세 군데로 끝나며, 앱 계정 권한을 넓히지 않는다. (c)는 운영 계정이 둘이 되는
대가가 이 규모에서 과하다.

### 1-5. 원장 파티셔닝 시점

**규모.** 요구서 1-2 의 정정값을 쓴다 — **일 2천만 행, 연 73억 행**
(**산술**: job 1천만 × 2행 = 20,000,000/일, × 365 = 7,300,000,000).

#### MySQL 8.4 파티셔닝의 제약 — 이것이 이 테이블의 진짜 비용이다

MySQL 8.4 Reference Manual, "Partitioning Keys, Primary Keys, and Unique Keys"
(`dev.mysql.com/doc/refman/8.4/en/partitioning-limitations-partitioning-keys-unique-keys.html`,
2026-09-23 열람)의 문장을 그대로 옮긴다:

> All columns used in the partitioning expression for a partitioned table must be part of
> every unique key that the table may have. … every unique key on the table must use every
> column in the table's partitioning expression.
>
> (This also includes the table's primary key, since it is by definition a unique key.)

**이 호스트에서 실행해 확인하지는 못했다** — 2026-09-23 기준 Docker 데몬이 떠 있지 않아
`mysql:8.4` 를 띄울 수 없었다(`docker info` → `Cannot connect to the Docker daemon`).
근거는 매뉴얼 원문이고, 실제 DDL 거부는 Phase 3 에서 확인한다.

현재 `ledger_entries` 의 키는 둘이다(인벤토리 3-5, `V1__baseline.sql` + V2 리네임):

```
PRIMARY KEY (id)
UNIQUE KEY uk_ledger_user_idem (user_id, idem_key)
KEY idx_ledger_user_id (user_id)
```

`created_at` 으로 RANGE 파티션하려면 **둘 다** 바꿔야 한다:

```
PRIMARY KEY (id, created_at)
UNIQUE KEY uk_ledger_user_idem (user_id, idem_key, created_at)
```

**후자가 멱등성 제약을 무력화한다.** `created_at` 은 `DATETIME(6)` 이라 마이크로초 단위다 —
사실상 행마다 다르다. 즉 `(user_id, idem_key, created_at)` 유니크는
**같은 `(user_id, idem_key)` 가 서로 다른 시각이면 전부 통과시킨다.** "약화"가 아니라
**그 제약이 없는 것과 같다.**

그 제약이 무엇을 지키고 있었는지는 인벤토리 3-1 지급 흐름에 있다 —
`UserService.grant` 의 멱등성 2차 방어다. 그것이 사라진다.

**`id` RANGE 파티션도 같은 값을 치른다.** `id` 역시 `uk_ledger_user_idem` 안에 없으므로
유니크 키를 `(user_id, idem_key, id)` 로 바꿔야 하고, `id` 는 AUTO_INCREMENT 라 행마다 다르다.
결과는 `created_at` 과 똑같다. **`id` 로 가면 제약을 지킬 수 있다는 것은 착각이다.**

다만 `id` 파티션에는 다른 장점이 있다 — 커서 페이징 쿼리
`WHERE user_id=? AND id<? ORDER BY id DESC`(`LedgerRepository.findByUserIdAndIdLessThanOrderByIdDesc`,
인벤토리 3-5)가 `id` 범위를 조건에 갖고 있어 **파티션 프루닝이 걸린다.** `created_at` 파티션에는
그 조건이 없어 모든 파티션을 훑는다. PERF-04(최근 내역 조회 p99 < 50ms)에는 `id` 쪽이 유리하다.

#### 대안 — 유니크 제약을 지키면서 가는 길

| 대안 | 내용 | 대가 |
|---|---|---|
| **(a) 지급 멱등성을 `idempotency_keys` 로 옮기고 원장 유니크를 버린다** | `uk_ledger_user_idem` 을 없애면 파티션 키 제약이 PK 하나로 줄어든다. 지급 경로도 job 접수와 **같은 구조**가 되어 멱등성 저장소가 한 곳으로 모인다 | `UserService.grant` 트랜잭션에 테이블 쓰기가 하나 는다. 그리고 이 유니크는 지금도 **지급 경로만** 지킨다 — `idem_key` 는 `DEFAULT NULL`(V1)이고, `LedgerEntry.hold`·`confirm`·`refund` 세 팩터리가 전부 `idemKey` 자리에 `null` 을 넣는다(`ledger/domain/LedgerEntry.kt` 확인). 값이 들어가는 것은 `charge`·`adminGrant` 뿐이다. 즉 잃는 것이 생각보다 작다 |
| **(b) 월별 아카이브 테이블로 이동** | 파티셔닝 없이 `ledger_entries_YYYYMM` 로 옮긴다. 스키마를 안 바꾸므로 제약이 그대로 산다 | 이동이 **DDL 이 아니라 DML** 이다 — 6억 행(**산술**: 2천만 × 30)을 옮기고 지우는 배치가 매달 돈다. 조회가 두 테이블에 걸치는 경계 문제가 생기고, 그 경계를 앱이 알아야 한다(INV-06 의 append-only 강제 장치도 테이블마다 붙여야 한다) |
| **(c) 파티션 안 한다** | 인덱스만 손본다(예: `idx_ledger_user_id` 를 `(user_id, id)` 복합으로 — 요구서 Tier C 의 C-2) | 삭제·아카이브 수단이 없으므로 테이블은 계속 자란다. 조회는 인덱스가 받쳐 주지만 백업·DDL·복구 시간은 행 수에 비례해 는다 |

파티션 규모 자체도 **산술**로 남겨 둔다: 월별 RANGE 면 파티션당 약 **6억 행**,
일별이면 **2천만 행 × 연 365 파티션**이다(MySQL 의 테이블당 파티션 상한은 8,192).
스키마에 외래 키가 없다는 점은 유리하다 — `V1__baseline.sql` 전문에 `FOREIGN KEY` 가 한 줄도 없어,
"파티션 테이블은 외래 키를 가질 수 없다"는 제약에는 걸리지 않는다.

#### "언제부터"는 지금 정할 수 없다

요구서 PERF-04 는 **1억 행 상태에서 `EXPLAIN` 으로 실행 계획을 먼저 확인**하라고 못 박았고
(요구서 3절 PERF-04, 6절 정정 13), 그 측정은 아직 없다(인벤토리 4-3).

- 파티셔닝의 동기는 두 가지다 — **조회 지연**과 **삭제·아카이브 수단**.
- 앞의 것은 PERF-04 가 답한다. InnoDB 세컨더리 인덱스는 PK 를 접미로 갖기 때문에
  `idx_ledger_user_id (user_id)` 가 사실상 `(user_id, id)` 처럼 동작할 수도 있다 —
  계획을 보기 전에는 인덱스가 부족한지조차 판정할 수 없다.
- 뒤의 것은 지연과 무관하게 필요할 수 있다. 다만 그때도 (a)~(c) 중 무엇을 고를지는
  위의 유니크 제약 비용을 지불할 것인가의 문제이지 행 수의 문제가 아니다.

**따라서 이 문서는 시점을 정하지 않는다.** 정할 수 있는 것은 순서다 —
**PERF-04 실측 → `EXPLAIN` 계획 → 그때 (a)~(c) 중 선택.**

---

## 2. gap 표

`닫는 단계`는 **Phase 3 / Tier B / Tier C** 중 하나다.

| 요구서 항목 | 있어야 했던 설계 | 현재 구현 | gap | 닫는 Tier | 닫는 단계 |
|---|---|---|---|---|---|
| **INV-04a** (미측정) | 크래시 주입 지점에서도 사용자 잔액이 원상 | 단건 경로는 통과, 크래시 주입 테스트 없음 | fault injection 지점 + 재기동 후 INV-02 세 검사 | A | Phase 3 |
| **INV-04b** (미측정, 지표) | 외부 호출 중복을 세고 상한 안에 있음을 보인다 | 중복 호출을 세는 계측이 없다 | 카운터 하나. **멱등키·시그니처 변경은 하지 않는다**(2026-09-23 확정, 1-2) | A | Phase 3 |
| **INV-05** (실패) | 미결이 T 안에 반드시 풀리고, 멈춘 워커의 **슬롯도** 회수된다 | 최악 ≈16.1분(요구서 4-4). hang 슬롯은 회수되지 않아 `concurrency: 3` 이 다 막히면 뒤에 선 job 의 T 에 **상한이 없다** | (i) T 를 맞추려면 `absolute-timeout-seconds ≤ 178`(1-4 산술) 또는 T=17분으로 요구서 수정 — **선택은 사용자** (ii) 슬롯 회수는 `TaskExecutor.execute` → `submit` + `(jobId, attemptNo) → Future` 레지스트리라는 **구조 변경**이 필요하고, 실제 HTTP 클라이언트가 인터럽트에 반응하는지는 **모른다** | A | Phase 3 |
| **INV-06** (실패) | `ledger_entries` 가 삽입만 되는 테이블 (2026-09-23 사용자 확정, 요구서 합의 5) | 막는 DB 장치가 없다 — 트리거·권한·`CHECK` 어디에도(인벤토리 3-4). 앱 쪽도 강제가 아니라 관행 | 마이그레이션 1개. **수단은 `BEFORE UPDATE`/`BEFORE DELETE` 트리거 + `SIGNAL` 을 권한다** — 앱 DB 계정이 `credit`(`application.yml`, `docker-compose.yml`)이고 Flyway 도 같은 계정으로 도므로, `REVOKE` 는 자기 권한을 스스로 걷는 셈이라 마이그레이션이 실을 수 없고 Testcontainers(`SharedContainers` 도 `credit`)로 증명할 수도 없다. 더구나 **MySQL 권한에는 거부(deny)가 없다** — 권한은 가산되기만 한다. `SharedContainers.createDatabase` 가 `GRANT ALL PRIVILEGES ON $database.* TO 'credit'@'%'` 로 **DB 단위 ALL** 을 주므로, 그 위에 `REVOKE UPDATE ON credit_system.ledger_entries` 를 걸어도 DB 단위 권한이 남아 막히지 않는다. REVOKE 안을 살리려면 DB 단위 GRANT 를 걷고 테이블별로 다시 부여해야 하고, 그것은 Testcontainers 셋업·compose·운영 세 곳의 권한 모델을 함께 바꾸는 일이다. 트리거는 마이그레이션이 싣고 테스트가 실패→통과로 증명할 수 있다. **단 선행 확인이 하나 붙는다** — 이 환경은 `log_bin=1`(인벤토리 5절)이고 `credit` 에는 `SUPER` 가 없다. MySQL 8.4 매뉴얼 "Stored Program Binary Logging"(2026-09-23 열람)은 함수 생성에 `SUPER` 를 요구하고(ERROR 1419), 이어서 "the preceding remarks regarding functions also apply to triggers" 와 "error messages similar to those for stored functions occur with CREATE TRIGGER if you do not have the required privileges" 라고 적는다 — **트리거에도 걸리는지가 문면상 모호하다.** 걸린다면 컨테이너에 `log_bin_trust_function_creators=1` 을 주거나 별도 DBA 계정으로 마이그레이션을 돌려야 한다. → **2026-09-23 실행으로 확인 완료**(같은 파일 1-4 아래 표). 기본 설정에서는 ERROR 1419, 플래그 또는 SUPER 필요(2026-09-23). 이 계정 판단 자체는 설정 파일(`application.yml`, `docker-compose.yml`, `SharedContainers.kt`)로 확인했다 | A | Phase 3 |
| **PERF-07** | 접수율과 워커 처리량의 격차를 **관리**한다 — 적체 상한, 접수 측 백프레셔, 또는 워커 스케일아웃 | 상한이 없다. 접수는 워커 처리량(**설정값 산술** 0.43~1.0건/s)과 무관하게 계속 성공하고 `HOLDING` 이 무한히 쌓인다(요구서 3-2: 10분 부하로 30만 건, 소진 83~194시간) | 격차 자체를 없애는 장치가 없다. **관측 게이지는 이미 있다** — `DomainSnapshotTask` 의 미결 수·미결 금액·가장 오래된 미결 나이(인벤토리 2-1, `DomainSnapshotTaskTest`). 즉 "보이지만 아무것도 하지 않는다"(인벤토리 3-2 C9 와 같은 모양). 게이지를 부하 중 기록하는 일은 Phase 3 의 측정 절차에 속하고, **이 행의 gap 은 장치 쪽이다** | B | Tier B |
| **roadmap 결정 2** (원장이 유일한 진실) | `initialBalance` 제거, 초기 잔액도 원장 행으로. 대사 공식 `잔액 − Σ원장` | **미구현.** `User.initialBalance` 살아 있고(`user/domain/User.kt`, `V1__baseline.sql`), 공식은 `balance == initialBalance + Σledger`(인벤토리 3-3). V1~V5 어디에도 제거 문장 없음 | 요구서 합의 3 이 현행 공식을 검증 대상으로 고정했으므로 Phase 3 을 막지는 않는다. 다만 결정 3(available/held 분리)·결정 5(감시 불변식)도 같은 묶음으로 미구현이라 **원장 재설계 전체가 미착수**다 | B | Tier B (마이그레이션 + 대사 재작성) |
| **PERF-04** | 1억 행에서 최근 내역 조회 p99 < 50ms | `idx_ledger_user_id (user_id)` 단일 컬럼, 쿼리는 `WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?`(인벤토리 3-5) | **인덱스가 부족한지 판정할 수 없다.** InnoDB 세컨더리 인덱스가 PK 를 접미로 가지므로 `EXPLAIN` 실행 계획 전에는 알 수 없다(요구서 3절 PERF-04, 6절 정정 13). 대용량 시드 스크립트도 없다(인벤토리 4-3) | A | Phase 3 (시드 + `EXPLAIN` + 측정) |

---

## 3. Tier 별로 무엇이 바뀌어야 하나

### 3-1. Tier A (로컬 실측, 500 RPS) — Phase 3 에서 닫는 것들

2절 gap 표에서 `닫는 단계 = Phase 3` 인 행이 전부다. 순서를 붙이면:

1. **INV-06 강제 장치** — 마이그레이션 1개 + 테스트. 요구서 4-5 가 정한 순서를 지킨다:
   **테스트를 먼저 써서 실패시키고**, 그다음 장치를 붙여 통과시킨다. 다른 gap 과 독립적이라
   먼저 닫아도 된다.
2. **PERF-04 의 `EXPLAIN`** — 1-5 의 파티셔닝 논의 전체가 이 측정에 걸려 있다.
   시드 스크립트가 먼저 필요하다(요구서 부록 B).
3. **PERF-07 의 관측 기록** — 게이지는 이미 있으므로 새로 만들 것은 없다.
   Phase 3 이 할 일은 부하 중 15초 간격으로 **기록하고**, 부하 제거 후 적체가
   단조 감소하는지를 확인하는 것이다. 격차를 없애는 **장치**는 Tier B 다(gap 표).
4. **INV-05 의 선택** — (a) `absolute-timeout-seconds ≤ 178` 인하냐 (b) T=17분이냐를
   먼저 사용자가 고른다. 슬롯 회수(구조 변경)는 그 뒤다.
5. **INV-04** — 요구서와 결정 12 의 충돌을 먼저 닫아야 시작할 수 있다.
   닫히면 `GenerationClient` 시그니처 확장 + 스텁 확장이 선행 작업이다.

측정 자체의 선행조건은 요구서가 이미 적었다 — 크레딧 시드(1-5),
대사 수동 트리거(4-1), fault injection 플래그(4-6), Hikari 지표 노출(2-2).

### 3-2. Tier B (DAU 100만) — 외삽 근거가 아직 없다

요구서 2-2 를 그대로 인용한다:

> **지금은 외삽할 근거가 없다.** 로컬 1대 실측에서 DAU 100만을 논증하려면 어떤 자원이
> 어디서 먼저 포화하는지를 알아야 하는데, 그 관측이 저장소에 없다.

따라서 이 절은 **설계 관점에서 필요한 변경 후보만** 나열한다. 수치 목표를 세우지 않는다.

**후보 1 — 앱 N대가 되면 스케줄러 5종이 인스턴스마다 돈다.**
인벤토리 2-1 스케줄러 표의 다섯이 전부 단일 프로세스 가정이다. N대에서 각각이 무엇을 하는가:

| 태스크 | N대에서 중복 실행되면 |
|---|---|
| `GenerationWorker.dispatchPendingJobs` | **안전하다.** 선점이 `startProcessingIfAttemptMatches` CAS 라 하나만 이긴다(인벤토리 3-1 S3). 중복은 헛선점 비용뿐 |
| `DeadJobRecoveryTask.scan` | **부분적으로 안전하다.** `failIfProcessing`·`refundIfFailed` 가 CAS 라 이중 환불은 안 난다(인벤토리 3-1 S6). 다만 N배의 스캔 쿼리가 돈다 |
| `LedgerReconciliationTask.reconcile` | **중복이 순수 낭비다.** 60초마다 사용자 전체를 `LEFT JOIN SUM` 으로 훑는 배치가 N번 돈다(인벤토리 3-3). 요구서 Tier C 의 C-3 이 이미 "원장이 73억 행이면 대사 자체가 부하다"라고 적었다 |
| `DomainSnapshotTask.takeSnapshot` | 게이지가 N개 인스턴스에서 각각 나온다. 값은 같지만 집계 쪽에서 구분·중복 처리가 필요하다 |
| `IdempotencyKeyCleanupTask.cleanup` | 같은 행을 N개가 지우려 든다. 삭제 락 경합이 N배 |

**후보 2 — 그런데 ShedLock 은 결정 13 으로 제외돼 있다.**
`docs/roadmap.md` 결정 13("서버는 1대", 2026-09-19)이 "스케줄러 겹침 실측과 ShedLock은 하지 않는다"고
못 박았고, 요구서 5절 비목표에도 같은 줄이 있다.

**즉 서버 1대 결정과 Tier B 가정은 모순이다.** 이것은 결함이 아니라 두 문서가 서로 다른 것을
말하고 있기 때문이다 — 결정 13 은 **실제 운영 목표**를, Tier B 는 **설계 논증용 가상 부하**를 말한다.
요구서 1-1 이 그 구분을 먼저 선언했고("이 표는 설계 훈련용 가상 시나리오다"),
5절 비목표가 "다중 앱 인스턴스 **실측**"만 제외하면서
"단 Tier B 논증에서는 N대 가정을 쓸 수 있다 — 논증과 실측을 구분한다"고 남겨 두었다.

**이 문서가 그 선을 다시 긋는다:** Tier B 의 N대 논의는 **논증**이다.
ShedLock 도입은 이 문서의 결론이 아니라 후보이고, 결정 13 이 살아 있는 한 실행되지 않는다.
결정 13 을 뒤집는 것은 사용자의 일이다.

**후보 3 — 1절의 설계안 중 Tier B 에서 비로소 값이 생기는 것들.**
1-1 의 (D)+결정 2·3·5(원장 재설계), 1-3 의 (a) 파티션 삭제, 1-5 의 (a) 지급 멱등성 이전.
전부 "1대에서는 굳이 할 이유가 없고, N대·대용량에서 값이 생기는" 부류다.

### 3-3. Tier C (DAU 500만+) — 요구서 2-3 을 넘어서지 않는다

요구서 2-3 은 구조적 한계 **후보**를 C-1~C-7 로 사실만 나열하고
"어디서 실제로 깨지는지, 무엇으로 바꿀지는 Phase 2 의 일"이라고 넘겼다.
여기서는 그 일곱 행에 **이 문서 1절의 어느 항목이 대응하는지**만 잇는다. 새 주장을 더하지 않는다.

| 요구서 Tier C | 이 문서의 대응 | 남는 것 |
|---|---|---|
| C-1 연 73억 행, 파티션·아카이빙 없음 | **1-5 전체** | 유니크 제약을 치를 것인가. 시점은 PERF-04 이후 |
| C-2 `idx_ledger_user_id` 단일 컬럼 | 2절 PERF-04 행 | `EXPLAIN` 전에는 판정 불가 |
| C-3 대사 배치가 73억 행을 훑는다 | 1-1 의 (D) / 3-2 후보 1 | 원장 재설계와 같은 묶음 |
| C-4 멱등키 7천만 행 상주 | **1-3 전체** | 7일의 근거는 커밋 메시지 한 단락뿐. (a)/(b)/(c) 각각의 대가는 1-3 에 |
| C-5 앱 1대·DB 1대 전제, 분산 락 없음 | 3-2 후보 1·2 | 결정 13 과 모순. 사용자가 닫을 항목 |
| C-6 워커 처리량 상한 0.43~1.0건/s | 2절 PERF-07 행 | 관측은 있고 장치는 없다 |
| C-7 `jobs.next_attempt_at` 인덱스 없음 | — | `V5__jobs_next_attempt_at.sql` 머리말이 "느려지는 것이 실제로 보일 때 계획을 떠서 붙인다"고 이미 근거를 남겼다. 이 문서가 더할 것이 없다 |

**claim 경합과 Kafka 재도입.** 접수율이 계속 오르면 디스패처의 선점 CAS
(`startProcessingIfAttemptMatches`)가 여러 인스턴스 사이에서 경합한다 —
`ADR-002` 가 남긴 **재도입 트리거 (c)** 가 가리키는 지점이 그것이다.
그 트리거가 실제로 당겨졌는지는 측정이 답하고, 이 문서는 트리거의 존재만 가리킨다.
`ADR-001`·`ADR-002` 가 이 왕복의 기록이다.

---

## 부록. 브리프와 다르게 확인된 것

이 문서를 쓰라고 준 작업 브리프의 전제 중, 코드·저장소를 열어 보니 달랐던 것들이다.

| # | 브리프의 전제 | 실제 확인값 | 확인 방법 |
|---|---|---|---|
| 1 | `retention-days: 7` 의 근거가 없으면 "근거 문서 없음"이라고 써라 | **근거는 있다** — JAVA `24bbfd4`(2026-08-19) 커밋 본문 한 단락. 다만 **용량 산술이 없고 설계 문서로 남지 않았다.** 그래서 "근거 문서 없음"은 사실이 아니다 | `git log -S "retention-days" --all` 양쪽 저장소 |
| 2 | 실제 외부 API 가 멱등키를 지원하는지는 "모른다"고 써라 | 저장소 안에 **단정이 있다** — `docs/roadmap.md` 결정 12 가 "Claude API에는 멱등키가 없다"고 적는다(출처 인용 없음). 실제 연결이 없으므로 **검증된 적은 없다**. 둘 다 적었다 | `docs/roadmap.md` 결정 12 |
| 3 | (브리프에 없음) | **요구서 INV-04 와 결정 12 가 충돌한다.** 결정 12 는 중복 외부 호출 원가를 운영자 손실로 **수용**하고, INV-04 는 "정확히 1회 외부 호출"을 불변식으로 요구한다. 어느 쪽으로도 정리하지 않고 사용자에게 남겼다 | `docs/roadmap.md` 결정 12 vs 요구서 4절 INV-04 |
| 4 | 잔액 갱신 전략의 대안은 셋 (비관적 락 / 낙관적 락 / 원장 append) | **넷이다.** `docs/roadmap.md` 결정 3(available/held 분리)·결정 5(감시 불변식)가 네 번째 안이고 역시 미구현이다 | `docs/roadmap.md` 결정 3·5, `V1__baseline.sql`(컬럼 없음) |
| 5 | 1-5 의 대안으로 "`id` RANGE 파티션"을 들었다 | **`id` 도 같은 값을 치른다.** `id` 역시 `uk_ledger_user_idem` 안에 없어 유니크 키를 `(user_id, idem_key, id)` 로 바꿔야 하고, `id` 는 AUTO_INCREMENT 라 제약이 무력화되는 결과가 `created_at` 과 같다. 다만 커서 페이징에 파티션 프루닝이 걸린다는 별개의 장점이 있다 | `V1__baseline.sql`, `LedgerRepository` |
| 6 | MySQL 파티셔닝 제약을 "사실로 확인하고 적어라" | **실행 확인은 못 했다.** Docker 데몬이 떠 있지 않아(2026-09-23, `docker info` → `Cannot connect to the Docker daemon`) `mysql:8.4` 를 띄울 수 없었다. 매뉴얼 인용으로만 적고 그 사실을 본문에 밝혔다 | `docker info` |
| 7 | (브리프에 없음) | **INV-06 의 강제 수단 선택**(요구서 4-5 가 Phase 2 로 넘긴 항목)이 브리프의 절 배정에 없었다. gap 표 INV-06 행에서 트리거를 권하는 근거와 함께 닫았다 | `application.yml`, `docker-compose.yml`, `SharedContainers.kt` — 앱·Flyway·테스트 모두 `credit` 계정 |
| 8 | `hangForever` 의 인터럽트 처리만 확인하라 | 확인했고(플래그 복원 후 `IllegalStateException`), **추가로** `GenerationJobProcessor.runGeneration` 이 `finally` 에서 `stopHeartbeat` 를 부른다는 것을 확인했다. 즉 인터럽트가 성공하면 인벤토리 3-2 C7 의 잔여 두 가지(슬롯 미반환, ZSET 고아 멤버)가 **함께** 닫힌다 | `GenerationStubClient.kt:hangForever`, `GenerationJobProcessor.kt:runGeneration` |
| 9 | (브리프 초안) 인증 트랜잭션 때문에 커넥션 예산이 **절반**이 된다 | **틀렸다.** 두 트랜잭션은 순차이고 외부 대기는 TX-1 쪽에만 들어간다. 인증 트랜잭션은 SELECT 한 번과 커밋이라 커넥션 점유가 밀리초 단위다 — 요청당 커넥션-초는 `0.2초 + ε` 이고 자릿수는 바뀌지 않는다. 초고에 있던 "절반(25 / 2.5 / 1건/s)" 산술을 철회하고 1-2 를 고쳐 썼다 | 요구서 1-4, `auth/login/UserAccountProvisioner.kt:provisionDevUser` |
| 10 | (브리프에 없음) | **INV-06 트리거의 선행 확인을 실행으로 끝냈다(2026-09-23).** 결과는 본문 1-4 아래 "INV-06 강제 장치 — 실행으로 확인한 것" 참조. 요약: 기본 설정에서는 `ERROR 1419` 로 막히고, `log_bin_trust_function_creators=1` 또는 `SUPER` 가 있어야 생성된다 | 아래 실행 기록 |
| 10b | (옛 기록) | **INV-06 트리거 권고에 선행 확인이 하나 붙는다.** `log_bin=1` 이고 `credit` 에 `SUPER` 가 없다. MySQL 8.4 매뉴얼 "Stored Program Binary Logging" 은 함수에 `SUPER` 를 요구하면서(ERROR 1419) "트리거에도 위 서술이 적용된다"고 적어 **문면이 모호하다.** 걸린다면 `log_bin_trust_function_creators=1` 또는 별도 DBA 계정이 필요하다. Docker 미기동으로 실행 확인 불가 | `dev.mysql.com/doc/refman/8.4/en/stored-programs-logging.html`(2026-09-23 열람), 인벤토리 5절 |
| 11 | (브리프 초안) 낙관적 락은 "과거에 있었고 걷어냈다" | **KT 저장소의 엔티티에 `@Version` 이 쓰인 적이 없다**(`git log -S "@Version" --all` → 일치 0건). JAVA 쪽 일치는 전부 문서 커밋이다. 다만 JAVA `cee4bf8` 본문은 "이 프로젝트가 실제로 가졌던 결함 버전(단일 트랜잭션 내 재시도)"을 언급하고, 세 전략 비교 벤치마크(`BalanceStrategyBenchmark.java`, `OptimisticLockStrategy.java`)가 JAVA 테스트 트리에 남아 있다. ADR-003 의 내용을 베끼지 않기 위해 본문은 **번호로만 가리키고** 논증은 이 요구서(PERF-05)로만 폈다 | `git log -S "@Version" --all` 양쪽, `git grep -il optimistic HEAD -- src`(JAVA) |
