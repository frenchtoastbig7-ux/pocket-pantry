package com.sovereignedge.pocketpantry.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.sovereignedge.pocketpantry.Settings
import com.sovereignedge.pocketpantry.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.optionals.getOrNull

/**
 * Direct calls to the Claude API with the user's own key. Nothing goes through a server of ours: the phone talks
 * to api.anthropic.com and the key only ever lives in [com.sovereignedge.pocketpantry.SecretStore].
 */
class ClaudeService(private val settings: Settings) {
    private var cached: Pair<String, AnthropicClient>? = null

    sealed interface Event {
        data class Text(val delta: String) : Event
        data class Notice(val text: String) : Event
    }

    class NotConnected : Exception("Connect your Claude API key in Settings first.")

    private fun client(): AnthropicClient {
        val key = settings.apiKey() ?: throw NotConnected()
        cached?.takeIf { it.first == key }?.let { return it.second }
        return AnthropicOkHttpClient.builder().apiKey(key).maxRetries(2).build().also { cached = key to it }
    }

    fun forget() {
        cached = null
    }

    /**
     * Streams one assistant turn for [history] and returns the full text. Cancelling the calling coroutine closes
     * the HTTP response, which is the only way to interrupt the blocking read.
     */
    suspend fun stream(system: String, history: List<ChatMessage>, onEvent: suspend (Event) -> Unit): String {
        val client = client()
        val snapshot = settings.current
        val params = MessageCreateParams.builder()
            .model(snapshot.model)
            .maxTokens(MAX_TOKENS)
            .systemOfBetaTextBlockParams(
                listOf(BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()),
            )
            .messages(history.map { it.toParam() })
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(snapshot.effort)).build())
            .apply {
                // Server-side refusal fallbacks: if the model declines, the API continues on a fallback model in the
                // same call. Haiku has no fallback route, so the parameter is only sent for Opus and Sonnet.
                if (snapshot.model.supportsFallbacks()) {
                    addBeta(FALLBACK_BETA)
                    fallbacksDefault()
                }
            }
            .build()

        val accumulator = BetaMessageAccumulator.create()
        val text = StringBuilder()
        withContext(Dispatchers.IO) {
            val response = client.beta().messages().createStreaming(params)
            coroutineScope {
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        response.close()
                    }
                }
                try {
                    response.use { stream ->
                        val events = stream.stream().iterator()
                        while (events.hasNext()) {
                            val event = events.next()
                            accumulator.accumulate(event)
                            event.contentBlockStart().getOrNull()?.contentBlock()?.fallback()?.getOrNull()?.let {
                                onEvent(Event.Notice("The first model declined; continuing on ${it.to().model()}."))
                            }
                            event.contentBlockDelta().getOrNull()?.delta()?.text()?.getOrNull()?.let {
                                text.append(it.text())
                                onEvent(Event.Text(it.text()))
                            }
                        }
                    }
                } finally {
                    closer.cancel()
                }
            }
        }
        val message = accumulator.message()
        when (message.stopReason().getOrNull()) {
            BetaStopReason.REFUSAL -> {
                val why = message.stopDetails().getOrNull()?.explanation()?.getOrNull()
                onEvent(Event.Notice("Claude declined to answer this one." + (why?.let { " $it" } ?: "")))
            }
            BetaStopReason.MAX_TOKENS -> onEvent(Event.Notice("The reply hit the length limit; ask Claude to continue."))
            else -> Unit
        }
        return text.toString()
    }

    /** One short, non-streamed, low-effort turn. Used for extracting a shopping list from a recipe. */
    suspend fun complete(system: String, prompt: String, maxTokens: Long = 4000): String = withContext(Dispatchers.IO) {
        val client = client()
        val params = MessageCreateParams.builder()
            .model(settings.current.model)
            .maxTokens(maxTokens)
            .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(system).build()))
            .addUserMessage(prompt)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .build()
        client.beta().messages().create(params).content()
            .mapNotNull { it.text().getOrNull()?.text() }
            .joinToString("")
    }

    /** A cheap round trip that proves the key works. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val client = client()
        val params = MessageCreateParams.builder()
            .model("claude-haiku-5-5")
            .maxTokens(32L)
            .addUserMessage("Reply with the single word OK.")
            .build()
        val reply = client.beta().messages().create(params)
        "Connected. Claude replied: " + reply.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("").trim()
    }

    private fun ChatMessage.toParam(): BetaMessageParam =
        BetaMessageParam.builder()
            .role(if (isUser) BetaMessageParam.Role.USER else BetaMessageParam.Role.ASSISTANT)
            .contentOfBetaContentBlockParams(listOf(BetaContentBlockParam.ofText(text.ifBlank { "(empty)" })))
            .build()

    private fun String.supportsFallbacks() = startsWith("claude-opus") || startsWith("claude-sonnet")

    companion object {
        const val MAX_TOKENS = 8000L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** A one-line, user-facing description of an API failure. */
        fun describe(e: Throwable): String = when (e) {
            is NotConnected -> e.message!!
            is UnauthorizedException -> "The API key was rejected. Check it in Settings."
            is PermissionDeniedException -> "This key isn't allowed to use that model. Pick another model in Settings."
            is RateLimitException -> "Rate limited by the API. Wait a moment and try again."
            is AnthropicServiceException -> "API error ${e.statusCode()}: ${e.message?.lineSequence()?.firstOrNull() ?: "unknown"}"
            is AnthropicIoException -> "Couldn't reach the Claude API. Check your connection."
            is AnthropicInvalidDataException -> "Unexpected reply from the API: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
