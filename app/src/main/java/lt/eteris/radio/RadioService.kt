package lt.eteris.radio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {

    companion object {
        const val ROOT = "root"
        const val FAV = "fav"
        const val TOP = "top"
        const val ALL = "all"

        // Android Auto display hints: station logos as a grid.
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2

        private const val TICK_MS = 20_000L
        /** A song title that has not changed for this long means the station's server is stuck. */
        private const val STALE_MS = 12 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null

    /** Which stream of the current station is in use (0 = best quality). */
    private val attempts = HashMap<String, Int>()
    private var retries = 0

    private val repoListener: () -> Unit = {
        scope.launch {
            session?.let { s ->
                listOf(ROOT, FAV, TOP, ALL).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(StationRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)

        // Start adaptive (HLS) streams at the highest quality; step down only if the network really can't keep up.
        val bandwidth = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(5_000_000L)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setBandwidthMeter(bandwidth)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, RadioPlayer(player), LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        StationRepository.addListener(repoListener)
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        StationRepository.removeListener(repoListener)
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Error handling: try the next stream of the same station, then retry ----------

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val st = StationRepository.byId(player.currentMediaItem?.mediaId) ?: return
            val index = player.currentMediaItemIndex
            val next = (attempts[st.id] ?: 0) + 1
            if (next < st.variants.size) {
                attempts[st.id] = next
                player.replaceMediaItem(index, st.toMediaItem(next))
                player.prepare()
                player.play()
            } else if (retries < 5) {
                // All streams failed (e.g. no signal in a tunnel): wait and start again from the best one.
                retries++
                attempts[st.id] = 0
                handler.postDelayed({
                    if (player.playerError != null && player.playWhenReady &&
                        player.currentMediaItem?.mediaId == st.id
                    ) {
                        player.replaceMediaItem(player.currentMediaItemIndex, st.toMediaItem(0))
                        player.prepare()
                        player.play()
                    }
                }, 4000L * retries)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retries = 0
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                attempts.clear()
                retries = 0
            }
            mediaItem?.mediaId?.let { StationRepository.setLast(this@RadioService, it) }
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                streamSong = null
                handler.post { tick(force = true) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) handler.post { tick(force = true) }
        }

        /**
         * The stream reports a new song (ICY "StreamTitle" or ID3 tags in HLS).
         * The station's own title in the queue would hide it, so the current item is updated:
         * title = song, artist = station. Same stream address, so playback is not interrupted.
         */
        override fun onMetadata(metadata: Metadata) {
            val st = StationRepository.byId(player.currentMediaItem?.mediaId) ?: return
            if (st.id in websiteSources) return          // these stations' streams carry no song info
            val raw = trackTitle(metadata) ?: return
            val song = cleanTitle(raw, st)
            if (song.isNotEmpty() && staleTitles[st.id] == song) return   // known stuck title
            if (song.isNotEmpty()) staleTitles.remove(st.id)              // server sends new songs again
            if (song != streamSong) {
                streamSong = song
                streamSongSince = SystemClock.elapsedRealtime()
            }
            setSong(st, song)
        }
    }

    // ---------- Song titles ----------

    /**
     * Stations whose stream has no song info, but whose website shows what is on air.
     * Found by checking the stations' sites (Power Hit Radio sends an empty title in the stream).
     */
    private val websiteSources = mapOf(
        "powerhit" to "https://powerhitradio.tv3.lt/Pwr/lastSong/",
        "powergold" to "https://powerhitradio.tv3.lt/Pwr/lastSong/?gold=1",
    )

    /** Last song from the stream and when it appeared (to notice a stuck title). */
    private var streamSong: String? = null
    private var streamSongSince = 0L
    /** Titles a station's server got stuck on (e.g. Radiocentras repeats one song for hours). */
    private val staleTitles = HashMap<String, String>()

    private val ticker = object : Runnable {
        override fun run() {
            tick(force = false)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick(force: Boolean) {
        if (!player.playWhenReady && !force) return
        val st = StationRepository.byId(player.currentMediaItem?.mediaId) ?: return

        val url = websiteSources[st.id]
        if (url != null) {
            scope.launch {
                val song = withContext(Dispatchers.IO) { fetchWebsiteSong(url) } ?: return@launch
                if (player.currentMediaItem?.mediaId == st.id) setSong(st, cleanTitle(song, st))
            }
            return
        }

        val song = streamSong
        if (!song.isNullOrEmpty() && SystemClock.elapsedRealtime() - streamSongSince > STALE_MS) {
            staleTitles[st.id] = song
            streamSong = null
            setSong(st, "")
        }
    }

    private fun fetchWebsiteSong(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        try {
            if (conn.responseCode != 200) null
            else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val artist = o.optString("artistName", "").trim()
                val title = o.optString("songTitle", "").trim()
                when {
                    artist.isNotEmpty() && title.isNotEmpty() -> "$artist - $title"
                    else -> o.optString("song", "").trim()
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Shows the song as title (artist = station) in the app, notification and Android Auto. */
    private fun setSong(st: Station, song: String) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId != st.id) return
        val newTitle = song.ifEmpty { st.name }
        if (newTitle == item.mediaMetadata.title?.toString()) return
        val meta = item.mediaMetadata.buildUpon()
            .setTitle(newTitle)
            .setArtist(st.name)
            .build()
        // Same stream address, so playback is not interrupted.
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta).build())
    }

    /** Reads the song from stream metadata; null if this metadata has no song info. */
    private fun trackTitle(metadata: Metadata): String? {
        var title: String? = null
        var artist: String? = null
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is IcyInfo -> e.title?.let { return it }
                is TextInformationFrame -> when (e.id) {
                    "TIT2" -> title = e.values.firstOrNull()
                    "TPE1" -> artist = e.values.firstOrNull()
                }
            }
        }
        if (title.isNullOrBlank()) return null
        return if (artist.isNullOrBlank()) title else "$artist - $title"
    }

    private fun cleanTitle(raw: String, st: Station): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // Some stations add a date or tag in slashes or brackets at the end, e.g. "Song /2026.10/".
        t = t.replace(Regex("\\s*/[^/]*/\\s*$"), "").trim()
        if (t.isEmpty() || Regex("^[-–—\\s.]*$").matches(t)) return ""
        if (t.equals(st.name, ignoreCase = true) || StationRepository.norm(t) == st.id) return ""
        if (Regex("^(unknown|n/a|null|undefined)$", RegexOption.IGNORE_CASE).matches(t)) return ""
        return t.replace(Regex("\\s+-\\s+"), " — ")
    }

    // ---------- Library: what Android Auto shows ----------

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build(),
            )
            .build()

    private fun children(parentId: String, all: List<Station>): List<MediaItem> {
        val favs = StationRepository.favorites(this)
        return when (parentId) {
            ROOT -> buildList {
                if (favs.isNotEmpty()) add(folder(FAV, getString(R.string.tab_favorites)))
                add(folder(TOP, getString(R.string.tab_popular)))
                add(folder(ALL, getString(R.string.tab_all)))
            }
            FAV -> all.filter { it.id in favs }.map { it.toMediaItem() }
            TOP -> all.filter { it.prio < 999 }.ifEmpty { all.take(25) }.map { it.toMediaItem() }
            ALL -> all.map { it.toMediaItem() }
            else -> emptyList()
        }
    }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE || page <= 0 && pageSize >= items.size) items
        else items.drop(page.coerceAtLeast(0) * pageSize).take(pageSize)

    /** Turns a request (an id, or a voice search like "play Power Hit Radio") into a playable station. */
    private fun resolve(item: MediaItem, all: List<Station>): MediaItem? {
        StationRepository.byId(item.mediaId)?.let { return it.toMediaItem() }
        val query = item.requestMetadata.searchQuery
        if (query != null) {
            val found = StationRepository.search(query).firstOrNull()
                ?: StationRepository.byId(StationRepository.lastId(this))
                ?: all.firstOrNull()
            return found?.toMediaItem()
        }
        return null
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putInt(STYLE_BROWSABLE, STYLE_LIST)
                putInt(STYLE_PLAYABLE, STYLE_GRID)
            }
            val root = MediaItem.Builder()
                .setMediaId(ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = StationRepository.ensureLoaded(this@RadioService)
            LibraryResult.ofItemList(paginate(children(parentId, all), page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            StationRepository.ensureLoaded(this@RadioService)
            val st = StationRepository.byId(mediaId)
            if (st != null) LibraryResult.ofItem(st.toMediaItem(), null)
            else LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            StationRepository.ensureLoaded(this@RadioService)
            session.notifySearchResultChanged(browser, query, StationRepository.search(query).size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            StationRepository.ensureLoaded(this@RadioService)
            val items = StationRepository.search(query).map { it.toMediaItem() }
            LibraryResult.ofItemList(paginate(items, page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val all = StationRepository.ensureLoaded(this@RadioService)
            mediaItems.mapNotNull { resolve(it, all) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val all = StationRepository.ensureLoaded(this@RadioService)
            val resolved = mediaItems.mapNotNull { resolve(it, all) }
            if (resolved.size > 1) {
                val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, resolved.size - 1)
                MediaSession.MediaItemsWithStartPosition(resolved, start, C.TIME_UNSET)
            } else {
                // One station chosen (e.g. in the car): queue its whole list so "next" / "previous" work.
                val id = resolved.firstOrNull()?.mediaId ?: StationRepository.lastId(this@RadioService)
                queueAround(id, all)
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val all = StationRepository.ensureLoaded(this@RadioService)
            queueAround(StationRepository.lastId(this@RadioService), all)
        }
    }

    private fun queueAround(id: String?, all: List<Station>): MediaSession.MediaItemsWithStartPosition {
        val favs = StationRepository.favorites(this)
        val list = if (id != null && id in favs) all.filter { it.id in favs } else all
        val index = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        return MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, index, C.TIME_UNSET)
    }
}
