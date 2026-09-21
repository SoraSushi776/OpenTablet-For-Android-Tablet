package personal.sushi.opentabletforandroidtablet

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import personal.sushi.opentabletforandroidtablet.databinding.ActivityMainBinding
import personal.sushi.opentabletforandroidtablet.mapping.MappingPresetStore
import personal.sushi.opentabletforandroidtablet.utils.RootUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HID_PATH = "/dev/hidg0"
    }

    private lateinit var binding: ActivityMainBinding
    private val bridge = HidBridge()
    private var screenW = 0
    private var screenH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        measureScreen()
        refreshStatus()

        binding.btnSetup.setOnClickListener { setupHidGadget() }
        binding.btnStart.setOnClickListener { startTabletMode() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnViewLog.setOnClickListener {
            LogRepository.i(TAG, "Opening log viewer")
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        binding.btnTeardown.setOnClickListener { teardownHidGadget() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun measureScreen() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        binding.textScreenSize.text = getString(R.string.fmt_screen, screenW, screenH)
    }

    private fun refreshStatus() {
        val rootOk = RootUtils.hasSu()
        binding.textRootStatus.text =
            if (rootOk) getString(R.string.status_root_ok) else getString(R.string.status_root_no)
        binding.textRootStatus.setTextColor(
            getColor(if (rootOk) R.color.m3_status_ok else R.color.m3_status_bad)
        )

        val hidgExists = RootUtils.pathExists(HID_PATH)
        binding.textHidgStatus.text = getString(
            if (hidgExists) R.string.status_present else R.string.status_absent
        )
        binding.textHidgStatus.setTextColor(
            getColor(if (hidgExists) R.color.m3_status_ok else R.color.m3_status_bad)
        )

        binding.btnSetup.isEnabled = rootOk
        binding.btnStart.isEnabled = rootOk && hidgExists
    }

    private fun setupHidGadget() {
        if (!RootUtils.hasSu()) {
            Toast.makeText(this, R.string.toast_no_root, Toast.LENGTH_LONG).show()
            LogRepository.e(TAG, "No root access")
            return
        }

        LogRepository.i(TAG, "Starting HID gadget setup")
        binding.btnSetup.isEnabled = false
        binding.btnSetup.text = getString(R.string.btn_setting_up)

        Thread {
            val reportDesc = bridge.nativeGetReportDescriptor()
            val b64 = Base64.encodeToString(reportDesc, Base64.NO_WRAP)
            val kbDesc = bridge.nativeGetKeyboardDescriptor()
            val kbB64 = Base64.encodeToString(kbDesc, Base64.NO_WRAP)
            val result = RootUtils.setupHidGadget(b64, kbB64)

            runOnUiThread {
                binding.btnSetup.isEnabled = true
                binding.btnSetup.text = getString(R.string.btn_setup)
                if (result.success) {
                    Toast.makeText(this, R.string.toast_setup_ok, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_setup_fail, result.stdout.ifEmpty { result.stderr }),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshStatus()
            }
        }.start()
    }

    private fun startTabletMode() {
        val current = MappingPresetStore.loadDraft(this) ?: MappingPresetStore.active(this)
        MappingPresetStore.saveDraft(this, current)
        LogRepository.i(
            TAG,
            "Launching tablet mode mapping=${current.aspectLabel} scale=${current.scalePercent} " +
                "off=(${current.offsetXPercent},${current.offsetYPercent})"
        )
        startActivity(Intent(this, TabletActivity::class.java).apply {
            putExtra("screenW", screenW)
            putExtra("screenH", screenH)
            putExtra("mapAspectW", current.aspectW)
            putExtra("mapAspectH", current.aspectH)
            putExtra("mapScale", current.scalePercent)
            putExtra("mapOffX", current.offsetXPercent)
            putExtra("mapOffY", current.offsetYPercent)
        })
    }

    private fun teardownHidGadget() {
        LogRepository.i(TAG, "Tearing down HID gadget")
        binding.btnTeardown.isEnabled = false
        binding.btnTeardown.text = getString(R.string.btn_setting_up)

        Thread {
            val result = RootUtils.teardownHidGadget()
            runOnUiThread {
                binding.btnTeardown.isEnabled = true
                binding.btnTeardown.text = getString(R.string.btn_teardown)
                if (result.success) {
                    Toast.makeText(this, R.string.toast_teardown_ok, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_teardown_fail, result.stderr.ifEmpty { result.stdout }),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshStatus()
            }
        }.start()
    }
}
