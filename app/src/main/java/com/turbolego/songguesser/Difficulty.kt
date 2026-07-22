package com.turbolego.songguesser

/**
 * Difficulty levels for the game.
 */
enum class Difficulty(
    val label: String,
    val pointMultiplier: Float,
    val yearRangeStart: Int,
    val yearRangeEnd: Int,
    val hintEnabled: Boolean
) {
    EASY("Lett", 1.0f, 1980, 2010, true),
    MEDIUM("Medium", 1.5f, 1970, 2024, false),
    HARD("Vanskelig", 2.5f, 1960, 2025, false);

    companion object {
        fun fromLabel(label: String): Difficulty =
            entries.find { it.label == label } ?: MEDIUM
    }
}
