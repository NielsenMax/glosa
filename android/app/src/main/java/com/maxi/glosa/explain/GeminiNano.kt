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
class GeminiNano {
    private val model by lazy { Generation.getClient() }

    suspend fun availability(): NanoAvailability = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> NanoAvailability.Ready
        FeatureStatus.DOWNLOADABLE -> NanoAvailability.Downloadable
        FeatureStatus.DOWNLOADING -> NanoAvailability.Downloading
        else -> NanoAvailability.Unavailable
    }

    fun download(): Flow<DownloadStatus> = model.download()

    suspend fun warmup() = model.warmup()

    /** Emits text fragments as Gemini Nano produces them. */
    fun stream(prompt: String): Flow<String> {
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0.3f
            topK = 16
            maxOutputTokens = MAX_OUTPUT_TOKENS
        }
        return model.generateContentStream(request)
            .map { response -> response.candidates.firstOrNull()?.text.orEmpty() }
            .retryWhen { cause, attempt ->
                val isBusy = cause is GenAiException && cause.errorCode == GenAiException.ErrorCode.BUSY
                if (isBusy && attempt < 3) delay(500L shl attempt.toInt())
                isBusy && attempt < 3
            }
    }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 512
    }
}
