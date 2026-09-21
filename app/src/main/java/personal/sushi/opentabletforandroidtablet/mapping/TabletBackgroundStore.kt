package personal.sushi.opentabletforandroidtablet.mapping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Tablet area background image: none / built-in asset / user-picked file.
 * Image is drawn stretched over the active mapping region (or full view).
 */
object TabletBackgroundStore {

    private const val PREFS = "tablet_background"
    private const val KEY_MODE = "mode" // none | default | custom
    private const val CUSTOM_FILE = "tablet_bg_custom.jpg"
    private const val ASSET_NAME = "tablet_bg.jpg"

    const val MODE_NONE = "none"
    const val MODE_DEFAULT = "default"
    const val MODE_CUSTOM = "custom"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_DEFAULT) ?: MODE_DEFAULT

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
    }

    fun customFile(context: Context): File = File(context.filesDir, CUSTOM_FILE)

    /**
     * Copy a picked content URI into app storage so it survives permission loss.
     * @return true on success
     */
    fun importCustomImage(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val dest = customFile(context)
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            } ?: return false
            // Validate decode
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(customFile(context).absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
            setMode(context, MODE_CUSTOM)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decode the active background, or null if none / failed.
     * Downsamples large images to [maxDim] on the long edge.
     */
    fun loadBitmap(context: Context, maxDim: Int = 2048): Bitmap? {
        return when (mode(context)) {
            MODE_DEFAULT -> decodeAsset(context, maxDim)
            MODE_CUSTOM -> {
                val f = customFile(context)
                if (!f.isFile) null else decodeFile(f.absolutePath, maxDim)
            }
            else -> null
        }
    }

    private fun decodeAsset(context: Context, maxDim: Int): Bitmap? {
        return try {
            context.assets.open(ASSET_NAME).use { input ->
                val bytes = input.readBytes()
                decodeBytes(bytes, maxDim)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFile(path: String, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun calculateInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var width = w
        var height = h
        while (width / 2 >= maxDim || height / 2 >= maxDim || maxOf(width, height) / 2 >= maxDim) {
            width /= 2
            height /= 2
            sample *= 2
            if (sample >= 32) break
        }
        // Also cap by max edge
        val longest = maxOf(w, h)
        if (longest > maxDim) {
            val direct = longest / maxDim
            if (direct > sample) sample = Integer.highestOneBit(direct)
        }
        return sample.coerceAtLeast(1)
    }
}
