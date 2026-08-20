package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.job.domain.IdempotencyKey
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.organization.domain.Organization

/**
 * 엔티티의 `id` 는 persist 전에는 없으므로 `Long?` 이다.
 * 테스트는 save 직후의 엔티티만 다루므로 id가 반드시 존재하지만,
 * 타입은 그것을 모른다. 호출부마다 `!!` 를 뿌리는 대신 여기서 한 번 확정한다.
 *
 * report.md B-1을 (b)/(c)로 정해 프로덕션 엔티티가 non-null id를 노출하게 되면
 * 이 파일은 삭제하면 되고, 호출부는 이름이 같으므로 그대로 둔다.
 */
val Organization.persistedId: Long get() = requireNotNull(id) { "저장되지 않은 Organization" }

val Job.persistedId: Long get() = requireNotNull(id) { "저장되지 않은 Job" }

val IdempotencyKey.persistedId: Long get() = requireNotNull(id) { "저장되지 않은 IdempotencyKey" }

val LedgerEntry.persistedId: Long get() = requireNotNull(id) { "저장되지 않은 LedgerEntry" }
