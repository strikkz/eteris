package lt.eteris.radio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }
            ?.let { runCatching { it.get() }.getOrNull() }

    private lateinit var adapter: StationAdapter
    private var shown: List<Station> = emptyList()
    private var favMode = false

    private lateinit var status: TextView
    private lateinit var lcdName: TextView
    private lateinit var lcdTrack: TextView
    private lateinit var lcdMeta: TextView
    private lateinit var lcdLogo: ImageView
    private lateinit var lcdInitials: TextView
    private lateinit var search: EditText
    private lateinit var tabAll: TextView
    private lateinit var tabFav: TextView
    private lateinit var empty: TextView
    private lateinit var btnPlay: ImageButton

    private val repoListener: () -> Unit = { runOnUiThread { refreshList(); updateNowPlaying() } }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateNowPlaying()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        lcdName = findViewById(R.id.lcdName)
        lcdTrack = findViewById(R.id.lcdTrack)
        lcdMeta = findViewById(R.id.lcdMeta)
        lcdLogo = findViewById(R.id.lcdLogo)
        lcdInitials = findViewById(R.id.lcdInitials)
        search = findViewById(R.id.search)
        tabAll = findViewById(R.id.tabAll)
        tabFav = findViewById(R.id.tabFav)
        empty = findViewById(R.id.empty)
        btnPlay = findViewById(R.id.btnPlay)

        adapter = StationAdapter(
            lifecycleScope,
            onClick = { pos -> playFrom(pos) },
            onFavorite = { st -> StationRepository.toggleFavorite(this, st.id) },
        )
        val columns = (resources.configuration.screenWidthDp / 170).coerceIn(2, 5)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns)
            adapter = this@MainActivity.adapter
        }

        search.doAfterTextChanged { refreshList() }
        tabAll.setOnClickListener { favMode = false; refreshList() }
        tabFav.setOnClickListener { favMode = true; refreshList() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { controller?.seekToNext() }

        askNotificationPermission()
        StationRepository.addListener(repoListener)

        status.setText(R.string.loading)
        lifecycleScope.launch {
            StationRepository.ensureLoaded(applicationContext)
            refreshList()
            updateNowPlaying()
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller?.addListener(playerListener)
            updateNowPlaying()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        StationRepository.removeListener(repoListener)
        super.onDestroy()
    }

    private fun refreshList() {
        val all = StationRepository.stations
        val favs = StationRepository.favorites(this)
        val raw = search.text.toString().trim()
        val q = StationRepository.norm(raw)
        shown = all.filter { st ->
            (!favMode || st.id in favs) &&
                (raw.isEmpty() || st.id.contains(q) || st.name.contains(raw, ignoreCase = true))
        }
        if (all.isNotEmpty()) status.text = getString(R.string.stations_count, all.size)

        styleTab(tabAll, !favMode)
        styleTab(tabFav, favMode)
        empty.visibility = if (shown.isEmpty() && all.isNotEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (favMode) R.string.empty_favorites else R.string.empty_search)

        adapter.submit(shown, favs, currentStationId())
    }

    private fun styleTab(tab: TextView, on: Boolean) {
        tab.setBackgroundResource(if (on) R.drawable.bg_tab_on else 0)
        tab.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent else R.color.muted))
    }

    private fun currentStationId(): String? =
        controller?.currentMediaItem?.mediaId ?: StationRepository.lastId(this)

    private fun playFrom(position: Int) {
        val c = controller ?: return
        if (position !in shown.indices) return
        val items = shown.map { MediaItem.Builder().setMediaId(it.id).build() }
        c.setMediaItems(items, position, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        val active = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        when {
            active -> c.pause()
            c.mediaItemCount == 0 -> {
                val last = StationRepository.lastId(this)
                val index = shown.indexOfFirst { it.id == last }.coerceAtLeast(0)
                playFrom(index)
            }
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    private fun updateNowPlaying() {
        val c = controller
        val id = currentStationId()
        val st = StationRepository.byId(id)

        lcdName.text = st?.name ?: getString(R.string.choose_station)
        if (st != null) {
            Logos.bind(lifecycleScope, st.favicon, lcdLogo, lcdInitials, st.name)
        } else {
            lcdLogo.visibility = View.INVISIBLE
            lcdInitials.visibility = View.VISIBLE
            lcdInitials.text = "FM"
        }

        // The stream's own song title replaces the station name in the title field when it arrives.
        val title = c?.mediaMetadata?.title?.toString()?.trim().orEmpty()
        val track = title.takeIf {
            st != null && c?.currentMediaItem?.mediaId == st.id && it.isNotEmpty() &&
                !it.equals(st.name, ignoreCase = true) && StationRepository.norm(it) != st.id
        }
        lcdTrack.text = track?.let { "♪ " + it.replace(Regex("\\s+-\\s+"), " — ") }
        lcdTrack.visibility = if (track == null) View.GONE else View.VISIBLE

        lcdMeta.text = when {
            c?.playerError != null -> getString(R.string.station_error)
            st != null -> listOf(st.qualityLabel, getString(R.string.max_quality))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            else -> getString(R.string.subtitle)
        }

        val active = c != null && (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING))
        btnPlay.setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (active) R.string.pause else R.string.play)

        adapter.setCurrent(id)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
