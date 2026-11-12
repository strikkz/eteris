package lt.eteris.radio

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.CopyOnWriteArrayList

/** One stream of a station (a station can have several: different bitrates, codecs, servers). */
data class Variant(
    val url: String,
    val codec: String,
    val bitrate: Int,
    val hls: Boolean,
    val ok: Boolean,
)

data class Station(
    val id: String,
    val name: String,
    val favicon: String?,
    /** Sorted best first: highest quality that works. */
    val variants: List<Variant>,
    val prio: Int,
    val votes: Long,
) {
    val qualityLabel: String
        get() {
            val v = variants.first()
            val codec = v.codec.ifEmpty { if (v.hls) "HLS" else "" }
            return listOf(codec, if (v.bitrate > 0) "${v.bitrate} kbps" else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
        }

    fun toMediaItem(variantIndex: Int = 0): MediaItem {
        val v = variants.getOrElse(variantIndex) { variants.first() }
        // Only the title: Android Auto would otherwise print the name twice (title + artist).
        val meta = MediaMetadata.Builder()
            .setTitle(name)
            .setStation(name)
            .setArtworkUri(Art.uri(id))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(id)
            .setUri(v.url)
            .setMediaMetadata(meta)
        if (v.hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }
}

object StationRepository {

    /** The best-known Lithuanian stations go first, in this order. */
    private val PRIORITY = listOf(
        "LRT Radijas", "LRT Klasika", "LRT Opus", "M-1", "Lietus", "M-1 Plius", "Power Hit Radio",
        "Radiocentras", "ZIP FM", "Rock FM", "Žinių radijas", "Relax FM", "European Hit Radio",
        "Gold FM", "Kelyje", "TOP FM", "Laluna", "Pūkas", "Extra FM", "Saulės radijas",
        "Marijos radijas", "Vilnius FM", "Easy FM", "Russkoje Radio Baltija", "Radio Kaunas",
        "LRT Lituanica",
    )
    private val prioKeys by lazy { PRIORITY.map(::norm) }

    /** Open station catalog (radio-browser.info), several mirrors. */
    private val SERVERS = listOf("de1", "de2", "fi1", "at1", "nl1")
        .map { "https://$it.api.radio-browser.info" }
    private const val CATALOG_PATH =
        "/json/stations/bycountrycodeexact/LT?hidebroken=true&order=clickcount&reverse=true&limit=400"
    const val USER_AGENT = "Eteris/1.0 (Android)"

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var stations: List<Station> = emptyList(); private set
    @Volatile private var byIdMap: Map<String, Station> = emptyMap()
    @Volatile private var netLoaded = false
    @Volatile private var refreshing = false
    @Volatile private var lastNetTry = 0L

    fun byId(id: String?): Station? = id?.let { byIdMap[it] }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Returns stations at once (from cache if needed) and refreshes from the internet in the background. */
    suspend fun ensureLoaded(ctx: Context): List<Station> {
        val app = ctx.applicationContext
        mutex.withLock {
            if (stations.isEmpty()) withContext(Dispatchers.IO) {
                val cached = runCatching { cacheFile(app).readText() }.getOrNull()
                    ?.let { runCatching { parse(it) }.getOrNull() }
                if (!cached.isNullOrEmpty()) {
                    publish(cached)
                } else {
                    lastNetTry = SystemClock.elapsedRealtime()
                    val raw = fetchCatalog()
                    val parsed = raw?.let { runCatching { parse(it) }.getOrNull() }
                    if (raw != null && !parsed.isNullOrEmpty()) {
                        runCatching { cacheFile(app).writeText(raw) }
                        publish(parsed)
                        netLoaded = true
                    } else {
                        publish(fallback())
                    }
                }
            }
            Unit
        }
        if (!netLoaded) refreshInBackground(app)
        return stations
    }

    private fun refreshInBackground(app: Context) {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (refreshing || (lastNetTry != 0L && now - lastNetTry < 60_000)) return
            refreshing = true
            lastNetTry = now
        }
        scope.launch {
            try {
                val raw = fetchCatalog() ?: return@launch
                val parsed = parse(raw)
                if (parsed.isNotEmpty()) {
                    runCatching { cacheFile(app).writeText(raw) }
                    publish(parsed)
                    netLoaded = true
                    listeners.forEach { runCatching { it() } }
                }
            } catch (_: Exception) {
            } finally {
                refreshing = false
            }
        }
    }

    private fun publish(list: List<Station>) {
        stations = list
        byIdMap = list.associateBy { it.id }
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "stations.json")

    private fun fetchCatalog(): String? {
        for (base in SERVERS) {
            try {
                val conn = URL(base + CATALOG_PATH).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        if (text.trimStart().startsWith("[")) return text
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Same station in several qualities ("M-1 AAC", "M-1 128 kbps") gets one key and one card. */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        t = t.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        t = t.replace(Regex("""\b\d+\s*k(bps|b/s)?\b"""), " ")
        t = t.replace(
            Regex("""\b(lt|lietuva|radijas|radio|fm|online|aac\+?|aacp|he-?aac|mp3|ogg|flac|hq|hd|high|low|stream)\b"""),
            " ",
        )
        val key = t.replace(Regex("[^a-z0-9]+"), "")
        // Names in other alphabets (e.g. Cyrillic) would become empty: keep their letters instead.
        val k = key.ifEmpty { s.lowercase().filter { it.isLetterOrDigit() } }
        return ALIASES[k] ?: k
    }

    /** The same station listed in the catalog under another name. */
    private val ALIASES = mapOf(
        "powerhitradiolithuania" to "powerhit",
        "zipfm" to "zip",
        "radijostotism1" to "m1",
        "radiostotislaluna" to "laluna",
        "radijostotisradio3" to "3",
        "radijostotiskelyje" to "kelyje",
        "goldfm" to "gold",
        "pmrworldmusic" to "pmr",
        "puko" to "pukas",
    )

    /** Streams that are wrongly listed as Lithuanian (e.g. Estonian Power Hit Radio on the Levira server). */
    private val BLOCKED_STREAMS = listOf(
        Regex("leviracloud\\.eu/phr", RegexOption.IGNORE_CASE),
    )

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "").trim()

    private class Group(val key: String, val name: String) {
        var favicon: String? = null
        var votes = 0L
        val variants = mutableListOf<Variant>()
    }

    private fun parse(raw: String): List<Station> {
        val arr = JSONArray(raw)
        val groups = LinkedHashMap<String, Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.str("url_resolved").ifEmpty { o.str("url") }
            if (!url.startsWith("http")) continue
            if (BLOCKED_STREAMS.any { it.containsMatchIn(url) }) continue
            val name = o.str("name")
            val key = norm(name)
            if (key.isEmpty()) continue
            // The catalog sometimes has junk values ("UNKNOWN", 3667 kbps for adaptive streams).
            var codec = o.str("codec").uppercase()
            if (codec == "UNKNOWN") codec = ""
            var br = o.optInt("bitrate", 0)
            if (br > 512) br = 0
            if (br == 0) br = bitrateFromUrl(url)
            val hls = o.optInt("hls", 0) == 1 || url.contains(".m3u8", ignoreCase = true)
            val ok = o.optInt("lastcheckok", 1) == 1

            val g = groups.getOrPut(key) { Group(key, name) }
            g.votes += o.optLong("clickcount", 0)
            if (g.favicon == null) o.str("favicon").takeIf { it.startsWith("http") }?.let { g.favicon = it }
            if (g.variants.none { it.url == url }) g.variants += Variant(url, codec, br, hls, ok)
        }
        return groups.values.map { g ->
            val p = prioKeys.indexOf(g.key)
            Station(
                id = g.key,
                name = if (p >= 0) PRIORITY[p] else g.name,
                favicon = g.favicon,
                variants = g.variants.sortedBy(::score),
                prio = if (p < 0) 999 else p,
                votes = g.votes,
            )
        }.sortedWith(compareBy<Station> { it.prio }.thenByDescending { it.votes })
    }

    /** Many stream addresses contain the bitrate ("phr192-mp3", "rc128.mp3"). */
    private fun bitrateFromUrl(url: String): Int =
        Regex("""(?<!\d)(48|56|64|96|112|128|160|192|256|320)(?!\d)""")
            .find(url.substringAfter("://").substringAfter('/'))?.value?.toInt() ?: 0

    /** Lower is better: working streams first, then the highest bitrate; at equal bitrate AAC beats MP3. */
    private fun score(v: Variant): Double {
        val effective = if (v.bitrate > 0) v.bitrate else if (v.hls) 192 else 96
        val codecBonus = when {
            "FLAC" in v.codec -> 60
            "AAC" in v.codec || "OPUS" in v.codec -> 20
            else -> 0
        }
        return (if (v.ok) 0.0 else 100000.0) - effective * 10.0 - codecBonus - (if (v.url.startsWith("https")) 1 else 0)
    }

    /** Used only if the catalog is unreachable and there is no saved copy yet. */
    private fun fallback(): List<Station> = listOf(
        "M-1" to "m1", "Lietus" to "lietus", "M-1 Plius" to "m1plius",
    ).mapIndexed { i, (name, path) ->
        Station(
            id = norm(name), name = name, favicon = null,
            variants = listOf(
                Variant("https://stream.m-1.fm/$path/aacp64", "AAC", 64, false, true),
                Variant("http://stream.m-1.fm/$path/aacp64", "AAC", 64, false, true),
            ),
            prio = i, votes = 0,
        )
    }

    // ---------- Favorites and last station ----------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("eteris", Context.MODE_PRIVATE)

    fun favorites(ctx: Context): Set<String> = prefs(ctx).getStringSet("favs", emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(ctx: Context, id: String) {
        val set = favorites(ctx).toMutableSet()
        if (!set.remove(id)) set.add(id)
        prefs(ctx).edit().putStringSet("favs", set).apply()
        listeners.forEach { runCatching { it() } }
    }

    fun lastId(ctx: Context): String? = prefs(ctx).getString("last", null)

    /** Remembers the station and when it was played (favorites are shown most recent first). */
    fun setLast(ctx: Context, id: String) {
        val history = JSONObject(prefs(ctx).getString("played", null) ?: "{}")
        history.put(id, System.currentTimeMillis())
        prefs(ctx).edit().putString("last", id).putString("played", history.toString()).apply()
    }

    private fun playedAt(ctx: Context): Map<String, Long> {
        val o = runCatching { JSONObject(prefs(ctx).getString("played", null) ?: "{}") }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    /** Favorites, most recently played first. */
    fun favoritesByRecent(ctx: Context): List<Station> {
        val favs = favorites(ctx)
        val played = playedAt(ctx)
        return stations.filter { it.id in favs }.sortedByDescending { played[it.id] ?: 0L }
    }

    /** Recently played stations (any), newest first. */
    fun recent(ctx: Context): List<Station> {
        val played = playedAt(ctx)
        return stations.filter { it.id in played }.sortedByDescending { played[it.id] ?: 0L }
    }

    fun search(query: String): List<Station> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val q = norm(raw)
        return stations.filter { it.id.contains(q) || it.name.contains(raw, ignoreCase = true) }
    }

    fun initials(name: String): String =
        name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "FM" }
}
