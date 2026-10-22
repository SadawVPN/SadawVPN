package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SadawActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val TAG = "SadawVPN"
    private val REQ_VPN = 1001
    // Временный URL (позже заменим на твой VPS)
    private val API_BASE = "https://sadaw-vpn-config.senakrivousov.workers.dev"
    private var currentProfileId: Long = -1L
    private var forcedBypassPackages = mutableSetOf<String>()

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
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/vpn-ui.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun checkSubscription(subKey: String): String {
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
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(response).getString("status")
                } else "error"
            } catch (e: Exception) { "error" }
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
                webView.post { webView.evaluateJavascript("window.onPingResult && window.onPingResult($index, $pingMs)", null) }
            }.start()
        }

        @JavascriptInterface
        fun connect(vlessUri: String): String {
            Thread {
                try {
                    SagerNet.stopService()
                    Thread.sleep(500)
                    val prefs = getSharedPreferences("sadaw_vpn", MODE_PRIVATE)
                    var profileId = prefs.getLong("profile_id", -1L)
                    if (profileId == -1L) {
                        val beans = runBlocking { parseProxies(vlessUri) }
                        val bean = beans.firstOrNull() ?: throw Exception("Ошибка парсинга")
                        val profile = runBlocking { ProfileManager.createProfile(0L, bean) }
                        profileId = profile.id
                        prefs.edit().putLong("profile_id", profileId).apply()
                    }
                    DataStore.selectedProxy = profileId
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

        @JavascriptInterface
        fun getInstalledApps(): String {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val jsonArray = JSONArray()
            forcedBypassPackages.clear()
            val sortedApps = apps.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            
            for (app in sortedApps) {
                if (pm.getLaunchIntentForPackage(app.packageName) == null) continue
                val label = pm.getApplicationLabel(app).toString()
                // Простой поиск MAX (как ты и просил, без лишних умностей)
                val isMax = label.contains("MAX", ignoreCase = true) || 
                            label.contains("МАКС", ignoreCase = true) || 
                            app.packageName.contains("max", ignoreCase = true)
                
                if (isMax) forcedBypassPackages.add(app.packageName)
                
                val appInfo = JSONObject()
                appInfo.put("packageName", app.packageName)
                appInfo.put("label", label)
                appInfo.put("isMax", isMax)
                jsonArray.put(appInfo)
            }
            return jsonArray.toString()
        }

        @JavascriptInterface
        fun setSplitTunneling(enabled: Boolean, bypassMode: Boolean, packagesJson: String) {
            val packagesArray = JSONArray(packagesJson)
            val packageList = mutableListOf<String>()
            for (i in 0 until packagesArray.length()) {
                packageList.add(packagesArray.getString(i))
            }
            val finalList = (packageList + forcedBypassPackages).distinct()
            val individualString = finalList.joinToString("\n")
            
            DataStore.proxyApps = enabled
            DataStore.bypass = bypassMode
            DataStore.individual = individualString
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
