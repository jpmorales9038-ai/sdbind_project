package com.sdcardbind.manager

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Lang {
    private const val PREF = "sdbind"
    private const val KEY = "lang"
    const val SYSTEM = "system"
    const val ES = "es"
    const val ES_ES = "es-ES"
    const val EN = "en"

    fun current(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        (ctx as? Activity)?.recreate()
    }

    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag == SYSTEM || tag.isBlank()) return base
        val locale = when (tag) {
            ES_ES -> Locale("es", "ES")
            EN -> Locale.ENGLISH
            else -> Locale("es")
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
