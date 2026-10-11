package staff.android.orders.transfer.solution

import staff.android.orders.transfer.ReportDraft
import staff.android.orders.transfer.ReportOutcome
import staff.android.orders.transfer.ReportPlan
import staff.android.orders.transfer.ReportRequest
import staff.android.orders.transfer.ReportSubmission

fun planReportSubmission(
    draft: ReportDraft,
    previous: ReportSubmission?,
    freshOperationId: String,
): ReportPlan {
    if (previous != null) {
        require(previous.request.accountId == draft.accountId)
        require(previous.request.reportId == draft.reportId)
    }
    val expectedRevision = when (val outcome = previous?.outcome) {
        null -> null
        ReportOutcome.Unknown -> {
            return ReportPlan.Reconcile(previous.request)
        }
        is ReportOutcome.Accepted -> {
            if (draft.body == previous.request.body) {
                return ReportPlan.AlreadyAccepted(
                    previous.request, outcome.revision,
                )
            }
            outcome.revision
        }
        is ReportOutcome.Rejected -> {
            if (draft.body == previous.request.body) {
                return ReportPlan.ReviewRejection(
                    previous.request, outcome.reason,
                )
            }
            previous.request.expectedRevision
        }
    }
    require(freshOperationId.isNotBlank())
    require(freshOperationId != previous?.request?.operationId)
    return ReportPlan.PersistAndSend(
        ReportRequest(
            draft.accountId, draft.reportId, freshOperationId,
            draft.body, expectedRevision,
        ),
    )
}
