# ADR-003 — 낙관적 락 재시도 루프를 조건부 UPDATE 하나로

- 날짜: 2026-07-05
- 저장소: JAVA (`../credit_system`)
- 커밋: `4632f56` — `fix: replace optimistic-lock retry loops with atomic conditional balance updates`

## 상황

최초 설계 스펙 §6.1 이 hold 를 버전 조건부 UPDATE 로 규정하면서 `→ 반환값 0행이면 최대 3회까지 재조회 후 재시도, 3회 소진 시 ConflictException(409)` 이라고 적었고,
코드가 그대로 구현되어 있었다. 그런데 그 루프는 **한 트랜잭션 안에** 있었다.

커밋 본문이 문제를 정확히 적고 있다.

> Retrying findById + versioned UPDATE inside one @Transactional could never
> recover under REPEATABLE READ: the re-read kept returning the same snapshot,
> so all retries were guaranteed to fail and legitimate requests got rejected
> with 409 under contention. Refund exhaustion even returned normally after
> log.error, leaving the job REFUNDED with the balance never restored.

— 출처: JAVA `git log -1 --format=%B 4632f56`

두 가지다. (1) REPEATABLE READ 아래서 재조회가 같은 스냅샷을 돌려주므로 **재시도는 반드시 전부 실패한다** —
경합 시 정상 요청이 409 로 거절된다. (2) 환불 쪽은 3회를 소진해도 `log.error` 후 **정상 리턴**해서,
job 은 `REFUNDED` 인데 잔액은 복구되지 않는 상태가 남았다.

## 당시 수치

**없음 — 측정하지 않았다.**

커밋 본문에 수치는 하나 등장하지만 측정치가 아니다 — `ConcurrentHoldTest now asserts exactly 5 successes instead of <=5`.
이는 테스트 단언(assertion)을 느슨한 `<=5` 에서 정확한 `5` 로 조인 것이지, 경합률·지연·처리량을 잰 기록이 아니다.
409 가 얼마나 났는지, 경합이 어느 수준이었는지에 대한 측정은 어디에도 없다.

## 결정

버전 비교 + 애플리케이션 재시도를 버리고, **조건 자체를 UPDATE 의 WHERE 절에 넣는다.**

> ```diff
>      @Query("""
>              UPDATE Organization o
>              SET o.balance = o.balance - :amount, o.version = o.version + 1, o.updatedAt = :now
> -            WHERE o.id = :id AND o.version = :expectedVersion
> +            WHERE o.id = :id AND o.balance >= :amount
>              """)
>      int deductBalance(@Param("id") Long id,
>                        @Param("amount") long amount,
> -                      @Param("expectedVersion") long expectedVersion,
>                        @Param("now") Instant now);
>  
>      @Modifying(clearAutomatically = true)
>      @Query("""
>              UPDATE Organization o
>              SET o.balance = o.balance + :amount, o.version = o.version + 1, o.updatedAt = :now
> -            WHERE o.id = :id AND o.version = :expectedVersion
> +            WHERE o.id = :id
>              """)
>      int addBalance(@Param("id") Long id,
>                     @Param("amount") long amount,
> -                   @Param("expectedVersion") long expectedVersion,
>                     @Param("now") Instant now);
>  }
> ```

— 출처: JAVA `git show 4632f56 -- .../organization/repository/OrganizationRepository.java`

`HoldService` 에서는 `MAX_LOCK_RETRIES = 3` 상수와 `for` 루프가 통째로 사라지고 단발 UPDATE 가 됐다.
`BalanceConflictException` 도 함께 삭제됐다(9 files, +43/−89).

원리와 대가를 커밋 본문이 밝힌다.

> Apply the same principle already used for job transitions: one conditional
> UPDATE atomizes check+act. deductBalance guards with balance >= amount,
> addBalance is an unconditional atomic increment, and the row lock serializes
> contention so no retry is needed. BalanceConflictException is gone, refund
> failure now rolls back so the job stays FAILED and is re-picked by the scan,
> and ConcurrentHoldTest now asserts exactly 5 successes instead of <=5.

— 같은 커밋 본문

## 대안

- **JPA `@Version`** — 스펙 §2 가 이미 기각해 둔 상태였다. 원문:

  > 이 낙관적 락은 **JPA `@Version`을 쓰지 않고**, `@Modifying @Query`로 직접 작성한 조건부 UPDATE의 반영 row 수(0/1)로 판정한다. JPA의 `OptimisticLockException` 기반 방식은 예외 처리 흐름이 원안의 "0행이면 재시도/실패 분기" 시맨틱과 다르므로 채택하지 않는다.

  이 커밋은 그 결론을 되돌리지 않고, 반대 방향으로 **수동 버전 비교마저 없앴다.**
- **트랜잭션 밖으로 재시도를 빼는 길**(REPEATABLE READ 스냅샷 문제를 재시도를 살린 채 해결) — 커밋 문서에 검토 흔적 없음.
- **비관적 락(`SELECT … FOR UPDATE`)** — 검토 흔적 없음. 결과적으로 조건부 UPDATE 의 row lock 이 같은 직렬화를 한다.

## 결과

**당시 측정 없음.** 검증은 테스트 단언의 강화(`<=5` → `5`)뿐이다.

WHERE 절은 지금 KT 코드에 **그대로 살아 있다.** 다만 `version` 컬럼 증가는 이식 과정에서 없어졌다 —
Java 판은 조건에서 버전을 빼면서도 `o.version = o.version + 1` 은 계속 올렸는데, Kotlin 판에는 그 항 자체가 없다.

```kotlin
// src/main/kotlin/.../user/repository/UserRepository.kt
        SET u.balance = u.balance - :amount, u.updatedAt = :now
        WHERE u.id = :id AND u.balance >= :amount
```

요구서와의 연결은 둘이다.

- **INV-01**("`users.balance` 가 음수가 되지 않는다") — 이 불변식을 지키는 장치가 바로 이 조건부 UPDATE 한 줄이다.
  요구서는 INV-01 을 단위·통합 수준 **통과**로 두되, 각주에서 "**부하 중** 음수 잔액이 나지 않는지는 미측정"이라고 못 박는다.
- **PERF-05**(핫 계정 편중, 500 RPS × 5% = 초당 25건이 한 행) — 이 항목이 재려는 대상이 바로 이 결정의 대가다.
  커밋 본문의 "the row lock serializes contention so no retry is needed" 는 곧
  **경합이 row lock 대기로 바뀐다**는 뜻이고, 요구서는 여기에 핫 행 락 점유 예산 **40ms/건**(1,000ms ÷ 25)을 건다.
  현재 상태는 **미측정**(인벤토리 4-3, 핫 계정 시나리오 없음)이다.

PERF-05 를 재기 전까지, 이 결정이 경합 아래서 어떻게 움직이는지는 **모른다.**
