package com.turbolego.songguesser

import kotlin.math.abs

/**
 * Manages scoring for the game.
 */
object ScoreManager {

    private var totalScore = 0
    private var currentStreak = 0
    private var highestStreak = 0
    private var totalGuesses = 0
    private var correctGuesses = 0

    data class GuessResult(
        val pointsEarned: Int,
        val difference: Int,
        val messageResId: Int,
        val messageArgs: List<Any>,
        val isCorrect: Boolean
    )

    /**
     * Calculate score based on year guess accuracy.
     */
    fun evaluateGuess(guessedYear: Int, actualYear: Int, difficulty: Difficulty): GuessResult {
        val diff = abs(guessedYear - actualYear)
        totalGuesses++

        val points: Int
        val messageResId: Int
        val messageArgs: List<Any>
        val isCorrect: Boolean

        when {
            diff == 0 -> {
                currentStreak++
                if (currentStreak > highestStreak) highestStreak = currentStreak
                correctGuesses++
                points = (50 * difficulty.pointMultiplier * (1 + currentStreak * 0.1)).toInt()
                messageResId = R.string.correct_exact
                messageArgs = emptyList()
                isCorrect = true
            }
            diff <= 1 -> {
                currentStreak++
                if (currentStreak > highestStreak) highestStreak = currentStreak
                correctGuesses++
                points = (30 * difficulty.pointMultiplier).toInt()
                messageResId = R.string.correct_very_close
                messageArgs = listOf(diff)
                isCorrect = true
            }
            diff <= 3 -> {
                currentStreak++
                correctGuesses++
                points = (20 * difficulty.pointMultiplier).toInt()
                messageResId = R.string.correct_close
                messageArgs = listOf(diff)
                isCorrect = true
            }
            diff <= 5 -> {
                currentStreak = 0
                points = (10 * difficulty.pointMultiplier).toInt()
                messageResId = R.string.correct_ok
                messageArgs = listOf(diff)
                isCorrect = false
            }
            diff <= 10 -> {
                currentStreak = 0
                points = (5 * difficulty.pointMultiplier).toInt()
                messageResId = R.string.correct_ok
                messageArgs = listOf(diff)
                isCorrect = false
            }
            else -> {
                currentStreak = 0
                points = 0
                messageResId = R.string.wrong
                messageArgs = listOf(actualYear)
                isCorrect = false
            }
        }

        totalScore += points

        return GuessResult(
            pointsEarned = points,
            difference = diff,
            messageResId = messageResId,
            messageArgs = messageArgs,
            isCorrect = isCorrect
        )
    }

    val score: Int get() = totalScore
    val streak: Int get() = currentStreak
    val highStreak: Int get() = highestStreak
    val guessCount: Int get() = totalGuesses
    val correctCount: Int get() = correctGuesses
    val accuracy: Float get() =
        if (totalGuesses == 0) 0f else correctGuesses.toFloat() / totalGuesses

    fun reset() {
        totalScore = 0
        currentStreak = 0
        highestStreak = 0
        totalGuesses = 0
        correctGuesses = 0
    }
}
