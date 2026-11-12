package lt.eteris.radio

import androidx.annotation.StringRes

/**
 * A short, fixed list of genres. The open catalog tags stations very unevenly
 * ("pop,top40,hits", "Lietuviška muzika", ...), so tags and station names are mapped onto these.
 */
enum class Genre(@StringRes val label: Int, val from: Long, val to: Long, private val words: List<String>) {
    POP(R.string.genre_pop, 0xFFFF9985, 0xFFA8343E, listOf("pop", "hit", "top 40", "top40", "chr", "chart", "power", "zip", "m-1", "m1", "relax")),
    DANCE(R.string.genre_dance, 0xFFA470FF, 0xFF352061, listOf("dance", "electro", "edm", "house", "techno", "trance", "club", "disco", "rave", "pulse")),
    ROCK(R.string.genre_rock, 0xFF8CAAFF, 0xFF263B91, listOf("rock", "metal", "alternative", "alternatyv", "indie", "punk", "opus")),
    RETRO(R.string.genre_retro, 0xFFFFDB8D, 0xFFAE6B22, listOf("oldies", "retro", "80s", "90s", "70s", "60s", "gold", "kasetes", "kasetė", "senti", "classic hits", "lietus")),
    JAZZ(R.string.genre_jazz, 0xFFACD972, 0xFF2F6E4A, listOf("jazz", "džiaz", "classical", "klasika", "opera", "blues", "soul", "lounge", "chill", "easy")),
    TALK(R.string.genre_talk, 0xFF6FD3C8, 0xFF1F5E63, listOf("news", "talk", "info", "žini", "ziniu", "public radio", "lrt radijas", "christian", "catholic", "marijos", "religi")),
    LITHUANIAN(R.string.genre_lt, 0xFFED9BDA, 0xFF712D75, listOf("lietuvi", "lithuanian music", "folk", "liaudi", "estrad", "kelyje", "pūkas", "pukas", "laluna", "geras", "gold fm")),
    KIDS(R.string.genre_kids, 0xFFFFC27A, 0xFFB1462C, listOf("vaik", "kids", "children")),
    ;

    fun matches(st: Station): Boolean {
        val hay = (st.tags + "," + st.name).lowercase()
        return words.any { it in hay }
    }

    companion object {
        fun of(st: Station): List<Genre> = entries.filter { it.matches(st) }
    }
}
