package lt.eteris.radio

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** What the player is doing, as shown on screen. "Playing" only after the player really plays. */
enum class PlayStatus { IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, ERROR }

/** Phone settings (DataStore is not needed for three switches). */
object Settings {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("eteris", Context.MODE_PRIVATE)
    fun autoplay(ctx: Context) = prefs(ctx).getBoolean("autoplay", false)
    fun setAutoplay(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoplay", on).apply()
    fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("wifi_only", false)
    fun setWifiOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wifi_only", on).apply()
}

/**
 * Everything the phone screens show: the station list, favorites and what is playing.
 * Playback itself stays in [RadioService]; the screens control it through a MediaController,
 * so the phone, the notification, headphones and Android Auto always agree.
 */
class AppState(private val ctx: Context) {

    var stations by mutableStateOf(StationRepository.stations)
        private set
    var favorites by mutableStateOf(StationRepository.favorites(ctx))
        private set
    var recent by mutableStateOf(StationRepository.recent(ctx))
        private set

    var currentId by mutableStateOf(StationRepository.lastId(ctx))
        private set
    var song by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf(PlayStatus.IDLE)
        private set
    /** Short message shown at the bottom (e.g. "Wi-Fi only is on"). */
    var notice by mutableStateOf<String?>(null)
    /** Whether the phone has internet right now. */
    var online by mutableStateOf(true)
        private set

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = setOnline(true)
        override fun onLost(network: android.net.Network) = setOnline(isOnlineNow())
    }

    private fun setOnline(value: Boolean) = ContextCompat.getMainExecutor(ctx).execute { online = value }

    private fun isOnlineNow(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private var future: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = future?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }

    /** True once the current station has really started playing (to tell "connecting" from "buffering"). */
    private var startedId: String? = null

    private val repoListener: () -> Unit = {
        ContextCompat.getMainExecutor(ctx).execute { refreshLists() }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    val current: Station? get() = StationRepository.byId(currentId)

    /** Favorites, most recently played first. */
    fun favoriteStations(): List<Station> = StationRepository.favoritesByRecent(ctx)

    val versionName: String
        get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

    fun refreshLists() {
        stations = StationRepository.stations
        favorites = StationRepository.favorites(ctx)
        recent = StationRepository.recent(ctx)
    }

    fun start(onReady: (AppState) -> Unit = {}) {
        StationRepository.addListener(repoListener)
        online = isOnlineNow()
        runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback) }
        val token = SessionToken(ctx, ComponentName(ctx, RadioService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            controller?.addListener(playerListener)
            sync()
            onReady(this)
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) }
        StationRepository.removeListener(repoListener)
        controller?.removeListener(playerListener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private fun sync() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: StationRepository.lastId(ctx)
        if (id != currentId) startedId = null
        currentId = id
        if (c.isPlaying) startedId = id

        val st = StationRepository.byId(id)
        val title = c.mediaMetadata.title?.toString()?.trim().orEmpty()
        song = title.takeIf {
            st != null && c.currentMediaItem?.mediaId == st.id && it.isNotEmpty() &&
                !it.equals(st.name, ignoreCase = true) && StationRepository.norm(it) != st.id
        }

        status = when {
            c.playerError != null -> PlayStatus.ERROR
            c.isPlaying -> PlayStatus.PLAYING
            c.playWhenReady && c.mediaItemCount > 0 ->
                if (startedId == id) PlayStatus.BUFFERING else PlayStatus.CONNECTING
            c.mediaItemCount > 0 -> PlayStatus.PAUSED
            else -> PlayStatus.IDLE
        }
        recent = StationRepository.recent(ctx)
    }

    val isActive: Boolean
        get() = status == PlayStatus.PLAYING || status == PlayStatus.CONNECTING || status == PlayStatus.BUFFERING

    private fun allowedToPlay(): Boolean {
        if (!Settings.wifiOnly(ctx)) return true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        if (!unmetered) notice = ctx.getString(R.string.wifi_blocked)
        return unmetered
    }

    /** Plays [list] starting at [index]; "next/previous" then move through this same list. */
    fun play(list: List<Station>, index: Int) {
        val c = controller ?: return
        if (index !in list.indices || !allowedToPlay()) return
        if (c.currentMediaItem?.mediaId == list[index].id && c.mediaItemCount == list.size && c.playerError == null) {
            if (!c.isPlaying) c.play()
            return
        }
        c.setMediaItems(list.map { MediaItem.Builder().setMediaId(it.id).build() }, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    /** Plays one station; the service builds the list around it (favorites if it is one, otherwise all). */
    fun playStation(id: String) {
        val c = controller ?: return
        if (!allowedToPlay()) return
        c.setMediaItem(MediaItem.Builder().setMediaId(id).build())
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        when {
            isActive -> c.pause()
            c.mediaItemCount == 0 -> currentId?.let { playStation(it) } ?: stations.firstOrNull()?.let { playStation(it.id) }
            else -> {
                if (!allowedToPlay()) return
                if (c.playbackState == Player.STATE_IDLE || c.playerError != null) c.prepare()
                c.play()
            }
        }
    }

    fun retry() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: currentId ?: return
        if (c.mediaItemCount > 0) {
            if (!allowedToPlay()) return
            c.prepare(); c.play()
        } else playStation(id)
    }

    fun next() = controller?.seekToNext() ?: Unit
    fun previous() = controller?.seekToPrevious() ?: Unit

    fun toggleFavorite(id: String) {
        StationRepository.toggleFavorite(ctx, id)
        favorites = StationRepository.favorites(ctx)
    }

    /** "Play last station" setting: start it when the app opens, unless something is already playing. */
    fun autoplayIfWanted() {
        val c = controller ?: return
        if (!Settings.autoplay(ctx) || c.isPlaying || c.playWhenReady) return
        StationRepository.lastId(ctx)?.let { playStation(it) }
    }
}
