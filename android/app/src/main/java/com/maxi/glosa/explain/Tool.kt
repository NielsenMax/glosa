package com.maxi.glosa.explain

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind

data class ToolCall(val tool: SourceKind, val query: String) {
    /** Two calls that differ only in case or spacing find the same thing. */
    val key: String get() = "$tool:" + query.lowercase().replace(Regex("\\s+"), " ").trim()
}

/** Something a tool found, with the text the model gets to read. */
data class Finding(val source: Source, val text: String)

/** What the reader is looking at, so a tool can pick the sense or the section that fits it. */
data class LookupContext(val passage: String, val question: String)

interface Tool {
    val kind: SourceKind

    /** Returns what was found, possibly nothing; throws [ToolException] when the lookup itself failed. */
    suspend fun run(query: String, context: LookupContext): List<Finding>
}

/** A lookup that could not be done, with a message the reader can understand. */
open class ToolException(message: String, cause: Throwable? = null) : Exception(message, cause)
