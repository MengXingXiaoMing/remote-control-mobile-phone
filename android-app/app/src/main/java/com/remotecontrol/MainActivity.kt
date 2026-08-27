package com.remotecontrol

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.remotecontrol.databinding.ActivityMainBinding
import org.json.JSONArray
import com.remotecontrol.databinding.DialogConfirmBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_SERVER_URL = "wss://你的服务器IP:3000"
        private const val KEY_SERVER_HISTORY = "server_history"
        private const val MAX_HISTORY = 20
    }

    private lateinit var binding: ActivityMainBinding
    private var pairingCode = ""
    private lateinit var historyAdapter: ServerHistoryAdapter

    private val prefs by lazy {
        getSharedPreferences("remote_control", MODE_PRIVATE)
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            ServiceManager.isRunning = true
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_SERVER_URL, serverUrl)
                putExtra(ScreenCaptureService.EXTRA_CODE, pairingCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                @Suppress("DEPRECATION")
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data!!)
            }
            startForegroundService(serviceIntent)
            updateUI()
        } else {
            binding.tvStatus.text = "屏幕捕获权限被拒绝"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupServerHistory()
        binding.etServerUrl.setText(
            prefs.getString("server_url", DEFAULT_SERVER_URL)
        )

        binding.tvClearHistory.setOnClickListener {
            clearServerHistory()
        }

        binding.btnToggle.setOnClickListener {
            if (ServiceManager.isRunning) {
                stopRemoteControl()
            } else {
                startRemoteControl()
            }
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        ServiceManager.onStatusChange = { status ->
            runOnUiThread {
                binding.tvStatus.text = status
                updateUI()
            }
        }
        ServiceManager.onConnectionRequest = {
            runOnUiThread { showConfirmationDialog() }
        }
        ServiceManager.onPaired = {
            runOnUiThread { updateUI() }
        }
        ServiceManager.onCodeAssigned = { code ->
            runOnUiThread {
                pairingCode = code
                binding.tvPairingCode.text = code
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            ServiceManager.onStatusChange = null
            ServiceManager.onConnectionRequest = null
            ServiceManager.onPaired = null
            ServiceManager.onCodeAssigned = null
        }
    }

    private var confirmDialog: AlertDialog? = null
    private var countDownTimer: CountDownTimer? = null

    private fun showConfirmationDialog() {
        // 如果已有对话框，先关闭
        confirmDialog?.dismiss()
        countDownTimer?.cancel()

        val dialogBinding = DialogConfirmBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this, R.style.ConfirmDialogTheme)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        // 3秒倒计时
        val totalSeconds = 3
        dialogBinding.btnAllow.isEnabled = false
        dialogBinding.btnAllow.alpha = 0.5f

        countDownTimer = object : CountDownTimer((totalSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000 + 1).toInt()
                dialogBinding.btnAllow.text = "允许 ($secs)"
            }

            override fun onFinish() {
                dialogBinding.btnAllow.isEnabled = true
                dialogBinding.btnAllow.alpha = 1.0f
                dialogBinding.btnAllow.text = "允许"
            }
        }.start()

        dialogBinding.btnAllow.setOnClickListener {
            WebSocketManager.confirmConnection()
            dialog.dismiss()
        }

        dialogBinding.btnReject.setOnClickListener {
            WebSocketManager.rejectConnection()
            dialog.dismiss()
        }

        confirmDialog = dialog
        dialog.show()
    }

    private fun startRemoteControl() {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage(
                    "远程控制需要开启无障碍服务才能模拟触摸操作。\n\n" +
                    "请点击「去开启」，在列表中找到「远程控制」并打开开关。"
                )
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val serverUrl = binding.etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            binding.tvStatus.text = "请输入服务器地址"
            return
        }
        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            binding.tvStatus.text = "地址需以 ws:// 或 wss:// 开头"
            return
        }

        prefs.edit().putString("server_url", serverUrl).apply()
        addServerToHistory(serverUrl)

        pairingCode = generateCode()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopRemoteControl() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        updateUI()
    }

    private fun generateCode(): String {
        return (100000 + (Math.random() * 900000).toInt()).toString()
    }

    private fun setupServerHistory() {
        historyAdapter = ServerHistoryAdapter(this, getServerHistory()) { url ->
            removeServerFromHistory(url)
            Toast.makeText(this, "已删除: $url", Toast.LENGTH_SHORT).show()
        }
        binding.etServerUrl.setAdapter(historyAdapter)
    }

    private fun getServerHistory(): MutableList<String> {
        val json = prefs.getString(KEY_SERVER_HISTORY, null)
        if (json.isNullOrEmpty()) return mutableListOf(DEFAULT_SERVER_URL)
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "")
                if (item.isNotBlank() && !list.contains(item)) list.add(item)
            }
            if (list.isEmpty()) mutableListOf(DEFAULT_SERVER_URL) else list
        } catch (e: Exception) {
            mutableListOf(DEFAULT_SERVER_URL)
        }
    }

    private fun saveServerHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_SERVER_HISTORY, arr.toString()).apply()
    }

    private fun addServerToHistory(url: String) {
        val list = getServerHistory()
        list.remove(url)
        list.add(0, url)
        val trimmed = if (list.size > MAX_HISTORY) list.subList(0, MAX_HISTORY) else list
        saveServerHistory(trimmed)
        refreshHistoryAdapter()
    }

    private fun removeServerFromHistory(url: String) {
        val list = getServerHistory()
        list.remove(url)
        if (list.isEmpty()) list.add(DEFAULT_SERVER_URL)
        saveServerHistory(list)
        refreshHistoryAdapter()
    }

    private fun clearServerHistory() {
        saveServerHistory(listOf(DEFAULT_SERVER_URL))
        refreshHistoryAdapter()
        binding.etServerUrl.setText(DEFAULT_SERVER_URL)
        Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show()
    }

    private fun refreshHistoryAdapter() {
        historyAdapter.clear()
        historyAdapter.addAll(getServerHistory())
        historyAdapter.notifyDataSetChanged()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val componentName = ComponentName(this, RemoteAccessibilityService::class.java)
        val service = componentName.flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }

    private fun updateUI() {
        if (ServiceManager.isRunning) {
            binding.btnToggle.text = "停止远程控制"
            binding.btnToggle.setBackgroundResource(R.drawable.btn_danger_bg)
            binding.tvPairingCode.text = pairingCode
            binding.layoutCode.visibility = View.VISIBLE
            binding.etServerUrl.isEnabled = false
            binding.tvAccessibilityStatus.visibility = View.GONE
            binding.btnAccessibility.visibility = View.GONE
        } else {
            binding.btnToggle.text = "开始远程控制"
            binding.btnToggle.setBackgroundResource(R.drawable.btn_primary_bg)
            binding.layoutCode.visibility = View.GONE
            binding.etServerUrl.isEnabled = true

            if (isAccessibilityEnabled()) {
                binding.tvAccessibilityStatus.text = "✓ 无障碍服务已开启"
                binding.tvAccessibilityStatus.setTextColor(0xFF4CAF50.toInt())
                binding.btnAccessibility.visibility = View.GONE
            } else {
                binding.tvAccessibilityStatus.text = "✗ 无障碍服务未开启（点击下方按钮开启）"
                binding.tvAccessibilityStatus.setTextColor(0xFFFF5722.toInt())
                binding.btnAccessibility.visibility = View.VISIBLE
            }
            binding.tvAccessibilityStatus.visibility = View.VISIBLE
        }
    }
}

/**
 * 服务器地址历史下拉列表，长按某项可删除
 */
class ServerHistoryAdapter(
    context: Context,
    items: List<String>,
    private val onDelete: (String) -> Unit
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        view.setOnLongClickListener {
            getItem(position)?.let(onDelete)
            true
        }
        return view
    }
}
