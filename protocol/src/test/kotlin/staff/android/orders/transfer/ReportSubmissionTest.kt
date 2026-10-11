package staff.android.orders.transfer

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

abstract class ReportSubmissionContract {
    abstract fun plan(
        draft: ReportDraft,
        previous: ReportSubmission?,
        freshOperationId: String,
    ): ReportPlan

    @ParameterizedTest(name = "{0}")
    @MethodSource("histories")
    fun plansTheExactBusinessOperation(
        label: String,
        draft: ReportDraft,
        previous: ReportSubmission?,
        freshOperationId: String,
        expected: ReportPlan,
    ) {
        val actual = assertDoesNotThrow<ReportPlan> {
            plan(draft, previous, freshOperationId)
        }
        assertEquals(expected, actual, label)
    }

    @Test fun rejectsAnUnrelatedAccountOrReportBeforeAnyOutcome() {
        val request = request()
        for (outcome in listOf(
            ReportOutcome.Unknown,
            ReportOutcome.Accepted(8),
            ReportOutcome.Rejected("Missing measurement"),
        )) {
            val previous = ReportSubmission(request, outcome)
            for (draft in listOf(
                ReportDraft("account-b", "north-gate", "Corrected"),
                ReportDraft("account-a", "south-gate", "Corrected"),
            )) {
                assertThrows(IllegalArgumentException::class.java) {
                    plan(draft, previous, "operation-2")
                }
            }
        }
    }

    @Test fun aNewRequestCannotReuseTheImmediatelyPreviousIdentity() {
        val previous = ReportSubmission(
            request(), ReportOutcome.Accepted(8),
        )
        assertThrows(IllegalArgumentException::class.java) {
            plan(draft("Corrected"), previous, "operation-1")
        }
    }

    @Test fun aNewRequestRequiresAnIdentityButDoesNotTrimItsBody() {
        assertThrows(IllegalArgumentException::class.java) {
            plan(draft("Valid body"), null, " ")
        }
        val body = "x".repeat(MAX_REPORT_BODY_CHARS)
        assertEquals(
            ReportPlan.PersistAndSend(request(body, "operation-2")),
            plan(draft(body), null, "operation-2"),
        )
    }

    @Test fun malformedModelsAndImpossibleAcceptedRevisionsFailEarly() {
        val invalid: List<() -> Unit> = listOf(
            { ReportDraft("", "north-gate", "Body") },
            { ReportDraft("account-a", " ", "Body") },
            { draft(" \n") },
            { draft("x".repeat(MAX_REPORT_BODY_CHARS + 1)) },
            { request(operationId = "") },
            { request(expectedRevision = 0) },
            { ReportOutcome.Accepted(0) },
            { ReportOutcome.Rejected(" ") },
            {
                ReportSubmission(
                    request(expectedRevision = 8),
                    ReportOutcome.Accepted(8),
                )
            },
        )
        for (makeInvalid in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                makeInvalid()
            }
        }
    }

    companion object {
        private fun draft(body: String = "Gate checked") = ReportDraft(
            "account-a", "north-gate", body,
        )

        private fun request(
            body: String = "Gate checked",
            operationId: String = "operation-1",
            expectedRevision: Long? = null,
        ) = ReportRequest(
            "account-a", "north-gate", operationId,
            body, expectedRevision,
        )

        @JvmStatic fun histories(): List<Arguments> {
            val original = request()
            val correction = request(expectedRevision = 7)
            val reason = "Missing measurement"
            return listOf(
                Arguments.of(
                    "first submission keeps exact Unicode and whitespace",
                    draft("  Vérifier la serrure\n"), null, "operation-2",
                    ReportPlan.PersistAndSend(
                        request("  Vérifier la serrure\n", "operation-2"),
                    ),
                ),
                Arguments.of(
                    "unknown unchanged submission is reconciled",
                    draft(), ReportSubmission(original, ReportOutcome.Unknown),
                    "operation-2", ReportPlan.Reconcile(original),
                ),
                Arguments.of(
                    "unknown corrected draft cannot replace original request",
                    draft("Corrected"),
                    ReportSubmission(original, ReportOutcome.Unknown),
                    "operation-2", ReportPlan.Reconcile(original),
                ),
                Arguments.of(
                    "reconciliation needs no unused new identity",
                    draft("Corrected"),
                    ReportSubmission(original, ReportOutcome.Unknown),
                    "", ReportPlan.Reconcile(original),
                ),
                Arguments.of(
                    "confirmed unchanged submission displays existing receipt",
                    draft(),
                    ReportSubmission(original, ReportOutcome.Accepted(8)),
                    "operation-2", ReportPlan.AlreadyAccepted(original, 8),
                ),
                Arguments.of(
                    "confirmed changed draft becomes a linked correction",
                    draft("Corrected"),
                    ReportSubmission(original, ReportOutcome.Accepted(8)),
                    "operation-2", ReportPlan.PersistAndSend(
                        request("Corrected", "operation-2", 8),
                    ),
                ),
                Arguments.of(
                    "accepted correction retains its own receipt",
                    draft(),
                    ReportSubmission(correction, ReportOutcome.Accepted(8)),
                    "operation-2", ReportPlan.AlreadyAccepted(correction, 8),
                ),
                Arguments.of(
                    "next correction uses accepted revision not old base",
                    draft("Second correction"),
                    ReportSubmission(correction, ReportOutcome.Accepted(8)),
                    "operation-2", ReportPlan.PersistAndSend(
                        request("Second correction", "operation-2", 8),
                    ),
                ),
                Arguments.of(
                    "rejected unchanged initial submission stays for review",
                    draft(),
                    ReportSubmission(original, ReportOutcome.Rejected(reason)),
                    "operation-2", ReportPlan.ReviewRejection(original, reason),
                ),
                Arguments.of(
                    "corrected rejected initial submission has a new identity",
                    draft("Measurement 7"),
                    ReportSubmission(original, ReportOutcome.Rejected(reason)),
                    "operation-2", ReportPlan.PersistAndSend(
                        request("Measurement 7", "operation-2"),
                    ),
                ),
                Arguments.of(
                    "rejected unchanged correction stays for review",
                    draft(),
                    ReportSubmission(correction, ReportOutcome.Rejected(reason)),
                    "operation-2", ReportPlan.ReviewRejection(correction, reason),
                ),
                Arguments.of(
                    "corrected rejected correction retains its reviewed base",
                    draft("Measurement 7"),
                    ReportSubmission(correction, ReportOutcome.Rejected(reason)),
                    "operation-2", ReportPlan.PersistAndSend(
                        request("Measurement 7", "operation-2", 7),
                    ),
                ),
                Arguments.of(
                    "whitespace change is a distinct exact payload",
                    draft("Gate checked "),
                    ReportSubmission(original, ReportOutcome.Accepted(8)),
                    "operation-2", ReportPlan.PersistAndSend(
                        request("Gate checked ", "operation-2", 8),
                    ),
                ),
                Arguments.of(
                    "unknown correction preserves its original reviewed base",
                    draft("Second correction"),
                    ReportSubmission(correction, ReportOutcome.Unknown),
                    "operation-2", ReportPlan.Reconcile(correction),
                ),
            )
        }
    }
}

class ReportSubmissionReferenceTest : ReportSubmissionContract() {
    override fun plan(
        draft: ReportDraft,
        previous: ReportSubmission?,
        freshOperationId: String,
    ): ReportPlan = staff.android.orders.transfer.solution.planReportSubmission(
        draft, previous, freshOperationId,
    )
}

@Tag("exercise")
class ReportSubmissionExerciseTest : ReportSubmissionContract() {
    override fun plan(
        draft: ReportDraft,
        previous: ReportSubmission?,
        freshOperationId: String,
    ): ReportPlan = staff.android.orders.transfer.exercise.planReportSubmission(
        draft, previous, freshOperationId,
    )
}
