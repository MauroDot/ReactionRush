package com.maurointeractive.reactionrush

enum class GameMode(
    val title: String,
    val rounds: Int,
    val description: String,
    val recordKey: String,
    val recordLabel: String
) {
    QUICK_DRAW(
        "QUICK DRAW",
        1,
        "One shot. One reaction. Chase your fastest time.",
        "record_quick_draw",
        "BEST TIME"
    ),
    CLASSIC_RUSH(
        "CLASSIC RUSH",
        5,
        "Five rounds. Balance raw speed with consistency.",
        "record_classic_rush",
        "BEST AVERAGE"
    ),
    ENDURANCE_RUSH(
        "ENDURANCE RUSH",
        10,
        "Ten rounds. Stay sharp and protect your average.",
        "record_endurance_rush",
        "BEST AVERAGE"
    )
}
