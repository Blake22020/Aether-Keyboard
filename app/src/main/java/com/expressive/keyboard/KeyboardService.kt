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
// 10. СОСТОЯНИЕ, ГОЛОС И СЕРВИС КЛАВИАТУРЫ
// ============================================================================

@Stable
class KeyboardState {
    var language by mutableStateOf(KeyboardLanguage.RU)
    var shift by mutableStateOf(ShiftState.OFF)
    var symbolsPage by mutableIntStateOf(0)
    var panel by mutableStateOf(ActivePanel.KEYBOARD)
    var toolbarOpen by mutableStateOf(false)
    var suggestions by mutableStateOf(SuggestionChipModel.EMPTY_ROW)
    var enterAction by mutableIntStateOf(EditorInfo.IME_ACTION_UNSPECIFIED)
    var translatorInput by mutableStateOf("")
    var translatorResult by mutableStateOf("")
    var translatorLoading by mutableStateOf(false)
    var pluginTab by mutableStateOf("")
    var pluginStatus by mutableStateOf("")
    /** Печать свайпом разрешена в этом поле (не пароль, не почта/адрес). */
    var glideAllowed by mutableStateOf(true)
    val clipboard = mutableStateListOf<String>()
    /** Закреплённые записи буфера: хранятся на диске и не стираются кнопкой «Очистить». */
    val pinnedClips = mutableStateListOf<String>()
}

@Stable
interface KeyboardActions {
    fun keyDown(key: KeyModel)
    fun keyUp()
    fun keyTick()
    fun commitKey(key: KeyModel, text: String)
    /** Где именно нажата следующая клавиша: смещение от её центра в долях ширины и высоты (-0.5…0.5). */
    fun keyTouch(fx: Float, fy: Float) {}
    fun swipeSpace(direction: Int)
    fun pickSuggestion(chip: SuggestionChipModel)
    fun commitText(text: String)
    fun commitEmoji(emoji: String)
    fun deleteBackward()
    fun commitGif(gif: TenorGifResult)
    fun headerMenu()
    fun togglePanel(panel: ActivePanel)
    fun toggleVoice()
    fun openSettings()
    fun openVoiceFallback()
    fun hideKeyboard()
    fun clearClipboard()
    fun insertTranslation()
    fun commitSymbol(text: String)
    fun runPluginAction(pluginId: String, index: Int)
    /** Мгновенный ввод: буква уже вставлена при касании — заменить её вариантом (или убрать, если new пустой). */
    fun replaceLast(old: String, new: String)
    fun commitGlide(path: List<Offset>, keys: Map<Char, Offset>, keyWidth: Float)
    fun switchKeyboard()
    fun runScriptButton(pluginId: String, id: String)
    fun removeClip(text: String)
    fun togglePinClip(text: String)
    /** Долгое нажатие на подсказку: забыть выученное слово. */
    fun forgetSuggestion(word: String)
}

@Stable
class VoiceInput(private val context: Context, private val onResult: (String) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("Нажмите на микрофон и говорите")
        private set
    var partial by mutableStateOf("")
        private set
    var rms by mutableFloatStateOf(0f)
        private set
    private var recognizer: SpeechRecognizer? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            status = "Говорите…"
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            rms = rmsdB
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            listening = false
            rms = 0f
            status = "Распознаю…"
        }
        override fun onError(error: Int) {
            listening = false
            rms = 0f
            status = "Не расслышал. Нажмите на микрофон ещё раз"
        }
        override fun onResults(results: Bundle?) {
            listening = false
            rms = 0f
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (best.isNotEmpty()) {
                partial = ""
                status = "Вставлено: $best"
                onResult(best)
            } else status = "Речь не распознана"
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (p.isNotEmpty()) partial = p
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start(language: KeyboardLanguage) {
        if (!hasPermission()) {
            status = "Нужен доступ к микрофону"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "Распознавание речи недоступно на устройстве"
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        partial = ""
        listening = true
        status = "Слушаю (${language.label})…"
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            status = "Не удалось запустить распознавание"
        }
    }

    fun stop() {
        runCatching { recognizer?.cancel() }
        listening = false
        rms = 0f
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }
}

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '«' to '»')

internal data class FieldFlags(
    val isPassword: Boolean = false,
    val suggestions: Boolean = true,
    val autoCorrect: Boolean = true,
    val autoCap: Boolean = false
)

class ComposeKeyboardService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, KeyboardActions, ScriptBridge {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val kb = KeyboardState()
    private lateinit var voice: VoiceInput
    private var clipboardManager: ClipboardManager? = null
    private var inputView: View? = null
    private var navColor: Int? = null
    private var navLight = false

    // --- отслеживание текста (без синхронных IPC-запросов к полю ввода) ---
    private var composing = ""
    private var composingStart = -1
    private var expSelStart = -1
    private var expSelEnd = -1
    private var lastCommittedWord = ""
    private var lastTypedChar: Char? = null
    private var undo: UndoAutoCorrectState? = null
    private var autoSpace = false
    private var lastActionWasSpace = false
    private var charBeforeSpace: Char? = null
    private var charBeforeAutoSpace: Char? = null
    private var lastSpaceTime = 0L
    private var lastShiftTime = 0L
    private var flags = FieldFlags()
    private var predictionJob: Job? = null
    private var precomputed: String? = null
    private var precomputedFor = ""
    /** В текущем «слове» до курсора есть не-буквы (например «:date») — только тогда автозамене нужно читать поле. */
    private var tokenHasSymbol = true
    /** Последнее слово вставлено свайпом: следующая буква начнёт новое слово, ⌫ удалит его целиком. */
    private var glideWord = false
    private var glideJob: Job? = null
    /** Регистр до последней буквы — чтобы вернуть его, если касание превратилось в свайп. */
    private var shiftBeforeKey = ShiftState.OFF
    /** Нажатые клавиши текущего слова для корейского (слоги) и вьетнамского (Telex): composing = compose(raw). */
    private var rawComposing = ""
    /** Точка касания последней клавиши и касания по буквам текущего слова (для автоисправления). */
    private var pendingTouch: KeyTouch? = null
    private val wordTouches = ArrayList<KeyTouch?>()
    private var touchesFor = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { syncClipboard() }

    private val settings: KeyboardSettings get() = KeyboardPrefs.current(this)

    private fun dataPrefs() = getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        KeyboardPrefs.ensureInit(this)
        WordPredictorEngine.ensureInitialized(this)
        EmojiCatalog.warmUp()
        EmojiRecents.load(this)
        IconStore.ensureLoaded(this)
        ScriptHost.bridge = this
        PluginRepository.ensureLoaded(this)
        // язык, на котором закончили в прошлый раз
        val enabled = Languages.enabled(settings)
        kb.language = dataPrefs().getString("last_language", null)?.let { id -> enabled.firstOrNull { it.id == id } } ?: enabled.first()
        WordPredictorEngine.onDictionaryLoaded = { if (composing.isEmpty() || precomputedFor != composing) schedulePredictions() }
        WordPredictorEngine.prepare(this, kb.language)
        runCatching {
            val arr = JSONArray(dataPrefs().getString("pinned_clips", "[]"))
            for (i in 0 until arr.length()) kb.pinnedClips += arr.getString(i)
        }
        voice = VoiceInput(this) { text -> commitText("$text ") }
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        syncClipboard()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { d ->
            d.setViewTreeLifecycleOwner(this)
            d.setViewTreeViewModelStoreOwner(this)
            d.setViewTreeSavedStateRegistryOwner(this)
        }
        val view = ComposeView(this).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@ComposeKeyboardService)
            setViewTreeViewModelStoreOwner(this@ComposeKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@ComposeKeyboardService)
            setContent {
                KeyboardRoot(kb, this@ComposeKeyboardService, voice) { bg, transparent ->
                    navColor = if (transparent) AndroidColor.TRANSPARENT else bg.toArgb()
                    navLight = bg.luminance() > 0.5f
                    applyWindowColors()
                }
            }
        }
        inputView = view
        applyWindowColors()
        return view
    }

    private fun moveLifecycle(target: Lifecycle.State) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = target
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        moveLifecycle(Lifecycle.State.RESUMED)
        resetForInput(info, restarting)
        applyWindowColors()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        composing = ""
        composingStart = -1
        rawComposing = ""
        undo = null
        autoSpace = false
        if (kb.panel == ActivePanel.VOICE) voice.stop()
        WordPredictorEngine.flush()
        ScriptHost.emit("finish")
    }

    override fun onWindowShown() {
        super.onWindowShown()
        moveLifecycle(Lifecycle.State.RESUMED)
        syncClipboard()
        applyWindowColors()
        ScriptHost.emit("show")
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        voice.stop()
        kb.panel = ActivePanel.KEYBOARD
        kb.toolbarOpen = false
        WordPredictorEngine.flush()
        moveLifecycle(Lifecycle.State.CREATED)
        ScriptHost.emit("hide")
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        applyWindowColors()
    }

    override fun onDestroy() {
        if (ScriptHost.bridge === this) ScriptHost.bridge = null
        WordPredictorEngine.onDictionaryLoaded = null
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        predictionJob?.cancel()
        voice.release()
        WordPredictorEngine.flush()
        serviceScope.cancel()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    private fun applyWindowColors() {
        val w = window?.window ?: return
        runCatching {
            w.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            WindowCompat.setDecorFitsSystemWindows(w, false)
            navColor?.let {
                @Suppress("DEPRECATION")
                w.navigationBarColor = it
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) w.isNavigationBarContrastEnforced = false
            WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightNavigationBars = navLight
        }
    }

    // ------------------------------------------------------------------ поле ввода

    private fun computeFlags(info: EditorInfo?): FieldFlags {
        if (info == null) return FieldFlags()
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val text = cls == InputType.TYPE_CLASS_TEXT
        val password = (text && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val addressLike = text && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI)
        val noSuggest = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val capFlags = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val suggestions = text && !password && !addressLike
        return FieldFlags(
            isPassword = password,
            suggestions = suggestions,
            autoCorrect = suggestions && !noSuggest,
            autoCap = text && !password && !addressLike && (type and capFlags) != 0
        )
    }

    private fun resetForInput(info: EditorInfo?, restarting: Boolean) {
        flags = computeFlags(info)
        kb.glideAllowed = flags.suggestions
        glideWord = false
        tokenHasSymbol = true
        composing = ""
        composingStart = -1
        rawComposing = ""
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        lastTypedChar = null
        precomputed = null
        // язык могли выключить в настройках
        val enabled = Languages.enabled(settings)
        if (enabled.none { it == kb.language }) kb.language = enabled.first()
        expSelStart = info?.initialSelStart ?: -1
        expSelEnd = info?.initialSelEnd ?: -1
        val imeOptions = info?.imeOptions ?: 0
        kb.enterAction = if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) ENTER_NEWLINE
        else imeOptions and EditorInfo.IME_MASK_ACTION
        if (!restarting) {
            kb.panel = ActivePanel.KEYBOARD
            kb.toolbarOpen = false
            kb.translatorInput = ""
        }
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        kb.symbolsPage = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) 1 else 0
        kb.shift = if (autoCapAllowed() && ((info?.initialCapsMode ?: 0) != 0 || expSelStart <= 0)) {
            ShiftState.SINGLE
        } else ShiftState.OFF
        schedulePredictions()
        if (ScriptHost.has("start")) ScriptHost.emit("start", scriptField())
    }

    /** У письменностей без заглавных Shift включает второй ряд букв — его нельзя включать автоматически. */
    private fun autoCapAllowed() = flags.autoCap && settings.autoCapitalize && kb.language.hasCase

    private fun requestAutoCap() {
        if (autoCapAllowed() && kb.shift == ShiftState.OFF) kb.shift = ShiftState.SINGLE
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (expSelStart < 0 || expSelEnd < 0) {
            if (composing.isNotEmpty()) {
                composingStart = if (candidatesStart >= 0) candidatesStart else newSelStart
                expect(composingStart + composing.length)
            } else {
                expSelStart = newSelStart
                expSelEnd = newSelEnd
            }
            return
        }
        if (isBelatedUpdate(oldSelStart, newSelStart, oldSelEnd, newSelEnd)) return
        // курсор переместил пользователь или само приложение
        tokenHasSymbol = true
        expSelStart = newSelStart
        expSelEnd = newSelEnd
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing = ""
            composingStart = -1
        }
        lastCommittedWord = ""
        lastTypedChar = null
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        glideWord = false
        schedulePredictions()
    }

    private fun isBelatedUpdate(oldStart: Int, newStart: Int, oldEnd: Int, newEnd: Int): Boolean {
        if (expSelStart == newStart && expSelEnd == newEnd) return true
        if (expSelStart == oldStart && expSelEnd == oldEnd && (oldStart != newStart || oldEnd != newEnd)) return false
        return newStart == newEnd &&
            (newStart - oldStart).toLong() * (expSelStart - newStart) >= 0 &&
            (newEnd - oldEnd).toLong() * (expSelEnd - newEnd) >= 0
    }

    private fun expect(pos: Int) {
        expSelStart = pos
        expSelEnd = pos
    }

    private fun expectUnknown() {
        expSelStart = -1
        expSelEnd = -1
    }

    private fun selectionStart(): Int = if (expSelStart < 0) -1 else min(expSelStart, expSelEnd)

    private fun icSetComposing(ic: InputConnection, word: String) {
        if (composing.isEmpty() || composingStart < 0) composingStart = selectionStart()
        ic.setComposingText(word, 1)
        composing = word
        lastTypedChar = word.lastOrNull()
        if (composingStart >= 0) expect(composingStart + word.length) else expectUnknown()
    }

    private fun icCommit(ic: InputConnection, text: String) {
        val base = if (composing.isNotEmpty()) composingStart else selectionStart()
        ic.commitText(text, 1)
        composing = ""
        composingStart = -1
        if (text.isNotEmpty()) lastTypedChar = text.last()
        if (base >= 0) expect(base + text.length) else expectUnknown()
    }

    private fun icDeleteBefore(ic: InputConnection, count: Int) {
        ic.deleteSurroundingText(count, 0)
        if (expSelStart >= 0 && expSelStart == expSelEnd) expect(max(0, expSelStart - count)) else expectUnknown()
        lastTypedChar = null
    }

    private fun resolveCorrection(word: String): String? {
        if (!settings.autoCorrect || !flags.autoCorrect) return null
        if (word == "i" && kb.language.id == "en") return "I"
        val c = if (precomputedFor == word) precomputed else WordPredictorEngine.autoCorrectionFor(
            word, kb.language, settings.autoCorrectStrength, lastCommittedWord, touchesOf(word)
        )
        return if (c != null && c != word) c else null
    }

    /** Касания по буквам слова — только если они точно относятся к нему (слово набрано по буквам). */
    private fun touchesOf(word: String): List<KeyTouch?>? =
        if (word.isNotEmpty() && touchesFor == word && wordTouches.size == word.length) wordTouches.toList() else null

    private fun trackTouch(before: String, after: String, touch: KeyTouch?) {
        if (touchesFor != before || wordTouches.size != before.length) {
            wordTouches.clear()
            repeat(before.length) { wordTouches.add(null) }
        }
        repeat(after.length - before.length) { wordTouches.add(touch) }
        touchesFor = after
    }

    private fun trimTouches(before: String, after: String) {
        if (touchesFor != before) return
        while (wordTouches.size > after.length) wordTouches.removeAt(wordTouches.lastIndex)
        touchesFor = after
    }

    private fun schedulePredictions() {
        predictionJob?.cancel()
        if (!flags.suggestions) {
            kb.suggestions = SuggestionChipModel.EMPTY_ROW
            precomputed = null
            precomputedFor = ""
            return
        }
        val word = composing
        val prev = lastCommittedWord
        val u = undo
        val lang = kb.language
        val capNext = kb.shift != ShiftState.OFF && lang.hasCase
        val s = settings
        val correct = s.autoCorrect && flags.autoCorrect
        val touches = touchesOf(word)
        val shortcut = if (word.isNotEmpty()) PluginRepository.shortcuts[word.lowercase()] else null
        // подсказки модов (событие "suggest") встают по бокам
        val extra = if (ScriptHost.has("suggest")) ScriptHost.suggestions(word, prev) else emptyList()
        predictionJob = serviceScope.launch {
            val snap = withContext(Dispatchers.Default) {
                WordPredictorEngine.predict(word, prev, u, lang, capNext, s.autoCorrectStrength, correct, touches)
            }
            val chips = snap.chips.toMutableList()
            if (extra.isNotEmpty()) {
                chips[0] = SuggestionChipModel(extra[0], extra[0], isKeepExact = true)
                extra.getOrNull(1)?.let { chips[2] = SuggestionChipModel(it, it, isKeepExact = true) }
            }
            if (shortcut != null) {
                // сокращение плагина показываем главной подсказкой
                val expanded = PluginTemplates.expand(shortcut, clipboard = kb.clipboard.firstOrNull().orEmpty())
                chips[1] = SuggestionChipModel("→ " + expanded.replace('\n', ' ').take(24), expanded, isPrimary = true, isKeepExact = true)
            }
            kb.suggestions = chips
            precomputed = snap.autoCorrection
            precomputedFor = snap.forWord
        }
    }

    private fun learn(previous: String, word: String, boost: Int = 1) {
        if (!flags.isPassword && flags.suggestions && !settings.incognito) WordPredictorEngine.learn(kb.language, previous, word, boost)
        if (ScriptHost.has("word") && !flags.isPassword) ScriptHost.emit("word", mapOf("word" to word, "previous" to previous))
    }

    // ------------------------------------------------------------------ корейский и Telex

    private fun composeRaw(c: Composer, raw: String): String = when (c) {
        Composer.HANGUL -> Hangul.compose(raw)
        Composer.TELEX -> Telex.compose(raw)
        Composer.NONE -> raw
    }

    /** Буква или знак огласовки (хинди, тайский) — продолжают слово. */
    private fun isWordLetter(ch: Char): Boolean {
        if (ch.isLetter()) return true
        val t = Character.getType(ch)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    // ------------------------------------------------------------------ набор текста

    private fun typeCharacter(text: String, touch: KeyTouch? = null) {
        val ic = currentInputConnection ?: return
        val afterSpace = lastActionWasSpace
        lastActionWasSpace = false
        val ch = text.singleOrNull()
        if (ch == null || (!isWordLetter(ch) && !ch.isWhitespace())) tokenHasSymbol = true
        if (ch != null && ch.isWhitespace()) tokenHasSymbol = false
        // буква сразу после слова из свайпа начинает новое слово
        if (glideWord && composing.isNotEmpty() && ch != null && ch.isLetter()) {
            learn(lastCommittedWord, composing)
            lastCommittedWord = composing
            icCommit(ic, "$composing ")
        }
        glideWord = false
        if (ch != null && settings.autoPair && tryAutoPair(ic, ch)) return
        val comp = kb.language.composer
        if (!flags.suggestions && comp == Composer.NONE) {
            icCommit(ic, composing + text)
            undo = null
            autoSpace = false
            return
        }
        if (ch != null && (isWordLetter(ch) || ((ch == '\'' || ch == '-') && composing.isNotEmpty()))) {
            undo = null
            autoSpace = false
            if (comp != Composer.NONE) {
                // слово собирается из нажатых клавиш: ㅎㅏㄴ → 한, tieengs → tiếng
                if (composeRaw(comp, rawComposing) != composing) rawComposing = composing
                rawComposing += text
                icSetComposing(ic, composeRaw(comp, rawComposing))
            } else {
                val before = composing
                icSetComposing(ic, before + text)
                trackTouch(before, composing, touch)
            }
            schedulePredictions()
            return
        }
        if (ch != null && ch in ".,!?:;") {
            // «слово ,» → «слово,»: пробел после слова переезжает за знак, как в Gboard
            if (settings.smartPunctuation && afterSpace && !autoSpace && composing.isEmpty() && charBeforeSpace?.isLetterOrDigit() == true) {
                ic.beginBatchEdit()
                icDeleteBefore(ic, 1)
                icCommit(ic, text)
                ic.endBatchEdit()
                tokenHasSymbol = true
                undo = null
                schedulePredictions()
                return
            }
            commitWithBoundary(ic, text)
            return
        }
        val word = composing
        if (word.isNotEmpty()) {
            learn(lastCommittedWord, word)
            lastCommittedWord = word
        }
        icCommit(ic, word + text)
        undo = null
        autoSpace = false
        schedulePredictions()
    }

    /** «(» вставляет «()» и ставит курсор внутрь; закрывающая скобка перед такой же просто перешагивает её. */
    private fun tryAutoPair(ic: InputConnection, ch: Char): Boolean {
        val close = PAIRS[ch]
        if (close != null) {
            val word = composing
            if (word.isNotEmpty()) {
                learn(lastCommittedWord, word)
                lastCommittedWord = word
            }
            icCommit(ic, "$word$ch$close")
            val pos = expSelStart
            if (pos > 0) {
                ic.setSelection(pos - 1, pos - 1)
                expect(pos - 1)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                expectUnknown()
            }
            undo = null
            autoSpace = false
            schedulePredictions()
            return true
        }
        if (ch in PAIRS.values && composing.isEmpty() && ic.getTextAfterCursor(1, 0)?.toString() == ch.toString()) {
            val pos = expSelStart
            if (pos >= 0) {
                ic.setSelection(pos + 1, pos + 1)
                expect(pos + 1)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                expectUnknown()
            }
            lastTypedChar = ch
            schedulePredictions()
            return true
        }
        return false
    }

    private fun commitWithBoundary(ic: InputConnection, suffix: String) {
        if (suffix.isNotBlank()) tokenHasSymbol = true
        val word = composing
        if (word.isEmpty()) {
            if (autoSpace && suffix.firstOrNull()?.let { it in ".,!?:;" } == true) {
                ic.beginBatchEdit()
                icDeleteBefore(ic, 1)
                icCommit(ic, "$suffix ")
                ic.endBatchEdit()
            } else {
                icCommit(ic, suffix)
                autoSpace = false
            }
            undo = null
            if (suffix.firstOrNull()?.let { it in ".!?" } == true && autoSpace) requestAutoCap()
            schedulePredictions()
            return
        }
        val correction = resolveCorrection(word)
        if (correction != null) {
            val committed = correction + suffix
            icCommit(ic, committed)
            undo = UndoAutoCorrectState(word, committed)
            // исправленное слово тоже учится — растут частота и пары слов, как в Gboard
            if (' ' !in correction) learn(lastCommittedWord, correction)
            lastCommittedWord = correction.substringAfterLast(' ')
        } else {
            icCommit(ic, word + suffix)
            undo = null
            learn(lastCommittedWord, word)
            lastCommittedWord = word
        }
        autoSpace = false
        schedulePredictions()
    }

    /** Автозамена сокращений из плагинов по пробелу. IPC к полю — только если есть сокращения с символами. */
    private fun tryExpandShortcut(ic: InputConnection): Boolean {
        val map = PluginRepository.shortcuts
        if (map.isEmpty() || flags.isPassword) return false
        var token: String? = composing.takeIf { it.isNotEmpty() && map.containsKey(it.lowercase()) }
        if (token == null && PluginRepository.hasSymbolShortcuts && tokenHasSymbol) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            token = Regex("\\S+$").find(before)?.value?.takeIf { map.containsKey(it.lowercase()) }
        }
        val t = token ?: return false
        val template = map[t.lowercase()] ?: return false
        val text = PluginTemplates.expand(template, clipboard = kb.clipboard.firstOrNull().orEmpty())
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            composing = ""
            composingStart = -1
        }
        ic.deleteSurroundingText(t.length, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        lastTypedChar = ' '
        schedulePredictions()
        return true
    }

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        glideWord = false
        if (ScriptHost.has("space") && ScriptHost.cancelled("space")) return
        if (tryExpandShortcut(ic)) {
            tokenHasSymbol = false
            return
        }
        tokenHasSymbol = false
        val now = SystemClock.uptimeMillis()
        if (composing.isEmpty() && lastActionWasSpace && settings.doubleSpacePeriod &&
            now - lastSpaceTime < 450L && charBeforeSpace?.isLetterOrDigit() == true && !autoSpace
        ) {
            ic.beginBatchEdit()
            icDeleteBefore(ic, 1)
            icCommit(ic, ". ")
            ic.endBatchEdit()
            undo = null
            lastActionWasSpace = false
            requestAutoCap()
            schedulePredictions()
            return
        }
        val prevChar = if (autoSpace && composing.isEmpty()) charBeforeAutoSpace else composing.lastOrNull() ?: lastTypedChar
        if (composing.isNotEmpty()) {
            commitWithBoundary(ic, " ")
        } else if (autoSpace) {
            autoSpace = false // пробел после подсказки уже стоит
        } else {
            icCommit(ic, " ")
            undo = null
        }
        charBeforeSpace = prevChar
        if (prevChar != null && prevChar in ".!?") requestAutoCap()
        lastActionWasSpace = true
        lastSpaceTime = now
        if (kb.symbolsPage != 0 && flags.suggestions) kb.symbolsPage = 0
        schedulePredictions()
    }

    override fun deleteBackward() {
        if (kb.panel == ActivePanel.TRANSLATE && kb.translatorInput.isNotEmpty()) {
            kb.translatorInput = kb.translatorInput.dropLast(1)
            return
        }
        val ic = currentInputConnection ?: return
        if (ScriptHost.has("backspace") && ScriptHost.cancelled("backspace")) return
        lastActionWasSpace = false
        if (glideWord && composing.isNotEmpty()) {
            // слово из свайпа стирается целиком, как в Gboard
            glideWord = false
            icCommit(ic, "")
            schedulePredictions()
            return
        }
        glideWord = false
        if (composing.isNotEmpty()) {
            val comp = kb.language.composer
            if (comp != Composer.NONE && rawComposing.isNotEmpty() && composeRaw(comp, rawComposing) == composing) {
                // стираем последнюю нажатую клавишу: 한 → 하
                rawComposing = rawComposing.dropLast(1)
                val c = composeRaw(comp, rawComposing)
                if (c.isEmpty()) icCommit(ic, "") else icSetComposing(ic, c)
                schedulePredictions()
                return
            }
            val before = composing
            val trimmed = if (composing.length >= 2 && composing.last().isLowSurrogate()) composing.dropLast(2) else composing.dropLast(1)
            if (trimmed.isEmpty()) icCommit(ic, "") else icSetComposing(ic, trimmed)
            trimTouches(before, trimmed)
            schedulePredictions()
            return
        }
        val u = undo
        if (u != null) {
            ic.beginBatchEdit()
            icDeleteBefore(ic, u.committedText.length)
            icSetComposing(ic, u.originalWord)
            ic.endBatchEdit()
            undo = null
            // отменённое исправление больше не повторяется
            WordPredictorEngine.rejectCorrection(kb.language, u.originalWord)
            learn("", u.originalWord, 2)
            schedulePredictions()
            return
        }
        autoSpace = false
        tokenHasSymbol = true
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (expSelStart >= 0) {
            if (expSelStart != expSelEnd) expect(min(expSelStart, expSelEnd)) else expect(max(0, expSelStart - 1))
        }
        lastTypedChar = null
        if (expSelStart == 0) requestAutoCap()
        schedulePredictions()
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        if (ScriptHost.has("enter") && ScriptHost.cancelled("enter")) return
        glideWord = false
        if (composing.isNotEmpty()) {
            learn(lastCommittedWord, composing)
            ic.finishComposingText()
            composing = ""
            composingStart = -1
        }
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val actionable = !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (actionable) ic.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        expectUnknown()
        lastTypedChar = '\n'
        tokenHasSymbol = false
        requestAutoCap()
        schedulePredictions()
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        kb.shift = when {
            kb.shift == ShiftState.CAPS_LOCK -> ShiftState.OFF
            now - lastShiftTime < 300L -> ShiftState.CAPS_LOCK
            kb.shift == ShiftState.SINGLE -> ShiftState.OFF
            else -> ShiftState.SINGLE
        }
        lastShiftTime = now
        schedulePredictions()
    }

    /** Следующий включённый язык (или предыдущий при direction < 0). */
    private fun toggleLanguage(direction: Int = 1) {
        val list = Languages.enabled(settings)
        val i = list.indexOf(kb.language)
        setLanguage(list[(i + (if (direction < 0) -1 else 1)).mod(list.size)])
    }

    private fun setLanguage(lang: KeyboardLanguage) {
        currentInputConnection?.let { ic ->
            if (composing.isNotEmpty()) {
                learn(lastCommittedWord, composing)
                lastCommittedWord = composing
                ic.finishComposingText()
                composing = ""
                composingStart = -1
            }
        }
        rawComposing = ""
        undo = null
        if (kb.language == lang) return
        kb.language = lang
        if (!lang.hasCase && kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        WordPredictorEngine.prepare(this, lang)
        dataPrefs().edit().putString("last_language", lang.id).apply()
        schedulePredictions()
        ScriptHost.emit("language", lang.id)
    }

    // ------------------------------------------------------------------ KeyboardActions

    override fun keyDown(key: KeyModel) {
        val special = key.type == KeyType.SPACE || key.type == KeyType.ENTER ||
            key.type == KeyType.BACKSPACE || key.type == KeyType.SHIFT
        HapticsEngine.keyPress(this, inputView, settings, special)
    }

    override fun keyUp() = HapticsEngine.release(this, settings)

    override fun keyTick() = HapticsEngine.tick(this, inputView, settings)

    override fun commitKey(key: KeyModel, text: String) {
        when (key.type) {
            KeyType.CHARACTER -> {
                // клавиша-команда из раскладки плагина
                key.action?.let { if (ScriptHost.runCommand(it)) return }
                shiftBeforeKey = kb.shift
                val wasSingle = kb.shift == ShiftState.SINGLE
                if (kb.panel == ActivePanel.TRANSLATE) kb.translatorInput += text else {
                    val t = if (ScriptHost.has("key") && !flags.isPassword) ScriptHost.filterKey(text) ?: return else text
                    if (t.isNotEmpty()) typeCharacter(t, pendingTouch)
                }
                pendingTouch = null
                if (wasSingle && kb.shift == ShiftState.SINGLE && text.any { isWordLetter(it) || it.isDigit() }) kb.shift = ShiftState.OFF
            }
            KeyType.SHIFT -> toggleShift()
            KeyType.BACKSPACE -> if (text == DELETE_WORD) deleteWord() else deleteBackward()
            KeyType.MODE_SYMBOLS -> {
                kb.symbolsPage = if (kb.symbolsPage == 0) 1 else 0
                if (kb.shift == ShiftState.SINGLE && kb.symbolsPage != 0) kb.shift = ShiftState.OFF
            }
            KeyType.SYMBOLS_PAGE -> kb.symbolsPage = if (kb.symbolsPage == 1) 2 else 1
            // нажатие — следующий язык, удержание — выбор из списка (text = код языка)
            KeyType.GLOBE -> Languages.enabled(settings).firstOrNull { it.label == text }?.let { setLanguage(it) } ?: toggleLanguage()
            KeyType.STICKERS -> togglePanel(ActivePanel.EMOJI)
            KeyType.SPACE -> if (kb.panel == ActivePanel.TRANSLATE) kb.translatorInput += " " else typeSpace()
            KeyType.ENTER -> if (kb.panel == ActivePanel.TRANSLATE && kb.translatorResult.isNotBlank()) {
                insertTranslation()
            } else performEnter()
            KeyType.SPACER -> Unit
        }
    }

    override fun keyTouch(fx: Float, fy: Float) {
        pendingTouch = KeyTouch(fx, fy)
    }

    override fun swipeSpace(direction: Int) {
        if (settings.spaceSwipe == "CURSOR" && kb.panel != ActivePanel.TRANSLATE) moveCursor(direction) else toggleLanguage(direction)
    }

    private fun finishComposing(ic: InputConnection) {
        if (composing.isEmpty()) return
        learn(lastCommittedWord, composing)
        ic.finishComposingText()
        composing = ""
        composingStart = -1
    }

    private fun moveCursor(direction: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        expectUnknown()
        sendDownUpKeyEvents(if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        HapticsEngine.tick(this, inputView, settings)
        schedulePredictions()
    }

    private fun deleteWord() {
        if (kb.panel == ActivePanel.TRANSLATE && kb.translatorInput.isNotEmpty()) {
            kb.translatorInput = kb.translatorInput.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
            return
        }
        val ic = currentInputConnection ?: return
        lastActionWasSpace = false
        undo = null
        autoSpace = false
        if (composing.isNotEmpty()) {
            icCommit(ic, "")
            schedulePredictions()
            return
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
            expectUnknown()
            schedulePredictions()
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val n = Regex("(\\S+\\s*|\\s+)$").find(before)?.value?.length ?: 1
        icDeleteBefore(ic, n)
        if (expSelStart == 0 || before.length == n) requestAutoCap()
        schedulePredictions()
    }

    override fun pickSuggestion(chip: SuggestionChipModel) {
        if (chip.isEmpty) return
        HapticsEngine.keyPress(this, inputView, settings, false)
        val ic = currentInputConnection ?: return
        glideWord = false
        val u = undo
        if (chip.isUndo && u != null) {
            ic.beginBatchEdit()
            icDeleteBefore(ic, u.committedText.length)
            icCommit(ic, u.originalWord + " ")
            ic.endBatchEdit()
            WordPredictorEngine.rejectCorrection(kb.language, u.originalWord)
            learn("", u.originalWord, 3)
            lastCommittedWord = u.originalWord
        } else {
            var word = chip.commitWord
            // «оставить как есть» — это слово больше не исправлять
            if (chip.isKeepExact && composing.isNotEmpty() && word == composing) WordPredictorEngine.rejectCorrection(kb.language, word)
            if (composing.isEmpty() && kb.shift != ShiftState.OFF) word = word.replaceFirstChar { it.uppercase() }
            icCommit(ic, "$word ")
            charBeforeAutoSpace = word.lastOrNull()
            learn(lastCommittedWord, word, if (chip.isKeepExact) 3 else 1)
            lastCommittedWord = word
        }
        undo = null
        autoSpace = true
        lastActionWasSpace = false
        if (kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        schedulePredictions()
    }

    override fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        val word = composing
        if (word.isNotEmpty()) {
            learn(lastCommittedWord, word)
            lastCommittedWord = word
        }
        icCommit(ic, word + text)
        tokenHasSymbol = true
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        schedulePredictions()
    }

    override fun commitEmoji(emoji: String) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        EmojiRecents.add(this, emoji)
        commitText(emoji)
    }

    override fun headerMenu() {
        HapticsEngine.keyPress(this, inputView, settings, false)
        if (kb.panel != ActivePanel.KEYBOARD) {
            if (kb.panel == ActivePanel.VOICE) voice.stop()
            kb.panel = ActivePanel.KEYBOARD
            kb.toolbarOpen = false
        } else kb.toolbarOpen = !kb.toolbarOpen
    }

    override fun togglePanel(panel: ActivePanel) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        val old = kb.panel
        val next = if (old == panel) ActivePanel.KEYBOARD else panel
        if (old == ActivePanel.VOICE && next != ActivePanel.VOICE) voice.stop()
        if (next == ActivePanel.CLIPBOARD) syncClipboard()
        kb.panel = next
        if (next == ActivePanel.KEYBOARD) kb.toolbarOpen = false
        if (next == ActivePanel.VOICE) voice.start(kb.language)
    }

    override fun toggleVoice() {
        HapticsEngine.keyPress(this, inputView, settings, true)
        if (voice.listening) voice.stop() else voice.start(kb.language)
    }

    override fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun openVoiceFallback() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun hideKeyboard() {
        requestHideSelf(0)
    }

    override fun clearClipboard() {
        kb.clipboard.retainAll(kb.pinnedClips.toSet())
    }

    override fun removeClip(text: String) {
        HapticsEngine.tick(this, inputView, settings)
        kb.clipboard.remove(text)
        if (kb.pinnedClips.remove(text)) savePinned()
    }

    override fun togglePinClip(text: String) {
        HapticsEngine.tick(this, inputView, settings)
        if (!kb.pinnedClips.remove(text)) kb.pinnedClips.add(0, text)
        savePinned()
    }

    private fun savePinned() {
        val arr = JSONArray()
        kb.pinnedClips.take(100).forEach { arr.put(it) }
        dataPrefs().edit().putString("pinned_clips", arr.toString()).apply()
    }

    override fun forgetSuggestion(word: String) {
        if (word.isBlank()) return
        HapticsEngine.tick(this, inputView, settings)
        WordPredictorEngine.forget(kb.language, word)
        Toast.makeText(this, "«$word» удалено из выученных слов", Toast.LENGTH_SHORT).show()
        schedulePredictions()
    }

    override fun runScriptButton(pluginId: String, id: String) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        ScriptHost.runButton(pluginId, id)
    }

    override fun insertTranslation() {
        val result = kb.translatorResult
        if (result.isBlank()) return
        kb.translatorInput = ""
        kb.translatorResult = ""
        commitText("$result ")
    }

    private fun syncClipboard() {
        val s = settings
        if (s.incognito) return
        if (!s.clipboardHistory) {
            if (kb.clipboard.isNotEmpty()) kb.clipboard.clear()
            return
        }
        runCatching {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            // пароли, помеченные системой как чувствительные, в историю не попадают
            if (Build.VERSION.SDK_INT >= 33 &&
                clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
            ) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                if (kb.clipboard.firstOrNull() == text) return
                kb.clipboard.remove(text)
                kb.clipboard.add(0, text)
                while (kb.clipboard.size > s.clipboardSize) kb.clipboard.removeAt(kb.clipboard.lastIndex)
                ScriptHost.emit("clipboard", text)
            }
        }
    }

    override fun commitSymbol(text: String) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        commitText(text)
    }

    // ------------------------------------------------------------------ мгновенный ввод и свайп

    override fun replaceLast(old: String, new: String) {
        if (kb.panel == ActivePanel.TRANSLATE) {
            if (kb.translatorInput.endsWith(old)) kb.translatorInput = kb.translatorInput.dropLast(old.length) + new
            if (new.isEmpty()) kb.shift = shiftBeforeKey
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty() && composing.endsWith(old)) {
            val rest = composing.dropLast(old.length)
            if (rest.isEmpty()) icCommit(ic, "") else icSetComposing(ic, rest)
        } else {
            icDeleteBefore(ic, old.length)
        }
        if (new.isNotEmpty()) typeCharacter(new)
        ic.endBatchEdit()
        if (new.isEmpty()) {
            kb.shift = shiftBeforeKey
            schedulePredictions()
        }
    }

    override fun commitGlide(path: List<Offset>, keys: Map<Char, Offset>, keyWidth: Float) {
        val lang = kb.language
        val shift = kb.shift
        glideJob?.cancel()
        glideJob = serviceScope.launch {
            val words = withContext(Dispatchers.Default) {
                GlideDecoder.decode(path, keys, keyWidth, WordPredictorEngine.glideLexicon(lang))
            }
            if (words.isEmpty()) {
                HapticsEngine.tick(this@ComposeKeyboardService, inputView, settings)
                return@launch
            }
            insertGlideWords(words.map { w ->
                when (shift) {
                    ShiftState.CAPS_LOCK -> w.uppercase()
                    ShiftState.SINGLE -> w.replaceFirstChar { it.uppercase() }
                    ShiftState.OFF -> w
                }
            })
        }
    }

    private fun insertGlideWords(words: List<String>) {
        val best = words.first()
        if (kb.panel == ActivePanel.TRANSLATE) {
            val t = kb.translatorInput
            kb.translatorInput = if (t.isEmpty() || t.endsWith(' ')) t + best else "$t $best"
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            learn(lastCommittedWord, composing)
            lastCommittedWord = composing
            icCommit(ic, "$composing ")
        } else {
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{«\"'„“/@#") icCommit(ic, " ")
        }
        icSetComposing(ic, best)
        ic.endBatchEdit()
        glideWord = true
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        tokenHasSymbol = false
        if (kb.shift == ShiftState.SINGLE) kb.shift = ShiftState.OFF
        // остальные варианты — в подсказки; выбор подсказки заменит слово
        predictionJob?.cancel()
        precomputed = null
        precomputedFor = best
        kb.suggestions = listOf(
            words.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY,
            SuggestionChipModel(best, best, isPrimary = true),
            words.getOrNull(2)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
        )
    }

    override fun switchKeyboard() {
        HapticsEngine.tick(this, inputView, settings)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    // ------------------------------------------------------------------ плагины

    private class TextTarget(val text: String, val selection: Boolean, val beforeText: String, val afterText: String, val trailing: String)

    private fun takeTarget(ic: InputConnection, scope: String): TextTarget? {
        finishComposing(ic)
        val sel = ic.getSelectedText(0)?.toString().orEmpty()
        if (scope != "text" && scope != "word" && sel.isNotEmpty()) return TextTarget(sel, true, "", "", "")
        if (scope == "selection") return null
        if (scope == "text") {
            val b = ic.getTextBeforeCursor(20000, 0)?.toString().orEmpty()
            val a = ic.getTextAfterCursor(20000, 0)?.toString().orEmpty()
            return if (b.isEmpty() && a.isEmpty()) null else TextTarget(b + a, false, b, a, "")
        }
        val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        val m = Regex("(\\S+)(\\s*)$").find(before) ?: return null
        return TextTarget(m.groupValues[1], false, m.value, "", m.groupValues[2])
    }

    private fun targetStillValid(ic: InputConnection, t: TextTarget): Boolean = runCatching {
        if (t.selection) return ic.getSelectedText(0)?.toString() == t.text
        val b = ic.getTextBeforeCursor(t.beforeText.length, 0)?.toString().orEmpty()
        val a = if (t.afterText.isEmpty()) "" else ic.getTextAfterCursor(t.afterText.length, 0)?.toString().orEmpty()
        b == t.beforeText && a == t.afterText
    }.getOrDefault(false)

    private fun replaceTarget(ic: InputConnection, t: TextTarget, out: String) {
        ic.beginBatchEdit()
        if (t.selection) {
            ic.commitText(out, 1)
        } else {
            ic.deleteSurroundingText(t.beforeText.length, t.afterText.length)
            ic.commitText(out + t.trailing, 1)
        }
        ic.endBatchEdit()
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        schedulePredictions()
    }

    private fun sendChord(ic: InputConnection, code: Int, meta: Int) {
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun pluginKey(ic: InputConnection, key: String) {
        if (key == "enter") return performEnter()
        if (key == "delete_word") return deleteWord()
        finishComposing(ic)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        when (key) {
            "tab" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            "left" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            "home" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
            "end" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
            "escape" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
            "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "undo" -> sendChord(ic, KeyEvent.KEYCODE_Z, ctrl)
            "redo" -> sendChord(ic, KeyEvent.KEYCODE_Z, ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        }
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        lastCommittedWord = ""
        schedulePredictions()
    }

    override fun runPluginAction(pluginId: String, index: Int) {
        val plugin = PluginRepository.active.firstOrNull { it.id == pluginId } ?: return
        val a = plugin.actions.getOrNull(index) ?: return
        HapticsEngine.keyPress(this, inputView, settings, false)
        val ic = currentInputConnection ?: return
        val clip = kb.clipboard.firstOrNull().orEmpty()
        runCatching {
            when (a.type) {
                PluginActionType.INSERT -> {
                    val sel = ic.getSelectedText(0)?.toString().orEmpty()
                    commitText(PluginTemplates.expand(a.text, sel, clip))
                    kb.pluginStatus = "✓ ${a.title}"
                }
                PluginActionType.TRANSFORM -> {
                    val t = takeTarget(ic, a.scope)
                    if (t == null) {
                        kb.pluginStatus = "Нет текста для «${a.title}»"
                    } else {
                        replaceTarget(ic, t, PluginTransforms.apply(a, t.text))
                        kb.pluginStatus = "✓ ${a.title}"
                    }
                }
                PluginActionType.KEY -> {
                    pluginKey(ic, a.key)
                    kb.pluginStatus = "✓ ${a.title}"
                }
                PluginActionType.HTTP -> {
                    val t = takeTarget(ic, a.scope)
                    kb.pluginStatus = "⏳ ${a.title}…"
                    serviceScope.launch {
                        val r = PluginHttp.run(a, t?.text.orEmpty(), clip)
                        val ic2 = currentInputConnection ?: return@launch
                        r.onSuccess { out ->
                            if (a.output == "replace" && t != null && targetStillValid(ic2, t)) replaceTarget(ic2, t, out)
                            else commitText(out)
                            kb.pluginStatus = "✓ ${a.title}"
                        }.onFailure { kb.pluginStatus = "✕ ${a.title}: ${it.message ?: "ошибка сети"}" }
                    }
                }
            }
        }.onFailure { kb.pluginStatus = "✕ ${a.title}: ${it.message ?: "ошибка"}" }
    }

    // ------------------------------------------------------------------ ScriptBridge: что могут скрипты модов

    override fun scriptCommit(text: String) = commitText(text)

    override fun scriptDelete(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.deleteSurroundingText(before, after)
        expectUnknown()
        undo = null
        autoSpace = false
        lastActionWasSpace = false
        schedulePredictions()
    }

    override fun scriptTextBefore(n: Int): String = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    override fun scriptTextAfter(n: Int): String = currentInputConnection?.getTextAfterCursor(n, 0)?.toString().orEmpty()

    override fun scriptSelected(): String = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()

    override fun scriptSetSelection(start: Int, end: Int) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        ic.setSelection(max(0, start), max(0, end))
        expectUnknown()
        schedulePredictions()
    }

    override fun scriptKey(name: String) {
        val ic = currentInputConnection ?: return
        when (name) {
            "space" -> typeSpace()
            "backspace", "delete" -> deleteBackward()
            "shift" -> toggleShift()
            "language", "globe" -> toggleLanguage()
            else -> pluginKey(ic, name)
        }
    }

    override fun scriptLanguage(): String = kb.language.id

    override fun scriptSetLanguage(id: String): Boolean {
        val lang = Languages.byId(id) ?: return false
        setLanguage(lang)
        return true
    }

    override fun scriptShift(): String = when (kb.shift) {
        ShiftState.OFF -> "off"
        ShiftState.SINGLE -> "single"
        ShiftState.CAPS_LOCK -> "caps"
    }

    override fun scriptSetShift(state: String) {
        kb.shift = when (state.lowercase()) {
            "single", "on" -> ShiftState.SINGLE
            "caps", "lock" -> ShiftState.CAPS_LOCK
            else -> ShiftState.OFF
        }
    }

    override fun scriptField(): Map<String, Any?> {
        val info = currentInputEditorInfo
        val type = info?.inputType ?: 0
        return mapOf(
            "package" to (info?.packageName ?: ""),
            "inputType" to type,
            "password" to flags.isPassword,
            "multiLine" to ((type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0),
            "action" to when (kb.enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> "search"
                EditorInfo.IME_ACTION_SEND -> "send"
                EditorInfo.IME_ACTION_GO -> "go"
                EditorInfo.IME_ACTION_DONE -> "done"
                EditorInfo.IME_ACTION_NEXT -> "next"
                else -> "enter"
            },
            "hint" to (info?.hintText?.toString() ?: "")
        )
    }

    override fun scriptOpenPanel(id: String) {
        if (id.startsWith("plugin:")) {
            // "plugin:<id плагина>/<id панели>" — сразу нужная вкладка
            val spec = id.removePrefix("plugin:")
            kb.pluginTab = if ('/' in spec) "${spec.substringBefore('/')}#p:${spec.substringAfter('/')}" else "$spec#a"
            if (kb.panel != ActivePanel.PLUGINS) togglePanel(ActivePanel.PLUGINS)
            return
        }
        val panel = when (id.lowercase()) {
            "emoji" -> ActivePanel.EMOJI
            "clipboard" -> ActivePanel.CLIPBOARD
            "translate", "translator" -> ActivePanel.TRANSLATE
            "gif" -> ActivePanel.GIF
            "voice" -> ActivePanel.VOICE
            "plugins" -> ActivePanel.PLUGINS
            else -> ActivePanel.KEYBOARD
        }
        if (panel == ActivePanel.KEYBOARD) {
            if (kb.panel == ActivePanel.VOICE) voice.stop()
            kb.panel = ActivePanel.KEYBOARD
            kb.toolbarOpen = false
        } else if (kb.panel != panel) togglePanel(panel)
    }

    override fun scriptStatus(text: String) {
        kb.pluginStatus = text
    }

    override fun scriptVibrate(ms: Int) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun scriptClipboard(): List<String> = (kb.pinnedClips + kb.clipboard).distinct()

    override fun scriptCopy(text: String) {
        runCatching { clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("plugin", text)) }
    }

    override fun scriptSuggestions(words: List<String>) {
        val w = words.filter { it.isNotBlank() }
        kb.suggestions = listOf(
            w.getOrNull(1)?.let { SuggestionChipModel(it, it, isKeepExact = true) } ?: SuggestionChipModel.EMPTY,
            w.getOrNull(0)?.let { SuggestionChipModel(it, it, isPrimary = true, isKeepExact = true) } ?: SuggestionChipModel.EMPTY.copy(isPrimary = true),
            w.getOrNull(2)?.let { SuggestionChipModel(it, it, isKeepExact = true) } ?: SuggestionChipModel.EMPTY
        )
    }

    override fun scriptHide() = requestHideSelf(0)

    override fun commitGif(gif: TenorGifResult) {
        HapticsEngine.keyPress(this, inputView, settings, false)
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val supports = EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes(it, "image/gif") || ClipDescription.compareMimeTypes(it, "image/*")
        }
        if (!supports) {
            commitText("${gif.fullGifUrl} ")
            Toast.makeText(this, "Поле не принимает GIF — вставлена ссылка", Toast.LENGTH_SHORT).show()
            return
        }
        serviceScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "gifs").apply { mkdirs() }
                    val file = File(dir, "gif_${gif.id}.gif")
                    if (!file.exists() || file.length() == 0L) {
                        URL(gif.fullGifUrl).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                    val uri = FileProvider.getUriForFile(this@ComposeKeyboardService, "$packageName.fileprovider", file)
                    val content = InputContentInfoCompat(uri, ClipDescription(gif.title, arrayOf("image/gif")), Uri.parse(gif.fullGifUrl))
                    uri to content
                }.getOrNull()
            }
            val committed = ok != null && InputConnectionCompat.commitContent(
                ic, info, ok.second, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
            )
            if (!committed) commitText("${gif.fullGifUrl} ")
        }
    }
}
