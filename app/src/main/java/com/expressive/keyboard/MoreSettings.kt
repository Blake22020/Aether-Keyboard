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
// 16. НАСТРОЙКИ: ЯЗЫКИ И СЛОВАРИ, ИКОНКИ, ПАНЕЛЬ ИНСТРУМЕНТОВ
// ============================================================================

@Composable
internal fun LanguagesPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val enabled = Languages.enabled(settings)
    val dictVersion = Dictionaries.version
    val status = Dictionaries.status
    var query by remember { mutableStateOf("") }

    fun save(list: List<KeyboardLanguage>) =
        KeyboardPrefs.edit(context) { putString("languages", list.joinToString(",") { it.id }) }

    fun download(lang: KeyboardLanguage) {
        val code = lang.dict ?: return
        scope.launch {
            Dictionaries.download(context, code)
                .onSuccess { Toast.makeText(context, "Словарь «${lang.title}»: $it слов", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Не удалось скачать словарь: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    InfoCard(
        Icons.Rounded.Language,
        "Язык меняется кнопкой 🌐 (удержание — список языков) или свайпом по пробелу. " +
            "Подсказки и автоисправление работают по частотному словарю языка: русский и английский встроены, " +
            "остальные скачиваются (~0,3–0,8 МБ). Корейский собирает слоги сам, вьетнамский набирается по Telex (aa → â, s → тон)."
    )
    SettingsSection("Включённые языки") {
        enabled.forEachIndexed { i, lang ->
            key(dictVersion, lang.id) {
                LanguageRow(
                    lang,
                    dictState(context, lang, status),
                    trailing = {
                        IconButton(onClick = { if (i > 0) save(enabled.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "Выше")
                        }
                        IconButton(onClick = { if (i < enabled.lastIndex) save(enabled.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }, enabled = i < enabled.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Ниже")
                        }
                        IconButton(onClick = { save(enabled - lang) }, enabled = enabled.size > 1) {
                            Icon(Icons.Rounded.RemoveCircleOutline, "Убрать", tint = if (enabled.size > 1) cs.error else cs.outline)
                        }
                    },
                    onDict = { download(lang) }
                )
            }
        }
    }
    SettingsSection("Добавить язык") {
        GroupItem {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Поиск: немецкий, Deutsch, de…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true
            )
        }
        val q = query.trim().lowercase()
        Languages.all.filter { it !in enabled }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.spaceLabel.lowercase().contains(q) || it.id.startsWith(q) }
            .forEach { lang ->
                key(dictVersion, lang.id) {
                    LanguageRow(lang, dictState(context, lang, status, enabled = false), trailing = {
                        FilledTonalIconButton(onClick = {
                            save(enabled + lang)
                            if (settings.dictAutoDownload && lang.dict != null && !Dictionaries.isInstalled(context, lang.dict)) download(lang)
                        }) { Icon(Icons.Rounded.Add, "Добавить") }
                    }, onDict = null)
                }
            }
    }
    SettingsSection("Словари") {
        ToggleItem(Icons.Rounded.CloudDownload, "Скачивать словарь автоматически", "При добавлении языка и при первом наборе на нём", settings.dictAutoDownload) {
            KeyboardPrefs.edit(context) { putBoolean("dict_auto_download", it) }
        }
    }
}

private fun dictState(context: Context, lang: KeyboardLanguage, status: Map<String, String>, enabled: Boolean = true): String {
    val code = lang.dict ?: return "Без словаря — подсказки из ваших слов"
    status[code]?.let { return if (it == "загрузка…") "Словарь загружается…" else "Ошибка: $it" }
    return when {
        Dictionaries.bundled(context, code) -> "Словарь встроен"
        Dictionaries.isInstalled(context, code) -> "Словарь скачан"
        enabled -> "Словарь не скачан — нажмите, чтобы скачать"
        else -> "Есть словарь подсказок"
    }
}

@Composable
private fun LanguageRow(lang: KeyboardLanguage, state: String, trailing: @Composable RowScope.() -> Unit, onDict: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val canDownload = onDict != null && state.endsWith("скачать") || (onDict != null && state.startsWith("Ошибка"))
    GroupItem(onClick = if (canDownload) onDict else null) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Text(lang.label, color = cs.onSecondaryContainer, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(if (lang.title == lang.spaceLabel) lang.title else "${lang.title} · ${lang.spaceLabel}", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state, style = MaterialTheme.typography.bodySmall,
                    color = if (state.startsWith("Ошибка")) cs.error else if (canDownload) cs.primary else cs.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

// ----------------------------------------------------------------------- иконки

@Composable
internal fun DefaultSlotIcon(slot: String, tint: Color, size: Dp) {
    val v: ImageVector? = when (slot) {
        "back" -> Icons.AutoMirrored.Rounded.ArrowBack
        "mic" -> Icons.Outlined.Mic
        "plugins" -> Icons.Rounded.Extension
        "translate" -> Icons.Rounded.Translate
        "gif" -> Icons.Rounded.GifBox
        "clipboard" -> Icons.Rounded.ContentPaste
        "emoji" -> Icons.Rounded.EmojiEmotions
        "one_handed" -> Icons.Rounded.SwapHoriz
        "incognito" -> Icons.Rounded.VisibilityOff
        "settings" -> Icons.Rounded.Settings
        "hide" -> Icons.Rounded.KeyboardHide
        "send" -> Icons.AutoMirrored.Rounded.Send
        "search" -> Icons.Rounded.Search
        "done" -> Icons.Rounded.Check
        else -> null
    }
    when {
        v != null -> Icon(v, null, tint = tint, modifier = Modifier.size(size))
        slot == "menu" -> GlyphIcon(Glyph.GRID, tint, size)
        slot == "globe" -> GlyphIcon(Glyph.GLOBE, tint, size)
        slot == "emoji_key" -> GlyphIcon(Glyph.EMOJI, tint, size)
        slot == "shift" -> GlyphIcon(Glyph.SHIFT, tint, size)
        slot == "caps" -> GlyphIcon(Glyph.CAPS, tint, size)
        slot == "backspace" -> GlyphIcon(Glyph.BACKSPACE, tint, size)
        slot == "enter" -> GlyphIcon(Glyph.RETURN, tint, size)
        slot == "go" -> GlyphIcon(Glyph.ARROW, tint, size)
        slot == "layout_swap" -> LayoutSwapIcon(tint, size)
        slot == "emoji_recent" -> Text("🕒", fontSize = 20.sp)
        slot.startsWith("emoji_cat_") -> Text(
            listOf("😀", "👋", "🦊", "🍕", "⚽", "🚀", "💎").getOrElse(slot.removePrefix("emoji_cat_").toIntOrNull() ?: 0) { "😀" },
            fontSize = 20.sp
        )
    }
}

@Composable
internal fun IconsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IconSlot?>(null) }
    var textFor by remember { mutableStateOf<IconSlot?>(null) }
    var menuFor by remember { mutableStateOf<IconSlot?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val slot = target
        if (uri != null && slot != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { IconStore.setImage(context, slot.id, uri) }
            if (!ok) Toast.makeText(context, "Не удалось открыть картинку", Toast.LENGTH_LONG).show()
        }
        target = null
    }
    val emojiFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "emoji_check.ttf")
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("open")
                    requireNotNull(AndroidTypeface.Builder(temp).build())
                    temp.copyTo(File(context.filesDir, KeyboardPrefs.EMOJI_FONT_FILENAME), overwrite = true)
                    temp.delete()
                }.isSuccess
            }
            if (ok) KeyboardPrefs.setEmojiFont(context)
            else Toast.makeText(context, "Нужен файл шрифта .ttf с цветными эмодзи", Toast.LENGTH_LONG).show()
        }
    }

    InfoCard(
        Icons.Rounded.Brush,
        "Нажмите на иконку, чтобы заменить её своей картинкой (PNG с прозрачностью смотрится лучше всего) " +
            "или эмодзи/текстом. Картинку можно перекрашивать в цвет темы. Плагины тоже могут менять иконки."
    )
    IconStore.slots.groupBy { it.group }.forEach { (group, slots) ->
        SettingsSection(group) {
            for (slot in slots) {
                val custom = IconStore.userIcon(slot.id)
                GroupItem(onClick = { menuFor = slot }) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            SlotIcon(slot.id, cs.onPrimaryContainer, 24.dp) { DefaultSlotIcon(slot.id, cs.onPrimaryContainer, 24.dp) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(slot.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                            Text(
                                when (custom) {
                                    null -> if (IconStore.get(slot.id) != null) "Из плагина" else "Стандартная"
                                    is CustomIcon.Picture -> if (custom.tint) "Своя картинка · в цвет темы" else "Своя картинка"
                                    is CustomIcon.Label -> "Свой текст: ${custom.text}"
                                },
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                            )
                        }
                        if (custom != null) {
                            IconButton(onClick = { IconStore.reset(context, slot.id) }) { Icon(Icons.Rounded.RestartAlt, "Вернуть стандартную") }
                        }
                    }
                }
            }
        }
    }
    SettingsSection("Стиль эмодзи") {
        ActionItem(
            Icons.Rounded.EmojiEmotions,
            if (settings.emojiFontVersion != 0L) "Сменить шрифт эмодзи" else "Свой шрифт эмодзи",
            "Цветной .ttf (например, в стиле iOS или Twemoji) — эмодзи на клавиатуре будут выглядеть по-новому",
            onClick = { emojiFontLauncher.launch("*/*") },
            trailing = if (settings.emojiFontVersion != 0L) {
                { IconButton(onClick = { KeyboardPrefs.resetEmojiFont(context) }) { Icon(Icons.Rounded.DeleteOutline, "Убрать", tint = cs.error) } }
            } else null
        )
    }
    SettingsSection(null) {
        ActionItem(Icons.Rounded.RestartAlt, "Вернуть все иконки", null, onClick = { confirmReset = true })
    }

    menuFor?.let { slot ->
        val custom = IconStore.userIcon(slot.id)
        AlertDialog(
            onDismissRequest = { menuFor = null },
            icon = { SlotIcon(slot.id, cs.primary, 32.dp) { DefaultSlotIcon(slot.id, cs.primary, 32.dp) } },
            title = { Text(slot.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { target = slot; menuFor = null; imageLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Image, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Картинка из галереи")
                    }
                    FilledTonalButton(onClick = { textFor = slot; menuFor = null }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.EmojiEmotions, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Эмодзи или текст")
                    }
                    if (custom is CustomIcon.Picture) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Перекрашивать в цвет темы", Modifier.weight(1f))
                            Switch(checked = custom.tint, onCheckedChange = { IconStore.setTint(context, slot.id, it) })
                        }
                    }
                    if (custom != null) {
                        OutlinedButton(onClick = { IconStore.reset(context, slot.id); menuFor = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("Вернуть стандартную")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Готово") } }
        )
    }
    textFor?.let { slot ->
        TextInputDialog(slot.title, (IconStore.userIcon(slot.id) as? CustomIcon.Label)?.text.orEmpty(), "Эмодзи или до 6 символов", onDismiss = { textFor = null }) { v ->
            if (v.isNotBlank()) IconStore.setText(context, slot.id, v.trim()) else IconStore.reset(context, slot.id)
            textFor = null
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Вернуть все иконки?") },
            text = { Text("Свои картинки и тексты будут удалены.") },
            confirmButton = { Button(onClick = { IconStore.resetAll(context); confirmReset = false }) { Text("Вернуть") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } }
        )
    }
}

// ----------------------------------------------------------------------- панель инструментов

@Composable
internal fun ToolbarPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val catalog = Toolbar.catalog()
    val byId = catalog.associateBy { it.id }
    val hidden = settings.toolbarHiddenSet
    val order = remember(catalog, settings.toolbarOrder) {
        Toolbar.arrange(catalog.map { it.id }, settings.toolbarOrderList, hidden, includeHidden = true)
    }
    fun saveOrder(list: List<String>) = KeyboardPrefs.edit(context) { putString("toolbar_order", list.joinToString(",")) }

    InfoCard(
        Icons.Rounded.DragIndicator,
        "Порядок кнопок на панели инструментов (кнопка с сеткой слева вверху). На самой клавиатуре порядок тоже " +
            "меняется: удерживайте кнопку и перетащите её влево или вправо."
    )
    SettingsSection("Кнопки") {
        order.forEachIndexed { i, id ->
            val def = byId[id] ?: return@forEachIndexed
            val visible = id !in hidden
            GroupItem {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (visible) cs.secondaryContainer else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        val tint = if (visible) cs.onSecondaryContainer else cs.outline
                        when {
                            def.slot != null && def.icon != null -> SlotIcon(def.slot, tint, 20.dp) { Icon(def.icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
                            def.spec != null -> SpecIcon(def.spec, tint, 20.dp)
                        }
                    }
                    Text(
                        def.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        color = if (visible) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { saveOrder(order.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }, enabled = i > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Раньше")
                    }
                    IconButton(onClick = { saveOrder(order.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }, enabled = i < order.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Позже")
                    }
                    Switch(
                        checked = visible,
                        onCheckedChange = { on -> Toolbar.setHidden(context, if (on) hidden - id else hidden + id) }
                    )
                }
            }
        }
    }
    SettingsSection(null) {
        ActionItem(Icons.Rounded.RestartAlt, "Порядок по умолчанию", "Все кнопки снова видны", onClick = {
            KeyboardPrefs.edit(context) { remove("toolbar_order"); remove("toolbar_hidden") }
        })
    }
}
