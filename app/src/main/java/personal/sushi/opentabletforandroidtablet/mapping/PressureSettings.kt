package personal.sushi.opentabletforandroidtablet.mapping

import android.content.Context
import kotlin.math.abs

/**
 * Pressure pipeline settings.
 *
 * Curve is a piecewise-linear map through (0,0) → (curveMidX, curveMidY) → (1,1),
 * then scaled by maxOut and floored at minTip when the tip is down.
 */
data class PressureSettings(
    val contactThresholdPercent: Int = 2,
    val minTipPressurePercent: Int = 15,
    val maxPressurePercent: Int = 100,
    /** Control point X, percent of raw input (10–90). */
    val curveMidXPercent: Int = 50,
    /** Control point Y, percent of output (10–90). Mid Y &lt; X → harder for light strokes. */
    val curveMidYPercent: Int = 45
) {
    val contactThreshold: Float get() = contactThresholdPercent / 100f
    val minTipPressure: Float get() = minTipPressurePercent / 100f
    val maxOut: Float get() = (maxPressurePercent / 100f).coerceIn(0.05f, 1f)
    val curveMidX: Float get() = (curveMidXPercent / 100f).coerceIn(0.05f, 0.95f)
    val curveMidY: Float get() = (curveMidYPercent / 100f).coerceIn(0.02f, 0.98f)

    /** Map raw 0..1 input pressure through the curve to 0..1 (no tip floor). */
    fun curve(raw: Float): Float {
        val x = raw.coerceIn(0f, 1f)
        val mx = curveMidX
        val my = curveMidY
        val y = when {
            x <= mx -> if (mx <= 0f) 0f else x / mx * my
            else -> my + (x - mx) / (1f - mx) * (1f - my)
        }
        return y.coerceIn(0f, 1f)
    }

    /**
     * @param rawPressure  MotionEvent pressure, typically 0..1
     * @param tipDown      true when we report HID tip contact
     * @return pressure to send in 0..1
     */
    fun apply(rawPressure: Float, tipDown: Boolean): Float {
        if (!tipDown) return 0f
        var p = curve(rawPressure) * maxOut
        if (p < minTipPressure) p = minTipPressure
        return p.coerceIn(0f, 1f)
    }

    companion object {
        val DEFAULT = PressureSettings()

        /** Clicks fire easily; brush still has some range. */
        val CLICK_OPTIMIZED = PressureSettings(
            contactThresholdPercent = 1,
            minTipPressurePercent = 28,
            maxPressurePercent = 100,
            curveMidXPercent = 40,
            curveMidYPercent = 55
        )

        /** Softer contact, light strokes read more gradually for painting apps. */
        val PS_PAINTING = PressureSettings(
            contactThresholdPercent = 2,
            minTipPressurePercent = 8,
            maxPressurePercent = 100,
            curveMidXPercent = 55,
            curveMidYPercent = 28
        )

        fun nearestPresetId(s: PressureSettings): String? = when {
            s.matches(CLICK_OPTIMIZED) -> "click"
            s.matches(PS_PAINTING) -> "ps"
            s.matches(DEFAULT) -> "default"
            else -> null
        }

        private fun PressureSettings.matches(o: PressureSettings): Boolean =
            contactThresholdPercent == o.contactThresholdPercent &&
                minTipPressurePercent == o.minTipPressurePercent &&
                maxPressurePercent == o.maxPressurePercent &&
                abs(curveMidXPercent - o.curveMidXPercent) <= 1 &&
                abs(curveMidYPercent - o.curveMidYPercent) <= 1
    }
}

object PressureSettingsStore {

    private const val PREFS = "pressure_settings"
    private const val KEY_CONTACT = "contact_threshold"
    private const val KEY_MIN_TIP = "min_tip"
    private const val KEY_MAX = "max_out"
    private const val KEY_MID_X = "curve_mid_x"
    private const val KEY_MID_Y = "curve_mid_y"
    // legacy gamma key — migrated away
    private const val KEY_GAMMA = "gamma"

    fun load(context: Context): PressureSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasMid = p.contains(KEY_MID_X)
        val gamma = p.getInt(KEY_GAMMA, 80)
        // Migrate old gamma-only profiles to a mid control point.
        val midX = if (hasMid) p.getInt(KEY_MID_X, 50) else 50
        val midY = if (hasMid) {
            p.getInt(KEY_MID_Y, 45)
        } else {
            // gamma 0.8 → slightly higher mid Y; gamma 0.5 → much higher
            when {
                gamma <= 50 -> 70
                gamma <= 70 -> 58
                gamma <= 90 -> 45
                gamma <= 120 -> 38
                else -> 30
            }
        }
        return PressureSettings(
            contactThresholdPercent = p.getInt(KEY_CONTACT, PressureSettings.DEFAULT.contactThresholdPercent),
            minTipPressurePercent = p.getInt(KEY_MIN_TIP, PressureSettings.DEFAULT.minTipPressurePercent),
            maxPressurePercent = p.getInt(KEY_MAX, PressureSettings.DEFAULT.maxPressurePercent),
            curveMidXPercent = midX,
            curveMidYPercent = midY
        )
    }

    fun save(context: Context, s: PressureSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CONTACT, s.contactThresholdPercent)
            .putInt(KEY_MIN_TIP, s.minTipPressurePercent)
            .putInt(KEY_MAX, s.maxPressurePercent)
            .putInt(KEY_MID_X, s.curveMidXPercent)
            .putInt(KEY_MID_Y, s.curveMidYPercent)
            .apply()
    }
}
