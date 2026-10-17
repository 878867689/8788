package com.mcpbridge.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etOwner: EditText
    private lateinit var etRepo: EditText
    private lateinit var etBranch: EditText
    private lateinit var etToken: EditText
    private lateinit var etWorkflow: EditText
    private lateinit var etPort: EditText
    private lateinit var etAuth: EditText
    private lateinit var tvUrl: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etOwner = findViewById(R.id.etOwner)
        etRepo = findViewById(R.id.etRepo)
        etBranch = findViewById(R.id.etBranch)
        etToken = findViewById(R.id.etToken)
        etWorkflow = findViewById(R.id.etWorkflow)
        etPort = findViewById(R.id.etPort)
        etAuth = findViewById(R.id.etAuth)
        tvUrl = findViewById(R.id.tvUrl)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        loadConfig()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveConfig()
            toast("配置已保存")
            refreshStatus()
        }

        btnStart.setOnClickListener {
            saveConfig()
            requestNotificationPermission()
            McpBridgeService.start(this)
            tvLog.postDelayed({ refreshStatus() }, 400)
        }

        btnStop.setOnClickListener {
            McpBridgeService.stop(this)
            tvLog.postDelayed({ refreshStatus() }, 400)
        }

        tvUrl.setOnClickListener {
            val text = tvUrl.text.toString()
            if (text.contains("http")) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mcp", text))
                toast("已复制到剪贴板")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadConfig() {
        etOwner.setText(Config.owner)
        etRepo.setText(Config.repo)
        etBranch.setText(Config.branch)
        etToken.setText(Config.githubToken)
        etWorkflow.setText(Config.workflowFile)
        etPort.setText(Config.port.toString())
        etAuth.setText(Config.authToken)
    }

    private fun saveConfig() {
        Config.owner = etOwner.text.toString()
        Config.repo = etRepo.text.toString()
        Config.branch = etBranch.text.toString()
        Config.githubToken = etToken.text.toString()
        Config.workflowFile = etWorkflow.text.toString()
        Config.port = etPort.text.toString().toIntOrNull() ?: 8388
        Config.authToken = etAuth.text.toString()
        Config.ensureAuthToken()
        etAuth.setText(Config.authToken)
    }

    private fun refreshStatus() {
        val ip = McpBridgeService.getLocalIpv4()
        val url = "http://$ip:${Config.port}/mcp"

        btnStart.isEnabled = !McpBridgeService.running
        btnStop.isEnabled = McpBridgeService.running

        tvUrl.text = if (McpBridgeService.running) {
            "MCP 地址（点击复制）\n$url\n令牌：${Config.authToken}"
        } else {
            "MCP 服务未启动\n配置端口：${Config.port}"
        }

        tvLog.text = buildString {
            append("状态：").append(if (McpBridgeService.running) "运行中" else "已停止").append('\n')
            McpBridgeService.lastError?.let { append("错误：").append(it).append('\n') }
            append('\n')
            append(Config.summary())
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
