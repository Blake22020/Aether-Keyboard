package com.expressive.keyboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Watches screenshots visible to this app, while the keyboard service is alive.
 * The user must opt in and grant image-library access. No screen capture is performed.
 */
internal class ScreenshotWatcher(
    private val context: Context,
    private val onScreenshot: (android.net.Uri, Long) -> Unit
) {
    private val prefs = context.getSharedPreferences("aether_screenshots", Context.MODE_PRIVATE)
    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private var listening = false
    private var lastSeen = prefs.getLong("last_seen_seconds", 0L)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { scan() }
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) { scan() }
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun refresh(enabled: Boolean) {
        val allowed = enabled && hasPermission()
        if (allowed && !listening) {
            if (lastSeen == 0L) {
                lastSeen = System.currentTimeMillis() / 1000
                prefs.edit().putLong("last_seen_seconds", lastSeen).apply()
            }
            context.contentResolver.registerContentObserver(collection, true, observer)
            listening = true
        } else if (!allowed && listening) {
            stop()
        }
        if (allowed) scan()
    }

    fun stop() {
        if (listening) context.contentResolver.unregisterContentObserver(observer)
        listening = false
    }

    private fun scan() {
        if (!listening || !hasPermission()) return
        val now = System.currentTimeMillis() / 1000
        // Time window prevents importing older images on first enable and bounds repeated scans.
        val threshold = maxOf(lastSeen - 2L, now - 600L)
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED)
        val found = ArrayList<Pair<android.net.Uri, Long>>()
        runCatching {
            context.contentResolver.query(collection, columns,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(threshold.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && found.size < 30) {
                    val name = cursor.getString(nameColumn).orEmpty()
                    val date = cursor.getLong(dateColumn)
                    if (date <= lastSeen || now - date !in 0..600) continue
                    // Screenshot filenames in localized media providers may differ; avoid reading non-screenshots.
                    if (!name.contains("screenshot", true) && !name.contains("скриншот", true)
                        && !name.contains("screen_shot", true)) continue
                    found += ContentUris.withAppendedId(collection, cursor.getLong(idColumn)) to (date * 1000)
                }
            }
        }
        // Oldest first; only the most recent image will remain the offered clip.
        found.asReversed().forEach { (uri, stamp) -> onScreenshot(uri, stamp) }
        if (found.isNotEmpty()) {
            lastSeen = maxOf(lastSeen, found.maxOf { it.second / 1000 })
            prefs.edit().putLong("last_seen_seconds", lastSeen).apply()
        }
    }
}
