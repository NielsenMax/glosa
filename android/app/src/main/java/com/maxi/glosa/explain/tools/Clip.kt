package com.maxi.glosa.explain.tools

/** Shortens [text] to at most [maxChars], ending on a whole sentence when one fits. */
internal fun clip(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.take(maxChars)
    val sentenceEnd = cut.lastIndexOf(". ")
    return if (sentenceEnd > maxChars / 2) cut.substring(0, sentenceEnd + 1) else cut.trimEnd() + "…"
}
