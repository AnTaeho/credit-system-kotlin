package com.example.credit_system_kotlin.integration.job.service

import com.example.credit_system_kotlin.global.exception.JobNotFoundException
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.JobQueryService
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class JobQueryServiceTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val jobRepository: JobRepository
) {

    private val jobQueryService = JobQueryService(jobRepository)

    private lateinit var user: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("acme", 1000L))
        other = userRepository.save(User("other", 1000L))
    }

    @Test
    fun `커서로 끝까지 빠짐과 중복 없이 순회한다`() {
        val ids = saveJobs(user, 25)

        val first = jobQueryService.findByUser(user.persistedId, page(null))
        val second = jobQueryService.findByUser(user.persistedId, page(first.nextCursor))
        val third = jobQueryService.findByUser(user.persistedId, page(second.nextCursor))

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
    fun `정확히 size 개만 있으면 nextCursor 는 null 이다`() {
        saveJobs(user, 10)

        val page = jobQueryService.findByUser(user.persistedId, page(null))

        assertThat(page.items).hasSize(10)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `다른 사용자의 job 이 섞여 있어도 내 것만 돌려준다`() {
        val mine = saveJobs(user, 3)
        saveJobs(other, 3)
        mine.add(jobRepository.save(Job.hold(user.persistedId, 100L, "mine-last")).persistedId)

        val page = jobQueryService.findByUser(user.persistedId, page(null))

        assertThat(page.items.map { it.id }).containsExactlyElementsOf(mine.sortedDescending())
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `내 job 은 단건으로 조회된다`() {
        val job = jobRepository.save(Job.hold(user.persistedId, 100L, "a cat"))

        val found = jobQueryService.findOne(user.persistedId, job.persistedId)

        assertThat(found.id).isEqualTo(job.persistedId)
        assertThat(found.status).isEqualTo("HOLDING")
        assertThat(found.prompt).isEqualTo("a cat")
    }

    @Test
    fun `남의 job 은 없는 job 과 같은 예외다`() {
        val job = jobRepository.save(Job.hold(other.persistedId, 100L, "not mine"))

        assertThatThrownBy { jobQueryService.findOne(user.persistedId, job.persistedId) }
            .isInstanceOf(JobNotFoundException::class.java)
            .hasMessage("존재하지 않는 job: ${job.persistedId}")
    }

    @Test
    fun `없는 job 은 예외다`() {
        assertThatThrownBy { jobQueryService.findOne(user.persistedId, 999_999L) }
            .isInstanceOf(JobNotFoundException::class.java)
            .hasMessage("존재하지 않는 job: 999999")
    }

    private fun saveJobs(owner: User, count: Int): MutableList<Long> =
        (1..count).map { jobRepository.save(Job.hold(owner.persistedId, 100L, "prompt-$it")).persistedId }
            .toMutableList()

    private fun page(cursor: Long?): CursorRequest = CursorRequest.of(cursor?.toString(), "10")
}
