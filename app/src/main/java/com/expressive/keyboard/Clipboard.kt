package com.expressive.keyboard

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================================
// КАРТИНКИ В БУФЕРЕ ОБМЕНА
// Запись истории — строка. Картинка хранится файлом в files/clips, а в историю
// попадает строка-ссылка с невидимым префиксом: так текст и картинки живут
// в одном списке, закрепляются и удаляются одинаково.
// ============================================================================

object ClipImages {
    private const val PREFIX = "\u0000img:"
    private const val MAX_BYTES = 20L * 1024 * 1024

    fun isImage(entry: String) = entry.startsWith(PREFIX)

    fun file(entry: String): File = File(entry.removePrefix(PREFIX).substringBefore('\u0000'))

    fun mime(entry: String): String = entry.substringAfterLast('\u0000', "image/png").ifEmpty { "image/png" }

    private fun dir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }

    /** Копирует картинку из буфера к себе: доступ к чужому uri пропадает, а история должна жить дальше. */
    suspend fun save(context: Context, uri: Uri, mime: String, stamp: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            val out = File(dir(context), "clip_$stamp.$ext")
            if (!out.exists() || out.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("too big")
                            o.write(buf, 0, n)
                        }
                    }
                } ?: return@runCatching null
            }
            PREFIX + out.absolutePath + "\u0000" + mime
        }.getOrElse { null }
    }

    fun delete(entry: String) {
        if (isImage(entry)) runCatching { file(entry).delete() }
    }

    /** Удалить файлы, на которые больше ничто не ссылается. */
    fun cleanup(context: Context, keep: Collection<String>) {
        val used = keep.filter { isImage(it) }.map { file(it).absolutePath }.toSet()
        runCatching { dir(context).listFiles()?.forEach { if (it.absolutePath !in used) it.delete() } }
    }

    /** Уменьшенная копия для плитки: не больше maxPx по большей стороне. */
    fun thumbnail(entry: String, maxPx: Int): ImageBitmap? = runCatching {
        val f = file(entry)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx || o.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

/** Миниатюра картинки из буфера; пока грузится — пустая подложка. */
@Composable
internal fun ClipThumb(entry: String, modifier: Modifier, placeholder: Color, maxPx: Int = 360) {
    val bmp = produceState<ImageBitmap?>(null, entry) {
        value = withContext(Dispatchers.IO) { ClipImages.thumbnail(entry, maxPx) }
    }.value
    if (bmp != null) Image(bmp, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(placeholder))
}
