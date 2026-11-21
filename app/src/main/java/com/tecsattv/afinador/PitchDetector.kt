package com.tecsattv.afinador

import kotlin.math.sqrt

/**
 * Detecção de frequência pelo algoritmo YIN.
 * Devolve a frequência em Hz, ou -1 se não houver som claro.
 */
class PitchDetector(private val sampleRate: Int, bufferSize: Int) {

    private val half = bufferSize / 2
    private val maxTau = minOf(half, sampleRate / 60)      // até ~60 Hz
    private val minTau = sampleRate / 1100                 // até ~1100 Hz
    private val diff = FloatArray(maxTau)
    private val threshold = 0.15f

    fun rms(buf: FloatArray): Float {
        var s = 0f
        for (v in buf) s += v * v
        return sqrt(s / buf.size)
    }

    fun detect(buf: FloatArray): Float {
        // função diferença
        for (tau in 1 until maxTau) {
            var sum = 0f
            for (i in 0 until half) {
                val d = buf[i] - buf[i + tau]
                sum += d * d
            }
            diff[tau] = sum
        }
        // média cumulativa normalizada
        diff[0] = 1f
        var running = 0f
        for (tau in 1 until maxTau) {
            running += diff[tau]
            diff[tau] = if (running == 0f) 1f else diff[tau] * tau / running
        }
        // primeiro mínimo abaixo do limiar
        var tau = minTau
        var found = -1
        while (tau < maxTau) {
            if (diff[tau] < threshold) {
                while (tau + 1 < maxTau && diff[tau + 1] < diff[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) return -1f

        // interpolação parabólica
        val better: Float = if (found in 1 until maxTau - 1) {
            val s0 = diff[found - 1]
            val s1 = diff[found]
            val s2 = diff[found + 1]
            val den = 2f * (2f * s1 - s2 - s0)
            if (den != 0f) found + (s2 - s0) / den else found.toFloat()
        } else found.toFloat()

        return sampleRate / better
    }
}
