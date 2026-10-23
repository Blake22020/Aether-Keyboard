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
import androidx.compose.material.icons.outlined.PushPin
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
// 9. ПАНЕЛИ: ПЕРЕВОДЧИК, ЭМОДЗИ, GIF, БУФЕР, ГОЛОС
// ============================================================================

internal val PanelShape = RoundedCornerShape(24.dp)

@Composable
internal fun PillButton(text: String, bg: Color, fg: Color, font: FontFamily, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
internal fun RepeatingBackspace(palette: KeyPalette, onDelete: () -> Unit) {
    val cb by rememberUpdatedState(onDelete)
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 44.dp)
            .clip(CircleShape)
            .background(palette.func)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    cb()
                    var interval = 420L
                    while (true) {
                        val ended = withTimeoutOrNull(interval) { awaitPressEnd(down.id) }
                        if (ended != null) break
                        cb()
                        interval = 55L
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(Glyph.BACKSPACE, palette.onFunc, 20.dp)
    }
}

/** Перевод с автоопределением языка; нажатие на чип — следующий язык перевода, удержание — список. */
@Composable
internal fun TranslatorBar(state: KeyboardState, actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val input = state.translatorInput
    val target = settings.translateTarget
    val targets = remember(settings.languages) {
        (Languages.enabled(settings).map { it.id } + listOf("en", "ru", "de", "fr", "es", "it", "zh", "ja", "ko", "tr", "uk", "ar"))
            .map { it.substringBefore('-') }.distinct()
    }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(input, target) {
        if (input.isBlank()) {
            state.translatorResult = ""
            state.translatorLoading = false
            return@LaunchedEffect
        }
        state.translatorLoading = true
        delay(350L)
        val r = Translator.translate(input, "auto", target)
        state.translatorResult = r.getOrElse { "" }
        state.translatorLoading = false
    }
    if (pick) {
        Row(
            Modifier.fillMaxWidth().clip(PanelShape).background(palette.panel).horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (t in targets) {
                val sel = t == target
                Box(
                    Modifier.clip(CircleShape).background(if (sel) palette.accent else palette.func)
                        .clickable { KeyboardPrefs.edit(context) { putString("translate_target", t) }; pick = false }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(t.uppercase(), color = if (sel) palette.onAccent else palette.onFunc, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(PanelShape).background(palette.panel).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.clip(CircleShape).background(palette.func)
                .combinedClickable(onLongClick = { pick = true }) {
                    val next = targets[(targets.indexOf(target) + 1).mod(targets.size)]
                    KeyboardPrefs.edit(context) { putString("translate_target", next) }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("Авто → ${target.uppercase()}", color = palette.onFunc, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (input.isEmpty()) "Печатайте — переведу" else input,
                color = if (input.isEmpty()) palette.muted else palette.onLetter,
                fontFamily = font, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val result = when {
                state.translatorLoading -> "Перевожу…"
                else -> state.translatorResult
            }
            if (result.isNotEmpty()) {
                Text(result, color = palette.accent, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (input.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { state.translatorInput = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = palette.muted, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(palette.accent).clickable { actions.insertTranslation() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, null, tint = palette.onAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun EmojiPanel(actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val cats = EmojiCatalog.categories
    val recents = EmojiRecents.items
    var tab by remember { mutableIntStateOf(0) }
    val tabs = remember(cats) { listOf("🕒") + cats.map { it.icon } }
    val list = if (tab == 0) recents else cats.getOrNull(tab - 1)?.emojis.orEmpty()
    // свой шрифт эмодзи (например, в стиле iOS или Twemoji) — только для отображения на клавиатуре
    val emojiFont = remember(settings.emojiFontVersion) { FontRepository.emoji(context, settings.emojiFontVersion) }
    Column(Modifier.fillMaxSize().clip(PanelShape).background(palette.panel)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(tabs) { i, icon ->
                val sel = i == tab
                Box(
                    Modifier.size(width = 48.dp, height = 36.dp).clip(RoundedCornerShape(if (sel) 18.dp else 12.dp))
                        .background(if (sel) palette.accentSoft else Color.Transparent).clickable { tab = i },
                    contentAlignment = Alignment.Center
                ) {
                    SlotIcon(if (i == 0) "emoji_recent" else "emoji_cat_${i - 1}", if (sel) palette.onAccentSoft else palette.onLetter, 22.dp) {
                        Text(icon, fontSize = 20.sp, fontFamily = emojiFont)
                    }
                }
            }
        }
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (cats.isEmpty()) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                else Text("Пока пусто", color = palette.muted, fontFamily = font)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)
            ) {
                gridItems(list) { e ->
                    Box(
                        Modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).clickable { actions.commitEmoji(e) },
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 27.sp, fontFamily = emojiFont) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton("ABC", palette.func, palette.onFunc, font) { actions.togglePanel(ActivePanel.KEYBOARD) }
            Spacer(Modifier.weight(1f))
            RepeatingBackspace(palette) { actions.deleteBackward() }
        }
    }
}

@Composable
internal fun GifPanel(actions: KeyboardActions, settings: KeyboardSettings, palette: KeyPalette, font: FontFamily) {
    val context = LocalContext.current
    val loader = remember { GifImageLoader.get(context) }
    var cat by remember { mutableIntStateOf(0) }
    var gifs by remember { mutableStateOf(GifCatalog.fallback(GifCatalog.categories[0].first)) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(cat, settings.giphyKey) {
        val (title, query) = GifCatalog.categories[cat]
        gifs = GifCatalog.fallback(title)
        loading = true
        gifs = GifCatalog.fetch(title, query, settings.giphyKey)
        loading = false
    }
    Column(
        modifier = Modifier.fillMaxSize().clip(PanelShape).background(palette.panel).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GifCatalog.categories.forEachIndexed { i, (label, _) ->
                val sel = i == cat
                Box(
                    Modifier.clip(CircleShape).background(if (sel) palette.accent else palette.func).clickable { cat = i }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(label, color = if (sel) palette.onAccent else palette.onFunc, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            gridItems(gifs) { gif ->
                Box(
                    Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp)).background(palette.func)
                        .clickable { actions.commitGif(gif) }
                ) {
                    AsyncImage(
                        model = gif.previewUrl,
                        imageLoader = loader,
                        contentDescription = gif.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** Нажатие — вставить, ✕ — удалить запись, 📌 (или долгое нажатие) — закрепить: закреплённые не стираются «Очистить». */
@Composable
internal fun ClipboardPanel(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val pinned = state.pinnedClips.toList()
    val items = pinned + state.clipboard.filter { it !in pinned }
    Column(
        modifier = Modifier.fillMaxSize().clip(PanelShape).background(palette.panel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // высота шапки постоянная — записи не прыгают, когда «Очистить» пропадает
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Буфер обмена", Modifier.weight(1f), color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            if (items.size > pinned.size) PillButton("Очистить", palette.func, palette.onFunc, font) { actions.clearClipboard() }
        }
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Скопируйте текст — он появится здесь", color = palette.muted, fontFamily = font)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(items, key = { it }) { t ->
                    val isPinned = t in pinned
                    Box(
                        Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(18.dp))
                            .background(if (isPinned) palette.accentSoft else palette.letter)
                            .combinedClickable(onLongClick = { actions.togglePinClip(t) }) { actions.commitText(t) }
                    ) {
                        Text(
                            t, color = if (isPinned) palette.onAccentSoft else palette.onLetter, fontFamily = font, fontSize = 14.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 34.dp)
                        )
                        Column(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).clickable { actions.removeClip(t) },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Close, "Удалить", tint = palette.muted, modifier = Modifier.size(16.dp)) }
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).clickable { actions.togglePinClip(t) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, "Закрепить",
                                    tint = if (isPinned) palette.accent else palette.muted, modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoicePanel(
    state: KeyboardState,
    actions: KeyboardActions,
    voice: VoiceInput,
    palette: KeyPalette,
    font: FontFamily,
    spec: WavySpec
) {
    val listening = voice.listening
    // вращение крутится только во время записи — в покое панель не перерисовывается каждый кадр
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(listening) {
        if (listening) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(9000, easing = LinearEasing))
            }
        } else {
            rotation.snapTo(rotation.value % 360f)
            rotation.animateTo(if (rotation.value > 180f) 360f else 0f, spring(dampingRatio = 0.6f, stiffness = 200f))
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().clip(PanelShape).background(palette.panel).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text("Голосовой ввод · ${state.language.label}", color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Box(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    val s = if (voice.listening) 1f + (voice.rms.coerceIn(0f, 10f) / 10f) * 0.18f else 1f
                    scaleX = s
                    scaleY = s
                }
                .wavyBackground(spec, if (listening) palette.accent else palette.func) { rotation.value % 360f }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.toggleVoice() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Mic, null, tint = if (listening) palette.onAccent else palette.onFunc, modifier = Modifier.size(44.dp))
        }
        Text(
            voice.partial.ifEmpty { voice.status },
            color = if (voice.partial.isNotEmpty()) palette.accent else palette.muted,
            fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        if (!voice.hasPermission()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Разрешить микрофон", palette.accent, palette.onAccent, font) { actions.openSettings() }
                PillButton("Системный ввод", palette.func, palette.onFunc, font) { actions.openVoiceFallback() }
            }
        }
    }
}

@Immutable
internal data class PluginTab(
    val key: String,
    val icon: String,
    val title: String,
    val plugin: KeyboardPlugin,
    val pack: SymbolPack?,
    val panel: ScriptPanel? = null
)

/** Кнопка на вкладке действий: декларативное действие плагина или кнопка скрипта. */
@Immutable
internal data class PluginEntry(val icon: String, val title: String, val actionIndex: Int, val buttonId: String?)

@Composable
internal fun PluginsPanel(state: KeyboardState, actions: KeyboardActions, palette: KeyPalette, font: FontFamily) {
    val active = PluginRepository.active
    val scriptButtons = ScriptHost.buttons
    val scriptPanels = ScriptHost.panels
    val tabs = remember(active, scriptButtons, scriptPanels) {
        buildList {
            for (p in active) {
                if (p.actions.isNotEmpty() || scriptButtons.any { it.pluginId == p.id }) add(PluginTab("${p.id}#a", p.icon, p.name, p, null))
                scriptPanels.filter { it.pluginId == p.id }.forEach { sp -> add(PluginTab("${p.id}#p:${sp.id}", sp.icon, sp.title, p, null, sp)) }
                p.symbols.forEachIndexed { i, s -> add(PluginTab("${p.id}#s$i", s.icon, s.title, p, s)) }
            }
        }
    }
    val selected = tabs.indexOfFirst { it.key == state.pluginTab }.coerceAtLeast(0)
    val tab = tabs.getOrNull(selected)
    Column(Modifier.fillMaxSize().clip(PanelShape).background(palette.panel)) {
        if (tab == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Icon(Icons.Rounded.Extension, null, tint = palette.muted, modifier = Modifier.size(36.dp))
                Text(
                    "Нет плагинов с действиями или символами",
                    color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                PillButton("Открыть плагины", palette.accent, palette.onAccent, font) { actions.openSettings() }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(tabs, key = { _, t -> t.key }) { i, t ->
                    val sel = i == selected
                    val corner by animateDpAsState(if (sel) 12.dp else 20.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "ptab")
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(corner))
                            .background(if (sel) palette.accent else palette.func)
                            .clickable { state.pluginTab = t.key }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecIcon(t.icon, if (sel) palette.onAccent else palette.onFunc, 18.dp)
                        Text(
                            t.title, color = if (sel) palette.onAccent else palette.onFunc, fontFamily = font,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1
                        )
                    }
                }
            }
            val pack = tab.pack
            val scriptPanel = tab.panel
            if (scriptPanel != null) {
                ScriptPanelView(scriptPanel, palette, font, Modifier.weight(1f).fillMaxWidth())
            } else if (pack != null) {
                val wide = remember(pack) { pack.items.any { it.length > 3 } }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (wide) 96.dp else 46.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gridItems(pack.items) { s ->
                        Box(
                            Modifier.height(46.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (wide) palette.letter else Color.Transparent)
                                .clickable { actions.commitSymbol(s) }.padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s, color = palette.onLetter, fontFamily = font, fontSize = if (wide) 14.sp else 24.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                val plugin = tab.plugin
                val entries = remember(plugin, scriptButtons) {
                    plugin.actions.mapIndexed { i, a -> PluginEntry(a.icon, a.title, i, null) } +
                        scriptButtons.filter { it.pluginId == plugin.id }.map { PluginEntry(it.icon, it.title, -1, it.id) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(entries) { e ->
                        val interaction = remember { MutableInteractionSource() }
                        val down by interaction.collectIsPressedAsState()
                        val corner by animateDpAsState(if (down) 26.dp else 16.dp, spring(dampingRatio = 0.55f, stiffness = 700f), label = "pact")
                        Row(
                            Modifier.height(48.dp).clip(RoundedCornerShape(corner)).background(palette.letter)
                                .clickable(interactionSource = interaction, indication = null) {
                                    if (e.buttonId != null) actions.runScriptButton(plugin.id, e.buttonId) else actions.runPluginAction(plugin.id, e.actionIndex)
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SpecIcon(e.icon, palette.onLetter, 18.dp)
                            Text(
                                e.title, color = palette.onLetter, fontFamily = font, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton("ABC", palette.func, palette.onFunc, font) { actions.togglePanel(ActivePanel.KEYBOARD) }
            Text(
                state.pluginStatus, Modifier.weight(1f), color = palette.muted, fontFamily = font, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            RepeatingBackspace(palette) { actions.deleteBackward() }
        }
    }
}
// ----------------------------------------------------------------------------- панели скриптов

/** Панель, которую рисует скрипт плагина: render() вызывается заново после kb.refresh(). */
@Composable
internal fun ScriptPanelView(panel: ScriptPanel, palette: KeyPalette, font: FontFamily, modifier: Modifier) {
    val tick = ScriptHost.renderTick
    var clicks by remember { mutableIntStateOf(0) }
    val nodes = remember(panel, tick, clicks) { ScriptHost.render(panel) }
    val onClick: (ScriptCall, Any?) -> Unit = { call, value ->
        ScriptHost.invoke(call, value)
        clicks++
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (n in nodes) ScriptNode(n, palette, font, onClick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScriptNode(n: UiNode, palette: KeyPalette, font: FontFamily, onClick: (ScriptCall, Any?) -> Unit) {
    when (n) {
        is UiNode.Text -> Text(
            n.text, color = n.color?.let { Color(it) } ?: palette.onLetter, fontFamily = font, fontSize = n.size.sp,
            fontWeight = if (n.bold) FontWeight.ExtraBold else FontWeight.Medium,
            textAlign = if (n.center) TextAlign.Center else TextAlign.Start,
            modifier = if (n.center) Modifier.fillMaxWidth() else Modifier
        )
        is UiNode.Button -> {
            val bg = if (n.accent) palette.accent else palette.letter
            val fg = if (n.accent) palette.onAccent else palette.onLetter
            Row(
                Modifier.then(if (n.wide) Modifier.fillMaxWidth() else Modifier).height(44.dp).clip(RoundedCornerShape(18.dp)).background(bg)
                    .clickable(enabled = n.action != null) { n.action?.let { onClick(it, n.value) } }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                if (n.icon.isNotEmpty()) SpecIcon(n.icon, fg, 18.dp)
                if (n.text.isNotEmpty()) Text(n.text, color = fg, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            }
        }
        is UiNode.Row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in n.children) ScriptNode(c, palette, font, onClick)
        }
        is UiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in n.children) ScriptNode(c, palette, font, onClick)
        }
        is UiNode.Grid -> FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (item in n.items) {
                Row(
                    Modifier.widthIn(min = n.minWidth.dp).height(n.height.dp).clip(RoundedCornerShape(14.dp))
                        .background(palette.letter.copy(alpha = palette.letter.alpha * 0.7f))
                        .clickable(enabled = n.action != null) { n.action?.let { onClick(it, item.value) } }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    if (item.icon.isNotEmpty()) SpecIcon(item.icon, palette.onLetter, 20.dp)
                    if (item.text.isNotEmpty()) Text(
                        item.text, color = palette.onLetter, fontFamily = font,
                        fontSize = if (item.text.length <= 2) 22.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        is UiNode.Spacer -> Spacer(Modifier.height(n.size.dp))
        is UiNode.Image -> {
            val img = remember(n.src) { IconStore.decodeDataUri(n.src) }
            if (img != null) Image(
                img, null,
                Modifier.size(n.size.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = n.action != null) { n.action?.let { onClick(it, n.value) } }
            )
        }
        is UiNode.Error -> Text("⚠ ${n.message}", color = palette.accent, fontFamily = font, fontSize = 13.sp)
    }
}
