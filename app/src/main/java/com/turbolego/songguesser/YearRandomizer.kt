package com.turbolego.songguesser

import kotlin.random.Random

/**
 * Weighted random year generator — exact port of the Spotify version's
 * `prioritizeModernYears()` from RandomSpotifySongTest.
 *
 * Distribution favors modern decades so players get recognizable songs:
 *   2010s: 24%  │  2000s: 18%  │  1990s: 12%  │  1980s: 10%
 *   1970s: 10%  │  1960s: 10%  │  1950s:  6%  │  1940s:  6%
 *   1930s:  2%  │  1920s:  2%
 */
object YearRandomizer {

    private const val MIN_YEAR = 1920
    private const val MAX_YEAR = 2025

    /**
     * Picks a random year weighted toward modern decades.
     * Matches the exact distribution from the Spotify web app.
     */
    fun prioritizeModernYears(): Int {
        val rand = Random.nextFloat()
        return when {
            rand < 0.02f  -> Random.nextInt(1923 - 1920 + 1) + 1920  // 2%  — 1920s
            rand < 0.04f  -> Random.nextInt(1939 - 1930 + 1) + 1930  // 2%  — 1930s
            rand < 0.10f  -> Random.nextInt(1949 - 1940 + 1) + 1940  // 6%  — 1940s
            rand < 0.16f  -> Random.nextInt(1959 - 1950 + 1) + 1950  // 6%  — 1950s
            rand < 0.26f  -> Random.nextInt(1969 - 1960 + 1) + 1960  // 10% — 1960s
            rand < 0.36f  -> Random.nextInt(1979 - 1970 + 1) + 1970  // 10% — 1970s
            rand < 0.46f  -> Random.nextInt(1989 - 1980 + 1) + 1980  // 10% — 1980s
            rand < 0.58f  -> Random.nextInt(1999 - 1990 + 1) + 1990  // 12% — 1990s
            rand < 0.76f  -> Random.nextInt(2009 - 2000 + 1) + 2000  // 18% — 2000s
            else          -> Random.nextInt(MAX_YEAR - 2010 + 1) + 2010  // 24% — 2010s–2025
        }
    }

    /**
     * Same weighting, but constrained to [availableYears] (years present
     * in the video asset file). Falls back to the unrestricted pick if
     * the selected year isn't in the list.
     */
    fun prioritizeModernYears(availableYears: List<Int>): Int {
        if (availableYears.isEmpty()) return 2000
        // Pick via the standard weighting
        val year = prioritizeModernYears()
        if (year in availableYears) return year
        // Closest available year
        return closest(year, availableYears)
    }

    private fun closest(target: Int, years: List<Int>): Int {
        return years.minByOrNull { kotlin.math.abs(it - target) } ?: 2000
    }

    /**
     * Uniform random year between MIN_YEAR and MAX_YEAR (inclusive).
     */
    fun uniform(): Int = Random.nextInt(MAX_YEAR - MIN_YEAR + 1) + MIN_YEAR
}
