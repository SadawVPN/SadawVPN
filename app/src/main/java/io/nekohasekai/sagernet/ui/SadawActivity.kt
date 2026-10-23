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

class SadawActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val TAG = "SadawVPN"
    private val REQ_VPN = 1001
    private var currentProfileId: Long = -1L
    private var forcedBypassPackages = mutableSetOf<String>()

    private val GIST_ID = "8ec0cf984648caaadfc716bd032c8d06"
    private val GH_TOKEN = "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" // Заменим на токен ниже

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
        private fun getGistData(): JSONObject {
            return try {
                val conn = URL("https://api.github.com/gists/${GIST_ID}").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "token ${GH_TOKEN}")
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val files = JSONObject(response).getJSONObject("files")
                val content = files.getJSONObject("sadaw_db.json").getString("content")
                JSONObject(content)
            } catch (e: Exception) {
                Log.e(TAG, "Gist read error: ${e.message}")
                JSONObject().put("keys", JSONObject())
            }
        }

        private fun saveGistData(data: JSONObject): Boolean {
            return try {
                val conn = URL("https://api.github.com/gists/${GIST_ID}").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "token ${GH_TOKEN}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = JSONObject().put("files", JSONObject().put("sadaw_db.json", JSONObject().put("content", data.toString())))
                val os = conn.outputStream
                os.write(body.toString().toByteArray())
                os.flush()
                os.close()
                conn.responseCode in 200..299
            } catch (e: Exception) {
                Log.e(TAG, "Gist save error: ${e.message}")
                false
            }
        }

        @JavascriptInterface
        fun checkSubscription(subKey: String): String {
            val data = getGistData()
            val keys = data.optJSONObject("keys") ?: return "invalid"
            val keyData = keys.optJSONObject(subKey) ?: return "invalid"
            val expires = keyData.optLong("expires", 0L)
            if (expires > 0 && System.currentTimeMillis() > expires) return "expired"
            return "ok"
        }

        @JavascriptInterface
        fun getConfig(subKey: String): String {
            val data = getGistData()
            return data.optString("vless", "")
        }

        @JavascriptInterface
        fun adminAction(action: String, dataJson: String): String {
            try {
                val data = JSONObject(dataJson)
                if (data.getString("password") != "ors192289") return "auth"
                val gistData = getGistData()
                var keys = gistData.optJSONObject("keys") ?: JSONObject()
                when (action) {
                    "create" -> {
                        val days = data.optInt("days", 30)
                        val newKey = "sadaw_" + System.currentTimeMillis().toString().takeLast(5)
                        val keyData = JSONObject().put("expires", System.currentTimeMillis() + days * 86400 * 1000L)
                        keys.put(newKey, keyData)
                        gistData.put("keys", keys)
                        saveGistData(gistData)
                        return newKey
                    }
                    "list" -> {
                        val arr = JSONArray()
                        keys.keys().forEach { key ->
                            val kd = keys.getJSONObject(key)
                            arr.put(JSONObject().put("email", key).put("expiryTime", kd.optLong("expires", 0L)).put("enable", true))
                        }
                        return arr.toString()
                    }
                    "updateVless" -> {
                        gistData.put("vless", data.getString("vless"))
                        saveGistData(gistData)
                        return "ok"
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
