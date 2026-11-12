package lt.eteris.radio.car

import android.content.ComponentName
import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import lt.eteris.radio.RadioService
import lt.eteris.radio.Station
import lt.eteris.radio.StationRepository

/**
 * BANGA on the Android Auto screen, built from Car App Library templates
 * (sections with logo tiles instead of the plain media browser).
 * Playback itself is still RadioService: the car only sends commands to it.
 */
class BangaCarAppService : CarAppService() {
    // Sideloaded app for personal use: accept the Android Auto host on this phone.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = BangaCarSession()
}

class BangaCarSession : Session() {

    private var future: ListenableFuture<MediaController>? = null
    val controller: MediaController?
        get() = future?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }

    /** Screens redraw when the playing station or favorites change. */
    private val listeners = mutableListOf<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners += l }
    fun removeOnChange(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { runCatching { it() } }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = changed()
        override fun onIsPlayingChanged(isPlaying: Boolean) = changed()
    }
    private val repoListener: () -> Unit = { ContextCompat.getMainExecutor(carContext).execute { changed() } }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                StationRepository.addListener(repoListener)
                // Connecting starts RadioService; then its session is registered for the car's playback bar.
                val token = SessionToken(carContext, ComponentName(carContext, RadioService::class.java))
                val f = MediaController.Builder(carContext, token).buildAsync()
                future = f
                f.addListener({
                    controller?.addListener(playerListener)
                    registerToken()
                    changed()
                }, ContextCompat.getMainExecutor(carContext))
            }

            override fun onDestroy(owner: LifecycleOwner) {
                StationRepository.removeListener(repoListener)
                controller?.removeListener(playerListener)
                future?.let { MediaController.releaseFuture(it) }
                future = null
            }
        })
    }

    private fun registerToken() {
        val token = RadioService.sessionCompatToken ?: return
        runCatching {
            carContext.getCarService(MediaPlaybackManager::class.java).registerMediaPlaybackToken(token)
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        if (intent.action == SHOW_MEDIA_PLAYBACK) {
            carContext.getCarService(ScreenManager::class.java).push(CarHomeScreen(carContext, this))
            return CarPlaybackScreen(carContext, this)
        }
        return CarHomeScreen(carContext, this)
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == SHOW_MEDIA_PLAYBACK) {
            val sm = carContext.getCarService(ScreenManager::class.java)
            if (sm.top !is CarPlaybackScreen) sm.push(CarPlaybackScreen(carContext, this))
        }
    }

    // ---------- commands from the car screens ----------

    val currentId: String?
        get() = controller?.currentMediaItem?.mediaId ?: StationRepository.lastId(carContext)

    /** Plays [list] from [index]; the steering-wheel next/previous then move through this list. */
    fun play(list: List<Station>, index: Int) {
        val c = controller ?: return
        if (index !in list.indices) return
        c.setMediaItems(list.map { MediaItem.Builder().setMediaId(it.id).build() }, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun toggleFavorite(id: String) = StationRepository.toggleFavorite(carContext, id)

    companion object {
        const val SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
