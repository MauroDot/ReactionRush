package com.maurointeractive.reactionrush

/** Presentation only: persisted medal names and scoring thresholds remain authoritative. */
internal fun medalArtwork(medal: PerformanceMedal): Int? = when (medal) {
    PerformanceMedal.BRONZE -> R.drawable.rr_medal_bronze
    PerformanceMedal.SILVER -> R.drawable.rr_medal_silver
    PerformanceMedal.GOLD -> R.drawable.rr_medal_gold
    PerformanceMedal.PLATINUM -> R.drawable.rr_medal_platinum
    PerformanceMedal.ELITE -> R.drawable.rr_medal_elite
    PerformanceMedal.UNRANKED -> null
}
