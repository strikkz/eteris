package lt.eteris.radio

import android.content.Context
import org.json.JSONObject

/** How a station describes itself (taken from its own website, checked by hand) and its site address. */
data class StationAbout(val text: String?, val url: String?)

object About {
    @Volatile private var data: Map<String, Pair<String?, String?>>? = null

    private fun load(ctx: Context): Map<String, Pair<String?, String?>> {
        data?.let { return it }
        val map = HashMap<String, Pair<String?, String?>>()
        runCatching {
            val o = JSONObject(ctx.assets.open("about.json").bufferedReader().use { it.readText() })
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                map[k] = e.optString("text").takeIf { it.isNotBlank() } to e.optString("url").takeIf { it.isNotBlank() }
            }
        }
        data = map
        return map
    }

    fun of(ctx: Context, st: Station): StationAbout {
        val e = load(ctx)[st.id]
        return StationAbout(e?.first, e?.second ?: st.homepage)
    }
}
