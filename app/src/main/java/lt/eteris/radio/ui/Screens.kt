package lt.eteris.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.os.ConfigurationCompat
import lt.eteris.radio.AppState
import lt.eteris.radio.Genre
import lt.eteris.radio.PlayStatus
import lt.eteris.radio.R
import lt.eteris.radio.Station
import lt.eteris.radio.StationRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SIDE = 22.dp
/** Room at the bottom of every list for the mini-player and navigation bar. */
val DOCK_SPACE = 170.dp

@Composable
private fun TopLine(title: String, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Wordmark(title)
        // Settings: two tuning sliders (lime knob), no circle around them.
        val label = stringResource(R.string.settings)
        Box(
            Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onSettings)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_tune), null, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Home (Pradžia)

@Composable
fun HomeScreen(app: AppState, onSettings: () -> Unit, onGenre: (Genre) -> Unit, onOpenPlayer: () -> Unit) {
    val stations = app.stations
    val popular = remember(stations) { stations.filter { it.prio < 999 }.ifEmpty { stations.take(20) } }
    val last = app.current
    val hero = last ?: popular.firstOrNull()
    val genres = remember(stations) { Genre.entries.filter { g -> stations.any { g in it.genres } } }
    val recent = app.recent.take(12)
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: java.util.Locale.getDefault()
    val date = remember(locale) {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)).replaceFirstChar { it.titlecase(locale) }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = DOCK_SPACE),
    ) {
        item { TopLine("banga", onSettings) }
        if (!app.online) item { OfflineNote() }
        item {
            Text(date, style = Banga.body, color = Banga.textSecondary, modifier = Modifier.padding(top = 28.dp, bottom = 6.dp))
            DisplayTitle(stringResource(R.string.home_title_1), stringResource(R.string.home_title_2))
        }
        if (hero != null) {
            item {
                SectionHeader(stringResource(if (last != null) R.string.continue_listening else R.string.recommended))
                androidx.compose.animation.Crossfade(hero, animationSpec = androidx.compose.animation.core.tween(420), label = "hero") { hero ->
                HeroCard(
                    st = hero,
                    isCurrent = hero.id == app.currentId,
                    status = app.status,
                    song = app.song.takeIf { hero.id == app.currentId },
                    onPlay = {
                        if (hero.id == app.currentId && app.status != PlayStatus.IDLE) app.toggle() else app.playStation(hero.id)
                    },
                    onOpen = {
                        if (hero.id != app.currentId || app.status == PlayStatus.IDLE) app.playStation(hero.id)
                        onOpenPlayer()
                    },
                )
                }
            }
        } else if (stations.isEmpty()) {
            item { LoadingCard() }
        }
        if (genres.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.by_mood))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres) { g -> GenreChip(g, false) { onGenre(g) } }
                }
            }
        }
        if (recent.size > 1) {
            item {
                SectionHeader(stringResource(R.string.recent))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(recent) { i, st ->
                        StationTile(st, st.id == app.currentId && app.isActive) { app.play(recent, i) }
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.popular)) }
        itemsIndexed(popular, key = { _, st -> "p_" + st.id }) { i, st ->
            StationRow(
                st, st.id == app.currentId, app.status, app.song, st.id in app.favorites,
                onClick = { app.play(popular, i) },
                onFavorite = { app.toggleFavorite(st.id) },
            )
        }
    }
}

/** "Šiandien rekomenduojame" / "Tęsk klausymą" card: station colours, wave ring, big name, play button. */
@Composable
private fun HeroCard(st: Station, isCurrent: Boolean, status: PlayStatus, song: String?, onPlay: () -> Unit, onOpen: () -> Unit) {
    val c = rememberColors(st)
    Box(
        Modifier
            .fillMaxWidth()
            .height(292.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(c.light, c.mid, c.dark, Color(0xFF0B191C))))
            .clickable(onClick = onOpen),
    ) {
        WaveRing(c.light.copy(alpha = 0.85f), Modifier.size(300.dp).align(Alignment.CenterEnd).padding(start = 60.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6001310)))))
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LivePill()
            StationArt(st, 52.dp, corner = 14.dp)
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                val longest = st.name.split(' ').maxOf { it.length }
                val size = when {
                    longest <= 8 -> 38.sp
                    longest <= 10 -> 32.sp
                    longest <= 13 -> 27.sp
                    else -> 22.sp
                }
                Text(
                    st.name.uppercase(),
                    style = Banga.featured.copy(fontSize = size, lineHeight = size * 1.0f),
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val genre = st.genres.firstOrNull()?.let { stringResource(it.label) }
                Text(
                    (if (isCurrent) song else null) ?: genre ?: stringResource(R.string.live),
                    style = Banga.supporting, color = Color(0xFFD3E0D5), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            PlayButton(if (isCurrent) status else PlayStatus.IDLE, 58.dp, onPlay)
        }
    }
}

@Composable
private fun OfflineNote() {
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF2A1F1E))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Banga.error))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.offline), style = Banga.supporting, color = Banga.text)
    }
}

/** Placeholder while the station list loads for the very first time. */
@Composable
private fun LoadingCard() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
    val a by t.animateFloat(
        0.35f, 0.7f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "a",
    )
    Column(Modifier.padding(top = 26.dp)) {
        Box(Modifier.fillMaxWidth().height(292.dp).clip(RoundedCornerShape(28.dp)).background(Banga.surface.copy(alpha = a)))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.loading), style = Banga.supporting, color = Banga.textSecondary)
    }
}

// ---------------------------------------------------------------- Explore (Atrasti)

@Composable
fun ExploreScreen(
    app: AppState,
    query: String,
    onQuery: (String) -> Unit,
    genre: Genre?,
    onGenre: (Genre?) -> Unit,
    onSettings: () -> Unit,
) {
    val stations = app.stations
    val genres = remember(stations) { Genre.entries.map { g -> g to stations.count { g in it.genres } }.filter { it.second > 0 } }
    val results = remember(stations, query, genre) {
        val q = query.trim()
        val key = StationRepository.norm(q)
        stations.filter { st ->
            (genre == null || genre in st.genres) &&
                (q.isEmpty() || st.id.contains(key) || st.name.contains(q, ignoreCase = true) || st.tags.contains(q.lowercase()))
        }
    }
    val browsing = query.isBlank() && genre == null

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = DOCK_SPACE),
    ) {
        item { TopLine(stringResource(R.string.nav_explore).lowercase(), onSettings) }
        item {
            Spacer(Modifier.height(26.dp))
            DisplayTitle(stringResource(R.string.explore_title_1), stringResource(R.string.explore_title_2))
            SearchField(query, onQuery)
        }
        if (!browsing) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(genres) { (g, _) -> GenreChip(g, g == genre) { onGenre(if (g == genre) null else g) } }
                }
            }
            if (results.isEmpty()) {
                item {
                    EmptyBox(stringResource(R.string.no_results), query) {
                        Pill(stringResource(R.string.clear_search), { onQuery(""); onGenre(null) })
                    }
                }
            }
            itemsIndexed(results, key = { _, st -> "r_" + st.id }) { i, st ->
                StationRow(
                    st, st.id == app.currentId, app.status, app.song, st.id in app.favorites,
                    onClick = { app.play(results, i) },
                    onFavorite = { app.toggleFavorite(st.id) },
                )
            }
        } else {
            item { SectionHeader(stringResource(R.string.genres_moods)) }
            items(genres.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (g, n) -> GenreTile(g, n, { onGenre(g) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { SectionHeader(stringResource(R.string.all_stations)) }
            itemsIndexed(stations, key = { _, st -> "a_" + st.id }) { i, st ->
                StationRow(
                    st, st.id == app.currentId, app.status, app.song, st.id in app.favorites,
                    onClick = { app.play(stations, i) },
                    onFavorite = { app.toggleFavorite(st.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .padding(vertical = 22.dp)
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Color(0xFF1B1D1E))
            .border(1.dp, Color(0xFF363838), RoundedCornerShape(17.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = Color(0xFF969A9A), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = Banga.body, color = Color(0xFF747979))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = Banga.body.copy(color = Banga.text),
                cursorBrush = SolidColor(Banga.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).clickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_search), tint = Banga.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Favorites (Mėgstami)

@Composable
fun FavoritesScreen(app: AppState, onSettings: () -> Unit, onExplore: () -> Unit) {
    val favs = remember(app.stations, app.favorites, app.recent) { app.favoriteStations() }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = DOCK_SPACE),
    ) {
        item { TopLine(stringResource(R.string.nav_favorites).lowercase(), onSettings) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.nav_favorites), style = Banga.screenTitle, color = Banga.text)
                Text(stringResource(R.string.saved_count, favs.size), style = Banga.supporting, color = Color(0xFF858989))
            }
        }
        if (favs.isEmpty()) {
            item {
                EmptyBox(stringResource(R.string.saved_empty), stringResource(R.string.saved_empty_hint)) {
                    Pill(stringResource(R.string.go_explore), onExplore, accent = true)
                }
            }
        }
        itemsIndexed(favs, key = { _, st -> "f_" + st.id }) { i, st ->
            StationRow(
                st, st.id == app.currentId, app.status, app.song, true,
                onClick = { app.play(favs, i) },
                onFavorite = { app.toggleFavorite(st.id) },
            )
        }
    }
}

// ---------------------------------------------------------------- Mini player + navigation

@Composable
fun MiniPlayer(app: AppState, onOpen: () -> Unit) {
    val st = app.current ?: return
    val sub = when (app.status) {
        PlayStatus.CONNECTING -> stringResource(R.string.connecting)
        PlayStatus.BUFFERING -> stringResource(R.string.buffering)
        PlayStatus.ERROR -> stringResource(if (app.online) R.string.stream_unavailable else R.string.offline)
        PlayStatus.PAUSED, PlayStatus.IDLE -> stringResource(R.string.paused)
        PlayStatus.PLAYING -> app.song ?: stringResource(R.string.live)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0xFF26292A))
            .border(1.dp, Color(0xFF383B3B), RoundedCornerShape(19.dp))
            .clickable(onClick = onOpen)
            .padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArt(st, 48.dp, corner = 12.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = Banga.supporting, fontWeight = FontWeight.Bold, color = Banga.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub,
                fontSize = 11.sp,
                color = if (app.status == PlayStatus.ERROR) Banga.error else Color(0xFFADAFAE),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (app.status == PlayStatus.ERROR) {
            Pill(stringResource(R.string.retry), app::retry)
        } else {
            PlayButton(app.status, 48.dp, app::toggle, filled = false)
        }
    }
}

enum class Tab(val label: Int, val icon: Int) {
    HOME(R.string.nav_home, R.drawable.ic_home),
    EXPLORE(R.string.nav_explore, R.drawable.ic_search),
    FAVORITES(R.string.nav_favorites, R.drawable.ic_heart_border),
}

@Composable
fun NavBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1A1C1D))
            .border(1.dp, Color(0xFF2D2F30), RoundedCornerShape(22.dp)),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTab(t) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(t.icon), null, tint = if (on) Banga.accent else Color(0xFF848889), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(t.label), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (on) Banga.accent else Color(0xFF848889))
            }
        }
    }
}
