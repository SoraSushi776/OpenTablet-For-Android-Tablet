package personal.sushi.opentabletforandroidtablet

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import personal.sushi.opentabletforandroidtablet.databinding.ActivityMainBinding
import personal.sushi.opentabletforandroidtablet.mapping.MappingPreset
import personal.sushi.opentabletforandroidtablet.mapping.MappingPresetStore
import personal.sushi.opentabletforandroidtablet.utils.RootUtils
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HID_PATH = "/dev/hidg0"
        private const val SEEK_SCALE_MIN = 20
        private const val SEEK_OFFSET_RANGE = 50 // -50% .. +50%
    }

    private lateinit var binding: ActivityMainBinding
    private val bridge = HidBridge()

    private var screenW = 0
    private var screenH = 0
    private var suppressUiCallbacks = false
    private var selectedPresetId: String = ""

    /** Current editor state (sliders / aspect). */
    private var current = MappingPresetStore.builtin.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        measureScreen()
        setupMappingUi()
        refreshStatus()

        binding.btnSetup.setOnClickListener { setupHidGadget() }
        binding.btnStart.setOnClickListener { startTabletMode() }
        binding.btnExportOtd.setOnClickListener { exportOtdConfig() }
        binding.btnViewLog.setOnClickListener {
            LogRepository.i(TAG, "Opening log viewer")
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        binding.btnTeardown.setOnClickListener { teardownHidGadget() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        rebuildPresetChips()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ── Screen measurement ──

    private fun measureScreen() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        binding.textScreenSize.text = getString(R.string.fmt_screen, screenW, screenH)

        // Simulate tablet touch view: left express-key rail is 200dp.
        val density = resources.displayMetrics.density
        val rail = (200 * density).toInt()
        binding.mappingPreview.setSimulatedViewSize(
            (screenW - rail).coerceAtLeast(1),
            screenH.coerceAtLeast(1)
        )
    }

    // ── Mapping preset UI ──

    private fun setupMappingUi() {
        current = MappingPresetStore.loadDraft(this) ?: MappingPresetStore.active(this)
        selectedPresetId = current.id
        applyStateToControls(current)

        binding.groupAspect.setOnCheckedChangeListener { _, checkedId ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            val (aw, ah) = when (checkedId) {
                R.id.radioAspect1610 -> 16 to 10
                R.id.radioAspect43 -> 4 to 3
                else -> 16 to 9
            }
            current = current.copy(aspectW = aw, aspectH = ah, isBuiltin = false)
            onEditorChanged()
        }

        binding.seekScale.setOnSeekBarChangeListener(simpleSeek {
            current = current.copy(scalePercent = it + SEEK_SCALE_MIN, isBuiltin = false)
            onEditorChanged()
        })
        binding.seekOffsetX.setOnSeekBarChangeListener(simpleSeek {
            current = current.copy(offsetXPercent = (it - SEEK_OFFSET_RANGE).toFloat(), isBuiltin = false)
            onEditorChanged()
        })
        binding.seekOffsetY.setOnSeekBarChangeListener(simpleSeek {
            current = current.copy(offsetYPercent = (it - SEEK_OFFSET_RANGE).toFloat(), isBuiltin = false)
            onEditorChanged()
        })

        binding.btnSavePreset.setOnClickListener { saveCurrentPreset() }
        binding.btnDeletePreset.setOnClickListener { deleteSelectedPreset() }

        rebuildPresetChips()
        binding.mappingPreview.setPreset(current)
        updateValueLabels()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && !suppressUiCallbacks) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun applyStateToControls(p: MappingPreset) {
        suppressUiCallbacks = true
        when {
            p.aspectW == 16 && p.aspectH == 10 -> binding.radioAspect1610.isChecked = true
            p.aspectW == 4 && p.aspectH == 3 -> binding.radioAspect43.isChecked = true
            else -> binding.radioAspect169.isChecked = true
        }
        binding.seekScale.progress = (p.scalePercent - SEEK_SCALE_MIN).coerceIn(0, binding.seekScale.max)
        binding.seekOffsetX.progress =
            (p.offsetXPercent.toInt() + SEEK_OFFSET_RANGE).coerceIn(0, binding.seekOffsetX.max)
        binding.seekOffsetY.progress =
            (p.offsetYPercent.toInt() + SEEK_OFFSET_RANGE).coerceIn(0, binding.seekOffsetY.max)
        if (p.name.isNotBlank() && !p.isBuiltin) {
            binding.editPresetName.setText(p.name)
        }
        suppressUiCallbacks = false
        updateValueLabels()
        binding.mappingPreview.setPreset(p)
    }

    private fun onEditorChanged() {
        updateValueLabels()
        binding.mappingPreview.setPreset(current)
        MappingPresetStore.saveDraft(this, current)
        // Deselect named chip when user tweaks sliders
        if (selectedPresetId != current.id) {
            selectedPresetId = current.id
            rebuildPresetChips()
        } else {
            highlightSelectedChip()
        }
    }

    private fun updateValueLabels() {
        binding.textScaleValue.text = getString(R.string.fmt_scale_value, current.scalePercent)
        binding.textOffsetXValue.text = getString(R.string.fmt_offset_value, fmtPct(current.offsetXPercent))
        binding.textOffsetYValue.text = getString(R.string.fmt_offset_value, fmtPct(current.offsetYPercent))
    }

    private fun fmtPct(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

    private fun rebuildPresetChips() {
        val container = binding.presetChipContainer
        container.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        MappingPresetStore.all(this).forEach { preset ->
            val btn = Button(this).apply {
                text = preset.displayLabel
                isAllCaps = false
                setPadding(pad * 2, pad, pad * 2, pad)
                val lp = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = pad
                layoutParams = lp
                setOnClickListener {
                    selectedPresetId = preset.id
                    current = preset.copy()
                    MappingPresetStore.setActive(this@MainActivity, current)
                    applyStateToControls(current)
                    highlightSelectedChip()
                }
            }
            container.addView(btn)
        }
        highlightSelectedChip()
    }

    private fun highlightSelectedChip() {
        val container = binding.presetChipContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? Button ?: continue
            val alpha = if (i == indexOfSelected()) 1f else 0.55f
            child.alpha = alpha
        }
    }

    private fun indexOfSelected(): Int {
        val all = MappingPresetStore.all(this)
        val idx = all.indexOfFirst { it.id == selectedPresetId }
        return if (idx >= 0) idx else 0
    }

    private fun saveCurrentPreset() {
        val name = binding.editPresetName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.toast_preset_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val toSave = current.copy(
            id = if (current.isBuiltin || current.id.startsWith("builtin_")) "" else current.id,
            name = name,
            isBuiltin = false
        )
        val saved = MappingPresetStore.upsert(this, toSave)
        current = saved
        selectedPresetId = saved.id
        MappingPresetStore.setActive(this, saved)
        MappingPresetStore.saveDraft(this, saved)
        applyStateToControls(saved)
        rebuildPresetChips()
        Toast.makeText(this, R.string.toast_preset_saved, Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedPreset() {
        val id = selectedPresetId
        if (id.isEmpty() || id.startsWith("builtin_")) {
            Toast.makeText(this, R.string.toast_preset_builtin, Toast.LENGTH_SHORT).show()
            return
        }
        MappingPresetStore.delete(this, id)
        current = MappingPresetStore.active(this)
        selectedPresetId = current.id
        applyStateToControls(current)
        rebuildPresetChips()
        Toast.makeText(this, R.string.toast_preset_deleted, Toast.LENGTH_SHORT).show()
    }

    // ── Status refresh ──

    private fun refreshStatus() {
        val rootOk = RootUtils.hasSu()
        binding.textRootStatus.text =
            if (rootOk) getString(R.string.status_root_ok) else getString(R.string.status_root_no)
        binding.textRootStatus.setTextColor(getColor(if (rootOk) R.color.teal_700 else R.color.purple_500))

        val hidgExists = RootUtils.pathExists(HID_PATH)
        binding.textHidgStatus.text = getString(
            if (hidgExists) R.string.status_present else R.string.status_absent
        )
        binding.textHidgStatus.setTextColor(
            getColor(if (hidgExists) R.color.teal_700 else R.color.purple_500)
        )

        binding.btnSetup.isEnabled = rootOk
        binding.btnStart.isEnabled = rootOk && hidgExists
    }

    // ── HID gadget setup (via Root su) ──

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
            LogRepository.d(TAG, "Report descriptor: ${reportDesc.size} bytes, b64=${b64.take(40)}...")

            val kbDesc = bridge.nativeGetKeyboardDescriptor()
            val kbB64 = Base64.encodeToString(kbDesc, Base64.NO_WRAP)
            LogRepository.d(TAG, "Keyboard descriptor: ${kbDesc.size} bytes")

            val result = RootUtils.setupHidGadget(b64, kbB64)

            runOnUiThread {
                binding.btnSetup.isEnabled = true
                binding.btnSetup.text = getString(R.string.btn_setup)

                if (result.success) {
                    LogRepository.i(TAG, "Setup success")
                    Toast.makeText(this, R.string.toast_setup_ok, Toast.LENGTH_SHORT).show()
                } else {
                    LogRepository.e(TAG, "Setup failed: exit=${result.exitCode}")
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

    // ── Launch Tablet Activity ──

    private fun startTabletMode() {
        // Persist whatever the user last edited / selected
        MappingPresetStore.saveDraft(this, current)
        if (selectedPresetId.isNotEmpty()) {
            MappingPresetStore.setActive(this, current)
        }

        LogRepository.i(
            TAG,
            "Launching tablet mode mapping=${current.aspectLabel} scale=${current.scalePercent} " +
                "off=(${current.offsetXPercent},${current.offsetYPercent})"
        )
        val intent = Intent(this, TabletActivity::class.java).apply {
            putExtra("screenW", screenW)
            putExtra("screenH", screenH)
            putExtra("mapAspectW", current.aspectW)
            putExtra("mapAspectH", current.aspectH)
            putExtra("mapScale", current.scalePercent)
            putExtra("mapOffX", current.offsetXPercent)
            putExtra("mapOffY", current.offsetYPercent)
        }
        startActivity(intent)
    }

    // ── Teardown: unmount HID gadget and restore ADB ──

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
                    LogRepository.i(TAG, "Teardown success, ADB restored")
                    Toast.makeText(this, R.string.toast_teardown_ok, Toast.LENGTH_SHORT).show()
                } else {
                    LogRepository.e(TAG, "Teardown failed: ${result.stderr.ifEmpty { result.stdout }}")
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

    // ── OTD config export ──

    private fun exportOtdConfig() {
        try {
            val json = """{
  "Name": "Android USB Tablet",
  "Specifications": {
    "Digitizer": {
      "Width": 289.61,
      "Height": 209.66,
      "MaxX": 32767,
      "MaxY": 32767
    },
    "Pen": {
      "MaxPressure": 8191,
      "ButtonCount": 2
    },
    "AuxiliaryButtons": null,
    "MouseButtons": null,
    "Touch": null
  },
  "DigitizerIdentifiers": [
    {
      "VendorID": 21825,
      "ProductID": 1,
      "InputReportLength": 8,
      "ReportParser": "OpenTabletDriver.Plugin.Tablet.TabletReportParser",
      "DeviceStrings": {}
    },
    {
      "VendorID": 21825,
      "ProductID": 1,
      "InputReportLength": 9,
      "ReportParser": "OpenTabletDriver.Plugin.Tablet.TabletReportParser",
      "DeviceStrings": {}
    },
    {
      "VendorID": 21825,
      "ProductID": 1,
      "ReportParser": "OpenTabletDriver.Plugin.Tablet.TabletReportParser",
      "DeviceStrings": {}
    }
  ],
  "AuxiliaryDeviceIdentifiers": [],
  "Attributes": {}
}
""".trimIndent()

            val outDir = getExternalFilesDir(null) ?: filesDir
            val outFile = File(outDir, "AndroidTablet.json")
            outFile.writeText(json)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_export_otd)))

            Log.i(TAG, "OTD config exported to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Toast.makeText(this,
                getString(R.string.toast_otd_export_fail, e.message ?: "error"), Toast.LENGTH_LONG).show()
        }
    }
}
