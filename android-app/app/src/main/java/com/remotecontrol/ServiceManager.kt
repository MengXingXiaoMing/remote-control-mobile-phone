package com.remotecontrol

import android.util.Log
import org.json.JSONObject

object ServiceManager {

    private const val TAG = "ServiceManager"

    @Volatile
    var accessibilityService: RemoteAccessibilityService? = null
        internal set

    @Volatile
    var isRunning = false
        internal set

    @Volatile
    var isPaired = false
        internal set

    var scaleX = 1f
    var scaleY = 1f

    var onStatusChange: ((String) -> Unit)? = null
    var onConnectionRequest: (() -> Unit)? = null
    var onPaired: (() -> Unit)? = null
    var onCodeAssigned: ((String) -> Unit)? = null

    fun onControllerRequest() {
        onConnectionRequest?.invoke()
        updateStatus("有人请求远程控制，请确认...")
    }

    fun onCodeAssigned(code: String) {
        onCodeAssigned?.invoke(code)
    }

    fun onPairedEvent() {
        isPaired = true
        onPaired?.invoke()
        updateStatus("控制端已连接")
    }

    fun onQueuedEvent(position: Int) {
        isPaired = false
        updateStatus("排队中，前面还有 ${position - 1} 人，请稍候...")
    }

    fun onControllerDisconnected() {
        isPaired = false
        updateStatus("控制端已断开")
    }

    fun onCommand(cmd: JSONObject) {
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "AccessibilityService 未连接，忽略命令")
            return
        }

        try {
            when (cmd.getString("type")) {
                "tap" -> {
                    val x = (cmd.optDouble("x", 0.0) * scaleX).toFloat()
                    val y = (cmd.optDouble("y", 0.0) * scaleY).toFloat()
                    service.tap(x, y)
                }
                "swipe" -> {
                    val x1 = (cmd.optDouble("x1", 0.0) * scaleX).toFloat()
                    val y1 = (cmd.optDouble("y1", 0.0) * scaleY).toFloat()
                    val x2 = (cmd.optDouble("x2", 0.0) * scaleX).toFloat()
                    val y2 = (cmd.optDouble("y2", 0.0) * scaleY).toFloat()
                    val duration = cmd.optLong("duration", 300L)
                    service.swipe(x1, y1, x2, y2, duration)
                }
                "swipe_move" -> { }
                "keyevent" -> {
                    service.performAction(cmd.optString("action", ""))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理命令失败", e)
        }
    }

    fun sendFrame(data: ByteArray) {
        WebSocketManager.sendFrame(data)
    }

    fun updateStatus(status: String) {
        Log.i(TAG, "状态: $status")
        onStatusChange?.invoke(status)
    }
}
