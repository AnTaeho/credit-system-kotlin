package com.example.credit_system_kotlin.integration.ledger.service

import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.ledger.service.LedgerQueryService
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class LedgerQueryServiceTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val ledgerQueryService = LedgerQueryService(ledgerRepository)

    private lateinit var user: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("acme", 1000L))
        other = userRepository.save(User("other", 1000L))
    }

    @Test
    fun `ledger 내역을 최신순으로 돌려준다`() {
        ledgerRepository.save(LedgerEntry.hold(user.persistedId, 1L, 100L))
        ledgerRepository.save(LedgerEntry.charge(user.persistedId, "charge-key-1", 500L))

        val page = ledgerQueryService.findByUser(user.persistedId, page(null))

        assertThat(page.items).hasSize(2)
        assertThat(page.items[0].type).isEqualTo("CHARGE")
        assertThat(page.items[1].type).isEqualTo("HOLD")
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `커서로 끝까지 빠짐과 중복 없이 순회한다`() {
        val ids = saveEntries(user, 25)

        val first = ledgerQueryService.findByUser(user.persistedId, page(null))
        val second = ledgerQueryService.findByUser(user.persistedId, page(first.nextCursor))
        val third = ledgerQueryService.findByUser(user.persistedId, page(second.nextCursor))

        assertThat(first.items).hasSize(10)
        assertThat(second.items).hasSize(10)
        assertThat(third.items).hasSize(5)
        assertThat(first.nextCursor).isEqualTo(first.items.last().id)
        assertThat(second.nextCursor).isEqualTo(second.items.last().id)
        assertThat(third.nextCursor).isNull()
        val visited = (first.items + second.items + third.items).map { it.id }
        assertThat(visited).containsExactlyElementsOf(ids.sortedDescending())
    }

    @Test
    fun `다른 사용자의 원장이 섞여 있어도 내 것만 돌려준다`() {
        val mine = saveEntries(user, 3)
        saveEntries(other, 3)
        mine.add(ledgerRepository.save(LedgerEntry.hold(user.persistedId, 999L, 100L)).persistedId)

        val page = ledgerQueryService.findByUser(user.persistedId, page(null))

        assertThat(page.items.map { it.id }).containsExactlyElementsOf(mine.sortedDescending())
        assertThat(page.nextCursor).isNull()
    }

    private fun saveEntries(owner: User, count: Int): MutableList<Long> =
        (1..count).map { ledgerRepository.save(LedgerEntry.hold(owner.persistedId, it.toLong(), 100L)).persistedId }
            .toMutableList()

    private fun page(cursor: Long?): CursorRequest = CursorRequest.of(cursor?.toString(), "10")
}
