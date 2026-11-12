package lt.eteris.radio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Station logos.
 * 1) A logo pack built into the app (assets/logos/<station-id>.png, collected from station websites).
 * 2) If a station has no logo there: a generated tile with the station's initials in its own colour.
 * Both the phone screen and Android Auto use these, so every station always has a clean picture.
 */
object Art {

    const val AUTHORITY = "lt.eteris.radio.art"
    private const val SIZE = 256

    /** Address Android Auto and the notification load the picture from (served by [ArtProvider]). */
    fun uri(id: String): Uri = Uri.parse("content://$AUTHORITY/logo/${Uri.encode(id)}.png")

    private val PALETTE = intArrayOf(
        0xFF1F6FEB.toInt(), 0xFFD9480F.toInt(), 0xFF2B8A3E.toInt(), 0xFF7048E8.toInt(),
        0xFFC2255C.toInt(), 0xFF0B7285.toInt(), 0xFFE67700.toInt(), 0xFF495057.toInt(),
        0xFF5F3DC4.toInt(), 0xFF087F5B.toInt(), 0xFFA61E4D.toInt(), 0xFF1864AB.toInt(),
    )

    @Volatile private var dir: File? = null

    /** A fresh folder per app version, so an updated logo pack replaces old pictures. */
    private fun dir(ctx: Context): File {
        dir?.let { return it }
        val version = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
        val d = File(ctx.cacheDir, "art-$version")
        if (!d.exists()) {
            ctx.cacheDir.listFiles()?.filter { it.name.startsWith("art-") }?.forEach { it.deleteRecursively() }
            d.mkdirs()
        }
        dir = d
        return d
    }

    fun hasPackLogo(ctx: Context, id: String): Boolean =
        runCatching { ctx.assets.open("logos/$id.png").close(); true }.getOrDefault(false)

    /** The picture file for a station (created on first use). */
    @Synchronized
    fun file(ctx: Context, id: String): File {
        val f = File(dir(ctx), URLEncoder.encode(id, "UTF-8") + ".png")
        if (f.exists() && f.length() > 0) return f
        val tmp = File(f.path + ".tmp")
        val copied = runCatching {
            ctx.assets.open("logos/$id.png").use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
        }.isSuccess
        if (!copied) {
            val name = StationRepository.byId(id)?.name ?: id
            FileOutputStream(tmp).use { generate(name).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        tmp.renameTo(f)
        return f
    }

    fun bitmap(ctx: Context, id: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file(ctx, id).path)
    }.getOrNull()

    /** Tile with initials on a gradient in a colour picked from the station name. */
    fun generate(name: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = PALETTE[Math.floorMod(name.lowercase().hashCode(), PALETTE.size)]
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                lighten(base, 0.18f), darken(base, 0.28f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bg)

        // Soft "radio waves" in the corner: decoration that says this is a station.
        val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.argb(40, 255, 255, 255)
        }
        for (r in listOf(70f, 110f, 150f)) c.drawCircle(SIZE.toFloat(), SIZE.toFloat(), r, wave)

        val text = StationRepository.initials(name).take(3)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (text.length >= 3) 84f else 104f
        }
        val y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(text, SIZE / 2f, y, paint)
        return bmp
    }

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
    )

    private fun darken(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt(),
    )
}

/** Gives station pictures to Android Auto and the system notification. Logos are public, so read-only access is open. */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val id = uri.lastPathSegment?.removeSuffix(".png") ?: return null
        return ParcelFileDescriptor.open(Art.file(ctx, id), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
