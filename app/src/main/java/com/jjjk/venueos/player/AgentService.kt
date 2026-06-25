package com.jjjk.venueos.player

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AgentService : Service() {

    companion object {
        const val TAG = "VenueOSAgent"
        const val APP_VERSION = "1.0.0"
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
        Thread { start() }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun start() {
        val venueUrl = prefs.getString(PREF_VENUE_URL, null)
        val screenId = prefs.getString(PREF_SCREEN_ID, null)
        if (venueUrl != null && screenId != null) {
            notifyDisplay(venueUrl, screenId)
            commandLoop(venueUrl, screenId)
        } else {
            provisionLoop()
        }
    }

    private fun provisionLoop() {
        val deviceId = getOrCreateDeviceId()
        while (true) {
            try {
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("model", Build.MODEL)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("appVersion", APP_VERSION)
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
                    val screenId = registerScreen(venueUrl, deviceId) ?: continue
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

    private fun registerScreen(venueUrl: String, deviceId: String): String? {
        return try {
            val body = JSONObject().apply {
                put("name", "Android Player (${Build.MODEL})")
            }.toString()
            val resp = httpPost("$venueUrl/signage/api/screens/register", body)
            JSONObject(resp).optString("screenId").takeIf { it.isNotEmpty() }
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
            "screenshot" -> {
                val uploadUrl = "$signageBase/api/screens/$screenId/screenshot"
                mainHandler.post { listener?.onTakeScreenshot(uploadUrl) }
            }
            "reboot" -> doReboot()
            "check-update" -> Log.i(TAG, "check-update received (Android APK updates via sideload)")
            else -> Log.w(TAG, "Unknown command: $type")
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
            id = UUID.randomUUID().toString()
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
        }
        return id
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray())
        return try {
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
