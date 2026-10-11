package staff.android.orders

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StaleSession : Exception("Session is no longer current")

enum class AttemptResult {
    Finished,
    Retry
}

class OrderStore(
    private val database: OrderDatabase,
    private val api: OrderApi,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.data()
    private val execution = Mutex()

    private suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection {
            it.withTransaction(
                Transactor.SQLiteTransactionType.IMMEDIATE) {
                    block()
                }
        }

    private suspend fun requireCurrent(ticket: SessionTicket) {
        if (dao.authority()?.ticket() != ticket) throw StaleSession()
    }

    suspend fun current(): SessionTicket? = dao.authority()?.ticket()

    suspend fun signIn(account: String): SessionTicket = transaction {
        require(account in setOf("A", "B"))
        val generation =
            Math.addExact(dao.authority()?.generation ?: 0, 1)
        dao.authority(
            Authority(
                accountId = account,
                generation = generation,
                active = true))
        SessionTicket(account, generation)
    }

    suspend fun signOut(ticket: SessionTicket) = transaction {
        requireCurrent(ticket)
        dao.authority(
            Authority(
                accountId = null,
                generation = Math.addExact(ticket.generation, 1),
                active = false))
        // Preserve uncertain business intent; sign-out does not undo an
        // accepted order.
    }

    suspend fun prepare(
        ticket: SessionTicket,
        quantity: Int
    ): OrderIntent {
        require(quantity in 1..5)
        transaction {
            requireCurrent(ticket)
            dao.unresolved(ticket.accountId)
        }
            ?.let {
                return it
            }
        val policy =
            api.policy(
                ticket
                    .accountId) // Read-only admission metadata, before
        // the local commit.
        return transaction {
            requireCurrent(ticket)
            dao.unresolved(ticket.accountId)
                ?: OrderIntent(
                        UUID.randomUUID().toString(),
                        ticket.accountId,
                        policy.admissionWindow,
                        "tea",
                        quantity)
                    .also { dao.intent(it) }
        }
    }

    suspend fun matchesUnresolved(
        ticket: SessionTicket,
        operationId: String
    ): Boolean = transaction {
        requireCurrent(ticket)
        dao.unresolved(ticket.accountId)?.operationId == operationId
    }

    suspend fun eligible(ticket: SessionTicket): Boolean = transaction {
        requireCurrent(ticket)
        dao.unresolved(ticket.accountId)?.let {
            it.phase != "NeedsReview" &&
                it.attention == null &&
                it.attempts < 5
        } ?: false
    }

    suspend fun retry(ticket: SessionTicket) = transaction {
        requireCurrent(ticket)
        dao.unresolved(ticket.accountId)
            ?.takeIf { it.phase != "NeedsReview" }
            ?.let {
                dao.intent(it.copy(attempts = 0, attention = null))
            }
    }

    private suspend fun publish(
        ticket: SessionTicket,
        claimed: OrderIntent,
        change: (OrderIntent) -> OrderIntent
    ) = transaction {
        requireCurrent(ticket)
        val current =
            dao.intent(claimed.operationId) ?: return@transaction
        if (current.attemptId == claimed.attemptId &&
            current.command() == claimed.command() &&
            current.phase == "Unknown")
            dao.intent(change(current))
    }

    suspend fun attempt(ticket: SessionTicket): AttemptResult =
        execution.withLock {
            val attemptAt = now()
            val claimed =
                try {
                    transaction {
                        requireCurrent(ticket)
                        val row =
                            dao.unresolved(ticket.accountId)
                                ?: return@transaction null
                        if (row.phase == "NeedsReview" ||
                            row.attention != null)
                            return@transaction null
                        if (row.attempts >= 5) {
                            dao.intent(
                                row.copy(
                                    attention =
                                        "Automatic checks paused; " +
                                            "check status when ready"))
                            return@transaction null
                        }
                        if (attemptAt < row.notBeforeMillis)
                            return@transaction row
                        row.copy(
                                phase = "Unknown",
                                attempts = row.attempts + 1,
                                attemptId =
                                    UUID.randomUUID().toString())
                            .also { dao.intent(it) }
                    }
                } catch (_: StaleSession) {
                    return@withLock AttemptResult.Finished
                }
            if (claimed == null) return@withLock AttemptResult.Finished
            if (attemptAt < claimed.notBeforeMillis)
                return@withLock AttemptResult.Retry
            try {
                val record =
                    when (val lookup =
                        api.lookup(
                            ticket.accountId, claimed.command())) {
                        is Lookup.Found -> lookup.record
                        Lookup.Missing -> {
                            transaction { requireCurrent(ticket) }
                            api.submit(
                                ticket.accountId, claimed.command())
                        }
                        Lookup.Gone -> {
                            publish(ticket, claimed) {
                                it.copy(
                                    phase = "NeedsReview",
                                    attemptId = null,
                                    attention =
                                        "Outcome unavailable; " +
                                            "support must reconcile this operation")
                            }
                            return@withLock AttemptResult.Finished
                        }
                    }
                if (record.command != claimed.command() ||
                    record.status !in setOf("Confirmed", "Rejected"))
                    throw ContractFailure("Result identity mismatch")
                publish(ticket, claimed) {
                    it.copy(
                        phase = record.status,
                        orderId = record.orderId,
                        reason = record.reason,
                        attemptId = null,
                        attention = null)
                }
                AttemptResult.Finished
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleSession) {
                AttemptResult.Finished
            } catch (failure: IOException) {
                try {
                    if (failure is ApiFailure && failure.code == 401) {
                        transaction {
                            requireCurrent(ticket)
                            dao.authority(
                                Authority(
                                    accountId = ticket.accountId,
                                    generation =
                                        Math.addExact(
                                            ticket.generation, 1),
                                    active = false))
                        }
                        AttemptResult.Finished
                    } else {
                        val unsafe =
                            failure is ContractFailure ||
                                failure is ApiFailure &&
                                    failure.code !in
                                        setOf(
                                            408,
                                            429,
                                            500,
                                            502,
                                            503,
                                            504)
                        publish(ticket, claimed) {
                            it.copy(
                                phase =
                                    if (unsafe) "NeedsReview"
                                    else "Unknown",
                                attemptId = null,
                                notBeforeMillis =
                                    maxOf(
                                        it.notBeforeMillis,
                                        (failure as? ApiFailure)
                                            ?.notBeforeMillis ?: 0),
                                attention =
                                    when {
                                        unsafe ->
                                            "Outcome requires review; " +
                                                "do not submit a replacement"
                                        it.attempts >= 5 ->
                                            "Automatic checks paused; " +
                                                "check status when ready"
                                        else -> null
                                    })
                        }
                        if (unsafe || claimed.attempts >= 5)
                            AttemptResult.Finished
                        else AttemptResult.Retry
                    }
                } catch (_: StaleSession) {
                    AttemptResult.Finished
                }
            }
        }
}
