package lt.eteris.radio.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import lt.eteris.radio.AppState
import lt.eteris.radio.R
import lt.eteris.radio.Settings

private val LANGUAGES = listOf("" to 0, "lt" to 1, "en" to 2, "uk" to 3, "ru" to 4)
private val NATIVE_NAMES = mapOf("lt" to "Lietuvių", "en" to "English", "uk" to "Українська", "ru" to "Русский")

@Composable
fun SettingsScreen(app: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var autoplay by remember { mutableStateOf(Settings.autoplay(ctx)) }
    var wifiOnly by remember { mutableStateOf(Settings.wifiOnly(ctx)) }
    val currentLang = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-') }

    LazyColumn(
        Modifier.fillMaxSize().blockTouchesBelow().background(Banga.bg).statusBarsPadding(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 40.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton48(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
            }
            Text(stringResource(R.string.settings), style = Banga.screenTitle, color = Banga.text, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        }

        item { Label(stringResource(R.string.language)) }
        LANGUAGES.forEach { (tag, _) ->
            item {
                val name = if (tag.isEmpty()) stringResource(R.string.language_system) else NATIVE_NAMES.getValue(tag)
                val selected = tag == currentLang
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!selected) {
                                AppCompatDelegate.setApplicationLocales(
                                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                                )
                            }
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = Banga.rowTitle, color = if (selected) Banga.accent else Banga.text, modifier = Modifier.weight(1f))
                    if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = Banga.accent, modifier = Modifier.size(22.dp))
                }
            }
        }
        item { HorizontalDivider(color = Color(0xFF292B2C), modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            ToggleRow(stringResource(R.string.autoplay), stringResource(R.string.autoplay_hint), autoplay) {
                autoplay = it; Settings.setAutoplay(ctx, it)
            }
            ToggleRow(stringResource(R.string.only_wifi), stringResource(R.string.only_wifi_hint), wifiOnly) {
                wifiOnly = it; Settings.setWifiOnly(ctx, it)
            }
        }

        item {
            Label(stringResource(R.string.about))
            Text("BANGA · " + stringResource(R.string.version, app.versionName), style = Banga.body, color = Banga.text)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stations_in_catalog, app.stations.size), style = Banga.supporting, color = Banga.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.tagline), style = Banga.supporting, color = Banga.textSecondary)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = Banga.eyebrow, color = Color(0xFF8C9090), modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Banga.rowTitle, color = Banga.text)
            Spacer(Modifier.height(4.dp))
            Text(hint, style = Banga.supporting, color = Banga.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Banga.onAccent,
                checkedTrackColor = Banga.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF444444),
                uncheckedBorderColor = Color(0xFF444444),
            ),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF292B2C)))
}
