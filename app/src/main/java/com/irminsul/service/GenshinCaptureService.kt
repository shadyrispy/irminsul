package com.irminsul.service

import android.net.VpnService
import android.net.VpnService.Builder
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.app.PendingIntent
import com.irminsul.jni.ParserBridge
import java.io.FileInputStream
import kotlinx.coroutines.*

class GenshinCaptureService : VpnService() {

    companion object {
        private const val TAG = "GenshinCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "irminsul_capture_channel"
        
        // Action常量
        const val ACTION_START = "com.irminsul.START_CAPTURE"
        const val ACTION_STOP = "com.irminsul.STOP_CAPTURE"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isCapturing = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    // 捕获线程
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Starting capture...")
                startCapture()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping capture...")
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 启动VPN捕获
     */
    private fun startCapture() {
        if (isCapturing) {
            Log.w(TAG, "Capture already running")
            return
        }

        try {
            // 建立VPN接口
            val builder = Builder()
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.irminsul.presentation.ui.main.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            builder.setSession("Irminsul")
                .setConfigureIntent(pendingIntent)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0) // 捕获所有流量

            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isCapturing = true
            
            // 启动前台服务通知
            startForeground(NOTIFICATION_ID, createNotification("正在捕获原神流量..."))
            
            // 启动数据包处理协程
            captureJob = serviceScope.launch {
                processPackets()
            }
            
            Log.d(TAG, "Capture started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture: ${e.message}")
            stopCapture()
        }
    }

    /**
     * 停止VPN捕获
     */
    private fun stopCapture() {
        isCapturing = false
        
        // 取消捕获协程
        captureJob?.cancel()
        captureJob = null
        
        // 关闭VPN接口
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        Log.d(TAG, "Capture stopped")
    }

    /**
     * 处理捕获的数据包
     */
    private suspend fun processPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        
        withContext(Dispatchers.IO) {
            try {
                val inputStream = FileInputStream(fd)
                val buffer = ByteArray(4096)
                
                while (isCapturing && !Thread.currentThread().isInterrupted) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        // 将数据包发送到Rust库进行解析
                        val packetData = buffer.copyOfRange(0, length)
                        val result = ParserBridge.parse(packetData)
                        if (result != null) {
                            Log.d(TAG, "Parsed packet: $result")
                            // 处理解析结果
                            processParsedData(result)
                        }
                    }
                }
                
                inputStream.close()
            } catch (e: Exception) {
                if (isCapturing) {
                    Log.e(TAG, "Error processing packets: ${e.message}")
                }
            }
        }
    }

    /**
     * 处理解析后的数据
     */
    private fun processParsedData(json: String) {
        try {
            val data = org.json.JSONObject(json)
            val type = data.optString("type", "")
            
            when (type) {
                "character" -> {
                    val entity = com.irminsul.data.parser.GameDataParser.parseCharacter(json)
                    entity?.let {
                        Log.d(TAG, "Parsed character: ${it.name}")
                        // TODO: 存储到数据库
                    }
                }
                "weapon" -> {
                    val entity = com.irminsul.data.parser.GameDataParser.parseWeapon(json)
                    entity?.let {
                        Log.d(TAG, "Parsed weapon: ${it.name}")
                        // TODO: 存储到数据库
                    }
                }
                "artifact" -> {
                    val entity = com.irminsul.data.parser.GameDataParser.parseArtifact(json)
                    entity?.let {
                        Log.d(TAG, "Parsed artifact: ${it.setName}")
                        // TODO: 存储到数据库
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown data type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing parsed data: ${e.message}")
        }
    }

    /**
     * 创建通知渠道（Android O+需要）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Irminsul流量捕获",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于原神流量捕获的服务通知"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(contentText: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Irminsul")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Irminsul")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}
