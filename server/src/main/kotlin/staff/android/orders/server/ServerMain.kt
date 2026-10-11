package staff.android.orders.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import staff.android.orders.*

// Loopback-only controlled service. Demo credentials and control endpoints are not production auth.
class OrderServer(path: Path, port: Int = 8790) : AutoCloseable {
    val ledger = OrderLedger(path)
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val executor = Executors.newFixedThreadPool(4)
    private val loseNext = AtomicBoolean(false)
    private val failNext = AtomicBoolean(false)
    private val holdNext = AtomicBoolean(false)
    private val offline = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val journal = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var gate: CountDownLatch? = null
    val port: Int get() = http.address.port
    init {
        http.executor = executor
        http.createContext("/v1") { handle(it) }
        http.createContext("/control") { control(it) }
        http.start()
    }
    private fun reply(e: HttpExchange, code: Int, body: String = "{}") {
        e.responseHeaders.set("Content-Type", "application/json")
        e.responseHeaders.set("Cache-Control", "no-store")
        if (code == 401) e.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"order-fixture\"")
        if (code == 503) e.responseHeaders.set("Retry-After", "1")
        if (code == 405) e.responseHeaders.set("Allow", if (e.requestURI.path == "/v1/orders" || e.requestURI.path.startsWith("/control")) "POST" else "GET")
        val bytes = body.toByteArray(); e.sendResponseHeaders(code, bytes.size.toLong())
        e.responseBody.use { it.write(bytes) }
    }
    private fun handle(e: HttpExchange) {
        try {
            val account = when (e.requestHeaders.getFirst("Authorization")) {
                "Bearer demo-A" -> "A"; "Bearer demo-B" -> "B"
                else -> { reply(e, 401); return }
            }
            if (expired.get()) { reply(e, 401); return }
            if (offline.get()) { reply(e, 503); return }
            val path = e.requestURI.path
            when {
                path == "/v1/policy" && e.requestMethod == "GET" ->
                    reply(e, 200, Json.encodeToString(ledger.policy()))
                path.startsWith("/v1/operations/") && e.requestMethod == "GET" -> {
                    val id = path.removePrefix("/v1/operations/")
                    val window = e.requestURI.rawQuery?.removePrefix("window=") ?: ""
                    if (!isUuid(id) || !isUuid(window)) { reply(e, 400); return }
                    val found = ledger.lookup(account, id, window)
                    journal += "LOOKUP $account $id -> ${found.code}"
                    reply(e, found.code, found.record?.let { Json.encodeToString(it) } ?: "{}")
                }
                path == "/v1/orders" && e.requestMethod == "POST" -> {
                    val bytes = e.requestBody.use { it.readNBytes(4097) }
                    if (bytes.size > 4096) { reply(e, 413); return }
                    val command = try { Json.decodeFromString<OrderCommand>(bytes.decodeToString(throwOnInvalidSequence = true)) }
                        catch (_: Exception) { reply(e, 400); return }
                    if (command.protocolVersion != 1) { reply(e, 400, "{\"error\":\"unsupported_application_version\"}"); return }
                    if (!isUuid(command.operationId) || !isUuid(command.admissionWindow) ||
                        command.productId != "tea" || command.quantity !in 1..5) { reply(e, 400); return }
                    val result = try { ledger.submit(account, command, failNext.compareAndSet(true, false)) }
                        catch (_: Exception) { journal += "ROLLBACK $account ${command.operationId}"; reply(e, 503); return }
                    journal += "POST $account ${command.operationId} -> ${result.code} ${result.record?.orderId}"
                    if (holdNext.compareAndSet(true, false)) {
                        val held = CountDownLatch(1); gate = held
                        journal += "HOLD_AFTER_COMMIT ${command.operationId}"
                        held.await(20, TimeUnit.SECONDS); gate = null
                    }
                    if (result.code == 201 && loseNext.compareAndSet(true, false)) {
                        journal += "LOST_REPLY ${command.operationId}"; return
                    }
                    reply(e, result.code, result.record?.let { Json.encodeToString(it) } ?: "{}")
                }
                path == "/v1/orders" || path == "/v1/policy" || path.startsWith("/v1/operations/") -> reply(e, 405)
                else -> reply(e, 404)
            }
        } catch (_: Exception) { /* Client may have disconnected after the durable commit. */ }
        finally { e.close() }
    }
    private fun control(e: HttpExchange) {
        try {
            if (e.requestMethod == "GET" && e.requestURI.path == "/control/journal") {
                reply(e, 200, synchronized(journal) { Json.encodeToString(journal.toList()) }); return
            }
            if (e.requestMethod == "GET" && e.requestURI.path == "/control/count") {
                reply(e, 200, "{\"orders\":${ledger.orderCount()}}"); return
            }
            if (e.requestMethod != "POST") { reply(e, 405); return }
            when (e.requestURI.path) {
                "/control/reset" -> {
                    loseNext.set(false); failNext.set(false); holdNext.set(false)
                    gate?.countDown(); gate = null
                    ledger.reset(); journal.clear(); offline.set(false); expired.set(false)
                }
                "/control/lose-reply" -> loseNext.set(true)
                "/control/fail-before-ledger" -> failNext.set(true)
                "/control/hold-after-commit" -> holdNext.set(true)
                "/control/release" -> gate?.countDown()
                "/control/offline" -> offline.set(true)
                "/control/online" -> offline.set(false)
                "/control/expire-auth" -> expired.set(true)
                "/control/renew-auth" -> expired.set(false)
                "/control/retire-window" -> ledger.retireWindow()
                "/control/expire-outcomes" -> ledger.expireOutcomes()
                else -> { reply(e, 404); return }
            }
            reply(e, 200)
        } finally { e.close() }
    }
    override fun close() {
        gate?.countDown(); http.stop(0); executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS); ledger.close()
    }
}
fun main(args: Array<String>) {
    val server = OrderServer(Path.of(args.getOrElse(1) { "orders.db" }), args.firstOrNull()?.toInt() ?: 8790)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Order fixture http://127.0.0.1:${server.port}; SQLite ledger survives restart")
    CountDownLatch(1).await()
}
