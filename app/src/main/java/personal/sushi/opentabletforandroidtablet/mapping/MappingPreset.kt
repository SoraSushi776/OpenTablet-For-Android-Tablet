package personal.sushi.opentabletforandroidtablet.mapping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MappingPreset(
    val id: String,
    val name: String,
    val aspectW: Int,
    val aspectH: Int,
    val scalePercent: Int,
    val offsetXPercent: Float,
    val offsetYPercent: Float,
    val isBuiltin: Boolean = false
) {
    val aspectLabel: String get() = "$aspectW:$aspectH"
    val displayLabel: String
        get() = if (isBuiltin) name else name.ifBlank { "$aspectLabel · ${scalePercent}%" }
}

object MappingPresetStore {

    private const val PREFS = "mapping_presets"
    private const val KEY_USER = "user_presets"
    private const val KEY_ACTIVE_ID = "active_id"
    private const val KEY_DRAFT = "draft"

    val builtin: List<MappingPreset>
        get() = listOf(
            MappingPreset(
                id = "builtin_16_9",
                name = "16:9 · 100%",
                aspectW = 16, aspectH = 9,
                scalePercent = 100,
                offsetXPercent = 0f,
                offsetYPercent = 0f,
                isBuiltin = true
            ),
            MappingPreset(
                id = "builtin_16_10",
                name = "16:10 · 100%",
                aspectW = 16, aspectH = 10,
                scalePercent = 100,
                offsetXPercent = 0f,
                offsetYPercent = 0f,
                isBuiltin = true
            )
        )

    fun all(context: Context): List<MappingPreset> = builtin + loadUser(context)

    fun loadUser(context: Context): List<MappingPreset> {
        val raw = prefs(context).getString(KEY_USER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        MappingPreset(
                            id = o.getString("id"),
                            name = o.optString("name"),
                            aspectW = o.getInt("aspectW"),
                            aspectH = o.getInt("aspectH"),
                            scalePercent = o.getInt("scale"),
                            offsetXPercent = o.optDouble("offX", 0.0).toFloat(),
                            offsetYPercent = o.optDouble("offY", 0.0).toFloat(),
                            isBuiltin = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveUserList(context: Context, list: List<MappingPreset>) {
        val arr = JSONArray()
        list.filter { !it.isBuiltin }.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("aspectW", p.aspectW)
                put("aspectH", p.aspectH)
                put("scale", p.scalePercent)
                put("offX", p.offsetXPercent.toDouble())
                put("offY", p.offsetYPercent.toDouble())
            })
        }
        prefs(context).edit().putString(KEY_USER, arr.toString()).apply()
    }

    fun upsert(context: Context, preset: MappingPreset): MappingPreset {
        val withId = if (preset.id.isBlank()) {
            preset.copy(id = UUID.randomUUID().toString().take(8))
        } else preset
        val list = loadUser(context).toMutableList()
        val idx = list.indexOfFirst { it.id == withId.id }
        if (idx >= 0) list[idx] = withId else list.add(withId)
        saveUserList(context, list)
        return withId
    }

    fun delete(context: Context, id: String) {
        if (id.startsWith("builtin_")) return
        saveUserList(context, loadUser(context).filterNot { it.id == id })
        if (prefs(context).getString(KEY_ACTIVE_ID, null) == id) {
            setActive(context, builtin.first())
        }
    }

    fun active(context: Context): MappingPreset {
        val id = prefs(context).getString(KEY_ACTIVE_ID, null)
        all(context).firstOrNull { it.id == id }?.let { return it }
        // Fall back to saved draft, then builtin 16:9
        loadDraft(context)?.let { return it }
        return builtin.first()
    }

    fun setActive(context: Context, preset: MappingPreset) {
        prefs(context).edit()
            .putString(KEY_ACTIVE_ID, preset.id)
            .putString(KEY_DRAFT, draftJson(preset))
            .apply()
    }

    /** Persist in-progress slider state (not necessarily a named preset). */
    fun saveDraft(context: Context, preset: MappingPreset) {
        prefs(context).edit().putString(KEY_DRAFT, draftJson(preset)).apply()
    }

    fun loadDraft(context: Context): MappingPreset? {
        val raw = prefs(context).getString(KEY_DRAFT, null) ?: return null
        return try {
            val o = JSONObject(raw)
            MappingPreset(
                id = o.optString("id"),
                name = o.optString("name"),
                aspectW = o.getInt("aspectW"),
                aspectH = o.getInt("aspectH"),
                scalePercent = o.getInt("scale"),
                offsetXPercent = o.optDouble("offX", 0.0).toFloat(),
                offsetYPercent = o.optDouble("offY", 0.0).toFloat(),
                isBuiltin = o.optBoolean("builtin", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun draftJson(p: MappingPreset): String = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("aspectW", p.aspectW)
        put("aspectH", p.aspectH)
        put("scale", p.scalePercent)
        put("offX", p.offsetXPercent.toDouble())
        put("offY", p.offsetYPercent.toDouble())
        put("builtin", p.isBuiltin)
    }.toString()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
