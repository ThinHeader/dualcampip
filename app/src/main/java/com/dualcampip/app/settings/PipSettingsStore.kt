package com.dualcampip.app.settings

import android.content.Context
import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.model.PipCorner

class PipSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PipConfig {
        val corner = prefs.getString(KEY_CORNER, null)
            ?.let { name -> runCatching { PipCorner.valueOf(name) }.getOrNull() }
            ?: PipCorner.BOTTOM_END
        val hasPlace = prefs.contains(KEY_LEFT) && prefs.contains(KEY_TOP)
        return PipConfig(
            corner = corner,
            widthFraction = prefs.getFloat(KEY_WIDTH, PipConfig.DEFAULT_WIDTH_FRACTION),
            radiusFraction = prefs.getFloat(KEY_RADIUS, PipConfig.DEFAULT_RADIUS_FRACTION),
            leftFraction = if (hasPlace) prefs.getFloat(KEY_LEFT, 0f) else null,
            topFraction = if (hasPlace) prefs.getFloat(KEY_TOP, 0f) else null,
        ).sanitized()
    }

    fun save(config: PipConfig) {
        val safe = config.sanitized()
        val editor = prefs.edit()
            .putString(KEY_CORNER, safe.corner.name)
            .putFloat(KEY_WIDTH, safe.widthFraction)
            .putFloat(KEY_RADIUS, safe.radiusFraction)
        if (safe.leftFraction != null && safe.topFraction != null) {
            editor.putFloat(KEY_LEFT, safe.leftFraction).putFloat(KEY_TOP, safe.topFraction)
        } else {
            editor.remove(KEY_LEFT).remove(KEY_TOP)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS = "pip_settings"
        const val KEY_CORNER = "corner"
        const val KEY_WIDTH = "width"
        const val KEY_RADIUS = "radius"
        const val KEY_LEFT = "left"
        const val KEY_TOP = "top"
    }
}
