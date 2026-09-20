package personal.sushi.opentabletforandroidtablet

import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import personal.sushi.opentabletforandroidtablet.databinding.ActivityTabletBinding
import personal.sushi.opentabletforandroidtablet.mapping.MappingMath
import personal.sushi.opentabletforandroidtablet.utils.RootUtils

class TabletActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TabletActivity"
        private const val HID_PATH = "/dev/hidg0"
        private const val KB_PATH = "/dev/hidg1"
    }

    private lateinit var binding: ActivityTabletBinding
    private val bridge = HidBridge()

    private var screenW = 0
    private var screenH = 0
    private var allowFingerInput = false
    private var screenLocked = false
    private var ctrlActive = false
    private var shiftActive = false
    private var altActive = false

    // Mapping preset from MainActivity (defaults: 16:9, 100%, centered).
    private var mapAspectW = 16
    private var mapAspectH = 9
    private var mapScale = 100
    private var mapOffX = 0f
    private var mapOffY = 0f

    // HID keyboard modifier bits.
    private val MOD_LCTRL: Int = 0x01
    private val MOD_LSHIFT: Int = 0x02
    private val MOD_LALT: Int = 0x04

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabletBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterImmersiveMode()

        screenW = intent.getIntExtra("screenW", resources.displayMetrics.widthPixels)
        screenH = intent.getIntExtra("screenH", resources.displayMetrics.heightPixels)
        mapAspectW = intent.getIntExtra("mapAspectW", 16)
        mapAspectH = intent.getIntExtra("mapAspectH", 9)
        mapScale = intent.getIntExtra("mapScale", 100)
        mapOffX = intent.getFloatExtra("mapOffX", 0f)
        mapOffY = intent.getFloatExtra("mapOffY", 0f)

        openHidDevices()
        startDigitizing()
        setupExpressKeys()
        setupToggles()
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun openHidDevices() {
        val digitizerOpened = bridge.nativeOpenHid(HID_PATH)
        Log.i(TAG, "nativeOpenHid($HID_PATH) = $digitizerOpened")
        if (!digitizerOpened) {
            Toast.makeText(this, R.string.toast_open_fail, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val kbOpened = bridge.nativeOpenKeyboard(KB_PATH)
        Log.i(TAG, "nativeOpenKeyboard($KB_PATH) = $kbOpened")
    }

    private fun startDigitizing() {
        // Apply aspect + scale + offset inside the touch View (not the full display).
        binding.touchViewTablet.post {
            val v = binding.touchViewTablet
            val viewW = v.width.toFloat().coerceAtLeast(1f)
            val viewH = v.height.toFloat().coerceAtLeast(1f)
            val region = MappingMath.compute(
                viewW = viewW,
                viewH = viewH,
                aspectW = mapAspectW,
                aspectH = mapAspectH,
                scalePercent = mapScale,
                offsetXPercent = mapOffX,
                offsetYPercent = mapOffY
            )
            Log.d(
                TAG,
                "Mapping: view=${viewW}x$viewH aspect=$mapAspectW:$mapAspectH " +
                    "scale=$mapScale off=($mapOffX,$mapOffY) → " +
                    "region=${region.width}x${region.height} @ (${region.originX},${region.originY})"
            )
            bridge.nativeSetMapping(
                region.width,
                region.height,
                region.originX,
                region.originY,
                viewW.toInt(),
                viewH.toInt()
            )
            bridge.nativeStartWriter()
            binding.touchViewTablet.attach(bridge)
            binding.touchViewTablet.setAllowFingerInput(allowFingerInput)
            binding.touchViewTablet.setMappingRegion(region)
            Log.i(TAG, "Writer thread started")
        }
    }

    // ── Express Keys ──

    private fun setupExpressKeys() {
        // Each button sends a keyboard shortcut on press, releases on release.
        binding.btnKeyUndo.setOnTouchListener { _, event -> handleKey(event, MOD_LCTRL, byteArrayOf(0x1D)) }
        binding.btnKeyRedo.setOnTouchListener { _, event -> handleKey(event, MOD_LCTRL or MOD_LSHIFT, byteArrayOf(0x1D)) }
        binding.btnKeyBrush.setOnTouchListener { _, event -> handleKey(event, 0, byteArrayOf(0x05)) }
        binding.btnKeyEraser.setOnTouchListener { _, event -> handleKey(event, 0, byteArrayOf(0x08)) }
        binding.btnKeyBrushSmaller.setOnTouchListener { _, event -> handleKey(event, 0, byteArrayOf(0x2E)) }
        binding.btnKeyBrushLarger.setOnTouchListener { _, event -> handleKey(event, 0, byteArrayOf(0x2F)) }
        binding.btnKeySpace.setOnTouchListener { _, event -> handleKey(event, 0, byteArrayOf(0x2C)) }
        binding.btnKeyTab.setOnTouchListener { _, event -> handleKey(event, 0, byteArrayOf(0x2B)) }

        binding.btnExitTablet.setOnClickListener { finish() }
    }

    private fun handleKey(event: android.view.MotionEvent, baseMod: Int, keys: ByteArray): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val mod = combineModifiers(baseMod)
                bridge.nativeSendKeys(mod.toByte(), keys)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                bridge.nativeReleaseKeys()
            }
        }
        return true
    }

    private fun combineModifiers(base: Int): Int {
        var mod = base
        if (ctrlActive) mod = mod or MOD_LCTRL
        if (shiftActive) mod = mod or MOD_LSHIFT
        if (altActive) mod = mod or MOD_LALT
        return mod
    }

    // ── Toggles ──

    private fun setupToggles() {
        (binding.switchFingerInput as Switch).setOnCheckedChangeListener { _, checked ->
            allowFingerInput = checked
            binding.touchViewTablet.setAllowFingerInput(checked)
            Log.i(TAG, "Finger input: $checked")
        }

        (binding.switchScreenLock as Switch).setOnCheckedChangeListener { _, checked ->
            screenLocked = checked
            if (checked) {
                enterImmersiveMode()
                startLockTaskMode()
            } else {
                stopLockTaskMode()
            }
            Log.i(TAG, "Screen lock: $checked")
        }

        binding.toggleCtrl.setOnCheckedChangeListener { _, checked ->
            ctrlActive = checked
            if (!checked) bridge.nativeReleaseKeys()
        }
        binding.toggleShift.setOnCheckedChangeListener { _, checked ->
            shiftActive = checked
            if (!checked) bridge.nativeReleaseKeys()
        }
        binding.toggleAlt.setOnCheckedChangeListener { _, checked ->
            altActive = checked
            if (!checked) bridge.nativeReleaseKeys()
        }
    }

    private fun startLockTaskMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use screen pinning via Lock Task Mode if available.
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val lists = am.lockTaskModeState
                if (lists != android.app.ActivityManager.LOCK_TASK_MODE_LOCKED &&
                    lists != android.app.ActivityManager.LOCK_TASK_MODE_PINNED) {
                    startLockTask()
                }
            } else {
                startLockTask()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lock task failed", e)
            // Fallback: just keep immersive mode.
            enterImmersiveMode()
        }
    }

    private fun stopLockTaskMode() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.e(TAG, "Stop lock task failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Only allow back if not screen-locked.
        if (screenLocked) {
            Toast.makeText(this, R.string.toast_unlock_first, Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        binding.touchViewTablet.detach()
        bridge.nativeStopWriter()
        bridge.nativeReleaseKeys()
        bridge.nativeCloseKeyboard()
        bridge.nativeCloseHid()
        RootUtils.restoreSelinux()
        Log.i(TAG, "Tablet activity destroyed")
        super.onDestroy()
    }
}
