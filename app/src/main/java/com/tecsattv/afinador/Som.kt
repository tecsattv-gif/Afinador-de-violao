package com.tecsattv.afinador

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.random.Random

/**
 * Som de corda de violão dedilhada (síntese Karplus-Strong).
 * Gera a nota na hora, sem arquivos de áudio.
 */
object Som {
    private const val SR = 44100
    private val executor = Executors.newSingleThreadExecutor()   // sequências
    private val toques = Executors.newCachedThreadPool()          // toques soltos
    private val cache = HashMap<Int, ShortArray>()

    fun freq(midi: Int, la: Int = 440): Double = la * 2.0.pow((midi - 69) / 12.0)

    @Synchronized
    private fun gerar(midi: Int, segundos: Double = 1.6): ShortArray {
        cache[midi]?.let { return it }
        val f = freq(midi)
        val n = (SR / f).toInt().coerceAtLeast(2)
        val linha = FloatArray(n) { Random.nextFloat() * 2f - 1f }
        // suaviza o ruído inicial (ataque menos "metálico")
        for (i in 1 until n) linha[i] = (linha[i] + linha[i - 1]) * 0.5f
        val total = (SR * segundos).toInt()
        val saida = ShortArray(total)
        // notas graves soam mais tempo, agudas apagam mais rápido
        val decaimento = (0.9965 + (60 - midi) * 0.00004).coerceIn(0.990, 0.9985).toFloat()
        var idx = 0
        for (i in 0 until total) {
            val prox = (idx + 1) % n
            val v = linha[idx]
            linha[idx] = decaimento * 0.5f * (v + linha[prox])
            idx = prox
            // fade-out nos últimos 80 ms para não dar estalo
            val fim = total - i
            val env = if (fim < 3528) fim / 3528f else 1f
            saida[i] = (v * env * 0.7f * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        cache[midi] = saida
        return saida
    }

    /** Toca uma nota (número MIDI). */
    fun tocar(midi: Int) {
        toques.execute { tocarAgora(midi) }
    }

    /** Toca uma sequência de notas com intervalo em ms. */
    fun tocarSequencia(midis: List<Int>, intervaloMs: Long = 320) {
        executor.execute {
            for (m in midis) {
                tocarAgora(m)
                Thread.sleep(intervaloMs)
            }
        }
    }

    private fun tocarAgora(midi: Int) {
        try {
            val dados = gerar(midi)
            val bytes = dados.size * 2
            val track = if (Build.VERSION.SDK_INT >= 23) {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STATIC)
            }
            track.write(dados, 0, dados.size)
            track.play()
            // libera depois de tocar, numa thread à parte para não atrasar a próxima nota
            Thread {
                try { Thread.sleep((dados.size * 1000L) / SR + 100) } catch (e: InterruptedException) {}
                try { track.stop() } catch (e: Exception) {}
                track.release()
            }.start()
        } catch (e: Exception) {
        }
    }
}
