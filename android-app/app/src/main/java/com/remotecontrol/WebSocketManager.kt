package com.remotecontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object WebSocketManager {

    private const val TAG = "WebSocketManager"
    private const val RECONNECT_DELAY = 3000L

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var shouldReconnect = false

    private var serverUrl = ""
    private var pairingCode = ""
    private var deviceInfo: JSONObject? = null
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(url: String, code: String, deviceInfo: JSONObject, context: Context) {
        disconnect()

        this.serverUrl = url
        this.pairingCode = code
        this.deviceInfo = deviceInfo
        this.appContext = context.applicationContext
        this.shouldReconnect = true

        doConnect()
    }

    private fun doConnect() {
        if (serverUrl.isEmpty()) return

        val builder = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
        appContext?.let { applySsl(builder, it) }
        val c = builder.build()
        client = c

        val request = Request.Builder().url(serverUrl).build()

        webSocket = c.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnected = true

                val register = JSONObject().apply {
                    put("type", "register")
                    put("role", "device")
                    put("code", pairingCode)
                    deviceInfo?.let { put("deviceInfo", it) }
                }
                ws.send(register.toString())
                ServiceManager.updateStatus("已连接服务器，等待控制端...")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val type = msg.optString("type", "")
                    when (type) {
                        "registered" -> {
                            Log.i(TAG, "Registered on server")
                            // 服务器可能因配对码冲突而重分配新码
                            val code = msg.optString("code", "")
                            if (code.isNotEmpty() && code != pairingCode) {
                                pairingCode = code
                                ServiceManager.onCodeAssigned(code)
                            }
                        }
                        "controller_request" -> ServiceManager.onControllerRequest()
                        "paired" -> ServiceManager.onPairedEvent()
                        "queued" -> {
                            val position = msg.optInt("position", 0)
                            ServiceManager.onQueuedEvent(position)
                        }
                        "controller_disconnected" -> ServiceManager.onControllerDisconnected()
                        "pair_code_expired" -> {
                            shouldReconnect = false
                            ServiceManager.updateStatus("配对码已过期，请重新开始")
                        }
                        else -> ServiceManager.onCommand(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: $text", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
                isConnected = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
                isConnected = false
                if (shouldReconnect) {
                    ServiceManager.updateStatus("正在重连服务器...(关闭码:$code)")
                    scheduleReconnect()
                } else {
                    ServiceManager.updateStatus("连接已断开(码:$code)")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                val code = response?.code ?: -1
                if (shouldReconnect) {
                    ServiceManager.updateStatus("正在重连...(错误:${t.message})")
                    scheduleReconnect()
                } else {
                    ServiceManager.updateStatus("连接失败:${t.message}(码:$code)")
                }
            }
        })
    }

    private fun applySsl(builder: OkHttpClient.Builder, context: Context) {
        try {
            // 信任内置自签证书，实现 WSS 加密传输
            val cf = CertificateFactory.getInstance("X.509")
            val certInput = context.resources.openRawResource(R.raw.server_cert)
            val ca = cf.generateCertificate(certInput)
            certInput.close()

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("ca", ca)

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.trustManagers, null)

            val trustManager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            if (trustManager != null) {
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                // 证书已固定为自签名证书，hostname 校验可放宽（不降低安全性）
                builder.hostnameVerifier { _, _ -> true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "配置证书信任失败: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        mainHandler.postDelayed({
            if (shouldReconnect && !isConnected) {
                Log.i(TAG, "Reconnecting...")
                doConnect()
            }
        }, RECONNECT_DELAY)
    }

    fun sendFrame(data: ByteArray) {
        if (isConnected && webSocket != null) {
            webSocket!!.send(data.toByteString(0, data.size))
        }
    }

    fun sendText(text: String) {
        if (isConnected && webSocket != null) {
            webSocket!!.send(text)
        }
    }

    fun confirmConnection() {
        sendText(JSONObject().apply {
            put("type", "confirm_connection")
        }.toString())
    }

    fun rejectConnection() {
        sendText(JSONObject().apply {
            put("type", "reject_connection")
        }.toString())
    }

    fun disconnect() {
        shouldReconnect = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webSocket?.close(1000, "disconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Close WebSocket failed", e)
        }
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        client = null
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected
}
