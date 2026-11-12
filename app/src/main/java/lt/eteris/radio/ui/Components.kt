package lt.eteris.radio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.eteris.radio.Genre
import lt.eteris.radio.PlayStatus
import lt.eteris.radio.R
import lt.eteris.radio.Station

/** Station logo with rounded corners (the real logo or the generated tile). */
@Composable
fun StationArt(st: Station, size: Dp, corner: Dp = 17.dp, modifier: Modifier = Modifier) {
    val logo = rememberLogo(st)
    val colors = rememberColors(st)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(colors.mid, colors.dark))),
    ) {
        if (logo != null) {
            Image(logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** BangaWaveArt: the sculpted wave ring from the design, drawn behind hero artwork. */
@Composable
fun WaveRing(color: Color, modifier: Modifier = Modifier, stroke: Dp = 52.dp, angle: Float = -33f) {
    Canvas(modifier.rotate(angle)) {
        val w = size.width
        val h = size.height
        drawOval(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.10f),
            size = Size(w * 0.86f, h * 0.80f),
            style = Stroke(width = stroke.toPx()),
        )
    }
}

/** "● TIESIOGIAI" pill. */
@Composable
fun LivePill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Banga.accent)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Banga.onAccent))
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.live).uppercase(), style = Banga.eyebrow, color = Banga.onAccent)
    }
}

@Composable
fun Wordmark(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_banga_wave), null, tint = Banga.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                append(text)
                withStyle(SpanStyle(color = Banga.accent)) { append(".") }
            },
            color = Banga.text,
            fontSize = 23.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.5).sp,
        )
    }
}

@Composable
fun CircleButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF1C1E1F))
            .border(1.dp, Color(0xFF393A3A), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = Banga.text, modifier = Modifier.size(22.dp))
    }
}

/** Two-line screen title, second line in lime. */
@Composable
fun DisplayTitle(first: String, second: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(first, style = Banga.hero, color = Banga.text)
        Text(second, style = Banga.hero, color = Banga.accent)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = Banga.section, color = Banga.text, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                "$action ↗",
                style = Banga.supporting,
                color = Banga.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun GenreChip(genre: Genre, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(40.dp)
    Box(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) Banga.accent else Color(0xFF1C1D1F))
            .border(1.dp, if (selected) Banga.accent else Color(0xFF37393A), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(genre.label),
            style = Banga.supporting,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (selected) Banga.onAccent else Color(0xFFD4D4D4),
        )
    }
}

/** Large gradient genre tile for the Explore grid. */
@Composable
fun GenreTile(genre: Genre, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(132.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(genre.from), Color(genre.to))))
            .clickable(onClick = onClick),
    ) {
        WaveRing(Color.White.copy(alpha = 0.35f), Modifier.size(150.dp).align(Alignment.BottomEnd).padding(start = 30.dp, top = 30.dp), stroke = 22.dp, angle = 30f)
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(genre.label), color = Color.White, fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(4.dp))
            Text("$count", style = Banga.supporting, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

fun subtitleOf(st: Station, genreName: String?, liveLabel: String): String =
    listOfNotNull(genreName, st.qualityLabel.takeIf { it.isNotEmpty() } ?: liveLabel).joinToString(" · ")

/** Station row: logo, name, genre or what is playing, heart. */
@Composable
fun StationRow(
    st: Station,
    isCurrent: Boolean,
    status: PlayStatus,
    song: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val genre = st.genres.firstOrNull()?.let { stringResource(it.label) }
    val live = stringResource(R.string.live)
    val sub = when {
        isCurrent && status == PlayStatus.CONNECTING -> stringResource(R.string.connecting)
        isCurrent && status == PlayStatus.ERROR -> stringResource(R.string.stream_unavailable)
        isCurrent && status == PlayStatus.PLAYING -> song ?: stringResource(R.string.now_playing)
        else -> subtitleOf(st, genre, live)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            StationArt(st, 62.dp)
            if (isCurrent && status != PlayStatus.IDLE) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp).clip(CircleShape)
                        .background(if (status == PlayStatus.ERROR) Banga.error else Banga.accent)
                        .border(2.dp, Banga.bg, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = Banga.rowTitle, color = Banga.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                sub,
                style = Banga.supporting,
                color = if (isCurrent && status == PlayStatus.PLAYING) Banga.accent else Banga.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeartButton(isFavorite, onFavorite)
    }
}

@Composable
fun HeartButton(isFavorite: Boolean, onClick: () -> Unit, tint: Color = Banga.textSecondary) {
    val label = stringResource(if (isFavorite) R.string.fav_remove else R.string.fav_add)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (isFavorite) R.drawable.ic_heart else R.drawable.ic_heart_border),
            null,
            tint = if (isFavorite) Banga.accent else tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Square tile for horizontal rows ("recently played"). */
@Composable
fun StationTile(st: Station, isCurrent: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box {
            StationArt(st, 104.dp, corner = 20.dp)
            if (isCurrent) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp).clip(CircleShape).background(Banga.accent).border(2.dp, Banga.bg, CircleShape))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(st.name, style = Banga.supporting, fontWeight = FontWeight.SemiBold, color = Banga.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round play/pause button; spinner while connecting. */
@Composable
fun PlayButton(status: PlayStatus, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val active = status == PlayStatus.PLAYING || status == PlayStatus.CONNECTING || status == PlayStatus.BUFFERING
    val label = stringResource(if (active) R.string.pause else R.string.play)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) Banga.accent else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (filled) Banga.onAccent else Banga.text
        if (status == PlayStatus.CONNECTING || status == PlayStatus.BUFFERING) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.5.dp, modifier = Modifier.size(size * 0.42f))
        } else {
            Icon(
                painterResource(if (active) R.drawable.ic_pause else R.drawable.ic_play),
                null,
                tint = tint,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50.dp))
            .background(if (accent) Banga.accent else Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Banga.supporting, fontWeight = FontWeight.SemiBold, color = if (accent) Banga.onAccent else Banga.text)
    }
}

@Composable
fun EmptyBox(title: String, text: String, content: @Composable BoxScope.() -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF191B1C))
            .border(1.dp, Color(0xFF383B3C), RoundedCornerShape(22.dp))
            .padding(horizontal = 20.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_banga_wave), null, tint = Banga.accent, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = Banga.section, color = Banga.text)
        Spacer(Modifier.height(6.dp))
        Text(text, style = Banga.body, color = Banga.textSecondary)
        Box(Modifier.padding(top = 16.dp), content = content)
    }
}
