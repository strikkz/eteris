@file:OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)

package lt.eteris.radio.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Chip
import androidx.car.app.model.ChipSection
import androidx.car.app.model.CondensedItem
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.SpotlightSection
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import lt.eteris.radio.Art
import lt.eteris.radio.Genre
import lt.eteris.radio.R
import lt.eteris.radio.Station
import lt.eteris.radio.StationRepository

/** Common bits: redraw on changes, logos through the content provider, open the player. */
abstract class BangaScreen(ctx: CarContext, val car: BangaCarSession) : Screen(ctx) {
    private val redraw: () -> Unit = { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = car.onChange(redraw)
            override fun onDestroy(owner: LifecycleOwner) = car.removeOnChange(redraw)
        })
    }

    val api: Int get() = carContext.carAppApiLevel
    fun str(id: Int, vararg args: Any): String = carContext.getString(id, *args)

    /** The car fetches the logo itself by address, so big pictures never pass through the connection. */
    fun logo(st: Station): CarIcon =
        CarIcon.Builder(IconCompat.createWithContentUri(Art.uri(st.id))).build()

    fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    /** Plays [list] from [st] and opens the player; next/previous on the wheel then walk this list. */
    fun playFrom(list: List<Station>, st: Station) {
        car.play(list, list.indexOfFirst { it.id == st.id }.coerceAtLeast(0))
        if (screenManager.top !is CarPlaybackScreen) screenManager.push(CarPlaybackScreen(carContext, car))
    }

    /** "Now playing" button in the header, the standard one when the car allows a click on it. */
    fun nowPlayingAction(): Action {
        val open = { screenManager.push(CarPlaybackScreen(carContext, car)) }
        return runCatching { Action.Builder(Action.MEDIA_PLAYBACK).setOnClickListener(open).build() }
            .getOrElse { Action.Builder().setIcon(CarIcon.MEDIA_PLAYBACK).setOnClickListener(open).build() }
    }

    val stations: List<Station> get() = StationRepository.stations
    val popular: List<Station> get() = stations.filter { it.prio < 999 }.ifEmpty { stations.take(12) }
}

/**
 * Main screen: spotlight with six favorites on top, genre chips, popular stations as tiles.
 * Older cars without the newer sections get the same content as plain grids.
 */
class CarHomeScreen(ctx: CarContext, car: BangaCarSession) : BangaScreen(ctx, car) {

    override fun onGetTemplate(): Template {
        val favs = StationRepository.favoritesByRecent(carContext).take(6)
        val current = StationRepository.byId(car.currentId)
        val b = SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(str(R.string.app_name))
                    .setStartHeaderAction(Action.APP_ICON)
                    .addEndHeaderAction(
                        Action.Builder().setTitle(str(R.string.all_stations))
                            .setOnClickListener { screenManager.push(CarListScreen(carContext, car, null)) }
                            .build(),
                    )
                    .build(),
            )
            .addAction(nowPlayingAction())

        if (stations.isEmpty()) return b.setLoading(true).build()

        // Favorites: spotlight (big logo of the playing station + six tiles), or a grid on older cars.
        if (favs.isNotEmpty()) {
            if (api >= 9) {
                val hero = current ?: favs.first()
                b.addSection(
                    SpotlightSection.Builder(logo(hero))
                        .setTitle(str(R.string.nav_favorites))
                        .setItems(favs.map { st ->
                            CondensedItem.Builder()
                                .setTitle(st.name)
                                .setText(if (st.id == current?.id) str(R.string.now_playing) else st.qualityLabel)
                                .setLeadingImage(logo(st))
                                .setOnClickListener { playFrom(favs, st) }
                                .build()
                        })
                        .build(),
                )
            } else {
                b.addSection(grid(str(R.string.nav_favorites), favs, GridSection.ITEM_SIZE_LARGE))
            }
        }

        // Genres as chips (newer cars only).
        if (api >= 9) {
            val genres = Genre.entries.filter { g -> stations.any { g in it.genres } }
            if (genres.isNotEmpty()) {
                b.addSection(
                    ChipSection.Builder()
                        .setItems(genres.map { g ->
                            Chip.Builder()
                                .setTitle(str(g.label))
                                .setOnClickListener { screenManager.push(CarListScreen(carContext, car, g)) }
                                .build()
                        })
                        .build(),
                )
            }
        }

        b.addSection(grid(str(R.string.popular), popular, GridSection.ITEM_SIZE_MEDIUM))
        return b.build()
    }

    private fun grid(title: String, list: List<Station>, size: Int): GridSection =
        GridSection.Builder()
            .setTitle(title)
            .setItemSize(size)
            .setItems(list.map { st ->
                GridItem.Builder()
                    .setTitle(st.name)
                    .setImage(logo(st))
                    .setOnClickListener { playFrom(list, st) }
                    .build()
            })
            .build()
}

/** All stations (or one genre) as a list with logos and an A–Z index. */
class CarListScreen(ctx: CarContext, car: BangaCarSession, private val genre: Genre?) : BangaScreen(ctx, car) {

    override fun onGetTemplate(): Template {
        val list = (if (genre == null) stations else stations.filter { genre in it.genres })
            .sortedBy { it.name.lowercase() }
        val favs = StationRepository.favorites(carContext)
        val current = car.currentId
        val title = genre?.let { str(it.label) } ?: str(R.string.all_stations)
        return SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
            .addAction(nowPlayingAction())
            .setAlphabeticalIndexingAllowed(genre == null)
            .addSection(
                RowSection.Builder()
                    .setItems(list.map { st ->
                        val row = Row.Builder()
                            .setTitle(st.name)
                            .addText(
                                when {
                                    st.id == current -> str(R.string.now_playing)
                                    st.id in favs -> "★  ${st.qualityLabel}"
                                    else -> st.qualityLabel
                                },
                            )
                            .setImage(logo(st), Row.IMAGE_TYPE_LARGE)
                            .setOnClickListener { playFrom(list, st) }
                        row.build()
                    })
                    .build(),
            )
            .build()
    }
}

/** Player: the car draws the controls from the media session; the header holds ★ and "other stations". */
class CarPlaybackScreen(ctx: CarContext, car: BangaCarSession) : BangaScreen(ctx, car) {

    override fun onGetTemplate(): Template {
        val id = car.currentId
        val fav = id != null && id in StationRepository.favorites(carContext)
        val header = Header.Builder()
            .setTitle(StationRepository.byId(id)?.name ?: str(R.string.app_name))
            .setStartHeaderAction(Action.BACK)
        if (id != null) {
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(icon(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_border))
                    .setOnClickListener { car.toggleFavorite(id); invalidate() }
                    .build(),
            )
        }
        header.addEndHeaderAction(
            Action.Builder()
                .setTitle(str(R.string.other_stations))
                .setOnClickListener {
                    if (screenManager.stackSize > 1) finish()
                    else screenManager.push(CarHomeScreen(carContext, car))
                }
                .build(),
        )
        return MediaPlaybackTemplate.Builder().setHeader(header.build()).build()
    }
}
