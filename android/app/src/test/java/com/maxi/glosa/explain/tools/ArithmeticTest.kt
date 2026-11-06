package com.maxi.glosa.explain.tools

import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.ToolException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArithmeticTest {
    private fun calculate(expression: String): String = runBlocking { CalculatorTool.run(expression, LookupContext(passage = "", question = "")).single().text }

    private fun failure(expression: String): String? =
        assertThrows(ToolException::class.java) { runBlocking { CalculatorTool.run(expression, LookupContext(passage = "", question = "")) } }.message

    @Test
    fun `applies the usual precedence`() {
        assertEquals("2 + 3 * 4 ^ 2 = 50", calculate("2 + 3 * 4 ^ 2"))
    }

    @Test
    fun `turns a spread into a percentage`() {
        assertEquals("(10.05 - 10) / 10 * 100 = 0.5", calculate("(10.05 - 10) / 10 * 100"))
    }

    @Test
    fun `reads a decimal comma`() {
        assertEquals("10,05 - 10 = 0.05", calculate("10,05 - 10"))
    }

    @Test
    fun `negates before raising only when the minus is outside the power`() {
        assertEquals("-2 ^ 2 = -4", calculate("-2 ^ 2"))
        assertEquals("(-2) ^ 2 = 4", calculate("(-2) ^ 2"))
    }

    @Test
    fun `reads a percentage`() {
        assertEquals("200 * 15% = 30", calculate("200 * 15%"))
    }

    @Test
    fun `rounds to six decimals`() {
        assertEquals("1 / 3 = 0.333333", calculate("1 / 3"))
    }

    @Test
    fun `refuses to divide by zero`() {
        assertEquals("no pude resolver «5 / (2 - 2)»: división por cero", failure("5 / (2 - 2)"))
    }

    @Test
    fun `refuses words`() {
        assertEquals("no pude resolver «spread / 2»: esperaba un número en la posición 1", failure("spread / 2"))
    }

    @Test
    fun `refuses an unclosed parenthesis`() {
        assertEquals("no pude resolver «(1 + 2»: falta cerrar un paréntesis", failure("(1 + 2"))
    }
}
