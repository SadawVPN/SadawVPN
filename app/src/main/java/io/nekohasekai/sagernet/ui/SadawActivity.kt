package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class SadawActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val TAG = "SadawVPN"
    private val REQ_VPN = 1001
    private var currentProfileId: Long = -1L
    private var forcedBypassPackages = mutableSetOf<String>()

    // Панель 3x-ui (вшито)
    private val PANEL_URL = "https://s-26313.pl3.h1clayd.click/VKdsW4izxEXISHud"
    private val API_TOKEN = "YtD6QHIPg1kKSZujaMwNfKNZXwNPKIsmpUBXvlo7dlQzHqVZ"
    private val INBOUND_ID = "1"

    // Вшитый базовый ключ (всегда работает)
    private val MASTER_KEY = "SADAW-BETA"

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
        private fun apiRequest(endpoint: String, method: String, body: String? = null): String {
            return try {
                val url = URL("${PANEL_URL}/panel/api/${endpoint}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer ${API_TOKEN}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (body != null) {
                    conn.doOutput = true
                    val os = conn.outputStream
                    os.write(body.toByteArray())
                    os.flush()
                    os.close()
                }
                if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            } catch (e: Exception) {
                Log.e(TAG, "API Error: ${e.message}")
                ""
            }
        }

        @JavascriptInterface
        fun checkSubscription(subKey: String): String {
            if (subKey == MASTER_KEY) return "ok"
            return try {
                val res = apiRequest("clients/get/${subKey}", "GET")
                if (res.contains("\"success\":true")) "ok" else "invalid"
            } catch (e: Exception) { "invalid" }
        }

        @JavascriptInterface
        fun getConfig(subKey: String): String {
            if (subKey == MASTER_KEY) {
                val prefs = getSharedPreferences("sadaw_prefs", MODE_PRIVATE)
                return prefs.getString("master_vless", "") ?: ""
            }
            return try {
                val clientRes = apiRequest("clients/get/${subKey}", "GET")
                if (!clientRes.contains("\"success\":true")) return ""
                val client = JSONObject(clientRes).getJSONObject("obj").getJSONObject("client")
                val uuid = client.getString("uuid")
                val flow = client.optString("flow", "")

                val inboundRes = apiRequest("inbounds/get/${INBOUND_ID}", "GET")
                if (!inboundRes.contains("\"success\":true")) return ""
                val inbound = JSONObject(inboundRes).getJSONObject("obj")
                val streamSettings = JSONObject(inbound.getString("streamSettings"))
                val reality = streamSettings.getJSONObject("realitySettings")
                val port = inbound.getInt("port")
                val pbk = reality.getString("publicKey")
                val sni = reality.getJSONArray("serverNames").getString(0)
                val sid = reality.getJSONArray("shortIds").getString(0)

                var link = "vless://${uuid}@83.171.241.170:${port}?type=tcp&security=reality&pbk=${pbk}&fp=firefox&sni=${sni}&sid=${sid}&spx=%2F#SadawVPN"
                if (flow.isNotEmpty()) link += "&flow=${flow}"
                link
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun adminAction(action: String, dataJson: String): String {
            try {
                val data = JSONObject(dataJson)
                if (data.getString("password") != "ors192289") return "auth"

                when (action) {
                    "setMasterVless" -> {
                        val link = data.getString("vless")
                        getSharedPreferences("sadaw_prefs", MODE_PRIVATE)
                            .edit().putString("master_vless", link).apply()
                        return "ok"
                    }
                    "getMasterVless" -> {
                        return getSharedPreferences("sadaw_prefs", MODE_PRIVATE)
                            .getString("master_vless", "") ?: ""
                    }
                    "create" -> {
                        val days = data.optInt("days", 30)
                        val newKey = "sadaw_" + UUID.randomUUID().toString().substring(0, 6)
                        val expireTime = System.currentTimeMillis() + days * 86400 * 1000L

                        val res = apiRequest("inbounds/get/${INBOUND_ID}", "GET")
                        val inbound = JSONObject(res).getJSONObject("obj")
                        val settings = JSONObject(inbound.getString("settings"))
                        val clients = settings.getJSONArray("clients")

                        val newClient = JSONObject()
                        newClient.put("id", UUID.randomUUID().toString())
                        newClient.put("flow", "xtls-rprx-vision")
                        newClient.put("email", newKey)
                        newClient.put("limitIp", 1)
                        newClient.put("totalGB", 0)
                        newClient.put("expiryTime", expireTime)
                        newClient.put("enable", true)
                        clients.put(newClient)
                        settings.put("clients", clients)

                        val body = JSONObject().put("id", INBOUND_ID).put("settings", settings.toString()).toString()
                        apiRequest("inbounds/update/${INBOUND_ID}", "POST", body)
                        return newKey
                    }
                    "list" -> {
                        val res = apiRequest("inbounds/get/${INBOUND_ID}", "GET")
                        val clients = JSONObject(JSONObject(res).getJSONObject("obj").getString("settings")).getJSONArray("clients")
                        return clients.toString()
                    }
                }
                return "ok"
            } catch (e: Exception) { return "error: ${e.message}" }
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
                        val bean = beans.firstOrNull() ?: throw Exception("Ошибка")
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

        private fun drawableToBase64(drawable: Drawable): String {
            val size = 64
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun getInstalledApps() {
            Thread {
                val pm = packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val jsonArray = JSONArray()
                forcedBypassPackages.clear()
                val sortedApps = apps.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                for (app in sortedApps) {
                    if (pm.getLaunchIntentForPackage(app.packageName) == null) continue
                    val label = pm.getApplicationLabel(app).toString()
                    val isMax = label.contains("MAX", true) || label.contains("МАКС", true) || app.packageName.contains("max", true)
                    if (isMax) forcedBypassPackages.add(app.packageName)
                    val appInfo = JSONObject().put("packageName", app.packageName).put("label", label).put("isMax", isMax)
                    try { appInfo.put("icon", drawableToBase64(pm.getApplicationIcon(app.packageName))) } catch (e: Exception) { appInfo.put("icon", "") }
                    jsonArray.put(appInfo)
                }
                val base64Json = Base64.encodeToString(jsonArray.toString().toByteArray(), Base64.NO_WRAP)
                webView.post { webView.evaluateJavascript("window.onAppsLoaded && window.onAppsLoaded('${base64Json}')", null) }
            }.start()
        }

        @JavascriptInterface
        fun setSplitTunneling(enabled: Boolean, bypassMode: Boolean, packagesJson: String) {
            val packagesArray = JSONArray(packagesJson)
            val packageList = mutableListOf<String>()
            for (i in 0 until packagesArray.length()) packageList.add(packagesArray.getString(i))
            val finalList = (packageList + forcedBypassPackages).distinct()
            DataStore.proxyApps = enabled
            DataStore.bypass = bypassMode
            DataStore.individual = finalList.joinToString("\n")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            val ok = resultCode == RESULT_OK
            if (ok) SagerNet.startService()
            webView.post { webView.evaluateJavascript("window.onVpnResult && window.onVpnResult(${ok})", null) }
        }
    }
}
