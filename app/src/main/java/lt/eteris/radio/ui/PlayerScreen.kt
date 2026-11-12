package lt.eteris.radio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import lt.eteris.radio.About
import lt.eteris.radio.StationAbout
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lt.eteris.radio.AppState
import lt.eteris.radio.PlayStatus
import lt.eteris.radio.R

/** Full-screen player: station artwork in its own colours, live label, heart, previous / play / next. */
@Composable
fun PlayerScreen(app: AppState, onClose: () -> Unit, onOtherStations: () -> Unit) {
    val st = app.current ?: return
    val c = rememberColors(st)

    // Swipe down to close: the player follows the finger; far enough or fast enough closes it.
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    // Swipe up on the logo: it slides away and the station's description slides in (only the top part moves).
    val info = remember(st.id) { Animatable(0f) }
    var columnTop by remember { mutableFloatStateOf(0f) }
    var coverBottom by remember { mutableFloatStateOf(0f) }
    var coverHeight by remember { mutableFloatStateOf(1f) }
    var mode by remember { mutableIntStateOf(0) }   // 1 = moving the description, 2 = closing the player
    val dragState = rememberDraggableState { delta ->
        scope.launch {
            if (mode == 1 && info.value <= 0f && delta > 0f) mode = 2
            if (mode == 1) info.snapTo((info.value - delta / coverHeight).coerceIn(0f, 1f))
            else drag.snapTo((drag.value + delta).coerceAtLeast(0f))
        }
    }
    val ctx = LocalContext.current
    val about = remember(st.id) { About.of(ctx, st) }

    Column(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .onGloballyPositioned { columnTop = it.positionInRoot().y }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { start -> mode = if (columnTop + start.y < coverBottom) 1 else 2 },
                onDragStopped = { velocity ->
                    if (mode == 1) {
                        val open = if (velocity < -1200f) true else if (velocity > 1200f) false else info.value > 0.5f
                        info.animateTo(if (open) 1f else 0f, tween(260))
                    } else {
                        if (drag.value > closeAt || velocity > 2200f) onClose() else drag.animateTo(0f)
                    }
                    mode = 0
                },
            )
            .blockTouchesBelow()
            // Translucent in the middle (the home screen shows through slightly), solid at the edges.
            .background(Brush.radialGradient(listOf(c.mid.copy(alpha = 0.85f), Color(0xFF16221E), Banga.bg), radius = 1400f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton48(R.drawable.ic_chevron_down, stringResource(R.string.collapse_player), onClose)
            Text(
                stringResource(R.string.now_playing).uppercase(),
                style = Banga.eyebrow,
                color = Color(0xFFC4D0C5),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.size(48.dp))
        }

        // Top part: the cover, or the station description after a swipe up. Everything below stays in place.
        Box(
            Modifier
                .padding(top = 18.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .onGloballyPositioned {
                    coverBottom = it.positionInRoot().y + it.size.height
                    coverHeight = it.size.height.toFloat().coerceAtLeast(1f)
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -info.value * size.height
                        alpha = 1f - info.value
                    }
                    .shadow(24.dp, RoundedCornerShape(32.dp))
                    .clip(RoundedCornerShape(32.dp))
                    .background(Brush.radialGradient(listOf(c.light, c.mid, c.dark))),
                contentAlignment = Alignment.Center,
            ) {
                WaveRing(Color.White.copy(alpha = 0.35f), Modifier.fillMaxSize().padding(start = 60.dp, top = 40.dp), stroke = 56.dp)
                val logo = rememberLogo(st)
                if (logo != null) {
                    Image(
                        logo, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(0.56f).aspectRatio(1f).shadow(16.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)),
                    )
                }
            }
            AboutPanel(
                st.name, about,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - info.value) * size.height
                        alpha = info.value
                    },
            )
        }
        // Hint / tap target for the description.
        Text(
            (if (info.value > 0.5f) "﹀  " else "︿  ") + stringResource(R.string.about_station),
            style = Banga.eyebrow,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { scope.launch { info.animateTo(if (info.value > 0.5f) 0f else 1f, tween(260)) } }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (app.status == PlayStatus.ERROR) Banga.error else Banga.accent))
            Text(
                "  " + stringResource(R.string.live).uppercase(),
                style = Banga.eyebrow,
                color = if (app.status == PlayStatus.ERROR) Banga.error else Banga.accent,
                modifier = Modifier.weight(1f),
            )
            HeartButton(st.id in app.favorites, { app.toggleFavorite(st.id) }, tint = Banga.text)
        }
        Text(st.name, style = Banga.screenTitle, color = Banga.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        val genre = st.genres.firstOrNull()?.let { stringResource(it.label) }
        val line = when (app.status) {
            PlayStatus.CONNECTING -> stringResource(R.string.connecting)
            PlayStatus.BUFFERING -> stringResource(R.string.buffering)
            PlayStatus.ERROR -> stringResource(R.string.stream_unavailable)
            else -> app.song ?: listOfNotNull(genre, stringResource(R.string.live)).joinToString(" · ")
        }
        Text(
            line,
            style = Banga.body,
            color = if (app.status == PlayStatus.ERROR) Banga.error else Color(0xFFB1C2B8),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton48(R.drawable.ic_prev, stringResource(R.string.previous), app::previous, big = true)
            if (app.status == PlayStatus.ERROR) {
                Pill(stringResource(R.string.retry), app::retry, accent = true)
            } else {
                PlayButton(app.status, 80.dp, app::toggle)
            }
            IconButton48(R.drawable.ic_next, stringResource(R.string.next), app::next, big = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)) {
            Pill(stringResource(R.string.other_stations), onOtherStations)
        }
    }
}

@Composable
fun IconButton48(icon: Int, description: String, onClick: () -> Unit, big: Boolean = false) {
    Box(
        Modifier
            .size(if (big) 60.dp else 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = Banga.text, modifier = Modifier.size(if (big) 32.dp else 26.dp))
    }
}

/** Overlays (player, settings) must not let taps reach the screen underneath. */
@Composable
fun Modifier.blockTouchesBelow(): Modifier = this.clickable(
    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    indication = null,
    onClick = {},
)

@Composable
private fun AboutPanel(name: String, about: StationAbout, modifier: Modifier) {
    val uri = LocalUriHandler.current
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(32.dp))
            .padding(22.dp),
    ) {
        Text(stringResource(R.string.about_station).uppercase(), style = Banga.eyebrow, color = Banga.accent)
        Spacer(Modifier.height(10.dp))
        Text(name, style = Banga.section, color = Banga.text)
        Spacer(Modifier.height(10.dp))
        // The whole text; if it is longer than the panel, it scrolls inside it.
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                about.text ?: stringResource(R.string.about_none),
                style = Banga.body,
                color = if (about.text != null) Color(0xFFD8E0DA) else Banga.textSecondary,
            )
        }
        Spacer(Modifier.height(14.dp))
        about.url?.let { url ->
            val domain = url.substringAfter("://").substringBefore('/').removePrefix("www.")
            Pill(stringResource(R.string.open_website) + " · " + domain + " ↗", { runCatching { uri.openUri(url) } }, accent = true)
        }
    }
}
