package lt.eteris.radio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * Sleep timer. Lives in the app process, so it keeps working while the app is closed and only
 * the playback service runs. When time is up, the service fades the sound out and pauses.
 */
object SleepTimer {
    /** SystemClock.elapsedRealtime() when playback stops; 0 = timer off. */
    var endAt by mutableLongStateOf(0L)
        private set

    /** Set by RadioService: fade out and pause. */
    internal var onFire: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable {
        endAt = 0L
        onFire?.invoke()
    }

    fun set(minutes: Int) {
        handler.removeCallbacks(fire)
        if (minutes <= 0) {
            endAt = 0L
            return
        }
        endAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(fire, minutes * 60_000L)
    }

    val isOn: Boolean get() = endAt != 0L

    /** Whole minutes left, rounded up. */
    fun minutesLeft(): Int {
        if (endAt == 0L) return 0
        val ms = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        return ((ms + 59_999) / 60_000).toInt()
    }
}
