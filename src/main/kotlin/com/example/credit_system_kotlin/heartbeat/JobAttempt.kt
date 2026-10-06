package com.example.credit_system_kotlin.heartbeat

/** job 의 시도 한 번. heartbeat ZSET 에는 "jobId:attemptNo" 문자열로 들어간다. */
data class JobAttempt(val jobId: Long, val attemptNo: Int) {

    /** [parse] 와 짝이다. 형식을 바꾸면 Redis 에 남아 있던 멤버를 못 읽는다. */
    internal fun toMember(): String = "$jobId$SEPARATOR$attemptNo"

    companion object {
        private const val SEPARATOR = ":"

        /** 두 조각이 아니거나 숫자가 아니면 null 이다. 버릴지는 호출자가 정한다. */
        internal fun parse(member: String?): JobAttempt? {
            val parts = member?.split(SEPARATOR) ?: return null
            if (parts.size != 2) {
                return null
            }
            val jobId = parts[0].toLongOrNull() ?: return null
            val attemptNo = parts[1].toIntOrNull() ?: return null
            return JobAttempt(jobId, attemptNo)
        }
    }
}
