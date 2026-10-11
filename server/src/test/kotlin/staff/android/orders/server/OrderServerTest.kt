package staff.android.orders.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import staff.android.orders.*

class OrderServerTest {
    private fun control(server: OrderServer, action: String) {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/control/$action"))
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        assertEquals(200, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
    }
    private suspend fun command(api: OrderApi, account: String = "A", quantity: Int = 2) =
        OrderCommand(UUID.randomUUID().toString(), api.policy(account).admissionWindow, "tea", quantity)
    private fun fixture(block: suspend (OrderServer, HttpOrderApi) -> Unit) = runBlocking {
        val path = Files.createTempDirectory("order-http").resolve("orders.db")
        OrderServer(path, 0).use { block(it, HttpOrderApi("http://127.0.0.1:${it.port}")) }
    }
    @Test fun lostReplyIsRecoveredWithoutAnotherOrder() = fixture { server, api ->
        val request = command(api); control(server, "lose-reply")
        try { api.submit("A", request); fail<Unit>("Reply should be lost") } catch (_: java.io.IOException) { }
        assertEquals(1, server.ledger.orderCount())
        val found = api.lookup("A", request) as Lookup.Found
        assertEquals("order-1", found.record.orderId)
        assertEquals(found.record, api.submit("A", request))
        assertEquals(1, server.ledger.orderCount())
    }
    @Test fun newIdAfterLostReplyCreatesTheDuplicate() = fixture { server, api ->
        val request = command(api); control(server, "lose-reply")
        runCatching { api.submit("A", request) }
        api.submit("A", request.copy(operationId = UUID.randomUUID().toString()))
        assertEquals(2, server.ledger.orderCount())
    }
    @Test fun concurrentSameOperationCommitsOnce() = fixture { server, api ->
        val request = command(api)
        val results = coroutineScope { (1..8).map { async { api.submit("A", request) } }.awaitAll() }
        assertEquals(1, results.distinct().size); assertEquals(1, server.ledger.orderCount())
    }
    @Test fun changedPayloadWithSameIdIsRejected() = fixture { server, api ->
        val request = command(api); api.submit("A", request)
        val failure = try { api.submit("A", request.copy(quantity = 3)); null } catch (e: ApiFailure) { e }
        assertEquals(409, failure?.code); assertEquals(1, server.ledger.orderCount())
    }
    @Test fun failedLedgerInsertRollsBackBusinessOrder() = fixture { server, api ->
        val request = command(api); control(server, "fail-before-ledger")
        val failure = try { api.submit("A", request); null } catch (e: ApiFailure) { e }
        assertEquals(503, failure?.code); assertEquals(0, server.ledger.orderCount())
        assertEquals(Lookup.Missing, api.lookup("A", request))
        assertEquals("order-1", api.submit("A", request).orderId)
    }
    @Test fun retainedResultReplaysAfterWindowRetirement() = fixture { server, api ->
        val request = command(api); val accepted = api.submit("A", request)
        control(server, "retire-window")
        assertEquals(accepted, api.submit("A", request))
        assertEquals(Lookup.Found(accepted), api.lookup("A", request))
        assertEquals(1, server.ledger.orderCount())
    }
    @Test fun retiredUnknownWindowCannotAdmitAnOrder() = fixture { server, api ->
        val request = command(api); assertEquals(Lookup.Missing, api.lookup("A", request))
        control(server, "retire-window")
        assertEquals(Lookup.Gone, api.lookup("A", request))
        val failure = try { api.submit("A", request); null } catch (e: ApiFailure) { e }
        assertEquals(410, failure?.code); assertEquals(0, server.ledger.orderCount())
    }
    @Test fun expiredOutcomeTombstoneBlocksReplay() = fixture { server, api ->
        val request = command(api); api.submit("A", request); control(server, "expire-outcomes")
        assertEquals(Lookup.Gone, api.lookup("A", request))
        val failure = try { api.submit("A", request); null } catch (e: ApiFailure) { e }
        assertEquals(410, failure?.code); assertEquals(1, server.ledger.orderCount())
    }
    @Test fun status404DoesNotReserveAbsence() = fixture { server, api ->
        val request = command(api); assertEquals(Lookup.Missing, api.lookup("A", request))
        val concurrent = api.submit("A", request)
        assertEquals(concurrent, api.submit("A", request)); assertEquals(1, server.ledger.orderCount())
    }
    @Test fun accountScopePreventsReceiptDisclosure() = fixture { server, api ->
        val request = command(api); api.submit("A", request)
        assertEquals(Lookup.Missing, api.lookup("B", request))
        assertEquals("order-2", api.submit("B", request).orderId)
        assertEquals(2, server.ledger.orderCount())
    }
    @Test fun businessRejectionIsRecordedAndReplayed() = fixture { server, api ->
        val request = command(api, quantity = 5); val rejected = api.submit("A", request)
        assertEquals("Rejected", rejected.status); assertNull(rejected.orderId)
        assertEquals(rejected, api.submit("A", request)); assertEquals(Lookup.Found(rejected), api.lookup("A", request))
        assertEquals(0, server.ledger.orderCount())
    }
    @Test fun oldApplicationProtocolDoesNotExecuteAnOrder() = fixture { server, api ->
        val request = command(api).copy(protocolVersion = 0)
        val failure = try { api.submit("A", request); null } catch (e: ApiFailure) { e }
        assertEquals(400, failure?.code); assertEquals(0, server.ledger.orderCount())
    }
    @Test fun restartKeepsWindowOrderAndReceipt() = runBlocking {
        val path = Files.createTempDirectory("order-restart").resolve("orders.db")
        lateinit var request: OrderCommand
        lateinit var receipt: OrderRecord
        OrderServer(path, 0).use {
            val api = HttpOrderApi("http://127.0.0.1:${it.port}")
            request = command(api); receipt = api.submit("A", request)
        }
        OrderServer(path, 0).use {
            val api = HttpOrderApi("http://127.0.0.1:${it.port}")
            assertEquals(request.admissionWindow, api.policy("A").admissionWindow)
            assertEquals(Lookup.Found(receipt), api.lookup("A", request))
            assertEquals(receipt, api.submit("A", request)); assertEquals(1, it.ledger.orderCount())
        }
    }
    @Test fun twoLedgerConnectionsContendWithoutDuplicatingTheOrder() {
        val path = Files.createTempDirectory("order-contention").resolve("orders.db")
        OrderLedger(path).use { first -> OrderLedger(path).use { second ->
            val command = OrderCommand(UUID.randomUUID().toString(), first.policy().admissionWindow, "tea", 2)
            val gate = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val tasks = listOf(first, second).map { ledger -> pool.submit(java.util.concurrent.Callable {
                    gate.await(); runCatching { ledger.submit("A", command) }
                }) }
                gate.countDown()
                val results = tasks.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertTrue(results.any { it.isSuccess })
                results.mapNotNull { it.exceptionOrNull() }.forEach { error ->
                    assertTrue(error is java.sql.SQLException &&
                        (error.errorCode and 0xff) in setOf(5, 6), "Unexpected contention failure: $error")
                }
                // SQLite BUSY is retryable at the whole-transaction boundary, using the same identity.
                val a = first.submit("A", command); val b = second.submit("A", command)
                assertEquals(a.record, b.record); assertEquals(1, first.orderCount())
            } finally { pool.shutdownNow() }
        } }
    }
    @Test fun contradictoryHttpStatusCannotBecomeABusinessOutcome() = runBlocking {
        val command = OrderCommand(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "tea", 2)
        for ((code, record) in listOf(201 to OrderRecord(command, "Rejected", reason = "No stock"),
            422 to OrderRecord(command, "Confirmed", orderId = "order-1"))) {
            val fixture = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            fixture.createContext("/v1/orders") { e ->
                e.requestBody.close()
                val body = kotlinx.serialization.json.Json.encodeToString(record).toByteArray()
                e.responseHeaders.set("Content-Type", "application/json")
                e.sendResponseHeaders(code, body.size.toLong()); e.responseBody.use { it.write(body) }; e.close()
            }
            fixture.start()
            try {
                val api = HttpOrderApi("http://127.0.0.1:${fixture.address.port}")
                val error = runCatching { api.submit("A", command) }.exceptionOrNull()
                assertInstanceOf(ContractFailure::class.java, error)
            } finally { fixture.stop(0) }
        }
    }
    @Test fun missingStatusResponseForbidsCaching() = fixture { server, api ->
        val command = command(api)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/v1/operations/${command.operationId}?window=${command.admissionWindow}"))
            .header("Authorization", "Bearer demo-A").GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, response.statusCode()); assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
    }

    @Test fun resetClearsEveryArmedFaultBeforeAnIndependentHistory() = fixture { server, api ->
        for (fault in listOf("lose-reply", "fail-before-ledger", "hold-after-commit", "offline", "expire-auth"))
            control(server, fault)
        control(server, "reset")
        val request = command(api)
        assertEquals("order-1", api.submit("A", request).orderId)
        assertEquals(1, server.ledger.orderCount())
        val journalRequest = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/control/journal")).GET().build()
        val body = HttpClient.newHttpClient().send(journalRequest, HttpResponse.BodyHandlers.ofString()).body()
        for (fault in listOf("LOST_REPLY", "ROLLBACK", "HOLD_AFTER_COMMIT")) assertFalse(body.contains(fault))
    }

}
