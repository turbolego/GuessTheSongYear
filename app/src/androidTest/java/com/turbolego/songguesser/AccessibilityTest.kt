package com.turbolego.songguesser

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility tests ensuring the app is usable by blind and
 * low-vision users with screen readers (e.g. TalkBack).
 *
 * Requirements: Android device or emulator.
 * Run: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // ═══════════════════════════════════════════════════════════════
    // Content descriptions — every image/icon button must be labeled
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `all ImageViews must have contentDescription`() {
        activityRule.scenario.onActivity { activity ->
            val failures = mutableListOf<String>()
            findImageViews(activity.window.decorView, failures)
            assertTrue(
                "Images missing contentDescription:\n${failures.joinToString("\n")}",
                failures.isEmpty()
            )
        }
    }

    @Test
    fun `all icon buttons must have contentDescription`() {
        activityRule.scenario.onActivity { activity ->
            val failures = mutableListOf<String>()
            findIconButtons(activity.window.decorView, failures)
            assertTrue(
                "Buttons missing contentDescription:\n${failures.joinToString("\n")}",
                failures.isEmpty()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Touch target sizes (WCAG 2.1: ≥48dp)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `all buttons must have touch target at least 48dp`() {
        activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val minPx = (48 * density).toInt()
            val failures = mutableListOf<String>()

            findButtons(activity.window.decorView, failures, minPx)
            assertTrue(
                "Buttons below 48dp touch target (WCAG 2.1):\n${failures.joinToString("\n")}",
                failures.isEmpty()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Text readability — minimum 12sp font for any visible text
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `all TextViews should have at least 12sp font size`() {
        activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.scaledDensity
            val failures = mutableListOf<String>()

            findTextViews(activity.window.decorView) { tv ->
                val sp = tv.textSize / density
                if (tv.visibility == android.view.View.VISIBLE && tv.text.isNotEmpty() && sp < 12) {
                    failures.add("Text \"${tv.text}\" is only ${sp.toInt()}sp (min 12sp)")
                }
            }

            assertTrue("Text too small:\n${failures.joinToString("\n")}", failures.isEmpty())
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Focus order / keyboard navigation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `main buttons should be reachable via keyboard`() {
        activityRule.scenario.onActivity { activity ->
            val focusableButtons = mutableListOf<String>()
            findFocusable(activity.window.decorView, focusableButtons)
            assertTrue(
                "No focusable buttons found — keyboard users can't navigate",
                focusableButtons.isNotEmpty()
            )
        }
    }

    @Test
    fun `editText should expose contentDescription for screen readers`() {
        activityRule.scenario.onActivity { activity ->
            val failures = mutableListOf<String>()
            findEditTexts(activity.window.decorView, failures)
            assertTrue(
                "EditText widgets without hint or contentDescription:\n${failures.joinToString("\n")}",
                failures.isEmpty()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers — recursive view tree walking
    // ═══════════════════════════════════════════════════════════════

    private fun findImageViews(root: View, failures: MutableList<String>) {
        if (root is android.widget.ImageView) {
            val desc = root.contentDescription?.toString()
            if (desc.isNullOrEmpty() && root.visibility == View.VISIBLE) {
                failures.add("ImageView id=${resourceIdFor(root)} has no contentDescription")
            }
        }
        if (root is ViewGroup) {
            for (j in 0 until root.childCount) {
                findImageViews(root.getChildAt(j), failures)
            }
        }
    }

    private fun findFocusable(root: View, list: MutableList<String>) {
        if (root.isFocusable && root.visibility == View.VISIBLE) {
            list.add(root.javaClass.simpleName)
        }
        if (root is ViewGroup) {
            for (j in 0 until root.childCount) {
                findFocusable(root.getChildAt(j), list)
            }
        }
    }

    private fun resourceIdFor(root: View) = try { root.resources.getResourceEntryName(root.id) } catch (_: Exception) { "unknown" }

    private fun findIconButtons(root: View, failures: MutableList<String>) {
        if (root is Button && root.text.isNullOrEmpty()) {
            val desc = root.contentDescription?.toString()
            if (desc.isNullOrEmpty() && root.visibility == View.VISIBLE) {
                failures.add("Button id=${resourceIdFor(root)} has no text or contentDescription")
            }
        }
        if (root is ViewGroup) {
            for (j in 0 until root.childCount) {
                findIconButtons(root.getChildAt(j), failures)
            }
        }
    }

    private fun findEditTexts(root: View, failures: MutableList<String>) {
        if (root is android.widget.EditText && root.visibility == View.VISIBLE) {
            val hint = root.hint?.toString()
            val desc = root.contentDescription?.toString()
            if (hint.isNullOrEmpty() && desc.isNullOrEmpty()) {
                failures.add("EditText id=${resourceIdFor(root)} has no hint or contentDescription")
            }
        }
        if (root is ViewGroup) {
            for (j in 0 until root.childCount) {
                findEditTexts(root.getChildAt(j), failures)
            }
        }
    }

    private fun findButtons(root: View, failures: MutableList<String>, minPx: Int) {
        if (root is Button && root.visibility == View.VISIBLE) {
            val h = root.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            val w = root.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
            if (h != ViewGroup.LayoutParams.WRAP_CONTENT && h < minPx) {
                failures.add("Button ${root.text} (${resourceIdFor(root)}) height=${h}px < ${minPx}px (48dp)")
            }
            if (w != ViewGroup.LayoutParams.WRAP_CONTENT && w < minPx) {
                failures.add("Button ${root.text} (${resourceIdFor(root)}) width=${w}px < ${minPx}px (48dp)")
            }
        }
        if (root is ViewGroup) {
            for (j in 0 until root.childCount) {
                findButtons(root.getChildAt(j), failures, minPx)
            }
        }
    }

    private fun findTextViews(root: View, check: (TextView) -> Unit) {
        if (root is TextView) {
            check(root)
        }
        if (root is ViewGroup) {
            for (j in 0 until root.childCount) {
                findTextViews(root.getChildAt(j), check)
            }
        }
    }
}