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
        return PipConfig(
            corner = corner,
            widthFraction = prefs.getFloat(KEY_WIDTH, PipConfig.DEFAULT_WIDTH_FRACTION),
            radiusFraction = prefs.getFloat(KEY_RADIUS, PipConfig.DEFAULT_RADIUS_FRACTION),
        ).sanitized()
    }

    fun save(config: PipConfig) {
        val safe = config.sanitized()
        prefs.edit()
            .putString(KEY_CORNER, safe.corner.name)
            .putFloat(KEY_WIDTH, safe.widthFraction)
            .putFloat(KEY_RADIUS, safe.radiusFraction)
            .apply()
    }

    private companion object {
        const val PREFS = "pip_settings"
        const val KEY_CORNER = "corner"
        const val KEY_WIDTH = "width"
        const val KEY_RADIUS = "radius"
    }
}
