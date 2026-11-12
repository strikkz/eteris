package lt.eteris.radio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import lt.eteris.radio.ui.BangaApp

class MainActivity : AppCompatActivity() {

    private lateinit var app: AppState
    /** Autoplay only once per app start, not on every rotation or language change. */
    private var autoplayChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        autoplayChecked = savedInstanceState?.getBoolean("autoplayChecked") ?: false

        app = AppState(applicationContext)
        setContent { BangaApp(app) }

        askNotificationPermission()
        lifecycleScope.launch {
            StationRepository.ensureLoaded(applicationContext)
            app.refreshLists()
        }
    }

    override fun onStart() {
        super.onStart()
        app.start { state ->
            if (!autoplayChecked) {
                autoplayChecked = true
                state.autoplayIfWanted()
            }
        }
    }

    override fun onStop() {
        app.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autoplayChecked", autoplayChecked)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
