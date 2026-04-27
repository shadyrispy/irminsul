package com.irminsul.jni

import android.util.Log

/**
 * Rust库JNI桥接类
 * 负责加载so库并调用native方法
 * 基于 auto-artifactarium 实现数据包解析
 */
object ParserBridge {
    private const val TAG = "ParserBridge"

    init {
        try {
            System.loadLibrary("irminsul_parser")
            Log.d(TAG, "Native library loaded successfully")
            // 打印版本信息
            getVersion()?.let { Log.d(TAG, "Version: $it") }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /**
     * 初始化解析器
     * @param keyData 初始密钥数据 (JSON字符串)，可为空
     * @return 是否成功
     */
    external fun initParser(keyData: String?): Boolean

    /**
     * 解析数据包
     * @param packetData 数据包字节数组
     * @return 解析后的JSON字符串，失败返回null
     */
    external fun parsePacket(packetData: ByteArray): String?

    /**
     * 获取库版本
     * @return 版本字符串
     */
    external fun getVersion(): String?

    /**
     * 释放字符串内存（CString）
     * @param strPtr 字符串指针
     */
    external fun freeString(strPtr: Long)

    /**
     * 测试函数：测试解析器是否正常工作
     * @return 测试结果JSON
     */
    external fun testParse(): String?

    /**
     * 测试函数：解析示例数据包
     * @return 测试结果JSON
     */
    external fun testParseSamplePacket(): String?

    // Kotlin包装方法
    fun init(keyData: String? = null): Boolean {
        return try {
            initParser(keyData ?: "null")
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
            false
        }
    }

    fun parse(data: ByteArray): String? {
        return try {
            parsePacket(data)
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
            null
        }
    }

    /**
     * 运行完整测试套件
     * @return 测试结果Map
     */
    fun runTests(): Map<String, Any> {
        return try {
            val results = mutableMapOf<String, Any>()

            // 测试1: 基础初始化
            results["init"] = init(null)

            // 测试2: 版本信息
            results["version"] = getVersion() ?: "unknown"

            // 测试3: 测试解析函数
            results["testParse"] = testParse().let {
                it != null && it.contains("ok")
            }

            // 测试4: 示例数据包解析
            results["testSamplePacket"] = testParseSamplePacket()?.let {
                it.contains("test") || it.contains("none")
            } ?: false

            results
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }
}