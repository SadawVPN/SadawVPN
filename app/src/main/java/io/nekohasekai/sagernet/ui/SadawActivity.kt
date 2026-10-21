package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking

class SadawActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val REQ_VPN = 1001
    private var currentProfileId: Long = -1L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/vpn-ui.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun checkSubscription(subKey: String): String {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val db = FirebaseFirestore.getInstance()
            val docRef = db.collection("licenses").document(subKey)
            return try {
                val document = com.google.android.gms.tasks.Tasks.await(docRef.get(), 10, java.util.concurrent.TimeUnit.SECONDS)
                if (!document.exists()) return "invalid"
                val savedDeviceId = document.getString("deviceId")
                if (savedDeviceId.isNullOrEmpty()) {
                    docRef.update("deviceId", deviceId).get()
                    "ok"
                } else if (savedDeviceId == deviceId) "ok"
                else "busy"
            } catch (e: Exception) { "error" }
        }

        @JavascriptInterface
        fun connect(vlessUri: String): String {
            Thread {
                try {
                    SagerNet.stopService()
                    Thread.sleep(500)
                    val beans = runBlocking { parseProxies(vlessUri) }
                    val bean = beans.firstOrNull() ?: throw Exception("Ошибка")
                    if (currentProfileId != -1L) runBlocking { ProfileManager.deleteProfile(currentProfileId) }
                    val profile = runBlocking { ProfileManager.createProfile(0L, bean) }
                    currentProfileId = profile.id
                    DataStore.selectedProxy = profile.id
                    DataStore.serviceMode = Key.MODE_VPN
                    runOnUiThread {
                        val intent = VpnService.prepare(this@SadawActivity)
                        if (intent != null) startActivityForResult(intent, REQ_VPN)
                        else { SagerNet.startService(); webView.evaluateJavascript("window.onVpnResult && window.onVpnResult(true)", null) }
                    }
                } catch (e: Exception) {
                    runOnUiThread { webView.evaluateJavascript("window.onVpnResult && window.onVpnResult(false)", null) }
                }
            }.start()
            return "pending"
        }

        @JavascriptInterface
        fun disconnect() {
            SagerNet.stopService()
            webView.post { webView.evaluateJavascript("window.onVpnStopped && window.onVpnStopped()", null) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            val ok = resultCode == RESULT_OK
            if (ok) SagerNet.startService()
            webView.post { webView.evaluateJavascript("window.onVpnResult && window.onVpnResult($ok)", null) }
        }
    }
}
