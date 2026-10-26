package com.expressive.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

// ============================================================================
// ГОЛОСОВОЙ ВВОД ЧЕРЕЗ СИСТЕМНОЕ ОКНО
// На части телефонов (Huawei, Xiaomi без сервисов Google, урезанные прошивки) нет службы распознавания,
// к которой клавиатура может подключиться напрямую, но есть приложение с окном «Говорите» (ассистент,
// Google, Яндекс). Клавиатура не может ждать результата окна сама — его ждёт эта прозрачная активность,
// а текст вставляется, когда приложение снова получает фокус.
// ============================================================================

object VoiceBridge {
    /** Распознанный текст, который ещё не вставлен. */
    @Volatile
    private var pending: String? = null

    fun deliver(text: String?) {
        pending = text?.trim()?.takeIf { it.isNotEmpty() }
    }

    val hasPending: Boolean get() = pending != null

    /** Забрать текст один раз. */
    @Synchronized
    fun take(): String? = pending.also { pending = null }

    internal fun recognizeIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** Есть приложение, которое откроет окно распознавания. */
    fun available(context: Context): Boolean = runCatching {
        context.packageManager.queryIntentActivities(recognizeIntent("en-US"), 0).isNotEmpty()
    }.getOrDefault(false)

    fun launch(context: Context, language: String): Boolean = runCatching {
        pending = null
        context.startActivity(
            Intent(context, VoiceActivity::class.java)
                .putExtra("lang", language)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        )
        true
    }.getOrDefault(false)
}

class VoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // после пересоздания (поворот) окно распознавания уже открыто — просто ждём результат
        if (savedInstanceState != null) return
        val lang = intent.getStringExtra("lang") ?: "en-US"
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(VoiceBridge.recognizeIntent(lang), REQUEST) }.onFailure { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK) {
            VoiceBridge.deliver(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
        }
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST = 7
    }
}
