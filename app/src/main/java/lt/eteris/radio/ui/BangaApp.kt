package lt.eteris.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import lt.eteris.radio.AppState
import lt.eteris.radio.Genre

/** BANGA phone app: three tabs, mini-player above the navigation, full player and settings on top. */
@Composable
fun BangaApp(app: AppState) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var genreName by rememberSaveable { mutableStateOf<String?>(null) }
    val genre = genreName?.let { n -> Genre.entries.firstOrNull { it.name == n } }

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = settingsOpen && !playerOpen) { settingsOpen = false }
    BackHandler(enabled = !playerOpen && !settingsOpen && tab != Tab.HOME) { tab = Tab.HOME }

    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Banga.accent, onPrimary = Banga.onAccent, background = Banga.bg,
            surface = Banga.surfaceRaised, onSurface = Banga.text,
        ),
    ) {
    Box(Modifier.fillMaxSize().background(Banga.bg)) {
      Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tab.HOME -> HomeScreen(
                app,
                onSettings = { settingsOpen = true },
                onGenre = { genreName = it.name; query = ""; tab = Tab.EXPLORE },
                onOpenPlayer = { playerOpen = true },
            )
            Tab.EXPLORE -> ExploreScreen(
                app, query, { query = it }, genre, { genreName = it?.name },
                onSettings = { settingsOpen = true },
            )
            Tab.FAVORITES -> FavoritesScreen(app, onSettings = { settingsOpen = true }, onExplore = { tab = Tab.EXPLORE })
        }

        // Dock: mini-player + navigation, over a fade so lists scroll under it.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Banga.bg.copy(alpha = 0.92f), 1f to Banga.bg))
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            MiniPlayer(app) { playerOpen = true }
            NavBar(tab) { tab = it }
        }
      }

        app.notice?.let { msg ->
            LaunchedEffect(msg) { delay(3500); app.notice = null }
            Text(
                msg,
                color = Banga.onAccent,
                style = Banga.supporting,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 170.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFDFFF8B))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInVertically(tween(300)) { it / 6 } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(260)) { it / 6 } + fadeOut(tween(200)),
        ) {
            SettingsScreen(app) { settingsOpen = false }
        }

        AnimatedVisibility(
            visible = playerOpen && app.current != null,
            enter = slideInVertically(tween(340)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(200)),
        ) {
            PlayerScreen(
                app,
                onClose = { playerOpen = false },
                onOtherStations = { playerOpen = false; tab = Tab.EXPLORE },
            )
        }
    }
    }
}
