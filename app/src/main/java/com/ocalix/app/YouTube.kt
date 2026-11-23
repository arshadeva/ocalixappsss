package com.ocalix.app

import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

private const val TAG = "Ocalix"
private const val YT_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

private fun JSONObject?.obj(k: String): JSONObject? = this?.optJSONObject(k)
private fun JSONObject?.arr(k: String): JSONArray? = this?.optJSONArray(k)

/* ===================== PENCARIAN ===================== */

object Search {
    private class Entry(val t: Long, val d: JSONObject)

    private val cache = Lru<String, Entry>(200)
    private val inflight = ConcurrentHashMap<String, FutureTask<JSONObject>>()
    private val HOME = listOf("lagu indonesia terpopuler 2026", "lagu santai akustik indonesia", "top global hits 2026")
    @Volatile private var warmStarted = false

    fun search(q: String): JSONObject {
        val c = cache[q]
        if (c != null && System.currentTimeMillis() - c.t < 600_000) return c.d
        val task = FutureTask(Callable { doSearch(q) })
        val running = inflight.putIfAbsent(q, task)
        if (running != null) return running.get()
        try {
            task.run()
            return task.get()
        } finally {
            inflight.remove(q)
        }
    }

    private fun doSearch(q: String): JSONObject {
        var items = JSONArray()
        var src = "youtube"
        try { items = youtube(q) } catch (e: Exception) { Log.d(TAG, "[yt] ${e.message}") }
        if (items.length() == 0) {
            src = "preview"
            try { items = itunes(q) } catch (e: Exception) { Log.d(TAG, "[itunes] ${e.message}") }
        }
        val d = JSONObject().put("items", items).put("src", src)
        if (items.length() > 0) cache.put(q, Entry(System.currentTimeMillis(), d))
        return d
    }

    private fun youtube(q: String): JSONArray {
        val client = JSONObject()
            .put("clientName", "WEB").put("clientVersion", "2.20240726.00.00")
            .put("hl", "id").put("gl", "ID")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", q).put("params", "EgIQAQ==")
            .toString()
        val r = Http.request(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key=$YT_KEY",
            mapOf("Origin" to "https://www.youtube.com"), body
        )
        val j = JSONObject(r.text())
        val out = JSONArray()
        val secs = j.obj("contents").obj("twoColumnSearchResultsRenderer").obj("primaryContents")
            .obj("sectionListRenderer").arr("contents") ?: return out
        for (si in 0 until secs.length()) {
            val items = secs.optJSONObject(si).obj("itemSectionRenderer").arr("contents") ?: continue
            for (ii in 0 until items.length()) {
                val v = items.optJSONObject(ii).obj("videoRenderer") ?: continue
                val lt = v.obj("lengthText")?.optString("simpleText", "") ?: ""
                if (lt.isEmpty()) continue
                var d = 0
                var mul = 1
                for (part in lt.split(":").reversed()) {
                    d += (part.toIntOrNull() ?: 0) * mul
                    mul *= 60
                }
                if (d > 1500) continue
                val id = v.optString("videoId")
                val title = StringBuilder()
                val runs = v.obj("title").arr("runs")
                if (runs != null) for (k in 0 until runs.length()) {
                    title.append(runs.optJSONObject(k)?.optString("text", "") ?: "")
                }
                val artist = (v.obj("ownerText").arr("runs")?.optJSONObject(0)?.optString("text", "") ?: "")
                    .removeSuffix(" - Topic")
                out.put(
                    JSONObject().put("id", id).put("title", title.toString()).put("artist", artist)
                        .put("dur", d).put("cover", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
                )
            }
        }
        return out
    }

    private fun itunes(q: String): JSONArray {
        val term = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val r = Http.request("https://itunes.apple.com/search?entity=song&limit=25&term=$term")
        val res = JSONObject(r.text()).getJSONArray("results")
        val out = JSONArray()
        for (i in 0 until res.length()) {
            val x = res.getJSONObject(i)
            val preview = x.optString("previewUrl")
            if (preview.isEmpty()) continue
            out.put(
                JSONObject().put("id", "it" + x.optLong("trackId"))
                    .put("title", x.optString("trackName")).put("artist", x.optString("artistName"))
                    .put("dur", 30)
                    .put("cover", x.optString("artworkUrl100").replace("100x100", "600x600"))
                    .put("src", preview)
            )
        }
        return out
    }

    /** Pemanasan cache beranda tiap ~11 menit (padanan warm() di Node). */
    fun startWarmup() {
        if (warmStarted) return
        warmStarted = true
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ocalix-warm").apply { isDaemon = true } }
        ex.scheduleWithFixedDelay({
            try {
                for (q in HOME) {
                    try { search(q) } catch (e: Exception) { /* abaikan */ }
                    Thread.sleep(800)
                }
            } catch (e: Exception) { /* abaikan */ }
        }, 0, 660, TimeUnit.SECONDS)
    }
}

/* ===================== RESOLVE AUDIO ===================== */

class Stream(val u: String, val ua: String, val m: String, val t: Long, val s: String)

object Resolver {
    private class VClient(val n: Int, val ua: String, val ctx: JSONObject) {
        val name: String get() = ctx.getString("clientName")
    }

    private val VC = listOf(
        VClient(
            101,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15",
            JSONObject().put("clientName", "VISIONOS").put("clientVersion", "1.02")
                .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("osName", "visionOS").put("osVersion", "26.5.23O471")
        ),
        VClient(
            3,
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.38")
                .put("androidSdkVersion", 30).put("osName", "Android").put("osVersion", "11")
        )
    )

    private val PURL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$YT_KEY"

    private val PS0 = listOf(
        "https://pipedapi.kavin.rocks", "https://pipedapi.leptons.xyz", "https://pipedapi.nosebs.ru",
        "https://pipedapi-libre.kavin.rocks", "https://piped-api.privacy.com.de", "https://pipedapi.adminforge.de",
        "https://api.piped.yt", "https://pipedapi.drgns.space", "https://pipedapi.owo.si",
        "https://pipedapi.ducks.party", "https://piped-api.codespace.cz", "https://pipedapi.reallyaweso.me",
        "https://api.piped.private.coffee", "https://pipedapi.darkness.services", "https://pipedapi.orangenet.cc"
    )

    private val ac = Lru<String, Stream>(300)
    private val whyMap = Lru<String, String>(100)
    private val neg = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap<String, FutureTask<Stream?>>()
    @Volatile private var vcSkip = 0L
    @Volatile private var goodP = ""
    @Volatile private var lastPE = ""
    @Volatile private var plT = 0L
    @Volatile private var plL: List<String> = emptyList()

    fun why(id: String): String = whyMap[id] ?: "server belum sempat mencoba"
    fun invalidate(id: String) = ac.remove(id)

    fun resolve(id: String, fresh: Boolean): Stream? {
        val now = System.currentTimeMillis()
        val c = ac[id]
        if (!fresh && c != null && now - c.t < 7_200_000) return c
        if (!fresh && (neg[id] ?: 0L) > now) return null
        val task = FutureTask(Callable<Stream?> { doResolve(id) })
        val running = inflight.putIfAbsent(id, task)
        if (running != null) return running.get()
        try {
            task.run()
            return task.get()
        } finally {
            inflight.remove(id)
        }
    }

    private fun player(v: VClient, id: String): JSONObject {
        val client = JSONObject().put("hl", "en").put("gl", "US")
        for (k in v.ctx.keys()) client.put(k, v.ctx.get(k))
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true)
            .toString()
        val r = Http.request(
            PURL,
            mapOf(
                "User-Agent" to v.ua,
                "Origin" to "https://www.youtube.com",
                "X-YouTube-Client-Name" to v.n.toString(),
                "X-YouTube-Client-Version" to v.ctx.getString("clientVersion")
            ),
            body
        )
        return JSONObject(r.text())
    }

    private fun pick(j: JSONObject): JSONObject? {
        val arr = j.obj("streamingData").arr("adaptiveFormats") ?: return null
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            if (x.optString("url").isNotEmpty() && x.optString("mimeType").startsWith("audio/")) list.add(x)
        }
        return list.sortedBy { x ->
            if (x.optInt("itag") == 140) 0 else if (x.optString("mimeType").contains("mp4")) 1 else 2
        }.firstOrNull()
    }

    private fun okh(url: String): Boolean = try {
        (URI(url).host ?: "").endsWith(".googlevideo.com")
    } catch (e: Exception) {
        false
    }

    private val PRIVATE_HOST = Regex("""^(localhost$|\d+\.\d+\.\d+\.\d+$|\[)""")

    private fun okp(u: String): Boolean = try {
        val x = URI(u)
        x.scheme == "https" && !PRIVATE_HOST.containsMatchIn(x.host ?: "localhost")
    } catch (e: Exception) {
        false
    }

    private fun doResolve(id: String): Stream? {
        var o: Stream? = null
        val w = ArrayList<String>()
        if (System.currentTimeMillis() > vcSkip) {
            for (v in VC) {
                try {
                    val j = player(v, id)
                    val f = pick(j)
                    if (f != null && okh(f.optString("url"))) {
                        o = Stream(
                            f.getString("url"), v.ua, f.getString("mimeType").split(";")[0],
                            System.currentTimeMillis(), v.name
                        )
                        break
                    }
                    val ps = j.optJSONObject("playabilityStatus")
                    val status = ps?.optString("status").orEmpty().ifEmpty { "?" }
                    val reason = ps?.optString("reason").orEmpty()
                    w.add("${v.name} $status" + (if (reason.isNotEmpty()) " (${reason.take(45)})" else ""))
                    Log.d(TAG, "[audio] ${v.name} $status $reason")
                } catch (e: Exception) {
                    w.add("${v.name} ${e.message}")
                    Log.d(TAG, "[audio] ${v.name} ${e.message}")
                }
            }
            if (o != null) {
                vcSkip = 0
            } else {
                vcSkip = System.currentTimeMillis() + 300_000
                Log.d(TAG, "[audio] YouTube langsung gagal, coba Piped")
            }
        } else {
            w.add("YouTube dilewati (baru gagal)")
        }
        if (o == null) {
            o = try { piped(id) } catch (e: Exception) { null }
            if (o != null) {
                Log.d(TAG, "[audio] via Piped ${o.s}")
            } else {
                w.add("Piped: $lastPE")
                Log.d(TAG, "[audio] semua jalur gagal")
            }
        }
        whyMap.put(id, if (o != null) "ok" else w.joinToString(" | "))
        if (o != null) {
            ac.put(id, o)
        } else {
            if (neg.size > 500) neg.clear()
            neg[id] = System.currentTimeMillis() + 60_000
        }
        return o
    }

    /* ---------- Piped (jalur cadangan) ---------- */

    private fun plist(): List<String> {
        if (System.currentTimeMillis() - plT < 3_600_000 && plL.isNotEmpty()) return plL
        var d: List<String> = emptyList()
        try {
            val r = Http.request("https://raw.githubusercontent.com/TeamPiped/Documentation/main/content/docs/public-instances/index.md")
            val strip = Regex("[|\\s]")
            d = Regex("""\|\s*https://[^\s|]+\s*\|""").findAll(r.text()).map { it.value.replace(strip, "") }.toList()
        } catch (e: Exception) { /* pakai daftar bawaan */ }
        val l = (Config.PIPED + (if (goodP.isNotEmpty()) listOf(goodP) else emptyList()) + d + PS0).distinct()
        plT = System.currentTimeMillis()
        plL = l
        return l
    }

    private fun pipedStream(b: String, id: String): Stream {
        val r = Http.request("$b/streams/$id", mapOf("Accept" to "application/json"), null, 6000)
        if (r.code != 200) throw Exception("http ${r.code}")
        val j = try { JSONObject(r.text()) } catch (e: Exception) { throw Exception("diblokir/bukan JSON") }
        val arr = j.optJSONArray("audioStreams") ?: JSONArray()
        val cands = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val url = x.optString("url")
            if (url.isNotEmpty() && !x.optBoolean("videoOnly") &&
                x.optString("mimeType").contains("mp4") && okp(url)
            ) cands.add(x)
        }
        val f = cands.minByOrNull { x ->
            val br = x.optLong("bitrate")
            if (br > 192_000) 1e9 else -br.toDouble()
        } ?: throw Exception("tanpa stream audio")
        return Stream(f.getString("url"), Http.UA, "audio/mp4", System.currentTimeMillis(), b)
    }

    private fun piped(id: String): Stream? {
        if (!Config.PIPED_ENABLED) return null
        val l = plist()
        val pe = HashMap<String, Int>()
        var i = 0
        while (i < l.size && i < 8) {
            val batch = l.subList(i, minOf(i + 4, l.size))
            val tasks: List<() -> Stream?> = batch.map { b ->
                {
                    try {
                        pipedStream(b, id)
                    } catch (e: Exception) {
                        val k = e.message ?: "error"
                        synchronized(pe) { pe[k] = (pe[k] ?: 0) + 1 }
                        null
                    }
                }
            }
            val o = firstNonNull(tasks)
            if (o != null) {
                goodP = o.s
                return o
            }
            i += 4
        }
        lastPE = pe.entries.joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "tidak ada instance" }
        return null
    }

    /* ---------- Debug (/api/audio?dbg=1&id=...) ---------- */

    private fun probe(url: String, ua: String): String = try {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Range", "bytes=2000000-2000099").build()
        Http.client.newCall(req).execute().use { it.code.toString() }
    } catch (e: Exception) {
        e.message ?: "error"
    }

    fun debug(id: String): JSONObject {
        val lines = Collections.synchronizedList(ArrayList<String>())
        var ok = false
        for (v in VC) {
            try {
                val j = player(v, id)
                val ps = j.optJSONObject("playabilityStatus")
                val fa = j.obj("streamingData").arr("adaptiveFormats")
                var total = 0
                var withUrl = 0
                if (fa != null) for (i in 0 until fa.length()) {
                    val x = fa.optJSONObject(i) ?: continue
                    if (x.optString("mimeType").startsWith("audio/")) {
                        total++
                        if (x.optString("url").isNotEmpty()) withUrl++
                    }
                }
                val status = ps?.optString("status").orEmpty().ifEmpty { "?" }
                val reason = ps?.optString("reason").orEmpty()
                var s = "${v.name}: $status" + (if (reason.isNotEmpty()) " ($reason)" else "") +
                    " | audio $total, ber-URL $withUrl"
                val p = pick(j)
                if (p != null && okh(p.optString("url"))) {
                    val q = probe(p.getString("url"), v.ua)
                    s += " | tes byte setelah 1MB: $q"
                    if (q == "206" || q == "200") ok = true
                }
                lines.add(s)
            } catch (e: Exception) {
                lines.add("${v.name}: error ${e.message}")
            }
        }
        if (Config.PIPED_ENABLED) {
            val l = plist().take(8)
            if (l.isNotEmpty()) {
                val pool = Executors.newFixedThreadPool(l.size)
                try {
                    val jobs = l.map { b ->
                        Callable<Stream?> {
                            val label = b.removePrefix("https://")
                            try {
                                val v = pipedStream(b, id)
                                lines.add("Piped $label: OK")
                                v
                            } catch (e: Exception) {
                                lines.add("Piped $label: ${e.message}")
                                null
                            }
                        }
                    }
                    val pv = pool.invokeAll(jobs).mapNotNull { f -> try { f.get() } catch (e: Exception) { null } }.firstOrNull()
                    if (pv != null) {
                        val q = probe(pv.u, Http.UA)
                        lines.add("Piped tes byte: $q")
                        if (q == "206" || q == "200") ok = true
                    }
                } finally {
                    pool.shutdownNow()
                }
            }
        }
        return JSONObject().put("ok", ok).put("lines", JSONArray(lines))
    }
}
