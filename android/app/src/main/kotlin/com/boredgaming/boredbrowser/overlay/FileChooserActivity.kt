package com.boredgaming.boredbrowser.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.File

/**
 * Invisible activity that opens the system file picker for the overlay browser.
 * A Service can't receive activity results, so the picked files come back through [pendingCallback].
 */
class FileChooserActivity : Activity() {
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val chooser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHOOSER_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHOOSER_INTENT)
        }
        if (chooser == null) {
            deliver(null)
            return
        }
        runCatching { startActivityForResult(chooser, REQUEST_CODE) }
            .onFailure { deliver(null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        val picked = if (resultCode == RESULT_OK) pickedUris(data) else null
        if (picked == null) {
            deliver(null)
            return
        }
        // Read access to picked files ends when this activity finishes, before the page
        // uploads them, so hand the page private copies instead. Copy off the main thread.
        Thread {
            val copies = copyToCache(picked)
            runOnUiThread { deliver(copies) }
        }.start()
    }

    override fun onDestroy() {
        // The WebView won't open another picker until the callback is answered, even with nothing.
        if (isFinishing) deliver(null)
        super.onDestroy()
    }

    private fun pickedUris(data: Intent?): Array<Uri>? {
        data ?: return null
        val clip = data.clipData
        val uris = if (clip != null) {
            List(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun copyToCache(uris: Array<Uri>): Array<Uri>? {
        val dir = File(cacheDir, UPLOADS_DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        val copies = uris.mapIndexedNotNull { index, uri ->
            runCatching {
                // One folder per file keeps the original name (sites see it) without collisions.
                val target = File(File(dir, index.toString()).apply { mkdirs() }, displayName(uri))
                contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(target)
            }.getOrNull()
        }
        return copies.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "upload"
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun deliver(uris: Array<Uri>?) {
        if (delivered) return
        delivered = true
        val callback = pendingCallback
        pendingCallback = null
        callback?.invoke(uris)
        finish()
    }

    companion object {
        const val EXTRA_CHOOSER_INTENT = "chooserIntent"
        private const val REQUEST_CODE = 1
        private const val UPLOADS_DIR = "uploads"

        var pendingCallback: ((Array<Uri>?) -> Unit)? = null
    }
}
