package com.poise.android

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language Poise shows. Empty follows the phone, which Android resolves against res/values-*
 * and falls back to English (res/values). Android 13+ keeps the pick itself, so it also shows in
 * the system's per-app language setting; before 13 it is kept here and applied by [wrap].
 */
object AppLanguage {
    /** Each language with a res/values-* folder, by its own name. Keep in step with res/xml/locales_config.xml. */
    val LANGUAGES = listOf(
            "en" to "English",
            "ar" to "العربية",
            "bn" to "বাংলা",
            "es" to "Español",
            "fa" to "فارسی",
            "fr" to "Français",
            "in" to "Bahasa Indonesia",
            "ms" to "Bahasa Melayu",
            "pt-BR" to "Português (Brasil)",
            "tr" to "Türkçe",
            "ur" to "اردو",
    )

    private const val KEY = "language"
    private fun prefs(context: Context) = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    fun get(context: Context): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            } else {
                prefs(context).getString(KEY, "").orEmpty()
            }

    /** Picks [tag], or the phone's language for "". Android 13+ recreates the activity itself. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(KEY, tag).commit() // read again as the activity recreates
            activity.recreate()
        }
    }

    /** Before Android 13: [base] in the picked language, for each activity's and service's attachBaseContext. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale) // number formats and capitals follow it too
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }
}
