package com.maxi.glosa.explain

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

enum class NanoAvailability { Ready, Downloadable, Downloading, Unavailable }

/** Gemini Nano through AICore. Inference only runs while the app is in the foreground. */
class GeminiNano : LanguageModel {
    private val model by lazy { Generation.getClient() }

    suspend fun availability(): NanoAvailability = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> NanoAvailability.Ready
        FeatureStatus.DOWNLOADABLE -> NanoAvailability.Downloadable
        FeatureStatus.DOWNLOADING -> NanoAvailability.Downloading
        else -> NanoAvailability.Unavailable
    }

    fun download(): Flow<DownloadStatus> = model.download()

    suspend fun warmup() = model.warmup()

    override suspend fun complete(prompt: String, maxOutputTokens: Int): String {
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0f
            topK = 1
            this.maxOutputTokens = maxOutputTokens
        }
        var attempt = 0
        while (true) {
            try {
                return model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
            } catch (e: GenAiException) {
                if (!isBusy(e) || attempt >= BUSY_RETRIES) throw e
                delay(backoff(attempt++))
            }
        }
    }

    override fun stream(prompt: String): Flow<String> {
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0.3f
            topK = 16
            maxOutputTokens = ANSWER_TOKENS
        }
        return model.generateContentStream(request)
            .map { response -> response.candidates.firstOrNull()?.text.orEmpty() }
            .retryWhen { cause, attempt ->
                val retry = isBusy(cause) && attempt < BUSY_RETRIES
                if (retry) delay(backoff(attempt.toInt()))
                retry
            }
    }

    private fun isBusy(cause: Throwable) = cause is GenAiException && cause.errorCode == GenAiException.ErrorCode.BUSY

    private fun backoff(attempt: Int): Long = 500L shl attempt

    private companion object {
        const val ANSWER_TOKENS = 512
        const val BUSY_RETRIES = 3
    }
}
