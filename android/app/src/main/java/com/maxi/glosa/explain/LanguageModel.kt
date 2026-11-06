package com.maxi.glosa.explain

import kotlinx.coroutines.flow.Flow

/** The on-device model as the explanation agent sees it. */
interface LanguageModel {
    /** A short, deterministic completion, used to decide which tools to call. */
    suspend fun complete(prompt: String, maxOutputTokens: Int): String

    fun stream(prompt: String): Flow<String>
}
