package com.example.credit_system_kotlin.heartbeat

data class JobAttempt(val jobId: Long, val attemptNo: Int) {

    internal fun toMember(): String = "$jobId$SEPARATOR$attemptNo"

    companion object {
        private const val SEPARATOR = ":"

        internal fun parse(member: String?): JobAttempt? {
            if (member == null) {
                return null
            }
            val separatorIndex = member.indexOf(SEPARATOR)
            if (separatorIndex < 0) {
                return null
            }
            val jobId = member.take(separatorIndex).toLongOrNull() ?: return null
            val attemptNo = member.substring(separatorIndex + 1).toIntOrNull() ?: return null
            return JobAttempt(jobId, attemptNo)
        }
    }
}
