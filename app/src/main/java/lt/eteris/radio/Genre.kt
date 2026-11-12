package lt.eteris.radio

import androidx.annotation.StringRes

/**
 * A short, fixed list of genres.
 * The main stations have their genres set by hand. For the rest, catalog tags are matched as whole words
 * (before, "pop" inside "popular" made a Catholic station "Pop"). No reliable match = no genre shown.
 */
enum class Genre(@StringRes val label: Int, val from: Long, val to: Long, private val words: List<String>) {
    POP(R.string.genre_pop, 0xFFFF9985, 0xFFA8343E, listOf("pop", "top 40", "top40", "chr", "hits", "charts")),
    DANCE(R.string.genre_dance, 0xFFA470FF, 0xFF352061, listOf("dance", "electronic", "electro", "edm", "house", "techno", "trance", "club", "disco")),
    ROCK(R.string.genre_rock, 0xFF8CAAFF, 0xFF263B91, listOf("rock", "metal", "alternative", "indie", "punk", "hard rock")),
    RETRO(R.string.genre_retro, 0xFFFFDB8D, 0xFFAE6B22, listOf("oldies", "retro", "80s", "90s", "70s", "60s", "classic hits", "golden oldies")),
    JAZZ(R.string.genre_jazz, 0xFFACD972, 0xFF2F6E4A, listOf("jazz", "classical", "classic", "opera", "blues", "soul", "lounge", "chillout")),
    TALK(R.string.genre_talk, 0xFF6FD3C8, 0xFF1F5E63, listOf("news", "talk", "information", "public radio")),
    LITHUANIAN(R.string.genre_lt, 0xFFED9BDA, 0xFF712D75, listOf("lithuanian music", "lietuviška muzika", "lietuviška", "folk", "estrada")),
    FAITH(R.string.genre_faith, 0xFF9FB7FF, 0xFF33406E, listOf("christian", "catholic", "religious", "religion", "gospel")),
    KIDS(R.string.genre_kids, 0xFFFFC27A, 0xFFB1462C, listOf("kids", "children", "vaikams")),
    ;

    companion object {
        /** Hand-checked genres of the main stations (by station id). */
        private val KNOWN: Map<String, List<Genre>> = mapOf(
            "lrt" to listOf(TALK), "ziniu" to listOf(TALK), "lrtklasika" to listOf(JAZZ), "lrtopus" to listOf(ROCK),
            "lrtlituanica" to listOf(TALK),
            "m1" to listOf(POP), "m1plius" to listOf(POP), "m1dance" to listOf(DANCE), "lietus" to listOf(LITHUANIAN),
            "laluna" to listOf(POP), "powerhit" to listOf(DANCE, POP), "powergold" to listOf(RETRO),
            "radiocentras" to listOf(POP), "zip" to listOf(POP), "zipiskasetes" to listOf(RETRO), "rock" to listOf(ROCK),
            "relax" to listOf(LITHUANIAN), "relax100hitu" to listOf(LITHUANIAN), "relaxsenti" to listOf(LITHUANIAN, RETRO),
            "europeanhit" to listOf(POP), "super" to listOf(POP), "3" to listOf(POP), "top" to listOf(POP),
            "gold" to listOf(RETRO), "geras" to listOf(RETRO, LITHUANIAN), "kelyje" to listOf(LITHUANIAN),
            "pukas" to listOf(LITHUANIAN), "pukas2" to listOf(LITHUANIAN), "easy" to listOf(POP), "jazz" to listOf(JAZZ),
            "marijos" to listOf(FAITH), "vaiku" to listOf(KIDS), "fiesta" to listOf(POP),
        )

        /** Words in the station name that safely give the genre. */
        private val NAME_WORDS = listOf(
            "rock" to ROCK, "metal" to ROCK, "dance" to DANCE, "jazz" to JAZZ, "klasika" to JAZZ,
            "retro" to RETRO, "gold" to RETRO, "vaik" to KIDS, "kids" to KIDS,
        )

        fun of(st: Station): List<Genre> {
            KNOWN[st.id]?.let { return it }
            val tags = st.tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val name = st.name.lowercase()
            val found = linkedSetOf<Genre>()
            for (g in entries) {
                if (g.words.any { w -> tags.any { tag -> Regex("(^|[^\\p{L}0-9])" + Regex.escape(w) + "($|[^\\p{L}0-9])").containsMatchIn(tag) } }) found += g
            }
            NAME_WORDS.forEach { (w, g) -> if (Regex("(^|[^\\p{L}])$w").containsMatchIn(name)) found += g }
            // Religion wins over anything else the tags may say.
            if (FAITH in found) return listOf(FAITH)
            return found.toList()
        }
    }
}
