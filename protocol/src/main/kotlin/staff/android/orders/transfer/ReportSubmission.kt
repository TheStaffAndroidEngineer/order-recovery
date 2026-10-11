package staff.android.orders.transfer

const val MAX_REPORT_BODY_CHARS = 2_000

data class ReportDraft(
    val accountId: String,
    val reportId: String,
    val body: String,
) {
    init {
        require(accountId.isNotBlank() && reportId.isNotBlank())
        require(body.isNotBlank() && body.length <= MAX_REPORT_BODY_CHARS)
    }
}

data class ReportRequest(
    val accountId: String,
    val reportId: String,
    val operationId: String,
    val body: String,
    val expectedRevision: Long?,
) {
    init {
        require(accountId.isNotBlank() && reportId.isNotBlank())
        require(operationId.isNotBlank())
        require(body.isNotBlank() && body.length <= MAX_REPORT_BODY_CHARS)
        require(expectedRevision == null || expectedRevision > 0)
    }
}

sealed interface ReportOutcome {
    data object Unknown : ReportOutcome

    data class Accepted(val revision: Long) : ReportOutcome {
        init { require(revision > 0) }
    }

    data class Rejected(val reason: String) : ReportOutcome {
        init { require(reason.isNotBlank()) }
    }
}

data class ReportSubmission(
    val request: ReportRequest,
    val outcome: ReportOutcome,
) {
    init {
        if (outcome is ReportOutcome.Accepted) {
            require(outcome.revision > (request.expectedRevision ?: 0))
        }
    }
}

sealed interface ReportPlan {
    data class PersistAndSend(val request: ReportRequest) : ReportPlan
    data class Reconcile(val request: ReportRequest) : ReportPlan
    data class AlreadyAccepted(
        val request: ReportRequest,
        val revision: Long,
    ) : ReportPlan
    data class ReviewRejection(
        val request: ReportRequest,
        val reason: String,
    ) : ReportPlan
}
