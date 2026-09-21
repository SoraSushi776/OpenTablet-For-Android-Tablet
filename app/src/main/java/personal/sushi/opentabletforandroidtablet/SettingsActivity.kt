package personal.sushi.opentabletforandroidtablet

import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import personal.sushi.opentabletforandroidtablet.databinding.ActivitySettingsBinding
import personal.sushi.opentabletforandroidtablet.mapping.MappingPreset
import personal.sushi.opentabletforandroidtablet.mapping.MappingPresetStore
import personal.sushi.opentabletforandroidtablet.mapping.PressureSettings
import personal.sushi.opentabletforandroidtablet.mapping.PressureSettingsStore
import personal.sushi.opentabletforandroidtablet.mapping.TabletBackgroundStore
import personal.sushi.opentabletforandroidtablet.view.PressureCurveView

class SettingsActivity : AppCompatActivity(), PressureCurveView.Listener {

    companion object {
        private const val SEEK_SCALE_MIN = 20
        private const val SEEK_OFFSET_RANGE = 50
    }

    private lateinit var binding: ActivitySettingsBinding
    private var suppressUi = false
    private var selectedPresetId = ""
    private var mapping = MappingPresetStore.builtin.first()
    private var pressure = PressureSettings.DEFAULT

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = TabletBackgroundStore.importCustomImage(this, uri)
        if (ok) {
            Toast.makeText(this, R.string.toast_bg_custom, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_bg_fail, Toast.LENGTH_SHORT).show()
        }
        refreshBackgroundUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPreviewSize()
        setupMappingUi()
        setupPressureUi()
        setupBackgroundUi()
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupBackgroundUi() {
        binding.btnBgDefault.setOnClickListener {
            TabletBackgroundStore.setMode(this, TabletBackgroundStore.MODE_DEFAULT)
            Toast.makeText(this, R.string.toast_bg_default, Toast.LENGTH_SHORT).show()
            refreshBackgroundUi()
        }
        binding.btnBgPick.setOnClickListener {
            pickImage.launch("image/*")
        }
        binding.btnBgNone.setOnClickListener {
            TabletBackgroundStore.setMode(this, TabletBackgroundStore.MODE_NONE)
            Toast.makeText(this, R.string.toast_bg_none, Toast.LENGTH_SHORT).show()
            refreshBackgroundUi()
        }
        refreshBackgroundUi()
    }

    private fun refreshBackgroundUi() {
        val mode = TabletBackgroundStore.mode(this)
        val label = when (mode) {
            TabletBackgroundStore.MODE_DEFAULT -> getString(R.string.bg_mode_default)
            TabletBackgroundStore.MODE_CUSTOM -> getString(R.string.bg_mode_custom)
            else -> getString(R.string.bg_mode_none)
        }
        binding.textBgMode.text = getString(R.string.bg_mode_label, label)

        val bmp = TabletBackgroundStore.loadBitmap(this, maxDim = 1024)
        if (bmp != null) {
            binding.imageBgPreview.setImageBitmap(bmp)
        } else {
            binding.imageBgPreview.setImageDrawable(null)
        }
    }

    private fun setupPreviewSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val density = resources.displayMetrics.density
        val rail = (200 * density).toInt()
        binding.mappingPreview.setSimulatedViewSize(
            (metrics.widthPixels - rail).coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
    }

    // ── Mapping ──

    private fun setupMappingUi() {
        mapping = MappingPresetStore.loadDraft(this) ?: MappingPresetStore.active(this)
        selectedPresetId = mapping.id
        applyMappingToControls(mapping)

        binding.groupAspect.setOnCheckedChangeListener { _, id ->
            if (suppressUi) return@setOnCheckedChangeListener
            val (aw, ah) = when (id) {
                R.id.radioAspect1610 -> 16 to 10
                R.id.radioAspect43 -> 4 to 3
                else -> 16 to 9
            }
            mapping = mapping.copy(aspectW = aw, aspectH = ah, isBuiltin = false)
            onMappingChanged()
        }
        binding.seekScale.setOnSeekBarChangeListener(seek {
            mapping = mapping.copy(scalePercent = it + SEEK_SCALE_MIN, isBuiltin = false)
            onMappingChanged()
        })
        binding.seekOffsetX.setOnSeekBarChangeListener(seek {
            mapping = mapping.copy(offsetXPercent = (it - SEEK_OFFSET_RANGE).toFloat(), isBuiltin = false)
            onMappingChanged()
        })
        binding.seekOffsetY.setOnSeekBarChangeListener(seek {
            mapping = mapping.copy(offsetYPercent = (it - SEEK_OFFSET_RANGE).toFloat(), isBuiltin = false)
            onMappingChanged()
        })
        binding.btnSavePreset.setOnClickListener { saveMappingPreset() }
        binding.btnDeletePreset.setOnClickListener { deleteMappingPreset() }
        rebuildMappingChips()
        binding.mappingPreview.setPreset(mapping)
    }

    private fun seek(on: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && !suppressUi) on(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
    }

    private fun applyMappingToControls(p: MappingPreset) {
        suppressUi = true
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
        if (!p.isBuiltin && p.name.isNotBlank()) binding.editPresetName.setText(p.name)
        suppressUi = false
        updateMappingLabels()
        binding.mappingPreview.setPreset(p)
    }

    private fun onMappingChanged() {
        updateMappingLabels()
        binding.mappingPreview.setPreset(mapping)
        MappingPresetStore.saveDraft(this, mapping)
        highlightMappingChips()
    }

    private fun updateMappingLabels() {
        binding.textScaleValue.text = getString(R.string.fmt_scale_value, mapping.scalePercent)
        binding.textOffsetXValue.text =
            getString(R.string.fmt_offset_value, fmt(mapping.offsetXPercent))
        binding.textOffsetYValue.text =
            getString(R.string.fmt_offset_value, fmt(mapping.offsetYPercent))
    }

    private fun fmt(v: Float) =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

    private fun rebuildMappingChips() {
        val c = binding.presetChipContainer
        c.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        MappingPresetStore.all(this).forEach { preset ->
            val btn = Button(this).apply {
                text = preset.displayLabel
                isAllCaps = false
                setPadding(pad * 2, pad, pad * 2, pad)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = pad }
                setOnClickListener {
                    selectedPresetId = preset.id
                    mapping = preset.copy()
                    MappingPresetStore.setActive(this@SettingsActivity, mapping)
                    applyMappingToControls(mapping)
                    highlightMappingChips()
                }
            }
            c.addView(btn)
        }
        highlightMappingChips()
    }

    private fun highlightMappingChips() {
        val all = MappingPresetStore.all(this)
        val idx = all.indexOfFirst { it.id == selectedPresetId }.coerceAtLeast(0)
        for (i in 0 until binding.presetChipContainer.childCount) {
            (binding.presetChipContainer.getChildAt(i) as? Button)?.alpha =
                if (i == idx) 1f else 0.55f
        }
    }

    private fun saveMappingPreset() {
        val name = binding.editPresetName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.toast_preset_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val toSave = mapping.copy(
            id = if (mapping.isBuiltin || mapping.id.startsWith("builtin_")) "" else mapping.id,
            name = name,
            isBuiltin = false
        )
        val saved = MappingPresetStore.upsert(this, toSave)
        mapping = saved
        selectedPresetId = saved.id
        MappingPresetStore.setActive(this, saved)
        MappingPresetStore.saveDraft(this, saved)
        applyMappingToControls(saved)
        rebuildMappingChips()
        Toast.makeText(this, R.string.toast_preset_saved, Toast.LENGTH_SHORT).show()
    }

    private fun deleteMappingPreset() {
        if (selectedPresetId.isEmpty() || selectedPresetId.startsWith("builtin_")) {
            Toast.makeText(this, R.string.toast_preset_builtin, Toast.LENGTH_SHORT).show()
            return
        }
        MappingPresetStore.delete(this, selectedPresetId)
        mapping = MappingPresetStore.active(this)
        selectedPresetId = mapping.id
        applyMappingToControls(mapping)
        rebuildMappingChips()
        Toast.makeText(this, R.string.toast_preset_deleted, Toast.LENGTH_SHORT).show()
    }

    // ── Pressure ──

    private fun setupPressureUi() {
        pressure = PressureSettingsStore.load(this)
        binding.pressureCurve.listener = this
        binding.pressureCurve.setSettings(pressure)
        binding.seekMaxPressure.progress = pressure.maxPressurePercent
        updatePressureSummary()

        binding.btnPresetClick.setOnClickListener { applyPressurePreset(PressureSettings.CLICK_OPTIMIZED) }
        binding.btnPresetPs.setOnClickListener { applyPressurePreset(PressureSettings.PS_PAINTING) }
        binding.btnPresetDefault.setOnClickListener { applyPressurePreset(PressureSettings.DEFAULT) }

        binding.seekMaxPressure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                pressure = pressure.copy(maxPressurePercent = progress.coerceAtLeast(5))
                binding.pressureCurve.setSettings(pressure)
                commitPressure()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun applyPressurePreset(s: PressureSettings) {
        pressure = s
        binding.pressureCurve.setSettings(s)
        binding.seekMaxPressure.progress = s.maxPressurePercent
        commitPressure()
        Toast.makeText(this, R.string.toast_pressure_preset, Toast.LENGTH_SHORT).show()
    }

    override fun onCurveSettingsChanged(settings: PressureSettings) {
        pressure = settings
        commitPressure()
    }

    private fun commitPressure() {
        PressureSettingsStore.save(this, pressure)
        binding.textMaxPressure.text = getString(R.string.fmt_scale_value, pressure.maxPressurePercent)
        updatePressureSummary()
    }

    private fun updatePressureSummary() {
        binding.textPressureSummary.text = getString(
            R.string.fmt_pressure_summary,
            pressure.contactThresholdPercent,
            pressure.minTipPressurePercent,
            pressure.curveMidXPercent,
            pressure.curveMidYPercent,
            pressure.maxPressurePercent
        )
    }
}
