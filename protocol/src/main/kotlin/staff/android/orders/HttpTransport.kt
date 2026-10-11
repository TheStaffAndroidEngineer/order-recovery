package staff.android.orders

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// The continuation remains suspended until the bounded body is closed.
data class HttpReply(val code: Int, val headers: Headers, val body: ByteArray)

class HttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val maximumBytes: Int = 64 * 1024,
) {
    init { require(maximumBytes in 1 until Int.MAX_VALUE) }

    suspend fun exchange(request: Request): HttpReply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val body = it.body
                        if (body.contentLength() > maximumBytes) {
                            throw ContractFailure("Response exceeds byte limit")
                        }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        body.byteStream().use { input ->
                            while (true) {
                                // Read one excess byte to detect unknown-length overflow.
                                val allowed = minOf(buffer.size, maximumBytes - output.size() + 1)
                                val read = input.read(buffer, 0, allowed)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                if (output.size() > maximumBytes) {
                                    throw ContractFailure("Response exceeds byte limit")
                                }
                            }
                        }
                        HttpReply(it.code, it.headers, output.toByteArray())
                    }
                    continuation.resume(reply)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }
}
