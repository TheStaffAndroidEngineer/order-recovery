package staff.android.orders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OrderDatabase
    private lateinit var store: OrderStore
    private lateinit var api: FakeApi
    private lateinit var name: String
    @Before fun before() {
        name = "test-${UUID.randomUUID()}.db"
        db = openOrders(context, name); api = FakeApi(); store = OrderStore(db, api)
    }
    @After fun after() { db.close(); context.deleteDatabase(name) }
    private suspend fun prepared(quantity: Int = 2): SessionTicket {
        val ticket = store.signIn("A"); store.prepare(ticket, quantity); return ticket
    }
    private suspend fun row() = checkNotNull(db.data().latest("A"))
    @Test fun commitBeforeSchedulingSurvivesDatabaseReopen() = runBlocking {
        val ticket = prepared(); val initial = row().command()
        db.close(); db = openOrders(context, name); store = OrderStore(db, api)
        assertEquals(initial, row().command()); assertTrue(store.eligible(ticket)); assertEquals(0, api.posts)
        store.attempt(ticket); assertEquals("Confirmed", row().phase); assertEquals(initial, row().command())
    }
    @Test fun duplicatePrepareKeepsOriginalQuantityAndIdentity() = runBlocking {
        val ticket = prepared(); val original = row()
        assertEquals(original, store.prepare(ticket, 3)); assertEquals(1, api.policies)
    }
    @Test fun lostAcceptedReplyBecomesUnknownThenLookupConfirms() = runBlocking {
        val ticket = prepared(); val original = row().command(); api.loseReply = true
        assertEquals(AttemptResult.Retry, store.attempt(ticket)); assertEquals("Unknown", row().phase)
        assertNull(row().orderId); assertEquals(1, api.posts)
        store = OrderStore(db, api); store.attempt(ticket)
        assertEquals("Confirmed", row().phase); assertEquals("order-1", row().orderId)
        assertEquals(original, row().command()); assertEquals(1, api.posts)
    }
    @Test fun businessRejectionIsTerminalAndAllowsNewIntent() = runBlocking {
        val ticket = prepared(5); store.attempt(ticket)
        val original = row(); assertEquals("Rejected", original.phase); assertNull(original.orderId)
        val fresh = store.prepare(ticket, 2)
        assertNotEquals(original.operationId, fresh.operationId); assertEquals("Queued", fresh.phase)
    }
    @Test fun unknownWindowExpiryNeverAllocatesReplacement() = runBlocking {
        val ticket = prepared(); val command = row().command(); api.gone = true
        store.attempt(ticket); assertEquals("NeedsReview", row().phase); assertEquals(0, api.posts)
        store.retry(ticket); assertFalse(store.eligible(ticket)); store.prepare(ticket, 3)
        assertEquals(command, row().command()); assertEquals(1, api.policies)
    }
    @Test fun repeatedTransportFailureRetainsIntentAndBoundsAttempts() = runBlocking {
        val ticket = prepared(); api.failure = IOException("Offline")
        repeat(4) { assertEquals(AttemptResult.Retry, store.attempt(ticket)) }
        assertEquals(AttemptResult.Finished, store.attempt(ticket)); assertEquals(5, row().attempts)
        assertEquals("Unknown", row().phase); assertNotNull(row().attention); assertFalse(store.eligible(ticket))
        store.retry(ticket); api.failure = null; store.attempt(ticket); assertEquals("Confirmed", row().phase)
    }
    @Test fun retryAfterSurvivesNewOwnerAndManualCheck() = runBlocking {
        var time = 1000L; store = OrderStore(db, api) { time }
        val ticket = prepared(); api.failure = ApiFailure(429, 10_000)
        store.attempt(ticket); assertEquals(1, api.lookups)
        store = OrderStore(db, api) { time }; store.retry(ticket); api.failure = null
        assertEquals(AttemptResult.Retry, store.attempt(ticket)); assertEquals(1, api.lookups)
        time = 10_000; store.attempt(ticket); assertEquals(2, api.lookups); assertEquals("Confirmed", row().phase)
    }
    @Test fun signOutWhileLookupWaitsPreventsFreshPost() = runBlocking {
        val ticket = prepared(); api.holdLookup = true
        val running = async { store.attempt(ticket) }; api.started.await()
        store.signOut(ticket); api.release.complete(Unit); running.await()
        assertEquals(0, api.posts); assertEquals("Unknown", row().phase)
    }
    @Test fun oldAcceptedReplyCannotPublishAfterAccountChange() = runBlocking {
        val ticket = prepared(); api.holdSubmit = true
        val running = async { store.attempt(ticket) }; api.started.await()
        store.signOut(ticket); val b = store.signIn("B")
        api.release.complete(Unit); running.await()
        assertEquals(b, store.current()); assertNull(db.data().latest("B")); assertEquals("Unknown", row().phase)
        assertEquals(1, api.records.size)
    }
    @Test fun sameAccountReauthenticationUsesOriginalWindowAndOperation() = runBlocking {
        val old = prepared(); val original = row().command(); api.holdSubmit = true
        val running = async { store.attempt(old) }; api.started.await()
        store.signOut(old); val fresh = store.signIn("A")
        api.release.complete(Unit); running.await(); assertEquals("Unknown", row().phase)
        api.holdSubmit = false; store.attempt(fresh)
        assertEquals(original, row().command()); assertEquals("Confirmed", row().phase)
        assertEquals(1, api.posts); assertEquals(1, api.policies)
    }
    @Test fun unauthorizedExpiresAuthorityWithoutDeletingOrder() = runBlocking {
        val old = prepared(); val original = row().command(); api.failure = ApiFailure(401)
        store.attempt(old); assertNull(store.current()); assertEquals(original, row().command())
        assertEquals("A", db.data().authority()!!.accountId)
        val fresh = store.signIn("A"); api.failure = null; store.attempt(fresh)
        assertNotEquals(old, fresh); assertEquals("Confirmed", row().phase)
    }
    @Test fun applicationProtocolFailureRequiresReviewNotBusinessRejection() = runBlocking {
        val ticket = prepared(); api.failure = ApiFailure(400)
        store.attempt(ticket); assertEquals("NeedsReview", row().phase); assertNull(row().reason)
    }
    @Test fun malformedReceiptPreservesUncertainty() = runBlocking {
        val ticket = prepared(); api.failure = ContractFailure("Mismatched receipt")
        store.attempt(ticket); assertEquals("NeedsReview", row().phase); assertNull(row().orderId)
    }
    @Test fun cancellationAfterRemoteAcceptanceLeavesRecoverableIdentity() = runBlocking {
        val ticket = prepared(); val original = row().command(); api.holdSubmit = true
        val running = async { store.attempt(ticket) }; api.started.await(); running.cancelAndJoin()
        assertEquals("Unknown", row().phase); assertEquals(original, row().command()); assertEquals(1, api.records.size)
        api.holdSubmit = false; store = OrderStore(db, api); store.attempt(ticket)
        assertEquals("Confirmed", row().phase); assertEquals(1, api.posts)
    }
    @Test fun newerAttemptWinsAgainstEarlierLateFailure() = runBlocking {
        val ticket = prepared(); api.holdSubmit = true; api.loseReply = true
        val old = async { store.attempt(ticket) }; api.started.await()
        val earlierAttempt = row().attemptId
        OrderStore(db, api).attempt(ticket)
        assertEquals("Confirmed", row().phase); assertNull(row().attemptId)
        api.release.complete(Unit); old.await()
        assertEquals("Confirmed", row().phase); assertEquals("order-1", row().orderId)
        assertNull(row().attention); assertNotNull(earlierAttempt); assertEquals(1, api.posts)
    }
    @Test fun changedReceiptIdentityCannotBePublished() = runBlocking {
        val ticket = prepared(); api.wrongIdentity = true
        store.attempt(ticket); assertEquals("NeedsReview", row().phase); assertNull(row().orderId)
    }
    @Test fun workerConstraintsAndRecoveryUseSavedOperation() = runBlocking {
        val ticket = prepared(); val original = row().command()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String,
                    workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == OrderWorker::class.java.name)
                        OrderWorker(appContext, workerParameters, store) else null
            }
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setExecutor(executor).setWorkerFactory(factory).build())
            val manager = WorkManager.getInstance(context)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            val request = OneTimeWorkRequestBuilder<OrderWorker>().setInputData(ticketData(ticket))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueue(request).await()
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get()!!.state)
            assertEquals(0, api.posts)
            driver.setAllConstraintsMet(request.id)
            withTimeout(5_000) { while (manager.getWorkInfoById(request.id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(20) }
            assertEquals("Confirmed", row().phase); assertEquals(original, row().command()); assertEquals(1, api.posts)
        } finally { executor.shutdown() }
    }
    @Test fun explicitReplacementPreservesCommandAndRetryAfter() = runBlocking {
        var time = 1000L; store = OrderStore(db, api) { time }
        val ticket = prepared(); val original = row().command()
        api.failure = ApiFailure(429, 10_000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String,
                    workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == OrderWorker::class.java.name)
                        OrderWorker(appContext, workerParameters, store) else null
            }
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setExecutor(executor).setWorkerFactory(factory).build())
            val manager = WorkManager.getInstance(context)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            val scheduler = OrderScheduler(manager)
            val unique = "orders:${sessionTag(ticket)}"
            scheduler.enqueue(ticket)
            val first = manager.getWorkInfosForUniqueWork(unique).get().single().id
            driver.setAllConstraintsMet(first)
            withTimeout(5_000) { while (true) {
                val info = manager.getWorkInfoById(first).get()!!
                if (info.runAttemptCount >= 1 && info.state == WorkInfo.State.ENQUEUED) break
                delay(20)
            } }
            assertEquals(10_000L, row().notBeforeMillis)
            store.retry(ticket); api.failure = null; scheduler.enqueue(ticket)
            val next = manager.getWorkInfosForUniqueWork(unique).get().single { it.id != first }.id
            val replaced = manager.getWorkInfoById(first).get()
            assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
            driver.setAllConstraintsMet(next)
            withTimeout(5_000) { while (true) {
                val info = manager.getWorkInfoById(next).get()!!
                if (info.runAttemptCount >= 1 && info.state == WorkInfo.State.ENQUEUED) break
                delay(20)
            } }
            assertEquals(original, row().command()); assertEquals(1, api.lookups)
            assertEquals(10_000L, row().notBeforeMillis)
            time = 10_000; store.retry(ticket); scheduler.enqueue(ticket)
            val eligible = manager.getWorkInfosForUniqueWork(unique).get()
                .single { it.id != first && it.id != next }.id
            driver.setAllConstraintsMet(eligible)
            withTimeout(5_000) { while (manager.getWorkInfoById(eligible).get()!!.state != WorkInfo.State.SUCCEEDED) delay(20) }
            assertEquals("Confirmed", row().phase); assertEquals(1, api.posts)
        } finally { executor.shutdown() }
    }
    @Test fun reorderedHintsCannotCreateOrCompleteAnOrder() = runBlocking {
        val ticket = prepared(); val original = row().command()
        val admitted = mutableListOf<SessionTicket>()
        val hints = RecoveryHints(store) { admitted += it }
        hints.operationChanged("B", original.operationId)
        hints.operationChanged("A", UUID.randomUUID().toString())
        assertTrue(admitted.isEmpty())
        repeat(2) { hints.operationChanged("A", original.operationId) }
        assertEquals(listOf(ticket, ticket), admitted)
        assertEquals("Queued", row().phase); assertEquals(0, api.posts)
        store.attempt(ticket); admitted.clear()
        hints.operationChanged("A", original.operationId); hints.reconnected()
        assertTrue(admitted.isEmpty()); assertEquals(1, api.posts)
    }
    private class FakeApi : OrderApi {
        val window = UUID.randomUUID().toString()
        val records = mutableMapOf<String, OrderRecord>()
        var policies = 0; var lookups = 0; var posts = 0
        var failure: IOException? = null
        var loseReply = false; var gone = false; var wrongIdentity = false
        var holdLookup = false; var holdSubmit = false
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        override suspend fun policy(account: String): AdmissionPolicy { policies++; return AdmissionPolicy(window) }
        override suspend fun lookup(account: String, command: OrderCommand): Lookup {
            lookups++; failure?.let { throw it }
            if (holdLookup) { started.complete(Unit); release.await() }
            if (gone) return Lookup.Gone
            return records[command.operationId]?.let { Lookup.Found(it) } ?: Lookup.Missing
        }
        override suspend fun submit(account: String, command: OrderCommand): OrderRecord {
            posts++
            val record = if (command.quantity == 5) OrderRecord(command, "Rejected", reason = "Stock")
                else OrderRecord(command, "Confirmed", "order-1")
            records[command.operationId] = record
            if (holdSubmit) { started.complete(Unit); release.await() }
            if (loseReply) { loseReply = false; throw IOException("Accepted but reply lost") }
            return if (wrongIdentity) record.copy(command = command.copy(quantity = 3)) else record
        }
    }
}
