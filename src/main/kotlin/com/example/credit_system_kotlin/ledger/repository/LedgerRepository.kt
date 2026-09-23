package com.example.credit_system_kotlin.ledger.repository

import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface LedgerRepository : JpaRepository<LedgerEntry, Long> {

    fun findByUserIdOrderByIdDesc(userId: Long): List<LedgerEntry>

    /** 커서 페이징의 첫 페이지. `Page` 가 아니라 `List` 로 받아 count 쿼리를 피한다. */
    fun findByUserIdOrderByIdDesc(userId: Long, pageable: Pageable): List<LedgerEntry>

    /** 커서 페이징의 다음 페이지. [cursor] 는 배타적 상한 id 다. */
    fun findByUserIdAndIdLessThanOrderByIdDesc(userId: Long, cursor: Long, pageable: Pageable): List<LedgerEntry>

    fun findByUserIdAndIdemKey(userId: Long, idemKey: String): LedgerEntry?

    /**
     * 대사 배치 2단계: 이미 확정된 사용자 id 집합의 원장만 집계한다.
     *
     * 예전에는 이 쿼리가 커서(`o.id > :lastId`) + `LIMIT 100` 을 직접 들고 있었다. 그런데
     * `LIMIT` 은 `GROUP BY` **뒤에** 걸린다 — 100명을 얻으려고 원장 전체를 조인·집계한 뒤
     * 100행만 남겼다. 커서가 줄이는 것은 출력이지 스캔이 아니다.
     *
     * 실측(`docs/SYSTEM.md` 6절 (2)): 원장 1억 행에서 265초가 지나도
     * 끝나지 않았고, 그동안 같은 DB 를 쓰는 조회 API 의 p99 가 10ms → 8,489ms 로 올랐다.
     *
     * 그래서 `UserRepository.findIdsAfter` 로 사용자 100명을 먼저 확정하고, 그 100명의 원장만
     * 여기서 집계한다. 집계 대상이 `idx_ledger_user_id` 로 좁혀진다.
     * 원장이 하나도 없는 사용자도 검사 대상이므로 LEFT JOIN 과 `COALESCE(SUM, 0)` 은 그대로다.
     */
    // 생성자 인스턴스화는 Hibernate 가 FQ 이름을 요구한다. 단순 이름으로 줄이면 부팅 시 SemanticException.
    @Query(
        """
        SELECT new com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck(
            o.id, o.balance, o.initialBalance, COALESCE(SUM(l.amount), 0L))
        FROM User o
        LEFT JOIN LedgerEntry l ON l.userId = o.id
        WHERE o.id IN :ids
        GROUP BY o.id, o.balance, o.initialBalance
        ORDER BY o.id
        """
    )
    fun findBalanceChecksFor(@Param("ids") ids: Collection<Long>): List<LedgerBalanceCheck>
}
