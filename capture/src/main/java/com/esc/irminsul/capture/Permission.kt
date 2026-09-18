package com.esc.irminsul.capture

/**
 * A permission the capture flow needs, named by what the user must fix rather
 * than by the Android mechanism that grants it.
 */
enum class PermissionKind {
    /** POST_NOTIFICATIONS / notification access. */
    Notifications,

    /** Heads-up (banner) display for the completion channel. */
    HeadsUp,

    /** The VPN consent that lets the capture service bind. */
    Vpn,

    /** Battery-optimization exemption, so a long capture is not killed. */
    BatteryOptimization,

    /** Auto-start allowance; only meaningful on ROMs that gate it. */
    AutoStart,

    /** Last-resort app details page. */
    AppDetails
}

/**
 * State of everything the capture flow can be blocked on. Emitted by
 * [IrminsulCapture.permissions] after a refresh.
 */
data class PermissionSnapshot(
    val notificationGranted: Boolean = false,
    val headsUpEnabled: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val batteryOptimizationExempt: Boolean = true,
    val needsAutoStart: Boolean = false,
    /** ROM-specific guidance, empty on stock Android. */
    val romHint: String = ""
) {
    val allRequiredGranted: Boolean
        get() = notificationGranted && headsUpEnabled && vpnPermissionGranted
    val allRecommendedGranted: Boolean
        get() = batteryOptimizationExempt && !needsAutoStart
    val allGranted: Boolean
        get() = allRequiredGranted && allRecommendedGranted
}

/** Everything the pipeline collected once the game's data is complete. */
data class Completion(
    val charactersCount: Int,
    val artifactsCount: Int,
    val weaponsCount: Int,
    val achievementsCount: Int,
    val materialsCount: Int
)

/** Outcome of loading the native library and creating the sniffer. */
sealed interface InitResult {
    data object Ready : InitResult
    /** The `.so` is not installed for this ABI. */
    data object NotInstalled : InitResult

    /** Loaded, but the sniffer could not be created; `code` is the native status. */
    data class Failed(val code: Int) : InitResult
}
