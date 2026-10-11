package staff.android.orders.transfer.exercise

import staff.android.orders.transfer.ReportDraft
import staff.android.orders.transfer.ReportPlan
import staff.android.orders.transfer.ReportRequest
import staff.android.orders.transfer.ReportSubmission

// Deliberately incomplete: every tap starts another initial submission.
fun planReportSubmission(
    draft: ReportDraft,
    previous: ReportSubmission?,
    freshOperationId: String,
): ReportPlan = ReportPlan.PersistAndSend(
    ReportRequest(
        draft.accountId, draft.reportId, freshOperationId,
        draft.body, expectedRevision = null,
    ),
)
