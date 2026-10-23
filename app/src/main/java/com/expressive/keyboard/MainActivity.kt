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
// 11. ЭКРАН НАСТРОЕК В СТИЛЕ MATERIAL 3 EXPRESSIVE (ANDROID 16)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardPrefs.ensureInit(this)
        WordPredictorEngine.ensureInitialized(this)
        IconStore.ensureLoaded(this)
        PluginRepository.ensureLoaded(this)
        enableEdgeToEdge()
        setContent {
            val settings by KeyboardPrefs.observe()
            ExpressiveTheme(settings) { SettingsScreen(settings) }
        }
    }
}

enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector, val shape: ExpressiveShapePreset) {
    HOME("Expressive Keyboard", "", Icons.Rounded.Keyboard, ExpressiveShapePreset.COOKIE_12),
    LOOK("Внешний вид", "Тема, форма акцентов, шрифт и анимации", Icons.Rounded.Palette, ExpressiveShapePreset.COOKIE_12),
    COLORS("Цвета и фон", "Свой цвет, цвета клавиш, прозрачность, картинка", Icons.Rounded.ColorLens, ExpressiveShapePreset.FLOWER_6),
    KEYS("Клавиши и раскладка", "Размеры, подсказки, кнопки, режим одной руки", Icons.Rounded.Keyboard, ExpressiveShapePreset.CLOVER_4),
    ICONS("Иконки и эмодзи", "Свои иконки для любых кнопок, вкладок эмодзи и стиль эмодзи", Icons.Rounded.Brush, ExpressiveShapePreset.SUNNY_8),
    TOOLBAR("Панель инструментов", "Порядок и видимость кнопок", Icons.Rounded.DragIndicator, ExpressiveShapePreset.FLOWER_6),
    LANGUAGES("Языки и словари", "Раскладки для 64 языков, словари подсказок", Icons.Rounded.Language, ExpressiveShapePreset.COOKIE_12),
    TYPING("Ввод текста", "Автоисправление, жесты, буфер обмена, GIF", Icons.Rounded.Spellcheck, ExpressiveShapePreset.SUNNY_8),
    FEEDBACK("Вибрация и звук", "Сила, длительность и характер отклика", Icons.Rounded.Vibration, ExpressiveShapePreset.COOKIE_12),
    PLUGINS("Плагины и моды", "Скрипты, свои панели и кнопки, действия, темы", Icons.Rounded.Extension, ExpressiveShapePreset.FLOWER_6),
    BACKUP("Резервная копия", "Экспорт и импорт настроек, сброс, словарь", Icons.Rounded.Backup, ExpressiveShapePreset.CLOVER_4),
    ABOUT("О приложении", "Версия и возможности", Icons.Rounded.Info, ExpressiveShapePreset.CIRCLE)
}

internal fun isImeEnabled(context: Context): Boolean = runCatching {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)

internal fun isImeSelected(context: Context): Boolean = runCatching {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
    id.substringBefore('/') == context.packageName
}.getOrDefault(false)

internal fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

internal fun importCustomFont(context: Context, uri: Uri): String? = runCatching {
    var name = "CustomFont.ttf"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
    }
    val temp = File(context.cacheDir, "font_check.ttf")
    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return null
    if (AndroidTypeface.Builder(temp).build() == null) {
        temp.delete()
        return null
    }
    temp.copyTo(File(context.filesDir, KeyboardPrefs.CUSTOM_FONT_FILENAME), overwrite = true)
    temp.delete()
    name
}.getOrNull()

/** Картинка уменьшается до ~1600 px и пересжимается — клавиатура не держит в памяти 50-мегапиксельное фото. */
internal fun importBackground(context: Context, uri: Uri): Boolean = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val maxSide = max(bounds.outWidth, bounds.outHeight)
    if (maxSide <= 0) return false
    var sample = 1
    while (maxSide / (sample * 2) >= 1600) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return false
    File(context.filesDir, KeyboardPrefs.BG_IMAGE_FILENAME).outputStream().use {
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
    }
    bmp.recycle()
    true
}.getOrDefault(false)

@Composable
fun SettingsScreen(settings: KeyboardSettings) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    androidx.activity.compose.BackHandler(enabled = page != SettingsPage.HOME) { page = SettingsPage.HOME }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != SettingsPage.HOME
            val enterSpring = spring<IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
            if (forward) {
                (slideInHorizontally(enterSpring) { it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(enterSpring) { -it / 6 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(enterSpring) { -it / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(enterSpring) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "settingsPage"
    ) { p ->
        SettingsPageScaffold(p, settings, onNavigate = { page = it })
    }
}

@Composable
internal fun SettingsPageScaffold(page: SettingsPage, settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val cs = MaterialTheme.colorScheme
    var testText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val showTest = page != SettingsPage.ABOUT && page != SettingsPage.BACKUP
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = cs.surfaceContainer,
        topBar = {
            LargeTopAppBar(
                title = { Text(page.title, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (page != SettingsPage.HOME) {
                        FilledTonalIconButton(onClick = { onNavigate(SettingsPage.HOME) }, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = cs.surfaceContainer, scrolledContainerColor = cs.surfaceContainer),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showTest) {
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Проверьте клавиатуру здесь…") },
                    leadingIcon = { Icon(Icons.Rounded.Keyboard, null) },
                    trailingIcon = {
                        if (testText.isNotEmpty()) IconButton(onClick = { testText = "" }) { Icon(Icons.Rounded.Close, null) }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                when (page) {
                    SettingsPage.HOME -> HomePage(settings, onNavigate)
                    SettingsPage.LOOK -> LookPage(settings)
                    SettingsPage.COLORS -> ColorsPage(settings)
                    SettingsPage.KEYS -> KeysPage(settings)
                    SettingsPage.ICONS -> IconsPage(settings)
                    SettingsPage.TOOLBAR -> ToolbarPage(settings)
                    SettingsPage.LANGUAGES -> LanguagesPage(settings)
                    SettingsPage.TYPING -> TypingPage(settings)
                    SettingsPage.FEEDBACK -> FeedbackPage(settings)
                    SettingsPage.PLUGINS -> PluginsPage()
                    SettingsPage.BACKUP -> BackupPage()
                    SettingsPage.ABOUT -> AboutPage()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
internal fun rememberSpec(settings: KeyboardSettings) =
    remember(settings.shapePreset, settings.waveCount, settings.waveAmplitudePercent) { settings.accentSpec() }

@Composable
internal fun PreviewSection(settings: KeyboardSettings) {
    SettingsSection("Предпросмотр") { GroupItem { StylePreview(settings, rememberSpec(settings)) } }
}

// ----------------------------------------------------------------------- главная

@Composable
internal fun HomePage(settings: KeyboardSettings, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var imeSelected by remember { mutableStateOf(isImeSelected(context)) }
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    // окно получает фокус после возврата из системных настроек и после закрытия выбора клавиатуры
    LaunchedEffect(focused) {
        if (focused) {
            imeEnabled = isImeEnabled(context)
            imeSelected = isImeSelected(context)
            micGranted = hasMicPermission(context)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    HeroCard(
        spec = rememberSpec(settings),
        imeEnabled = imeEnabled,
        imeSelected = imeSelected,
        micGranted = micGranted,
        onEnable = {
            runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        onSelect = { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() },
        onMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    )
    PreviewSection(settings)
    val groups = listOf(
        "Оформление" to listOf(SettingsPage.LOOK, SettingsPage.COLORS, SettingsPage.KEYS, SettingsPage.ICONS, SettingsPage.TOOLBAR),
        "Работа" to listOf(SettingsPage.LANGUAGES, SettingsPage.TYPING, SettingsPage.FEEDBACK, SettingsPage.PLUGINS),
        "Прочее" to listOf(SettingsPage.BACKUP, SettingsPage.ABOUT)
    )
    var i = 0
    for ((title, pages) in groups) {
        SettingsSection(title) {
            for (p in pages) {
                val idx = i++
                CategoryTile(p, idx) { onNavigate(p) }
            }
        }
    }
}

// ----------------------------------------------------------------------- внешний вид

@Composable
internal fun LookPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val name = withContext(Dispatchers.IO) { importCustomFont(context, uri) }
            if (name != null) {
                KeyboardPrefs.saveCustomFont(context, name)
                Toast.makeText(context, "Шрифт «$name» применён", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "Нужен корректный файл .ttf или .otf", Toast.LENGTH_LONG).show()
        }
    }
    PreviewSection(settings)
    SettingsSection("Тема") {
        ChoiceItem(
            Icons.Rounded.DarkMode, "Режим", "AMOLED — чистый чёрный для OLED-экранов",
            listOf("SYSTEM" to "Авто", "LIGHT" to "Светлая", "DARK" to "Тёмная", "AMOLED" to "AMOLED"),
            settings.themeMode
        ) { mode -> KeyboardPrefs.edit(context) { putString("theme_mode", mode) } }
    }
    SettingsSection("Форма акцентов") {
        GroupItem { Column(Modifier.padding(vertical = 16.dp)) { ShapePicker(settings) } }
        SliderItem(
            icon = Icons.Rounded.AutoAwesome, title = "Количество волн",
            value = settings.waveCount.toFloat(), range = 4f..18f, steps = 13,
            format = { "${it.roundToInt()}" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putInt("wave_count", v.roundToInt()) } }
        )
        SliderItem(
            icon = Icons.Rounded.Tune, title = "Глубина волн",
            value = settings.waveAmplitudePercent, range = 0f..14f,
            format = { "${it.roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("wave_amplitude_percent", v) } }
        )
    }
    SettingsSection("Шрифт") {
        ChoiceItem(
            Icons.Rounded.TextFields, "Начертание",
            if (settings.fontStyle == "CUSTOM") "Свой шрифт: ${settings.customFontName}" else "Округлый — как на макете",
            listOf("ROUNDED" to "Округлый", "SYSTEM" to "Системный", "SERIF" to "Serif", "MONOSPACE" to "Mono"),
            settings.fontStyle
        ) { f -> KeyboardPrefs.edit(context) { putString("font_style", f) } }
        SliderItem(
            icon = Icons.Rounded.FormatBold, title = "Толщина букв",
            value = settings.fontWeight.toFloat(), range = 300f..900f, steps = 5,
            format = {
                when (it.roundToInt() / 100 * 100) {
                    300 -> "Тонкий"; 400 -> "Обычный"; 500 -> "Средний"; 600 -> "Полужирный"; 700 -> "Жирный"; 800 -> "Очень жирный"
                    else -> "Чёрный"
                }
            },
            onCommit = { v -> KeyboardPrefs.edit(context) { putInt("font_weight", (v / 100f).roundToInt() * 100) } }
        )
        SliderItem(
            icon = Icons.Rounded.FormatSize, title = "Размер букв",
            value = settings.keyLabelScale, range = 0.7f..1.4f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_label_scale", v) } }
        )
        ActionItem(
            icon = Icons.Rounded.FontDownload,
            title = "Загрузить свой шрифт",
            subtitle = "Файл .ttf или .otf",
            onClick = { fontLauncher.launch("*/*") },
            trailing = if (settings.customFontName.isNotEmpty()) {
                {
                    IconButton(onClick = { KeyboardPrefs.resetCustomFont(context) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Удалить шрифт", tint = cs.error)
                    }
                }
            } else null
        )
    }
    SettingsSection("Анимации") {
        SliderItem(
            icon = Icons.Rounded.Animation, title = "Отклик клавиш при нажатии",
            value = settings.pressAnimation, range = 0f..2f,
            format = { if (it < 0.05f) "Выкл" else "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("press_animation", if (v < 0.05f) 0f else v) } }
        )
        ToggleItem(Icons.Rounded.AutoAwesome, "Анимация панелей", "Плавные переходы между клавиатурой, эмодзи и панелями", settings.panelAnimations) {
            KeyboardPrefs.edit(context) { putBoolean("panel_animations", it) }
        }
    }
}

// ----------------------------------------------------------------------- цвета и фон

private data class ColorTarget(val key: String, val title: String)

@Composable
internal fun ColorsPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<ColorTarget?>(null) }
    val auto = remember(cs) { buildKeyPalette(cs, KeyboardSettings()) }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { importBackground(context, uri) }
            if (ok) KeyboardPrefs.setBackgroundImage(context)
            else Toast.makeText(context, "Не удалось открыть картинку", Toast.LENGTH_LONG).show()
        }
    }
    val monet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    PreviewSection(settings)
    SettingsSection("Источник цвета") {
        ChoiceItem(
            Icons.Rounded.Palette, "Палитра Material You",
            if (settings.colorSource == "SEED") "Вся тема строится из выбранного цвета"
            else if (monet) "Цвета берутся из обоев (Monet)" else "Monet доступен с Android 12 — используется базовая палитра",
            listOf("DYNAMIC" to "Из обоев", "SEED" to "Свой цвет"),
            settings.colorSource
        ) { v -> KeyboardPrefs.edit(context) { putString("color_source", v) } }
        AnimatedVisibility(
            visible = settings.colorSource == "SEED",
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            GroupItem {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { c ->
                                ColorDot(Color(c), settings.seedColor == c) { KeyboardPrefs.edit(context) { putInt("seed_color", c) } }
                            }
                        }
                    }
                    FilledTonalButton(onClick = { picker = ColorTarget("seed_color", "Основной цвет") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Выбрать любой цвет")
                    }
                }
            }
        }
    }
    SettingsSection("Цвета клавиатуры") {
        ColorRow("Фон клавиатуры", settings.colorBackground, auto.background) { picker = ColorTarget("color_background", "Фон клавиатуры") }
        ColorRow("Клавиши букв", settings.colorKey, auto.letter) { picker = ColorTarget("color_key", "Клавиши букв") }
        ColorRow("Текст на буквах", settings.colorKeyText, auto.onLetter) { picker = ColorTarget("color_key_text", "Текст на буквах") }
        ColorRow("Служебные клавиши", settings.colorFunc, auto.func) { picker = ColorTarget("color_func", "Служебные клавиши") }
        ColorRow("Текст служебных", settings.colorFuncText, auto.onFunc) { picker = ColorTarget("color_func_text", "Текст служебных") }
        ColorRow("Акцент (Enter, нажатие)", settings.colorAccent, auto.accent) { picker = ColorTarget("color_accent", "Акцент") }
        ColorRow("Текст на акценте", settings.colorAccentText, auto.onAccent) { picker = ColorTarget("color_accent_text", "Текст на акценте") }
        SliderItem(
            icon = Icons.Rounded.Opacity, title = "Непрозрачность клавиш",
            value = settings.keyOpacity, range = 0.2f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_opacity", v) } }
        )
        ActionItem(Icons.Rounded.RestartAlt, "Вернуть цвета темы", "Сбросить ручные цвета и прозрачность", onClick = { KeyboardPrefs.resetColors(context) })
    }
    SettingsSection("Фоновая картинка") {
        ActionItem(
            icon = Icons.Rounded.Wallpaper,
            title = if (settings.hasBackgroundImage) "Сменить картинку" else "Выбрать картинку",
            subtitle = "Фото из галереи под клавишами",
            onClick = { bgLauncher.launch("image/*") },
            trailing = if (settings.hasBackgroundImage) {
                {
                    IconButton(onClick = { KeyboardPrefs.clearBackgroundImage(context) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Убрать картинку", tint = cs.error)
                    }
                }
            } else null
        )
        AnimatedVisibility(settings.hasBackgroundImage) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SliderItem(
                    icon = Icons.Rounded.Brightness6, title = "Затемнение",
                    value = settings.bgDim, range = 0f..0.9f,
                    format = { "${(it * 100).roundToInt()}%" },
                    onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bg_dim", v) } }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SliderItem(
                        icon = Icons.Rounded.BlurOn, title = "Размытие",
                        value = settings.bgBlurDp, range = 0f..24f,
                        format = { if (it < 0.5f) "Нет" else "${it.roundToInt()} dp" },
                        onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bg_blur_dp", v) } }
                    )
                }
            }
        }
    }

    picker?.let { t ->
        val current = when (t.key) {
            "seed_color" -> settings.seedColor
            "color_background" -> settings.colorBackground
            "color_key" -> settings.colorKey
            "color_key_text" -> settings.colorKeyText
            "color_func" -> settings.colorFunc
            "color_func_text" -> settings.colorFuncText
            "color_accent" -> settings.colorAccent
            else -> settings.colorAccentText
        }
        ColorPickerDialog(t.title, current, allowAuto = t.key != "seed_color", onDismiss = { picker = null }) { c ->
            KeyboardPrefs.edit(context) {
                putInt(t.key, c)
                if (t.key == "seed_color") putString("color_source", "SEED")
            }
            picker = null
        }
    }
}

// ----------------------------------------------------------------------- клавиши

@Composable
internal fun KeysPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    PreviewSection(settings)
    SettingsSection("Размеры") {
        SliderItem(
            icon = Icons.Rounded.Height, title = "Высота клавиш",
            value = settings.keyHeightDp, range = 44f..68f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_height_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.RoundedCorner, title = "Скругление клавиш",
            value = settings.cornerRadiusDp, range = 6f..30f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("corner_radius_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.SpaceBar, title = "Промежуток между клавишами",
            value = settings.keySpacingDp, range = 2f..8f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("key_spacing_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Menu, title = "Промежуток между рядами",
            value = settings.rowSpacingDp, range = 4f..12f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("row_spacing_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.Straighten, title = "Отступы по бокам",
            value = settings.sidePaddingDp, range = 0f..32f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("side_padding_dp", v) } }
        )
        SliderItem(
            icon = Icons.Rounded.VerticalAlignBottom, title = "Отступ снизу",
            value = settings.bottomOffsetDp, range = 0f..64f,
            format = { "${it.roundToInt()} dp" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("bottom_offset_dp", v) } }
        )
    }
    SettingsSection("Режим одной руки") {
        ChoiceItem(
            Icons.Rounded.SwapHoriz, "Положение", "Также включается кнопкой ⇄ на панели инструментов",
            listOf("OFF" to "Выкл", "LEFT" to "Слева", "RIGHT" to "Справа"), settings.oneHanded
        ) { v -> KeyboardPrefs.edit(context) { putString("one_handed", v) } }
        SliderItem(
            icon = Icons.Rounded.Straighten, title = "Ширина клавиатуры",
            value = settings.oneHandedWidth, range = 0.65f..0.95f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("one_handed_width", v) } }
        )
    }
    SettingsSection("Клавиши и кнопки") {
        ToggleItem(Icons.Rounded.Dialpad, "Ряд цифр", null, settings.showNumberRow) {
            KeyboardPrefs.edit(context) { putBoolean("show_number_row", it) }
        }
        ToggleItem(Icons.AutoMirrored.Rounded.Label, "Подсказки на клавишах", "Маленький символ, доступный по удержанию", settings.showHints) {
            KeyboardPrefs.edit(context) { putBoolean("show_hints", it) }
        }
        ToggleItem(Icons.Rounded.TouchApp, "Всплывающая буква при нажатии", null, settings.keyPreview) {
            KeyboardPrefs.edit(context) { putBoolean("key_preview", it) }
        }
        ToggleItem(Icons.Rounded.BorderOuter, "Контур клавиш", null, settings.showKeyBorders) {
            KeyboardPrefs.edit(context) { putBoolean("show_key_borders", it) }
        }
        ToggleItem(Icons.Rounded.Language, "Кнопка смены языка", "Без неё язык меняется свайпом по пробелу. Удержание — список языков", settings.showGlobeKey) {
            KeyboardPrefs.edit(context) { putBoolean("show_globe_key", it) }
        }
        ToggleItem(Icons.Rounded.MoreHoriz, "Запятая и точка на странице ?123", "По бокам пробела. Удержание — другие знаки: ; : ' \" ? ! …", settings.punctuationKeys) {
            KeyboardPrefs.edit(context) { putBoolean("punctuation_keys", it) }
        }
        ToggleItem(Icons.Rounded.EmojiEmotions, "Кнопка эмодзи", null, settings.showEmojiKey) {
            KeyboardPrefs.edit(context) { putBoolean("show_emoji_key", it) }
        }
        ToggleItem(Icons.Outlined.Mic, "Кнопка микрофона", null, settings.showMicButton) {
            KeyboardPrefs.edit(context) { putBoolean("show_mic_button", it) }
        }
        ToggleItem(Icons.Rounded.Lightbulb, "Строка подсказок", "Если выключить, наверху всегда будут инструменты", settings.showSuggestions) {
            KeyboardPrefs.edit(context) { putBoolean("show_suggestions", it) }
        }
    }
}

// ----------------------------------------------------------------------- ввод

@Composable
internal fun TypingPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    var giphyDialog by remember { mutableStateOf(false) }
    SettingsSection("Скорость") {
        ToggleItem(Icons.Rounded.Bolt, "Мгновенный ввод", "Буква появляется в момент касания, а не при отпускании пальца", settings.instantInput) {
            KeyboardPrefs.edit(context) { putBoolean("instant_input", it) }
        }
    }
    SettingsSection("Печать без отрыва пальца") {
        ToggleItem(Icons.Rounded.Gesture, "Свайп-набор", "Ведите палец от буквы к букве — слово вставится само, ещё варианты — в подсказках", settings.glideTyping) {
            KeyboardPrefs.edit(context) { putBoolean("glide_typing", it) }
        }
        AnimatedVisibility(settings.glideTyping) {
            ToggleItem(Icons.Rounded.Timeline, "След за пальцем", null, settings.glideTrail) {
                KeyboardPrefs.edit(context) { putBoolean("glide_trail", it) }
            }
        }
    }
    SettingsSection("Мелочи") {
        ToggleItem(Icons.Rounded.North, "Свайп вверх по клавише", "Вводит маленький символ-подсказку без удержания", settings.swipeUpHint) {
            KeyboardPrefs.edit(context) { putBoolean("swipe_up_hint", it) }
        }
        ToggleItem(Icons.Rounded.SpaceBar, "Удержание пробела", "Открывает выбор клавиатуры", settings.spaceLongPress) {
            KeyboardPrefs.edit(context) { putBoolean("space_long_press", it) }
        }
        ToggleItem(Icons.Rounded.DataArray, "Парные скобки", "( [ { « сразу закрываются, курсор встаёт внутрь", settings.autoPair) {
            KeyboardPrefs.edit(context) { putBoolean("auto_pair", it) }
        }
        ToggleItem(Icons.Rounded.VisibilityOff, "Инкогнито", "Не учить новые слова и не запоминать буфер. Кнопка есть и на панели инструментов", settings.incognito) {
            KeyboardPrefs.edit(context) { putBoolean("incognito", it) }
        }
    }
    SettingsSection("Исправления") {
        ToggleItem(
            Icons.Rounded.Spellcheck, "Автоисправление",
            "Исправляет опечатки по пробелу, только если слова нет в словаре. ⌫ сразу после — отмена, и слово запоминается",
            settings.autoCorrect
        ) {
            KeyboardPrefs.edit(context) { putBoolean("auto_correct_enabled", it) }
        }
        AnimatedVisibility(settings.autoCorrect) {
            ChoiceItem(
                Icons.Rounded.Tune, "Насколько смело исправлять",
                if (settings.autoCorrectStrength == "NORMAL") "Исправляет и неоднозначные опечатки — как Gboard"
                else "Только явные опечатки: соседняя клавиша, пропущенная или лишняя буква",
                listOf("SAFE" to "Осторожно", "NORMAL" to "Активно"), settings.autoCorrectStrength
            ) { v -> KeyboardPrefs.edit(context) { putString("auto_correct_strength", v) } }
        }
        ToggleItem(Icons.Rounded.MoreHoriz, "Знаки после пробела", "«слово ,» → «слово,» — пробел переезжает за запятую или точку", settings.smartPunctuation) {
            KeyboardPrefs.edit(context) { putBoolean("smart_punctuation", it) }
        }
        ToggleItem(Icons.Rounded.KeyboardCapslock, "Заглавная в начале предложения", null, settings.autoCapitalize) {
            KeyboardPrefs.edit(context) { putBoolean("auto_capitalize", it) }
        }
        ToggleItem(Icons.Rounded.MoreHoriz, "Точка двойным пробелом", null, settings.doubleSpacePeriod) {
            KeyboardPrefs.edit(context) { putBoolean("double_space_period", it) }
        }
    }
    SettingsSection("Жесты") {
        ChoiceItem(
            Icons.Rounded.Swipe, "Свайп по пробелу",
            when (settings.spaceSwipe) {
                "CURSOR" -> "Ведите палец по пробелу — курсор двигается"
                "LANGUAGE" -> "Свайп меняет язык"
                else -> "Жест выключен"
            },
            listOf("CURSOR" to "Курсор", "LANGUAGE" to "Язык", "OFF" to "Выкл"), settings.spaceSwipe
        ) { v -> KeyboardPrefs.edit(context) { putString("space_swipe", v) } }
        ToggleItem(Icons.AutoMirrored.Rounded.Backspace, "Свайп влево по ⌫", "Удаляет целые слова", settings.backspaceSwipe) {
            KeyboardPrefs.edit(context) { putBoolean("backspace_swipe", it) }
        }
        SliderItem(
            icon = Icons.Rounded.Timer, title = "Задержка удержания",
            value = settings.longPressMs, range = 180f..700f,
            format = { "${it.roundToInt()} мс" },
            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("long_press_ms", v) } }
        )
    }
    SettingsSection("Буфер обмена") {
        ToggleItem(Icons.Rounded.ContentPaste, "История буфера", "Хранится только в памяти, пароли пропускаются", settings.clipboardHistory) {
            KeyboardPrefs.edit(context) { putBoolean("clipboard_history", it) }
        }
        AnimatedVisibility(settings.clipboardHistory) {
            SliderItem(
                icon = Icons.Rounded.History, title = "Сколько хранить",
                value = settings.clipboardSize.toFloat(), range = 5f..100f,
                format = { "${it.roundToInt()}" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putInt("clipboard_size", v.roundToInt()) } }
            )
        }
    }
    SettingsSection("GIF") {
        ActionItem(
            Icons.Rounded.GifBox, "Ключ GIPHY API",
            if (settings.giphyKey.isBlank()) "Не задан — показывается встроенная подборка" else "Задан — тренды и поиск по категориям",
            onClick = { giphyDialog = true }
        )
    }
    if (giphyDialog) {
        TextInputDialog("Ключ GIPHY API", settings.giphyKey, "Ключ с developers.giphy.com", onDismiss = { giphyDialog = false }) { v ->
            KeyboardPrefs.edit(context) { putString("giphy_key", v) }
            giphyDialog = false
        }
    }
}

// ----------------------------------------------------------------------- вибрация

@Composable
internal fun FeedbackPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    SettingsSection("Вибрация") {
        ToggleItem(Icons.Rounded.Vibration, "Вибрация при нажатии", null, settings.hapticsEnabled) {
            KeyboardPrefs.edit(context) { putBoolean("haptics_enabled", it) }
            if (it) HapticsEngine.preview(context, HapticMode.fromId(settings.hapticMode), settings.vibrationMs, settings.vibrationAmplitude)
        }
        AnimatedVisibility(
            visible = settings.hapticsEnabled,
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ChoiceItem(
                    Icons.Rounded.TouchApp, "Характер отклика", null,
                    HapticMode.entries.map { it.id to it.title }, settings.hapticMode
                ) { id ->
                    KeyboardPrefs.edit(context) { putString("haptic_mode", id) }
                    HapticsEngine.preview(context, HapticMode.fromId(id), settings.vibrationMs, settings.vibrationAmplitude)
                }
                AnimatedVisibility(visible = settings.hapticMode == HapticMode.CUSTOM.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SliderItem(
                            icon = Icons.Rounded.Timer, title = "Длительность",
                            value = settings.vibrationMs, range = 1f..80f,
                            format = { "${it.roundToInt()} мс" },
                            onDrag = { v -> HapticsEngine.preview(context, HapticMode.CUSTOM, v, settings.vibrationAmplitude) },
                            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("vibration_ms", v) } }
                        )
                        SliderItem(
                            icon = Icons.Rounded.Bolt, title = "Сила",
                            value = settings.vibrationAmplitude, range = 1f..255f,
                            format = { "${(it / 2.55f).roundToInt()}%" },
                            onDrag = { v -> HapticsEngine.preview(context, HapticMode.CUSTOM, settings.vibrationMs, v) },
                            onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("vibration_amplitude", v) } }
                        )
                    }
                }
                ToggleItem(Icons.Rounded.Keyboard, "Сильнее на Shift, Enter, пробеле", null, settings.strongSpecialKeys) {
                    KeyboardPrefs.edit(context) { putBoolean("strong_special_keys", it) }
                }
                ToggleItem(Icons.Rounded.SwapVert, "Отклик при отпускании", null, settings.vibrateOnRelease) {
                    KeyboardPrefs.edit(context) { putBoolean("vibrate_on_release", it) }
                }
            }
        }
    }
    SettingsSection("Звук") {
        ToggleItem(Icons.Rounded.MusicNote, "Звук нажатия", null, settings.soundEnabled) {
            KeyboardPrefs.edit(context) { putBoolean("sound_enabled", it) }
        }
        AnimatedVisibility(settings.soundEnabled) {
            SliderItem(
                icon = Icons.Rounded.GraphicEq, title = "Громкость",
                value = settings.soundVolume, range = 0.05f..1f,
                format = { "${(it * 100).roundToInt()}%" },
                onCommit = { v -> KeyboardPrefs.edit(context) { putFloat("sound_volume", v) } }
            )
        }
    }
}

// ----------------------------------------------------------------------- плагины

@Composable
internal fun PluginsPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val plugins = PluginRepository.plugins
    val errors = PluginRepository.errors
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<KeyboardPlugin?>(null) }
    var pendingNetwork by remember { mutableStateOf<KeyboardPlugin?>(null) }

    fun onImported(r: Result<KeyboardPlugin>) {
        r.onSuccess { p ->
            Toast.makeText(context, "Плагин «${p.name}» установлен", Toast.LENGTH_SHORT).show()
            expanded = p.id
            if (p.usesNetwork || "read_text" in p.permissions || "clipboard" in p.permissions) pendingNetwork = p
        }.onFailure { Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val modExampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/javascript")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.assets.open("examples/my-mod.js").use { it.readBytes() }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Пример мода сохранён — отредактируйте и импортируйте" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { onImported(withContext(Dispatchers.IO) { PluginRepository.importFrom(context, uri) }) }
    }
    val exampleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(PluginRepository.EXAMPLE.toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Пример сохранён — отредактируйте и импортируйте" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
        }
    }

    InfoCard(
        Icons.Rounded.Extension,
        "Как установить: нажмите «Импорт из файла» и выберите плагин (.json, .js или .zip) или скопируйте его текст " +
            "и нажмите «Импорт из буфера обмена». Плагин появится в списке ниже — его можно включить, выключить или удалить.\n\n" +
            "Как пользоваться: на клавиатуре нажмите кнопку с сеткой слева вверху, затем 🧩 — откроются вкладки плагинов " +
            "с их кнопками и панелями. Кнопки, отмеченные плагином, появляются прямо на панели инструментов.\n\n" +
            "Как сделать свой: сохраните пример кнопкой ниже, отредактируйте и импортируйте. Описание формата — в файле PLUGINS.md."
    )
    SettingsSection("Установка") {
        ActionItem(Icons.Rounded.FileOpen, "Импорт из файла", ".js (мод), .zip (мод с картинками) или .json", onClick = {
            importLauncher.launch(arrayOf("application/json", "application/javascript", "text/javascript", "application/zip", "text/plain", "application/octet-stream", "*/*"))
        })
        ActionItem(Icons.Rounded.Code, "Сохранить пример мода (.js)", "Своя панель, кнопка, реакция на слова и хранилище", onClick = {
            modExampleLauncher.launch("my-mod.js")
        })
        ActionItem(Icons.Rounded.ContentPaste, "Импорт из буфера обмена", "Скопируйте JSON плагина или код мода и нажмите", onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (text.isBlank()) Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            else onImported(PluginRepository.importText(context, text))
        })
        ActionItem(Icons.Rounded.Download, "Сохранить пример плагина", "Шаблон со всеми возможностями формата", onClick = {
            exampleLauncher.launch("my-plugin.json")
        })
    }
    if (errors.isNotEmpty()) {
        SettingsSection("Не загрузились") {
            errors.forEach { e ->
                GroupItem {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = cs.error)
                        Text(e, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    }
                }
            }
        }
    }
    val user = plugins.filter { !it.builtIn }
    val builtIn = plugins.filter { it.builtIn }
    if (user.isNotEmpty()) {
        SettingsSection("Мои плагины") {
            user.forEach { p ->
                PluginCard(p, expanded == p.id, onExpand = { expanded = if (expanded == p.id) null else p.id }, onDelete = { confirmDelete = p })
            }
        }
    }
    SettingsSection("Встроенные") {
        builtIn.forEach { p ->
            PluginCard(p, expanded == p.id, onExpand = { expanded = if (expanded == p.id) null else p.id }, onDelete = null)
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Удалить «${p.name}»?") },
            text = { Text("Файл плагина будет удалён. Его можно импортировать заново.") },
            confirmButton = {
                Button(onClick = {
                    PluginRepository.delete(context, p.id)
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } }
        )
    }
    pendingNetwork?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingNetwork = null },
            icon = { Icon(Icons.Rounded.Security, null) },
            title = { Text("Разрешения плагина") },
            text = {
                Text(
                    "«${p.name}» просит:\n\n" + buildList {
                        if ("read_text" in p.permissions) add("• читать набираемый текст и текст в поле")
                        if ("clipboard" in p.permissions) add("• читать историю буфера обмена")
                        if ("network" in p.permissions) add(
                            "• выходить в интернет" + p.actions.filter { it.type == PluginActionType.HTTP }
                                .joinToString("") { "\n   ${it.title}: ${Uri.parse(it.url).host}" }
                        )
                    }.joinToString("\n") + "\n\nУстанавливайте моды только из источников, которым доверяете. Оставить плагин включённым?"
                )
            },
            confirmButton = { Button(onClick = { pendingNetwork = null }) { Text("Оставить") } },
            dismissButton = {
                TextButton(onClick = {
                    PluginRepository.setEnabled(context, p.id, false)
                    pendingNetwork = null
                }) { Text("Выключить") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginCard(p: KeyboardPlugin, expanded: Boolean, onExpand: () -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val settings by KeyboardPrefs.observe()
    val enabled = PluginRepository.isEnabled(p.id) && p.id !in PluginRepository.disabled
    val spec = remember(p.id) {
        val presets = ExpressiveShapePreset.entries
        val pr = presets[(p.id.hashCode() and 0x7fffffff) % presets.size]
        WavySpec(pr.profile, pr.defaultWaves, pr.defaultAmplitude / 100f)
    }
    GroupItem(onClick = onExpand) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).wavyBackground(spec, if (enabled) cs.primaryContainer else cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { SpecIcon(p.icon, if (enabled) cs.onPrimaryContainer else cs.onSurfaceVariant, 24.dp) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.usesNetwork) Icon(Icons.Rounded.Public, "Использует сеть", tint = cs.tertiary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        listOf("v${p.version}", p.author, p.summary).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { PluginRepository.setEnabled(context, p.id, it) },
                    thumbContent = if (enabled) {
                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    val perms = p.permissions.filter { it in PluginParser.PERMISSIONS }
                    if (perms.isNotEmpty()) {
                        Text("Разрешения", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            perms.joinToString("  ·  ") {
                                when (it) {
                                    "read_text" -> "читает текст"
                                    "clipboard" -> "буфер обмена"
                                    else -> "интернет"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall, color = cs.tertiary
                        )
                    }
                    if (p.actions.isNotEmpty()) {
                        Text("Действия", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.actions.joinToString("  ·  ") { if (it.icon.startsWith("@") || it.icon.startsWith("data:")) it.title else "${it.icon} ${it.title}" },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (p.script != null) {
                        val log = ScriptHost.logs[p.id].orEmpty()
                        Text("Журнал скрипта", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            if (log.isEmpty()) (if (enabled) "Пусто" else "Мод выключен") else log.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(10.dp)
                        )
                    }
                    if (p.shortcuts.isNotEmpty()) {
                        Text("Сокращения (по пробелу)", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.shortcuts.entries.take(12).joinToString("\n") { (k, v) -> "$k → ${v.replace("\n", "⏎").take(40)}" } +
                                if (p.shortcuts.size > 12) "\n… ещё ${p.shortcuts.size - 12}" else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                    }
                    if (p.symbols.isNotEmpty()) {
                        Text("Символы", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(p.symbols.joinToString("  ·  ") { "${it.icon} ${it.title} (${it.items.size})" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    if (p.layouts.isNotEmpty()) {
                        Text("Раскладки", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(
                            p.layouts.entries.joinToString("\n") { (id, rows) -> "$id: " + rows.joinToString(" / ") { r -> r.keys.joinToString("") { it.label.take(1) } } },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val skinOn = p.skin != null && settings.skinPlugin == p.id
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.hasLook && !skinOn) {
                            FilledTonalButton(onClick = {
                                if (!enabled) PluginRepository.setEnabled(context, p.id, true)
                                PluginRepository.applyLook(context, p)
                                Toast.makeText(context, "Оформление «${p.name}» применено", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Rounded.FormatPaint, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Применить оформление")
                            }
                        }
                        if (skinOn) {
                            OutlinedButton(onClick = { PluginRepository.removeSkin(context) }) {
                                Icon(Icons.Rounded.FormatColorReset, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Снять оформление")
                            }
                        }
                        if (onDelete != null) {
                            OutlinedButton(onClick = onDelete) {
                                Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = cs.error)
                                Spacer(Modifier.width(6.dp))
                                Text("Удалить", color = cs.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- резервная копия

@Composable
internal fun BackupPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(KeyboardPrefs.exportJson(context).toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Настройки сохранены" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Файл не открылся")
                    KeyboardPrefs.importJson(context, raw)
                }
            }
            r.onSuccess { Toast.makeText(context, "Восстановлено параметров: $it", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    SettingsSection("Настройки") {
        ActionItem(Icons.Rounded.Upload, "Экспорт настроек", "Сохранить все параметры в .json", onClick = {
            exportLauncher.launch("expressive-keyboard-settings.json")
        })
        ActionItem(Icons.Rounded.Download, "Импорт настроек", "Восстановить из файла", onClick = {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        })
        ActionItem(Icons.Rounded.RestartAlt, "Сбросить настройки", "Словарь, плагины и недавние эмодзи сохранятся", onClick = { confirm = "settings" })
    }
    SettingsSection("Данные") {
        ActionItem(Icons.Rounded.AutoFixHigh, "Очистить выученные слова", "Подсказки забудут ваши слова и пары слов", onClick = { confirm = "dict" })
        ActionItem(Icons.Rounded.History, "Очистить недавние эмодзи", null, onClick = {
            EmojiRecents.clear(context)
            Toast.makeText(context, "Готово", Toast.LENGTH_SHORT).show()
        })
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.RestartAlt, null) },
            title = { Text(if (what == "dict") "Очистить словарь?" else "Сбросить настройки?") },
            text = {
                Text(
                    if (what == "dict") "Все выученные слова будут удалены."
                    else "Размеры, цвета, тема, шрифт, фон и вибрация вернутся к значениям по умолчанию."
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (what == "dict") WordPredictorEngine.clearUserData() else KeyboardPrefs.resetAll(context)
                    confirm = null
                }) { Text(if (what == "dict") "Очистить" else "Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }
        )
    }
}

// ----------------------------------------------------------------------- о приложении

@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val spec = remember { WavySpec(ShapeProfile.LOBES, 6, 0.1f) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(cs.primaryContainer).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(96.dp).wavyBackground(spec, cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Keyboard, null, tint = cs.onPrimary, modifier = Modifier.size(44.dp))
        }
        Text("Expressive Keyboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
        Text("Версия $version · Material 3 Expressive", style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
    }
    SettingsSection("Возможности") {
        listOf(
            Icons.Rounded.Palette to "Material You: цвета из обоев или из любого своего цвета",
            Icons.Rounded.Extension to "Моды на JavaScript: свои панели, кнопки, клавиши, события и подсказки",
            Icons.Rounded.Language to "64 языка, частотные словари, хангыль и Telex",
            Icons.Rounded.Brush to "Свои иконки для любых кнопок и вкладок эмодзи",
            Icons.Rounded.Swipe to "Курсор свайпом по пробелу, удаление слов свайпом по ⌫",
            Icons.Rounded.SwapHoriz to "Режим одной руки",
            Icons.Rounded.Translate to "Переводчик, GIF, буфер обмена, голосовой ввод",
            Icons.Rounded.Spellcheck to "Подсказки и автоисправление, которые учатся вашим словам"
        ).forEach { (icon, text) ->
            GroupItem {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon)
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                }
            }
        }
    }
}
