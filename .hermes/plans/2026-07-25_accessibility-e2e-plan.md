# Android Accessibility Implementation Plan — Blind User E2E Testing

> **For Hermes:** Use plan mode tasks to implement accessibility fixes AND E2E tests, working through tasks sequentially.

**Goal:** Make GuessTheSongYear fully navigable and playable by blind users via TalkBack, then write instrumented E2E tests (Espresso + ATF) that prove a blind user can play from launch to score.

** **Architecture:** Two phases: (1) Fix every view's accessibility properties (contentDescription, focus order, live regions for dynamic content) so TalkBack announces correctly. (2) Add `espresso-accessibility` and `AccessibilityChecks.enable()` to test suite, then write explicit E2E test scenarios simulating a TalkBack user's gameplay.

**Tech Stack:** Android Views (XML), Espresso 3.7.0, Accessibility Test Framework, JUnit4, Android Gradle Plugin 9.3

---

## Phase 1 — Accessibility Fixes

### What Makes an Android App Accessible to Blind Users

Core principles:
1. **`contentDescription`** — every interactive element AND meaningful image must have a text description that TalkBack reads
2. **Focus order** — when Tab-ing or swiping with TalkBack, the order must match visual/reading order
3. **Live regions** — dynamic content (score changes, feedback, countdown) must announce automatically via `AccessibilityLiveRegion` or `announceForAccessibility()`
4. **Headers and sections** — use `accessibilityRole = "header"` on section titles
5. **Touch target size** — minimum 48dp × 48dp
6. **Non-color cues** — don't rely solely on color; use text/shape too
7. **Label/hint on editable fields** — `contentDescription` = purpose, `hint` = example input
8. **Group related info** — announce once per screen region (merges, accessibilityNodeInfo)

### Task 1.1: Add accessibility labels to fragment_video_player.xml

**Objective:** Fix the main game screen for TalkBack — every interactive element gets a contentDescription, the player area gets labels, and the dynamic areas are live-enabled.

**Files to modify:**
- `app/src/main/res/layout/fragment_video_player.xml`

**Changes:**

```xml
<!-- Toggle Video Button — add contentDescription -->
<TextView
    android:id="@+id/buttonToggleVideo"
    android:contentDescription="@string/a11y_hide_video"
    ... />

<!-- Song Title — mark as heading for quick navigation -->
<TextView
    android:id="@+id/textViewSongTitle"
    android:accessibilityHeading="true"
    ... />

<!-- EditText guess — add contentDescription -->
<EditText
    android:id="@+id/editTextGuess"
    android:contentDescription="@string/a11y_year_input"
    ... />

<!-- Button guess — add contentDescription -->
<Button
    android:id="@+id/buttonGuess"
    android:contentDescription="@string/a11y_submit_guess"
    ... />

<!-- Button next video — add contentDescription -->
<Button
    android:id="@+id/buttonNextVideo"
    android:contentDescription="@string/a11y_next_song"
    ... />

<!-- Button reveal answers — add contentDescription -->
<Button
    android:id="@+id/buttonRevealAnswers"
    android:contentDescription="@string/a11y_reveal_answers"
    ... />

<!-- Feedback text — live region for auto-announcement -->
<TextView
    android:id="@+id/textViewFeedback"
    android:accessibilityLiveRegion="polite"
    ... />

<!-- Score text — live region -->
<TextView
    android:id="@+id/textViewScore"
    android:accessibilityLiveRegion="polite"
    ... />
```

Need 2 × new strings (nb + en):

```xml
<!-- values/strings.xml -->
<string name="a11y_hide_video">Skjul video</string>
<string name="a11y_year_input">Skriv inn gjettet år, 1960 til 2025</string>
<string name="a11y_submit_guess">Send inn gjett</string>
<string name="a11y_next_song">Neste sang</string>
<string name="a11y_reveal_answers">Vis svar for alle spiller</string>

<!-- values-en/strings.xml -->
<string name="a11y_hide_video">Hide video</string>
<string name="a11y_year_input">Enter, year, guess, 1960, through 2025</string>
<string name="a11y_submit_guess">Submit, guess</string>
<string name="a11y_next_song">Next, song</string>
<string name="a11y_reveal_answers">Reveal, answers</string>
```

**Also needed — add `android:contentDescription` to button `✕`** in item_player_row.xml:

```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/buttonRemovePlayer"
    android:contentDescription="@string/a11y_remove_player"
    ... />
```

```xml
<!-- Strings -->
<string name="a11y_remove_player"</string> <!-- nb --> "Fjern spiller"; <!-- en: --> "Remove player"> 
```

### Task 1.2: Dynamic announcements in VideoPlayerFragment.kt

**Objective:** Use `announceForAccessibility()` on feedback changes, score updates, and countdown so a blind user gets real-time audio feedback without needing to focus on any element.

**File:** `app/src/main/java/com/turbolego/songguesser/VideoPlayerFragment.kt`

**Implementation** — add a helper method:

```kotlin
private fun announceForAccessibility(text: String) {
    binding.playerView?.announceForAccessibility(text) ?: run {
        binding.playerRoot.announceForAccessibility(text)
    }
}
```

Then call `announceForAccessibility()`:
- When feedback text is shown (score awarded / miss) → announce feedback string
- When countdown starts → announce "3", "2", "1", "Start"
- When a new song loads → announce title
- When multiplayer turn changes → announce player name
- When game finishes → announce winner + score

### Task 1.3: Handle player-list RecyclerViews for accessibility

**File:** item_player_guess.xml

Add contentDescription to EditText per player:

```xml
<!-- Programmatically set via PlayerSetupAdapter: a11y_year_input_for_X -->
<EditText
    android:id="@+id/editTextYear"
    android:contentDescription="@string/a11y_year_input_for_player"
    android:hint="@string/year_hint"
    ... />

<!-- Result text — also live region -->
<TextView
    android:id="@+id/textViewPlayerResult"
    android:accessibilityLiveRegion="true"
    ... />
```

**File:** `PlayerSetupAdapter.kt` — set the per-player contentDescription:

```kotlin
override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.editTextYear.contentDescription = 
        "$playerName: ${context.getString(R.string.a11y_year_input)}"
}
```

**Strings to add:**

```xml
<string name="a11y_year_input_for_player">Spiller, s, %s: Skriv inn år</string>  <!-- nb -->
<string name="a11y_year_input_for_player">Player, s, %s: Enter, year</string>  <!-- en -->
```

### Task 1.4: Game session menu / status screen accessibility

**Files:**
- `fragment_multiplayer_setup.xml` — add contentDescription to "✕" button
- `fragment_host_game.xml` — add contentDescription to copy-IP and QR-image
- `fragment_debug.xml` — sanity-check focus order

---

## Phase 2 — E2E Tests with AccessibilityChecks

### Discussion: How to Test Accessibility for Blind Users

Accessibility testing works at two levels:

1. **ATF checks (automatic)** — `AccessibilityChecks.enable()` lets Espresso participate in every ViewAction — a test that clicks/navigates automatically runs the Accessibility Test Framework on each View touched. This catches: missing `contentDescription`, too-small touch targets, low contrast, unlabelled control elements.

2. **Explicit flow tests (human-reviewed logic)** — They simulate the actual TalkBack user flow: focus-order from element to element, checking that the right `contentDescription` is on the correct view, and that `announceForAccessibility()` happened.

We'll write **one general A11y smoke test** (unit) + **one full-game flow test** (E2E instrumented).

### Task 2.1: Add ATF + espresso-accessibility dependency

**File:** `gradle/libs.versions.toml`

```toml
[versions]
accessibilityTestFramework = "4.1.2"

[libraries]
androidx-espresso-accessibility = { group = "androidx.test.espresso", name = "espresso-accessibility", version = "3.7.0" }
atf = { group = "com.google.android.apps.common.testing.accessibility.framework", name = "accessibility-test-framework", version.ref = "accessibilityTestFramework" }
atf-espresso = { group = "com.google.android.apps.common.testing.accessibility.framework", name = "accessibility-test-framework-espresso", version.ref = "accessibilityTestFramework" }
```

**File:** `app/build.gradle.kts` — add:
```kotlin
androidTestImplementation(libs.espressoAccessibility)
androidTestImplementation(libs.atf)
androidTestImplementation(libs.atfEspresso)
```

### Task 2.2: Create `AccessibilitySmokeInstrumentedTest.kt`

**Custom runner with Accessibility Checks enabled automatically for all tests coupled with this smoke suite:**

```kotlin
package com.turbognom.songguesser

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils.matchesViews
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AccessibilitySmokeTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableAccessibilityChecks() {
            AccessibilityChecks.enable().setRunChecksFromRootView(true)
        }
    }

    // ── 1. Home screen elements have labels ─────────────────────────────

    @Test
    fun `a01 singlePlayer screen shows title and guess area with labels`() {
        ActivityScenario.launch(MainActivity::class.java)

        // Verify the inf opening screen:
        // title , song info section
        onView(withId(R.id.textViewSongTitle))        // focuses → announcement
            .check(matches(isDisplayed()))
            .check(matches(withContentDescription(anything())))  // Must have label

        // guess input
        onView(withId(R.id.editTextGuess))
            .check(matches(isDisplayed()))
            .check(matches(withContentDescription(anything())))

        // guess button
        onView(withId(R.id.buttonGuess))
            .check(matches(isDisplayed()))
            .check(matches(withContentDescription(anything())))

        // hide video button
        onView(withId(R.id.buttonToggleVideo))
            .check(matches(isDisplayed()))
            .check(matches(withContentDescription(anything())))

        // Double-check focus — TODO later
    }

    // ── 2. Screen elements have adequate touch target ────────────────────
    
    @Test
    fun `b_buttonsAndInputs_exceedMinimumTouchTargetsize` context() {
        ActivityScenario.launch(MainActivity::class.java)

        // Already by ATF, but extra explicit
        onView(withId(R.id.buttonGuess)).check(matches(withEffectiveWidth(atLeast(96))))
        onView(withId(R.id.editTextGuess)).check(matches(withEffectiveHeight(atLeast(48))))
    }

    // ── 3. Multiplayer setup screen navigation ──────────────────────────

    @Test
    fun `c_multiplayerSetup is reachable with all labels complete` context() {
        ActivityScenario.launch(MainActivity::class.java)
        openOverflowMenu()
        clickMenuItem(R.string.menu_local_multiplayer)

        // Verify setup elements have contentDescription
        onView(withId(R.id).check(matches(anything))
    }
}
```

This is a STARTING POINT. But the full download of what to test:

The flow:

SingleLayer:
1. open app → home
2. verify title, guess, show/hide
3. type year (1964), press "Guess!"
4. verify feedback appears + announced
5. press "Next song"
6. verify new song label appears

Multiplayer:
1. Navigation → multiplayer play
2. Add 2 players
3. Play # 1 open, focus on guess, enter, see feedback
4. Player 2 makes guess
5. Press reveal, verify that all answers show
6. Check that ür feedback

### Task 2.3: E2E Test — Singleplayer Game w/ TalkBack flows

**File:** `app/src/androidTest/java/com/turbolego/songguesser/BlindUserGameFlowTest.kt`

This test simulates a TalkBack user's path:

``` kotlin
package com.turnlesom.songguesser

onimport androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewAction
import view. x.
import androidx.test.espresso.accessibility.AccessibilityChecks
import x.*
import org.junit.BeforeClass
on .* 
import org.junit.Function
import org.junit.Test
import org.junit.near. ...
```

Key flows the test covers:

1. Launch → wait for first song to load (detect by `textViewSongTitle` being visible and not empty)
2. Since a TalkBack user will first hear the title, we verify `contentDescription` on songTitle matches expected string
3. They swipe right to the editText — check that announceForAccessibility happened (need to verify via ATF that the label is unique)
4. They type "1980" and press Enter
5. Result appears → since `accessibilityLiveRegion`, it's announced. Our test: try to assert the text contains feedback
6. WaitCo for `episodeNextStepavailable`; button should have clear label
7. Press next → go again

However, getting ATF to test `announceForAccessibility` is tricky because they aren't regular view events and inTalkBack they're announced via the screen reader. There's little way to test the actual spoken text in an automated test. We'll test that the views are correctly labeled, that levels meet criteria, and that nothing 'crashes TalkBack'.

### Task 2.4: E2E — Multiplayer Game Play

```kotlin
@Test
fun multiplayerBlindUserPlaythrough() {
    // 1. Navigate to multiplayer screen
    openOverflowMenu()
    onView(withText(R.string.local)).perform(click())

    // 2. Add two players
    onView(withId(R.id.btnAddPlayer)).perform(click())
    // In dialog, type name
    onView(isInstanceOf(EditText::class.java)).perform(typeText("Alice"), pressEnter())
    onView(withId(R.id.btnAddPlayer)).perform(click())
    // Another one:
    typingOn("Birger")

    // 3. Verify names appear with contentDescription
    onView(thatRecyclerView(R.id.recyclerViewPlayerList))
        child(0): hasText("Alice")
        child(0): matches(hasContentDescription(forString("Alice")))
        child(1): hasText("Bob")

    // 4. Start game
    onView(withId(R.string.start)).perform(click())

    // 5. game starts, verify player label
    waitForTitle(notEmpty)

    // 6. Player 1 guesses
    typeInEditText(R.id.editTextYear, "1995")

    // verify result appears
    verifyFeedback("1995")

    // 7. Next player
    onView(withId(R.string.next...

    // ... same flow
    // Finally start game
    doneOrFail()
}
```

### Task 2.5: Build, Execute and Verify

```bash
# In real device / emulator:
./gradlew connectedDebugAndroidTest --info

# View test output
cat app/outputs/ test- report...

```

---

### Phases to avoid

Don't try to test the actual TalkBack reader at all. Also, don't slow down the tests with `Thread.sleep()`: use Espresso's built-in synchronization which waits for idle.

Battery saver: Test on hardware — the emulator has no TalkBack.

## Files Changed Summary

| Phase | File | Operation |
|---|---|---|
| 1 | `fragment_video_player.xml` | +6 contentDescription, +3 liveRegion |
| 1 | `item_player_guess.xml` | +1 contentDescription, onBindDynamic |
| 1 | `item_player_row.xml` | +1 description for ✕ |
| 1 | `values/strings.xml` | +9/10 new a11y strings (NO) |
| 1 | `values-en/strings.xml` | +9/10 new a11y strings (EN) |
| 1 | `VideoPlayerFragment.kt` | helper +announcement calls (5 places) |
| 1 |  `MultiPlayerStepAdapter.kt` | dynamic contentDescriptions per player |
| 2.1 | `gradle/libs.versions.toml` | +3 library definitions |
| 2.1 | `app/build.gradle.kts` | +3 dependency lines |
| 2.2 | `AccessibilitySmokeTest.kt` | NEW — ATF + base checks |
| 2.3 | `PlaybackGameFlowTest.kt` | NEW — singleplayer to score check |
| 2.4 | `MultiplayerA11yFlowTest.kt` | NEW — multiplayer 2-player game |

## Risks / Open

1. **TalkBack unavailable on CI** — tests run on emulators which may not have `TalkBack` running. The ATF checks run regardless; the `accessibilityLiveRegion` cannot be programmatically tested without TalkBack.
2. **`, 0 , Drop failing test IPL pictures** — On emulator's initial loading, the first video may timeout and cause the test to bloat
3. **accessibilityResult for the debug fragment** — Debug tab in footer not related to gameplay; skip those checks.

**Verification:** Build ✅ → run deviceT test ✅ → attach Device → accessibilityTalkBack ✓ → playthrough manually once
```

## Implementation Order

Phase 1 first (get the code right), then Phase 2 (prove it works). This matches TDD: any mist each leads test failing will pinpoint exactly what to fix.

**Ready to implement!**