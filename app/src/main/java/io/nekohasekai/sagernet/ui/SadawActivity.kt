package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class SadawActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val TAG = "SadawVPN"
    private val REQ_VPN = 1001
    private val API_BASE = "https://sadaw-vpn-config.senakrivousov.workers.dev"

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
            Log.i(TAG, "checkSubscription: $subKey")
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            
            return try {
                val url = URL("$API_BASE/activate")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val jsonBody = JSONObject()
                jsonBody.put("key", subKey)
                jsonBody.put("deviceId", deviceId)

                val os = conn.outputStream
                val writer = OutputStreamWriter(os, "UTF-8")
                writer.write(jsonBody.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val status = jsonResponse.getString("status")
                    Log.i(TAG, "Ответ от Cloudflare: $status")
                    status
                } else {
                    "error"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сети: ${e.message}", e)
                "error"
            }
        }

        @JavascriptInterface
        fun ping(host: String, port: Int, index: Int) {
            Thread {
                var pingMs = -1
                try {
                    val start = System.currentTimeMillis()
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(host, port), 2000)
                    socket.close()
                    pingMs = (System.currentTimeMillis() - start).toInt()
                } catch (e: Exception) {}
                webView.post {
                    webView.evaluateJavascript("window.onPingResult && window.onPingResult($index, $pingMs)", null)
                }
            }.start()
        }

        @JavascriptInterface
        fun connect(vlessUri: String): String {
            Thread {
                try {
                    SagerNet.stopService()
                    Thread.sleep(500)
                    
                    // Загружаем сохраненный ID профиля (или создаем новый)
                    val prefs = getSharedPreferences("sadaw_vpn", MODE_PRIVATE)
                    var profileId = prefs.getLong("profile_id", -1L)
                    
                    if (profileId == -1L) {
                        // Первый раз — создаем профиль
                        Log.i(TAG, "Создаем новый профиль")
                        val beans = runBlocking { parseProxies(vlessUri) }
                        val bean = beans.firstOrNull() ?: throw Exception("Ошибка парсинга")
                        val profile = runBlocking { ProfileManager.createProfile(0L, bean) }
                        profileId = profile.id
                        prefs.edit().putLong("profile_id", profileId).apply()
                        Log.i(TAG, "Профиль создан с ID: $profileId")
                    } else {
                        Log.i(TAG, "Используем существующий профиль ID: $profileId")
                    }
                    
                    DataStore.selectedProxy = profileId
                    DataStore.serviceMode = Key.MODE_VPN
                    
                    runOnUiThread {
                        val intent = VpnService.prepare(this@SadawActivity)
                        if (intent != null) startActivityForResult(intent, REQ_VPN)
                        else {
                            SagerNet.startService()
                            webView.evaluateJavascript("window.onVpnResult && window.onVpnResult(true)", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка подключения: ${e.message}", e)
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
