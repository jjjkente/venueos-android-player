package com.jjjk.venueos.player

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AgentService : Service() {

    companion object {
        const val TAG = "VenueOSAgent"
        val APP_VERSION: String get() = BuildConfig.VERSION_NAME
        const val PROVISION_BASE = "https://admin.venueos.jjjk.com.au"
        const val NOTIF_CHANNEL = "venueos_agent"
        const val NOTIF_ID = 1
        const val PREF_NAME = "player_prefs"
        const val PREF_DEVICE_ID = "device_id"
        const val PREF_PAIRING_CODE = "pairing_code"
        const val PREF_VENUE_URL = "venue_url"
        const val PREF_SCREEN_ID = "screen_id"

        // MainActivity sets this to receive events from the service
        var listener: AgentListener? = null
    }

    interface AgentListener {
        fun onShowPairing(code: String)
        fun onLaunchDisplay(url: String)
        fun onRefresh()
        fun onRotate(value: String)
        fun onTakeScreenshot(uploadUrl: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var running = false

    // HttpURLConnection's connectTimeout/readTimeout don't reliably bound DNS
    // resolution on Android - a lookup that hangs at the native getaddrinfo()
    // level (seen for real: right as wifi finishes negotiating on boot, one
    // attempt failed fast with "no address associated", the next just hung
    // forever with zero further log output) can block a plain network call
    // indefinitely with no timeout ever firing. Running each call on this
    // executor with an explicit Future.get(timeout) means a wedged attempt
    // just gets abandoned (its thread may leak until the OS eventually kills
    // the hung native call, but nothing here waits on it) instead of
    // permanently freezing the registration/poll/command retry loops.
    private val networkExecutor = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        val notif = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        // Only relevant on a cold boot launch (BootReceiver) - if MainActivity
        // is what actually started this service (normal foreground use) it's
        // already on screen, so this is a harmless no-op re-launch of the
        // same activity. See BootReceiver for why this lives here and not there.
        if (BuildConfig.AUTO_LAUNCH_ON_BOOT) {
            val launch = Intent(this, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
        Thread { start() }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun start() {
        reportDeviceInfo()
        val venueUrl = prefs.getString(PREF_VENUE_URL, null)
        val screenId = prefs.getString(PREF_SCREEN_ID, null)
        if (venueUrl != null && screenId != null) {
            notifyDisplay(venueUrl, screenId)
            commandLoop(venueUrl, screenId)
        } else {
            provisionLoop()
        }
    }

    // Best-effort, fire-and-forget report of the OS build fingerprint + signing keys
    // (Build.TAGS is "release-keys" on a proper signed manufacturer build, "test-keys"
    // /"dev-keys" otherwise) so admin.venueos.jjjk.com.au can flag panels running a
    // non-release-signed or unofficially-built firmware image. Called unconditionally
    // on every start() - register() only fires for a still-unpaired device, but this
    // is the one thing already-assigned panels need to report too, since after
    // pairing they never talk to PROVISION_BASE again (everything else goes to the
    // venue's own signage server).
    private fun reportDeviceInfo() {
        Thread {
            try {
                val deviceId = getOrCreateDeviceId()
                httpPost("$PROVISION_BASE/api/players/$deviceId/info", JSONObject().apply {
                    put("fingerprint", Build.FINGERPRINT)
                    put("buildTags", Build.TAGS)
                }.toString())
            } catch (e: Exception) {
                Log.w(TAG, "reportDeviceInfo failed: ${e.message}")
            }
        }.start()
    }

    private fun provisionLoop() {
        val deviceId = getOrCreateDeviceId()
        val hardwareId = getHardwareId()
        while (true) {
            try {
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("model", Build.MODEL)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("appVersion", APP_VERSION)
                    put("fingerprint", Build.FINGERPRINT)
                    put("buildTags", Build.TAGS)
                    if (hardwareId != null) put("hardwareId", hardwareId)
                }.toString()
                val resp = httpPost("$PROVISION_BASE/api/players/register", body)
                val code = JSONObject(resp).getString("pairingCode")
                prefs.edit().putString(PREF_PAIRING_CODE, code).apply()
                updateNotification("Waiting to pair: $code")
                mainHandler.post { listener?.onShowPairing(code) }
                break
            } catch (e: Exception) {
                Log.w(TAG, "Register failed, retrying: ${e.message}")
                Thread.sleep(10_000)
            }
        }

        // Poll until assigned
        while (true) {
            try {
                val resp = httpGet("$PROVISION_BASE/api/players/$deviceId/poll")
                val json = JSONObject(resp)
                if (json.optBoolean("assigned")) {
                    val venueUrl = json.getString("venueUrl")
                    // A known hardwareId lets the server hand back the screen this
                    // device was already paired as, so a nightly storage wipe on
                    // flaky panel firmware silently reconnects instead of minting
                    // a fresh unclaimed pairing code every reboot.
                    //
                    // This same knownScreenId branch is also what collapses the
                    // whole flow to a single on-screen code as of the venue-side
                    // "Claim a New Device" UI (2026-08-06): that path claims the
                    // screen and sets screenId on the player record in one atomic
                    // step server-side, so it's already present here and
                    // registerScreen() below never runs a second registration -
                    // no second pairing code is ever shown. The old superadmin
                    // "Assign to Venue" action only sets venueUrl (not screenId),
                    // so it still falls through to registerScreen() below and a
                    // second code, kept as a working fallback path deliberately.
                    // org.json's JSONObject.NULL has a toString() override that
                    // returns the literal string "null", so optString(key, "")
                    // does NOT fall back to "" when the key is present-but-null,
                    // only when the key is absent entirely - has()+isNull() is
                    // the only reliable way to tell "no screenId" from "null".
                    val knownScreenId = if (json.has("screenId") && !json.isNull("screenId")) json.getString("screenId") else null
                    val screenId = knownScreenId ?: run {
                        val newScreenId = registerScreen(venueUrl, deviceId) ?: return@run null
                        reportScreenId(deviceId, newScreenId)
                        newScreenId
                    } ?: continue
                    prefs.edit()
                        .putString(PREF_VENUE_URL, venueUrl)
                        .putString(PREF_SCREEN_ID, screenId)
                        .apply()
                    notifyDisplay(venueUrl, screenId)
                    commandLoop(venueUrl, screenId)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll error: ${e.message}")
            }
            Thread.sleep(5_000)
        }
    }

    private fun reportScreenId(deviceId: String, screenId: String) {
        try {
            httpPost("$PROVISION_BASE/api/players/$deviceId/screen", JSONObject().apply {
                put("screenId", screenId)
            }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Report screenId failed: ${e.message}")
        }
    }

    private fun registerScreen(venueUrl: String, deviceId: String): String? {
        return try {
            val body = JSONObject().apply {
                put("name", "Android Player (${Build.MODEL})")
            }.toString()
            val resp = httpPost("$venueUrl/signage/api/screens/register", body)
            val obj = JSONObject(resp)
            if (obj.has("screenId") && !obj.isNull("screenId")) obj.getString("screenId") else null
        } catch (e: Exception) {
            Log.e(TAG, "Screen register failed: ${e.message}")
            null
        }
    }

    private fun notifyDisplay(venueUrl: String, screenId: String) {
        val displayUrl = "$venueUrl/signage/display?screen=$screenId"
        updateNotification("Running — ${venueUrl.removePrefix("https://")}")
        mainHandler.post { listener?.onLaunchDisplay(displayUrl) }
    }

    private fun commandLoop(venueUrl: String, screenId: String) {
        val base = "$venueUrl/signage"
        while (true) {
            try {
                val resp = httpGet("$base/api/screens/$screenId/agent-status?agentVersion=$APP_VERSION&platform=android")
                val json = JSONObject(resp)
                val cmd = json.optJSONObject("pendingCommand") ?: json.optJSONObject("command")
                if (cmd != null) handleCommand(cmd, base, screenId)
            } catch (e: Exception) {
                Log.w(TAG, "Command poll: ${e.message}")
            }
            Thread.sleep(10_000)
        }
    }

    private fun handleCommand(cmd: JSONObject, signageBase: String, screenId: String) {
        when (val type = cmd.optString("type")) {
            "refresh" -> mainHandler.post { listener?.onRefresh() }
            "rotate" -> {
                val value = cmd.optString("value", "normal")
                mainHandler.post { listener?.onRotate(value) }
            }
            "resolution" -> applyResolution(cmd.optString("value", ""))
            "screenshot" -> {
                val uploadUrl = "$signageBase/api/screens/$screenId/screenshot"
                mainHandler.post { listener?.onTakeScreenshot(uploadUrl) }
            }
            "reboot" -> doReboot()
            "check-update" -> Log.i(TAG, "check-update received (Android APK updates via sideload)")
            else -> Log.w(TAG, "Unknown command: $type")
        }
    }

    // Android equivalent of the Pi agent's apply_resolution() gtf/xrandr
    // modeline synthesis - `wm size` is WindowManager's own supported
    // override for a non-EDID-advertised panel (e.g. an LED wall processor),
    // so no manual mode synthesis is needed here, just root shell access
    // (same su binary already used for the boot animation on this hardware).
    // Empty value ("") is what the dashboard sends to clear back to native -
    // `wm size reset` is the documented way to do that.
    private fun applyResolution(value: String) {
        val arg = if (value.isBlank()) "reset" else value
        try {
            Runtime.getRuntime().exec(arrayOf("su", "0", "wm", "size", arg)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "wm size failed (device may not be rooted): ${e.message}")
        }
    }

    private fun doReboot() {
        try {
            @Suppress("DEPRECATION")
            (getSystemService(POWER_SERVICE) as PowerManager).reboot(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "PowerManager.reboot denied, trying shell")
            try { Runtime.getRuntime().exec(arrayOf("reboot")) } catch (_: Exception) {}
        }
    }

    fun uploadScreenshot(uploadUrl: String, jpeg: ByteArray) {
        Thread {
            try {
                val boundary = "VenueOSBoundary${System.currentTimeMillis()}"
                val conn = URL(uploadUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.outputStream.use { os ->
                    val header = "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"screenshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                    os.write(header.toByteArray())
                    os.write(jpeg)
                    os.write("\r\n--$boundary--\r\n".toByteArray())
                }
                Log.d(TAG, "Screenshot upload: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot upload failed: ${e.message}")
            }
        }.start()
    }

    private fun getOrCreateDeviceId(): String {
        var id = prefs.getString(PREF_DEVICE_ID, null)
        if (id == null) {
            // ANDROID_ID survives app data clears; only changes on factory reset (intentional new registration)
            id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
        }
        return id
    }

    // Ethernet MAC baked into the NIC survives even a full Android data wipe,
    // unlike ANDROID_ID/SharedPreferences which some panel firmware resets on
    // every unclean power cycle. Best-effort only — null if no wired NIC exposes one.
    private fun getHardwareId(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { !it.isLoopback && it.hardwareAddress != null }
            .sortedByDescending { it.name.startsWith("eth") }
            .firstOrNull()
            ?.hardwareAddress
            ?.joinToString(":") { String.format("%02X", it) }
    } catch (e: Exception) {
        Log.w(TAG, "getHardwareId failed: ${e.message}")
        null
    }

    // Wraps the actual blocking work in a hard 20s deadline - see
    // networkExecutor's comment above for why connectTimeout/readTimeout
    // alone aren't enough (they don't reliably bound DNS resolution).
    private fun <T> withTimeout(timeoutSec: Long = 20, block: () -> T): T {
        val future = networkExecutor.submit(Callable { block() })
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IOException("Request timed out after ${timeoutSec}s")
        }
    }

    private fun httpGet(url: String): String = withTimeout {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): String = withTimeout {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray())
        try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL, "VenueOS Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("VenueOS Player")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        // getSystemService(Class<T>) is API 23+ only; this app's minSdk is 21
        // (real hardware: a Philips/TPV panel on Android 5.0.1 hit this exact
        // NoSuchMethodError, crashing the whole process right after the pairing
        // code first showed). Use the string-constant overload, valid since API 1.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
