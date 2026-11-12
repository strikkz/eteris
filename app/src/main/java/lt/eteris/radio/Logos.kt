package lt.eteris.radio

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows station logos on the phone screen (the same pictures Android Auto gets). */
object Logos {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun bind(scope: CoroutineScope, station: Station, image: ImageView, initials: TextView) {
        initials.text = StationRepository.initials(station.name)
        image.tag = station.id
        cache.get(station.id)?.let { show(image, initials, it); return }

        image.setImageDrawable(null)
        image.visibility = View.INVISIBLE
        initials.visibility = View.VISIBLE
        val ctx = image.context.applicationContext
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Art.bitmap(ctx, station.id) } ?: return@launch
            cache.put(station.id, bmp)
            if (image.tag == station.id) show(image, initials, bmp)
        }
    }

    private fun show(image: ImageView, initials: TextView, bmp: Bitmap) {
        image.setImageBitmap(bmp)
        image.visibility = View.VISIBLE
        initials.visibility = View.INVISIBLE
    }
}
