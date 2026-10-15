package com.jarvis.assistant.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Filled in automatically by the cloud build with your own GitHub repository. */
object Config {
    const val UPDATE_REPO = "OWNER/REPO"
}

/**
 * Self-update from the latest GitHub release. The cloud build publishes "Jarvis build N" on every change;
 * if N is newer than the installed build, Jarvis downloads the APK and hands it to Android's installer.
 * Android always owns the final install step, so a one-tap confirmation can still appear (most often the first time).
 * Needs the repository to be public so the phone can download without logging in.
 */
object Updater {
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Release(val build: Int, val apkUrl: String)

    fun configured(): Boolean = !Config.UPDATE_REPO.startsWith("OWNER")

    @Suppress("DEPRECATION")
    fun installedBuild(ctx: Context): Int = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode
    } catch (e: Exception) {
        0
    }

    fun versionLabel(ctx: Context): String = "build ${installedBuild(ctx)}"

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** Blocking. Call off the main thread. */
    fun latest(): Release? {
        if (!configured()) return null
        val j = JSONObject(
            Net.get(
                "https://api.github.com/repos/${Config.UPDATE_REPO}/releases/tags/latest",
                mapOf("Accept" to "application/vnd.github+json")
            )
        )
        val build = Regex("(\\d+)").find(j.optString("name"))?.value?.toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return Release(build, a.getString("browser_download_url"))
        }
        return null
    }

    /**
     * Checks and, when [install] is true and a newer build exists, downloads and starts the install.
     * Returns a sentence Jarvis can say.
     */
    suspend fun check(ctx: Context, install: Boolean): String = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext "Updates aren't connected to a GitHub repository yet."
        try {
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            val r = latest() ?: return@withContext "I couldn't find a published build to compare with."
            val mine = installedBuild(ctx)
            if (r.build <= mine) return@withContext "I'm up to date. This is build $mine."
            if (!install) return@withContext "A newer build, number ${r.build}, is available."
            if (!canInstall(ctx)) {
                openInstallPermission(ctx)
                return@withContext "A new version is ready. Please allow Jarvis to install updates on the page I opened, then ask me again."
            }
            val apk = File(ctx.cacheDir, "update.apk")
            Net.download(r.apkUrl, apk)
            if (apk.length() < 1_000_000L) return@withContext "The download looked incomplete, so I'll try again later."
            commit(ctx, apk)
            "Updating to build ${r.build}. Android may ask you to confirm."
        } catch (e: Exception) {
            "I couldn't check for updates right now."
        }
    }

    /** Quiet background check on app start, at most every 6 hours. */
    fun autoCheck(ctx: Context) {
        if (!Prefs.autoUpdate || !configured()) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < CHECK_EVERY_MS) return
        if (!canInstall(ctx)) return
        Thread {
            try {
                kotlinx.coroutines.runBlocking { check(ctx.applicationContext, true) }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("jarvis.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
    }
}

/** Receives the installer's progress; shows Android's confirmation screen when it needs a tap. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(it) } catch (_: Exception) { }
            }
        }
    }
}
