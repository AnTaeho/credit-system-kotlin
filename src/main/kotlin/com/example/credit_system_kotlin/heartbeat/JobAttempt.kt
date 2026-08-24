package com.example.credit_system_kotlin.heartbeat

data class JobAttempt(val jobId: Long, val attemptNo: Int) {

    internal fun toMember(): String = "$jobId$SEPARATOR$attemptNo"

    companion object {
        private const val SEPARATOR = ":"

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
