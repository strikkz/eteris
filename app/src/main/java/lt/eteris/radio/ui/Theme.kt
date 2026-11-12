package lt.eteris.radio.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lt.eteris.radio.Art
import lt.eteris.radio.Station

/** BANGA design tokens (tokens.json, "Noir Signal / Electric Lime"). */
object Banga {
    val bg = Color(0xFF0B0D0D)
    val surface = Color(0xFF171B1A)
    val surfaceRaised = Color(0xFF212624)
    val text = Color(0xFFF4F7EF)
    val textSecondary = Color(0xFFA6ADA7)
    val accent = Color(0xFFDFFF63)
    val onAccent = Color(0xFF10140B)
    val outline = Color(0xFF343B37)
    val error = Color(0xFFFF8F89)

    private val family = FontFamily.SansSerif
    val hero = TextStyle(fontFamily = family, fontSize = 43.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.06).em)
    val featured = TextStyle(fontFamily = family, fontSize = 36.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.06).em)
    val screenTitle = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.05).em)
    val section = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em)
    val rowTitle = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val body = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val supporting = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val eyebrow = TextStyle(fontFamily = family, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.18.em)
}

/** Colours taken from a station's logo, used for its artwork, hero card and the player glow. */
@Immutable
data class StationColors(val light: Color, val mid: Color, val dark: Color)

private val DEFAULT_COLORS = StationColors(Color(0xFFD6FF8A), Color(0xFF72A359), Color(0xFF153B39))

private object VisualCache {
    val logos = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    val colors = HashMap<String, StationColors>()
}

private fun colorsOf(bmp: Bitmap): StationColors {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val vivid = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        ?: return DEFAULT_COLORS
    val mid = Color(vivid.rgb)
    val light = p.lightVibrantSwatch?.let { Color(it.rgb) } ?: lerp(mid, Color.White, 0.45f)
    val dark = (p.darkMutedSwatch ?: p.darkVibrantSwatch)?.let { Color(it.rgb) } ?: lerp(mid, Color.Black, 0.65f)
    // Keep the dark tone really dark, so white text on top stays readable.
    return StationColors(light, mid, lerp(dark, Color.Black, 0.35f))
}

private suspend fun load(ctx: android.content.Context, st: Station): Pair<ImageBitmap?, StationColors> =
    withContext(Dispatchers.IO) {
        val bmp = Art.bitmap(ctx, st.id)
        val img = bmp?.asImageBitmap()
        val colors = VisualCache.colors[st.id] ?: (bmp?.let { colorsOf(it) } ?: DEFAULT_COLORS)
        img?.let { VisualCache.logos.put(st.id, it) }
        VisualCache.colors[st.id] = colors
        img to colors
    }

@Composable
fun rememberLogo(st: Station): ImageBitmap? {
    val ctx = LocalContext.current
    // produceState keeps its old value when the station changes, so always set it for the new station.
    val img by produceState(VisualCache.logos.get(st.id), st.id) {
        value = VisualCache.logos.get(st.id)
        if (value == null) value = load(ctx, st).first
    }
    return img
}

@Composable
fun rememberColors(st: Station?): StationColors {
    val ctx = LocalContext.current
    if (st == null) return DEFAULT_COLORS
    val colors by produceState(VisualCache.colors[st.id] ?: DEFAULT_COLORS, st.id) {
        value = VisualCache.colors[st.id] ?: DEFAULT_COLORS
        if (VisualCache.colors[st.id] == null) value = load(ctx, st).second
    }
    return colors
}

fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())
