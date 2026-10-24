package com.expressive.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

// ============================================================================
// 14. ПЕЧАТЬ БЕЗ ОТРЫВА ПАЛЬЦА (GLIDE / SWIPE TYPING)
// Палец ведётся от буквы к букве, по отпусканию траектория сравнивается
// с «идеальными» ломаными слов словаря (форма + проход рядом с каждой буквой
// + частотность). Лучшее слово вставляется, ещё два — в строку подсказок.
// ============================================================================

@Stable
class GlideController {
    private val keys = HashMap<String, CoordsHolder>()
    /** След пальца в координатах слоя клавиатуры; читается только при отрисовке. */
    val trail = mutableStateListOf<Offset>()

    fun register(label: String, holder: CoordsHolder) {
        keys[label] = holder
    }

    fun unregister(label: String, holder: CoordsHolder) {
        if (keys[label] === holder) keys.remove(label)
    }

    /** Центры буквенных клавиш и типичная ширина клавиши. */
    fun snapshot(layer: LayoutCoordinates): Pair<Map<Char, Offset>, Float>? {
        if (!layer.isAttached) return null
        val map = HashMap<Char, Offset>()
        val widths = ArrayList<Int>()
        for ((label, h) in keys) {
            val c = h.coords ?: continue
            if (!c.isAttached || label.length != 1 || !label[0].isLetter()) continue
            val p = layer.localPositionOf(c, Offset.Zero)
            map[label[0]] = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f)
            widths += c.size.width
        }
        if (map.size < 10) return null
        widths.sort()
        return map to widths[widths.size / 2].toFloat()
    }
}

object GlideDecoder {
    private const val N = 32
    private val ALIASES = mapOf('ё' to 'е', 'ъ' to 'ь', 'й' to 'и')

    fun resample(src: List<Offset>, n: Int): Array<Offset> {
        if (src.size == 1) return Array(n) { src[0] }
        var total = 0f
        for (i in 1 until src.size) total += (src[i] - src[i - 1]).getDistance()
        if (total <= 0f) return Array(n) { src[0] }
        val step = total / (n - 1)
        val out = ArrayList<Offset>(n)
        out += src[0]
        var acc = 0f
        var prev = src[0]
        var i = 1
        while (i < src.size && out.size < n) {
            val cur = src[i]
            val d = (cur - prev).getDistance()
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                val q = prev + (cur - prev) * t
                out += q
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out += src.last()
        return out.toTypedArray()
    }

    /** Возвращает до limit слов, лучшее первым. lexicon: слово → штраф за редкость (0 — частое). */
    fun decode(
        path: List<Offset>,
        keys: Map<Char, Offset>,
        keyW: Float,
        lexicon: List<Pair<String, Float>>,
        limit: Int = 3
    ): List<String> {
        if (path.size < 2 || keyW <= 0f) return emptyList()
        val pts = resample(path, N)
        val dense = resample(path, 64)
        val start = path.first()
        val end = path.last()
        val ideal = ArrayList<Offset>(16)
        val scored = ArrayList<Pair<String, Float>>()
        val near = keyW * 1.5f
        for ((word, prior) in lexicon) {
            // сначала дешёвая проверка первой и последней буквы: она отсекает почти весь словарь
            if (word.isEmpty()) continue
            val f = keys[word[0]] ?: ALIASES[word[0]]?.let { keys[it] } ?: continue
            if ((f - start).getDistance() > near) continue
            val l = keys[word[word.length - 1]] ?: ALIASES[word[word.length - 1]]?.let { keys[it] } ?: continue
            if ((l - end).getDistance() > near) continue
            ideal.clear()
            var ok = true
            for (ch in word) {
                val k = keys[ch] ?: ALIASES[ch]?.let { keys[it] }
                if (k == null) {
                    ok = false
                    break
                }
                if (ideal.isEmpty() || ideal.last() != k) ideal += k
            }
            if (!ok || ideal.size < 2) continue
            if ((ideal.first() - start).getDistance() > keyW * 1.5f) continue
            if ((ideal.last() - end).getDistance() > keyW * 1.5f) continue
            // каждая буква слова должна быть рядом с траекторией
            var miss = 0f
            for (k in ideal) {
                var best = Float.MAX_VALUE
                for (p in dense) best = min(best, (p - k).getDistanceSquared())
                val d = kotlin.math.sqrt(best) / keyW
                if (d > 0.55f) miss += d - 0.55f
                if (miss > 1.4f) break
            }
            if (miss > 1.4f) continue
            val ip = resample(ideal, N)
            var sum = 0f
            for (i in 0 until N) sum += (pts[i] - ip[i]).getDistance()
            val shape = sum / N / keyW
            scored += word to (shape + miss * 1.3f + prior)
        }
        scored.sortBy { it.second }
        return scored.take(limit).map { it.first }
    }
}

/** След пальца: хвост сужается и тает к началу. */
@Composable
internal fun BoxScope.GlideTrailLayer(glide: GlideController, color: Color, enabled: Boolean) {
    if (!enabled) return
    Canvas(Modifier.matchParentSize()) {
        val pts = glide.trail
        val n = pts.size
        if (n < 2) return@Canvas
        val from = max(1, n - 60)
        val span = (n - from).coerceAtLeast(1)
        val maxW = 7.dp.toPx()
        for (i in from until n) {
            val t = (i - from + 1f) / span
            drawLine(color.copy(alpha = color.alpha * (0.15f + 0.75f * t)), pts[i - 1], pts[i], maxW * (0.3f + 0.7f * t), StrokeCap.Round)
        }
    }
}
