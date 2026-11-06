package com.maxi.glosa.explain.tools

import com.maxi.glosa.data.Source
import com.maxi.glosa.data.SourceKind
import com.maxi.glosa.explain.Finding
import com.maxi.glosa.explain.LookupContext
import com.maxi.glosa.explain.Tool
import com.maxi.glosa.explain.ToolException

/** Small models get arithmetic wrong, so numbers in an answer come from here. */
object CalculatorTool : Tool {
    override val kind = SourceKind.Calculator

    override suspend fun run(query: String, context: LookupContext): List<Finding> {
        val result = try {
            Arithmetic.format(Arithmetic.evaluate(query))
        } catch (e: IllegalArgumentException) {
            throw ToolException("no pude resolver «$query»: ${e.message}", e)
        }
        return listOf(Finding(Source(SourceKind.Calculator, title = "$query = $result"), text = "$query = $result"))
    }
}
