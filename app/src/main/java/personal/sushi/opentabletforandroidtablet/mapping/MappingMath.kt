package personal.sushi.opentabletforandroidtablet.mapping

import kotlin.math.min

/**
 * A mapping region inside the tablet touch View.
 * Touch samples in [originX, originX+width) × [originY, originY+height)
 * are normalized to the full 0..32767 HID range; outside samples clamp.
 */
data class MappingRegion(
    val width: Float,
    val height: Float,
    val originX: Float,
    val originY: Float
)

object MappingMath {

    /**
     * @param aspectW/aspectH  target tablet aspect (e.g. 16×9)
     * @param scalePercent     100 = largest rect of that aspect that fits the view
     * @param offsetXPercent   shifts region origin; % of view width, 0 = centered
     * @param offsetYPercent   same for Y
     */
    fun compute(
        viewW: Float,
        viewH: Float,
        aspectW: Int,
        aspectH: Int,
        scalePercent: Int,
        offsetXPercent: Float,
        offsetYPercent: Float
    ): MappingRegion {
        val w = viewW.coerceAtLeast(1f)
        val h = viewH.coerceAtLeast(1f)
        val aw = aspectW.coerceAtLeast(1)
        val ah = aspectH.coerceAtLeast(1)
        val aspect = aw.toFloat() / ah.toFloat()

        var fitW = w
        var fitH = w / aspect
        if (fitH > h) {
            fitH = h
            fitW = h * aspect
        }

        val s = (scalePercent.coerceIn(5, 200)) / 100f
        val mapW = min(w * 2f, fitW * s).coerceAtLeast(1f)
        val mapH = min(h * 2f, fitH * s).coerceAtLeast(1f)

        val originX = (w - mapW) / 2f + (offsetXPercent / 100f) * w
        val originY = (h - mapH) / 2f + (offsetYPercent / 100f) * h
        return MappingRegion(mapW, mapH, originX, originY)
    }
}
