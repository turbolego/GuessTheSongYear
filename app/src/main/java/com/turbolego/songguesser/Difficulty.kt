package com.turbolego.songguesser

import androidx.annotation.StringRes

/**
 * Difficulty levels for the game.
 */
enum class Difficulty(
    @StringRes val labelResId: Int,
    val pointMultiplier: Float,
    val yearRangeStart: Int,
    val yearRangeEnd: Int,
    val hintEnabled: Boolean
) {
    EASY(R.string.difficulty_easy, 1.0f, 1980, 2010, true),
    MEDIUM(R.string.difficulty_medium, 1.5f, 1970, 2024, false),
    HARD(R.string.difficulty_hard, 2.5f, 1960, 2025, false);
}