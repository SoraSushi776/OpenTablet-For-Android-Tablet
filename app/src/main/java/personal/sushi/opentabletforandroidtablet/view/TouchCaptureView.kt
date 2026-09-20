package personal.sushi.opentabletforandroidtablet.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import personal.sushi.opentabletforandroidtablet.HidBridge
import personal.sushi.opentabletforandroidtablet.mapping.MappingRegion

class TouchCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bridge: HidBridge? = null
    private var active = false
    private var allowFingerInput = false

    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0f
    private var touchActive = false
    private var isHovering = false
    private var toolType = "none"

    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    /**
     * True from ACTION_DOWN until the matching ACTION_UP/CANCEL.
     * Hover is hard-gated on this flag so tip=0 reports cannot interleave
     * with an in-progress tip=1 stream (root cause of cursor flicker).
     */
    private var penDown = false

    /** Uptime of the last confirmed pen-up; hover stays muted briefly after. */
    private var lastPenUpUptime = 0L

    /**
     * Some OEM skins keep delivering HOVER_* for a few ms after contact.
     * Keep this short — too long and continuous hover after a tap feels dead.
     */
    private val hoverMuteAfterUpMs = 30L

    /**
     * Stylus touch samples below this pressure are treated as hover (tip=0).
     * Covers devices that fold air-events into onTouchEvent instead of
     * onHoverEvent.
     */
    private val contactPressureThreshold = 0.06f

    private val markerPaint = Paint().apply {
        color = Color.argb(100, 0, 200, 255)
        style = Paint.Style.FILL
    }
    private val hoverPaint = Paint().apply {
        color = Color.argb(60, 255, 200, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(120, 0, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val mappingStroke = Paint().apply {
        color = Color.argb(180, 33, 150, 243)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val mappingFill = Paint().apply {
        color = Color.argb(28, 33, 150, 243)
        style = Paint.Style.FILL
    }

    private var mappingRegion: MappingRegion? = null

    fun setMappingRegion(region: MappingRegion?) {
        mappingRegion = region
        invalidate()
    }

    fun attach(bridge: HidBridge) {
        this.bridge = bridge
        active = true
    }

    fun detach() {
        if (penDown && bridge != null && active) {
            bridge!!.nativeProcessTouch(lastX, lastY, false, 0f)
        }
        active = false
        bridge = null
        touchActive = false
        penDown = false
        isHovering = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    fun setAllowFingerInput(allow: Boolean) {
        allowFingerInput = allow
    }

    private fun isPenTool(tool: Int): Boolean =
        tool == MotionEvent.TOOL_TYPE_STYLUS ||
            tool == MotionEvent.TOOL_TYPE_ERASER

    private fun hoverSuppressed(): Boolean {
        if (penDown || touchActive) return true
        if (lastPenUpUptime == 0L) return false
        val sinceUp = SystemClock.uptimeMillis() - lastPenUpUptime
        return sinceUp in 0 until hoverMuteAfterUpMs
    }

    private fun emitHover(x: Float, y: Float) {
        val b = bridge ?: return
        isHovering = true
        toolType = "hover"
        lastX = x
        lastY = y
        lastPressure = 0f
        // In-range hover: set status bit0 (same bit this project's OTD setup
        // treats as "pen present") but pressure=0 so tip bindings that key
        // off pressure do not click. Hosts that drop tip=0/out-of-range
        // reports will now track the cursor while the pen is in the air.
        // Finger/touch still send pressure>0; ACTION_UP still sends tip=0.
        b.nativeProcessTouch(x, y, true, 0f)
        invalidate()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!active || bridge == null) return false

        // Never emit tip=0 while a contact stream is live.
        if (hoverSuppressed()) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                // Send immediately so OTD picks up cursor position on enter,
                // not only after the first MOVE.
                emitHover(event.x, event.y)
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (hoverSuppressed()) return true
                emitHover(event.x, event.y)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                isHovering = false
                // Do NOT send a report on hover exit — ACTION_DOWN will
                // send tip=1. Emitting tip=0 here causes a contact flicker.
                invalidate()
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active || bridge == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val tool = event.getToolType(index)
                val isPen = isPenTool(tool)
                val isFinger = tool == MotionEvent.TOOL_TYPE_FINGER

                if (isFinger && !allowFingerInput) return false
                if (!isPen && !isFinger) {
                    // Unknown tools still allowed for pen-like hover fallback
                    // only when they look like stylus source — skip otherwise.
                    return false
                }

                val x = event.getX(index)
                val y = event.getY(index)
                val rawPressure = if (isPen) event.getPressure(index) else 1f
                val contact = !isPen || rawPressure >= contactPressureThreshold

                activePointerId = pointerId

                if (!contact) {
                    // Air / hover sample delivered as a touch event.
                    penDown = false
                    touchActive = false
                    emitHover(x, y)
                    return true
                }

                isHovering = false
                touchActive = true
                penDown = true
                toolType = if (isPen) "pen" else "finger"

                val pressure = if (isPen) rawPressure else 1f
                lastX = x
                lastY = y
                lastPressure = pressure
                bridge!!.nativeProcessTouch(x, y, true, pressure)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                var index = event.findPointerIndex(activePointerId)
                var tool = event.getToolType(index.coerceAtLeast(0))
                if (index < 0) {
                    tool = event.getToolType(0)
                    val ok = isPenTool(tool) ||
                        (tool == MotionEvent.TOOL_TYPE_FINGER && allowFingerInput)
                    if (!ok) return true
                    index = 0
                }

                val x = event.getX(index)
                val y = event.getY(index)
                val isPen = isPenTool(tool) || toolType == "pen"
                val rawPressure =
                    if (isPen) event.getPressure(index) else 1f
                val contact = !isPen || rawPressure >= contactPressureThreshold

                if (!contact) {
                    if (!penDown) {
                        emitHover(x, y)
                    } else {
                        // Mid-stroke pressure dipped — keep tip down so the
                        // stroke is not interrupted.
                        lastX = x
                        lastY = y
                        lastPressure = rawPressure
                        bridge!!.nativeProcessTouch(x, y, true, rawPressure)
                        invalidate()
                    }
                    return true
                }

                lastX = x
                lastY = y
                lastPressure = if (isPen) rawPressure else 1f
                touchActive = true
                penDown = true
                isHovering = false
                bridge!!.nativeProcessTouch(x, y, true, lastPressure)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId != activePointerId &&
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP
                ) {
                    return true
                }

                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                bridge!!.nativeProcessTouch(x, y, false, 0f)

                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    pointerId == activePointerId
                ) {
                    touchActive = false
                    penDown = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    lastPenUpUptime = SystemClock.uptimeMillis()
                    lastX = x
                    lastY = y
                    lastPressure = 0f
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                bridge!!.nativeProcessTouch(lastX, lastY, false, 0f)
                touchActive = false
                penDown = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                lastPenUpUptime = SystemClock.uptimeMillis()
                lastPressure = 0f
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        mappingRegion?.let { r ->
            val l = r.originX
            val t = r.originY
            val rt = l + r.width
            val b = t + r.height
            canvas.drawRect(l, t, rt, b, mappingFill)
            canvas.drawRect(l, t, rt, b, mappingStroke)
        }

        if (touchActive) {
            canvas.drawCircle(lastX, lastY, 30f, markerPaint)
        } else if (isHovering) {
            canvas.drawCircle(lastX, lastY, 20f, hoverPaint)
        }

        val status = when {
            touchActive -> "$toolType: (${lastX.toInt()}, ${lastY.toInt()})  P=${"%.2f".format(lastPressure)}"
            isHovering -> "hover: (${lastX.toInt()}, ${lastY.toInt()})"
            else -> "Ready — pen/finger to digitize"
        }
        canvas.drawText(status, 20f, 60f, textPaint)
    }
}
