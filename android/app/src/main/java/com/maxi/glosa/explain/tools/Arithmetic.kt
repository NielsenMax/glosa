package com.maxi.glosa.explain.tools

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Evaluates `+ - * / ^ %` and parentheses with the usual precedence. A comma is read as the decimal
 * separator, since the model often writes numbers the Spanish way.
 */
object Arithmetic {
    fun evaluate(expression: String): Double {
        val result = Parser(expression).parse()
        require(result.isFinite()) { "el resultado no es un número finito" }
        return result
    }

    fun format(value: Double): String =
        BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private class Parser(expression: String) {
        private val text = expression.replace('×', '*').replace('÷', '/').replace(',', '.').filterNot(Char::isWhitespace)
        private var position = 0

        fun parse(): Double {
            require(text.isNotEmpty()) { "la cuenta está vacía" }
            val value = sum()
            require(position == text.length) { "no entiendo «${text.substring(position)}»" }
            return value
        }

        private fun sum(): Double {
            var value = product()
            while (true) {
                value = when (peek()) {
                    '+' -> value + next { product() }
                    '-' -> value - next { product() }
                    else -> return value
                }
            }
        }

        private fun product(): Double {
            var value = signed()
            while (true) {
                value = when (peek()) {
                    '*' -> value * next { signed() }
                    '/' -> next { signed() }.let { divisor ->
                        require(divisor != 0.0) { "división por cero" }
                        value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun signed(): Double = when (peek()) {
            '-' -> -next { signed() }
            '+' -> next { signed() }
            else -> power()
        }

        private fun power(): Double {
            val base = percent()
            return if (peek() == '^') base.pow(next { signed() }) else base
        }

        private fun percent(): Double {
            val value = atom()
            return if (peek() == '%') next { value / 100 } else value
        }

        private fun atom(): Double {
            if (peek() == '(') {
                val value = next { sum() }
                require(peek() == ')') { "falta cerrar un paréntesis" }
                position++
                return value
            }
            val start = position
            while (peek()?.let { it.isDigit() || it == '.' } == true) position++
            require(position > start) { "esperaba un número en la posición ${start + 1}" }
            return text.substring(start, position).toDoubleOrNull() ?: throw IllegalArgumentException("«${text.substring(start, position)}» no es un número")
        }

        private fun peek(): Char? = text.getOrNull(position)

        private inline fun <T> next(read: () -> T): T {
            position++
            return read()
        }
    }
}
