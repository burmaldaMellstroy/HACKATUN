package com.hex.stenographer

import kotlin.math.abs
import kotlin.math.min

/** Лёгкая предобработка сигнала: срез низких частот (гул), удаление DC, нормализация громкости. */
object AudioProc {
    fun prepare(x: FloatArray): FloatArray = normalize(highPass(x))

    /** Однополюсный ФВЧ ~80 Гц при 16 кГц. */
    private fun highPass(x: FloatArray, a: Float = 0.97f): FloatArray {
        if (x.isEmpty()) return x
        val y = FloatArray(x.size)
        var prevX = x[0]
        var prevY = 0f
        for (i in x.indices) {
            val out = a * (prevY + x[i] - prevX)
            prevX = x[i]; prevY = out
            y[i] = out
        }
        return y
    }

    /** Пиковая нормализация до ~0.9, усиление ограничено 20x, чтобы не раздувать шум. */
    fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        if (peak < 1e-4f) return x
        val gain = min(0.9f / peak, 20f)
        if (gain in 0.95f..1.05f) return x
        return FloatArray(x.size) { (x[it] * gain).coerceIn(-1f, 1f) }
    }
}
