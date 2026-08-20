package com.turbolego.songguesser

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Manages runtime language switching for the app.
 *
 * - Stores language code in SharedPreferences
 * - Applies the stored locale via wrapped context in [attachBaseContext]
 * - Call [setLanguage] to switch language and recreate the activity
 */
object LocaleHelper {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANG = "app_language"

    const val LANGUAGE_EN = "en"
    const val LANGUAGE_NB = "nb"

    /** Default language is Norwegian Bokmål. */
    private const val DEFAULT_LANG = LANGUAGE_NB

    // ── Read / Write preference ─────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG

    private fun setLanguagePref(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANG, lang).apply()
    }

    // ── Apply locale to context ─────────────────────────────────────────────

    /**
     * Wraps [base] with the user's chosen locale.
     * Call from `Activity.attachBaseContext()`.
     */
    fun attachBaseContext(base: Context): Context {
        val lang = getLanguage(base)
        return updateBaseContextLocale(base, lang)
    }

    private fun updateBaseContextLocale(context: Context, lang: String): Context {
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    // ── Switch language ─────────────────────────────────────────────────────

    /**
     * Persists the language choice and recreates [activity] so all
     * string resources are refreshed.
     */
    fun setLanguage(activity: AppCompatActivity, lang: String) {
        if (getLanguage(activity) == lang) return // already set
        setLanguagePref(activity, lang)
        activity.recreate()
    }
}