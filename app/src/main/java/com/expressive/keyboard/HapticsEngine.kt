@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 5. ВИБРАЦИЯ И ЗВУК
// ============================================================================

object HapticsEngine {
    @Volatile private var cached: Vibrator? = null

    private fun vibrator(context: Context): Vibrator? {
        cached?.let { return it }
        val v = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
        cached = v
        return v
    }

    fun keyPress(context: Context, view: View?, s: KeyboardSettings, special: Boolean) {
        if (s.soundEnabled) runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.playSoundEffect(
                if (special) AudioManager.FX_KEYPRESS_SPACEBAR else AudioManager.FX_KEYPRESS_STANDARD, s.soundVolume
            )
        }
        if (!s.hapticsEnabled) return
        runCatching {
            val v = vibrator(context)
            if (v == null || !v.hasVibrator()) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return
            }
            val boost = s.strongSpecialKeys && special
            val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            when (HapticMode.fromId(s.hapticMode)) {
                HapticMode.CLICK -> if (q) v.vibrate(
                    VibrationEffect.createPredefined(if (boost) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK)
                ) else oneShot(v, if (boost) 22L else 12L, if (boost) 200 else 140)
                HapticMode.TICK -> if (q) v.vibrate(
                    VibrationEffect.createPredefined(if (boost) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
                ) else oneShot(v, if (boost) 14L else 8L, if (boost) 130 else 80)
                HapticMode.HEAVY -> if (q) v.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                ) else oneShot(v, 28L, 255)
                HapticMode.CUSTOM -> {
                    val d = s.vibrationMs.toLong().coerceIn(1L, 100L)
                    val a = s.vibrationAmplitude.toInt().coerceIn(1, 255)
                    oneShot(v, if (boost) (d * 1.3f).toLong() else d, if (boost) (a * 1.25f).toInt() else a)
                }
            }
        }
    }

    fun tick(context: Context, view: View?, s: KeyboardSettings) {
        if (!s.hapticsEnabled) return
        runCatching {
            val v = vibrator(context)
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else oneShot(v, 7L, 80)
            } else view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun release(context: Context, s: KeyboardSettings) {
        if (!s.hapticsEnabled || !s.vibrateOnRelease) return
        runCatching {
            val v = vibrator(context) ?: return
            if (!v.hasVibrator()) return
            oneShot(v, (s.vibrationMs * 0.45f).toLong(), (s.vibrationAmplitude * 0.5f).toInt())
        }
    }

    fun preview(context: Context, mode: HapticMode, durationMs: Float, amplitude: Float) {
        keyPress(
            context, null,
            KeyboardSettings(hapticMode = mode.id, vibrationMs = durationMs, vibrationAmplitude = amplitude),
            false
        )
    }

    private fun oneShot(v: Vibrator, duration: Long, amplitude: Int) {
        runCatching {
            val d = duration.coerceIn(1L, 100L)
            val a = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(d, a))
        }
    }
}
