package com.ocalix.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpResult(val code: Int, val type: String?, val body: ByteArray) {
    fun text(): String = String(body, Charsets.UTF_8)
}

object Http {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Untuk streaming audio (read timeout lebih longgar). */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** GET, atau POST JSON kalau [body] diisi. */
    fun request(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Long = 0
    ): HttpResult {
        val rb = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) rb.header(k, v)
        if (body != null) rb.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val c = if (timeoutMs > 0) client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else client
        return c.newCall(rb.build()).execute().use { r ->
            HttpResult(r.code, r.header("Content-Type"), r.body?.bytes() ?: ByteArray(0))
        }
    }
}

/** Cache kecil berurutan masuk (padanan Map + hapus entri tertua di JS). */
class Lru<K, V>(private val max: Int) {
    private val m = object : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    @Synchronized operator fun get(k: K): V? = m[k]
    @Synchronized fun put(k: K, v: V) { m[k] = v }
    @Synchronized fun remove(k: K) { m.remove(k) }
}

/** Jalankan semua task paralel, kembalikan hasil non-null pertama (atau null). */
fun <T : Any> firstNonNull(tasks: List<() -> T?>): T? {
    if (tasks.isEmpty()) return null
    val pool = Executors.newFixedThreadPool(tasks.size)
    try {
        val cs = ExecutorCompletionService<T?>(pool)
        for (t in tasks) {
            cs.submit(Callable<T?> { try { t() } catch (e: Exception) { null } })
        }
        for (i in tasks.indices) {
            val r = try { cs.take().get() } catch (e: Exception) { null }
            if (r != null) return r
        }
        return null
    } finally {
        pool.shutdownNow()
    }
}
