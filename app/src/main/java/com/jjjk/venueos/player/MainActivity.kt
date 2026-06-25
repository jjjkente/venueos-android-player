package com.jjjk.venueos.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), AgentService.AgentListener {

    private lateinit var pairingView: View
    private lateinit var webViewContainer: View
    private lateinit var webView: WebView
    private lateinit var pairingCodeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        pairingView = findViewById(R.id.pairing_view)
        webViewContainer = findViewById(R.id.webview_container)
        pairingCodeText = findViewById(R.id.pairing_code)
        webView = findViewById(R.id.webview)
        setupWebView()

        // Restore state if already provisioned
        val prefs = getSharedPreferences(AgentService.PREF_NAME, MODE_PRIVATE)
        val venueUrl = prefs.getString(AgentService.PREF_VENUE_URL, null)
        val screenId = prefs.getString(AgentService.PREF_SCREEN_ID, null)
        val pairingCode = prefs.getString(AgentService.PREF_PAIRING_CODE, null)
        when {
            venueUrl != null && screenId != null ->
                onLaunchDisplay("$venueUrl/signage/display?screen=$screenId")
            pairingCode != null ->
                onShowPairing(pairingCode)
        }

        startAgentService()
    }

    override fun onResume() {
        super.onResume()
        AgentService.listener = this
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        if (AgentService.listener === this) AgentService.listener = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Block back button in kiosk mode
    }

    // ---- AgentListener ----

    override fun onShowPairing(code: String) {
        pairingCodeText.text = code
        pairingView.visibility = View.VISIBLE
        webViewContainer.visibility = View.GONE
    }

    override fun onLaunchDisplay(url: String) {
        webView.loadUrl(url)
        webViewContainer.visibility = View.VISIBLE
        pairingView.visibility = View.GONE
    }

    override fun onRefresh() {
        webView.reload()
    }

    override fun onRotate(value: String) {
        requestedOrientation = when (value) {
            "left"     -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "right"    -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "inverted" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else       -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    override fun onTakeScreenshot(uploadUrl: String) {
        if (webView.width == 0 || webView.height == 0) return
        val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bitmap))
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val jpeg = baos.toByteArray()
        Thread {
            try {
                val boundary = "VenueOSBoundary${System.currentTimeMillis()}"
                val conn = java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.outputStream.use { os ->
                    os.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"screenshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                    os.write(jpeg)
                    os.write("\r\n--$boundary--\r\n".toByteArray())
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("VenueOSMain", "Screenshot upload failed: ${e.message}")
            }
        }.start()
    }

    // ---- helpers ----

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun startAgentService() {
        val intent = Intent(this, AgentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
