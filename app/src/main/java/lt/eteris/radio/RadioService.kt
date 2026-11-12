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
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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
        /** Android's own session token; the Android Auto car screens register it to show the playback bar. */
        @Volatile var sessionCompatToken: android.support.v4.media.session.MediaSessionCompat.Token? = null

        const val ROOT = "root"
        const val FAV = "fav"
        const val ALL = "all"
        /** Android Auto's side card ("For you") next to navigation. */
        const val SUGGESTED = "suggested"
        /** What Android shows to resume playback. */
        const val RECENT = "recent"

        private const val AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val ACTION_FAVORITE = "lt.eteris.radio.FAVORITE"
        private val FAVORITE_COMMAND = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)

        private const val TICK_MS = 20_000L
        /** A song title that has not changed for this long means the station's server is stuck. */
        private const val STALE_MS = 10 * 60_000L
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
                listOf(ROOT, FAV, ALL, SUGGESTED, RECENT).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
            }
            updateFavoriteButton()
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
        sessionCompatToken = session?.sessionCompatToken

        StationRepository.addListener(repoListener)
        handler.postDelayed(ticker, TICK_MS)
        updateFavoriteButton()
        SleepTimer.onFire = ::fadeOutAndPause
    }

    /** Sleep timer: lower the volume over ~12 seconds, then pause and restore the volume for next time. */
    private fun fadeOutAndPause() {
        val steps = 24
        for (i in 1..steps) {
            handler.postDelayed({
                if (i < steps) player.volume = 1f - i / steps.toFloat()
                else {
                    session?.player?.pause()
                    player.volume = 1f
                }
            }, i * 500L)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * The app was swiped away in the recent-apps screen: stop the radio and close.
     * Exception: Android Auto is connected - then the car keeps playing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val s = session
        val carConnected = s?.connectedControllers?.any { it.packageName == AUTO_PACKAGE } == true
        if (s != null && carConnected && s.player.playWhenReady) return
        s?.player?.run {
            pause()
            clearMediaItems()
        }
        SleepTimer.set(0)
        stopSelf()
    }

    override fun onDestroy() {
        sessionCompatToken = null
        SleepTimer.onFire = null
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
            updateFavoriteButton()
            notifyLists()
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
            if (song.isNotEmpty() && staleTitles[st.id] == song) {          // known stuck title: hide it at once
                setSong(st, "")
                return
            }
            if (song.isNotEmpty() && staleTitles.remove(st.id) != null) saveStale()   // server sends new songs again
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
    /**
     * Titles a station's server got stuck on (Radiocentras repeated one song for days).
     * Saved, so the stuck title is hidden right away next time too, until the station sends another song.
     */
    private val staleTitles: HashMap<String, String> by lazy {
        val map = HashMap<String, String>()
        // Already known: Radiocentras' server has been repeating this title since 11 Nov 2026.
        map["radiocentras"] = "KATY PERRY, SKIP MARLEY — Chained To The Rhythm"
        runCatching {
            val o = JSONObject(getSharedPreferences("eteris", MODE_PRIVATE).getString("stale_titles", null) ?: "{}")
            o.keys().forEach { map[it] = o.getString(it) }
        }
        map
    }

    private fun saveStale() {
        getSharedPreferences("eteris", MODE_PRIVATE).edit()
            .putString("stale_titles", JSONObject(staleTitles as Map<*, *>).toString()).apply()
    }

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
            saveStale()
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
        // Song playing: title = song, second line = station. No song: only the station name (shown once).
        val meta = item.mediaMetadata.buildUpon()
            .setTitle(newTitle)
            .setArtist(if (song.isEmpty()) null else st.name)
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

    private fun folder(id: String, title: String, grid: Boolean): MediaItem {
        val style = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .setExtras(style)
                    .build(),
            )
            .build()
    }

    /** Favorites (most recently played first); if there are none yet, the best-known stations. */
    private fun favoritesOrPopular(all: List<Station>): List<Station> =
        StationRepository.favoritesByRecent(this).ifEmpty { all.filter { it.prio < 999 }.ifEmpty { all.take(20) } }

    private fun children(parentId: String, all: List<Station>): List<MediaItem> = when (parentId) {
        // Two tabs at the top of the Android Auto screen.
        ROOT -> listOf(
            folder(FAV, getString(R.string.tab_favorites), grid = true),
            folder(ALL, getString(R.string.tab_all), grid = false),
        )
        FAV -> favoritesOrPopular(all).map { it.toMediaItem() }
        ALL -> all.map { it.toMediaItem() }
        SUGGESTED -> favoritesOrPopular(all).take(6).map { it.toMediaItem() }
        RECENT -> listOfNotNull(StationRepository.byId(StationRepository.lastId(this))?.toMediaItem())
        else -> emptyList()
    }

    private fun notifyLists() {
        session?.let { s -> listOf(FAV, SUGGESTED, RECENT).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) } }
    }

    // ---------- Favorite button on the Android Auto player screen ----------

    private fun updateFavoriteButton() {
        val s = session ?: return
        val id = player.currentMediaItem?.mediaId
        val isFav = id != null && id in StationRepository.favorites(this)
        val button = CommandButton.Builder()
            .setDisplayName(getString(if (isFav) R.string.fav_remove else R.string.fav_add))
            .setIconResId(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_border)
            .setSessionCommand(FAVORITE_COMMAND)
            .build()
        s.setCustomLayout(ImmutableList.of(button))
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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(FAVORITE_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_FAVORITE) {
                player.currentMediaItem?.mediaId?.let { StationRepository.toggleFavorite(this@RadioService, it) }
                updateFavoriteButton()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootId = when {
                params?.isRecent == true -> RECENT
                params?.isSuggested == true -> SUGGESTED
                else -> ROOT
            }
            val extras = Bundle().apply {
                // Tabs as text; stations as logo tiles unless a tab says otherwise.
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
            val root = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            val out = LibraryParams.Builder()
                .setRecent(rootId == RECENT)
                .setSuggested(rootId == SUGGESTED)
                .setExtras(extras)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, out))
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

    /** The list "next/previous" (steering wheel) moves through: favorites if the station is one, otherwise all. */
    private fun queueAround(id: String?, all: List<Station>): MediaSession.MediaItemsWithStartPosition {
        val favs = StationRepository.favorites(this)
        val list = if (id != null && id in favs) all.filter { it.id in favs } else all
        val index = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        return MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, index, C.TIME_UNSET)
    }
}
