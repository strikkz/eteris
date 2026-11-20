package lt.eteris.radio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Xml
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A podcast from the Apple Podcasts directory (free, open, no key). */
data class Podcast(
    val id: Long,
    val title: String,
    val author: String,
    val artwork: String,
    val feedUrl: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("artwork", artwork); put("feed", feedUrl)
    }

    companion object {
        fun fromJson(o: JSONObject) = Podcast(
            o.getLong("id"), o.getString("title"), o.optString("author"), o.optString("artwork"), o.getString("feed"),
        )
    }
}

/** One episode from a podcast's RSS feed. */
data class Episode(
    /** "ep:<podcast>:<hash>" - the "ep:" prefix tells the player this is not a live station. */
    val id: String,
    val podcastId: Long,
    val podcastTitle: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    /** Publication time, ms since 1970 (0 if unknown). */
    val date: Long,
    /** Length from the feed, ms (0 if unknown; the player then finds it out). */
    val durationMs: Long,
    val image: String,
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(audioUrl)
        // lrt.lt recordings come as HLS playlists
        .setMimeType(if (audioUrl.contains(".m3u8", ignoreCase = true)) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(audioUrl)).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(podcastTitle)
                .setAlbumTitle(podcastTitle)
                // Through the app's picture provider: Android Auto cannot load web addresses itself.
                .setArtworkUri(Art.uri(PodcastRepository.COVER_PREFIX + podcastId))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build(),
        )
        .build()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("pid", podcastId); put("ptitle", podcastTitle); put("title", title)
        put("desc", description.take(1500)); put("url", audioUrl); put("date", date); put("dur", durationMs); put("img", image)
    }

    companion object {
        fun fromJson(o: JSONObject) = Episode(
            o.getString("id"), o.optLong("pid"), o.optString("ptitle"), o.optString("title"), o.optString("desc"),
            o.getString("url"), o.optLong("date"), o.optLong("dur"), o.optString("img"),
        )
    }
}

/** Where an episode was left off. */
data class Progress(val positionMs: Long, val durationMs: Long, val done: Boolean, val at: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Podcasts: Apple's directory for finding them (top chart of Lithuania, search, LRT shows),
 * each podcast's own RSS feed for the episodes. Subscriptions and listening progress stay on the phone.
 */
object PodcastRepository {

    const val EPISODE_PREFIX = "ep:"
    /** Picture id of a podcast cover in [Art] ("pc-<podcast id>"). */
    const val COVER_PREFIX = "pc-"

    /** The cover picture address of a podcast (from the directory, or from one of its episodes). */
    fun coverUrl(pictureId: String): String? {
        val pid = pictureId.removePrefix(COVER_PREFIX).toLongOrNull() ?: return null
        return podcasts[pid]?.artwork?.takeIf { it.isNotEmpty() }
            ?: episodes.values.firstOrNull { it.podcastId == pid && it.image.isNotEmpty() }?.image
    }

    fun coverName(pictureId: String): String? {
        val pid = pictureId.removePrefix(COVER_PREFIX).toLongOrNull() ?: return null
        return podcasts[pid]?.title ?: episodes.values.firstOrNull { it.podcastId == pid }?.podcastTitle
    }
    fun isEpisode(id: String?) = id?.startsWith(EPISODE_PREFIX) == true

    private const val COUNTRY = "lt"
    private const val UA = "BANGA/2.0 (Android; podcasts)"

    /** Every episode seen this session (and the saved ones), so the player can find them by id. */
    private val episodes = ConcurrentHashMap<String, Episode>()
    private val podcasts = ConcurrentHashMap<Long, Podcast>()
    private val feedCache = ConcurrentHashMap<Long, Pair<Long, List<Episode>>>()
    private val listCache = ConcurrentHashMap<String, Pair<Long, List<Podcast>>>()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    fun episode(id: String?): Episode? = id?.let { episodes[it] }
    fun podcast(id: Long): Podcast? = podcasts[id]

    // ---------- Directory ----------

    /** Most listened podcasts in Lithuania (Apple's chart). */
    suspend fun topLithuania(): List<Podcast>? = cached("top") {
        val text = get("https://itunes.apple.com/$COUNTRY/rss/toppodcasts/limit=50/json") ?: return@cached null
        val entries = runCatching { JSONObject(text).getJSONObject("feed").optJSONArray("entry") }.getOrNull()
            ?: return@cached emptyList()
        val ids = (0 until entries.length()).mapNotNull {
            entries.getJSONObject(it).optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.toLongOrNull()
        }
        lookup(ids)
    }

    /** LRT's own podcasts. */
    suspend fun lrt(): List<Podcast>? = cached("lrt") {
        search("LRT", limit = 100)?.filter { it.author.equals("LRT", ignoreCase = true) }
    }

    /** Search the whole directory (shows from Lithuania come first when they match). */
    suspend fun search(query: String, limit: Int = 50): List<Podcast>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return cached("s:" + q.lowercase()) {
            val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&country=$COUNTRY&limit=$limit&term=" +
                URLEncoder.encode(q, "UTF-8")
            parseDirectory(get(url) ?: return@cached null)
        }
    }

    private suspend fun lookup(ids: List<Long>): List<Podcast>? {
        if (ids.isEmpty()) return emptyList()
        val text = get("https://itunes.apple.com/lookup?entity=podcast&country=$COUNTRY&id=" + ids.joinToString(","))
            ?: return null
        val found = parseDirectory(text).associateBy { it.id }
        return ids.mapNotNull { found[it] }   // keep the chart order
    }

    private fun parseDirectory(text: String): List<Podcast> {
        val arr = runCatching { JSONObject(text).getJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val feed = o.optString("feedUrl")
            if (feed.isEmpty()) return@mapNotNull null
            val p = Podcast(
                id = o.optLong("collectionId"),
                title = o.optString("collectionName").ifEmpty { o.optString("trackName") },
                author = o.optString("artistName"),
                artwork = o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") },
                feedUrl = feed,
            )
            podcasts[p.id] = p
            p
        }
    }

    private suspend fun cached(key: String, load: suspend () -> List<Podcast>?): List<Podcast>? {
        listCache[key]?.let { (at, list) -> if (SystemClock.elapsedRealtime() - at < 30 * 60_000) return list }
        val list = withContext(Dispatchers.IO) { load() } ?: return null
        listCache[key] = SystemClock.elapsedRealtime() to list
        return list
    }

    // ---------- Episodes (RSS) ----------

    /** Episodes of a podcast, newest first. Null if the feed could not be loaded. */
    suspend fun episodes(p: Podcast, force: Boolean = false): List<Episode>? {
        podcasts[p.id] = p
        if (!force) feedCache[p.id]?.let { (at, list) -> if (SystemClock.elapsedRealtime() - at < 15 * 60_000) return list }
        val feed = withContext(Dispatchers.IO) {
            val text = get(p.feedUrl) ?: return@withContext null
            runCatching { parseFeed(p, text) }.getOrNull()
        }
        // LRT sometimes stops updating a show's podcast feed while new episodes keep appearing on lrt.lt:
        // the newer ones are taken from LRT's own site.
        val fromSite = if (p.author.equals("LRT", ignoreCase = true)) {
            withContext(Dispatchers.IO) { runCatching { lrtNewer(p, feed.orEmpty()) }.getOrDefault(emptyList()) }
        } else emptyList()
        if (feed == null && fromSite.isEmpty()) return null
        val list = (fromSite + feed.orEmpty()).sortedByDescending { it.date }
        list.forEach { episodes[it.id] = it }
        feedCache[p.id] = SystemClock.elapsedRealtime() to list
        return list
    }

    // ---------- LRT's own site (lrt.lt radioteka) ----------

    private val lrtCategories = ConcurrentHashMap<Long, Long>()

    /** LRT's show (category) for a podcast, found by its name. */
    private fun lrtCategory(p: Podcast): Long? {
        lrtCategories[p.id]?.let { return it.takeIf { id -> id > 0 } }
        val text = get("https://www.lrt.lt/api/search?count=1&q=" + URLEncoder.encode(p.title, "UTF-8")) ?: return null
        val cats = JSONObject(text).optJSONArray("similar_categories") ?: JSONArray()
        val want = p.title.trim().lowercase()
        var found = 0L
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            if (c.optInt("is_audio") != 1) continue
            val t = c.optString("category_title").trim().lowercase()
            if (t == want) { found = c.optLong("category_id"); break }
        }
        lrtCategories[p.id] = found
        return found.takeIf { it > 0 }
    }

    private val lrtDate = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("Europe/Vilnius")
    }

    /** Episodes on lrt.lt that are newer than the podcast feed's newest one. */
    private suspend fun lrtNewer(p: Podcast, feed: List<Episode>): List<Episode> = coroutineScope {
        val cat = lrtCategory(p) ?: return@coroutineScope emptyList()
        val text = get("https://www.lrt.lt/api/search?count=20&order=desc&category_id=$cat") ?: return@coroutineScope emptyList()
        val items = JSONObject(text).optJSONArray("items") ?: return@coroutineScope emptyList()
        val newest = feed.maxOfOrNull { it.date } ?: 0L
        val titles = feed.map { it.title.trim().lowercase() }.toSet()
        val fresh = (0 until items.length()).map { items.getJSONObject(it) }.filter { o ->
            val d = runCatching { lrtDate.parse(o.optString("item_date"))!!.time }.getOrDefault(0L)
            o.optInt("is_audio") == 1 && d > newest + 30 * 60_000 && o.optString("title").trim().lowercase() !in titles
        }.take(12)
        fresh.map { o -> async { lrtEpisode(p, o.optString("url")) } }.awaitAll().filterNotNull()
    }

    /** One lrt.lt recording: its audio address, date and text. */
    private fun lrtEpisode(p: Podcast, path: String): Episode? {
        if (path.isEmpty()) return null
        val text = get("https://www.lrt.lt/servisai/stream_url/vod/media_info/?url=" + URLEncoder.encode(path, "UTF-8")) ?: return null
        val o = JSONObject(text)
        val item = o.optJSONObject("playlist_item") ?: return null
        val file = item.optString("file").ifEmpty { return null }
        val date = runCatching { lrtDate.parse(o.optString("date"))!!.time }.getOrDefault(0L)
        val img = item.optString("image").takeIf { it.isNotEmpty() }?.let { if (it.startsWith("http")) it else "https://www.lrt.lt$it" }
        return Episode(
            id = EPISODE_PREFIX + p.id + ":lrt" + (item.optString("mediaid").ifEmpty { o.optString("id") }),
            podcastId = p.id, podcastTitle = p.title,
            title = o.optString("title").ifEmpty { item.optString("title") }.trim(),
            description = plain(o.optString("content")),
            audioUrl = file, date = date, durationMs = 0L,
            image = p.artwork.ifEmpty { img.orEmpty() },
        )
    }

    /** Newest episodes from all subscribed podcasts together. */
    suspend fun newFromSubscriptions(ctx: Context, limit: Int = 30): List<Episode> = coroutineScope {
        subscriptions(ctx).map { p -> async { episodes(p).orEmpty().take(10) } }.awaitAll()
            .flatten().sortedByDescending { it.date }.take(limit)
    }

    private fun parseFeed(p: Podcast, text: String): List<Episode> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(text.reader())
        val out = ArrayList<Episode>()
        var channelImage = p.artwork
        var inItem = false
        var title = ""; var desc = ""; var url = ""; var guid = ""; var date = 0L; var dur = 0L; var img = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "item" -> {
                        inItem = true
                        title = ""; desc = ""; url = ""; guid = ""; date = 0L; dur = 0L; img = ""
                    }
                    name == "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        if (inItem) img = href else if (href.isNotEmpty()) channelImage = href
                    }
                    !inItem -> Unit
                    name == "enclosure" -> {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val u = parser.getAttributeValue(null, "url").orEmpty()
                        if (url.isEmpty() && u.isNotEmpty() && (type.isEmpty() || type.startsWith("audio") || u.contains(".mp3") || u.contains(".m4a"))) url = u
                    }
                    name == "title" -> title = text(parser)
                    name == "guid" -> guid = text(parser)
                    name == "pubDate" -> date = parseDate(text(parser))
                    name == "itunes:duration" -> dur = parseDuration(text(parser))
                    name == "description" || name == "itunes:summary" || name == "content:encoded" -> {
                        val d = text(parser)
                        if (d.length > desc.length) desc = d
                    }
                }
                XmlPullParser.END_TAG -> if (name == "item") {
                    inItem = false
                    if (url.isNotEmpty() && title.isNotEmpty()) {
                        out += Episode(
                            id = EPISODE_PREFIX + p.id + ":" + hash(guid.ifEmpty { url }),
                            podcastId = p.id, podcastTitle = p.title, title = title.trim(),
                            description = plain(desc), audioUrl = url, date = date, durationMs = dur,
                            image = img.ifEmpty { channelImage },
                        )
                    }
                }
            }
            event = parser.next()
        }
        return out.sortedByDescending { it.date }
    }

    private fun text(parser: XmlPullParser): String = runCatching { parser.nextText() }.getOrDefault("")

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    private fun parseDate(s: String): Long {
        val t = s.trim()
        for (f in dateFormats) {
            runCatching { return SimpleDateFormat(f, Locale.ENGLISH).parse(t)!!.time }
        }
        return 0L
    }

    /** "1:02:03", "62:03" or "3723" (seconds). */
    private fun parseDuration(s: String): Long {
        val parts = s.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0L }
        val sec = parts.fold(0.0) { acc, v -> acc * 60 + v }
        return (sec * 1000).toLong()
    }

    /** Feed descriptions are HTML: keep the words and line breaks only. */
    private fun plain(html: String): String {
        var t = html.replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n")
        t = t.replace(Regex("<[^>]+>"), "")
        t = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun get(url: String): String? {
        var target = url
        repeat(5) {
            try {
                val conn = URL(target).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", UA)
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        target = URL(URL(target), conn.getHeaderField("Location") ?: return null).toString()
                        return@repeat
                    }
                    if (code != 200) return null
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    // ---------- Subscriptions ----------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("podcasts", Context.MODE_PRIVATE)

    fun subscriptions(ctx: Context): List<Podcast> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("subs", "[]"))
        (0 until arr.length()).map { Podcast.fromJson(arr.getJSONObject(it)).also { p -> podcasts[p.id] = p } }
    }.getOrDefault(emptyList())

    fun isSubscribed(ctx: Context, id: Long) = subscriptions(ctx).any { it.id == id }

    fun toggleSubscription(ctx: Context, p: Podcast) {
        val list = subscriptions(ctx).toMutableList()
        if (list.removeAll { it.id == p.id }.not()) list.add(0, p)
        prefs(ctx).edit().putString("subs", JSONArray(list.map { it.toJson() }).toString()).apply()
        changed()
    }

    // ---------- Listening progress ----------

    private var progressCache: MutableMap<String, Progress>? = null

    private fun progressMap(ctx: Context): MutableMap<String, Progress> {
        progressCache?.let { return it }
        val map = HashMap<String, Progress>()
        runCatching {
            val o = JSONObject(prefs(ctx).getString("progress", "{}"))
            o.keys().forEach { k ->
                val p = o.getJSONObject(k)
                map[k] = Progress(p.optLong("pos"), p.optLong("dur"), p.optBoolean("done"), p.optLong("at"))
                p.optJSONObject("ep")?.let { e -> runCatching { Episode.fromJson(e) }.getOrNull()?.let { episodes.putIfAbsent(it.id, it) } }
            }
        }
        progressCache = map
        return map
    }

    fun progress(ctx: Context, id: String): Progress? = progressMap(ctx)[id]

    /** Where to start: the saved place, a few seconds back, or the beginning if it was finished. */
    fun startPosition(ctx: Context, id: String): Long {
        val p = progress(ctx, id) ?: return 0L
        return if (p.done || p.positionMs < 15_000) 0L else (p.positionMs - 5_000).coerceAtLeast(0L)
    }

    @Synchronized
    fun saveProgress(ctx: Context, id: String, positionMs: Long, durationMs: Long, ended: Boolean = false) {
        val map = progressMap(ctx)
        val dur = if (durationMs > 0) durationMs else episodes[id]?.durationMs ?: 0L
        val done = ended || (dur > 0 && positionMs > dur - 60_000 && positionMs > dur * 0.95)
        map[id] = Progress(if (done) dur else positionMs, dur, done, System.currentTimeMillis())
        // Keep the last 300 only.
        if (map.size > 300) map.entries.sortedBy { it.value.at }.take(map.size - 300).forEach { map.remove(it.key) }
        val o = JSONObject()
        map.forEach { (k, p) ->
            o.put(k, JSONObject().apply {
                put("pos", p.positionMs); put("dur", p.durationMs); put("done", p.done); put("at", p.at)
                // Unfinished ones keep the episode itself, so "continue listening" works after a restart.
                if (!p.done) episodes[k]?.let { put("ep", it.toJson()) }
            })
        }
        prefs(ctx).edit().putString("progress", o.toString()).apply()
        changed()
    }

    fun markPlayed(ctx: Context, id: String, played: Boolean) {
        val dur = episodes[id]?.durationMs ?: progress(ctx, id)?.durationMs ?: 0L
        if (played) saveProgress(ctx, id, dur, dur, ended = true) else saveProgress(ctx, id, 0L, dur)
    }

    /** Started, not finished episodes - most recent first. */
    fun continueListening(ctx: Context, limit: Int = 10): List<Pair<Episode, Progress>> =
        progressMap(ctx).entries
            .filter { !it.value.done && it.value.positionMs > 15_000 }
            .sortedByDescending { it.value.at }
            .mapNotNull { e -> episodes[e.key]?.let { it to e.value } }
            .take(limit)
}
