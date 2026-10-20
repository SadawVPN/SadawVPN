package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
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

class SadawActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val TAG = "SadawVPN"
    private val REQ_VPN = 1001

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
        fun connect(vlessUri: String): String {
            Log.i(TAG, "connect(): $vlessUri")
            
            Thread {
                try {
                    // 1. Парсим ссылку (parseProxies возвращает List<AbstractBean>)
                    val beans = parseProxies(vlessUri)
                    val bean = beans.firstOrNull() ?: throw Exception("Не удалось распарсить ссылку")

                    // 2. Сохраняем профиль (createProfile - НЕ suspend, вызываем напрямую)
                    val profile = ProfileManager.createProfile(0L, bean)
                    
                    // 3. Выбираем профиль и включаем VPN-режим
                    DataStore.selectedProxy = profile.id
                    DataStore.serviceMode = Key.MODE_VPN

                    // 4. Запрашиваем разрешение и запускаем сервис
                    runOnUiThread {
                        val intent = VpnService.prepare(this@SadawActivity)
                        if (intent != null) {
                            startActivityForResult(intent, REQ_VPN)
                        } else {
                            SagerNet.startService()
                            webView.evaluateJavascript("window.onVpnResult && window.onVpnResult(true)", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при подключении: ${e.message}", e)
                    runOnUiThread {
                        webView.evaluateJavascript("window.onVpnResult && window.onVpnResult(false)", null)
                    }
                }
            }.start()

            return "pending"
        }

        @JavascriptInterface
        fun disconnect() {
            Log.i(TAG, "disconnect()")
            SagerNet.stopService()
            webView.post {
                webView.evaluateJavascript("window.onVpnStopped && window.onVpnStopped()", null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            val ok = resultCode == RESULT_OK
            if (ok) {
                SagerNet.startService()
            }
            webView.post {
                webView.evaluateJavascript("window.onVpnResult && window.onVpnResult($ok)", null)
            }
        }
    }
}
