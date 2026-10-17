package com.mcpbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcpbridge.android.mcp.McpServer
import com.mcpbridge.android.mcp.buildToolRegistry
import fi.iki.elonen.NanoHTTPD
import java.net.NetworkInterface

/**
 * 前台服务：常驻托管 MCP Server（Streamable HTTP）。
 */
class McpBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "mcp_bridge"
        private const val NOTIF_ID = 1001

        @Volatile
        var running: Boolean = false

        @Volatile
        var lastError: String? = null

        fun start(context: Context) {
            val intent = Intent(context, McpBridgeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpBridgeService::class.java))
        }

        /** 取本机局域网 IPv4，便于拼出可访问的 MCP 地址。 */
        fun getLocalIpv4(): String {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.hostAddress
                    ?: "127.0.0.1"
            } catch (t: Throwable) {
                "127.0.0.1"
            }
        }
    }

    private var server: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (server == null) {
            try {
                val srv = McpServer(
                    port = Config.port,
                    authToken = Config.ensureAuthToken(),
                    registry = buildToolRegistry(this)
                )
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = srv
                running = true
                lastError = null
            } catch (t: Throwable) {
                running = false
                lastError = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        server = null
        running = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP 服务",
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP Android Bridge 运行中")
            .setContentText("http://${getLocalIpv4()}:${Config.port}/mcp")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
