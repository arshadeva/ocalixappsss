package com.ocalix.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

/**
 * Padanan index.js (Node): melayani /api/* dan file statis dari assets/public,
 * hanya di 127.0.0.1 supaya WebView bisa memuat UI yang sama persis.
 */
class OcalixServer(private val ctx: Context, port: Int) : NanoHTTPD(Config.HOST, port) {

    private object BadGateway : Response.IStatus {
        override fun getRequestStatus(): Int = 502
        override fun getDescription(): String = "502 Bad Gateway"
    }

    private val imgCache = Lru<String, HttpResult>(80)
    private val idRe = Regex("^[\\w-]{11}$")
    private val imgHost = Regex("""(^|\.)(ytimg\.com|mzstatic\.com|ggpht\.com|googleusercontent\.com)$""")

    private val mime = mapOf(
        "html" to "text/html; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "png" to "image/png",
        "svg" to "image/svg+xml",
        "json" to "application/json; charset=utf-8"
    )

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, "error")
        }
    }

    private fun param(s: IHTTPSession, k: String): String = s.parameters[k]?.firstOrNull().orEmpty()

    private fun text(st: Response.IStatus, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(st, "text/plain; charset=utf-8", body)

    private fun json(o: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", o.toString())

    private fun route(s: IHTTPSession): Response {
        when (s.uri) {
            "/api/config" ->
                return json(JSONObject().put("name", "Ocalix").put("domain", Config.DOMAIN).put("port", listeningPort))
            "/api/search" -> {
                val q = param(s, "q").trim().take(100)
                if (q.isEmpty()) return json(JSONObject().put("items", JSONArray()))
                return json(Search.search(q))
            }
            "/api/why" ->
                return json(JSONObject().put("v", "1.2.3").put("why", Resolver.why(param(s, "id"))))
            "/api/audio" -> return audio(s, false)
            "/api/img" -> return img(param(s, "u"))
        }
        return asset(s.uri)
    }

    /* ---------- file statis ---------- */

    private fun asset(uri: String): Response {
        val rel = if (uri == "/") "index.html" else uri.trimStart('/')
        if (rel.contains("..")) return text(Status.NOT_FOUND, "404")
        val bytes = try {
            ctx.assets.open("public/$rel").use { it.readBytes() }
        } catch (e: IOException) {
            return text(Status.NOT_FOUND, "404")
        }
        val ext = rel.substringAfterLast('.', "").lowercase()
        val type = mime[ext] ?: "application/octet-stream"
        val res = if (type.startsWith("text/") || type.startsWith("application/json")) {
            NanoHTTPD.newFixedLengthResponse(Status.OK, type, String(bytes, Charsets.UTF_8))
        } else {
            NanoHTTPD.newFixedLengthResponse(Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        res.addHeader("Cache-Control", if (ext == "html") "no-cache" else "public,max-age=86400")
        return res
    }

    /* ---------- proxy gambar sampul ---------- */

    private fun img(u: String): Response {
        val t = try { URI(u) } catch (e: Exception) { return text(Status.FORBIDDEN, "") }
        if (t.scheme != "https" || !imgHost.containsMatchIn(t.host ?: "")) return text(Status.FORBIDDEN, "")
        val key = t.toString()
        val r = imgCache[key] ?: Http.request(key).also { if (it.code == 200) imgCache.put(key, it) }
        if (r.code != 200) return text(Status.NOT_FOUND, "")
        val res = NanoHTTPD.newFixedLengthResponse(
            Status.OK, r.type ?: "image/jpeg", ByteArrayInputStream(r.body), r.body.size.toLong()
        )
        res.addHeader("Cache-Control", "public,max-age=86400")
        return res
    }

    /* ---------- proxy audio (mendukung Range) ---------- */

    private fun audio(s: IHTTPSession, retry: Boolean): Response {
        val id = param(s, "id")
        if (!Config.AUDIO || !idRe.matches(id)) return text(Status.NOT_FOUND, "")
        if (param(s, "dbg").isNotEmpty()) return json(Resolver.debug(id))

        val st = Resolver.resolve(id, retry) ?: return text(BadGateway, "")
        val range = s.headers["range"] ?: "bytes=0-"
        val req = Request.Builder().url(st.u)
            .header("User-Agent", st.ua)
            .header("Range", range)
            .build()
        val resp = try {
            Http.streamClient.newCall(req).execute()
        } catch (e: Exception) {
            return text(BadGateway, "")
        }
        if (resp.code >= 400) {
            resp.close()
            Resolver.invalidate(id)
            return if (!retry) audio(s, true) else text(BadGateway, "")
        }
        val body = resp.body
        if (body == null) {
            resp.close()
            return text(BadGateway, "")
        }
        val status = if (resp.code == 206) Status.PARTIAL_CONTENT else Status.OK
        val len = body.contentLength()
        val out = if (len >= 0) {
            NanoHTTPD.newFixedLengthResponse(status, st.m, body.byteStream(), len)
        } else {
            NanoHTTPD.newChunkedResponse(status, st.m, body.byteStream())
        }
        out.addHeader("Accept-Ranges", "bytes")
        out.addHeader("Cache-Control", "no-store")
        resp.header("Content-Range")?.let { out.addHeader("Content-Range", it) }
        return out
    }
}

/** Menjaga satu server selama proses hidup. */
object ServerHolder {
    private var server: OcalixServer? = null

    @Synchronized
    fun ensure(ctx: Context): Int {
        server?.let { if (it.isAlive) return it.listeningPort }
        for (port in Config.PORT..Config.PORT + 9) {
            val s = OcalixServer(ctx.applicationContext, port)
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = s
                Search.startWarmup()
                return port
            } catch (e: IOException) {
                // port terpakai, coba berikutnya
            }
        }
        throw IllegalStateException("Tidak ada port kosong di ${Config.PORT}..${Config.PORT + 9}")
    }
}
