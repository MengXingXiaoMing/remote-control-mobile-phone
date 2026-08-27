package com.remotecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilitySvc"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceManager.accessibilityService = this
        Log.i(TAG, "无障碍服务已连接")
        ServiceManager.updateStatus("无障碍服务已就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理事件，仅用于触摸注入
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ServiceManager.accessibilityService = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    /**
     * 在指定坐标点击
     */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "tap($x, $y) => $result")
    }

    /**
     * 从 (x1,y1) 滑动到 (x2,y2)
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val dur = durationMs.coerceIn(50L, 5000L)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "swipe($x1,$y1 -> $x2,$y2, ${dur}ms) => $result")
    }

    /**
     * 执行系统按键操作
     */
    fun performAction(action: String) {
        val globalAction = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> {
                Log.w(TAG, "未知操作: $action")
                return
            }
        }
        val result = performGlobalAction(globalAction)
        Log.d(TAG, "performAction($action) => $result")
    }
}
