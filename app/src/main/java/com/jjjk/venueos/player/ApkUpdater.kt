package com.jjjk.venueos.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Manually-triggered self-update - only ever runs when someone presses
// "Check for Update" in the venue's Signage dashboard (the check-update
// command), never on a timer: Joe's call, an update restarts the screen
// mid-trade, so it has to be someone's deliberate choice.
//
// Downloads the APK superadmin hosts (/player-apk/venueos-player.apk, the
// autolaunch flavor CI deploys on every push to main) and installs it:
//   1. silently via root `pm install -r` where the firmware has su (Philips
//      Q-Line BDL3650Q ships an eng build) - no one has to touch the screen
//   2. otherwise via Android's PackageInstaller, which puts an "Install
//      update?" prompt on the screen for someone at the venue to accept
//      (Philips D-Line / BDL3550Q / hospitality TVs - locked release builds)
// Every step is reported to superadmin's Players page via /info.
object ApkUpdater {
    private const val TAG = "VenueOSUpdate"
    private const val ACTION_INSTALL_RESULT = "com.jjjk.venueos.player.INSTALL_RESULT"
    // A successful install kills this process before it can say so - the
    // version we were installing is saved here, and the NEW app reports
    // "installed" on its first start (see reportFinishedUpdate)
    private const val PREF_PENDING_UPDATE = "pending_update_version"

    @Volatile private var running = false

    fun checkAndUpdate(ctx: Context, deviceId: String, report: (status: String, message: String) -> Unit) {
        if (running) return report("busy", "An update is already in progress")
        running = true
        try {
            // Only the autolaunch flavor is hosted - installing it over a
            // standard-flavor tablet would silently change its boot behaviour
            if (BuildConfig.FLAVOR != "autolaunch") {
                return report("skipped", "This is the ${BuildConfig.FLAVOR} build - only autolaunch builds are hosted for remote update")
            }
            val latest = fetchLatestVersion()
            val current = BuildConfig.VERSION_NAME
            if (compareVersions(latest, current) <= 0) {
                return report("up-to-date", "v$current is the latest")
            }
            report("downloading", "v$current -> v$latest")
            val apk = download(ctx)
            report("installing", "Installing v$latest")
            ctx.getSharedPreferences(AgentService.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_PENDING_UPDATE, latest).apply()
            if (installWithRoot(apk)) {
                // Normally never reached: pm kills this process as soon as
                // the new APK is in place. BootReceiver's MY_PACKAGE_REPLACED
                // brings the player back up and the new agentVersion shows
                // in the dashboard on its first poll.
                report("installed", "Installed v$latest silently")
                return
            }
            installWithPrompt(ctx, apk, latest, report)
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            report("failed", e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
        }
    }

    // Called once at startup. Returns the status to report if this start is
    // the first run after one of our updates replaced the app, else null.
    fun reportFinishedUpdate(ctx: Context): Pair<String, String>? {
        val prefs = ctx.getSharedPreferences(AgentService.PREF_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getString(PREF_PENDING_UPDATE, null) ?: return null
        val current = BuildConfig.VERSION_NAME
        // Still on the old version = this was a plain restart while the
        // prompt sat unanswered (or the install failed) - keep waiting
        if (compareVersions(current, pending) < 0) return null
        prefs.edit().remove(PREF_PENDING_UPDATE).apply()
        return "installed" to "Updated to v$current"
    }

    private fun fetchLatestVersion(): String {
        val conn = URL("${AgentService.PROVISION_BASE}/player-apk/version.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) throw IOException("Version check HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().readText()).getString("version")
        } finally {
            conn.disconnect()
        }
    }

    // "1.10.0" > "1.9.2" - plain string compare gets that wrong
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun download(ctx: Context): File {
        val dir = File(ctx.filesDir, "update").apply { mkdirs() }
        val dest = File(dir, "venueos-player.apk")
        val tmp = File(dir, "venueos-player.apk.part")
        val conn = URL("${AgentService.PROVISION_BASE}/player-apk/venueos-player.apk").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("APK download HTTP ${conn.responseCode}")
            val size = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (size > 0 && tmp.length() != size) throw IOException("Incomplete download (${tmp.length()}/$size bytes)")
            if (!tmp.renameTo(dest)) throw IOException("Could not save downloaded APK")
            // Root's pm runs as another user - it has to be able to read it
            dest.setReadable(true, false)
            return dest
        } finally {
            conn.disconnect()
        }
    }

    // Two su syntaxes in the wild: "su 0 <cmd>" (AOSP userdebug/eng su,
    // what the Philips eng builds ship - same form applyResolution uses)
    // and "su -c '<cmd>'" (SuperSU/Magisk). Either one printing "Success"
    // means it worked. Anything else - no su, denied, error - falls through
    // to the on-screen prompt.
    private fun installWithRoot(apk: File): Boolean {
        val attempts = listOf(
            arrayOf("su", "0", "pm", "install", "-r", apk.absolutePath),
            arrayOf("su", "-c", "pm install -r '${apk.absolutePath}'")
        )
        for (cmd in attempts) {
            try {
                val proc = Runtime.getRuntime().exec(cmd)
                val out = (proc.inputStream.bufferedReader().readText() + proc.errorStream.bufferedReader().readText()).trim()
                proc.waitFor()
                Log.i(TAG, "${cmd.take(2).joinToString(" ")} pm install -> $out")
                if (out.contains("Success")) return true
            } catch (e: Exception) {
                Log.i(TAG, "Root install unavailable (${cmd.take(2).joinToString(" ")}): ${e.message}")
            }
        }
        return false
    }

    private fun installWithPrompt(ctx: Context, apk: File, version: String, report: (String, String) -> Unit) {
        // Android 8+ needs the per-app "Install unknown apps" toggle on
        // before it'll even show the prompt. Over ADB:
        //   appops set com.jjjk.venueos.player REQUEST_INSTALL_PACKAGES allow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            report("needs-permission", "Allow 'Install unknown apps' for VenueOS Player on this screen (or over ADB: appops set ${ctx.packageName} REQUEST_INSTALL_PACKAGES allow)")
            return
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("venueos-player.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            InstallResultReceiver.onResult = report
            InstallResultReceiver.version = version
            val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            session.commit(PendingIntent.getBroadcast(ctx, sessionId, intent, flags).intentSender)
        }
    }

    // PackageInstaller answers here: first with PENDING_USER_ACTION (we
    // show the system's confirm dialog), later with the final result if the
    // install didn't replace this process (failure/cancel).
    class InstallResultReceiver : BroadcastReceiver() {
        companion object {
            @Volatile var onResult: ((String, String) -> Unit)? = null
            @Volatile var version: String = "?"
        }

        private fun clearPending(context: Context) {
            context.getSharedPreferences(AgentService.PREF_NAME, Context.MODE_PRIVATE).edit()
                .remove(PREF_PENDING_UPDATE).apply()
        }

        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                        onResult?.invoke("awaiting-tap", "Waiting for someone to tap Install on the screen (v$version)")
                    } catch (e: Exception) {
                        onResult?.invoke("failed", "Couldn't show the install prompt: ${e.message}")
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> onResult?.invoke("installed", "Installed v$version")
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    clearPending(context)
                    onResult?.invoke("cancelled", "Install was cancelled on the screen")
                }
                else -> {
                    clearPending(context)
                    onResult?.invoke("failed", "Install failed ($status): $message")
                }
            }
        }
    }
}
