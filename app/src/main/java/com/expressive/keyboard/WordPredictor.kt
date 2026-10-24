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
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.UserDictionary
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 6. СЛОВАРЬ, ПОДСКАЗКИ И АВТОИСПРАВЛЕНИЕ
// Частотные словари на ~50 000 слов (ru/en встроены, остальные скачиваются),
// поиск опечаток с учётом соседних клавиш и осторожные правила замены:
// слово из словаря или выученное (см. LEARNED) не исправляется.
// ============================================================================

/** Куда попал палец: смещение от центра нажатой клавиши в долях её ширины и высоты (-0.5…0.5). */
data class KeyTouch(val dx: Float, val dy: Float)

data class UndoAutoCorrectState(val originalWord: String, val committedText: String)

@Immutable
data class SuggestionChipModel(
    val displayText: String,
    val commitWord: String,
    val isPrimary: Boolean = false,
    val isKeepExact: Boolean = false,
    val isUndo: Boolean = false
) {
    val isEmpty: Boolean get() = commitWord.isEmpty() && !isUndo

    companion object {
        val EMPTY = SuggestionChipModel("", "")
        val EMPTY_ROW = listOf(EMPTY, EMPTY.copy(isPrimary = true), EMPTY)
    }
}

/**
 * [autoCorrection] — что заменит пробел (своё исправление, сведённое с системной проверкой),
 * [ownCorrection] — только своё: если системный ответ придёт позже, по пробелу их сводят заново.
 */
data class PredictionSnapshot(
    val chips: List<SuggestionChipModel>,
    val autoCorrection: String?,
    val forWord: String,
    val ownCorrection: String? = autoCorrection,
    val usedSystem: Boolean = false,
    /** Слово есть в словаре (или его подтвердила системная проверка). */
    val known: Boolean = false
)

/** Словарь одного языка: слова по убыванию частоты. */
class LangDict(words: List<String>) {
    val words: Array<String> = words.toTypedArray()
    val rank: HashMap<String, Int> = HashMap<String, Int>(words.size * 2).also { m -> words.forEachIndexed { i, w -> m.putIfAbsent(w, i) } }
    /** По алфавиту — для поиска продолжений по префиксу. */
    val sorted: Array<String> = this.words.copyOf().also { it.sort() }
    /** Индексы слов по первой букве (до 40 000 самых частых) — для поиска опечаток. */
    val byFirst: Map<Char, IntArray> = run {
        val tmp = HashMap<Char, ArrayList<Int>>()
        for (i in 0 until min(this.words.size, 40_000)) {
            val w = this.words[i]
            if (w.isNotEmpty()) tmp.getOrPut(w[0]) { ArrayList() }.add(i)
        }
        tmp.mapValues { it.value.toIntArray() }
    }
    val size: Int get() = words.size
}

object Dictionaries {
    private const val DIR = "dicts"
    private const val BASE = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
    private const val LIMIT = 50_000

    /** code → "загрузка…" / текст ошибки; готовые словари определяются по файлу. */
    var status by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var version by mutableIntStateOf(0)
        private set
    private val tried = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var bundledList: Set<String>? = null

    fun bundled(context: Context, code: String): Boolean {
        val list = bundledList ?: runCatching { context.assets.list("dict")?.toSet() }.getOrNull().orEmpty().also { bundledList = it }
        return "$code.txt" in list
    }

    fun file(context: Context, code: String) = File(File(context.applicationContext.filesDir, DIR), "$code.txt")

    fun isInstalled(context: Context, code: String) = bundled(context, code) || file(context, code).let { it.exists() && it.length() > 0 }

    fun read(context: Context, code: String): List<String>? = runCatching {
        val f = file(context, code)
        when {
            f.exists() && f.length() > 0 -> f.readLines()
            bundled(context, code) -> context.assets.open("dict/$code.txt").bufferedReader().use { it.readLines() }
            else -> null
        }
    }.getOrNull()

    private val WORD = Regex("^\\p{L}[\\p{L}\\p{M}'\\-]*$")

    /** Скачивает частотный список (OpenSubtitles, FrequencyWords) и оставляет 50 000 настоящих слов. */
    suspend fun download(context: Context, code: String): Result<Int> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        status = status + (code to "загрузка…")
        val r = runCatching {
            var lastError: Throwable? = null
            for (suffix in listOf("50k", "full")) {
                val res = runCatching {
                    val conn = (URL("$BASE/$code/${code}_$suffix.txt").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                    }
                    try {
                        if (conn.responseCode != 200) throw IllegalStateException("сервер ответил ${conn.responseCode}")
                        val out = LinkedHashSet<String>()
                        conn.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (out.size >= LIMIT) break
                                val w = line.trim().substringBefore(' ').lowercase()
                                if (w.isNotEmpty() && WORD.matches(w) && !w.endsWith("'") && !w.endsWith("-")) out += w
                            }
                        }
                        require(out.size >= 500) { "слишком маленький словарь" }
                        out
                    } finally {
                        conn.disconnect()
                    }
                }
                val words = res.getOrElse { lastError = it; null } ?: continue
                val f = file(app, code)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "$code.tmp")
                tmp.writeText(words.joinToString("\n"))
                // при обновлении словаря старый файл мешает переименованию на части файловых систем
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) throw IllegalStateException("не удалось сохранить словарь")
                }
                return@runCatching words.size
            }
            throw lastError ?: IllegalStateException("словарь не найден")
        }
        status = if (r.isSuccess) status - code else status + (code to (r.exceptionOrNull()?.message ?: "ошибка"))
        withContext(Dispatchers.Main) { version++ }
        WordPredictorEngine.invalidate(code)
        r
    }

    /** Один раз за запуск: если словаря языка нет — скачать в фоне. */
    fun ensureAsync(context: Context, lang: KeyboardLanguage) {
        val code = lang.dict ?: return
        if (isInstalled(context, code) || !tried.add(code)) return
        if (!KeyboardPrefs.current(context).dictAutoDownload) return
        CoroutineScope(Dispatchers.IO).launch { download(context, code) }
    }

    fun delete(context: Context, code: String) {
        file(context, code).delete()
        version++
        WordPredictorEngine.invalidate(code)
    }
}

/**
 * Системная проверка правописания (TextServicesManager). На большинстве телефонов это проверка Gboard
 * или Samsung — та же модель опечаток и тот же словарь, что у их клавиатур. Слово отправляется на каждое
 * нажатие, ответ приходит за 5–30 мс в своём потоке, так что к пробелу он обычно уже готов.
 */
object SystemSpell {
    class Result(val inDictionary: Boolean, val typo: Boolean, val recommended: Boolean, val suggestions: List<String>) {
        val hasOpinion: Boolean get() = inDictionary || typo
    }

    private val NONE = Result(false, false, false, emptyList())
    private const val ATTRS = SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY or
        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS

    private class Pending(val latch: CountDownLatch, val at: Long)

    private val handler: Handler by lazy { Handler(HandlerThread("system-spell").also { it.start() }.looper) }
    private val main: Handler by lazy { Handler(android.os.Looper.getMainLooper()) }
    private val cache = object : LinkedHashMap<String, Result>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 500
    }
    private val pending = ConcurrentHashMap<String, Pending>()

    // только в потоке handler
    private var session: SpellCheckerSession? = null
    private var sessionLocale = ""
    private val failedAt = HashMap<String, Long>()
    private val queue = ArrayDeque<Pair<Int, String>>()
    private var seq = 0

    /** Пришёл ответ для слова — вызывается в главном потоке. */
    @Volatile var onResult: ((String) -> Unit)? = null

    private fun key(locale: String, word: String) = "$locale|$word"

    fun cached(lang: KeyboardLanguage, word: String): Result? =
        synchronized(cache) { cache[key(lang.locale, word)] }?.takeIf { it !== NONE }

    fun request(context: Context, lang: KeyboardLanguage, word: String) {
        if (word.length < 2 || word.length > 40 || !word.all(WordPredictorEngine::isWordChar)) return
        val k = key(lang.locale, word)
        if (synchronized(cache) { cache.containsKey(k) }) return
        val now = SystemClock.uptimeMillis()
        val p = pending[k]
        if (p != null && now - p.at < 1500L) return
        pending[k] = Pending(CountDownLatch(1), now)
        val app = context.applicationContext
        handler.post { send(app, lang.locale, word, k) }
    }

    /** Ответ для слова; если запрос ещё в пути — ждёт его не дольше timeoutMs (по пробелу, обычно 0 мс). */
    fun await(lang: KeyboardLanguage, word: String, timeoutMs: Long): Result? {
        cached(lang, word)?.let { return it }
        val p = pending[key(lang.locale, word)] ?: return null
        runCatching { p.latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return cached(lang, word)
    }

    fun close() = handler.post {
        runCatching { session?.close() }
        session = null
        sessionLocale = ""
        while (queue.isNotEmpty()) finish(queue.removeFirst().second, null)
    }

    /** Название включённой проверки правописания (Android 12+), null — неизвестно или выключена. */
    fun currentName(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT < 31) return null
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        if (!tsm.isSpellCheckerEnabled) return null
        tsm.currentSpellCheckerInfo?.loadLabel(context.packageManager)?.toString()
    }.getOrNull()

    private fun finish(k: String, r: Result?) {
        synchronized(cache) { cache[k] = r ?: NONE }
        pending.remove(k)?.latch?.countDown()
        if (r != null && r.hasOpinion) {
            val word = k.substringAfter('|')
            main.post { onResult?.invoke(word) }
        }
    }

    private fun sessionFor(app: Context, locale: String): SpellCheckerSession? {
        session?.let { if (sessionLocale == locale) return it }
        runCatching { session?.close() }
        session = null
        sessionLocale = ""
        failedAt[locale]?.let { if (SystemClock.uptimeMillis() - it < 30_000L) return null }
        val tsm = app.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        val loc = Locale.forLanguageTag(locale)
        val s = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val params = SpellCheckerSession.SpellCheckerSessionParams.Builder()
                    .setLocale(loc)
                    .setShouldReferToSpellCheckerLanguageSettings(false)
                    .setSupportedAttributes(ATTRS)
                    .build()
                tsm.newSpellCheckerSession(params, { handler.post(it) }, listener)
            } else {
                // ответы придут в поток, где создана сессия, — то есть сюда
                @Suppress("DEPRECATION")
                tsm.newSpellCheckerSession(null, loc, listener, false)
            }
        }.getOrNull()
        if (s == null) failedAt[locale] = SystemClock.uptimeMillis() else failedAt.remove(locale)
        session = s
        sessionLocale = if (s != null) locale else ""
        return s
    }

    private fun send(app: Context, locale: String, word: String, k: String) {
        val s = sessionFor(app, locale)
        if (s == null) {
            finish(k, null)
            return
        }
        val id = ++seq
        queue.addLast(id to k)
        // ответы потерялись (сервис перезапустился) — не копим очередь
        while (queue.size > 16) finish(queue.removeFirst().second, null)
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(word, id, id)), 5) }.onFailure {
            queue.removeAll { it.first == id }
            finish(k, null)
        }
    }

    private fun deliver(info: SuggestionsInfo?) {
        if (queue.isEmpty()) return
        val idx = info?.let { i -> queue.indexOfFirst { it.first == i.sequence } }?.takeIf { it >= 0 } ?: 0
        // всё, что было до этого ответа, уже не придёт
        repeat(idx) { finish(queue.removeFirst().second, null) }
        val k = queue.removeFirst().second
        if (info == null) {
            finish(k, null)
            return
        }
        val a = info.suggestionsAttributes
        val word = k.substringAfter('|')
        val list = (0 until max(0, info.suggestionsCount))
            .mapNotNull { info.getSuggestionAt(it)?.trim() }
            .filter { it.isNotEmpty() && it != word }
            .distinct()
        finish(
            k, Result(
                inDictionary = a and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0,
                typo = a and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0,
                recommended = a and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
                suggestions = list
            )
        )
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            results?.forEach { deliver(it) }
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            // пустой ответ — тоже ответ, иначе пробел ждал бы его до таймаута
            if (results.isNullOrEmpty()) return deliver(null)
            for (s in results) {
                if (s == null || s.suggestionsCount <= 0) {
                    deliver(null)
                    continue
                }
                // отправляем по одному слову, поэтому берём часть, покрывающую слово с начала (или первую)
                val i = (0 until s.suggestionsCount).firstOrNull { s.getOffsetAt(it) == 0 } ?: 0
                deliver(s.getSuggestionsInfoAt(i))
            }
        }
    }
}

object WordPredictorEngine {
    const val DATA_PREFS = "expressive_keyboard_v8_data"
    private const val KEY_WORDS = "user_words"
    private const val KEY_BIGRAMS = "user_bigrams"

    /** "ru:слово" → сколько раз набрано (см. LEARNED). */
    private val userWords = ConcurrentHashMap<String, Int>()
    private val userBigrams = ConcurrentHashMap<String, Int>()
    private val dicts = ConcurrentHashMap<String, LangDict>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    /** Слова, добавленные плагинами: язык → слова. */
    @Volatile var pluginWords: Map<String, List<String>> = emptyMap()
        set(value) {
            field = value
            dicts.clear()
        }
    @Volatile private var started = false
    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val glideCache = ConcurrentHashMap<String, List<Pair<String, Float>>>()
    /** Вызывается в главном потоке, когда словарь загрузился — клавиатура обновляет подсказки. */
    @Volatile var onDictionaryLoaded: (() -> Unit)? = null

    private const val RU_SEED =
        "я ты он она оно мы вы они меня тебя его её нас вас их мне тебе ему ей нам вам им мной тобой " +
        "привет здравствуйте спасибо пожалуйста извини прости хорошо отлично супер класс круто ясно понятно ладно окей ок давай " +
        "сегодня завтра вчера сейчас потом скоро уже ещё еще всегда никогда иногда " +
        "приветик спасибки нормалек погнали увидимся созвонимся спишемся"

    private const val EN_SEED =
        "hello hi hey thanks thank please sorry yes yeah okay ok sure maybe today tomorrow tonight " +
        "dont doesnt didnt isnt arent wont cant im youre"

    private val ruBigrams: Map<String, List<String>> = mapOf(
        "привет" to listOf("как", "что", "дела"),
        "здравствуйте" to listOf("как", "подскажите", "я"),
        "как" to listOf("дела", "ты", "жизнь"),
        "дела" to listOf("хорошо", "отлично", "у"),
        "что" to listOf("делаешь", "нового", "случилось"),
        "доброе" to listOf("утро", "время", "здоровье"),
        "добрый" to listOf("день", "вечер", "человек"),
        "спокойной" to listOf("ночи", "дороги", "смены"),
        "спасибо" to listOf("большое", "тебе", "за"),
        "большое" to listOf("спасибо", "тебе", "дело"),
        "пожалуйста" to listOf("не", "помоги", "скажи"),
        "я" to listOf("не", "сейчас", "люблю"),
        "ты" to listOf("где", "как", "сегодня"),
        "мы" to listOf("сегодня", "уже", "будем"),
        "не" to listOf("знаю", "могу", "хочу"),
        "очень" to listOf("хорошо", "круто", "вкусно"),
        "давай" to listOf("завтра", "сегодня", "потом"),
        "хорошего" to listOf("дня", "вечера", "настроения"),
        "у" to listOf("меня", "тебя", "нас"),
        "меня" to listOf("все", "нет", "есть"),
        "тебя" to listOf("люблю", "как", "жду"),
        "мне" to listOf("кажется", "нужно", "нравится"),
        "это" to listOf("очень", "просто", "правда"),
        "все" to listOf("хорошо", "отлично", "будет"),
        "всё" to listOf("хорошо", "отлично", "понятно")
    )

    private val enBigrams: Map<String, List<String>> = mapOf(
        "hello" to listOf("how", "there", "everyone"),
        "hi" to listOf("how", "there", "guys"),
        "how" to listOf("are", "is", "about"),
        "are" to listOf("you", "we", "they"),
        "you" to listOf("are", "can", "know"),
        "thank" to listOf("you", "goodness", "so"),
        "thanks" to listOf("for", "a", "so"),
        "i" to listOf("am", "have", "think"),
        "what" to listOf("is", "are", "about"),
        "good" to listOf("morning", "night", "idea"),
        "see" to listOf("you", "what", "if")
    )

    private val ruTypos = mapOf(
        "привкт" to "привет", "првиет" to "привет", "привте" to "привет", "пирвет" to "привет",
        "превет" to "привет", "кка" to "как", "аак" to "как", "длеа" to "дела",
        "спсибо" to "спасибо", "спаисбо" to "спасибо", "сапсибо" to "спасибо", "пожалуста" to "пожалуйста",
        "пожалуйтса" to "пожалуйста", "хоршо" to "хорошо", "хрошо" to "хорошо", "хорощо" to "хорошо",
        "сегодян" to "сегодня", "сегдня" to "сегодня", "сйечас" to "сейчас", "сейчса" to "сейчас",
        "потмо" to "потом", "чот" to "что", "чьо" to "что", "жто" to "это", "наврено" to "наверное",
        "кнечно" to "конечно", "коненчо" to "конечно", "любюл" to "люблю", "тбея" to "тебя"
    )

    private val enTypos = mapOf(
        "teh" to "the", "hte" to "the", "adn" to "and", "nad" to "and", "taht" to "that",
        "tihs" to "this", "waht" to "what", "hwat" to "what", "hwo" to "how", "yuo" to "you",
        "uoy" to "you", "thnak" to "thank", "thnaks" to "thanks", "plaese" to "please",
        "plesae" to "please", "becuase" to "because", "awsome" to "awesome",
        "tomorow" to "tomorrow", "freind" to "friend", "recieve" to "receive", "dont" to "don't",
        "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't", "cant" to "can't", "wont" to "won't", "im" to "I'm"
    )

    private fun isCyrillic(c: Char): Boolean = c in 'а'..'я' || c == 'ё'

    fun ensureInitialized(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        scope.launch {
            runCatching {
                val data = app.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE)
                var wordsRaw = data.getString(KEY_WORDS, null)
                var bigramsRaw = data.getString(KEY_BIGRAMS, null)
                if (wordsRaw == null) {
                    // перенос словаря из v7, где он лежал в файле настроек
                    val legacy = KeyboardPrefs.prefs(app)
                    wordsRaw = legacy.getString("user_learned_words_v7", null)
                    bigramsRaw = legacy.getString("user_learned_bigrams_v7", null)
                    if (wordsRaw != null) legacy.edit().remove("user_learned_words_v7").remove("user_learned_bigrams_v7").apply()
                }
                parseInto(wordsRaw, userWords)
                parseInto(bigramsRaw, userBigrams)
            }
            prepare(app, KeyboardLanguage.RU)
            prepare(app, KeyboardLanguage.EN)
        }
    }

    /** Личный словарь Android ("ru:слово", "*:слово" — для всех языков): такие слова никогда не исправляются. */
    @Volatile private var personal: Set<String> = emptySet()
    /** Сокращения из личного словаря: "омв" → "on my way". */
    @Volatile var personalShortcuts: Map<String, String> = emptyMap()
        private set
    @Volatile private var personalLoadedAt = 0L

    /**
     * Читает личный словарь Android (Настройки → Язык → Личный словарь). Доступен только активной клавиатуре,
     * поэтому вызывается из сервиса, не чаще раза в минуту.
     */
    fun loadPersonalDictionary(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (personalLoadedAt != 0L && now - personalLoadedAt < 60_000L) return
        personalLoadedAt = now
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val words = HashSet<String>()
                val shortcuts = HashMap<String, String>()
                app.contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE, UserDictionary.Words.SHORTCUT),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext() && words.size < 20_000) {
                        val w = c.getString(0)?.trim().orEmpty()
                        if (w.isEmpty()) continue
                        val lang = c.getString(1)?.substringBefore('_')?.substringBefore('-')?.lowercase().orEmpty().ifEmpty { "*" }
                        words += "$lang:${w.lowercase()}"
                        val sc = c.getString(2)?.trim()?.lowercase()
                        if (!sc.isNullOrEmpty()) shortcuts[sc] = w
                    }
                }
                personal = words
                personalShortcuts = shortcuts
            }
        }
    }

    private fun isPersonal(lang: KeyboardLanguage, lower: String): Boolean {
        val p = personal
        return p.isNotEmpty() && ("*:$lower" in p || "${lang.id}:$lower" in p || "${lang.locale.substringBefore('-')}:$lower" in p)
    }

    /** Старые ключи без языка ("слово") получают язык по алфавиту. */
    private fun parseInto(raw: String?, target: ConcurrentHashMap<String, Int>) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val key = if (':' in k) k else (if (k.firstOrNull()?.let(::isCyrillic) == true) "ru:" else "en:") + k
                target[key] = json.optInt(k, 1)
            }
        }
    }

    /** Загружает словарь языка в фоне (если ещё нет). */
    fun prepare(context: Context, lang: KeyboardLanguage) {
        val app = context.applicationContext
        appContext = appContext ?: app
        val code = lang.dict ?: lang.id
        if (dicts.containsKey(code) || !loading.add(code)) return
        scope.launch {
            try {
                val words = LinkedHashSet<String>()
                Dictionaries.read(app, code)?.let { list -> list.forEach { if (it.isNotBlank()) words += it.trim() } }
                when (code) {
                    "ru" -> RU_SEED.split(' ').forEach { if (it.isNotBlank()) words += it }
                    "en" -> EN_SEED.split(' ').forEach { if (it.isNotBlank()) words += it }
                }
                pluginWords[lang.id]?.let { words += it.map { w -> w.lowercase() } }
                dicts[code] = LangDict(words.toList())
                glideCache.remove(code)
                withContext(Dispatchers.Main) { onDictionaryLoaded?.invoke() }
            } finally {
                loading.remove(code)
            }
        }
        if (lang.dict != null) Dictionaries.ensureAsync(app, lang)
    }

    fun invalidate(code: String) {
        dicts.remove(code)
        glideCache.remove(code)
        appContext?.let { ctx -> Languages.all.filter { (it.dict ?: it.id) == code }.forEach { prepare(ctx, it) } }
    }

    private fun dictFor(lang: KeyboardLanguage): LangDict? {
        val d = dicts[lang.dict ?: lang.id]
        if (d == null) appContext?.let { prepare(it, lang) }
        return d
    }

    /** Есть ли полноценный словарь (а не только пара сотен слов-затравок). */
    fun hasDictionary(lang: KeyboardLanguage): Boolean = (dicts[lang.dict ?: lang.id]?.size ?: 0) >= 3000

    private fun uKey(lang: KeyboardLanguage, w: String) = "${lang.id}:$w"

    /** Буква, знак огласовки (хинди, тайский) или апостроф/дефис внутри слова. */
    fun isWordChar(c: Char): Boolean {
        if (c.isLetter() || c == '\'' || c == '-') return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun learn(lang: KeyboardLanguage, previous: String, word: String, boost: Int = 1) {
        val w = word.trim().lowercase()
        if (w.length < 2 || !w.all(::isWordChar)) return
        val k = uKey(lang, w)
        userWords[k] = (userWords[k] ?: 0) + boost
        val p = previous.trim().lowercase()
        if (p.isNotEmpty() && p.all(::isWordChar)) {
            val key = "${lang.id}:$p|$w"
            userBigrams[key] = (userBigrams[key] ?: 0) + 1
        }
        scheduleSave(3000L)
    }

    /** Забыть слово (долгое нажатие на подсказку). */
    fun forget(lang: KeyboardLanguage, word: String) {
        val w = word.trim().lowercase()
        userWords.remove(uKey(lang, w))
        val suffix = "|$w"
        userBigrams.keys.filter { it.startsWith("${lang.id}:") && it.endsWith(suffix) }.forEach { userBigrams.remove(it) }
        scheduleSave(500L)
    }

    fun flush() = scheduleSave(0L)

    fun clearUserData() {
        userWords.clear()
        userBigrams.clear()
        glideCache.clear()
        scheduleSave(0L)
    }

    private fun scheduleSave(delayMs: Long) {
        val app = appContext ?: return
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                runCatching {
                    val wj = JSONObject()
                    userWords.entries.sortedByDescending { it.value }.take(6000).forEach { wj.put(it.key, it.value) }
                    val bj = JSONObject()
                    userBigrams.entries.sortedByDescending { it.value }.take(6000).forEach { bj.put(it.key, it.value) }
                    app.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_WORDS, wj.toString())
                        .putString(KEY_BIGRAMS, bj.toString())
                        .apply()
                }
            }
        }
    }

    private fun userCount(lang: KeyboardLanguage, lower: String) = userWords[uKey(lang, lower)] ?: 0

    /**
     * Незнакомое слово становится «своим» (не исправляется и подсказывается), когда набрано 3 раза
     * или его явно оставили: отменили исправление ⌫ или выбрали «как есть» — это сразу +3.
     */
    private const val LEARNED = 3

    /** Словарь для печати свайпом: слово → штраф за редкость (раньше в словаре и чаще набрано — меньше). */
    fun glideLexicon(lang: KeyboardLanguage): List<Pair<String, Float>> {
        val code = lang.dict ?: lang.id
        val base = glideCache[code] ?: run {
            val d = dictFor(lang)
            val list = ArrayList<Pair<String, Float>>()
            if (d != null) {
                for (i in 0 until min(d.size, 20_000)) {
                    val w = d.words[i]
                    if (w.length >= 2 && w.all { it.isLetter() }) list += w to 0.035f * kotlin.math.ln(1f + i / 4f)
                }
            }
            if (d != null) glideCache[code] = list
            list
        }
        val prefix = "${lang.id}:"
        val out = LinkedHashMap<String, Float>(base.size + 64)
        for ((w, p) in base) out[w] = p
        for ((k, n) in userWords) {
            if (!k.startsWith(prefix)) continue
            val w = k.substring(prefix.length)
            if (w.length < 2 || !w.all { it.isLetter() } || (n < LEARNED && w !in out)) continue
            val pen = max(0f, 0.12f - 0.04f * kotlin.math.ln(1f + n))
            out[w] = min(out[w] ?: Float.MAX_VALUE, pen)
        }
        return out.toList()
    }

    /** Слово знакомо: есть в словаре любого загруженного языка с тем же алфавитом или набрано вами хотя бы дважды. */
    private fun isKnown(lower: String, lang: KeyboardLanguage): Boolean {
        if (userCount(lang, lower) >= LEARNED || isPersonal(lang, lower)) return true
        if (dictFor(lang)?.rank?.containsKey(lower) == true) return true
        for ((_, d) in dicts) if (d.rank.containsKey(lower)) return true
        return false
    }

    private fun applyCase(word: String, cap: Boolean, allCaps: Boolean): String = when {
        word.isEmpty() -> word
        allCaps -> word.uppercase()
        cap -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    // ---------------------------------------------------------------- опечатки

    /** Исправления, отменённые в этой сессии: такое слово больше не трогаем (даже в режиме инкогнито). */
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    /** Своё исправление уверенное (см. correctionLower). */
    private val confident = ConcurrentHashMap<String, Boolean>()
    /**
     * Сколько раз системная проверка назвала «словом из словаря» явную опечатку. Без словаря языка она так
     * отвечает на всё подряд — после трёх таких ответов её мнение для языка не учитывается до перезапуска.
     */
    private val systemMisses = ConcurrentHashMap<String, MutableSet<String>>()

    fun rejectCorrection(lang: KeyboardLanguage, word: String) {
        rejected += uKey(lang, word.trim().lowercase())
    }

    private fun baseLetter(c: Char): Char {
        if (c == 'ё') return 'е'
        if (c.code < 0xC0) return c
        val d = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        return d.firstOrNull() ?: c
    }

    private fun adjacent(lang: KeyboardLanguage, a: Char, b: Char): Boolean {
        val pa = lang.keyPositions[a] ?: return false
        val pb = lang.keyPositions[b] ?: return false
        return abs(pa.first - pb.first) <= 1f && abs(pa.second - pb.second) <= 1.01f
    }

    /** Клавиша выше, чем шире, — по вертикали промахиваются реже. */
    private const val ROW_SCALE = 1.3f

    /** Квадрат расстояния (в ширинах клавиши) от касания по клавише typed до центра клавиши target. */
    private fun touchDist2(lang: KeyboardLanguage, typed: Char, touch: KeyTouch, target: Char): Float {
        val p = lang.keyPositions[typed] ?: return Float.MAX_VALUE
        val q = lang.keyPositions[target] ?: return Float.MAX_VALUE
        val dx = p.second + touch.dx - q.second
        val dy = (p.first + touch.dy - q.first) * ROW_SCALE
        return dx * dx + dy * dy
    }

    /**
     * Буквы, которые путают на слух, а не пальцем: «спосибо», «сигодня», «дила».
     * Такие ошибки — самые частые в русском, поэтому стоят дешевле промаха по соседней клавише.
     */
    private val SOUND_ALIKE: Set<String> = buildSet {
        val ru = listOf("ао", "еи", "ея", "иы", "еэ", "юу", "ёо", "шщ", "ьъ", "зс", "дт", "бп", "вф", "гк", "жш", "цс")
        val latin = listOf("ae", "ei", "io", "ou", "ua", "ai", "eo", "yi", "ck", "cs", "sz", "sc")
        for (p in ru + latin) {
            add(p)
            add(p.reversed())
        }
    }

    private fun soundAlike(a: Char, b: Char) = "$a$b" in SOUND_ALIKE

    /**
     * Цена замены набранной буквы a на b. Если известно, куда попал палец, решает геометрия, как в Gboard:
     * касание у границы «а» и «о» делает замену почти бесплатной, удар точно в центр «а» — дорогой.
     */
    private fun subCost(lang: KeyboardLanguage, a: Char, b: Char, touch: KeyTouch?): Float {
        if (a == b) return 0f
        if (baseLetter(a) == baseLetter(b)) return 0.15f
        val sound = if (soundAlike(a, b)) 0.45f else 1f
        if (touch != null && lang.keyPositions.containsKey(a) && lang.keyPositions.containsKey(b)) {
            val spatial = 0.1f + 0.6f * (touchDist2(lang, a, touch, b) - touchDist2(lang, a, touch, a))
            return min(sound, spatial.coerceIn(0.1f, 1f))
        }
        return min(sound, if (adjacent(lang, a, b)) 0.55f else 1f)
    }

    /** Взвешенное расстояние Дамерау–Левенштейна: typed → dict, с ранним выходом после limit. */
    private fun editCost(lang: KeyboardLanguage, typed: String, dict: String, limit: Float, touches: List<KeyTouch?>?): Float {
        val n = typed.length
        val m = dict.length
        if (n > 30 || m > 30) return Float.MAX_VALUE
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1)
        for (j in 1..m) prev[j] = prev[j - 1] + if (j >= 2 && dict[j - 1] == dict[j - 2]) 0.45f else 0.8f
        var cur = FloatArray(m + 1)
        // перестановка смотрит на два ряда назад — выходим, только если оба ряда уже дороже лимита
        var prevMin = 0f
        for (i in 1..n) {
            cur[0] = i * 0.85f
            var rowMin = cur[0]
            val a = typed[i - 1]
            val touch = touches?.getOrNull(i - 1)
            // лишняя буква: дешевле, если это дубль или соседняя клавиша (задели две сразу)
            val delCost = when {
                i >= 2 && typed[i - 2] == a -> 0.45f
                i >= 2 && adjacent(lang, typed[i - 2], a) -> 0.6f
                else -> 0.85f
            }
            for (j in 1..m) {
                val b = dict[j - 1]
                // пропущенная буква: удвоенную («класный» → «классный») пропускают чаще всего
                val insCost = if (j >= 2 && dict[j - 2] == b) 0.45f else 0.8f
                var v = min(
                    prev[j - 1] + subCost(lang, a, b, touch),
                    min(prev[j] + delCost, cur[j - 1] + insCost)
                )
                if (i >= 2 && j >= 2 && a == dict[j - 2] && typed[i - 2] == b) v = min(v, prev2[j - 2] + 0.5f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit && prevMin > limit) return Float.MAX_VALUE
            prevMin = rowMin
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** Штраф за редкость слова: чем дальше от начала частотного списка, тем дороже. */
    private fun freqCost(rank: Int): Float = 0.15f * kotlin.math.ln(1f + rank / 30f)

    /** Бонус за то, что слово часто идёт после предыдущего («доброе» → «утро»). */
    private fun contextBonus(lang: KeyboardLanguage, prev: String, w: String): Float {
        if (prev.isEmpty()) return 0f
        var bonus = 0f
        val n = userBigrams["${lang.id}:$prev|$w"] ?: 0
        if (n > 0) bonus += min(0.6f, 0.25f * kotlin.math.ln(1f + n))
        val builtin = when (lang.id) {
            "ru" -> ruBigrams[prev]
            "en" -> enBigrams[prev]
            else -> null
        }
        if (builtin?.contains(w) == true) bonus += 0.3f
        return bonus
    }

    private class Candidate(val word: String, val cost: Float, val score: Float, val rank: Int)

    /** Расчёт для устаревшего набора прерывается: пока считали, пользователь уже нажал следующую букву. */
    private class Cancelled : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private val cancelCheck = ThreadLocal<(() -> Boolean)?>()

    private fun checkCancelled() {
        if (cancelCheck.get()?.invoke() == true) throw Cancelled()
    }

    /** Ближайшие слова словаря к набранному (с первой буквой, её соседями или переставленной второй). */
    private fun nearWords(
        lower: String,
        lang: KeyboardLanguage,
        maxCost: Float,
        limit: Int = 4,
        prev: String = "",
        touches: List<KeyTouch?>? = null
    ): List<Candidate> {
        val d = dictFor(lang) ?: return emptyList()
        val first = lower[0]
        val starts = LinkedHashSet<Char>()
        starts += first
        if (lower.length > 1) starts += lower[1]
        lang.keyPositions.keys.forEach { if (adjacent(lang, first, it)) starts += it }
        if (baseLetter(first) != first) starts += baseLetter(first)
        lang.keyPositions.keys.forEach { if (soundAlike(first, it)) starts += it }
        val maxLenDiff = if (lower.length <= 4) 1 else 2
        val p = prev.trim().lowercase()
        val best = ArrayList<Candidate>()
        for (c in starts) {
            val idx = d.byFirst[c] ?: continue
            checkCancelled()
            for (i in idx) {
                val w = d.words[i]
                if (abs(w.length - lower.length) > maxLenDiff || w == lower) continue
                val cost = editCost(lang, lower, w, maxCost, touches)
                if (cost > maxCost) continue
                val uc = userCount(lang, w)
                val score = cost + freqCost(i) - 0.12f * kotlin.math.ln(1f + uc) - contextBonus(lang, p, w)
                best += Candidate(w, cost, score, i)
            }
        }
        best.sortBy { it.score }
        return best.distinctBy { it.word }.take(limit)
    }

    /** Однобуквенные слова, которые можно отделить пропущенным пробелом. */
    private fun singleLetterWord(lang: KeyboardLanguage, s: String): Boolean = when (lang.id) {
        "ru" -> s in RU_SINGLE
        "en" -> s == "a" || s == "i"
        else -> false
    }

    private val RU_SINGLE = setOf("в", "с", "и", "к", "у", "я", "а", "о")

    /** Пропущенный пробел: «приветкак» → «привет как», «вкино» → «в кино». Возвращает пару и её цену. */
    private fun splitWords(lower: String, lang: KeyboardLanguage, normal: Boolean): Pair<String, Float>? {
        if (lower.length < (if (normal) 5 else 6)) return null
        val d = dictFor(lang) ?: return null
        // обе части — частые слова: иначе «вкусняшка» превращается в «вкус няшка»
        val maxRank = if (normal) 15_000 else 5_000
        var bestText: String? = null
        var bestScore = Float.MAX_VALUE
        for (k in 1 until lower.length) {
            val l = lower.substring(0, k)
            val r = lower.substring(k)
            if (l.length == 1 && !singleLetterWord(lang, l)) continue
            if (r.length == 1 && !singleLetterWord(lang, r)) continue
            val rl = d.rank[l]?.takeIf { it <= maxRank } ?: continue
            val rr = d.rank[r]?.takeIf { it <= maxRank } ?: continue
            val score = 0.5f + freqCost(rl) + freqCost(rr) - contextBonus(lang, l, r)
            if (score < bestScore) {
                bestScore = score
                bestText = "$l $r"
            }
        }
        val text = bestText ?: return null
        return if (bestScore <= (if (normal) 1.9f else 1.4f)) text to bestScore else null
    }

    /**
     * Автоисправление незнакомого слова: лучший вариант по сумме «цена опечатки + редкость − контекст».
     * Касания учитываются — промах у края клавиши исправляется уверенно, а точное попадание почти никогда.
     */
    private fun correctionLower(
        lower: String,
        lang: KeyboardLanguage,
        strength: String,
        prev: String,
        touches: List<KeyTouch?>?
    ): String? {
        if (lower.length < 2 || lower.any { !it.isLetter() && it != '\'' && it != '-' }) return null
        if (lang.id == "ru") ruTypos[lower]?.let { return it }
        if (lang.id == "en") enTypos[lower]?.let { return it }
        if (lower.length < 3 || !lang.autoCorrect || !hasDictionary(lang)) return null
        val normal = strength == "NORMAL"
        val hasTouch = touches?.any { it != null } == true
        val len = lower.length
        // короткие слова часто сленг и сокращения («спс», «оч»): без данных о касаниях осторожный режим их не трогает
        val maxCost = when {
            len == 3 -> if (normal) 0.5f else if (hasTouch) 0.4f else return null
            len == 4 -> if (normal) 0.85f else 0.65f
            len <= 7 -> if (normal) 1.35f else 1.05f
            else -> if (normal) 1.9f else 1.5f
        }
        val maxRank = when {
            len == 3 -> if (normal) 8_000 else 3_000
            len == 4 -> if (normal) 25_000 else 10_000
            else -> if (normal) 40_000 else 25_000
        }
        val cands = nearWords(lower, lang, maxCost, 4, prev, touches)
            .filter { it.rank <= maxRank || userCount(lang, it.word) >= LEARNED }
        val best = cands.firstOrNull()
        val split = splitWords(lower, lang, normal)
        if (split != null && (best == null || split.second < best.score)) return split.first
        if (best == null) return null
        val second = cands.getOrNull(1)
        // в осторожном режиме два почти равных варианта — не угадываем
        if (second != null && second.score - best.score < (if (normal) 0.05f else 0.15f) && second.cost <= best.cost + 0.1f) return null
        // уверенное исправление: одна дешёвая ошибка и частое слово — его не отменяет системная проверка
        confident[uKey(lang, lower)] = best.cost <= 0.7f && best.rank <= 20_000 && (second == null || second.score - best.score >= 0.25f)
        return best.word
    }

    /**
     * Слово есть в словаре, но очень редкое, а палец явно промахнулся мимо частого соседа.
     * Срабатывает только при данных о касаниях и только если вы сами это слово никогда не набирали.
     */
    private fun rareWordFix(
        lower: String,
        lang: KeyboardLanguage,
        strength: String,
        prev: String,
        touches: List<KeyTouch?>?
    ): String? {
        if (touches == null || touches.none { it != null } || lower.length < 3) return null
        if (!lang.autoCorrect || !hasDictionary(lang) || userCount(lang, lower) > 0 || isPersonal(lang, lower)) return null
        if (lower.any { !it.isLetter() }) return null
        val d = dictFor(lang) ?: return null
        val r = d.rank[lower] ?: return null
        val normal = strength == "NORMAL"
        if (r < (if (normal) 12_000 else 20_000)) return null
        val best = nearWords(lower, lang, if (normal) 0.4f else 0.32f, 2, prev, touches).firstOrNull() ?: return null
        if (best.rank * 20 > r) return null
        return if (freqCost(r) - best.score >= (if (normal) 0.3f else 0.45f)) best.word else null
    }

    fun autoCorrectionFor(
        word: String,
        lang: KeyboardLanguage,
        strength: String = "SAFE",
        previous: String = "",
        touches: List<KeyTouch?>? = null
    ): String? {
        val clean = word.trim()
        if (clean.length < 2) return null
        if (clean.any { it.isDigit() }) return null
        // аббревиатуры и слова с заглавной внутри (iPhone, ООО) не трогаем
        if (clean.drop(1).any { it.isUpperCase() } && !clean.all { it.isUpperCase() }) return null
        // «кто-» или «rock'» — слово ещё не дописано, а не опечатка
        if (clean.last() == '-' || clean.last() == '\'') return null
        val lower = clean.lowercase()
        if (uKey(lang, lower) in rejected) return null
        val t = touches?.takeIf { it.size == clean.length }
        val prev = previous.trim().lowercase()
        val fix = if (isKnown(lower, lang)) rareWordFix(lower, lang, strength, prev, t)
        else correctionLower(lower, lang, strength, prev, t)
        fix ?: return null
        return applyCase(fix, clean.first().isUpperCase(), clean.length > 1 && clean.all { it.isUpperCase() })
    }

    /**
     * Сводит своё исправление с ответом системной проверки (Gboard, Samsung): слово есть в её словаре — не трогаем,
     * уверенная рекомендация — берём её, иначе решает своё. Выученные, личные и отменённые слова — всегда за нами.
     */
    fun mergeSystem(word: String, own: String?, sys: SystemSpell.Result?, lang: KeyboardLanguage, strength: String): String? {
        if (sys == null || !sys.hasOpinion || !lang.autoCorrect) return own
        val clean = word.trim()
        if (clean.length < 2 || clean.last() == '-' || clean.last() == '\'') return own
        if (clean.drop(1).any { it.isUpperCase() } && !clean.all { it.isUpperCase() }) return own
        val lower = clean.lowercase()
        // слово другой письменности (английское на русской раскладке) системная проверка этого языка не поймёт
        if (!lang.keyPositions.containsKey(lower[0])) return own
        if (uKey(lang, lower) in rejected || userCount(lang, lower) >= LEARNED || isPersonal(lang, lower)) return own
        val table = when (lang.id) {
            "ru" -> ruTypos[lower]
            "en" -> enTypos[lower]
            else -> null
        }
        if (table != null) return own
        if ((systemMisses[lang.id]?.size ?: 0) >= 3) return own
        if (!sys.typo) {
            if (own != null && confident[uKey(lang, lower)] == true) {
                systemMisses.getOrPut(lang.id) { ConcurrentHashMap.newKeySet() } += lower
                return own
            }
            return null
        }
        val first = sys.suggestions.firstOrNull() ?: return own
        val cap = clean.first().isUpperCase()
        val allCaps = clean.length > 1 && clean.all { it.isUpperCase() }
        if (own != null && sys.suggestions.take(3).any { it.equals(own, ignoreCase = true) }) return own
        if (sys.recommended) return applyCase(first, cap, allCaps)
        if (own != null) return own
        // слово из своего словаря без уверенной рекомендации не трогаем
        if (strength != "NORMAL" || lower.length < 3 || isKnown(lower, lang)) return null
        // без рекомендации первый вариант берём, только если это действительно близкая опечатка
        val limit = when {
            lower.length <= 4 -> 0.9f
            lower.length <= 7 -> 1.4f
            else -> 1.9f
        }
        return if (editCost(lang, lower, first.lowercase(), limit, null) <= limit) applyCase(first, cap, allCaps) else null
    }

    // ---------------------------------------------------------------- подсказки

    /** Продолжения по префиксу: сначала ваши частые слова, затем самые частые в языке. */
    private fun completions(lower: String, lang: KeyboardLanguage, limit: Int): List<String> {
        val scored = HashMap<String, Float>()
        val prefix = "${lang.id}:$lower"
        for ((k, n) in userWords) {
            if (k.length > prefix.length && k.startsWith(prefix)) {
                val w = k.substring(lang.id.length + 1)
                // случайно набранное один раз (часто — опечатка) в подсказки не лезет
                if (n < LEARNED && dictFor(lang)?.rank?.containsKey(w) != true) continue
                scored[w] = min(scored[w] ?: Float.MAX_VALUE, 3f - kotlin.math.ln(1f + n))
            }
        }
        val d = dictFor(lang)
        if (d != null) {
            var lo = 0
            var hi = d.sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (d.sorted[mid] < lower) lo = mid + 1 else hi = mid
            }
            val top = ArrayList<Pair<String, Int>>()
            var i = lo
            while (i < d.sorted.size && d.sorted[i].startsWith(lower)) {
                val w = d.sorted[i]
                if (w.length > lower.length) top += w to (d.rank[w] ?: Int.MAX_VALUE)
                i++
            }
            top.sortBy { it.second }
            for ((w, r) in top.take(limit * 2)) {
                val s = kotlin.math.ln(2f + r) - 0.7f * kotlin.math.ln(1f + userCount(lang, w))
                scored[w] = min(scored[w] ?: Float.MAX_VALUE, s)
            }
        }
        return scored.entries.sortedBy { it.value }.take(limit).map { it.key }
    }

    fun predict(
        word: String,
        previous: String,
        undo: UndoAutoCorrectState?,
        lang: KeyboardLanguage,
        capitalizeNext: Boolean,
        strength: String = "SAFE",
        autoCorrect: Boolean = true,
        touches: List<KeyTouch?>? = null,
        sys: SystemSpell.Result? = null,
        cancelled: (() -> Boolean)? = null
    ): PredictionSnapshot? {
        cancelCheck.set(cancelled)
        return try {
            predictInner(word, previous, undo, lang, capitalizeNext, strength, autoCorrect, touches, sys)
        } catch (_: Cancelled) {
            null
        } finally {
            cancelCheck.set(null)
        }
    }

    private fun predictInner(
        word: String,
        previous: String,
        undo: UndoAutoCorrectState?,
        lang: KeyboardLanguage,
        capitalizeNext: Boolean,
        strength: String,
        autoCorrect: Boolean,
        touches: List<KeyTouch?>?,
        sys: SystemSpell.Result?
    ): PredictionSnapshot {
        val clean = word.trim()
        if (clean.isEmpty()) return nextWords(previous, undo, lang, capitalizeNext)

        val lower = clean.lowercase()
        val allCaps = clean.length > 1 && clean.all { it.isUpperCase() }
        val cap = clean.first().isUpperCase()
        val sysOk = sys != null && sys.inDictionary && !sys.typo
        val known = isKnown(lower, lang) || sysOk
        val own = if (autoCorrect) autoCorrectionFor(clean, lang, strength, previous, touches) else null
        val fix = if (autoCorrect) mergeSystem(clean, own, sys, lang, strength) else null
        // варианты системной проверки — когда она считает слово опечаткой
        val sysAlt = if (sys != null && sys.typo) sys.suggestions.map { applyCase(it, cap, allCaps) }.filter { it != clean && it != fix } else emptyList()
        val comps = completions(lower, lang, 3).map { applyCase(it, cap, allCaps) }.filter { it != clean && it != fix }

        val chips = when {
            fix != null -> listOf(
                SuggestionChipModel("«$clean»", clean, isKeepExact = true),
                SuggestionChipModel(fix, fix, isPrimary = true),
                (sysAlt.firstOrNull() ?: comps.firstOrNull() ?: alternativeTo(fix, lower, lang, previous, touches, cap, allCaps))
                    ?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            sysAlt.isNotEmpty() -> listOf(
                SuggestionChipModel(sysAlt[0], sysAlt[0]),
                SuggestionChipModel(clean, clean, isPrimary = true, isKeepExact = true),
                (sysAlt.getOrNull(1) ?: comps.firstOrNull())?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            known -> listOf(
                comps.getOrNull(0)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY,
                SuggestionChipModel(clean, clean, isPrimary = true, isKeepExact = true),
                comps.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            comps.isNotEmpty() -> listOf(
                SuggestionChipModel("«$clean»", clean, isKeepExact = true),
                SuggestionChipModel(comps[0], comps[0], isPrimary = true),
                comps.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
            )
            else -> {
                // похожие слова — только как подсказки, без замены по пробелу
                val near = if (clean.length >= 3) nearWords(lower, lang, 1.4f, 2, previous, touches?.takeIf { it.size == clean.length }).map { applyCase(it.word, cap, allCaps) } else emptyList()
                listOf(
                    near.getOrNull(0)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY,
                    SuggestionChipModel(clean, clean, isPrimary = true, isKeepExact = true),
                    near.getOrNull(1)?.let { SuggestionChipModel(it, it) } ?: SuggestionChipModel.EMPTY
                )
            }
        }
        return PredictionSnapshot(chips, fix, clean, own, sys != null, known)
    }

    /** Второй по вероятности вариант исправления — для правой подсказки. */
    private fun alternativeTo(
        fix: String,
        lower: String,
        lang: KeyboardLanguage,
        previous: String,
        touches: List<KeyTouch?>?,
        cap: Boolean,
        allCaps: Boolean
    ): String? {
        if (lower.length < 3) return null
        val t = touches?.takeIf { it.size == lower.length }
        return nearWords(lower, lang, 1.4f, 3, previous.trim().lowercase(), t)
            .map { applyCase(it.word, cap, allCaps) }
            .firstOrNull { !it.equals(fix, ignoreCase = true) }
    }

    private fun nextWords(
        previous: String,
        undo: UndoAutoCorrectState?,
        lang: KeyboardLanguage,
        cap: Boolean
    ): PredictionSnapshot {
        val prev = previous.trim().lowercase()
        val set = LinkedHashSet<String>()
        if (prev.isNotEmpty()) {
            val prefix = "${lang.id}:$prev|"
            // связка, набранная хотя бы дважды, — уже привычка, а не случайность
            userBigrams.entries.asSequence()
                .filter { it.key.startsWith(prefix) && it.value >= 2 }
                .sortedByDescending { it.value }
                .take(3)
                .forEach { set.add(it.key.substringAfter('|')) }
            when (lang.id) {
                "ru" -> ruBigrams[prev]?.let { set.addAll(it) }
                "en" -> enBigrams[prev]?.let { set.addAll(it) }
            }
        }
        val words = set.filter { it.isNotBlank() }.map { applyCase(it, cap, false) }
        val a = words.getOrElse(0) { "" }
        val b = words.getOrElse(1) { "" }
        val c = words.getOrElse(2) { "" }
        val left = if (undo != null) {
            SuggestionChipModel("↶ ${undo.originalWord}", undo.originalWord, isUndo = true)
        } else SuggestionChipModel(b, b)
        val right = if (undo != null) SuggestionChipModel(b, b) else SuggestionChipModel(c, c)
        return PredictionSnapshot(listOf(left, SuggestionChipModel(a, a, isPrimary = true), right), null, "")
    }
}
