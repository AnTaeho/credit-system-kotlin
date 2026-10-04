package com.example.credit_system_kotlin.ledger.controller

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.TestTokens
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository,
    accessTokenService: AccessTokenService
) {

    private val tokens = TestTokens(accessTokenService)

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var user: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        // 같은 H2 를 쓰는 다른 테스트가 남긴 원장이 이 사용자 id 와 겹치지 않게 비우고 시작한다.
        ledgerRepository.deleteAll()
        user = userRepository.save(User("acme", 1000L, email = "user@test.local"))
        other = userRepository.save(User("other", 1000L, email = "other@test.local"))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `인증되면 ledger 내역을 최신순으로 돌려준다`() {
        ledgerRepository.save(LedgerEntry.hold(user.persistedId, 1L, 100L))
        ledgerRepository.save(LedgerEntry.charge(user.persistedId, "charge-key-1", 500L))

        val page = getPage("/api/ledger")

        assertThat(page.items).hasSize(2)
        assertThat(page.items[0].type).isEqualTo("CHARGE")
        assertThat(page.items[1].type).isEqualTo("HOLD")
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `커서로 끝까지 빠짐과 중복 없이 순회한다`() {
        val ids = saveEntries(user, 25)

        val first = getPage("/api/ledger?size=10")
        val second = getPage("/api/ledger?size=10&cursor=${first.nextCursor}")
        val third = getPage("/api/ledger?size=10&cursor=${second.nextCursor}")

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

        val page = getPage("/api/ledger")

        assertThat(page.items.map { it.id }).containsExactlyElementsOf(mine.sortedDescending())
        assertThat(page.nextCursor).isNull()
    }

    private fun saveEntries(owner: User, count: Int): MutableList<Long> =
        (1..count).map { ledgerRepository.save(LedgerEntry.hold(owner.persistedId, it.toLong(), 100L)).persistedId }
            .toMutableList()

    private fun getPage(path: String): CursorPage<LedgerResponse> {
        val response = restTemplate.exchange(
            url(path), HttpMethod.GET, HttpEntity<Void>(authHeaders()),
            object : ParameterizedTypeReference<CursorPage<LedgerResponse>>() {}
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return requireNotNull(response.body)
    }

    /** [user] 로 로그인한 요청. */
    private fun authHeaders(): HttpHeaders = tokens.bearerHeaders(user)

    private fun url(path: String) = "http://localhost:$port$path"
}
