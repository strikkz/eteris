package lt.eteris.radio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Updates without sending APK files around: the app reads update.json on the BANGA site
 * (written by the build for every release, with what changed in each language), offers the new
 * version in Settings, downloads it and hands it to Android's installer.
 */
object Updater {

    private const val INFO_URL = "https://strikkz.github.io/eteris/update.json"
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

    /** One newer version: its name and what changed (in the app's language). */
    data class Change(val name: String, val notes: List<String>)

    data class Update(val code: Long, val name: String, val apkUrl: String, val changes: List<Change>)

    var available by mutableStateOf<Update?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** Has a check finished (so "you have the latest version" can be shown). */
    var checked by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** 0..1 while downloading, null otherwise. */
    var progress by mutableStateOf<Float?>(null)
        private set
    /** Android needs the "install unknown apps" permission for BANGA first. */
    var needsPermission by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun installedCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Checks now, or only if the last check was long ago ([force] = false, e.g. when the app opens). */
    fun check(ctx: Context, force: Boolean = true) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("eteris", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("update_checked", 0) < CHECK_EVERY_MS && checked) return
        if (job?.isActive == true) return
        job = scope.launch {
            checking = true; failed = false
            val result = withContext(Dispatchers.IO) { runCatching { load(app) }.getOrNull() }
            checking = false
            if (result == null) { failed = true; return@launch }
            available = result.getOrNull()
            checked = true
            prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply()
        }
    }

    /** Result.success(null) = no newer version. */
    private fun load(ctx: Context): Result<Update?> {
        val text = get("$INFO_URL?t=${System.currentTimeMillis() / 60_000}") ?: error("no info")
        val o = JSONObject(text)
        val latest = o.getJSONObject("latest")
        val code = latest.getLong("code")
        val mine = installedCode(ctx)
        if (code <= mine) return Result.success(null)
        val lang = language()
        val history = o.optJSONArray("history")
        val changes = ArrayList<Change>()
        if (history != null) {
            for (i in 0 until history.length()) {
                val h = history.getJSONObject(i)
                if (h.optLong("code") <= mine) continue
                val notes = h.optJSONObject("notes") ?: continue
                val list = (notes.optJSONArray(lang) ?: notes.optJSONArray("lt")) ?: continue
                changes += Change(h.optString("name"), (0 until list.length()).map { list.getString(it) })
            }
        }
        return Result.success(Update(code, latest.optString("name"), latest.getString("apk"), changes))
    }

    /** The app's language (lt / en / uk / ru), as chosen in Settings or on the phone. */
    private fun language(): String {
        val tag = AppCompatDelegate.getApplicationLocales()[0]?.language ?: Locale.getDefault().language
        return when (tag) { "en", "uk", "ru" -> tag; else -> "lt" }
    }

    /** Downloads the new version and opens Android's installer for it. */
    fun downloadAndInstall(ctx: Context) {
        val u = available ?: return
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            // Android asks once: allow BANGA to install apps. Then the user taps "Update" again.
            needsPermission = true
            app.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        needsPermission = false
        if (job?.isActive == true) return
        job = scope.launch {
            progress = 0f; failed = false
            val file = withContext(Dispatchers.IO) { runCatching { download(app, u.apkUrl) }.getOrNull() }
            progress = null
            if (file == null) { failed = true; return@launch }
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun download(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "banga.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == 200)
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastShown > 256 * 1024) {
                            lastShown = done
                            val f = done.toFloat() / total
                            withContext(Dispatchers.Main) { progress = f }
                        }
                    }
                }
            }
            check(total <= 0 || done == total) { "incomplete" }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
