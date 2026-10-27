package com.expressive.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.toArgb
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.net.HttpURLConnection
import java.net.URL
import org.mozilla.javascript.Context as Rhino
import org.mozilla.javascript.Function as JsFunction

// ============================================================================
// 14. МОДЫ: JAVASCRIPT-СКРИПТЫ ПЛАГИНОВ
// Плагин с полем "script" (или файл .js / архив .zip) получает свою песочницу JavaScript
// (ES6, без доступа к Java-классам и файлам) и объект kb: события клавиатуры, ввод и
// чтение текста, свои кнопки, панели, клавиши-команды, хранилище, сеть, таймеры, иконки.
// Каждый вызов ограничен по времени — зависший скрипт не подвесит клавиатуру.
// Полное описание API — PLUGINS.md.
// ============================================================================

/** То, что скрипты могут попросить у сервиса клавиатуры. Реализует ComposeKeyboardService. */
interface ScriptBridge {
    fun scriptCommit(text: String)
    fun scriptDelete(before: Int, after: Int)
    fun scriptTextBefore(n: Int): String
    fun scriptTextAfter(n: Int): String
    fun scriptSelected(): String
    fun scriptSetSelection(start: Int, end: Int)
    fun scriptKey(name: String)
    fun scriptLanguage(): String
    fun scriptSetLanguage(id: String): Boolean
    fun scriptShift(): String
    fun scriptSetShift(state: String)
    fun scriptField(): Map<String, Any?>
    fun scriptOpenPanel(id: String)
    fun scriptStatus(text: String)
    fun scriptVibrate(ms: Int)
    fun scriptClipboard(): List<String>
    fun scriptCopy(text: String)
    fun scriptSuggestions(words: List<String>)
    fun scriptHide()
}

@Immutable
data class ScriptButton(val pluginId: String, val id: String, val title: String, val icon: String, val toolbar: Boolean)

@Immutable
data class ScriptPanel(val pluginId: String, val id: String, val title: String, val icon: String)

/** Ссылка на функцию скрипта (обработчик нажатия в панели). */
@Stable
class ScriptCall(val pluginId: String, internal val fn: JsFunction)

/** Элементы панели, которую рисует скрипт: render() возвращает массив таких объектов. */
@Immutable
sealed interface UiNode {
    data class Text(val text: String, val size: Float, val bold: Boolean, val color: Int?, val center: Boolean) : UiNode
    data class Button(val text: String, val icon: String, val accent: Boolean, val wide: Boolean, val action: ScriptCall?, val value: Any?) : UiNode
    data class Row(val children: List<UiNode>) : UiNode
    data class Column(val children: List<UiNode>) : UiNode
    data class Grid(val items: List<GridItem>, val minWidth: Float, val height: Float, val action: ScriptCall?) : UiNode
    data class Spacer(val size: Float) : UiNode
    data class Image(val src: String, val size: Float, val action: ScriptCall?, val value: Any?) : UiNode
    data class Error(val message: String) : UiNode
}

@Immutable
data class GridItem(val text: String, val icon: String, val value: Any?)

private class SandboxFactory : ContextFactory() {
    override fun makeContext(): Rhino = super.makeContext().apply {
        @Suppress("DEPRECATION")
        optimizationLevel = -1 // на Android байт-код JVM не загрузить — только интерпретатор
        languageVersion = Rhino.VERSION_ES6
        instructionObserverThreshold = 5_000
        maximumInterpreterStackDepth = 1_000
        // никаких Java-классов из скрипта
        setClassShutter { false }
    }

    override fun observeInstructionCount(cx: Rhino, instructionCount: Int) {
        val deadline = cx.getThreadLocal(DEADLINE) as? Long ?: return
        if (SystemClock.uptimeMillis() > deadline) throw ScriptTimeout()
    }

    companion object {
        const val DEADLINE = "deadline"
    }
}

private class ScriptTimeout : Error("скрипт работает слишком долго и остановлен")

/** Функция kb.* — обычная JS-функция без доступа к Java-объекту за ней. */
private class Fn(scope: Scriptable, private val fname: String, private val body: (Rhino, Array<Any?>) -> Any?) : BaseFunction() {
    init {
        parentScope = scope
        prototype = getFunctionPrototype(scope)
    }

    override fun call(cx: Rhino, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        ScriptHost.toJs(cx, parentScope, body(cx, args))

    override fun getFunctionName(): String = fname
}

private class Mod(val plugin: KeyboardPlugin) {
    lateinit var scope: ScriptableObject
    val handlers = HashMap<String, MutableList<JsFunction>>()
    val commands = HashMap<String, JsFunction>()
    val buttons = LinkedHashMap<String, Pair<ScriptButton, JsFunction?>>()
    val panels = LinkedHashMap<String, Pair<ScriptPanel, JsFunction?>>()
    val timers = HashMap<Int, Runnable>()
    var nextTimer = 1
    /** Что мод рисует поверх клавиатуры (kb.overlay). */
    var overlay: List<OverlayItem> = emptyList()
    /** Раскладки, заданные на лету (kb.setLayout). */
    val layouts = HashMap<String, List<KeyRow>>()
    /** Оформление, заданное на лету (kb.setSkin). */
    var skin: KeyboardSkin? = null
    fun has(p: String) = p in plugin.permissions
}

object ScriptHost {
    private val factory = SandboxFactory()
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)
    private var mods: Map<String, Mod> = emptyMap()
    private var appContext: Context? = null

    @Volatile var bridge: ScriptBridge? = null

    var buttons by mutableStateOf<List<ScriptButton>>(emptyList())
        private set
    var panels by mutableStateOf<List<ScriptPanel>>(emptyList())
        private set
    /** Увеличивается по kb.refresh() — панели скриптов перерисовываются. */
    var renderTick by mutableIntStateOf(0)
        private set
    /** Журнал скриптов: console.log, ошибки и время работы. */
    val logs = mutableStateMapOf<String, List<String>>()
    /** Всё, что моды рисуют поверх клавиатуры. */
    var overlayItems by mutableStateOf<List<OverlayItem>>(emptyList())
        private set
    /** Есть подписчики на "frame" — клавиатура шлёт кадры анимации. */
    var wantsFrames by mutableStateOf(false)
        private set
    /** Раскладки из kb.setLayout: важнее раскладок из манифестов плагинов. */
    var layoutOverrides by mutableStateOf<Map<String, List<KeyRow>>>(emptyMap())
        private set
    /** Оформление из kb.setSkin: важнее выбранного в настройках. */
    var skinOverride by mutableStateOf<KeyboardSkin?>(null)
        private set

    private const val CALL_BUDGET_MS = 300L
    private const val LOAD_BUDGET_MS = 2000L
    private const val MAX_TIMERS = 64
    /** События, которые показывают набираемый текст или буфер, приходят только плагинам с разрешением. */
    private val EVENT_PERMS = mapOf(
        "key" to "read_text", "word" to "read_text", "suggest" to "read_text", "start" to "read_text", "press" to "read_text",
        "clipboard" to "clipboard"
    )

    fun log(pluginId: String, line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logs[pluginId] = ((logs[pluginId] ?: emptyList()) + "$t  $line").takeLast(80)
    }

    private fun describe(t: Throwable): String = when (t) {
        is RhinoException -> "${t.details()} (строка ${t.lineNumber()})"
        is ScriptTimeout -> t.message.orEmpty()
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun <T> exec(m: Mod?, budgetMs: Long, block: (Rhino) -> T): Result<T> {
        val cx = factory.enterContext()
        val before = cx.getThreadLocal(SandboxFactory.DEADLINE)
        return try {
            cx.putThreadLocal(SandboxFactory.DEADLINE, SystemClock.uptimeMillis() + budgetMs)
            Result.success(block(cx))
        } catch (t: Throwable) {
            if (m != null) log(m.plugin.id, "✕ " + describe(t))
            Result.failure(t)
        } finally {
            if (before != null) cx.putThreadLocal(SandboxFactory.DEADLINE, before) else cx.removeThreadLocal(SandboxFactory.DEADLINE)
            Rhino.exit()
        }
    }

    // ------------------------------------------------------------------ загрузка

    fun reload(context: Context, plugins: List<KeyboardPlugin>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { reload(context, plugins) }
            return
        }
        appContext = context.applicationContext
        mods.values.forEach { m -> m.timers.values.forEach(main::removeCallbacks) }
        val next = LinkedHashMap<String, Mod>()
        for (p in plugins) {
            val src = p.script ?: continue
            val m = Mod(p)
            next[p.id] = m
            val started = SystemClock.uptimeMillis()
            exec(m, LOAD_BUDGET_MS) { cx ->
                m.scope = cx.initSafeStandardObjects()
                installApi(cx, m)
                cx.evaluateString(m.scope, src, "${p.id}.js", 1, null)
            }.onSuccess { log(p.id, "✓ " + tr("загружен за {0} мс", SystemClock.uptimeMillis() - started)) }
        }
        mods = next
        publish()
        renderTick++
    }

    private fun publish() {
        buttons = mods.values.flatMap { m -> m.buttons.values.map { it.first } }
        panels = mods.values.flatMap { m -> m.panels.values.map { it.first } }
        publishOverlay()
        wantsFrames = mods.values.any { it.handlers["frame"]?.isNotEmpty() == true }
        val lay = HashMap<String, List<KeyRow>>()
        for (m in mods.values) for ((k, v) in m.layouts) lay.putIfAbsent(k, v)
        if (lay != layoutOverrides) layoutOverrides = lay
        val skin = mods.values.firstNotNullOfOrNull { it.skin }
        if (skin !== skinOverride) skinOverride = skin
    }

    private fun publishOverlay() {
        val items = mods.values.flatMap { it.overlay }
        if (items.isNotEmpty() || overlayItems.isNotEmpty()) overlayItems = items
    }

    /** Кадр анимации для подписчиков "frame": dt — миллисекунды с прошлого кадра. */
    fun frame(dt: Long) {
        emit("frame", dt.toDouble())
    }

    // ------------------------------------------------------------------ события

    fun has(event: String): Boolean = mods.values.any { it.handlers[event]?.isNotEmpty() == true }

    /** Вызывает обработчики события во всех модах. Результаты (уже в Kotlin-виде) — по порядку. */
    /** События, обработчики которых сейчас выполняются: kb.enter() внутри "enter" не вызовет "enter" снова. */
    private val running = HashSet<String>()

    fun emit(event: String, vararg args: Any?): List<Any?> {
        if (!has(event) || !running.add(event)) return emptyList()
        val out = ArrayList<Any?>()
        try {
            for (m in mods.values) {
                val hs = m.handlers[event] ?: continue
                if (EVENT_PERMS[event]?.let { !m.has(it) } == true) continue
                for (h in hs.toList()) {
                    exec(m, CALL_BUDGET_MS) { cx ->
                        val jsArgs = args.map { toJs(cx, m.scope, it) }.toTypedArray()
                        fromJs(h.call(cx, m.scope, m.scope, jsArgs))
                    }.onSuccess { out += it }
                }
            }
        } finally {
            running.remove(event)
        }
        return out
    }

    /** "key": обработчик может вернуть строку (ввести её вместо буквы) или false (отменить нажатие). */
    fun filterKey(text: String): String? {
        val results = emit("key", mapOf("text" to text))
        var cur: String? = text
        for (r in results) {
            if (r == false) return null
            if (r is String) cur = r
        }
        return cur
    }

    /** true — какой-то обработчик вернул false (отменить стандартное действие). */
    fun cancelled(event: String, vararg args: Any?): Boolean = emit(event, *args).any { it == false }

    fun suggestions(word: String, previous: String): List<String> =
        emit("suggest", mapOf("word" to word, "previous" to previous))
            .flatMap { r -> (r as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) } ?: emptyList() }
            .distinct()

    fun runButton(pluginId: String, id: String) {
        val m = mods[pluginId] ?: return
        val fn = m.buttons[id]?.second ?: return
        exec(m, CALL_BUDGET_MS) { cx -> fn.call(cx, m.scope, m.scope, emptyArray()) }
    }

    /** Клавиша раскладки с "action": "pluginId/команда". */
    fun runCommand(spec: String): Boolean {
        val m = mods[spec.substringBefore('/')] ?: return false
        val fn = m.commands[spec.substringAfter('/')] ?: return false
        exec(m, CALL_BUDGET_MS) { cx -> fn.call(cx, m.scope, m.scope, emptyArray()) }
        return true
    }

    fun invoke(call: ScriptCall, vararg args: Any?) {
        val m = mods[call.pluginId] ?: return
        exec(m, CALL_BUDGET_MS) { cx ->
            call.fn.call(cx, m.scope, m.scope, args.map { toJs(cx, m.scope, it) }.toTypedArray())
        }
    }

    fun render(panel: ScriptPanel): List<UiNode> {
        val m = mods[panel.pluginId] ?: return listOf(UiNode.Error("Плагин выключен"))
        val fn = m.panels[panel.id]?.second ?: return emptyList()
        return exec(m, CALL_BUDGET_MS) { cx -> parseNodes(m, fn.call(cx, m.scope, m.scope, emptyArray())) }
            .getOrElse { listOf(UiNode.Error(describe(it))) }
    }

    // ------------------------------------------------------------------ преобразования JS ⇄ Kotlin

    internal fun toJs(cx: Rhino, scope: Scriptable, v: Any?): Any? = when (v) {
        null -> null
        is Unit -> Undefined.instance
        is String, is Boolean, is Double -> v
        is Number -> v.toDouble()
        is Scriptable -> v
        is List<*> -> cx.newArray(scope, v.map { toJs(cx, scope, it) }.toTypedArray())
        is Map<*, *> -> cx.newObject(scope).also { o -> v.forEach { (k, x) -> ScriptableObject.putProperty(o, k.toString(), toJs(cx, scope, x)) } }
        else -> v.toString()
    }

    internal fun fromJs(v: Any?, depth: Int = 0): Any? = when {
        v == null || v is Undefined -> null
        v is CharSequence -> v.toString()
        v is Boolean -> v
        v is Number -> v.toDouble()
        v is JsFunction -> v
        // глубже 32 уровней — почти наверняка объект ссылается сам на себя
        depth > 32 -> null
        v is NativeArray -> (0 until minOf(v.length, 100_000L).toInt()).map { fromJs(v.get(it, v), depth + 1) }
        v is Scriptable -> v.ids.associate { id ->
            id.toString() to fromJs(if (id is Number) v.get(id.toInt(), v) else v.get(id.toString(), v), depth + 1)
        }
        else -> v.toString()
    }

    private fun str(a: Array<Any?>, i: Int, def: String = ""): String =
        a.getOrNull(i)?.takeIf { it !is Undefined && it != null }?.let { Rhino.toString(it) } ?: def

    private fun num(a: Array<Any?>, i: Int, def: Double): Double =
        a.getOrNull(i)?.takeIf { it !is Undefined && it != null }?.let { Rhino.toNumber(it) }?.takeIf { !it.isNaN() } ?: def

    private fun opts(a: Array<Any?>, i: Int): Map<String, Any?> = (fromJs(a.getOrNull(i)) as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    private fun need(m: Mod, perm: String) {
        if (!m.has(perm)) throw IllegalStateException("нужно разрешение \"$perm\" в поле permissions")
    }

    // ------------------------------------------------------------------ API kb.*

    private fun installApi(cx: Rhino, m: Mod) {
        val s = m.scope
        val ctx = appContext!!
        val id = m.plugin.id
        fun def(obj: Scriptable, name: String, body: (Rhino, Array<Any?>) -> Any?) =
            ScriptableObject.putProperty(obj, name, Fn(s, name, body))
        fun b() = bridge

        val kb = cx.newObject(s)
        ScriptableObject.putProperty(kb, "pluginId", id)
        ScriptableObject.putProperty(kb, "apiVersion", 2)

        // --- регистрация
        def(kb, "on") { _, a ->
            val ev = str(a, 0)
            val fn = a.getOrNull(1) as? JsFunction ?: throw IllegalArgumentException("kb.on(событие, функция)")
            m.handlers.getOrPut(ev) { ArrayList() } += fn
            if (ev == "frame") main.post { publish() }
            Unit
        }
        def(kb, "off") { _, a -> m.handlers.remove(str(a, 0)); main.post { publish() }; Unit }
        def(kb, "addButton") { _, a ->
            val o = opts(a, 0)
            val title = o["title"]?.toString() ?: throw IllegalArgumentException("addButton: нужно title")
            val bid = o["id"]?.toString() ?: title
            m.buttons[bid] = ScriptButton(id, bid, title, o["icon"]?.toString() ?: "🔘", o["toolbar"] == true) to (o["onClick"] as? JsFunction)
            main.post { publish() }
            bid
        }
        def(kb, "removeButton") { _, a -> m.buttons.remove(str(a, 0)); main.post { publish() }; Unit }
        def(kb, "addPanel") { _, a ->
            val o = opts(a, 0)
            val title = o["title"]?.toString() ?: throw IllegalArgumentException("addPanel: нужно title")
            val pid = o["id"]?.toString() ?: title
            m.panels[pid] = ScriptPanel(id, pid, title, o["icon"]?.toString() ?: m.plugin.icon) to (o["render"] as? JsFunction)
            main.post { publish() }
            pid
        }
        def(kb, "addCommand") { _, a ->
            val fn = a.getOrNull(1) as? JsFunction ?: throw IllegalArgumentException("kb.addCommand(имя, функция)")
            m.commands[str(a, 0)] = fn
            Unit
        }
        def(kb, "refresh") { _, _ -> main.post { renderTick++ }; Unit }

        // --- текст
        def(kb, "commit") { _, a -> b()?.scriptCommit(str(a, 0)); Unit }
        def(kb, "insert") { _, a -> b()?.scriptCommit(str(a, 0)); Unit }
        def(kb, "deleteBefore") { _, a -> b()?.scriptDelete(num(a, 0, 1.0).toInt().coerceIn(0, 100_000), 0); Unit }
        def(kb, "deleteAfter") { _, a -> b()?.scriptDelete(0, num(a, 0, 1.0).toInt().coerceIn(0, 100_000)); Unit }
        def(kb, "replaceBefore") { _, a ->
            b()?.scriptDelete(num(a, 0, 0.0).toInt().coerceIn(0, 100_000), 0)
            b()?.scriptCommit(str(a, 1))
            Unit
        }
        def(kb, "textBefore") { _, a -> need(m, "read_text"); b()?.scriptTextBefore(num(a, 0, 200.0).toInt().coerceIn(0, 20_000)).orEmpty() }
        def(kb, "textAfter") { _, a -> need(m, "read_text"); b()?.scriptTextAfter(num(a, 0, 200.0).toInt().coerceIn(0, 20_000)).orEmpty() }
        def(kb, "selectedText") { _, _ -> need(m, "read_text"); b()?.scriptSelected().orEmpty() }
        def(kb, "currentWord") { _, _ ->
            need(m, "read_text")
            Regex("[\\p{L}\\p{M}\\p{N}'_-]+$").find(b()?.scriptTextBefore(64).orEmpty())?.value.orEmpty()
        }
        def(kb, "setSelection") { _, a -> b()?.scriptSetSelection(num(a, 0, 0.0).toInt(), num(a, 1, num(a, 0, 0.0)).toInt()); Unit }
        def(kb, "key") { _, a -> b()?.scriptKey(str(a, 0).lowercase()); Unit }
        def(kb, "enter") { _, _ -> b()?.scriptKey("enter"); Unit }
        def(kb, "setSuggestions") { _, a ->
            b()?.scriptSuggestions((fromJs(a.getOrNull(0)) as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()); Unit
        }

        // --- состояние клавиатуры
        def(kb, "language") { _, _ -> b()?.scriptLanguage() ?: "" }
        def(kb, "setLanguage") { _, a -> b()?.scriptSetLanguage(str(a, 0)) ?: false }
        def(kb, "languages") { _, _ -> Languages.enabled(KeyboardPrefs.current(ctx)).map { it.id } }
        def(kb, "shift") { _, _ -> b()?.scriptShift() ?: "off" }
        def(kb, "setShift") { _, a -> b()?.scriptSetShift(str(a, 0)); Unit }
        def(kb, "field") { _, _ -> b()?.scriptField() ?: emptyMap<String, Any?>() }
        def(kb, "openPanel") { _, a -> b()?.scriptOpenPanel(str(a, 0, "keyboard")); Unit }
        def(kb, "closePanel") { _, _ -> b()?.scriptOpenPanel("keyboard"); Unit }
        def(kb, "hide") { _, _ -> b()?.scriptHide(); Unit }
        def(kb, "status") { _, a -> b()?.scriptStatus(str(a, 0)); Unit }
        def(kb, "toast") { _, a -> Toast.makeText(ctx, str(a, 0), Toast.LENGTH_SHORT).show(); Unit }
        def(kb, "vibrate") { _, a -> b()?.scriptVibrate(num(a, 0, 20.0).toInt().coerceIn(1, 400)); Unit }

        // --- слой поверх клавиатуры, клавиши и анимация
        def(kb, "overlay") { _, a ->
            val v = fromJs(a.getOrNull(0))
            val list = v as? List<*> ?: listOfNotNull(v)
            m.overlay = list.take(4000).mapNotNull { OverlayItem.parse(it) }
            publishOverlay()
            Unit
        }
        def(kb, "clearOverlay") { _, _ -> m.overlay = emptyList(); publishOverlay(); Unit }
        def(kb, "keyboardSize") { _, _ ->
            val sz = KeyboardGeometry.size()
            mapOf("width" to (sz?.width ?: 0f), "height" to (sz?.height ?: 0f))
        }
        def(kb, "keys") { _, _ ->
            KeyboardGeometry.visible().mapNotNull { (h, r) ->
                val name = KeyboardGeometry.nameOf(h)
                if (name.isEmpty()) null
                else mapOf(
                    "label" to name, "type" to h.key.type.name.lowercase(), "x" to r.left, "y" to r.top,
                    "w" to r.width, "h" to r.height, "cx" to r.center.x, "cy" to r.center.y
                )
            }
        }
        def(kb, "keyRect") { _, a ->
            val h = KeyboardGeometry.find(str(a, 0)) ?: return@def null
            val r = KeyboardGeometry.rectOf(h) ?: return@def null
            mapOf("x" to r.left, "y" to r.top, "w" to r.width, "h" to r.height, "cx" to r.center.x, "cy" to r.center.y)
        }
        def(kb, "pressKey") { _, a ->
            val h = KeyboardGeometry.find(str(a, 0)) ?: return@def false
            h.pulse()
            if (opts(a, 1)["type"] != false) {
                val k = h.key
                val text = when (k.type) {
                    KeyType.CHARACTER -> k.text(b()?.scriptShift().let { it != null && it != "off" })
                    KeyType.SPACE -> " "
                    else -> ""
                }
                KeyboardGeometry.actions?.commitKey(k, text)
            }
            true
        }
        def(kb, "colors") { _, _ ->
            val p = KeyboardGeometry.palette ?: return@def emptyMap<String, Any?>()
            fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%08X", c.toArgb())
            mapOf(
                "background" to hex(p.background), "key" to hex(p.letter), "keyText" to hex(p.onLetter),
                "func" to hex(p.func), "funcText" to hex(p.onFunc), "accent" to hex(p.accent), "accentText" to hex(p.onAccent)
            )
        }
        def(kb, "setLayout") { _, a ->
            val lid = str(a, 0)
            require(lid in PluginParser.LAYOUT_IDS || Regex("^[a-z]{2,3}([-_][a-z0-9]{2,8})?$").matches(lid)) { "setLayout: код языка, symbols или symbols2" }
            val rows = fromJs(a.getOrNull(1)) as? List<*>
            if (rows == null) m.layouts.remove(lid)
            else m.layouts[lid] = rows.take(8).mapNotNull { r ->
                (r as? List<*>)?.let { keys ->
                    KeyboardLayouts.parseRow(JSONArray(keys.map { x -> if (x is Map<*, *>) JSONObject(x) else x }), lid !in PluginParser.LAYOUT_IDS, id)
                }
            }.filter { it.keys.isNotEmpty() }
            publish()
            Unit
        }
        def(kb, "setSkin") { _, a ->
            val v = fromJs(a.getOrNull(0)) as? Map<*, *>
            m.skin = v?.let { SkinParser.parse(JSONObject(it)) }
            publish()
            Unit
        }

        // --- буфер обмена
        def(kb, "clipboard") { _, _ -> need(m, "clipboard"); b()?.scriptClipboard() ?: emptyList<String>() }
        def(kb, "copy") { _, a -> b()?.scriptCopy(str(a, 0)); Unit }

        // --- иконки
        def(kb, "setIcon") { _, a -> IconStore.setPluginIcon(id, str(a, 0), str(a, 1)); Unit }
        def(kb, "resetIcon") { _, a -> IconStore.setPluginIcon(id, str(a, 0), ""); Unit }

        // --- файлы из архива плагина
        def(kb, "asset") { _, a ->
            val path = str(a, 0)
            val data = m.plugin.files[path] ?: return@def null
            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".webp") || path.endsWith(".gif")) {
                "data:image/${path.substringAfterLast('.')};base64,$data"
            } else String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
        }

        // --- хранилище плагина (переживает перезапуск)
        val prefs = ctx.getSharedPreferences("plugin_storage_$id", Context.MODE_PRIVATE)
        val json = ScriptableObject.getProperty(s, "JSON") as Scriptable
        val stringify = ScriptableObject.getProperty(json, "stringify") as JsFunction
        val parse = ScriptableObject.getProperty(json, "parse") as JsFunction
        val storage = cx.newObject(s)
        def(storage, "get") { c, a ->
            val raw = prefs.getString(str(a, 0), null) ?: return@def a.getOrNull(1)
            parse.call(c, s, s, arrayOf(raw))
        }
        def(storage, "set") { c, a ->
            val raw = Rhino.toString(stringify.call(c, s, s, arrayOf(a.getOrNull(1))))
            require(raw.length < 256 * 1024) { "storage: значение больше 256 КБ" }
            prefs.edit().putString(str(a, 0), raw).apply()
            Unit
        }
        def(storage, "remove") { _, a -> prefs.edit().remove(str(a, 0)).apply(); Unit }
        def(storage, "keys") { _, _ -> prefs.all.keys.toList() }
        def(storage, "clear") { _, _ -> prefs.edit().clear().apply(); Unit }
        ScriptableObject.putProperty(kb, "storage", storage)

        // --- настройки клавиатуры
        val settings = cx.newObject(s)
        def(settings, "get") { _, a -> KeyboardPrefs.prefs(ctx).all[str(a, 0)] }
        def(settings, "set") { _, a ->
            val v = fromJs(a.getOrNull(1)) ?: return@def false
            KeyboardPrefs.applyPluginSettings(ctx, mapOf(str(a, 0) to v)) > 0
        }
        ScriptableObject.putProperty(kb, "settings", settings)

        // --- сеть
        def(kb, "http") { _, a ->
            need(m, "network")
            val o = opts(a, 0)
            val cb = a.getOrNull(1) as? JsFunction
            val url = o["url"]?.toString() ?: throw IllegalArgumentException("http: нужно url")
            require(url.startsWith("https://")) { "http: разрешены только https-адреса" }
            io.launch {
                val r = runCatching { httpCall(url, o) }
                withContext(Dispatchers.Main) {
                    if (cb == null || mods[id] !== m) return@withContext
                    exec(m, CALL_BUDGET_MS) { c ->
                        r.fold(
                            onSuccess = { (code, body) ->
                                val res = mapOf("status" to code, "text" to body, "ok" to (code in 200..299))
                                val jsRes = toJs(c, s, res) as Scriptable
                                runCatching { ScriptableObject.putProperty(jsRes, "json", parse.call(c, s, s, arrayOf(body))) }
                                cb.call(c, s, s, arrayOf(null, jsRes))
                            },
                            onFailure = { e -> cb.call(c, s, s, arrayOf(e.message ?: "ошибка сети", null)) }
                        )
                    }
                }
            }
            Unit
        }
        def(kb, "translate") { _, a ->
            need(m, "network")
            val text = str(a, 0)
            val to = str(a, 1, "en")
            val cb = a.getOrNull(2) as? JsFunction
            io.launch {
                val r = Translator.translate(text, "auto", to)
                withContext(Dispatchers.Main) {
                    if (cb == null || mods[id] !== m) return@withContext
                    exec(m, CALL_BUDGET_MS) { c -> cb.call(c, s, s, arrayOf(r.exceptionOrNull()?.message, r.getOrNull())) }
                }
            }
            Unit
        }

        // --- журнал
        val logFn: (Rhino, Array<Any?>) -> Any? = { _, a -> log(id, a.joinToString(" ") { v -> if (v is Undefined) "undefined" else Rhino.toString(v) }); Unit }
        def(kb, "log", logFn)
        val console = cx.newObject(s)
        def(console, "log", logFn)
        def(console, "warn", logFn)
        def(console, "error", logFn)
        ScriptableObject.putProperty(s, "console", console)

        // --- таймеры
        fun timer(a: Array<Any?>, repeat: Boolean): Int {
            val fn = a.getOrNull(0) as? JsFunction ?: throw IllegalArgumentException("нужна функция")
            require(m.timers.size < MAX_TIMERS) { "слишком много таймеров" }
            val delay = num(a, 1, 0.0).toLong().coerceIn(if (repeat) 50L else 0L, 24 * 3600_000L)
            val tid = m.nextTimer++
            val r = object : Runnable {
                override fun run() {
                    if (mods[id] !== m) return
                    if (!repeat) m.timers.remove(tid)
                    exec(m, CALL_BUDGET_MS) { c -> fn.call(c, s, s, emptyArray()) }
                    if (repeat && m.timers.containsKey(tid)) main.postDelayed(this, delay)
                }
            }
            m.timers[tid] = r
            main.postDelayed(r, delay)
            return tid
        }
        val clear: (Rhino, Array<Any?>) -> Any? = { _, a -> m.timers.remove(num(a, 0, -1.0).toInt())?.let(main::removeCallbacks); Unit }
        ScriptableObject.putProperty(s, "setTimeout", Fn(s, "setTimeout") { _, a -> timer(a, false) })
        ScriptableObject.putProperty(s, "setInterval", Fn(s, "setInterval") { _, a -> timer(a, true) })
        ScriptableObject.putProperty(s, "clearTimeout", Fn(s, "clearTimeout", clear))
        ScriptableObject.putProperty(s, "clearInterval", Fn(s, "clearInterval", clear))

        ScriptableObject.putProperty(s, "kb", kb)
    }

    private fun httpCall(url: String, o: Map<String, Any?>): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val method = o["method"]?.toString()?.uppercase() ?: "GET"
            conn.requestMethod = if (method in setOf("GET", "POST", "PUT", "DELETE", "PATCH")) method else "GET"
            conn.setRequestProperty("User-Agent", "ExpressiveKeyboard-Plugin")
            (o["headers"] as? Map<*, *>)?.forEach { (k, v) -> conn.setRequestProperty(k.toString(), v.toString()) }
            val body = o["body"]?.let { if (it is Map<*, *> || it is List<*>) JSONObject.wrap(it).toString() else it.toString() }
            if (body != null && conn.requestMethod != "GET") {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < 1_000_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ панели скриптов

    private fun parseNodes(m: Mod, raw: Any?): List<UiNode> {
        val list = when (val v = fromJs(raw)) {
            is List<*> -> v
            null -> emptyList<Any?>()
            else -> listOf(v)
        }
        return list.mapNotNull { node(m, it) }
    }

    private fun call(m: Mod, v: Any?) = (v as? JsFunction)?.let { ScriptCall(m.plugin.id, it) }

    private fun node(m: Mod, v: Any?): UiNode? {
        if (v is String) return UiNode.Text(v, 14f, false, null, false)
        val o = v as? Map<*, *> ?: return null
        fun s(k: String, def: String = "") = o[k]?.toString() ?: def
        fun f(k: String, def: Float) = (o[k] as? Double)?.toFloat() ?: def
        fun kids() = (o["children"] as? List<*>)?.mapNotNull { node(m, it) } ?: emptyList()
        return when (s("type", "text")) {
            "text" -> UiNode.Text(s("text"), f("size", 14f).coerceIn(8f, 40f), o["bold"] == true, PluginParser.parseColor(o["color"]?.toString()), o["center"] == true)
            "button" -> UiNode.Button(s("text"), s("icon"), o["accent"] == true, o["wide"] == true, call(m, o["onClick"]), o["value"])
            "row" -> UiNode.Row(kids())
            "column" -> UiNode.Column(kids())
            "grid" -> UiNode.Grid(
                (o["items"] as? List<*>)?.take(2000)?.map { it2 ->
                    when (it2) {
                        is Map<*, *> -> GridItem(it2["text"]?.toString().orEmpty(), it2["icon"]?.toString().orEmpty(), it2["value"] ?: it2["text"])
                        else -> GridItem(it2?.toString().orEmpty(), "", it2)
                    }
                } ?: emptyList(),
                f("minWidth", 46f).coerceIn(24f, 400f),
                f("height", 46f).coerceIn(24f, 200f),
                call(m, o["onClick"])
            )
            "spacer" -> UiNode.Spacer(f("size", 8f).coerceIn(0f, 200f))
            "image" -> UiNode.Image(s("src"), f("size", 48f).coerceIn(8f, 300f), call(m, o["onClick"]), o["value"])
            else -> UiNode.Text(s("text"), 14f, false, null, false)
        }
    }
}
