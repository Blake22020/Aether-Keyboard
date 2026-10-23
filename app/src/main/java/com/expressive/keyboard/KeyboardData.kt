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
// 7. РАСКЛАДКИ, ВАРИАНТЫ ПРИ УДЕРЖАНИИ, ЭМОДЗИ, GIF, ПЕРЕВОДЧИК
// ============================================================================

enum class KeyType { CHARACTER, SHIFT, BACKSPACE, MODE_SYMBOLS, SYMBOLS_PAGE, GLOBE, STICKERS, SPACE, ENTER, SPACER }

enum class ShiftState { OFF, SINGLE, CAPS_LOCK }

enum class ActivePanel { KEYBOARD, EMOJI, TRANSLATE, GIF, CLIPBOARD, VOICE, PLUGINS }

@Immutable
data class KeyModel(
    val label: String,
    val type: KeyType = KeyType.CHARACTER,
    val weight: Float = 1f,
    val extraVariants: List<String> = emptyList(),
    /** Что вводит клавиша, если это не её подпись (клавиши из плагинов: "→" вводит "->"). */
    val output: String? = null,
    /** Свои варианты при удержании из плагина; null — из каталога. */
    val customVariants: List<String>? = null,
    /** Буква с Shift у письменностей без регистра (хинди, грузинский, корейский). */
    val upperLabel: String? = null,
    /** Варианты из описания языка (диакритика, цифра верхнего ряда). */
    val langVariants: List<String>? = null,
    /** Команда скрипта плагина "pluginId/команда" — клавиша вызывает её вместо ввода текста. */
    val action: String? = null
) {
    fun variants(upper: Boolean): List<String> {
        if (customVariants != null) return if (upper) customVariants.map { upperOf(it) } else customVariants
        if (type != KeyType.CHARACTER) return extraVariants
        PluginRepository.variants[label.lowercase()]?.let { v -> return if (upper) v.map { upperOf(it) } else v }
        langVariants?.let { v -> return if (upper) v.map { upperOf(it) } else v }
        return KeyVariantsCatalog.getVariantsFor(label, upper)
    }

    private fun upperOf(s: String): String = if (s == label && upperLabel != null) upperLabel else s.uppercase()

    fun shownLabel(upper: Boolean): String = if (upper && type == KeyType.CHARACTER) upperLabel ?: label.uppercase() else label

    fun text(upper: Boolean): String {
        if (upper && type == KeyType.CHARACTER && output == null && upperLabel != null) return upperLabel
        val t = output ?: label
        return if (upper && type == KeyType.CHARACTER) t.uppercase() else t
    }
}

@Immutable
data class KeyRow(val keys: List<KeyModel>, val heightWeight: Float = 1f)

object KeyVariantsCatalog {
    private val variantsMap: Map<String, List<String>> = mapOf(
        "и" to listOf("ї", "й", "і"), "е" to listOf("5", "є", "ё", "э"), "ь" to listOf("ы", "ъ", "ѣ"),
        "ш" to listOf("щ", "8", "ш"), "щ" to listOf("ш", "9", "щ"), "й" to listOf("ї", "1", "і"),
        "ц" to listOf("@", "2", "ц"), "у" to listOf("ў", "3", "у"), "к" to listOf("$", "4", "к"),
        "н" to listOf("%", "6", "н"), "г" to listOf("ґ", "7", "г"), "з" to listOf("№", "0", "з"),
        "х" to listOf("[", "ъ", "]"), "ф" to listOf("~", "ф", "|"), "ы" to listOf("і", "ы", "ї"),
        "в" to listOf("#", "в", "№"), "а" to listOf("@", "а", "&"), "п" to listOf("§", "п", "¶"),
        "р" to listOf("₽", "р", "®"), "о" to listOf("°", "о", "º"), "л" to listOf("/", "л", "\\"),
        "д" to listOf(":", "д", ";"), "ж" to listOf("\"", "ж", "'"), "э" to listOf("є", "э", "ё"),
        "я" to listOf("«", "я", "»"), "ч" to listOf("<", "ч", ">"), "с" to listOf("©", "с", "$"),
        "м" to listOf("µ", "м", "№"), "т" to listOf("™", "т", "+"), "б" to listOf("„", "б", "“"),
        "ю" to listOf("…", "ю", "•"),
        "a" to listOf("à", "á", "â", "ä", "æ", "ã"), "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"), "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"), "c" to listOf("ç", "ć", "č"), "n" to listOf("ñ", "ń", "ň"),
        "s" to listOf("ß", "ś", "š"), "y" to listOf("ÿ", "ý", "¥"), "z" to listOf("ž", "ź", "ż"),
        "q" to listOf("!", "1", "@"), "w" to listOf("@", "2", "#"), "r" to listOf("$", "4", "®"),
        "t" to listOf("%", "5", "™"), "p" to listOf("(", "0", ")"), "d" to listOf("ď", "đ", "$"),
        "f" to listOf("%", "f", "&"), "g" to listOf("ğ", "g", "*"), "h" to listOf("(", "h", ")"),
        "j" to listOf("-", "j", "+"), "k" to listOf(":", "k", ";"), "l" to listOf("ł", "l", "/"),
        "x" to listOf("×", "x", "÷"), "v" to listOf("!", "v", "?"), "b" to listOf("\"", "b", "'"),
        "m" to listOf("µ", "m", "№"),
        "1" to listOf("¹", "½", "⅓", "¼"), "2" to listOf("²", "⅔", "№"), "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴", "₽", "$", "€"), "5" to listOf("⁵", "‰", "⅝"), "6" to listOf("⁶", "^", "↑"),
        "7" to listOf("⁷", "&", "⅞"), "8" to listOf("⁸", "∞", "×"), "9" to listOf("⁹", "(", "«"),
        "0" to listOf("°", "∅", "⁰"), "." to listOf("…", "!", "?", ",", ":"),
        "," to listOf(";", "!", "?", "—", "…"), "$" to listOf("₽", "€", "£", "¥", "₿"),
        "-" to listOf("–", "—", "•", "±"), "+" to listOf("±", "×", "÷", "="),
        "\"" to listOf("«", "»", "„", "“"), "'" to listOf("‘", "’", "`"),
        "?" to listOf("¿", "‽", "!"), "!" to listOf("¡", "‼", "?")
    )

    fun getVariantsFor(label: String, upper: Boolean): List<String> {
        val base = PluginRepository.variants[label.lowercase()] ?: variantsMap[label.lowercase()] ?: listOf(label)
        return if (upper) base.map { it.uppercase() } else base
    }
}

object KeyboardLayouts {
    private fun c(vararg s: String): List<KeyModel> = s.map { KeyModel(it) }
    private fun gap(w: Float) = KeyModel("", KeyType.SPACER, w)
    private fun shift(w: Float) = KeyModel("⇧", KeyType.SHIFT, w)
    private fun back(w: Float) = KeyModel("⌫", KeyType.BACKSPACE, w)
    private val punctuation = listOf("!", ",", ".", "?", ":")

    /** Удержание «,» и «.» — как в Gboard: вариант по умолчанию стоит в середине. */
    private val COMMA_VARIANTS = listOf(";", ":", ",", "'", "\"")
    private val PERIOD_VARIANTS = listOf("?", "!", "…", ".", ",", ":", "-")

    /**
     * Нижний ряд: ?123 · 🌐 · 😀 · пробел · ввод; на страницах символов ещё «,» и «.» по бокам пробела.
     * Скрытые кнопки отдают ширину пробелу.
     * Удержание 🌐 — список языков.
     */
    private fun bottom(mode: String, space: String, letters: Boolean, globe: Boolean, emoji: Boolean, punct: Boolean, languages: List<String>): KeyRow {
        // «,» и «.» — только на страницах символов (?123), буквенная раскладка остаётся прежней
        val withPunct = punct && !letters
        val spaceWeight = 3.9f + (if (globe) 0f else 1.1f) + (if (emoji) 0f else 1.1f) - (if (withPunct) 1.6f else 0f)
        return KeyRow(
            buildList {
                add(KeyModel(mode, KeyType.MODE_SYMBOLS, if (withPunct) 1.35f else 1.5f, if (letters && !withPunct) punctuation else emptyList()))
                if (globe) add(KeyModel("globe", KeyType.GLOBE, if (withPunct) 1f else 1.1f, if (languages.size > 1) languages else emptyList()))
                if (emoji) add(KeyModel("emoji", KeyType.STICKERS, if (withPunct) 1f else 1.1f))
                if (withPunct) add(KeyModel(",", weight = 0.9f, customVariants = COMMA_VARIANTS))
                add(KeyModel(space, KeyType.SPACE, max(2.2f, spaceWeight)))
                if (withPunct) add(KeyModel(".", weight = 0.9f, customVariants = PERIOD_VARIANTS))
                add(KeyModel("enter", KeyType.ENTER, if (withPunct) 1.6f else 1.8f))
            }
        )
    }

    private val numberRow = KeyRow(c("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), 0.78f)

    private val englishLetters = listOf(
        KeyRow(c("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")),
        KeyRow(listOf(gap(0.45f)) + c("a", "s", "d", "f", "g", "h", "j", "k", "l") + gap(0.45f)),
        KeyRow(listOf(shift(1.35f), gap(0.2f)) + c("z", "x", "c", "v", "b", "n", "m") + listOf(gap(0.2f), back(1.55f)))
    )

    private val russianLetters = listOf(
        KeyRow(c("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х")),
        KeyRow(c("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э")),
        KeyRow(listOf(shift(1.3f)) + c("я", "ч", "с", "м", "и", "т", "ь", "б", "ю") + back(1.5f))
    )

    private val symbols1 = listOf(
        KeyRow(c("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")),
        KeyRow(c("@", "#", "₽", "_", "&", "-", "+", "(", ")", "/")),
        KeyRow(listOf(KeyModel("=\\<", KeyType.SYMBOLS_PAGE, 1.45f)) + c("*", "\"", "'", ":", ";", "!", "?") + back(1.5f))
    )

    private val symbols2 = listOf(
        KeyRow(c("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")),
        KeyRow(c("£", "€", "$", "¥", "^", "°", "=", "{", "}", "\\")),
        KeyRow(listOf(KeyModel("?123", KeyType.SYMBOLS_PAGE, 1.45f)) + c("%", "©", "®", "™", "✓", "[", "]") + back(1.5f))
    )

    private val languageRows = java.util.concurrent.ConcurrentHashMap<KeyboardLanguage, List<KeyRow>>()

    private fun lettersFor(lang: KeyboardLanguage): List<KeyRow> = when (lang.id) {
        "ru" -> if (lang.fromPlugin == null) russianLetters else Languages.buildRows(lang)
        "en" -> if (lang.fromPlugin == null) englishLetters else Languages.buildRows(lang)
        else -> languageRows.getOrPut(lang) { Languages.buildRows(lang) }
    }

    fun rows(
        lang: KeyboardLanguage,
        page: Int,
        withNumbers: Boolean,
        globe: Boolean = true,
        emoji: Boolean = true,
        punct: Boolean = true,
        languages: List<String> = emptyList()
    ): List<KeyRow> {
        // раскладка из плагина: без {space} заменяет только верхние ряды, с {space} — всю клавиатуру
        val custom = PluginRepository.layouts[layoutId(lang, page)]
        if (custom != null && custom.any { r -> r.keys.any { it.type == KeyType.SPACE } }) {
            return custom.map { r ->
                KeyRow(r.keys.map {
                    when (it.type) {
                        KeyType.SPACE -> it.copy(label = lang.spaceLabel)
                        KeyType.GLOBE -> it.copy(extraVariants = if (languages.size > 1) languages else emptyList())
                        else -> it
                    }
                }, r.heightWeight)
            }
        }
        return when (page) {
            1 -> (custom ?: symbols1) + bottom("ABC", lang.spaceLabel, false, globe, emoji, punct, languages)
            2 -> (custom ?: symbols2) + bottom("ABC", lang.spaceLabel, false, globe, emoji, punct, languages)
            else -> {
                val letters = custom ?: lettersFor(lang)
                val base = letters + bottom("?123", lang.spaceLabel, true, globe, emoji, punct, languages)
                if (withNumbers) listOf(numberRow) + base else base
            }
        }
    }

    fun layoutId(lang: KeyboardLanguage, page: Int): String = when (page) {
        1 -> "symbols"
        2 -> "symbols2"
        else -> lang.id
    }

    /**
     * Ряд из плагина. Строка — обычная клавиша; "{shift}", "{backspace:1.5}", "{space}", "{enter}", "{symbols}",
     * "{page}", "{globe}", "{emoji}", "{gap:0.5}" — служебные; объект {"label","output","weight","variants"} — своя клавиша.
     */
    fun parseRow(arr: JSONArray, punctuationOnMode: Boolean, pluginId: String = ""): KeyRow {
        val keys = ArrayList<KeyModel>()
        var height = 1f
        for (i in 0 until min(arr.length(), 20)) {
            val o = arr.opt(i)
            if (o is JSONObject) {
                if (o.has("rowHeight")) {
                    height = o.optDouble("rowHeight", 1.0).toFloat().coerceIn(0.5f, 1.6f)
                    continue
                }
                val label = o.optString("label").take(12)
                if (label.isEmpty()) continue
                val vars = o.optJSONArray("variants")?.let { a -> (0 until min(a.length(), 12)).map { a.optString(it) }.filter { it.isNotEmpty() } }
                keys += KeyModel(
                    label = label,
                    weight = o.optDouble("weight", 1.0).toFloat().coerceIn(0.2f, 8f),
                    output = o.optString("output").takeIf { o.has("output") }?.take(200),
                    customVariants = vars?.takeIf { it.isNotEmpty() },
                    action = o.optString("action").trim().takeIf { it.isNotEmpty() && pluginId.isNotEmpty() }?.let { "$pluginId/$it" }
                )
                continue
            }
            val s = o?.toString() ?: continue
            if (s.isEmpty()) continue
            val m = Regex("^\\{([a-z]+)(?::([0-9.]+))?\\}$").find(s)
            if (m == null) {
                keys += KeyModel(s.take(12))
                continue
            }
            val w = m.groupValues[2].toFloatOrNull()?.coerceIn(0.1f, 8f)
            keys += when (m.groupValues[1]) {
                "shift" -> shift(w ?: 1.3f)
                "backspace" -> back(w ?: 1.5f)
                "space" -> KeyModel("", KeyType.SPACE, w ?: 4f)
                "enter" -> KeyModel("enter", KeyType.ENTER, w ?: 1.8f)
                "symbols" -> KeyModel("?123", KeyType.MODE_SYMBOLS, w ?: 1.5f, if (punctuationOnMode) punctuation else emptyList())
                "abc" -> KeyModel("ABC", KeyType.MODE_SYMBOLS, w ?: 1.5f)
                "page" -> KeyModel("=\\<", KeyType.SYMBOLS_PAGE, w ?: 1.45f)
                "globe" -> KeyModel("globe", KeyType.GLOBE, w ?: 1.1f)
                "emoji" -> KeyModel("emoji", KeyType.STICKERS, w ?: 1.1f)
                "gap" -> gap(w ?: 0.5f)
                else -> KeyModel(s.take(12))
            }
        }
        return KeyRow(keys, height)
    }
}

@Immutable
data class EmojiCategory(val title: String, val icon: String, val emojis: List<String>)

object EmojiCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var categories by mutableStateOf<List<EmojiCategory>>(emptyList())
        private set
    @Volatile private var started = false

    private val defs: List<Triple<String, String, List<IntRange>>> = listOf(
        Triple("Смайлы", "😀", listOf(0x1F600..0x1F64F, 0x1F910..0x1F92F, 0x1F970..0x1F97A, 0x1FAE0..0x1FAE8)),
        Triple("Люди", "👋", listOf(0x1F440..0x1F4AA, 0x1F590..0x1F596, 0x1F91A..0x1F91F, 0x1F930..0x1F93E, 0x1FAF0..0x1FAF8)),
        Triple("Природа", "🦊", listOf(0x1F400..0x1F43F, 0x1F980..0x1F9AE, 0x1F330..0x1F343, 0x1FAB0..0x1FABD)),
        Triple("Еда", "🍕", listOf(0x1F344..0x1F37F, 0x1F950..0x1F96F, 0x1F9C0..0x1F9CB, 0x1FAD0..0x1FAD9)),
        Triple("Спорт", "⚽", listOf(0x1F3A0..0x1F3CA, 0x1F3CF..0x1F3FA, 0x1F93A..0x1F94F)),
        Triple("Транспорт", "🚀", listOf(0x1F680..0x1F6C5, 0x1F300..0x1F320, 0x1F5FA..0x1F5FF)),
        Triple("Символы", "💎", listOf(0x1F4A0..0x1F53D, 0x2600..0x26FF, 0x2700..0x27BF))
    )

    fun warmUp() {
        if (started) return
        started = true
        scope.launch {
            val paint = AndroidPaint()
            val list = defs.map { (title, icon, ranges) -> EmojiCategory(title, icon, generate(ranges, paint)) }
            withContext(Dispatchers.Main) { categories = list }
        }
    }

    private fun generate(ranges: List<IntRange>, paint: AndroidPaint): List<String> {
        val out = ArrayList<String>(256)
        for (r in ranges) for (cp in r) {
            if (cp in 0x1F3FB..0x1F3FF || !Character.isDefined(cp)) continue
            val s = String(Character.toChars(cp))
            if (runCatching { paint.hasGlyph(s) }.getOrDefault(false)) out.add(s)
        }
        return out.distinct()
    }
}

object EmojiRecents {
    var items by mutableStateOf(listOf("😂", "❤️", "🔥", "✨", "👍", "🥹", "😍", "🎉"))
        private set
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString("recent_emojis", null)
        if (!raw.isNullOrBlank()) items = raw.split(",").filter { it.isNotBlank() }
    }

    fun clear(context: Context) {
        items = emptyList()
        prefs(context).edit().remove("recent_emojis").apply()
    }

    fun add(context: Context, emoji: String) {
        items = (listOf(emoji) + items.filter { it != emoji }).take(32)
        prefs(context).edit().putString("recent_emojis", items.joinToString(",")).apply()
    }
}

@Immutable
data class TenorGifResult(val id: String, val title: String, val previewUrl: String, val fullGifUrl: String, val category: String)

object GifImageLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
            .also { loader = it }
    }
}

object GifCatalog {
    val categories: List<Pair<String, String>> = listOf(
        "🔥 Топ" to "",
        "😎 Мемы" to "meme",
        "😂 Реакции" to "reaction laugh",
        "🐱 Котики" to "cute cat",
        "❤️ Эмоции" to "love hug happy"
    )

    private fun g(id: String, title: String, cat: String): TenorGifResult {
        val url = "https://media.giphy.com/media/$id/giphy.gif"
        return TenorGifResult(id, title, url, url, cat)
    }

    private val builtIn = listOf(
        g("Ju7l5y9osyymQ", "Rickroll Dance", "🔥 Топ"),
        g("3o7abKhOpu0NwenH3O", "Awesome Thumbs Up", "🔥 Топ"),
        g("l0MYt5jPR6QX5pnqM", "Party Celebration", "🔥 Топ"),
        g("26ufdipQqU2lhNA4g", "Mind Blown Galaxy", "🔥 Топ"),
        g("xT5LMHxhOfscxPfIfm", "Homer Simpson Yes", "🔥 Топ"),
        g("5GoVLqeAOo6PK", "Excited Jonah Hill", "🔥 Топ"),
        g("QMHoU66sBXqqLqYvGO", "This Is Fine Dog", "😎 Мемы"),
        g("YnkMcHgNIMW4Yfmjxr", "Stonks Up", "😎 Мемы"),
        g("a93jwI0wkWTQs", "Homer Backing Into Bushes", "😎 Мемы"),
        g("3o7527pa7qs9kCG78A", "Confused Dog", "😎 Мемы"),
        g("d3mlE7uhX8KFgEmY", "Smart Thinking", "😎 Мемы"),
        g("10UeedrT5MIfPG", "Laughing Hysterically", "😂 Реакции"),
        g("pUeXcg80cO8I8", "Michael Jackson Popcorn", "😂 Реакции"),
        g("7rj2ZgttvgomY", "Slow Clap Applause", "😂 Реакции"),
        g("JIX9t2j0ZTN9S", "Hacker Cat Typing", "🐱 Котики"),
        g("MDJ9IbxxvDUQM", "Cat Sending Love Kiss", "🐱 Котики"),
        g("VbnUQpnihPSIgIXuZv", "Computer Cat Glasses", "🐱 Котики"),
        g("3M4NpbLCTxBqU", "Warm Ghost Hug", "❤️ Эмоции"),
        g("l4pTdcifPZLpDjL1e", "Sending Big Love Hearts", "❤️ Эмоции")
    )

    fun fallback(category: String): List<TenorGifResult> =
        builtIn.filter { it.category == category }.ifEmpty { builtIn }

    suspend fun fetch(category: String, query: String, apiKey: String = ""): List<TenorGifResult> = withContext(Dispatchers.IO) {
        val key = apiKey.trim().ifBlank { GIPHY_API_KEY }
        if (key.isBlank()) return@withContext fallback(category)
        val network = runCatching {
            val k = URLEncoder.encode(key, "UTF-8")
            val endpoint = if (query.isBlank()) {
                "https://api.giphy.com/v1/gifs/trending?api_key=$k&limit=24"
            } else {
                "https://api.giphy.com/v1/gifs/search?api_key=$k&q=${URLEncoder.encode(query, "UTF-8")}&limit=24"
            }
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            val root = try {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
            val data = root.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { i ->
                val item = data.getJSONObject(i)
                val images = item.optJSONObject("images")
                val preview = images?.optJSONObject("fixed_height")?.optString("url").orEmpty()
                val full = images?.optJSONObject("original")?.optString("url") ?: preview
                if (preview.isEmpty()) null
                else TenorGifResult(item.optString("id", "g$i"), item.optString("title", "GIF"), preview, full, category)
            }
        }.getOrNull()
        if (network.isNullOrEmpty()) fallback(category) else network
    }
}

object Translator {
    suspend fun translate(text: String, from: String, to: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = text.trim()
            if (clean.isEmpty()) return@runCatching ""
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" +
                URLEncoder.encode(clean, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val root = try {
                JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
            val parts = root.getJSONArray(0)
            buildString {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONArray(i)
                    if (!p.isNull(0)) append(p.getString(0))
                }
            }
        }
    }
}
