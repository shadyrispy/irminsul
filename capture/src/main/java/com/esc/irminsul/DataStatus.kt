package com.esc.irminsul

/** Progress of the player-data collection derived from captured packets. */
data class DataStatus(
    val itemsLoaded: Boolean = false,
    val charactersLoaded: Boolean = false,
    val weaponsLoaded: Boolean = false,
    val achievementsLoaded: Boolean = false,
    val artifactsCount: Int = 0,
    val charactersCount: Int = 0,
    val materialsCount: Int = 0,
    val weaponsCount: Int = 0,
    val achievementsCount: Int = 0
)
