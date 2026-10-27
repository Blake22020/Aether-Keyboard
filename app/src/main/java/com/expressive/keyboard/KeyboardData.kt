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

/** EMOJI_SEARCH / GIF_SEARCH — поиск: клавиатура печатает в строку поиска. */
enum class ActivePanel { KEYBOARD, EMOJI, EMOJI_SEARCH, TRANSLATE, GIF, GIF_SEARCH, CLIPBOARD, VOICE, PLUGINS }

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

    /** Своя клавиша у пробела на буквенной раскладке: «,» и «.» с вариантами как в Gboard, остальное — из каталога. */
    private fun slotKey(symbol: String): KeyModel? {
        val s = symbol.trim().take(4)
        if (s.isEmpty()) return null
        return KeyModel(
            s, weight = 1f, customVariants = when (s) {
                "," -> COMMA_VARIANTS
                "." -> PERIOD_VARIANTS
                else -> null
            }
        )
    }

    /**
     * Нижний ряд: ?123 · 🌐 · 😀 · [своя клавиша] · пробел · [своя клавиша] · ввод;
     * на страницах символов вместо своих клавиш «,» и «.» по бокам пробела.
     * Скрытые кнопки отдают ширину пробелу.
     * Удержание 🌐 — список языков.
     */
    private fun bottom(
        mode: String, space: String, letters: Boolean, globe: Boolean, emoji: Boolean, punct: Boolean,
        languages: List<String>, leftSlot: String = "", rightSlot: String = ""
    ): KeyRow {
        // «,» и «.» — только на страницах символов (?123), на буквах — клавиши, выбранные в настройках
        val withPunct = punct && !letters
        val left = if (letters) slotKey(leftSlot) else null
        val right = if (letters) slotKey(rightSlot) else null
        val spaceWeight = 3.9f + (if (globe) 0f else 1.1f) + (if (emoji) 0f else 1.1f) - (if (withPunct) 1.6f else 0f) -
            (left?.weight ?: 0f) - (right?.weight ?: 0f)
        return KeyRow(
            buildList {
                add(KeyModel(mode, KeyType.MODE_SYMBOLS, if (withPunct) 1.35f else 1.5f, if (letters && !withPunct) punctuation else emptyList()))
                if (globe) add(KeyModel("globe", KeyType.GLOBE, if (withPunct) 1f else 1.1f, if (languages.size > 1) languages else emptyList()))
                if (emoji) add(KeyModel("emoji", KeyType.STICKERS, if (withPunct) 1f else 1.1f))
                if (withPunct) add(KeyModel(",", weight = 0.9f, customVariants = COMMA_VARIANTS))
                left?.let { add(it) }
                add(KeyModel(space, KeyType.SPACE, max(2.2f, spaceWeight)))
                right?.let { add(it) }
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
        languages: List<String> = emptyList(),
        leftSlot: String = "",
        rightSlot: String = "",
        user: Map<String, List<String>> = emptyMap()
    ): List<KeyRow> {
        // раскладка из плагина: без {space} заменяет только верхние ряды, с {space} — всю клавиатуру
        val id = layoutId(lang, page)
        val custom = ScriptHost.layoutOverrides[id] ?: PluginRepository.layouts[id]
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
        // свои места клавиш (настройки → «Свои места клавиш»)
        val order = user[id]
        return when (page) {
            1 -> UserLayouts.apply(custom ?: symbols1, order) + bottom("ABC", lang.spaceLabel, false, globe, emoji, punct, languages)
            2 -> UserLayouts.apply(custom ?: symbols2, order) + bottom("ABC", lang.spaceLabel, false, globe, emoji, punct, languages)
            else -> {
                val letters = UserLayouts.apply(custom ?: lettersFor(lang), order)
                val base = letters + bottom("?123", lang.spaceLabel, true, globe, emoji, punct, languages, leftSlot, rightSlot)
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

/** Страница результатов: гифки и «откуда продолжить» (null — дальше ничего нет). */
@Immutable
data class GifPage(val items: List<TenorGifResult>, val next: String?, val error: String? = null)

/**
 * GIF из сети. KLIPY (бесплатная замена закрытого Tenor, совместимый с Tenor API) или GIPHY — по ключу:
 * свой ключ из настроек важнее ключа, вшитого при сборке. Без ключей — встроенная подборка.
 */
object GifCatalog {
    val categories: List<Pair<String, String>> = listOf(
        "🔥 Топ" to "",
        "👋 Привет" to "hello",
        "😂 Смешно" to "lol funny",
        "❤️ Люблю" to "love",
        "👍 Да" to "yes",
        "🙅 Нет" to "no",
        "🙏 Спасибо" to "thank you",
        "😢 Грустно" to "sad",
        "😮 Вау" to "wow",
        "🎉 Праздник" to "party celebrate",
        "💃 Танец" to "dance",
        "😎 Мемы" to "meme",
        "🐱 Котики" to "cute cat",
        "🐶 Собачки" to "cute dog",
        "🤔 Хм" to "thinking",
        "😴 Сплю" to "sleepy tired"
    )

    private fun g(id: String, title: String, cat: String): TenorGifResult {
        val url = "https://media.giphy.com/media/$id/giphy.gif"
        return TenorGifResult(id, title, url, url, cat)
    }

    private val builtIn = listOf(
        g("Ju7l5y9osyymQ", "Rickroll Dance", "💃 Танец"),
        g("3o7abKhOpu0NwenH3O", "Awesome Thumbs Up", "👍 Да"),
        g("l0MYt5jPR6QX5pnqM", "Party Celebration", "🎉 Праздник"),
        g("26ufdipQqU2lhNA4g", "Mind Blown Galaxy", "😮 Вау"),
        g("xT5LMHxhOfscxPfIfm", "Homer Simpson Yes", "👍 Да"),
        g("5GoVLqeAOo6PK", "Excited Jonah Hill", "😮 Вау"),
        g("QMHoU66sBXqqLqYvGO", "This Is Fine Dog", "😎 Мемы"),
        g("YnkMcHgNIMW4Yfmjxr", "Stonks Up", "😎 Мемы"),
        g("a93jwI0wkWTQs", "Homer Backing Into Bushes", "😎 Мемы"),
        g("3o7527pa7qs9kCG78A", "Confused Dog", "🤔 Хм"),
        g("d3mlE7uhX8KFgEmY", "Smart Thinking", "🤔 Хм"),
        g("10UeedrT5MIfPG", "Laughing Hysterically", "😂 Смешно"),
        g("pUeXcg80cO8I8", "Michael Jackson Popcorn", "😂 Смешно"),
        g("7rj2ZgttvgomY", "Slow Clap Applause", "🎉 Праздник"),
        g("JIX9t2j0ZTN9S", "Hacker Cat Typing", "🐱 Котики"),
        g("MDJ9IbxxvDUQM", "Cat Sending Love Kiss", "🐱 Котики"),
        g("VbnUQpnihPSIgIXuZv", "Computer Cat Glasses", "🐱 Котики"),
        g("3M4NpbLCTxBqU", "Warm Ghost Hug", "❤️ Люблю"),
        g("l4pTdcifPZLpDjL1e", "Sending Big Love Hearts", "❤️ Люблю")
    )

    /** Встроенная подборка: по категории или по словам названия. */
    fun fallback(category: String, query: String = ""): List<TenorGifResult> {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) return builtIn.filter { g -> q.split(' ').any { it.isNotBlank() && g.title.lowercase().contains(it) } }
        return builtIn.filter { it.category == category }.ifEmpty { builtIn }
    }

    /** Ключ: свой из настроек → вшитый при сборке. */
    private fun klipyKey(user: String) = user.trim().ifBlank { BuildConfig.KLIPY_API_KEY }
    private fun giphyKey(user: String) = user.trim().ifBlank { BuildConfig.GIPHY_API_KEY }

    /** Есть ли вообще чем искать в сети. */
    fun online(klipyUser: String, giphyUser: String) = klipyKey(klipyUser).isNotBlank() || giphyKey(giphyUser).isNotBlank()

    private const val PAGE = 30

    suspend fun fetch(
        category: String,
        query: String,
        next: String?,
        klipyUser: String,
        giphyUser: String
    ): GifPage = withContext(Dispatchers.IO) {
        val klipy = klipyKey(klipyUser)
        val giphy = giphyKey(giphyUser)
        if (klipy.isBlank() && giphy.isBlank()) {
            return@withContext GifPage(if (next == null) fallback(category, query) else emptyList(), null)
        }
        val r = if (klipy.isNotBlank()) runCatching { klipy(klipy, category, query, next) } else null
        if (r != null && r.isSuccess && r.getOrThrow().items.isNotEmpty()) return@withContext r.getOrThrow()
        val g = if (giphy.isNotBlank()) runCatching { giphy(giphy, category, query, next) } else null
        if (g != null && g.isSuccess) return@withContext g.getOrThrow()
        val err = (g?.exceptionOrNull() ?: r?.exceptionOrNull())?.message ?: "нет ответа"
        GifPage(if (next == null) fallback(category, query) else emptyList(), null, err)
    }

    private fun get(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "ExpressiveKeyboard")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun locale(): String = java.util.Locale.getDefault().let { "${it.language}_${it.country.ifEmpty { it.language.uppercase() }}" }

    /** KLIPY в формате Tenor v2: results[].media_formats.{tinygif, gif…}, продолжение — поле next. */
    private fun klipy(key: String, category: String, query: String, next: String?): GifPage {
        val k = URLEncoder.encode(key, "UTF-8")
        val base = if (query.isBlank()) "https://api.klipy.com/v2/featured?key=$k"
        else "https://api.klipy.com/v2/search?key=$k&q=${URLEncoder.encode(query, "UTF-8")}"
        val url = "$base&limit=$PAGE&client_key=expressive_keyboard&contentfilter=medium&locale=${locale()}" +
            "&media_filter=tinygif,gif,mediumgif" + (next?.let { "&pos=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val root = get(url)
        val results = root.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val f = item.optJSONObject("media_formats") ?: return@mapNotNull null
            fun u(name: String) = f.optJSONObject(name)?.optString("url").orEmpty()
            val preview = u("tinygif").ifEmpty { u("nanogif") }.ifEmpty { u("gif") }
            val full = u("mediumgif").ifEmpty { u("gif") }.ifEmpty { preview }
            if (preview.isEmpty()) null
            else TenorGifResult(item.optString("id", "k$i"), item.optString("content_description", item.optString("title", "GIF")), preview, full, category)
        }
        val nxt = root.optString("next").takeIf { it.isNotBlank() && it != "0" && items.isNotEmpty() }
        return GifPage(items, nxt)
    }

    /** GIPHY: offset для продолжения, превью — fixed_width_downsampled. */
    private fun giphy(key: String, category: String, query: String, next: String?): GifPage {
        val k = URLEncoder.encode(key, "UTF-8")
        val offset = next?.toIntOrNull() ?: 0
        val lang = java.util.Locale.getDefault().language
        val url = if (query.isBlank()) "https://api.giphy.com/v1/gifs/trending?api_key=$k&limit=$PAGE&offset=$offset&rating=pg-13"
        else "https://api.giphy.com/v1/gifs/search?api_key=$k&q=${URLEncoder.encode(query, "UTF-8")}&limit=$PAGE&offset=$offset&rating=pg-13&lang=$lang"
        val root = get(url)
        val data = root.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { i ->
            val item = data.getJSONObject(i)
            val images = item.optJSONObject("images")
            val preview = images?.optJSONObject("fixed_width_downsampled")?.optString("url")
                ?: images?.optJSONObject("fixed_height")?.optString("url").orEmpty()
            val full = images?.optJSONObject("downsized")?.optString("url")
                ?: images?.optJSONObject("original")?.optString("url") ?: preview
            if (preview.isNullOrEmpty()) null
            else TenorGifResult(item.optString("id", "g$i"), item.optString("title", "GIF"), preview, full, category)
        }
        val p = root.optJSONObject("pagination")
        val total = p?.optInt("total_count", 0) ?: 0
        val end = offset + (p?.optInt("count", items.size) ?: items.size)
        return GifPage(items, if (items.isNotEmpty() && end < total && end < 4999) end.toString() else null)
    }
}

object Translator {
    /**
     * Перевод через несколько бесплатных адресов по очереди: основной адрес Google у части операторов
     * и в части стран недоступен или отвечает ошибкой на частые запросы — тогда пробуем следующий.
     */
    suspend fun translate(text: String, from: String, to: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext Result.success("")
        var last: Throwable? = null
        for (attempt in listOf(::viaGtx, ::viaDictExt, ::viaMyMemory)) {
            val r = runCatching { attempt(clean, from, to) }
            val out = r.getOrNull()?.trim()
            if (!out.isNullOrEmpty()) return@withContext Result.success(out)
            last = r.exceptionOrNull() ?: last
        }
        Result.failure(last ?: java.io.IOException("empty translation"))
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    }

    private fun HttpURLConnection.body(): String = try {
        if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode")
        inputStream.bufferedReader().use { it.readText() }
    } finally {
        disconnect()
    }

    /** Основной адрес; текст — в теле запроса, чтобы длинные фразы не упирались в длину адреса. */
    private fun viaGtx(text: String, from: String, to: String): String {
        val conn = open("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&ie=UTF-8&oe=UTF-8")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
        val parts = JSONArray(conn.body()).optJSONArray(0) ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                if (!p.isNull(0)) append(p.optString(0))
            }
        }
    }

    /** Запасной адрес Google (тот, что у расширения браузера): ответ [["перевод","язык"]] или ["перевод"]. */
    private fun viaDictExt(text: String, from: String, to: String): String {
        val raw = open(
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$from&tl=$to&q=" + URLEncoder.encode(text, "UTF-8")
        ).body()
        val root = JSONArray(raw)
        return buildString {
            for (i in 0 until root.length()) {
                when (val v = root.opt(i)) {
                    is String -> append(v)
                    is JSONArray -> append(v.optString(0))
                }
            }
        }
    }

    /** Не Google: MyMemory. Ему нужен исходный язык — угадываем по алфавиту. */
    private fun viaMyMemory(text: String, from: String, to: String): String {
        val src = if (from != "auto") from else when {
            text.any { it in 'Ѐ'..'ӿ' } -> if (text.any { it in "іїєґІЇЄҐ" }) "uk" else "ru"
            text.any { it in '一'..'鿿' } -> "zh"
            text.any { it in '぀'..'ヿ' } -> "ja"
            text.any { it in '가'..'힯' } -> "ko"
            text.any { it in '؀'..'ۿ' } -> "ar"
            else -> "en"
        }
        if (src == to) return text
        val raw = open(
            "https://api.mymemory.translated.net/get?langpair=$src%7C$to&q=" + URLEncoder.encode(text.take(480), "UTF-8")
        ).body()
        val o = JSONObject(raw)
        if (o.optInt("responseStatus", 200) != 200) return ""
        return o.optJSONObject("responseData")?.optString("translatedText").orEmpty()
    }
}
