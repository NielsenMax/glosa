package com.maxi.glosa.settings

/** The colors of a page, as ARGB: the paper and the ink printed on it. */
data class Paper(val background: Int, val ink: Int) {
    /**
     * A 4×5 color matrix, row by row as Android's `ColorMatrix` takes it, that sends white to
     * [background] and black to [ink]. A PDF page, printed black on white, takes these colors.
     */
    fun colorMatrix(): FloatArray {
        val (red, redInk) = channel(16)
        val (green, greenInk) = channel(8)
        val (blue, blueInk) = channel(0)
        return floatArrayOf(
            red, 0f, 0f, 0f, redInk,
            0f, green, 0f, 0f, greenInk,
            0f, 0f, blue, 0f, blueInk,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun channel(shift: Int): Pair<Float, Float> {
        val paper = (background shr shift) and 0xFF
        val printed = (ink shr shift) and 0xFF
        return (paper - printed) / 255f to printed.toFloat()
    }

    companion object {
        val Night = Paper(background = 0xFF121212.toInt(), ink = 0xFFDCDCDC.toInt())

        /** [tone] 0 is barely warm white; 1 is old, yellowed paper. */
        fun sepia(tone: Float): Paper = Paper(
            background = mix(0xFFFCF9F2.toInt(), 0xFFE2C9A0.toInt(), tone),
            ink = mix(0xFF1E1A15.toInt(), 0xFF3B2C1A.toInt(), tone),
        )

        private fun mix(from: Int, to: Int, fraction: Float): Int {
            var mixed = 0xFF shl 24
            for (shift in listOf(16, 8, 0)) {
                val start = (from shr shift) and 0xFF
                val end = (to shr shift) and 0xFF
                mixed = mixed or ((start + (end - start) * fraction).toInt().coerceIn(0, 255) shl shift)
            }
            return mixed
        }
    }
}
