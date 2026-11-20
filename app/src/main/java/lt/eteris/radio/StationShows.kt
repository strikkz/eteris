package lt.eteris.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows of Lithuanian radio stations that publish recordings on their own sites (not as podcasts):
 * each station's shows become "podcasts" here, so following, progress and the car work the same way.
 *
 * Feed addresses inside the app tell where a show comes from:
 *   bal:<id>      radijas.lt (Radiocentras, Relax FM, Radio R...), its public app API
 *   zr:<path>     Žinių radijas show page
 *   mr:<filter>   Marijos radijas recordings filtered by show
 *   gold:<slug>   Gold FM show
 * Some recordings' audio address is only on the episode's own page; such episodes carry "lazy:..."
 * and the player asks [resolveAudio] right before playing.
 */
object StationShows {

    /** A station with its shows, as a tile in the Podcasts tab. [logo] is the station id for its logo. */
    data class Group(val key: String, val name: String, val logo: String)

    val groups = listOf(
        Group("powerhit", "Power Hit Radio", "powerhit"),
        Group("ziniu", "Žinių radijas", "ziniu"),
        Group("radiocentras", "Radiocentras", "radiocentras"),
        Group("relax", "Relax FM", "relax"),
        Group("radior", "Radio R", "r"),
        Group("marijos", "Marijos radijas", "marijos"),
        Group("gold", "Gold FM", "gold"),
        Group("lrt", "LRT", "lrt"),
    )

    /** The shows of one station. */
    suspend fun shows(key: String): List<Podcast>? = when (key) {
        "powerhit" -> powerHit()
        "ziniu" -> ziniu()
        "radiocentras", "relax", "radior" -> radijasLt(key)
        "marijos" -> marijos()
        "gold" -> gold()
        "lrt" -> PodcastRepository.lrt()
        else -> null
    }

    /** All shows found so far (for search). */
    val known: Collection<Podcast> get() = loaded.values.flatten()
    private val loaded = ConcurrentHashMap<String, List<Podcast>>()

    /** Loads every station's shows once (in the background), so search can find them. */
    suspend fun loadAll() = coroutineScope {
        groups.map { g -> async { runCatching { shows(g.key) } } }.awaitAll()
        Unit
    }

    private fun remember(key: String, list: List<Podcast>?): List<Podcast>? {
        list?.let { loaded[key] = it; it.forEach { p -> PodcastRepository.podcasts[p.id] = p } }
        return list
    }

    /** Stable negative ids, so they never clash with Apple's podcast ids. */
    private fun idOf(source: String, key: String) = -(2_000_000_000L + ((source + ":" + key).hashCode().toLong() and 0x7fffffff))

    private val vilnius: TimeZone = TimeZone.getTimeZone("Europe/Vilnius")
    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = vilnius }

    // ---------- Power Hit Radio: schedule-based hours + the guests' podcast ----------

    private suspend fun powerHit(): List<Podcast>? {
        val shows = PodcastRepository.powerHitShows()
        // "Power Hit Radio svečiai": interviews with the guests, published as a podcast.
        val guests = runCatching { PodcastRepository.lookupOne(1821842843L) }.getOrNull()
        return remember("powerhit", listOfNotNull(guests) + shows.orEmpty()).takeIf { shows != null || guests != null }
    }

    // ---------- radijas.lt (Radiocentras, Relax FM, Radio R) ----------

    private const val BAL = "https://production.brandsarelive.com/api/v1.3/app/198"
    @Volatile private var balToken: String? = null

    /** Show ids on radijas.lt, by station (found in its app catalogue). */
    private val BAL_IDS = mapOf(
        "radiocentras" to listOf(55814L, 55815L, 55840L, 55841L, 55842L, 55847L, 55957L, 56092L, 56137L, 56233L),
        "relax" to listOf(55813L, 55828L, 55832L, 55833L, 55834L, 55836L, 55837L, 55838L),
        "radior" to listOf(55846L, 56286L, 55981L),
    )

    private fun balGet(path: String): String? {
        val token = balToken ?: balLogin() ?: return null
        return PodcastRepository.get(BAL + path, headers = mapOf("Authorization" to "JWT $token", "App-Language" to "lt"))
            ?: run { balToken = null; balLogin()?.let { PodcastRepository.get(BAL + path, headers = mapOf("Authorization" to "JWT $it", "App-Language" to "lt")) } }
    }

    /** radijas.lt lets any listener in as a guest; the app does the same. */
    private fun balLogin(): String? {
        val body = JSONObject().put("access_token", java.util.UUID.randomUUID().toString()).toString()
        val text = PodcastRepository.get("$BAL/social/guest/login/", post = body) ?: return null
        return runCatching { JSONObject(text).getString("access") }.getOrNull()?.also { balToken = it }
    }

    private suspend fun radijasLt(key: String): List<Podcast>? = PodcastRepository.cached("bal:$key") {
        coroutineScope {
            BAL_IDS[key].orEmpty().map { id ->
                async {
                    val o = balGet("/podcasts/$id/")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@async null
                    if (o.optString("mode") != "podcast") return@async null
                    Podcast(
                        id = idOf("bal", id.toString()),
                        title = o.optString("name").trim(),
                        author = groups.first { it.key == key }.name,
                        artwork = o.optString("square_image_url").ifEmpty { o.optString("og_image_url") },
                        feedUrl = "bal:$id",
                    ) to o.optString("updated")
                }
            }.awaitAll().filterNotNull().sortedByDescending { it.second }.map { it.first }
        }.ifEmpty { null }
    }.let { remember(key, it) }

    private val balDate = fmt("yyyy-MM-dd'T'HH:mm:ss")

    private fun balEpisodes(p: Podcast, id: String): List<Episode>? {
        val out = ArrayList<Episode>()
        for (page in 1..2) {
            val text = balGet("/podcasts/$id/episodes/?page=$page") ?: break
            val o = runCatching { JSONObject(text) }.getOrNull() ?: break
            val res = o.optJSONArray("results") ?: break
            for (i in 0 until res.length()) {
                val e = res.getJSONObject(i)
                val date = runCatching { balDate.parse(e.optString("published_date").take(19))!!.time }.getOrDefault(0L)
                out += Episode(
                    id = PodcastRepository.EPISODE_PREFIX + p.id + ":b" + e.optLong("id"),
                    podcastId = p.id, podcastTitle = p.title,
                    title = e.optString("title").trim(),
                    description = PodcastRepository.plain(e.optString("description")),
                    audioUrl = "lazy:bal:" + e.optLong("id"),
                    date = date, durationMs = (e.optDouble("duration", 0.0) * 1000).toLong(),
                    image = e.optString("cover_image_url").ifEmpty { p.artwork },
                )
            }
            if (o.isNull("next")) break
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---------- Žinių radijas ----------

    private const val ZR = "https://www.ziniuradijas.lt"
    private val LT_MONTHS = listOf("sausio", "vasario", "kovo", "balandžio", "gegužės", "birželio", "liepos", "rugpjūčio", "rugsėjo", "spalio", "lapkričio", "gruodžio")

    private suspend fun ziniu(): List<Podcast>? = PodcastRepository.cached("zr") {
        coroutineScope {
            // The archive page shows the latest episodes: those shows are on air now and go first
            // (the site also lists many shows that ended years ago).
            val active = async {
                PodcastRepository.get("$ZR/archyvas/")?.let { h ->
                    Regex("class=\"episode-name\"><a href=\"(/laidos/[a-z0-9-]+)\"").findAll(h).map { it.groupValues[1] }.distinct().toList()
                }.orEmpty()
            }
            val all = listOf("ac", "df", "gy", "jl", "mo", "ps", "tu", "vz").map { letter ->
                async {
                    val html = PodcastRepository.get("$ZR/laidos?letter=$letter") ?: return@async emptyList<Podcast>()
                    html.split("class=\"show-item").drop(1).mapNotNull { b ->
                        val path = Regex("href=\"(/laidos/[a-z0-9-]+)\"").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
                        val title = Regex("<h2>([^<]+)</h2>").find(b)?.groupValues?.get(1)?.let(PodcastRepository::unescape)?.trim() ?: return@mapNotNull null
                        val img = Regex("<img src=\"([^\"]+)\"").find(b)?.groupValues?.get(1)?.let { if (it.startsWith("/")) ZR + it else it }.orEmpty()
                        Podcast(idOf("zr", path), title, "Žinių radijas", img, "zr:$path")
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }
            val order = active.await()
            all.sortedBy { p -> order.indexOf(p.feedUrl.removePrefix("zr:")).let { if (it < 0) Int.MAX_VALUE else it } }
        }.ifEmpty { null }
    }.let { remember("ziniu", it) }

    /** "2026 m. lapkričio 19 d." */
    private fun ziniuDate(s: String): Long {
        val m = Regex("(\\d{4})\\s*m\\.\\s*([a-ząčęėįšųūž]+)\\s+(\\d{1,2})").find(s.lowercase()) ?: return 0L
        val month = LT_MONTHS.indexOf(m.groupValues[2]).takeIf { it >= 0 } ?: return 0L
        return java.util.Calendar.getInstance(vilnius).apply {
            clear(); set(m.groupValues[1].toInt(), month, m.groupValues[3].toInt(), 12, 0)
        }.timeInMillis
    }

    private fun ziniuEpisodes(p: Podcast, path: String): List<Episode>? {
        val html = PodcastRepository.get(ZR + path) ?: return null
        val out = ArrayList<Episode>()
        for (b in html.split("class=\"small-new").drop(1)) {
            if (!b.contains("soundtrack=1")) continue                       // video only, no sound version
            val link = Regex("href=\"($path/[a-z0-9-]+)").find(b)?.groupValues?.get(1) ?: continue
            val title = Regex("<h3>([^<]+)</h3>").find(b)?.groupValues?.get(1)?.let(PodcastRepository::unescape)?.trim() ?: continue
            val date = Regex("class=\"date\">([^<]+)<").find(b)?.groupValues?.get(1)?.let(::ziniuDate) ?: 0L
            val img = Regex("data-src=\"([^\"]+)\"").find(b)?.groupValues?.get(1)?.let { ZR + it }
            out += Episode(
                id = PodcastRepository.EPISODE_PREFIX + p.id + ":" + link.substringAfterLast('/'),
                podcastId = p.id, podcastTitle = p.title, title = title, description = "",
                audioUrl = "lazy:zr:$link", date = date, durationMs = 0L, image = img ?: p.artwork,
            )
        }
        return out.distinctBy { it.id }.takeIf { it.isNotEmpty() }
    }

    // ---------- Marijos radijas ----------

    private const val MR = "https://www.marijosradijas.lt"
    private const val MR_PAGE = "$MR/irasai/naujausi-laidu-irasai.html"
    @Volatile private var mrToken: String? = null

    /** Marijos radijas filters by show only within a session (cookie), so the app keeps cookies. */
    private fun cookies() {
        if (java.net.CookieHandler.getDefault() == null) java.net.CookieHandler.setDefault(java.net.CookieManager())
    }

    private suspend fun marijos(): List<Podcast>? = PodcastRepository.cached("mr") {
        cookies()
        val html = PodcastRepository.get(MR_PAGE) ?: return@cached null
        mrToken = Regex("var ajaxData = \\{'([0-9a-f]+)':'1'\\}").find(html)?.groupValues?.get(1)
        Regex("class=\"filter-option\" data-filter=\"(\\d+)\" data-title=\"([^\"]+)\"").findAll(html)
            .map { m ->
                val title = PodcastRepository.unescape(m.groupValues[2]).trim()
                Podcast(idOf("mr", m.groupValues[1]), title, "Marijos radijas", "", "mr:" + m.groupValues[1])
            }.distinctBy { it.id }.toList().ifEmpty { null }
    }.let { remember("marijos", it) }

    private val mrDate = fmt("yyyy-MM-dd HH:mm:ss")

    private fun marijosEpisodes(p: Podcast, filter: String): List<Episode>? {
        cookies()
        val token = mrToken ?: PodcastRepository.get(MR_PAGE)?.let { h ->
            Regex("var ajaxData = \\{'([0-9a-f]+)':'1'\\}").find(h)?.groupValues?.get(1)
        }?.also { mrToken = it } ?: return null
        val out = ArrayList<Episode>()
        for (page in 0..1) {
            val body = JSONObject().put(token, "1").put("task", "getnextpage").put("page", page).put("filter", filter).put("date", "").toString()
            val html = PodcastRepository.get(MR_PAGE, post = body) ?: break
            val cards = Regex("<span class=\"listen-btn\"([^>]+)>").findAll(html).toList()
                // only this show's recordings (if the site ignores the filter, nothing wrong is shown)
                .filter { c -> Regex("data-title=\"([^\"]*)\"").find(c.groupValues[1])?.groupValues?.get(1)?.let(PodcastRepository::unescape)?.trim().equals(p.title, ignoreCase = true) }
            if (cards.isEmpty()) break
            for (c in cards) {
                val a = c.groupValues[1]
                fun attr(n: String) = Regex("data-$n=\"([^\"]*)\"").find(a)?.groupValues?.get(1)?.let(PodcastRepository::unescape).orEmpty()
                val url = attr("audio").ifEmpty { continue }
                val date = runCatching { mrDate.parse(attr("broadcast"))!!.time }.getOrDefault(0L)
                val text = attr("text").trim()
                out += Episode(
                    id = PodcastRepository.EPISODE_PREFIX + p.id + ":" + url.substringAfterLast('/').substringBeforeLast('.'),
                    podcastId = p.id, podcastTitle = p.title,
                    title = text.lineSequence().firstOrNull()?.take(120)?.ifBlank { null } ?: attr("title"),
                    description = text,
                    audioUrl = url, date = date, durationMs = 0L,
                    image = attr("image").let { if (it.startsWith("/")) MR + it else it }.ifEmpty { p.artwork },
                )
            }
        }
        if (p.artwork.isEmpty()) out.firstOrNull()?.image?.let { img -> PodcastRepository.podcasts[p.id] = p.copy(artwork = img) }
        return out.distinctBy { it.id }.sortedByDescending { it.date }.takeIf { it.isNotEmpty() }
    }

    // ---------- Gold FM ----------

    private const val GOLD = "https://goldfm.lt"

    private suspend fun gold(): List<Podcast>? = PodcastRepository.cached("gold") {
        val html = PodcastRepository.get("$GOLD/podcasts/") ?: return@cached null
        Regex("value=\"([a-z0-9-]+)\" name=\"_sft_podcastfilter\\[\\]\"[^>]*><label[^>]*>([^<]+)</label>").findAll(html)
            .map { m -> Podcast(idOf("gold", m.groupValues[1]), PodcastRepository.unescape(m.groupValues[2]).trim(), "Gold FM", "", "gold:" + m.groupValues[1]) }
            .toList().ifEmpty { null }
    }.let { remember("gold", it) }

    private val goldDate = fmt("yyyy.MM.dd")

    private fun goldEpisodes(p: Podcast, slug: String): List<Episode>? {
        val html = PodcastRepository.get("$GOLD/filtered-podcasts/?_sft_podcastfilter=$slug") ?: return null
        val out = ArrayList<Episode>()
        for (m in Regex("<div class=\"sec2\"><a href=\"(https://goldfm\\.lt/podcast/[^\"]+)\">([^<]+)</a></div>\\s*<div class=\"sec6\">([^<]+)<").findAll(html)) {
            val (link, title, date) = m.destructured
            out += Episode(
                id = PodcastRepository.EPISODE_PREFIX + p.id + ":" + link.trimEnd('/').substringAfterLast('/').take(60),
                podcastId = p.id, podcastTitle = p.title, title = PodcastRepository.unescape(title).trim(), description = "",
                audioUrl = "lazy:gold:$link", date = runCatching { goldDate.parse(date.trim())!!.time }.getOrDefault(0L),
                durationMs = 0L, image = p.artwork,
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---------- shared ----------

    /** Episodes of a station show (null if this show is not from a station site). */
    fun episodesOf(p: Podcast): List<Episode>? {
        val f = p.feedUrl
        return when {
            f.startsWith("bal:") -> balEpisodes(p, f.removePrefix("bal:"))
            f.startsWith("zr:") -> ziniuEpisodes(p, f.removePrefix("zr:"))
            f.startsWith("mr:") -> marijosEpisodes(p, f.removePrefix("mr:"))
            f.startsWith("gold:") -> goldEpisodes(p, f.removePrefix("gold:"))
            else -> null
        }
    }

    fun isStationFeed(feed: String) = feed.startsWith("bal:") || feed.startsWith("zr:") || feed.startsWith("mr:") || feed.startsWith("gold:")

    /** The real audio address of a "lazy:" episode (looked up on the station's site). */
    suspend fun resolveAudio(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("lazy:")) return@withContext url
        val rest = url.removePrefix("lazy:")
        when {
            rest.startsWith("bal:") -> balGet("/audios/${rest.removePrefix("bal:")}/")
                ?.let { runCatching { JSONObject(it).optString("file_url") }.getOrNull() }?.ifEmpty { null }
            rest.startsWith("zr:") -> PodcastRepository.get(ZR + rest.removePrefix("zr:") + "?soundtrack=1")
                ?.let { Regex("/assets/Uploads/Episodes/Audio/[^\"']+\\.mp3").find(it)?.value }?.let { ZR + it }
            rest.startsWith("gold:") -> PodcastRepository.get(rest.removePrefix("gold:"))
                ?.let { Regex("https://goldfm\\.lt/wp-content/uploads/[^\"'\\s]+\\.mp3").find(it)?.value }
            else -> null
        }
    }
}
