package com.expressive.keyboard

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

// ============================================================================
// ДИЗАЙН: ГОТОВЫЕ СТИЛИ ВСЕЙ КЛАВИАТУРЫ ОДНИМ НАЖАТИЕМ
// Стиль — набор настроек (форма акцентов, шрифт, скругление, толщина букв) плюс встроенное
// оформление (skin) из assets/plugins. После выбора всё можно донастроить вручную.
// ============================================================================

enum class DesignStyle(
    val id: String,
    val title: String,
    val subtitle: String,
    val skin: String,
    val shape: ExpressiveShapePreset,
    val font: String,
    val corner: Float,
    val weight: Int
) {
    EXPRESSIVE(
        "EXPRESSIVE", "Material You Expressive", "Волнистые «печеньки», морфинг формы, округлый шрифт",
        "", ExpressiveShapePreset.COOKIE_12, "ROUNDED", 16f, 700
    ),
    MATERIAL(
        "MATERIAL", "Material 3 · Google", "Строго по гайдлайнам Google: круглые кнопки, системный шрифт, клавиши-таблетки",
        "builtin.material", ExpressiveShapePreset.CIRCLE, "SYSTEM", 10f, 500
    ),
    GLASS(
        "GLASS", "Liquid Glass", "Прозрачное стекло с преломлением фона и бликами",
        "builtin.liquid-glass", ExpressiveShapePreset.CIRCLE, "SYSTEM", 18f, 600
    ),
    CLASSIC(
        "CLASSIC", "Классика", "Плоские клавиши с лёгкой тенью, без лишних анимаций",
        "builtin.classic", ExpressiveShapePreset.CIRCLE, "SYSTEM", 6f, 400
    ),
    NEON("NEON", "Неон", "Тёмный фон и светящиеся контуры", "builtin.neon", ExpressiveShapePreset.FLOWER_6, "MONOSPACE", 14f, 600),
    NEUMORPH("NEUMORPH", "Неоморфизм", "Мягкие выпуклые клавиши из материала фона", "builtin.neumorph", ExpressiveShapePreset.COOKIE_12, "ROUNDED", 16f, 600);

    fun apply(context: Context) {
        if (skin.isNotEmpty() && !PluginRepository.isEnabled(skin)) PluginRepository.setEnabled(context, skin, true)
        KeyboardPrefs.edit(context) {
            putString("design_style", id)
            putString("skin_plugin", skin)
            putString("accent_shape_preset", shape.id)
            putInt("wave_count", shape.defaultWaves)
            putFloat("wave_amplitude_percent", shape.defaultAmplitude)
            putString("font_style", font)
            putFloat("corner_radius_dp", corner)
            putInt("font_weight", weight)
        }
    }

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: EXPRESSIVE
    }
}

/** Карточки стилей с мини-клавиатурой в каждом — вверху главного экрана настроек. */
@Composable
internal fun DesignPicker(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val current = DesignStyle.fromId(settings.designStyle)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DesignStyle.entries.forEach { d ->
            val sel = d == current
            val corner by animateDpAsState(if (sel) 28.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "designCorner")
            val bg by animateColorAsState(if (sel) cs.primaryContainer else cs.surfaceContainerHigh, label = "designBg")
            Column(
                Modifier.width(156.dp).clip(RoundedCornerShape(corner)).background(bg)
                    .then(if (sel) Modifier.border(2.dp, cs.primary, RoundedCornerShape(corner)) else Modifier)
                    .clickable { d.apply(context) }.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Canvas(Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(14.dp))) { drawMiniKeyboard(d, cs.primary, cs.surfaceContainer, cs.surfaceBright, cs.secondaryContainer) }
                Text(
                    tr(d.title), style = MaterialTheme.typography.titleSmall,
                    color = if (sel) cs.onPrimaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    tr(d.subtitle), style = MaterialTheme.typography.bodySmall,
                    color = if (sel) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant, maxLines = 3, minLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Схематичная клавиатура стиля: фон, три ряда клавиш, пробел и Enter. */
private fun DrawScope.drawMiniKeyboard(d: DesignStyle, primary: Color, surface: Color, key: Color, func: Color) {
    val w = size.width
    val h = size.height
    val bg: Brush; val keyColor: Color; val funcColor: Color; val enter: Color
    var outline: Color? = null
    when (d) {
        DesignStyle.GLASS -> {
            bg = Brush.linearGradient(listOf(Color(0xFF1B1F4B), Color(0xFF3A1C71), Color(0xFF0F4C5C)))
            keyColor = Color.White.copy(alpha = 0.22f); funcColor = Color.White.copy(alpha = 0.3f); enter = Color(0x998AB4FF)
        }
        DesignStyle.NEON -> {
            bg = Brush.verticalGradient(listOf(Color(0xFF07040F), Color(0xFF120A24)))
            keyColor = Color.Transparent; funcColor = Color.Transparent; enter = Color(0xFFFF2BD1); outline = Color(0xFF00F0FF)
        }
        DesignStyle.NEUMORPH -> {
            bg = Brush.verticalGradient(listOf(Color(0xFFE4E9F2), Color(0xFFE4E9F2)))
            keyColor = Color(0xFFEDF1F8); funcColor = Color(0xFFDCE2EC); enter = Color(0xFF5B7CFA)
        }
        else -> {
            bg = Brush.verticalGradient(listOf(surface, surface))
            keyColor = key; funcColor = func; enter = primary
        }
    }
    drawRect(bg)
    val pad = w * 0.04f
    val gap = w * 0.02f
    val rows = 4
    val rowH = (h - pad * 2 - gap * (rows - 1)) / rows
    val r = when (d) {
        DesignStyle.CLASSIC -> rowH * 0.18f
        DesignStyle.MATERIAL -> rowH * 0.3f
        else -> rowH * 0.42f
    }
    for (row in 0 until 3) {
        val n = if (row == 2) 7 else 10 - row
        val kw = (w - pad * 2 - gap * (n - 1)) / n
        for (i in 0 until n) {
            val tl = Offset(pad + i * (kw + gap), pad + row * (rowH + gap))
            if (d == DesignStyle.CLASSIC) drawRoundRect(Color.Black.copy(alpha = 0.18f), tl + Offset(0f, 1.5f), Size(kw, rowH), CornerRadius(r, r))
            drawRoundRect(keyColor, tl, Size(kw, rowH), CornerRadius(r, r))
            outline?.let { drawRoundRect(it, tl, Size(kw, rowH), CornerRadius(r, r), style = Stroke(1.2f)) }
        }
    }
    val y = pad + 3 * (rowH + gap)
    val unit = (w - pad * 2 - gap * 3) / 8f
    val pill = if (d == DesignStyle.MATERIAL || d == DesignStyle.EXPRESSIVE || d == DesignStyle.GLASS) rowH / 2f else r
    drawRoundRect(funcColor, Offset(pad, y), Size(unit * 1.5f, rowH), CornerRadius(r, r))
    drawRoundRect(funcColor, Offset(pad + unit * 1.5f + gap, y), Size(unit * 4.5f, rowH), CornerRadius(pill, pill))
    outline?.let { drawRoundRect(it, Offset(pad + unit * 1.5f + gap, y), Size(unit * 4.5f, rowH), CornerRadius(pill, pill), style = Stroke(1.2f)) }
    val ex = pad + unit * 6f + gap * 2
    drawRoundRect(enter, Offset(ex, y), Size(w - pad - ex, rowH), CornerRadius(min(pill, rowH / 2f), min(pill, rowH / 2f)))
}
