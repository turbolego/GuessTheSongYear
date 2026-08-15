package com.turbolego.songguesser

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import org.hamcrest.Matchers.containsString
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end gameplay coverage. These tests intentionally interact with the
 * rendered activity instead of calling fragment methods, so countdown, input,
 * reveal, multiplayer navigation, and Arcade turn state are tested together.
 */
@RunWith(AndroidJUnit4::class)
class GameplayE2ETest {

    private lateinit var appContext: Context

    @Before
    fun resetPersistentState() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext.getSharedPreferences("game_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        appContext.getSharedPreferences("play_history", Context.MODE_PRIVATE).edit().clear().commit()
        appContext.getSharedPreferences("player_statistics", Context.MODE_PRIVATE).edit().clear().commit()
        appContext.getSharedPreferences("video_source_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        MultiPlayerManager.clear()
    }

    @After
    fun clearPlayers() {
        MultiPlayerManager.clear()
    }

    @Test
    fun soloRound_acceptsGuess_revealsAnswer_andAllowsNextRound() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForCountdown()

            onView(withId(R.id.editTextGuess)).check(matches(isDisplayed()))
            onView(withId(R.id.editTextGuess)).check(matches(isEnabled()))
            onView(withId(R.id.editTextGuess)).perform(replaceText("1999"))
            closeSoftKeyboard()
            onView(withId(R.id.buttonGuess)).perform(click())

            onView(withId(R.id.textViewFeedback)).check(matches(isDisplayed()))
            onView(withId(R.id.buttonNextVideo)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun arcadeRound_revealsSinglePlayerResult_thenRotatesToNextPlayer() {
        GamePreferences.savePlayerNames(appContext, listOf("A", "B"))
        GamePreferences.setGameMode(appContext, GameMode.ARCADE)

        ActivityScenario.launch(MainActivity::class.java).use {
            openActionBarOverflowOrOptionsMenu(appContext)
            onView(withText(R.string.menu_local_multiplayer)).perform(click())
            onView(withId(R.id.buttonStartGame)).check(matches(isEnabled()))
            onView(withId(R.id.buttonStartGame)).perform(click())

            waitForCountdown()
            onView(withId(R.id.textViewArcadeTurn)).check(matches(isDisplayed()))
            onView(withId(R.id.textViewArcadeTurn)).check(matches(withText(containsString("A"))))
            onView(withId(R.id.editTextGuess)).perform(replaceText("2000"))
            closeSoftKeyboard()
            onView(withId(R.id.buttonGuess)).perform(click())
            onView(withId(R.id.buttonNextVideo)).check(matches(isDisplayed()))

            onView(withId(R.id.buttonNextVideo)).perform(click())
            waitForCountdown()
            onView(withId(R.id.textViewArcadeTurn)).check(matches(withText(containsString("B"))))
        }
    }

    private fun waitForCountdown() {
        // The production game uses a deliberate three-second music countdown.
        SystemClock.sleep(3_600L)
    }
}
