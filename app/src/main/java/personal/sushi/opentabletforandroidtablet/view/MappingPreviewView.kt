package personal.sushi.opentabletforandroidtablet.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import personal.sushi.opentabletforandroidtablet.mapping.MappingMath
import personal.sushi.opentabletforandroidtablet.mapping.MappingPreset

/**
 * Live preview of the digitizer mapping region inside the tablet touch View.
 * Outer box ≈ touch View proportion; filled rect = active mapping area.
 */
class MappingPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var preset: MappingPreset? = null

    /** Proportion of the real tablet touch view (w/h), used to mimic layout. */
    private var simW = 16f
    private var simH = 10f

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val framePaint = Paint().apply {
        color = Color.parseColor("#8E9AAF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val regionFill = Paint().apply {
        color = Color.parseColor("#332196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val regionStroke = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#E3F2FD")
        textSize = 28f
        isAntiAlias = true
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#C9A0DC")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
        isAntiAlias = true
    }

    fun setSimulatedViewSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            simW = w.toFloat()
            simH = h.toFloat()
            invalidate()
        }
    }

    fun setPreset(p: MappingPreset) {
        preset = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = preset ?: return

        val pad = 16f
        val availW = width - pad * 2
        val availH = height - pad * 2
        if (availW <= 0 || availH <= 0) return

        // Fit simulated view into canvas
        val simAspect = simW / simH
        var boxW = availW
        var boxH = availW / simAspect
        if (boxH > availH) {
            boxH = availH
            boxW = availH * simAspect
        }
        val boxL = pad + (availW - boxW) / 2f
        val boxT = pad + (availH - boxH) / 2f
        val box = RectF(boxL, boxT, boxL + boxW, boxT + boxH)

        canvas.drawRoundRect(box, 8f, 8f, bgPaint)
        canvas.drawRoundRect(box, 8f, 8f, framePaint)

        // Default full-view mapping outline
        canvas.drawRoundRect(
            RectF(boxL + 4, boxT + 4, boxR(box), boxB(box)),
            4f, 4f, dimPaint
        )

        val region = MappingMath.compute(
            viewW = boxW,
            viewH = boxH,
            aspectW = p.aspectW,
            aspectH = p.aspectH,
            scalePercent = p.scalePercent,
            offsetXPercent = p.offsetXPercent,
            offsetYPercent = p.offsetYPercent
        )

        val rl = boxL + region.originX
        val rt = boxT + region.originY
        val rr = rl + region.width
        val rb = rt + region.height
        val regionRect = RectF(rl, rt, rr, rb)
        canvas.drawRect(regionRect, regionFill)
        canvas.drawRect(regionRect, regionStroke)

        val info = "${p.aspectLabel}  ·  ${p.scalePercent}%  ·  " +
            "Δ ${fmt(p.offsetXPercent)}, ${fmt(p.offsetYPercent)}"
        canvas.drawText(info, boxL, box.bottom + labelPaint.textSize + 8f, labelPaint)
    }

    private fun boxR(box: RectF) = box.right - 4f
    private fun boxB(box: RectF) = box.bottom - 4f
    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}%" else "%.1f%%".format(v)
}
