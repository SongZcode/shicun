package com.emojetools.app.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class HttpTransport(timeoutSeconds: Long = 15, followRedirects: Boolean = false, callTimeoutSeconds: Long = timeoutSeconds) {
    val client = OkHttpClient.Builder().connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS).callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(followRedirects).followSslRedirects(false).build()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }

    suspend fun <T> consume(request: Request, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        // Keep cancellation attached until the body is consumed, not just until headers arrive.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { withContext(Dispatchers.IO) { call.execute().use(read) } }
        finally { cancellation.cancelAndJoin() }
    }
}
