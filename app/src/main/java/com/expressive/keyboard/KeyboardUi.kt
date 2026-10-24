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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
// 8. UI КЛАВИАТУРЫ (MATERIAL 3 EXPRESSIVE ПО МАКЕТУ)
// ============================================================================

@Immutable
data class KeyPalette(
    val background: Color,
    val letter: Color, val onLetter: Color,
    val func: Color, val onFunc: Color,
    val accent: Color, val onAccent: Color,
    val accentSoft: Color, val onAccentSoft: Color,
    val panel: Color, val outline: Color, val muted: Color
)

/** Читаемый цвет текста поверх произвольного фона. */
fun contentColorFor(bg: Color): Color = if (bg.luminance() > 0.42f) Color(0xFF1B1B1F) else Color(0xFFF5F5F7)

/** Палитра клавиатуры: Monet/свой цвет + ручные переопределения + прозрачность клавиш. */
fun buildKeyPalette(cs: ColorScheme, s: KeyboardSettings): KeyPalette {
    fun pick(v: Int, auto: Color) = if (v == 0) auto else Color(v)
    val bg = pick(s.colorBackground, cs.surfaceContainer)
    val letter = pick(s.colorKey, cs.surfaceBright)
    val onLetter = when {
        s.colorKeyText != 0 -> Color(s.colorKeyText)
        s.colorKey != 0 -> contentColorFor(letter)
        else -> cs.onSurface
    }
    val func = pick(s.colorFunc, cs.secondaryContainer)
    val onFunc = when {
        s.colorFuncText != 0 -> Color(s.colorFuncText)
        s.colorFunc != 0 -> contentColorFor(func)
        else -> cs.onSecondaryContainer
    }
    val accent = pick(s.colorAccent, cs.primary)
    val onAccent = when {
        s.colorAccentText != 0 -> Color(s.colorAccentText)
        s.colorAccent != 0 -> contentColorFor(accent)
        else -> cs.onPrimary
    }
    val accentSoft = if (s.colorAccent != 0) lerp(accent, bg, 0.62f) else cs.primaryContainer
    val onAccentSoft = if (s.colorAccent != 0) contentColorFor(accentSoft) else cs.onPrimaryContainer
    val panel = if (s.colorBackground != 0) lerp(bg, letter, 0.35f) else cs.surfaceContainerHigh
    val a = s.keyOpacity
    return KeyPalette(
        background = bg,
        letter = letter.copy(alpha = letter.alpha * a), onLetter = onLetter,
        func = func.copy(alpha = func.alpha * a), onFunc = onFunc,
        accent = accent, onAccent = onAccent,
        accentSoft = accentSoft.copy(alpha = accentSoft.alpha * (0.5f + a / 2f)), onAccentSoft = onAccentSoft,
        panel = panel.copy(alpha = panel.alpha * (0.4f + a * 0.6f)),
        outline = cs.outlineVariant,
        muted = if (s.colorKeyText != 0 || s.colorKey != 0) onLetter.copy(alpha = 0.66f) else cs.onSurfaceVariant
    )
}

@Composable
fun rememberKeyPalette(settings: KeyboardSettings): KeyPalette {
    val cs = MaterialTheme.colorScheme
    return remember(
        cs, settings.colorBackground, settings.colorKey, settings.colorKeyText, settings.colorFunc,
        settings.colorFuncText, settings.colorAccent, settings.colorAccentText, settings.keyOpacity
    ) { buildKeyPalette(cs, settings) }
}

/** Фоновая картинка декодируется в фоне один раз на версию файла. */
@Composable
fun rememberBackgroundBitmap(version: Long): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(null, version) {
        value = if (version == 0L) null else withContext(Dispatchers.IO) {
            runCatching {
                val f = File(context.filesDir, KeyboardPrefs.BG_IMAGE_FILENAME)
                if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
            }.getOrNull()
        }
    }
    return state.value
}

enum class Glyph { SHIFT, CAPS, BACKSPACE, ARROW, RETURN, GLOBE, EMOJI, GRID }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawGlyph(glyph, color)
    }
}

internal fun DrawScope.drawGlyph(g: Glyph, color: Color) {
    val u = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    val round = Stroke(width = 3.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        Glyph.SHIFT, Glyph.CAPS -> {
            val p = Path().apply {
                moveTo(12f * u, 3.5f * u); lineTo(20.5f * u, 12f * u); lineTo(15.5f * u, 12f * u)
                lineTo(15.5f * u, 18f * u); lineTo(8.5f * u, 18f * u); lineTo(8.5f * u, 12f * u)
                lineTo(3.5f * u, 12f * u); close()
            }
            drawPath(p, color)
            drawPath(p, color, style = Stroke(width = 2.6f * u, join = StrokeJoin.Round))
            if (g == Glyph.CAPS) drawLine(color, o(8f, 21.6f), o(16f, 21.6f), 2.6f * u, StrokeCap.Round)
        }
        Glyph.BACKSPACE -> {
            drawLine(color, o(6.5f, 6.5f), o(17.5f, 17.5f), 3.4f * u, StrokeCap.Round)
            drawLine(color, o(17.5f, 6.5f), o(6.5f, 17.5f), 3.4f * u, StrokeCap.Round)
        }
        Glyph.ARROW -> {
            drawLine(color, o(4.5f, 12f), o(18.5f, 12f), 3.4f * u, StrokeCap.Round)
            val p = Path().apply { moveTo(12.5f * u, 6f * u); lineTo(18.8f * u, 12f * u); lineTo(12.5f * u, 18f * u) }
            drawPath(p, color, style = Stroke(width = 3.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Glyph.RETURN -> {
            val p = Path().apply { moveTo(19f * u, 5f * u); lineTo(19f * u, 13f * u); lineTo(5.5f * u, 13f * u) }
            drawPath(p, color, style = round)
            val h = Path().apply { moveTo(10f * u, 8.5f * u); lineTo(5.5f * u, 13f * u); lineTo(10f * u, 17.5f * u) }
            drawPath(h, color, style = round)
        }
        Glyph.GLOBE -> {
            // контурный глобус: окружность, меридиан-овал и три параллели
            val r = 9.6f
            val line = Stroke(width = 2.1f * u, cap = StrokeCap.Round)
            drawCircle(color, r * u, o(12f, 12f), style = line)
            drawOval(color, o(12f - 4.3f, 12f - r), Size(8.6f * u, 2f * r * u), style = line)
            drawLine(color, o(12f, 12f - r), o(12f, 12f + r), 2.1f * u, StrokeCap.Round)
            for (dy in listOf(-4.8f, 0f, 4.8f)) {
                val half = sqrt(r * r - dy * dy)
                drawLine(color, o(12f - half, 12f + dy), o(12f + half, 12f + dy), 1.9f * u, StrokeCap.Round)
            }
        }
        Glyph.EMOJI -> {
            drawCircle(color, 10.5f * u, o(12f, 12f))
            drawCircle(Color.Black, 1.7f * u, o(8.6f, 9.2f), blendMode = BlendMode.Clear)
            drawCircle(Color.Black, 1.7f * u, o(15.4f, 9.2f), blendMode = BlendMode.Clear)
            drawArc(
                Color.Black, 0f, 180f, true, topLeft = o(6.8f, 8.6f), size = Size(10.4f * u, 9.8f * u),
                blendMode = BlendMode.Clear
            )
        }
        Glyph.GRID -> {
            drawRoundRect(color, o(3f, 3f), Size(8f * u, 8f * u), CornerRadius(2.4f * u, 2.4f * u))
            drawCircle(color, 4f * u, o(17f, 7f))
            drawCircle(color, 4f * u, o(7f, 17f))
            drawRoundRect(color, o(13f, 13f), Size(8f * u, 8f * u), CornerRadius(2.4f * u, 2.4f * u))
        }
    }
}

@Immutable
data class KeyColors(val bg: Color, val fg: Color, val activeBg: Color, val activeFg: Color)

internal fun keyColors(type: KeyType, shift: ShiftState, p: KeyPalette): KeyColors = when (type) {
    KeyType.CHARACTER, KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE, KeyType.GLOBE, KeyType.STICKERS ->
        KeyColors(p.letter, p.onLetter, p.accent, p.onAccent)
    KeyType.SHIFT -> if (shift == ShiftState.OFF) KeyColors(p.accentSoft, p.onAccentSoft, p.accent, p.onAccent)
    else KeyColors(p.accent, p.onAccent, p.accentSoft, p.onAccentSoft)
    KeyType.BACKSPACE -> KeyColors(p.func, p.onFunc, p.accent, p.onAccent)
    KeyType.SPACE -> KeyColors(p.func, p.onFunc, p.accentSoft, p.onAccentSoft)
    KeyType.ENTER -> KeyColors(p.accent, p.onAccent, lerp(p.accent, p.onAccent, 0.18f), p.onAccent)
    KeyType.SPACER -> KeyColors(Color.Transparent, Color.Transparent, Color.Transparent, Color.Transparent)
}

/** Доля скругления от меньшей стороны: в покое → при нажатии (морфинг формы). */
internal fun shapeFractions(type: KeyType): Pair<Float, Float> = when (type) {
    KeyType.CHARACTER, KeyType.SHIFT -> 0.38f to 0.5f
    KeyType.BACKSPACE -> 0.5f to 0.28f
    KeyType.ENTER -> 0.42f to 0.24f
    KeyType.SPACE -> 0.4f to 0.5f
    KeyType.MODE_SYMBOLS, KeyType.SYMBOLS_PAGE -> 0.3f to 0.5f
    else -> 0.33f to 0.5f
}

@Stable
class CoordsHolder {
    var coords: LayoutCoordinates? = null
}

@Immutable
data class PopupState(
    val owner: Any,
    val anchorX: Float,
    val anchorTop: Float,
    val primary: String,
    val variants: List<String>,
    val selected: Int,
    val expanded: Boolean
)

/** Состояние всплывашки читает ТОЛЬКО слой всплывашки — клавиатура при нажатии не перекомпонуется. */
@Stable
class PopupController {
    var state by mutableStateOf<PopupState?>(null)
        private set

    fun show(s: PopupState) {
        state = s
    }

    fun select(owner: Any, index: Int) {
        val c = state ?: return
        if (c.owner === owner && c.selected != index) state = c.copy(selected = index)
    }

    fun clear(owner: Any) {
        if (state?.owner === owner) state = null
    }
}

/** Чем закончилось касание буквы до выбора варианта. */
internal enum class KeyGesture { RELEASED, CANCELED, HOLD, SLIDE, GLIDE, SWIPE_UP }

internal suspend fun AwaitPointerEventScope.awaitPressEnd(id: PointerId): Boolean {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) return true
    }
}

internal val VARIANT_KEY = KeyModel("")
internal const val DELETE_WORD = "\u0000word"

internal fun hintFor(label: String, variants: List<String>): String? {
    if (variants.size <= 1) return null
    return variants.firstOrNull { it.length == 1 && it[0].isDigit() }
        ?: variants.firstOrNull { !it.equals(label, ignoreCase = true) }
}

/** Положение клавиши относительно фона клавиатуры — нужно стеклу, чтобы «видеть», что под ним. */
internal fun keyPosIn(holder: CoordsHolder, backdrop: Backdrop?): Offset? {
    val k = holder.coords ?: return null
    val r = backdrop?.coords ?: return null
    if (!k.isAttached || !r.isAttached) return null
    return r.localPositionOf(k, Offset.Zero)
}

@Composable
internal fun KeyView(
    key: KeyModel,
    label: String,
    upper: Boolean,
    shift: ShiftState,
    enterAction: Int,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController,
    actions: KeyboardActions,
    glideEnabled: Boolean,
    numberRow: Boolean,
    modifier: Modifier
) {
    val pluginState = PluginRepository.active
    val variants = remember(key, upper, pluginState) { key.variants(upper) }
    val output = remember(key, upper) { key.text(upper) }
    val holder = remember { CoordsHolder() }
    val anim = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current
    val backdrop = LocalBackdrop.current
    val style = remember(skin, key.type, key.label) { skin?.styleFor(key.type, key.label) }
    val skinCache = remember { SkinKeyCache() }
    val isChar = key.type == KeyType.CHARACTER
    val upHint = if (isChar) remember(label, variants) { hintFor(label, variants) } else null
    val cKey by rememberUpdatedState(key)
    val cLabel by rememberUpdatedState(label)
    val cOutput by rememberUpdatedState(output)
    val cVariants by rememberUpdatedState(variants)
    val cSettings by rememberUpdatedState(settings)
    val cActions by rememberUpdatedState(actions)
    val cGlide by rememberUpdatedState(glideEnabled)
    val cUpHint by rememberUpdatedState(upHint)
    val colors = remember(key.type, shift, palette) { keyColors(key.type, shift, palette) }
    val fr = shapeFractions(key.type)
    val capPx = with(LocalDensity.current) { settings.cornerRadiusDp.dp.toPx() }
    val border = settings.showKeyBorders
    val borderColor = palette.outline
    val press = settings.pressAnimation
    val effect = style?.press ?: PressEffect.SCALE
    val cEffect by rememberUpdatedState(effect)
    val pressScale = style?.pressScale
    val weight = LocalKeyWeight.current
    val hint = if (isChar && settings.showHints && !numberRow && style?.hideHint != true) upHint else null

    // клавиша видна модам: kb.keys(), kb.pressKey()
    val handle = remember(key, label) {
        KeyHandle(key, label, holder) {
            scope.launch {
                anim.snapTo(0f)
                anim.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 1800f))
                anim.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 600f))
            }
        }
    }
    DisposableEffect(handle) {
        KeyboardGeometry.register(handle)
        onDispose { KeyboardGeometry.unregister(handle) }
    }

    // буквы регистрируются для печати свайпом
    if (isChar && label.length == 1 && label[0].isLetter()) {
        DisposableEffect(label, glide) {
            val l = label.lowercase()
            glide.register(l, holder)
            onDispose { glide.unregister(l, holder) }
        }
    }

    fun anchor(): Offset? {
        val k = holder.coords ?: return null
        val l = layer.coords ?: return null
        if (!k.isAttached || !l.isAttached) return null
        val p = l.localPositionOf(k, Offset.Zero)
        return Offset(p.x + k.size.width / 2f, p.y)
    }

    fun origin(): Offset? {
        val k = holder.coords ?: return null
        val l = layer.coords ?: return null
        if (!k.isAttached || !l.isAttached) return null
        return l.localPositionOf(k, Offset.Zero)
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { holder.coords = it }
            .graphicsLayer {
                when (effect) {
                    PressEffect.SCALE -> {
                        val s = 1f - (1f - (pressScale ?: 0.95f)) * press * anim.value.coerceIn(0f, 1.2f)
                        scaleX = s
                        scaleY = s
                    }
                    PressEffect.JELLY -> {
                        val a = press * anim.value.coerceIn(-0.6f, 1.3f)
                        scaleX = 1f + 0.08f * a
                        scaleY = 1f - 0.08f * a
                    }
                    PressEffect.BOUNCE -> {
                        val s = 1f + ((pressScale ?: 1.08f) - 1f) * press * anim.value.coerceIn(-0.3f, 1.3f)
                        scaleX = s
                        scaleY = s
                    }
                    PressEffect.SINK -> {
                        val a = press * anim.value.coerceIn(0f, 1.2f)
                        translationY = 2.5.dp.toPx() * a
                        scaleX = 1f - 0.02f * a
                        scaleY = 1f - 0.02f * a
                    }
                    PressEffect.NONE -> Unit
                }
            }
            .drawBehind {
                val p = anim.value.coerceIn(0f, 1f)
                val m = min(size.width, size.height)
                var rest = fr.first * m
                if (isChar) rest = min(rest, capPx)
                val st = style
                if (st != null) {
                    drawSkinKey(st, colors, p, rest, fr.second * m, skinCache, backdrop) { keyPosIn(holder, backdrop) }
                } else {
                    val r = lerpF(rest, fr.second * m, p)
                    val cr = CornerRadius(r, r)
                    drawRoundRect(color = lerp(colors.bg, colors.activeBg, p), cornerRadius = cr)
                    if (border) drawRoundRect(color = borderColor, cornerRadius = cr, style = Stroke(1.dp.toPx()))
                }
            }
            .pointerInput(Unit) {
                val slop = 12.dp.toPx()
                val step = 44.dp.toPx()
                val swipe = 56.dp.toPx()
                val cursorStep = 14.dp.toPx()
                val wordStep = 36.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    val k = cKey
                    val act = cActions
                    pressed = true
                    if (cSettings.pressAnimation <= 0f) scope.launch { anim.snapTo(1f) }
                    else scope.launch { anim.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 1800f)) }
                    act.keyDown(k)
                    try {
                        when (k.type) {
                            KeyType.BACKSPACE -> {
                                act.commitKey(k, "")
                                var deadline = SystemClock.uptimeMillis() + 420L
                                var wordMode = false
                                var lastX = down.position.x
                                while (true) {
                                    val left = deadline - SystemClock.uptimeMillis()
                                    val ev = if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                    if (ev == null) {
                                        if (!wordMode) {
                                            act.keyTick()
                                            act.commitKey(k, "")
                                        }
                                        deadline = SystemClock.uptimeMillis() + 55L
                                        continue
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == id } ?: break
                                    if (!ch.pressed) break
                                    if (!wordMode && cSettings.backspaceSwipe && down.position.x - ch.position.x > slop * 2) {
                                        wordMode = true
                                        lastX = ch.position.x
                                        act.keyTick()
                                        act.commitKey(k, DELETE_WORD)
                                    }
                                    if (wordMode) {
                                        while (lastX - ch.position.x > wordStep) {
                                            lastX -= wordStep
                                            act.keyTick()
                                            act.commitKey(k, DELETE_WORD)
                                        }
                                        deadline = Long.MAX_VALUE
                                    }
                                }
                            }
                            KeyType.SPACE -> {
                                val mode = cSettings.spaceSwipe
                                var swiped = false
                                var picker = false
                                var lastX = down.position.x
                                // удержание пробела без движения — выбор клавиатуры
                                val lpAt = if (cSettings.spaceLongPress) SystemClock.uptimeMillis() + 550L else Long.MAX_VALUE
                                while (true) {
                                    val waiting = !swiped && !picker && lpAt != Long.MAX_VALUE
                                    val ev = if (waiting) {
                                        val left = lpAt - SystemClock.uptimeMillis()
                                        if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                    } else awaitPointerEvent()
                                    if (ev == null) {
                                        picker = true
                                        act.switchKeyboard()
                                        continue
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == id } ?: break
                                    if (!ch.pressed) {
                                        if (!swiped && !picker) act.commitKey(k, " ")
                                        break
                                    }
                                    if (picker) continue
                                    val dx = ch.position.x - down.position.x
                                    when (mode) {
                                        "LANGUAGE" -> if (!swiped && abs(dx) > swipe) {
                                            swiped = true
                                            act.swipeSpace(if (dx > 0) 1 else -1)
                                        }
                                        "CURSOR" -> {
                                            if (!swiped && abs(dx) > slop) {
                                                swiped = true
                                                lastX = ch.position.x
                                            }
                                            if (swiped) {
                                                while (ch.position.x - lastX > cursorStep) {
                                                    lastX += cursorStep
                                                    act.swipeSpace(1)
                                                }
                                                while (lastX - ch.position.x > cursorStep) {
                                                    lastX -= cursorStep
                                                    act.swipeSpace(-1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                val vars = cVariants
                                val lbl = cLabel
                                val out = cOutput
                                val letterKey = k.type == KeyType.CHARACTER
                                val glideOn = letterKey && cGlide && lbl.length == 1 && lbl[0].isLetter()
                                // мгновенный ввод: буква/цифра уходит в поле в момент касания
                                val instant = letterKey && cSettings.instantInput && out.length in 1..2 && out.all { it.isLetterOrDigit() }
                                val upSymbol = if (letterKey && cSettings.swipeUpHint) cUpHint else null
                                val a0 = anchor()
                                if (letterKey && cSettings.keyPreview && a0 != null) {
                                    popup.show(PopupState(holder, a0.x, a0.y, lbl, vars, 0, false))
                                }
                                // точка касания внутри клавиши (-0.5…0.5) — автоисправление учитывает, куда попал палец
                                val touchX = down.position.x / size.width - 0.5f
                                val touchY = down.position.y / size.height - 0.5f
                                if (instant) {
                                    act.keyTouch(touchX, touchY)
                                    act.commitKey(k, out)
                                }
                                val glideStart = max(slop * 1.5f, size.width * 0.6f)
                                val upDist = size.height * 0.45f
                                val holdAt = if (vars.size > 1) SystemClock.uptimeMillis() + cSettings.longPressMs.toLong() else Long.MAX_VALUE
                                var gesture = KeyGesture.CANCELED
                                var last = down.position
                                while (true) {
                                    val ev = if (holdAt == Long.MAX_VALUE) awaitPointerEvent() else {
                                        val left = holdAt - SystemClock.uptimeMillis()
                                        if (left > 0) withTimeoutOrNull(left) { awaitPointerEvent() } else null
                                    }
                                    if (ev == null) {
                                        gesture = KeyGesture.HOLD
                                        break
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == id } ?: break
                                    val d = ch.position - down.position
                                    last = ch.position
                                    if (!ch.pressed) {
                                        gesture = if (upSymbol != null && d.y < -upDist && abs(d.x) < abs(d.y)) KeyGesture.SWIPE_UP else KeyGesture.RELEASED
                                        break
                                    }
                                    if (glideOn && d.getDistance() > glideStart) {
                                        gesture = KeyGesture.GLIDE
                                        break
                                    }
                                    if (!glideOn && vars.size > 1 && abs(d.x) > slop && abs(d.x) > -d.y) {
                                        gesture = KeyGesture.SLIDE
                                        break
                                    }
                                }
                                when (gesture) {
                                    KeyGesture.RELEASED -> if (!instant) {
                                        act.keyTouch(touchX, touchY)
                                        act.commitKey(k, out)
                                    }
                                    KeyGesture.CANCELED -> Unit
                                    KeyGesture.SWIPE_UP -> {
                                        val sym = upSymbol ?: out
                                        act.keyTick()
                                        if (instant) act.replaceLast(out, sym) else act.commitKey(k, sym)
                                    }
                                    KeyGesture.HOLD, KeyGesture.SLIDE -> {
                                        val def = vars.size / 2
                                        var sel = def
                                        val a = anchor() ?: a0
                                        if (a != null) popup.show(PopupState(holder, a.x, a.y, lbl, vars, sel, true))
                                        act.keyTick()
                                        while (true) {
                                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                            if (!ch.pressed) {
                                                val chosen = vars.getOrElse(sel) { out }
                                                if (instant) {
                                                    if (chosen != out) act.replaceLast(out, chosen)
                                                } else {
                                                    act.commitKey(if (k.type == KeyType.CHARACTER || k.type == KeyType.GLOBE) k else VARIANT_KEY, chosen)
                                                }
                                                break
                                            }
                                            val idx = (def + ((ch.position.x - down.position.x) / step).roundToInt())
                                                .coerceIn(0, vars.lastIndex)
                                            if (idx != sel) {
                                                sel = idx
                                                popup.select(holder, idx)
                                                act.keyTick()
                                            }
                                        }
                                    }
                                    KeyGesture.GLIDE -> {
                                        popup.clear(holder)
                                        if (instant) act.replaceLast(out, "")
                                        val o = origin() ?: Offset.Zero
                                        glide.trail.clear()
                                        glide.trail.add(o + down.position)
                                        glide.trail.add(o + last)
                                        act.keyTick()
                                        var released = false
                                        while (true) {
                                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                            glide.trail.add(o + ch.position)
                                            if (!ch.pressed) {
                                                released = true
                                                break
                                            }
                                            ch.consume()
                                        }
                                        val pts = glide.trail.toList()
                                        glide.trail.clear()
                                        if (released && pts.size >= 2) {
                                            val net = pts.last() - pts.first()
                                            var len = 0f
                                            for (i in 1 until pts.size) len += (pts[i] - pts[i - 1]).getDistance()
                                            if (upSymbol != null && net.y < -upDist && abs(net.x) < abs(net.y) * 0.7f && len < size.width * 2.2f) {
                                                act.commitKey(k, upSymbol)
                                            } else {
                                                val snap = layer.coords?.let { glide.snapshot(it) }
                                                if (snap != null) act.commitGlide(pts, snap.first, snap.second)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        pressed = false
                        popup.clear(holder)
                        if (k.type == KeyType.CHARACTER) glide.trail.clear()
                        act.keyUp()
                        if (cSettings.pressAnimation <= 0f) scope.launch { anim.snapTo(0f) }
                        else if (cEffect == PressEffect.JELLY) scope.launch { anim.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 520f)) }
                        else scope.launch { anim.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val st = style
        val fg = if (pressed) st?.pressedText ?: st?.text ?: colors.activeFg else st?.text ?: colors.fg
        val scale = st?.fontScale ?: 1f
        val custom = st?.label
        val textStyle = remember(st) {
            TextStyle(
                fontStyle = if (st?.italic == true) FontStyle.Italic else null,
                shadow = st?.textGlow?.let { Shadow(it, Offset.Zero, 18f) }
            )
        }
        val w = st?.fontWeight?.let { FontWeight(it) } ?: weight
        when {
            custom != null && key.type != KeyType.CHARACTER -> Text(
                custom, color = fg, fontFamily = font, fontWeight = w, fontSize = (16f * scale).sp, maxLines = 1,
                softWrap = false, style = textStyle
            )
            key.type == KeyType.SHIFT -> if (shift == ShiftState.CAPS_LOCK) {
                SlotIcon("caps", fg, (22f * scale).dp) { GlyphIcon(Glyph.CAPS, fg, (22f * scale).dp) }
            } else SlotIcon("shift", fg, (22f * scale).dp) { GlyphIcon(Glyph.SHIFT, fg, (22f * scale).dp) }
            key.type == KeyType.BACKSPACE -> SlotIcon("backspace", fg, (22f * scale).dp) { GlyphIcon(Glyph.BACKSPACE, fg, (22f * scale).dp) }
            key.type == KeyType.GLOBE -> SlotIcon("globe", fg, (24f * scale).dp) { GlyphIcon(Glyph.GLOBE, fg, (24f * scale).dp) }
            key.type == KeyType.STICKERS -> SlotIcon("emoji_key", fg, (26f * scale).dp) { GlyphIcon(Glyph.EMOJI, fg, (26f * scale).dp) }
            key.type == KeyType.ENTER -> when (enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> SlotIcon("search", fg, (26f * scale).dp) { Icon(Icons.Rounded.Search, null, tint = fg, modifier = Modifier.size((26f * scale).dp)) }
                EditorInfo.IME_ACTION_SEND -> SlotIcon("send", fg, (24f * scale).dp) { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = fg, modifier = Modifier.size((24f * scale).dp)) }
                EditorInfo.IME_ACTION_DONE -> SlotIcon("done", fg, (28f * scale).dp) { Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size((28f * scale).dp)) }
                ENTER_NEWLINE -> SlotIcon("enter", fg, (24f * scale).dp) { GlyphIcon(Glyph.RETURN, fg, (24f * scale).dp) }
                else -> SlotIcon("go", fg, (24f * scale).dp) { GlyphIcon(Glyph.ARROW, fg, (24f * scale).dp) }
            }
            key.type == KeyType.SPACE -> Text(
                label, color = fg.copy(alpha = 0.72f), fontSize = (13f * scale).sp, fontWeight = w,
                fontFamily = font, maxLines = 1, style = textStyle
            )
            key.type == KeyType.SPACER -> Unit
            else -> Text(
                text = if (custom != null && !upper) custom else if (custom != null) custom.uppercase() else label,
                color = fg,
                fontFamily = font,
                fontWeight = w,
                fontSize = when {
                    key.type == KeyType.MODE_SYMBOLS || key.type == KeyType.SYMBOLS_PAGE -> (15f * scale).sp
                    numberRow -> (17f * settings.keyLabelScale * scale).sp
                    else -> ((settings.keyHeightDp * 0.4f).coerceIn(18f, 25f) * settings.keyLabelScale * scale).sp
                },
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = textStyle
            )
        }
        if (hint != null) {
            Text(
                hint,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 5.dp),
                color = fg.copy(alpha = 0.5f),
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                maxLines = 1
            )
        }
    }
}

/** Слой всплывающих «печенек»: один Canvas, без shadow/border, не перехватывает касания. */
@Composable
internal fun BoxScope.PopupLayer(popup: PopupController, palette: KeyPalette, font: FontFamily, spec: WavySpec) {
    val s = popup.state ?: return
    key(s.owner) { PopupBubbles(s, palette, font, spec) }
}

@Composable
internal fun BoxScope.PopupBubbles(s: PopupState, palette: KeyPalette, font: FontFamily, spec: WavySpec) {
    val measurer = rememberTextMeasurer()
    val skinPopup = LocalSkin.current?.popup
    val glass = skinPopup?.material == SkinMaterial.GLASS
    val baseFill = skinPopup?.fill?.primary ?: if (glass) Color.White.copy(alpha = 0.2f) else palette.accentSoft
    val selFill = skinPopup?.pressedFill?.primary ?: palette.accent
    val baseText = skinPopup?.text ?: palette.onAccentSoft
    val selText = skinPopup?.pressedText ?: palette.onAccent
    val style = remember(font) { TextStyle(fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp) }
    val items = if (s.expanded) s.variants else listOf(s.primary)
    val layouts = remember(items, style) { items.map { measurer.measure(AnnotatedString(it), style = style) } }
    val selPos by animateFloatAsState(
        if (s.expanded) s.selected.toFloat() else 0f,
        spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "popupSel"
    )
    val appear = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 800f)) }
    val path = remember { Path() }
    Canvas(Modifier.matchParentSize()) {
        val n = layouts.size
        val slot = 50.dp.toPx()
        val baseR = 22.dp.toPx()
        val selR = 29.dp.toPx()
        val ring = 2.5.dp.toPx()
        val margin = 4.dp.toPx()
        val width = if (n == 1) selR * 2f else n * slot
        val left = (s.anchorX - width / 2f).coerceIn(margin, max(margin, size.width - width - margin))
        val baseCy = max(s.anchorTop - 4.dp.toPx() - selR, selR + margin)
        val emph = { i: Int -> if (n == 1) 1f else (1f - abs(i - selPos)).coerceIn(0f, 1f) }
        val sc = appear.value
        for (i in (0 until n).sortedBy { emph(it) }) {
            val e = emph(i)
            val r = lerpF(baseR, selR, e) * sc
            val cx = if (n == 1) left + width / 2f else left + slot * (i + 0.5f)
            val cy = baseCy - e * 4.dp.toPx()
            buildWavyPath(spec, cx, cy, r, path)
            if (!glass) drawPath(path, palette.background, style = Stroke(ring * 2f))
            drawPath(path, lerp(baseFill, selFill, e))
            if (glass) drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(1.2.dp.toPx()))
            val lay = layouts[i]
            scale(scale = r / selR, pivot = Offset(cx, cy)) {
                drawText(
                    lay,
                    color = lerp(baseText, selText, e),
                    topLeft = Offset(cx - lay.size.width / 2f, cy - lay.size.height / 2f)
                )
            }
        }
    }
}

/** Фон чипа подсказок/панели инструментов: обычный цвет или стиль "chips" из оформления. */
internal fun Modifier.chipBackground(style: KeyStyle?, fallback: Color, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val clipped = this.clip(shape)
    if (style == null) return clipped.background(fallback)
    return clipped.drawBehind {
        val rect = Rect(Offset.Zero, size)
        when {
            style.material == SkinMaterial.GLASS -> drawGlassChip(rect, min(size.height / 2f, 22.dp.toPx()), style.glass?.tint ?: style.fill?.primary)
            style.fill != null -> drawRect(style.fill.brush(rect))
            else -> drawRect(fallback)
        }
        val bw = style.borderWidth ?: 0f
        if (bw > 0f && style.border != null) {
            val r = min(size.height / 2f, 22.dp.toPx())
            drawRoundRect(style.border.brush(rect), cornerRadius = CornerRadius(r, r), style = Stroke(bw.dp.toPx()))
        }
    }
}

@Composable
internal fun CookieButton(
    spec: WavySpec,
    color: Color,
    size: Dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "cookieScale")
    val rot by animateFloatAsState(if (pressed) 25f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cookieRot")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .wavyBackground(spec, color) { rot }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
internal fun HeaderBar(
    state: KeyboardState,
    actions: KeyboardActions,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    spec: WavySpec
) {
    val panel = state.panel
    val candidates = state.candidates
    val showCandidates = candidates.isNotEmpty() && (panel == ActivePanel.KEYBOARD)
    val tools = !showCandidates && (state.toolbarOpen || panel != ActivePanel.KEYBOARD || !settings.showSuggestions)
    val button = if (settings.landscape) 38.dp else 46.dp
    Row(
        modifier = Modifier.fillMaxWidth().height(if (settings.landscape) 40.dp else 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CookieButton(spec, palette.accent, button, onClick = { actions.headerMenu() }) {
            if (panel != ActivePanel.KEYBOARD) {
                SlotIcon("back", palette.onAccent, 22.dp) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = palette.onAccent, modifier = Modifier.size(22.dp)) }
            } else {
                SlotIcon("menu", palette.onAccent, 22.dp) { GlyphIcon(Glyph.GRID, palette.onAccent, 22.dp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (settings.panelAnimations) {
                AnimatedContent(
                    targetState = tools,
                    transitionSpec = {
                        (fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 700f), initialScale = 0.9f)) togetherWith
                            fadeOut(tween(90))
                    },
                    label = "headerSwap"
                ) { showTools ->
                    when {
                        showTools -> ToolbarPill(state, actions, settings, palette)
                        showCandidates -> CandidateStrip(candidates, actions, palette, font)
                        else -> SuggestionStrip(state, actions, palette, font)
                    }
                }
            } else {
                when {
                    tools -> ToolbarPill(state, actions, settings, palette)
                    showCandidates -> CandidateStrip(candidates, actions, palette, font)
                    else -> SuggestionStrip(state, actions, palette, font)
                }
            }
        }
        if (settings.showMicButton) {
            CookieButton(spec, palette.accent, button, onClick = { actions.togglePanel(ActivePanel.VOICE) }) {
                SlotIcon("mic", palette.onAccent, 22.dp) { Icon(Icons.Outlined.Mic, null, tint = palette.onAccent, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

@Composable
internal fun SuggestionStrip(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val chips = state.suggestions
    val chipStyle = LocalSkin.current?.chips
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val chip = chips.getOrNull(i) ?: SuggestionChipModel.EMPTY
            val primary = i == 1
            val fg = chipStyle?.let { if (primary) it.pressedText ?: it.text else it.text } ?: if (primary) palette.onAccentSoft else palette.onFunc
            val interaction = remember { MutableInteractionSource() }
            val down by interaction.collectIsPressedAsState()
            val corner by animateDpAsState(if (down) 10.dp else 20.dp, spring(dampingRatio = 0.6f, stiffness = 800f), label = "chipCorner")
            val chipFill = chipStyle?.let { if (primary && it.pressedFill != null) it.copy(fill = it.pressedFill) else it }
            Box(
                modifier = Modifier
                    .weight(if (primary) 1.15f else 1f)
                    .height(40.dp)
                    .chipBackground(chipFill, if (primary) palette.accentSoft else palette.func, RoundedCornerShape(corner))
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = !chip.isEmpty,
                        // долгое нажатие — забыть выученное слово
                        onLongClick = { actions.forgetSuggestion(chip.commitWord) }
                    ) { actions.pickSuggestion(chip) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (chip.isEmpty) "…" else chip.displayText,
                    color = if (chip.isEmpty) fg.copy(alpha = 0.5f) else fg,
                    fontFamily = font,
                    fontSize = 15.sp,
                    fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Варианты китайского и японского ввода: прокручиваемая лента, первый вариант — главный (его вставит пробел). */
@Composable
internal fun CandidateStrip(candidates: List<String>, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val listState = rememberLazyListState()
    LaunchedEffect(candidates) { listState.scrollToItem(0) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(palette.func),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        itemsIndexed(candidates) { i, c ->
            val first = i == 0
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (first) palette.accentSoft else Color.Transparent)
                    .combinedClickable(onLongClick = { actions.forgetSuggestion(c) }) { actions.pickCandidate(i) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    c, color = if (first) palette.onAccentSoft else palette.onFunc, fontFamily = font,
                    fontSize = 18.sp, fontWeight = if (first) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

/** Кнопка панели инструментов: встроенная, закреплённое действие плагина или кнопка скрипта. */
@Immutable
data class ToolDef(val id: String, val title: String, val slot: String?, val icon: ImageVector?, val spec: String?)

object Toolbar {
    /** Все кнопки в порядке по умолчанию. Читает состояние плагинов — вызывать из композиции или главного потока. */
    fun catalog(): List<ToolDef> = buildList {
        add(ToolDef("plugins", "Плагины", "plugins", Icons.Rounded.Extension, null))
        for (p in PluginRepository.active) {
            p.actions.forEachIndexed { i, a ->
                if (a.pinned) add(ToolDef("pin:${p.id}#$i", a.title, if (a.icon == "@layout_swap") "layout_swap" else null, null, a.icon))
            }
        }
        for (b in ScriptHost.buttons) if (b.toolbar) add(ToolDef("btn:${b.pluginId}:${b.id}", b.title, null, null, b.icon))
        add(ToolDef("translate", "Переводчик", "translate", Icons.Rounded.Translate, null))
        add(ToolDef("gif", "GIF", "gif", Icons.Rounded.GifBox, null))
        add(ToolDef("clipboard", "Буфер обмена", "clipboard", Icons.Rounded.ContentPaste, null))
        add(ToolDef("emoji", "Эмодзи", "emoji", Icons.Rounded.EmojiEmotions, null))
        add(ToolDef("one_handed", "Одна рука", "one_handed", Icons.Rounded.SwapHoriz, null))
        add(ToolDef("incognito", "Инкогнито", "incognito", Icons.Rounded.VisibilityOff, null))
        add(ToolDef("settings", "Настройки", "settings", Icons.Rounded.Settings, null))
        add(ToolDef("hide", "Скрыть клавиатуру", "hide", Icons.Rounded.KeyboardHide, null))
    }

    /** Сохранённый порядок; новые кнопки встают рядом со своими соседями по умолчанию. */
    fun arrange(available: List<String>, order: List<String>, hidden: Set<String>, includeHidden: Boolean = false): List<String> {
        val result = ArrayList<String>()
        for (id in order) if (id in available && id !in result) result += id
        available.forEachIndexed { i, id ->
            if (id in result) return@forEachIndexed
            val prev = available.subList(0, i).lastOrNull { it in result }
            result.add(if (prev == null) 0 else result.indexOf(prev) + 1, id)
        }
        return if (includeHidden) result else result.filter { it !in hidden }
    }

    /** Видимые кнопки в новом порядке; скрытые остаются на своих местах в конце списка. */
    fun save(context: Context, visible: List<String>, hidden: Set<String>) {
        val all = visible + catalog().map { it.id }.filter { it in hidden && it !in visible }
        KeyboardPrefs.edit(context) { putString("toolbar_order", all.joinToString(",")) }
    }

    fun setHidden(context: Context, hidden: Set<String>) =
        KeyboardPrefs.edit(context) { putString("toolbar_hidden", hidden.joinToString(",")) }
}

/** Долгое нажатие на кнопку и перетаскивание меняет порядок; порядок также настраивается в настройках. */
@Composable
internal fun ToolbarPill(state: KeyboardState, actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette) {
    val context = LocalContext.current
    val chipStyle = LocalSkin.current?.chips
    val fg = chipStyle?.text ?: palette.onFunc
    val catalog = Toolbar.catalog()
    val byId = remember(catalog) { catalog.associateBy { it.id } }
    val ids = remember(catalog, settings.toolbarOrder, settings.toolbarHidden) {
        Toolbar.arrange(catalog.map { it.id }, settings.toolbarOrderList, settings.toolbarHiddenSet)
    }
    var order by remember(ids) { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    // отпускание после долгого нажатия/перетаскивания не считается нажатием
    var dragEndedAt by remember { mutableLongStateOf(0L) }
    val step = with(LocalDensity.current) { 42.dp.toPx() }
    val hidden = settings.toolbarHiddenSet

    fun click(id: String) {
        if (dragging != null || SystemClock.uptimeMillis() - dragEndedAt < 400L) return
        when {
            id == "plugins" -> actions.togglePanel(ActivePanel.PLUGINS)
            id == "translate" -> actions.togglePanel(ActivePanel.TRANSLATE)
            id == "gif" -> actions.togglePanel(ActivePanel.GIF)
            id == "clipboard" -> actions.togglePanel(ActivePanel.CLIPBOARD)
            id == "emoji" -> actions.togglePanel(ActivePanel.EMOJI)
            id == "one_handed" -> KeyboardPrefs.edit(context) { putString("one_handed", if (settings.oneHanded == "OFF") "RIGHT" else "OFF") }
            id == "incognito" -> {
                KeyboardPrefs.edit(context) { putBoolean("incognito", !settings.incognito) }
                Toast.makeText(context, tr(if (settings.incognito) "Инкогнито выключено" else "Инкогнито: слова и буфер не запоминаются"), Toast.LENGTH_SHORT).show()
            }
            id == "settings" -> actions.openSettings()
            id == "hide" -> actions.hideKeyboard()
            id.startsWith("pin:") -> {
                val rest = id.removePrefix("pin:")
                actions.runPluginAction(rest.substringBeforeLast('#'), rest.substringAfterLast('#').toIntOrNull() ?: return)
            }
            id.startsWith("btn:") -> {
                val rest = id.removePrefix("btn:")
                actions.runScriptButton(rest.substringBefore(':'), rest.substringAfter(':'))
            }
        }
    }

    // жест — на всём ряду: координаты ряда не меняются, когда кнопки меняются местами
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).chipBackground(chipStyle, palette.func, CircleShape)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)
            .pointerInput(ids) {
                detectDragGesturesAfterLongPress(
                    onDragStart = start@{ at ->
                        val i = (at.x / step).toInt()
                        dragging = order.getOrNull(i) ?: return@start
                        dragDx = 0f
                        actions.keyTick()
                    },
                    onDrag = drag@{ change, amount ->
                        val id = dragging ?: return@drag
                        change.consume()
                        dragDx += amount.x
                        val cur = order.indexOf(id)
                        if (dragDx > step / 2f && cur in 0 until order.lastIndex) {
                            order = order.toMutableList().also { java.util.Collections.swap(it, cur, cur + 1) }
                            dragDx -= step
                            actions.keyTick()
                        } else if (dragDx < -step / 2f && cur > 0) {
                            order = order.toMutableList().also { java.util.Collections.swap(it, cur, cur - 1) }
                            dragDx += step
                            actions.keyTick()
                        }
                    },
                    onDragEnd = {
                        if (dragging != null) Toolbar.save(context, order, hidden)
                        dragging = null
                        dragDx = 0f
                        dragEndedAt = SystemClock.uptimeMillis()
                    },
                    onDragCancel = {
                        dragging = null
                        dragDx = 0f
                        dragEndedAt = SystemClock.uptimeMillis()
                    }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (id in order) {
            val def = byId[id] ?: continue
            key(id) {
                val selected = when (id) {
                    "plugins" -> state.panel == ActivePanel.PLUGINS
                    "translate" -> state.panel == ActivePanel.TRANSLATE
                    "gif" -> state.panel == ActivePanel.GIF
                    "clipboard" -> state.panel == ActivePanel.CLIPBOARD
                    "emoji" -> state.panel == ActivePanel.EMOJI || state.panel == ActivePanel.EMOJI_SEARCH
                    "one_handed" -> settings.oneHanded != "OFF"
                    "incognito" -> settings.incognito
                    else -> false
                }
                val isDrag = dragging == id
                Box(
                    Modifier
                        .zIndex(if (isDrag) 1f else 0f)
                        .graphicsLayer {
                            if (isDrag) {
                                translationX = dragDx
                                scaleX = 1.18f
                                scaleY = 1.18f
                            }
                        }
                ) {
                    ToolButton(def, selected || isDrag, palette, fg) { click(id) }
                }
            }
        }
    }
}

@Composable
internal fun ToolButton(def: ToolDef, selected: Boolean, palette: KeyPalette, tint: Color, onClick: () -> Unit) {
    val corner by animateDpAsState(if (selected) 12.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "toolCorner")
    val color = if (selected) palette.onAccent else tint
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 36.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (selected) palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val slot = def.slot
        when {
            slot != null && def.icon != null -> SlotIcon(slot, color, 20.dp) { Icon(def.icon, def.title, tint = color, modifier = Modifier.size(20.dp)) }
            def.spec != null -> SpecIcon(def.spec, color, 20.dp)
            def.icon != null -> Icon(def.icon, def.title, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector, selected: Boolean, palette: KeyPalette, tint: Color = palette.onFunc, onClick: () -> Unit) {
    val corner by animateDpAsState(if (selected) 12.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "toolCorner")
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 36.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (selected) palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (selected) palette.onAccent else tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun ToolText(text: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 40.dp, height = 36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tint, fontSize = if (text.length > 2) 11.sp else 17.sp, maxLines = 1, fontWeight = FontWeight.Bold)
    }
}

/** onBackground(цвет, прозрачная навигация) — фон под системной панелью навигации. */
@Composable
fun KeyboardRoot(state: KeyboardState, actions: KeyboardActions, voice: VoiceInput, onBackground: (Color, Boolean) -> Unit) {
    val raw by KeyboardPrefs.observe()
    val skin = PluginRepository.activeSkin(raw)
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val settings = remember(raw, skin, landscape, configuration.screenHeightDp) {
        val s = skin?.applyColors(raw) ?: raw
        if (landscape) s.forLandscape(configuration.screenHeightDp) else s
    }
    ExpressiveTheme(settings) {
        val palette = rememberKeyPalette(settings)
        val baseFont = LocalKeyFont.current
        val font = remember(baseFont, skin) { skin?.fontFamily(baseFont) ?: baseFont }
        val image = if (settings.hasBackgroundImage) rememberBackgroundBitmap(settings.bgImageVersion) else null
        val density = LocalDensity.current
        val backdrop = remember { Backdrop() }
        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        val decorated = image != null || skin?.hasBackground == true
        LaunchedEffect(palette.background, decorated) { onBackground(palette.background, decorated) }
        // подложку для стекла считаем в фоне и только когда оформление стеклянное
        LaunchedEffect(skin, image, palette.background, settings.bgDim, rootSize) {
            if (skin == null || !skin.usesGlass || rootSize.width == 0 || rootSize.height == 0) {
                backdrop.bitmap = null
                return@LaunchedEffect
            }
            val (bmp, s) = withContext(Dispatchers.Default) {
                renderBackdrop(rootSize.width, rootSize.height, density, skin, palette.background, image, if (image != null) settings.bgDim else 0f)
            }
            backdrop.scale = s
            backdrop.bitmap = bmp
        }
        CompositionLocalProvider(LocalSkin provides skin, LocalBackdrop provides backdrop) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = (skin?.corner ?: 28f).dp, topEnd = (skin?.corner ?: 28f).dp),
                color = palette.background
            ) {
                Box(
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        backdrop.coords = it
                        if (it.size != rootSize) rootSize = it.size
                    }
                ) {
                    if (skin != null && skin.hasBackground) {
                        Canvas(Modifier.matchParentSize()) { drawSkinBackground(skin, size) }
                    }
                    if (image != null) {
                        val blur = settings.bgBlurDp
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                                .then(if (blur > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur.dp) else Modifier)
                        )
                        if (settings.bgDim > 0f) Box(Modifier.matchParentSize().background(palette.background.copy(alpha = settings.bgDim)))
                    }
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = settings.bottomOffsetDp.dp)) {
                        KeyboardScreen(state, actions, voice, settings, palette, font)
                    }
                }
            }
        }
    }
}

@Composable
internal fun KeyboardScreen(
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily
) {
    val popup = remember { PopupController() }
    val glide = remember { GlideController() }
    val layer = remember { CoordsHolder() }
    val spec = remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent) { settings.accentSpec() }
    val panel = state.panel
    val numbers = settings.showNumberRow && state.symbolsPage == 0
    val bodyHeight = (settings.keyHeightDp * (if (numbers) 4.78f else 4f) + settings.rowSpacingDp * (if (numbers) 4f else 3f)).dp
    val trailColor = LocalSkin.current?.trail ?: palette.accent.copy(alpha = 0.85f)
    val density = LocalDensity.current
    SideEffect {
        KeyboardGeometry.layer = layer
        KeyboardGeometry.density = density
        KeyboardGeometry.actions = actions
        KeyboardGeometry.palette = palette
    }
    val gap = if (settings.landscape) 4.dp else 8.dp

    Box(Modifier.fillMaxWidth().onGloballyPositioned { layer.coords = it }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = settings.sidePaddingDp.dp, vertical = gap),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            HeaderBar(state, actions, settings, palette, font, spec)
            if (panel == ActivePanel.TRANSLATE) TranslatorBar(state, actions, settings, palette, font)
            if (panel == ActivePanel.EMOJI_SEARCH) EmojiSearchBar(state, actions, settings, palette, font)
            Box(Modifier.fillMaxWidth().height(bodyHeight)) {
                val body = if (panel == ActivePanel.TRANSLATE || panel == ActivePanel.EMOJI_SEARCH) ActivePanel.KEYBOARD else panel
                if (settings.panelAnimations) {
                    AnimatedContent(
                        targetState = body,
                        transitionSpec = {
                            (fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = 500f), initialScale = 0.94f)) togetherWith
                                fadeOut(tween(100))
                        },
                        label = "body"
                    ) { target -> PanelBody(target, state, actions, voice, settings, palette, font, spec, popup, layer, glide) }
                } else {
                    PanelBody(body, state, actions, voice, settings, palette, font, spec, popup, layer, glide)
                }
            }
        }
        GlideTrailLayer(glide, trailColor, settings.glideTrail)
        ScriptOverlayLayer()
        PopupLayer(popup, palette, font, spec)
        ScriptFrameTicker()
    }
}

@Composable
internal fun PanelBody(
    target: ActivePanel,
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    spec: WavySpec,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController
) {
    when (target) {
        ActivePanel.EMOJI -> EmojiPanel(actions, settings, palette, font)
        ActivePanel.GIF -> GifPanel(actions, settings, palette, font)
        ActivePanel.CLIPBOARD -> ClipboardPanel(state, actions, palette, font)
        ActivePanel.VOICE -> VoicePanel(state, actions, voice, palette, font, spec)
        ActivePanel.PLUGINS -> PluginsPanel(state, actions, palette, font)
        else -> KeyArea(state, actions, settings, palette, font, popup, layer, glide)
    }
}

/** Режим одной руки: клавиатура сжимается к краю, на свободном месте — кнопки «перенести» и «развернуть». */
@Composable
internal fun KeyArea(
    state: KeyboardState,
    actions: KeyboardActions,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController
) {
    val mode = settings.oneHanded
    if (mode != "LEFT" && mode != "RIGHT") {
        KeyGrid(state, actions, settings, palette, font, popup, layer, glide)
        return
    }
    val context = LocalContext.current
    val w = settings.oneHandedWidth
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode == "RIGHT") OneHandRail(Modifier.weight(1f - w), palette, mode, context)
        Box(Modifier.weight(w).fillMaxHeight()) { KeyGrid(state, actions, settings, palette, font, popup, layer, glide) }
        if (mode == "LEFT") OneHandRail(Modifier.weight(1f - w), palette, mode, context)
    }
}

@Composable
internal fun OneHandRail(modifier: Modifier, palette: KeyPalette, mode: String, context: Context) {
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(palette.func)
                .clickable { KeyboardPrefs.edit(context) { putString("one_handed", if (mode == "LEFT") "RIGHT" else "LEFT") } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.SwapHoriz, "Перенести", tint = palette.onFunc) }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(palette.accentSoft)
                .clickable { KeyboardPrefs.edit(context) { putString("one_handed", "OFF") } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.OpenInFull, "Развернуть", tint = palette.onAccentSoft) }
    }
}

@Composable
internal fun KeyGrid(
    state: KeyboardState,
    actions: KeyboardActions,
    settings: KeyboardSettings,
    palette: KeyPalette,
    font: FontFamily,
    popup: PopupController,
    layer: CoordsHolder,
    glide: GlideController
) {
    val lang = state.language
    val page = state.symbolsPage
    val shift = state.shift
    val enter = state.enterAction
    val pluginLayouts = PluginRepository.layouts
    val scriptLayouts = ScriptHost.layoutOverrides
    val langLabels = remember(settings.languages, Languages.extra) { Languages.enabled(settings).map { it.label } }
    val rows = remember(
        lang, page, settings.showNumberRow, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
        settings.spaceLeftKey, settings.spaceRightKey, langLabels, pluginLayouts, scriptLayouts
    ) {
        KeyboardLayouts.rows(
            lang, page, settings.showNumberRow, settings.showGlobeKey, settings.showEmojiKey, settings.punctuationKeys,
            langLabels, settings.spaceLeftKey, settings.spaceRightKey
        )
    }
    val upper = shift != ShiftState.OFF && page == 0
    val glideEnabled = settings.glideTyping && state.glideAllowed && page == 0 && lang.composer == Composer.NONE
    val dense = page == 0 && rows.any { r -> r.keys.count { it.type == KeyType.CHARACTER } >= 11 }
    val hSpace = (if (dense) min(settings.keySpacingDp, 4f) else settings.keySpacingDp).dp

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(settings.rowSpacingDp.dp)) {
        rows.forEach { row ->
            val isNumberRow = row.heightWeight < 1f
            Row(Modifier.fillMaxWidth().weight(row.heightWeight), horizontalArrangement = Arrangement.spacedBy(hSpace)) {
                for (key in row.keys) {
                    if (key.type == KeyType.SPACER) {
                        Spacer(Modifier.weight(key.weight))
                        continue
                    }
                    val isChar = key.type == KeyType.CHARACTER
                    KeyView(
                        key = key,
                        label = when {
                            key.type == KeyType.SPACE && settings.incognito -> "🕶 ${key.label}"
                            key.type == KeyType.GLOBE -> lang.label
                            isChar -> key.shownLabel(upper)
                            else -> key.label
                        },
                        upper = upper && isChar,
                        shift = if (key.type == KeyType.SHIFT) shift else ShiftState.OFF,
                        enterAction = if (key.type == KeyType.ENTER) enter else 0,
                        settings = settings,
                        palette = palette,
                        font = font,
                        popup = popup,
                        layer = layer,
                        glide = glide,
                        actions = actions,
                        glideEnabled = glideEnabled,
                        numberRow = isNumberRow,
                        modifier = Modifier.weight(key.weight).fillMaxHeight()
                    )
                }
            }
        }
    }
}
