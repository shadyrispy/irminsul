package com.esc.irminsul.capture.internal

import android.util.Log

internal object NativeLib {
    private const val TAG = "NativeLib"
    private var libraryLoaded = false
    private var libraryLoadAttempted = false
    private var logCallback: LogCallback? = null
    private var dataCompleteCallback: DataCompleteCallback? = null

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

    fun initLogging() {
        ensureLibraryLoaded()
        if (libraryLoaded) {
            nativeInitLogging()
        }
    }

    /**
     * Creates the native sniffer. [storageDir] is where it keeps the command
     * bodies it uses to open sessions whose seed it never saw; those have to
     * outlive the process, so the sniffer needs a directory to write them to.
     */
    fun createSniffer(storageDir: String): Int {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeCreateSniffer(storageDir)
        } else {
            -1
        }
    }

    /**
     * Clears the native per-session flags (collection progress booleans and the
     * completion edge) without discarding collected player data.
     */
    fun resetSession() {
        ensureLibraryLoaded()
        if (libraryLoaded) {
            nativeResetSession()
        }
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
    fun exportGood(settingsJson: String?): String? {
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

    /**
     * Return the full JSON (including the decoded proto body) of a single
     * cached command, or null when the packet was evicted or the index is
     * out of range.
     */
    fun commandBody(packetId: Long, commandIndex: Int): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeCommandBody(packetId, commandIndex)
        } else {
            null
        }
    }

    /**
     * How the key decrypting the current session was obtained — "dispatch",
     * "known_body", "time_search" — or null when no session key exists yet.
     * Cheap enough to read after every packet, which is the only way to notice a
     * client re-logging in and taking the key with it.
     */
    fun keyOrigin(): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeKeyOrigin()
        } else {
            null
        }
    }

    /**
     * Copies the session's known-body samples into [destDir] and returns the path
     * of the copy, or null when there is nothing to hand over. The app's own files
     * dir is unreachable on devices that refuse `run-as`, and these samples decide
     * whether a capture that missed a handshake is readable at all.
     */
    fun exportKnownBodies(destDir: String): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeExportKnownBodies(destDir)
        } else {
            null
        }
    }

    /** Opens a pcap for replay; -1 when it is not a readable pcap (see `logs`). */
    fun pcapOpen(path: String): Long {
        ensureLibraryLoaded()
        return if (libraryLoaded) nativePcapOpen(path) else -1L
    }

    /** The next frame, or null at the end. [timestampOut] receives the file's own ms. */
    fun pcapNext(handle: Long, timestampOut: LongArray): ByteArray? {
        ensureLibraryLoaded()
        return if (libraryLoaded) nativePcapNext(handle, timestampOut) else null
    }

    fun pcapClose(handle: Long) {
        if (libraryLoaded) {
            nativePcapClose(handle)
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
    private external fun nativeCreateSniffer(storageDir: String): Int

    @JvmStatic
    private external fun nativeResetSession()

    @JvmStatic
    private external fun nativeProcessPacket(packetData: ByteArray): String?

    @JvmStatic
    private external fun nativeCommandBody(packetId: Long, commandIndex: Int): String?

    @JvmStatic
    private external fun nativeKeyOrigin(): String?

    @JvmStatic
    private external fun nativeExportGood(settingsJson: String?): String?

    @JvmStatic
    private external fun nativeExportAchievements(formatCode: Int): String?

    @JvmStatic
    private external fun nativeExportKnownBodies(destDir: String): String?

    @JvmStatic
    private external fun nativePcapOpen(path: String): Long

    @JvmStatic
    private external fun nativePcapNext(handle: Long, timestampOut: LongArray): ByteArray?

    @JvmStatic
    private external fun nativePcapClose(handle: Long)

    @JvmStatic
    private external fun nativeDestroySniffer()
}
