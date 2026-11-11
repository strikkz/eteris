package lt.eteris.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Loads station logos from the internet, keeps them in memory, shows initials until loaded. */
object Logos {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    fun bind(scope: CoroutineScope, url: String?, image: ImageView, initials: TextView, name: String) {
        initials.text = StationRepository.initials(name)
        image.tag = url
        val cached = url?.let { cache.get(it) }
        if (cached != null) {
            show(image, initials, cached)
            return
        }
        image.setImageDrawable(null)
        image.visibility = View.INVISIBLE
        initials.visibility = View.VISIBLE
        if (url == null || synchronized(failed) { url in failed }) return

        scope.launch {
            val bmp = withContext(Dispatchers.IO) { download(url) }
            if (bmp == null) {
                synchronized(failed) { failed += url }
                return@launch
            }
            cache.put(url, bmp)
            if (image.tag == url) show(image, initials, bmp)
        }
    }

    private fun show(image: ImageView, initials: TextView, bmp: Bitmap) {
        image.setImageBitmap(bmp)
        image.visibility = View.VISIBLE
        initials.visibility = View.INVISIBLE
    }

    private fun download(address: String): Bitmap? {
        try {
            var url = URL(address)
            var hops = 0
            while (true) {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", StationRepository.USER_AGENT)
                try {
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 4) {
                        val location = conn.getHeaderField("Location") ?: return null
                        url = URL(url, location)
                        hops++
                        continue
                    }
                    if (code != 200) return null
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0) return null
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                    return BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                } finally {
                    conn.disconnect()
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }
}
