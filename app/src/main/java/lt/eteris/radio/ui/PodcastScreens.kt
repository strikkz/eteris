package lt.eteris.radio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import lt.eteris.radio.AppState
import lt.eteris.radio.Episode
import lt.eteris.radio.PlayStatus
import lt.eteris.radio.Podcast
import lt.eteris.radio.PodcastRepository
import lt.eteris.radio.Progress
import lt.eteris.radio.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------- small pieces ----------

@Composable
fun Cover(url: String?, size: Dp, corner: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(Banga.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_podcast), null, tint = Banga.textSecondary, modifier = Modifier.size(size * 0.4f))
        if (!url.isNullOrEmpty()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun dateText(ms: Long): String {
    if (ms <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun episodeMeta(ep: Episode, pr: Progress?): String {
    val parts = mutableListOf<String>()
    dateText(ep.date).takeIf { it.isNotEmpty() }?.let { parts += it }
    val dur = pr?.durationMs?.takeIf { it > 0 } ?: ep.durationMs
    when {
        pr?.done == true -> parts += stringResource(R.string.episode_done)
        pr != null && pr.positionMs > 15_000 && dur > 0 ->
            parts += stringResource(R.string.min_left, (((dur - pr.positionMs) / 60_000).coerceAtLeast(1)).toInt())
        dur > 0 -> parts += stringResource(R.string.minutes_short, ((dur + 30_000) / 60_000).toInt().coerceAtLeast(1))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Banga.accent))
    }
}

@Composable
private fun PodcastTile(p: Podcast, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Cover(p.artwork, 128.dp, corner = 18.dp)
        Spacer(Modifier.height(8.dp))
        Text(p.title, style = Banga.supporting, fontWeight = FontWeight.SemiBold, color = Banga.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(p.author, fontSize = 11.sp, color = Banga.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PodcastRow(p: Podcast, index: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("${index + 1}", style = Banga.supporting, fontWeight = FontWeight.Bold, color = Banga.textSecondary, modifier = Modifier.width(26.dp))
        }
        Cover(p.artwork, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = Banga.rowTitle, color = Banga.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.author, style = Banga.supporting, color = Banga.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Started episode card: cover, title, how much is left, progress line. */
@Composable
private fun ContinueCard(ep: Episode, pr: Progress, playing: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(272.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Banga.surface)
            .border(1.dp, if (playing) Banga.accent else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(ep.image, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ep.title, style = Banga.supporting, fontWeight = FontWeight.SemiBold, color = Banga.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ep.podcastTitle, fontSize = 11.sp, color = Banga.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        ProgressLine(pr.fraction, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(episodeMeta(ep, pr), fontSize = 11.sp, color = Banga.textSecondary)
    }
}

/** Episode in a list: date and length, title, progress; tap plays, long press marks it played. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(app: AppState, ep: Episode, showPodcast: Boolean = false) {
    val ctx = LocalContext.current
    val pr = remember(app.podcastVersion, ep.id) { PodcastRepository.progress(ctx, ep.id) }
    val isCurrent = app.currentId == ep.id
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { if (isCurrent) app.toggle() else app.playEpisode(ep) }, onLongClick = { menu = true })
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showPodcast) {
                Cover(ep.image, 52.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (showPodcast) {
                    Text(ep.podcastTitle, fontSize = 11.sp, color = Banga.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    ep.title, style = Banga.rowTitle,
                    color = if (pr?.done == true && !isCurrent) Banga.textSecondary else if (isCurrent) Banga.accent else Banga.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(episodeMeta(ep, pr), fontSize = 11.sp, color = Banga.textSecondary)
                if (pr != null && !pr.done && pr.positionMs > 15_000) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(pr.fraction, Modifier.width(120.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            val status = if (isCurrent) app.status else PlayStatus.PAUSED
            PlayButton(status, 40.dp, { if (isCurrent) app.toggle() else app.playEpisode(ep) }, filled = false)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Banga.surfaceRaised)) {
            val done = pr?.done == true
            DropdownMenuItem(
                text = { Text(stringResource(if (done) R.string.mark_unplayed else R.string.mark_played), style = Banga.rowTitle, color = Banga.text) },
                onClick = { PodcastRepository.markPlayed(ctx, ep.id, !done); menu = false },
            )
        }
    }
}

// ---------- Podcasts tab ----------

@Composable
fun PodcastsScreen(
    app: AppState,
    query: String,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenPodcast: (Podcast) -> Unit,
) {
    val ctx = LocalContext.current
    var top by remember { mutableStateOf<List<Podcast>?>(null) }
    var lrt by remember { mutableStateOf<List<Podcast>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        val t = PodcastRepository.topLithuania()
        val l = PodcastRepository.lrt()
        top = t; lrt = l
        failed = t == null && l == null
    }
    val subs = remember(app.podcastVersion) { PodcastRepository.subscriptions(ctx) }
    val cont = remember(app.podcastVersion) { PodcastRepository.continueListening(ctx) }
    var fresh by remember { mutableStateOf<List<Episode>>(emptyList()) }
    LaunchedEffect(subs.map { it.id }) { fresh = PodcastRepository.newFromSubscriptions(ctx, 12) }

    var results by remember { mutableStateOf<List<Podcast>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(450)
        searching = true
        results = PodcastRepository.search(query) ?: emptyList()
        searching = false
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = DOCK_SPACE),
    ) {
        item { TopLine(stringResource(R.string.nav_podcasts).lowercase(), onSettings) }
        item {
            Spacer(Modifier.height(26.dp))
            DisplayTitle(stringResource(R.string.podcasts_title_1), stringResource(R.string.podcasts_title_2))
            SearchField(query, onQuery, stringResource(R.string.podcast_search_hint))
        }

        if (query.isNotBlank()) {
            item { SectionHeader(if (searching && results == null) stringResource(R.string.world_searching) else stringResource(R.string.podcast_results)) }
            val r = results.orEmpty()
            if (!searching && results != null && r.isEmpty()) {
                item { EmptyBox(stringResource(R.string.no_results), query) { Pill(stringResource(R.string.clear_search), { onQuery("") }) } }
            }
            items(r, key = { "s" + it.id }) { p -> PodcastRow(p) { onOpenPodcast(p) } }
            return@LazyColumn
        }

        if (cont.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.continue_listening)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(cont, key = { it.first.id }) { (ep, pr) ->
                        ContinueCard(ep, pr, app.currentId == ep.id && app.isActive) {
                            if (app.currentId == ep.id) app.toggle() else app.playEpisode(ep)
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.my_podcasts)) }
        if (subs.isEmpty()) {
            item { Text(stringResource(R.string.podcast_empty_subs), style = Banga.supporting, color = Banga.textSecondary) }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(subs, key = { "m" + it.id }) { p -> PodcastTile(p) { onOpenPodcast(p) } }
                }
            }
            if (fresh.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.new_episodes)) }
                items(fresh.take(6), key = { "n" + it.id }) { ep -> EpisodeRow(app, ep, showPodcast = true) }
            }
        }

        if (failed) {
            item {
                EmptyBox(stringResource(R.string.podcasts_error), "") { Pill(stringResource(R.string.retry), { attempt++ }) }
            }
        }
        lrt?.takeIf { it.isNotEmpty() }?.let { list ->
            item { SectionHeader(stringResource(R.string.lrt_podcasts)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { "l" + it.id }) { p -> PodcastTile(p) { onOpenPodcast(p) } }
                }
            }
        }
        top?.takeIf { it.isNotEmpty() }?.let { list ->
            item { SectionHeader(stringResource(R.string.top_lt)) }
            items(list.size, key = { "t" + list[it].id }) { i -> PodcastRow(list[i], i) { onOpenPodcast(list[i]) } }
        }
        if (top == null && lrt == null && !failed) {
            item { Text(stringResource(R.string.loading), style = Banga.supporting, color = Banga.textSecondary, modifier = Modifier.padding(top = 20.dp)) }
        }
    }
}

// ---------- One podcast ----------

@Composable
fun PodcastPage(app: AppState, podcast: Podcast, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var episodes by remember(podcast.id) { mutableStateOf<List<Episode>?>(null) }
    var failed by remember(podcast.id) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(podcast.id, attempt) {
        val e = PodcastRepository.episodes(podcast, force = attempt > 0)
        episodes = e ?: episodes
        failed = e == null
    }
    val subscribed = remember(app.podcastVersion, podcast.id) { PodcastRepository.isSubscribed(ctx, podcast.id) }
    var shown by remember(podcast.id) { mutableIntStateOf(30) }

    LazyColumn(
        Modifier.fillMaxSize().blockTouchesBelow().background(Banga.bg).statusBarsPadding(),
        contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = DOCK_SPACE),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.podcast_eyebrow).uppercase(), style = Banga.eyebrow, color = Banga.textSecondary)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(44.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(podcast.artwork, 128.dp, corner = 22.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(podcast.title, style = Banga.section.copy(fontSize = 20.sp, lineHeight = 24.sp), color = Banga.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(podcast.author, style = Banga.supporting, color = Banga.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Pill(
                        stringResource(if (subscribed) R.string.subscribed else R.string.subscribe),
                        { PodcastRepository.toggleSubscription(ctx, podcast) },
                        accent = !subscribed, small = true,
                    )
                }
            }
        }
        val list = episodes
        item {
            SectionHeader(stringResource(R.string.episodes) + (list?.let { "  ·  ${it.size}" } ?: ""))
        }
        when {
            list == null && failed -> item {
                EmptyBox(stringResource(R.string.podcasts_error), "") { Pill(stringResource(R.string.retry), { attempt++ }) }
            }
            list == null -> item { Text(stringResource(R.string.loading), style = Banga.supporting, color = Banga.textSecondary) }
            else -> {
                items(list.take(shown), key = { it.id }) { ep -> EpisodeRow(app, ep) }
                if (list.size > shown) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            Pill(stringResource(R.string.show_more), { shown += 30 }, small = true)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Mini player and full player for an episode ----------

private fun speedLabel(sp: Float): String =
    (if (sp % 1f == 0f) "%.0f" else if ((sp * 10) % 1f == 0f) "%.1f" else "%.2f").format(Locale.US, sp) + "×"

/** Playback speed: a list to pick from, like the sleep timer. Lime when not 1×. */
@Composable
private fun SpeedButton(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val sp = app.speed
    val changed = kotlin.math.abs(sp - 1f) > 0.01f
    Box {
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(if (changed) Banga.accent else Color.White.copy(alpha = 0.09f))
                .clickable { open = true }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.speed) + "  " + speedLabel(sp),
                style = Banga.supporting, fontWeight = FontWeight.SemiBold,
                color = if (changed) Banga.onAccent else Banga.text,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Banga.surfaceRaised)) {
            listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v ->
                val on = kotlin.math.abs(v - sp) < 0.01f
                DropdownMenuItem(
                    text = { Text(speedLabel(v), style = Banga.rowTitle, color = if (on) Banga.accent else Banga.text) },
                    onClick = { app.changeSpeed(v); open = false },
                )
            }
        }
    }
}

@Composable
fun EpisodeMiniPlayer(app: AppState, ep: Episode, onOpen: () -> Unit) {
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ep.id, app.status) {
        while (true) {
            val d = app.duration()
            fraction = if (d > 0) app.position().toFloat() / d else 0f
            if (app.status != PlayStatus.PLAYING) break
            delay(1000)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0xFF26292A))
            .border(1.dp, Color(0xFF383B3B), RoundedCornerShape(19.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(start = 9.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(ep.image, 48.dp, corner = 12.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(ep.title, style = Banga.supporting, fontWeight = FontWeight.Bold, color = Banga.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (app.status) {
                        PlayStatus.CONNECTING -> stringResource(R.string.connecting)
                        PlayStatus.BUFFERING -> stringResource(R.string.buffering)
                        PlayStatus.ERROR -> stringResource(if (app.online) R.string.stream_unavailable else R.string.offline)
                        else -> ep.podcastTitle
                    },
                    fontSize = 11.sp,
                    color = if (app.status == PlayStatus.ERROR) Banga.error else Color(0xFFADAFAE),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.status == PlayStatus.ERROR) Pill(stringResource(R.string.retry), app::retry, small = true)
            else PlayButton(app.status, 48.dp, app::toggle, filled = false)
        }
        ProgressLine(fraction, Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 2.dp))
    }
}

@Composable
fun EpisodePlayerScreen(app: AppState, ep: Episode, onClose: () -> Unit, onOpenPodcast: (Long) -> Unit) {
    var position by remember { mutableLongStateOf(app.position()) }
    var duration by remember { mutableLongStateOf(app.duration()) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var expanded by remember(ep.id) { mutableStateOf(false) }
    LaunchedEffect(ep.id) {
        while (true) {
            if (dragging == null) position = app.position()
            duration = app.duration()
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .blockTouchesBelow()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF1D2A22).copy(alpha = 0.96f),
                    0.55f to Banga.bg.copy(alpha = 0.97f),
                    1f to Banga.bg,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton48(R.drawable.ic_chevron_down, stringResource(R.string.collapse_player), onClose)
                Text(
                    stringResource(R.string.podcast_eyebrow).uppercase(),
                    style = Banga.eyebrow, color = Color(0xFFC4D0C5),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.size(48.dp))
            }

            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Cover(ep.image, minOf(maxWidth, 300.dp), corner = 28.dp)
            }

            Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                Text(ep.title, style = Banga.section.copy(fontSize = 22.sp, lineHeight = 27.sp), color = Banga.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    ep.podcastTitle, style = Banga.supporting, color = Banga.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenPodcast(ep.podcastId) },
                )
            }

            // Time bar
            val dur = duration.coerceAtLeast(1L)
            val shownPos = dragging?.let { (it * dur).toLong() } ?: position
            Slider(
                value = (shownPos.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { app.seekTo((it * dur).toLong()); position = (it * dur).toLong() }
                    dragging = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Banga.accent, activeTrackColor = Banga.accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(clock(shownPos), style = Banga.supporting, color = Banga.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "−" + clock(duration - shownPos) else "", style = Banga.supporting, color = Banga.textSecondary)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton48(R.drawable.ic_replay15, stringResource(R.string.skip_back), app::skipBack, big = true)
                if (app.status == PlayStatus.ERROR) Pill(stringResource(R.string.retry), app::retry, accent = true)
                else PlayButton(app.status, 80.dp, app::toggle)
                IconButton48(R.drawable.ic_forward15, stringResource(R.string.skip_forward), app::skipForward, big = true)
            }

            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeedButton(app)
                Spacer(Modifier.weight(1f))
                SleepTimerButton()
            }

            if (ep.description.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp, bottom = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable { expanded = !expanded }
                        .padding(16.dp),
                ) {
                    Text(
                        ep.description, style = Banga.body, color = Color(0xFFD9DED8),
                        maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(if (expanded) R.string.show_less else R.string.show_more),
                        style = Banga.supporting, fontWeight = FontWeight.SemiBold, color = Banga.accent,
                    )
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
