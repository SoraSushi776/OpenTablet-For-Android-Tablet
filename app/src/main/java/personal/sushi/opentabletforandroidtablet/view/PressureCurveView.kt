package personal.sushi.opentabletforandroidtablet.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import personal.sushi.opentabletforandroidtablet.mapping.PressureSettings
import kotlin.math.hypot

/**
 * Interactive pressure curve editor.
 *
 * - Drag the **curve midpoint** to reshape input→output response
 * - Drag the **vertical dashed line** = contact threshold (raw %)
 * - Drag the **horizontal dashed line** = min tip pressure when down
 */
class PressureCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onCurveSettingsChanged(settings: PressureSettings)
    }

    var listener: Listener? = null

    private var settings = PressureSettings.DEFAULT

    private val pad = 28f
    private val plot = RectF()
    private val path = Path()

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#121528")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#2A3350")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#8E9AAF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val curvePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#224CAF50")
        style = Paint.Style.FILL
    }
    private val contactPaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
        isAntiAlias = true
    }
    private val minTipPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#E3F2FD")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleStroke = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 24f
        isAntiAlias = true
    }

    private enum class Drag { NONE, MID, CONTACT, MIN_TIP }
    private var drag = Drag.NONE
    private val handleRadius = 22f

    fun setSettings(s: PressureSettings, notify: Boolean = false) {
        settings = s
        invalidate()
        if (notify) listener?.onCurveSettingsChanged(s)
    }

    fun currentSettings(): PressureSettings = settings

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plot.set(pad, pad, w - pad, h - pad - 36f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plot.width() <= 0 || plot.height() <= 0) return

        canvas.drawRoundRect(RectF(plot.left - 8, plot.top - 8, plot.right + 8, plot.bottom + 8), 10f, 10f, bgPaint)

        // Grid
        for (i in 0..4) {
            val x = plot.left + plot.width() * i / 4f
            val y = plot.top + plot.height() * i / 4f
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
        }
        // Diagonal reference
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.top, gridPaint)
        canvas.drawRect(plot, axisPaint)

        // Curve polyline
        path.reset()
        val steps = 48
        for (i in 0..steps) {
            val xIn = i / steps.toFloat()
            val yOut = settings.curve(xIn)
            val px = plot.left + xIn * plot.width()
            val py = plot.bottom - yOut * plot.height()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val fill = Path(path)
        fill.lineTo(plot.right, plot.bottom)
        fill.lineTo(plot.left, plot.bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, curvePaint)

        // Contact threshold vertical line
        val cx = plot.left + settings.contactThreshold * plot.width()
        canvas.drawLine(cx, plot.top, cx, plot.bottom, contactPaint)
        canvas.drawCircle(cx, plot.bottom, handleRadius * 0.7f, handlePaint)
        canvas.drawCircle(cx, plot.bottom, handleRadius * 0.7f, contactPaint)

        // Min tip horizontal line
        val my = plot.bottom - settings.minTipPressure * plot.height()
        canvas.drawLine(plot.left, my, plot.right, my, minTipPaint)
        canvas.drawCircle(plot.left, my, handleRadius * 0.7f, handlePaint)
        canvas.drawCircle(plot.left, my, handleRadius * 0.7f, minTipPaint)

        // Mid control point
        val mx = plot.left + settings.curveMidX * plot.width()
        val mpy = plot.bottom - settings.curveMidY * plot.height()
        canvas.drawCircle(mx, mpy, handleRadius, handlePaint)
        canvas.drawCircle(mx, mpy, handleRadius, handleStroke)

        canvas.drawText("输入压感 →", plot.left, plot.bottom + 30f, labelPaint)
        canvas.drawText(
            "接触 ${settings.contactThresholdPercent}% · 下笔 ≥${settings.minTipPressurePercent}% · 点 (${settings.curveMidXPercent},${settings.curveMidYPercent})",
            plot.left,
            22f,
            labelPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = hitTest(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(drag != Drag.NONE)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return false
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Drag {
        val cx = plot.left + settings.contactThreshold * plot.width()
        val my = plot.bottom - settings.minTipPressure * plot.height()
        val mx = plot.left + settings.curveMidX * plot.width()
        val mpy = plot.bottom - settings.curveMidY * plot.height()

        val dMid = hypot(x - mx, y - mpy)
        val dContact = hypot(x - cx, y - plot.bottom)
        val dMin = hypot(x - plot.left, y - my)

        // Prefer mid handle when overlapping
        if (dMid < handleRadius * 1.6f) return Drag.MID
        if (dContact < handleRadius * 1.5f) return Drag.CONTACT
        if (dMin < handleRadius * 1.5f) return Drag.MIN_TIP
        // Contact line body (vertical strip)
        if (abs(x - cx) < 16f && y >= plot.top && y <= plot.bottom) return Drag.CONTACT
        if (abs(y - my) < 16f && x >= plot.left && x <= plot.right) return Drag.MIN_TIP
        return Drag.NONE
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    private fun updateFromTouch(x: Float, y: Float) {
        val nx = ((x - plot.left) / plot.width()).coerceIn(0f, 1f)
        val ny = ((plot.bottom - y) / plot.height()).coerceIn(0f, 1f)

        settings = when (drag) {
            Drag.MID -> settings.copy(
                curveMidXPercent = (nx * 100).toInt().coerceIn(8, 92),
                curveMidYPercent = (ny * 100).toInt().coerceIn(5, 95)
            )
            Drag.CONTACT -> settings.copy(
                contactThresholdPercent = (nx * 100).toInt().coerceIn(0, 25)
            )
            Drag.MIN_TIP -> settings.copy(
                minTipPressurePercent = (ny * 100).toInt().coerceIn(0, 60)
            )
            Drag.NONE -> settings
        }
        invalidate()
        listener?.onCurveSettingsChanged(settings)
    }
}
