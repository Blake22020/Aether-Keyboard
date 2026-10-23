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
// 1. ФИГУРЫ MATERIAL 3 EXPRESSIVE
// ============================================================================

enum class ShapeProfile { SINE, LOBES, CIRCLE }

enum class ExpressiveShapePreset(
    val id: String,
    val title: String,
    val profile: ShapeProfile,
    val defaultWaves: Int,
    val defaultAmplitude: Float
) {
    COOKIE_12("COOKIE_12", "Печенька", ShapeProfile.SINE, 12, 6f),
    SUNNY_8("WAVY_8", "Солнце", ShapeProfile.SINE, 8, 8f),
    FLOWER_6("FLOWER_6", "Цветок", ShapeProfile.LOBES, 6, 10f),
    CLOVER_4("CLOVER_4", "Клевер", ShapeProfile.LOBES, 4, 12f),
    CIRCLE("CIRCLE", "Круг", ShapeProfile.CIRCLE, 12, 0f);

    companion object {
        fun fromId(id: String): ExpressiveShapePreset = entries.firstOrNull { it.id == id } ?: COOKIE_12
    }
}

@Immutable
data class WavySpec(val profile: ShapeProfile, val waves: Int, val amplitude: Float)

fun buildWavyPath(spec: WavySpec, cx: Float, cy: Float, radius: Float, path: Path = Path()): Path {
    path.reset()
    val waves = spec.waves.coerceIn(3, 24)
    val amp = if (spec.profile == ShapeProfile.CIRCLE) 0f else spec.amplitude.coerceIn(0f, 0.16f)
    val steps = (waves * 20).coerceIn(120, 400)
    for (i in 0 until steps) {
        val t = i.toDouble() / steps * 2.0 * PI
        val k = when (spec.profile) {
            ShapeProfile.SINE -> 1f - amp + amp * cos(waves * t).toFloat()
            ShapeProfile.LOBES -> 1f - amp + amp * abs(cos(waves / 2.0 * t)).toFloat()
            ShapeProfile.CIRCLE -> 1f
        }
        val a = t - PI / 2.0
        val x = cx + radius * k * cos(a).toFloat()
        val y = cy + radius * k * sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Волнистый фон без clip/shadow: путь кэшируется по размеру, вращение читается только при отрисовке. */
fun Modifier.wavyBackground(spec: WavySpec, color: Color, rotation: () -> Float = { 0f }): Modifier =
    this.drawWithCache {
        val path = buildWavyPath(spec, size.width / 2f, size.height / 2f, min(size.width, size.height) / 2f)
        onDrawBehind {
            rotate(rotation(), center) { drawPath(path, color) }
        }
    }
