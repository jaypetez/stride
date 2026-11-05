package org.stride.feature.coach

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Why the coach could not answer. Messages never contain the API key. */
sealed interface CoachLlmError {
    val message: String

    data object InvalidKey : CoachLlmError {
        override val message = "Anthropic rejected your API key. Check it in the coach settings."
    }

    data object RateLimited : CoachLlmError {
        override val message = "Anthropic is rate-limiting your key. Try again in a minute."
    }

    data object Overloaded : CoachLlmError {
        override val message = "Anthropic is busy right now. Try again shortly."
    }

    data object Network : CoachLlmError {
        override val message = "Couldn't reach Anthropic. Check your internet connection."
    }

    data object Refused : CoachLlmError {
        override val message = "The coach declined to answer this one."
    }

    data class Failed(val detail: String) : CoachLlmError {
        override val message = "The coach couldn't answer ($detail)."
    }
}

sealed interface CoachLlmResult {
    data class Ok(val text: String) : CoachLlmResult

    data class Err(val error: CoachLlmError) : CoachLlmResult
}

/** A one-shot text completion. The fake in tests records what would be sent. */
fun interface CoachLlm {
    suspend fun complete(apiKey: String, system: String, user: String): CoachLlmResult
}

@Serializable
internal data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<RequestMessage>,
    @SerialName("output_config") val outputConfig: OutputConfig
)

@Serializable
internal data class RequestMessage(val role: String, val content: String)

@Serializable
internal data class OutputConfig(val effort: String)

@Serializable
internal data class MessagesResponse(
    val content: List<ResponseBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null
)

@Serializable
internal data class ResponseBlock(val type: String, val text: String? = null)

/**
 * Calls the Anthropic Messages API directly from the phone with the user's own
 * key (`POST /v1/messages`). There is no logging interceptor, so the key in
 * `x-api-key` never reaches logcat.
 */
class OkHttpAnthropicClient(
    private val http: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val baseUrl: String = BASE_URL,
    private val model: String = DEFAULT_MODEL
) : CoachLlm {
    override suspend fun complete(apiKey: String, system: String, user: String): CoachLlmResult = withContext(io) {
        val body = JSON.encodeToString(MessagesRequest.serializer(), buildRequest(model, system, user))
        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.isSuccessful -> parse(text)
                    response.code == 401 || response.code == 403 -> CoachLlmResult.Err(CoachLlmError.InvalidKey)
                    response.code == 429 -> CoachLlmResult.Err(CoachLlmError.RateLimited)
                    response.code == 529 || response.code >= 500 -> CoachLlmResult.Err(CoachLlmError.Overloaded)
                    else -> CoachLlmResult.Err(CoachLlmError.Failed("HTTP ${response.code}"))
                }
            }
        } catch (_: IOException) {
            CoachLlmResult.Err(CoachLlmError.Network)
        }
    }

    companion object {
        const val BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"

        /** Fast and cheap: the coach only rephrases computed facts. */
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1024
        private val JSON_MEDIA = "application/json".toMediaType()
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        internal fun buildRequest(model: String, system: String, user: String) = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = system,
            messages = listOf(RequestMessage(role = "user", content = user)),
            outputConfig = OutputConfig(effort = "low")
        )

        internal fun encode(request: MessagesRequest): String = JSON.encodeToString(MessagesRequest.serializer(), request)

        internal fun parse(body: String): CoachLlmResult = try {
            val response = JSON.decodeFromString(MessagesResponse.serializer(), body)
            // Thinking blocks (if any) are skipped: only text blocks are shown.
            val text = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("").trim()
            when {
                response.stopReason == "refusal" -> CoachLlmResult.Err(CoachLlmError.Refused)
                text.isEmpty() -> CoachLlmResult.Err(CoachLlmError.Failed("empty reply"))
                else -> CoachLlmResult.Ok(text)
            }
        } catch (_: SerializationException) {
            CoachLlmResult.Err(CoachLlmError.Failed("unreadable reply"))
        } catch (_: IllegalArgumentException) {
            CoachLlmResult.Err(CoachLlmError.Failed("unreadable reply"))
        }
    }
}

/** The coach's instructions: explain the facts, never invent numbers. */
val COACH_SYSTEM_PROMPT = """
    You are Stride's running coach. You explain a runner's training using ONLY the FACTS JSON you are given.
    Every number in FACTS was computed by Stride's sports-science engine; you never compute or estimate new numbers.
    Rules:
    - Quote numbers exactly as they appear in FACTS (you may round them).
    - Never state any other number: no new totals, percentages, paces, dates, durations or predictions.
    - Explain what the numbers mean for the runner in plain, encouraging language, and suggest what to focus on next in general terms.
    - Fitness (CTL) is long-term load, fatigue (ATL) is short-term load and form (TSB) is fitness minus fatigue.
    - Do not give medical advice. If something looks concerning, suggest easing off and talking to a professional.
    - Keep it under 180 words. Plain text, short paragraphs, no tables.
""".trimIndent()

fun coachUserPrompt(facts: CoachFacts): String {
    val question = when (facts.subject) {
        CoachFacts.SUBJECT_ACTIVITY ->
            "Explain this activity: what kind of session it was, how hard it was and what it did for my training."

        else -> "Explain my current fitness trend: fitness, fatigue, form and how this week compares with last week."
    }
    return "FACTS:\n${facts.toJson()}\n\n$question"
}
