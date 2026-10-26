package com.expressive.keyboard

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ============================================================================
// ИСПРАВЛЕНИЕ ТЕКСТА
// Кто исправляет — выбирается в настройках:
//  • Gemini Nano прямо на телефоне (AICore): без ключа и интернета, но не на всех телефонах и не для всех языков;
//  • Gemini API (бесплатный ключ из Google AI Studio), Claude API, OpenAI, Groq — по ключу пользователя;
//  • LanguageTool — бесплатно и без ключа, но это не ИИ.
// «Авто»: Nano, если умеет; иначе первый сервис с ключом; иначе LanguageTool.
// ============================================================================

enum class AiProvider(val id: String, val title: String) {
    AUTO("AUTO", "Авто"),
    NANO("NANO", "На телефоне"),
    GEMINI("GEMINI", "Gemini"),
    CLAUDE("CLAUDE", "Claude"),
    OPENAI("OPENAI", "OpenAI"),
    GROQ("GROQ", "Groq"),
    LANGUAGETOOL("LANGUAGETOOL", "LanguageTool");

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Ключи и выбор сервиса — из настроек. */
data class AiKeys(val provider: AiProvider, val gemini: String, val claude: String, val openai: String, val groq: String)

object AiFix {
    private const val CLAUDE_MODEL = "claude-opus-5-5"
    private const val GEMINI_MODEL = "gemini-flash-latest"
    private const val OPENAI_MODEL = "gpt-4.1-mini"
    private const val GROQ_MODEL = "llama-3.3-70b-versatile"
    private const val MAX_CHARS = 8000

    private val SYSTEM = """
        You fix text typed on a phone keyboard. Correct spelling, typos, grammar, punctuation and capitalization.
        Keep the original language, meaning, tone, slang, emoji, line breaks and formatting. Do not translate,
        shorten, expand, explain or add anything. If the text is already correct, return it unchanged.
        Reply with the corrected text only.
    """.trimIndent()

    /** Ошибка, после которой в режиме «Авто» стоит попробовать следующий сервис (а не показывать её сразу). */
    private class Skip(message: String) : Exception(message)

    /** Сервис не знает модель (или ключу она недоступна) — HTTP 404. */
    private class ModelNotFound(message: String) : Exception(message)

    /** Модель, которую сервис сам предложил после 404: база URL → id модели. */
    private val pickedModel = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Исправленный текст и кто исправлял, или понятная ошибка. lang — код языка клавиатуры (ru, en, …). */
    suspend fun fix(context: Context, text: String, keys: AiKeys, lang: String): Result<Pair<String, AiProvider>> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException(tr("Нет текста")))
            if (text.length > MAX_CHARS) return@withContext Result.failure(IllegalArgumentException(tr("Слишком длинный текст")))
            val order = when (keys.provider) {
                AiProvider.AUTO -> buildList {
                    add(AiProvider.NANO)
                    if (keys.gemini.isNotBlank()) add(AiProvider.GEMINI)
                    if (keys.claude.isNotBlank()) add(AiProvider.CLAUDE)
                    if (keys.openai.isNotBlank()) add(AiProvider.OPENAI)
                    if (keys.groq.isNotBlank()) add(AiProvider.GROQ)
                    add(AiProvider.LANGUAGETOOL)
                }
                else -> listOf(keys.provider)
            }
            var last: Throwable? = null
            for (p in order) {
                try {
                    return@withContext Result.success(run(p, context, text, keys, lang) to p)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // в «Авто» — к следующему сервису (сеть, лимит, ошибка сервера у одного не мешает другим);
                    // при явном выборе — показать причину
                    last = if (e is Skip || e is IllegalStateException) e else IllegalStateException(e.message ?: e.javaClass.simpleName)
                    if (keys.provider != AiProvider.AUTO) break
                }
            }
            Result.failure(last ?: IllegalStateException(tr("Не удалось исправить текст")))
        }

    private fun run(p: AiProvider, context: Context, text: String, keys: AiKeys, lang: String): String = when (p) {
        AiProvider.NANO -> nano(context, text, lang)
        AiProvider.GEMINI -> gemini(text, keys.gemini.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "Gemini")) })
        AiProvider.CLAUDE -> claude(text, keys.claude.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "Claude")) })
        AiProvider.OPENAI -> openAiCompatible(
            "https://api.openai.com/v1", OPENAI_MODEL, text,
            keys.openai.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "OpenAI")) }, "OpenAI"
        )
        AiProvider.GROQ -> openAiCompatible(
            "https://api.groq.com/openai/v1", GROQ_MODEL, text,
            keys.groq.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "Groq")) }, "Groq"
        )
        AiProvider.LANGUAGETOOL -> languageTool(text, lang)
        AiProvider.AUTO -> throw IllegalStateException("auto")
    }

    // ------------------------------------------------------------------ Gemini Nano на телефоне

    private val NANO_LANGS = mapOf(
        "en" to ProofreaderOptions.Language.ENGLISH, "ja" to ProofreaderOptions.Language.JAPANESE,
        "de" to ProofreaderOptions.Language.GERMAN, "fr" to ProofreaderOptions.Language.FRENCH,
        "it" to ProofreaderOptions.Language.ITALIAN, "es" to ProofreaderOptions.Language.SPANISH,
        "ko" to ProofreaderOptions.Language.KOREAN
    )

    @Volatile private var nanoClient: Proofreader? = null
    @Volatile private var nanoLang = -1
    /** Языки, для которых исправление на телефоне недоступно: не ждём проверку по 10 секунд при каждом нажатии. */
    private val nanoUnavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Состояние исправления на телефоне (FeatureStatus) для языка или null, если язык не поддерживается. */
    suspend fun nanoStatus(context: Context, lang: String): Int? = withContext(Dispatchers.IO) {
        val l = NANO_LANGS[lang] ?: return@withContext null
        runCatching { proofreader(context, l).checkFeatureStatus().get(10, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun proofreader(context: Context, l: Int): Proofreader = synchronized(this) {
        nanoClient?.takeIf { nanoLang == l } ?: run {
            runCatching { nanoClient?.close() }
            Proofreading.getClient(
                ProofreaderOptions.builder(context.applicationContext)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                    .setLanguage(l)
                    .build()
            ).also {
                nanoClient = it
                nanoLang = l
            }
        }
    }

    private fun nano(context: Context, text: String, lang: String): String {
        val l = NANO_LANGS[lang] ?: throw Skip(tr("Исправление на телефоне не поддерживает этот язык"))
        if (l in nanoUnavailable) throw Skip(tr("Исправление на телефоне недоступно"))
        val pr = try {
            proofreader(context, l)
        } catch (e: Throwable) {
            nanoUnavailable += l
            throw Skip(tr("Исправление на телефоне недоступно"))
        }
        val status = runCatching { pr.checkFeatureStatus().get(5, TimeUnit.SECONDS) }.getOrNull()
        if (status == null || status == FeatureStatus.UNAVAILABLE) {
            nanoUnavailable += l
            throw Skip(tr("Исправление на телефоне недоступно"))
        }
        // модель ещё не скачана — первый запрос скачает её сам; ждём дольше
        val wait = if (status == FeatureStatus.AVAILABLE) 30L else 180L
        val result = try {
            pr.runInference(ProofreadingRequest.builder(text).build()).get(wait, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            throw Skip(tr("Исправление на телефоне не сработало"))
        }
        return result.results.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() } ?: text
    }

    // ------------------------------------------------------------------ Claude (SDK Anthropic)

    @Volatile private var claudeClient: AnthropicClient? = null
    @Volatile private var claudeKey = ""

    private fun claudeClient(key: String): AnthropicClient = synchronized(this) {
        // клавиатура не может ждать минутами: короткий таймаут и одна повторная попытка
        claudeClient?.takeIf { claudeKey == key } ?: AnthropicOkHttpClient.builder().apiKey(key)
            .timeout(java.time.Duration.ofSeconds(30)).maxRetries(1).build().also {
                claudeClient = it
                claudeKey = key
            }
    }

    private fun claude(text: String, key: String): String = try {
        val params = MessageCreateParams.builder()
            .model(CLAUDE_MODEL)
            .maxTokens(16000L)
            .system(SYSTEM)
            // простая правка: низкое усилие — быстрее ответ
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(text)
            .build()
        val msg = claudeClient(key).messages().create(params)
        if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") {
            throw IllegalStateException(tr("Claude отказался обработать этот текст"))
        }
        msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("").trim()
            .ifEmpty { throw IllegalStateException(tr("Пустой ответ")) }
    } catch (e: UnauthorizedException) {
        throw IllegalStateException(tr("Неверный ключ {0}", "Claude"))
    } catch (e: PermissionDeniedException) {
        throw IllegalStateException(tr("Ключу {0} не хватает прав", "Claude"))
    } catch (e: RateLimitException) {
        throw IllegalStateException(tr("Слишком много запросов — попробуйте через минуту"))
    } catch (e: AnthropicServiceException) {
        throw IllegalStateException(tr("Ошибка {0}: {1}", "Claude", e.statusCode()))
    } catch (e: AnthropicIoException) {
        throw IllegalStateException(tr("Нет соединения с сервером"))
    }

    // ------------------------------------------------------------------ Gemini API, OpenAI, Groq (HTTP)

    private fun post(url: String, body: JSONObject, headers: Map<String, String>, name: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            // пояснение от самого сервиса — чтобы по сообщению было видно причину
            val detail = runCatching {
                JSONObject(raw).let { o -> o.optJSONObject("error")?.optString("message") ?: o.optString("message") }
            }.getOrNull().orEmpty().take(160)
            when {
                code == 401 || code == 403 || (code == 400 && raw.contains("API_KEY_INVALID")) ->
                    throw IllegalStateException(tr("Неверный ключ {0}", name))
                code == 429 -> throw IllegalStateException(tr("Слишком много запросов — попробуйте через минуту"))
                code == 404 -> throw ModelNotFound(tr("Ошибка {0}: {1}", name, code) + if (detail.isNotEmpty()) " — $detail" else "")
                code !in 200..299 -> throw IllegalStateException(tr("Ошибка {0}: {1}", name, code) + if (detail.isNotEmpty()) " — $detail" else "")
            }
            return JSONObject(raw)
        } catch (e: java.io.IOException) {
            throw IllegalStateException(tr("Нет соединения с сервером"))
        } finally {
            conn.disconnect()
        }
    }

    private fun gemini(text: String, key: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
        val root = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            body, mapOf("x-goog-api-key" to key), "Gemini"
        )
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException(tr("Пустой ответ"))
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
            .ifEmpty { throw IllegalStateException(tr("Пустой ответ")) }
    }

    /**
     * OpenAI и всё, что говорит на его языке (Groq): /chat/completions. Если модели нет (404) — спрашиваем
     * у сервиса список моделей, доступных ключу, берём подходящую и запоминаем её.
     */
    private fun openAiCompatible(base: String, model: String, text: String, key: String, name: String): String {
        val current = pickedModel[base] ?: model
        return try {
            chat(base, current, text, key, name)
        } catch (e: ModelNotFound) {
            val other = availableModel(base, key, name, preferred = listOf(model)) ?: throw IllegalStateException(e.message)
            if (other == current) throw IllegalStateException(e.message)
            pickedModel[base] = other
            try {
                chat(base, other, text, key, name)
            } catch (e2: ModelNotFound) {
                throw IllegalStateException(e2.message)
            }
        }
    }

    /** Модели, доступные ключу (GET /models): сначала знакомые, потом любая текстовая. */
    private fun availableModel(base: String, key: String, name: String, preferred: List<String>): String? = runCatching {
        val conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: return@runCatching null
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
            // не чат-модели: распознавание речи, озвучка, модерация, эмбеддинги, картинки
            val chat = ids.filter { id ->
                listOf("whisper", "tts", "orpheus", "guard", "embed", "moderation", "dall-e", "image", "audio", "realtime", "transcribe", "search")
                    .none { id.contains(it, ignoreCase = true) }
            }
            val known = preferred + listOf(
                "openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-3.1-8b-instant",
                "gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini", "gpt-4.1", "gpt-4o"
            )
            known.firstOrNull { it in chat } ?: chat.firstOrNull()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun chat(base: String, model: String, text: String, key: String, name: String): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
        val root = post("$base/chat/completions", body, mapOf("Authorization" to "Bearer $key"), name)
        return root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
            ?.ifEmpty { null } ?: throw IllegalStateException(tr("Пустой ответ"))
    }

    // ------------------------------------------------------------------ LanguageTool (бесплатно, не ИИ)

    private fun languageTool(text: String, lang: String): String {
        val l = when (lang) {
            "", "auto" -> "auto"
            "en" -> "en-US"
            "de" -> "de-DE"
            "pt" -> "pt-BR"
            "ru" -> "ru-RU"
            "uk" -> "uk-UA"
            "pl" -> "pl-PL"
            else -> lang
        }
        return try {
            languageTool(text, l, retry = l != "auto")
        } catch (e: UnsupportedLanguage) {
            // язык клавиатуры LanguageTool не знает — пусть определит сам
            languageTool(text, "auto", retry = false)
        }
    }

    private class UnsupportedLanguage : Exception()

    private fun languageTool(text: String, l: String, retry: Boolean): String {
        val conn = (URL("https://api.languagetool.org/v2/check").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        val body = "text=" + URLEncoder.encode(text, "UTF-8") + "&language=" + URLEncoder.encode(l, "UTF-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 429) throw IllegalStateException(tr("Слишком много запросов — попробуйте через минуту"))
            if (code == 400 && retry) throw UnsupportedLanguage()
            if (code !in 200..299) throw IllegalStateException(tr("Ошибка {0}: {1}", "LanguageTool", code))
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val matches = root.optJSONArray("matches") ?: return text
            val fixes = (0 until matches.length()).mapNotNull { i ->
                val m = matches.getJSONObject(i)
                val rep = m.optJSONArray("replacements")?.optJSONObject(0)?.optString("value") ?: return@mapNotNull null
                Triple(m.optInt("offset"), m.optInt("length"), rep)
            }.sortedByDescending { it.first }
            val sb = StringBuilder(text)
            var lastStart = Int.MAX_VALUE
            for ((off, len, rep) in fixes) {
                // пересекающиеся правки пропускаем
                if (off < 0 || off + len > sb.length || off + len > lastStart) continue
                sb.replace(off, off + len, rep)
                lastStart = off
            }
            return sb.toString()
        } catch (e: java.io.IOException) {
            throw IllegalStateException(tr("Нет соединения с сервером"))
        } finally {
            conn.disconnect()
        }
    }
}
