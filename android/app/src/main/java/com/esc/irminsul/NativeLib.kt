package com.esc.irminsul

import android.content.Context
import android.util.Log
import java.io.File

object NativeLib {
    private const val TAG = "NativeLib"
    private const val DATA_CACHE_ASSET = "data_cache.json"
    private const val CACHE_SUBDIR = "irminsul_data_cache"

    private var libraryLoaded = false
    private var libraryLoadAttempted = false
    private var logCallback: LogCallback? = null
    private var dataCompleteCallback: DataCompleteCallback? = null

    @Volatile
    private var appContext: Context? = null

    interface LogCallback {
        fun onLog(message: String)
    }

    interface DataCompleteCallback {
        fun onDataComplete(
            artifactCount: Int,
            weaponCount: Int,
            materialCount: Int,
            characterCount: Int,
            achievementCount: Int
        )
    }

    @Synchronized
    private fun ensureLibraryLoaded() {
        if (libraryLoadAttempted) {
            return
        }
        libraryLoadAttempted = true

        try {
            System.loadLibrary("irminsul")
            libraryLoaded = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not found: " + e.message)
            libraryLoaded = false
        }
    }

    fun isAvailable(): Boolean {
        ensureLibraryLoaded()
        return libraryLoaded
    }

    fun setLogCallback(callback: LogCallback?) {
        logCallback = callback
    }

    fun setDataCompleteCallback(callback: DataCompleteCallback?) {
        dataCompleteCallback = callback
    }

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun log(message: String) {
        logCallback?.onLog(message)
    }

    @JvmStatic
    fun onDataComplete(
        artifactCount: Int,
        weaponCount: Int,
        materialCount: Int,
        characterCount: Int,
        achievementCount: Int
    ) {
        dataCompleteCallback?.onDataComplete(
            artifactCount,
            weaponCount,
            materialCount,
            characterCount,
            achievementCount
        )
    }

    /**
     * Read the bundled `assets/data_cache.json` snapshot. Returned as a byte
     * array that the native side can parse on first run, when no usable
     * local cache exists and the network is unavailable.
     */
    @JvmStatic
    fun readBundledDataCache(): ByteArray? {
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open(DATA_CACHE_ASSET).use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled data_cache.json asset: ${e.message}")
            null
        }
    }

    /**
     * Returns the directory used to persist `data_cache.json` and its meta
     * file. Falls back to `context.cacheDir` if `filesDir` is unavailable.
     */
    fun cacheDirPath(context: Context): String {
        val base = context.filesDir ?: context.cacheDir
        val dir = File(base, CACHE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.absolutePath
    }

    fun initLogging() {
        ensureLibraryLoaded()
        if (libraryLoaded) {
            nativeInitLogging()
        }
    }

    /**
     * Initialise the native sniffer and load `data_cache.json`.
     *
     * @return a status JSON object containing `ok`, `error`, and
     *   `data_cache_source/version/git_hash`. Returns null on link failure.
     */
    fun createSniffer(context: Context): String? {
        ensureLibraryLoaded()
        if (!libraryLoaded) return null
        attachContext(context)
        return nativeCreateSniffer(cacheDirPath(context))
    }

    fun refreshDataCache(context: Context): String? {
        ensureLibraryLoaded()
        if (!libraryLoaded) return null
        attachContext(context)
        return nativeRefreshDataCache(cacheDirPath(context))
    }

    /**
     * Process a raw packet from VPN capture.
     * Returns a JSON status string with current data counts, or null if no match.
     */
    fun processPacket(packetData: ByteArray): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeProcessPacket(packetData)
        } else {
            null
        }
    }

    /**
     * Export data in GOOD v3 JSON format.
     * @param settingsJson JSON string with export settings, or null for defaults.
     * @return GOOD JSON string, or null on failure.
     */
    fun exportGood(settingsJson: String? = null): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeExportGood(settingsJson)
        } else {
            null
        }
    }

    /**
     * Export achievements in the specified format.
     * @param formatCode 0 = UIAF, 1 = Seelie, 2 = CSV
     * @return Export string, or null on failure.
     */
    fun exportAchievements(formatCode: Int): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeExportAchievements(formatCode)
        } else {
            null
        }
    }

    fun destroySniffer() {
        if (libraryLoaded) {
            nativeDestroySniffer()
        }
    }

    @JvmStatic
    private external fun nativeInitLogging()

    @JvmStatic
    private external fun nativeCreateSniffer(cacheDir: String): String?

    @JvmStatic
    private external fun nativeRefreshDataCache(cacheDir: String): String?

    @JvmStatic
    private external fun nativeProcessPacket(packetData: ByteArray): String?

    @JvmStatic
    private external fun nativeExportGood(settingsJson: String?): String?

    @JvmStatic
    private external fun nativeExportAchievements(formatCode: Int): String?

    @JvmStatic
    private external fun nativeDestroySniffer()
}
