package com.expressive.keyboard

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// СВОИ МЕСТА КЛАВИШ
// Для каждой раскладки (язык, «символы», «символы 2») хранится порядок символьных клавиш.
// Клавиша переезжает целиком — с вариантами при удержании; ширина остаётся у места.
// Вместо любой клавиши можно поставить свой символ.
// ============================================================================

object UserLayouts {
    private const val KEY = "user_layouts"

    fun parse(raw: String): Map<String, List<String>> = runCatching {
        if (raw.isBlank()) return emptyMap()
        val o = JSONObject(raw)
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).map { a.getString(it) }
        }
    }.getOrDefault(emptyMap())

    /** Символьные клавиши рядов по порядку — то, что можно переставлять. */
    fun editable(rows: List<KeyRow>): List<KeyModel> = rows.flatMap { r -> r.keys.filter { it.type == KeyType.CHARACTER } }

    /** Расставить клавиши в сохранённом порядке; если раскладка с тех пор изменилась — оставить как есть. */
    fun apply(rows: List<KeyRow>, order: List<String>?): List<KeyRow> {
        if (order.isNullOrEmpty()) return rows
        val original = editable(rows)
        if (original.size != order.size) return rows
        val pool = original.toMutableList()
        val placed = order.map { label ->
            val i = pool.indexOfFirst { it.label == label }
            if (i >= 0) pool.removeAt(i) else null
        }
        var k = 0
        return rows.map { r ->
            KeyRow(r.keys.map { key ->
                if (key.type != KeyType.CHARACTER) key
                else {
                    val idx = k++
                    // ширина — у места, клавиша — та, что сюда переставлена (или новая со своим символом)
                    (placed[idx] ?: KeyModel(order[idx])).copy(weight = key.weight)
                }
            }, r.heightWeight)
        }
    }

    fun save(context: Context, current: Map<String, List<String>>, id: String, order: List<String>?) {
        val next = current.toMutableMap()
        if (order == null) next.remove(id) else next[id] = order
        val o = JSONObject()
        next.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        KeyboardPrefs.edit(context) { putString(KEY, if (next.isEmpty()) "" else o.toString()) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LayoutEditorPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val languages = remember(settings.languages) { Languages.enabled(settings) }
    var langId by remember { mutableStateOf(languages.first().id) }
    var page by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val lang = languages.firstOrNull { it.id == langId } ?: languages.first()
    val id = KeyboardLayouts.layoutId(lang, page)
    val user = settings.userLayoutMap
    // ряды без нижнего (пробел, Enter) — их символьные клавиши и переставляем
    val rows = remember(lang, page, user) {
        KeyboardLayouts.rows(lang, page, false, user = user).dropLast(1)
    }
    val keys = remember(rows) { UserLayouts.editable(rows) }

    fun save(order: List<String>?) {
        UserLayouts.save(context, user, id, order)
        selected = null
    }

    InfoCard(
        Icons.Rounded.SwapHoriz,
        "Нажмите на две клавиши — они поменяются местами. Удерживайте клавишу, чтобы поставить на её место свой символ: " +
            "букву, цифру или знак. Варианты при удержании переезжают вместе с клавишей."
    )
    SettingsSection("Раскладка") {
        if (languages.size > 1) {
            ChoiceItem(
                Icons.Rounded.SwapHoriz, "Язык", null,
                languages.take(4).map { it.id to it.label }, langId
            ) { langId = it; selected = null }
        }
        ChoiceItem(
            Icons.Rounded.SwapHoriz, "Страница", null,
            listOf("0" to "Буквы", "1" to "Символы", "2" to "Символы 2"), page.toString()
        ) { page = it.toInt(); selected = null }
    }
    SettingsSection(lang.displayTitle()) {
        GroupItem {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var index = 0
                for (r in rows) {
                    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (key in r.keys) {
                            if (key.type != KeyType.CHARACTER) {
                                Spacer(Modifier.weight(key.weight))
                                continue
                            }
                            val i = index++
                            val sel = selected == i
                            val bg by animateColorAsState(if (sel) cs.primary else cs.surfaceContainerHighest, label = "editKey")
                            Box(
                                Modifier.weight(key.weight).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(bg)
                                    .combinedClickable(onLongClick = { editing = i }) {
                                        val s = selected
                                        when {
                                            s == null -> selected = i
                                            s == i -> selected = null
                                            else -> {
                                                val order = keys.map { it.label }.toMutableList()
                                                java.util.Collections.swap(order, s, i)
                                                save(order)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    key.label, color = if (sel) cs.onPrimary else cs.onSurface,
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        if (user.containsKey(id)) {
            ActionItem(Icons.Rounded.RestartAlt, "Вернуть как было", "Только для этой раскладки", onClick = { save(null) })
        }
    }

    val e = editing
    if (e != null && e < keys.size) {
        // старый символ выделен — набранный сразу его заменит
        var text by remember(e) { mutableStateOf(TextFieldValue(keys[e].label, TextRange(0, keys[e].label.length))) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("Символ на клавише")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { if (it.text.length <= 4) text = it }, singleLine = true,
                    label = { Text(tr("Буква, цифра или знак")) }
                )
            },
            confirmButton = {
                TextButton(enabled = text.text.isNotBlank(), onClick = {
                    val order = keys.map { it.label }.toMutableList()
                    order[e] = text.text.trim()
                    save(order)
                    editing = null
                }) { Text(tr("Готово")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Отмена")) } }
        )
    }
}
