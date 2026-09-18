package com.esc.irminsul

/**
 * Receives player-data status updates derived from captured packets. The
 * capture module reports into this sink; the host app implements it with its
 * own state holder, keeping the module free of app-level storage.
 */
interface DataStatusSink {
    fun updateStatus(
        itemsLoaded: Boolean,
        charactersLoaded: Boolean,
        achievementsLoaded: Boolean,
        artifactsCount: Int,
        weaponsCount: Int,
        materialsCount: Int,
        charactersCount: Int,
        achievementsCount: Int
    )
}
