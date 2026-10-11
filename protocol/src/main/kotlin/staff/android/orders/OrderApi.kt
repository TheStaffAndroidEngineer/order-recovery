package staff.android.orders

import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ContractFailure(message: String) : IOException(message)

class ApiFailure(val code: Int, val notBeforeMillis: Long = 0) :
    IOException("HTTP $code")

@Serializable
data class OrderCommand(
    val operationId: String,
    val admissionWindow: String,
    val productId: String,
    val quantity: Int,
    val protocolVersion: Int = 1
)

@Serializable
data class OrderRecord(
    val command: OrderCommand,
    val status: String,
    val orderId: String? = null,
    val reason: String? = null
)

@Serializable data class AdmissionPolicy(val admissionWindow: String)

sealed interface Lookup {
    data class Found(val record: OrderRecord) : Lookup

    data object Missing : Lookup

    data object Gone : Lookup
}

fun isUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value
}
    .getOrDefault(false)

interface OrderApi {
    suspend fun policy(account: String): AdmissionPolicy

    suspend fun lookup(account: String, command: OrderCommand): Lookup

    suspend fun submit(
        account: String,
        command: OrderCommand
    ): OrderRecord
}

class HttpOrderApi(
    baseUrl: String,
    private val transport: HttpTransport = HttpTransport()
) : OrderApi {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(
        account: String,
        path: String
    ): Request.Builder {
        require(account in setOf("A", "B"))
        return Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer demo-$account")
            .header("Cache-Control", "no-store")
    }

    private inline fun <reified T> decode(reply: HttpReply): T {
        if (reply.headers["Content-Type"]?.substringBefore(';') !=
            "application/json")
            throw ContractFailure("Expected JSON")
        return try {
            json.decodeFromString<T>(
                reply.body.decodeToString(
                    throwOnInvalidSequence = true))
        } catch (_: Exception) {
            throw ContractFailure("Invalid representation")
        }
    }

    private fun failure(reply: HttpReply): ApiFailure {
        val now = System.currentTimeMillis()
        val raw = reply.headers["Retry-After"]
        val deadline =
            raw?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let {
                    if (it > (Long.MAX_VALUE - now) / 1000)
                        Long.MAX_VALUE
                    else now + it * 1000
                }
                ?: raw?.let {
                    runCatching {
                        java.time.ZonedDateTime.parse(
                                it,
                                java.time.format.DateTimeFormatter
                                    .RFC_1123_DATE_TIME)
                            .toInstant()
                            .toEpochMilli()
                    }
                        .getOrNull()
                }
                ?: 0
        return ApiFailure(reply.code, deadline.coerceAtLeast(0))
    }

    private fun record(
        reply: HttpReply,
        expected: OrderCommand
    ): OrderRecord {
        val record = decode<OrderRecord>(reply)
        if (record.command != expected ||
            when (record.status) {
                "Confirmed" ->
                    record.orderId.isNullOrBlank() ||
                        record.reason != null
                "Rejected" ->
                    record.orderId != null ||
                        record.reason.isNullOrBlank()
                else -> true
            })
            throw ContractFailure(
                "Receipt does not describe this operation")
        return record
    }

    override suspend fun policy(account: String): AdmissionPolicy {
        val reply =
            transport.exchange(
                request(account, "/v1/policy").get().build())
        if (reply.code != 200) throw failure(reply)
        return decode<AdmissionPolicy>(reply).also {
            if (!isUuid(it.admissionWindow))
                throw ContractFailure("Invalid admission window")
        }
    }

    override suspend fun lookup(
        account: String,
        command: OrderCommand
    ): Lookup {
        require(isUuid(command.operationId))
        val reply =
            transport.exchange(
                request(
                        account,
                        "/v1/operations/${command.operationId}" +
                            "?window=${command.admissionWindow}")
                    .get()
                    .build())
        return when (reply.code) {
            200 -> Lookup.Found(record(reply, command))
            404 -> Lookup.Missing
            410 -> Lookup.Gone
            else -> throw failure(reply)
        }
    }

    override suspend fun submit(
        account: String,
        command: OrderCommand
    ): OrderRecord {
        val body =
            json
                .encodeToString(command)
                .toRequestBody("application/json".toMediaType())
        val reply =
            transport.exchange(
                request(account, "/v1/orders").post(body).build())
        if (reply.code !in setOf(200, 201, 422)) throw failure(reply)
        val result = record(reply, command)
        if (reply.code == 201 && result.status != "Confirmed" ||
            reply.code == 422 && result.status != "Rejected")
            throw ContractFailure("Status contradicts recorded outcome")
        return result
    }
}
