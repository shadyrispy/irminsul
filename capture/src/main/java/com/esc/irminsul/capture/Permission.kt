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
}

/** Everything the pipeline collected once the game's data is complete. */
data class Completion(
    val charactersCount: Int,
    val artifactsCount: Int,
    val weaponsCount: Int,
    val achievementsCount: Int,
    val materialsCount: Int
)
