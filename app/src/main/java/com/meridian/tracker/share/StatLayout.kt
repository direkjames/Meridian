package com.meridian.tracker.share

import kotlin.math.max

/**
 * Text layout maths for share images, kept free of Android classes so it's unit-tested.
 * Widths come from a measuring function, so the same code works with any font.
 */
object StatLayout {

    data class Columns(val size: Float, val xs: List<Float>)

    /**
     * Places [count] stat columns across [avail] pixels starting at [left].
     *
     * Uses three equal columns when everything fits (the classic look). When a value is wide,
     * e.g. a 3:27:52 time, the columns are spread by their real widths with at least [minGap]
     * between them; if that still doesn't fit, the value size shrinks towards [minSize].
     * [widthsAt] returns each column's width (label or value, whichever is wider) at a value size.
     */
    fun columns(
        left: Float,
        avail: Float,
        baseSize: Float,
        minSize: Float,
        minGap: Float,
        widthsAt: (Float) -> List<Float>,
    ): Columns {
        var size = baseSize
        while (true) {
            val w = widthsAt(size)
            val n = w.size
            if (n == 0) return Columns(size, emptyList())
            val col = avail / n
            val equalFits = w.withIndex().all { (i, wi) -> if (i < n - 1) wi + minGap <= col else wi <= col }
            if (equalFits) return Columns(size, List(n) { left + it * col })

            val total = w.sum() + minGap * (n - 1)
            if (total <= avail || size <= minSize) {
                val gap = if (n > 1) max(minGap, (avail - w.sum()) / (n - 1)) else 0f
                val xs = ArrayList<Float>(n)
                var x = left
                w.forEach { wi ->
                    xs.add(x)
                    x += wi + gap
                }
                return Columns(size, xs)
            }
            size = max(minSize, size * 0.94f)
        }
    }

    /** The largest text size from [baseSize] down to [minSize] whose width fits in [maxWidth]. */
    fun fit(baseSize: Float, minSize: Float, maxWidth: Float, widthAt: (Float) -> Float): Float {
        val w = widthAt(baseSize)
        if (w <= maxWidth || w <= 0f) return baseSize
        // Text width scales linearly with size, so one step gets close; a few more make sure.
        var size = max(minSize, baseSize * maxWidth / w)
        var guard = 0
        while (size > minSize && widthAt(size) > maxWidth && guard++ < 40) size = max(minSize, size * 0.97f)
        return size
    }
}
