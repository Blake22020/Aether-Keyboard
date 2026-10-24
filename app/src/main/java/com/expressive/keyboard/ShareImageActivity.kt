package com.expressive.keyboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * Explicitly import screenshots or other shared images into the Android clipboard.
 * The source is copied to private app storage before forwarding via our FileProvider,
 * so the clipboard remains valid when the sender's temporary URI grant expires.
 */
class ShareImageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
        } else null
        if (source != null) {
            runCatching {
                val dir = File(filesDir, "shared_images").apply { mkdirs() }
                val dest = File(dir, "image_${System.currentTimeMillis()}")
                contentResolver.openInputStream(source)?.use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 20L * 1024L * 1024L) error("Shared image exceeds 20 MB")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Cannot open shared image")
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", dest)
                val clip = ClipData.newUri(contentResolver, "Shared image", uri)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            }
        }
        finish()
    }
}
