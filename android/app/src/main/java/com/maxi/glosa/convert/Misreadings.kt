package com.maxi.glosa.convert

/**
 * The OCR that made a scan's text layer reads a V set in some faces as a U ("Ualue", "Uersus").
 * A word takes the spelling with V when the book uses that spelling often: no two English or
 * Spanish words differ only by that letter, so a real word is left as it is.
 */
class Misreadings(private val vocabulary: Map<String, Int>) {
    fun fix(page: PageLines): PageLines = PageLines(page.index, page.width, page.height, page.lines.map(::fix))

    /** A line's first word in lowercase may end a word broken on the line before, like the "uary" of "Jan-uary". */
    private fun fix(line: TextLine): TextLine {
        var isFirstWord = true
        val runs = line.runs.map { run ->
            val text = WORD.replace(run.text) { match ->
                val word = match.value
                val mayEndBrokenWord = isFirstWord && word.first().isLowerCase()
                isFirstWord = false
                if (mayEndBrokenWord) word else respell(word)
            }
            run.copy(text = text)
        }
        return TextLine(runs, line.x0, line.x1, line.baseline, line.size)
    }

    private fun respell(word: String): String {
        if (word.length < MIN_LETTERS) return word
        for (i in word.indices) {
            val v = when (word[i]) {
                'U' -> 'V'
                'u' -> 'v'
                else -> continue
            }
            val respelled = word.substring(0, i) + v + word.substring(i + 1)
            if ((vocabulary[respelled.lowercase()] ?: 0) >= MIN_COMMON) return respelled
        }
        return word
    }

    private companion object {
        val WORD = Regex("\\p{L}+")

        /** Shorter words are initials and abbreviations, like the U of "U.S." beside the v of "SEC v. Smith". */
        const val MIN_LETTERS = 4
        const val MIN_COMMON = 5
    }
}
