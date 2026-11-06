package com.maxi.glosa.explain

import com.maxi.glosa.data.Source

data class CitedAnswer(val answer: String, val sources: List<Source>)

/** Turns the model's [n] references to numbered findings into a short source list in citation order. */
object Citations {
    private val reference = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")

    /**
     * Renumbers the answer so [1] is the first source cited. When the model cites nothing, the sources are the
     * ones looked up this turn, so the reader still sees where the information came from.
     */
    fun resolve(answer: String, numbered: List<Finding>, consulted: List<Finding>): CitedAnswer {
        val order = reference.findAll(answer)
            .flatMap { match -> numbersIn(match) }
            .filter { it in 1..numbered.size }
            .distinct()
            .toList()
        if (order.isEmpty()) {
            return CitedAnswer(answer, consulted.map { it.source }.distinct())
        }
        val renumbered = reference.replace(answer) { match ->
            val numbers = numbersIn(match).filter { it in order }.map { order.indexOf(it) + 1 }.distinct().toList()
            if (numbers.isEmpty()) match.value else numbers.joinToString(", ", "[", "]")
        }
        return CitedAnswer(renumbered, order.map { numbered[it - 1].source })
    }

    private fun numbersIn(match: MatchResult): Sequence<Int> =
        match.groupValues[1].splitToSequence(',').map { it.trim().toInt() }
}
