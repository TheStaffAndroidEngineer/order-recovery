package staff.android.orders

// A hint carries identity, never authority to publish an order outcome.
class RecoveryHints(
    private val store: OrderStore,
    private val enqueue: suspend (SessionTicket) -> Unit
) {
    suspend fun operationChanged(account: String, operationId: String) {
        val ticket = store.current() ?: return
        if (ticket.accountId != account) return
        try {
            if (store.matchesUnresolved(ticket, operationId) &&
                store.eligible(ticket))
                enqueue(ticket)
        } catch (_: StaleSession) {
            /* Account changed while the hint was being inspected. */
        }
    }

    suspend fun reconnected() {
        val ticket = store.current() ?: return
        try {
            if (store.eligible(ticket)) enqueue(ticket)
        } catch (_: StaleSession) {
            /* A later recovery entry can inspect current authority. */
        }
    }
}
