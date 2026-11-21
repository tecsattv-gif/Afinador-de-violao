package com.tecsattv.afinador

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // Cordas do violão (afinação padrão), do bordão (6ª) para a prima (1ª).
    // Semitons em relação ao Lá 440 (A4).
    private data class Corda(val numero: Int, val nome: String, val letra: String, val semitons: Int)

    private val cordas = listOf(
        Corda(6, "Mi", "E2", -29),
        Corda(5, "Lá", "A2", -24),
        Corda(4, "Ré", "D3", -19),
        Corda(3, "Sol", "G3", -14),
        Corda(2, "Si", "B3", -10),
        Corda(1, "Mi", "E4", -5)
    )
    private val notasPt = arrayOf("Dó", "Dó♯", "Ré", "Ré♯", "Mi", "Fá", "Fá♯", "Sol", "Sol♯", "Lá", "Lá♯", "Si")

    // modo: -1 = Auto, -2 = Cromático, 0..5 = corda fixa (índice em `cordas`)
    private var modo = -1
    private var la = 440

    private lateinit var tuner: TunerView
    private lateinit var dica: TextView
    private lateinit var laTxt: TextView
    private val botoesModo = mutableListOf<Pair<Int, TextView>>()

    @Volatile private var rodando = false
    private var thread: Thread? = null

    private val historico = ArrayDeque<Float>()
    private var ultimoSom = 0L

    private val dp by lazy { resources.displayMetrics.density }
    private val verde = Color.parseColor("#2ECC71")
    private val texto = Color.parseColor("#ECEFF1")
    private val fraco = Color.parseColor("#8A949E")
    private val cartao = Color.parseColor("#1E2329")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getPreferences(MODE_PRIVATE)
        la = prefs.getInt("la", 440)
        modo = prefs.getInt("modo", -1)
        setContentView(montarTela())
        atualizarBotoes()
    }

    override fun onResume() {
        super.onResume()
        if (temPermissao()) iniciarAudio()
        else if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onPause() {
        super.onPause()
        pararAudio()
        getPreferences(MODE_PRIVATE).edit().putInt("la", la).putInt("modo", modo).apply()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) iniciarAudio()
        else dica.text = "Permita o microfone para afinar"
    }

    private fun temPermissao(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- Tela ----------

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }

        raiz.addView(TextView(this).apply {
            text = "Afinador de violão"
            setTextColor(texto); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        raiz.addView(TextView(this).apply {
            text = "Afinação padrão · E A D G B E"
            setTextColor(fraco); textSize = 14f; gravity = Gravity.CENTER
        })

        tuner = TunerView(this)
        raiz.addView(tuner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        dica = TextView(this).apply {
            text = "Toque uma corda"
            setTextColor(texto); textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        raiz.addView(dica)

        // linha 1: Auto / Cromático
        val linha1 = linha()
        linha1.addView(botao("Auto\ncorda mais próxima", -1), peso())
        linha1.addView(botao("Cromático\nqualquer nota", -2), peso())
        raiz.addView(linha1)

        // linha 2: as 6 cordas
        val linha2 = linha()
        cordas.forEachIndexed { i, c -> linha2.addView(botao("${c.numero}ª\n${c.nome}", i), peso()) }
        raiz.addView(linha2)

        // calibração
        val linha3 = linha()
        val menos = botaoSimples("−") { mudarLa(-1) }
        val mais = botaoSimples("+") { mudarLa(1) }
        laTxt = TextView(this).apply {
            setTextColor(texto); textSize = 15f; gravity = Gravity.CENTER
        }
        linha3.addView(menos, LinearLayout.LayoutParams((56 * dp).toInt(), (48 * dp).toInt()).apply { setMargins(m(), m(), m(), m()) })
        linha3.addView(laTxt, peso())
        linha3.addView(mais, LinearLayout.LayoutParams((56 * dp).toInt(), (48 * dp).toInt()).apply { setMargins(m(), m(), m(), m()) })
        raiz.addView(linha3)
        atualizarLa()

        raiz.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} · Luiz Alberto Cardoso"
            setTextColor(fraco); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        return raiz
    }

    private fun m() = (3 * dp).toInt()
    private fun linha() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private fun peso() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m(), m(), m(), m()) }

    private fun fundo(cor: Int, borda: Int) = GradientDrawable().apply {
        cornerRadius = 12 * dp; setColor(cor); setStroke((1.5f * dp).toInt(), borda)
    }

    private fun botao(rotulo: String, valor: Int): TextView {
        val b = TextView(this).apply {
            text = rotulo; gravity = Gravity.CENTER; textSize = 14f
            minHeight = (52 * dp).toInt()
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                modo = valor
                historico.clear()
                atualizarBotoes()
            }
        }
        botoesModo.add(valor to b)
        return b
    }

    private fun botaoSimples(rotulo: String, acao: () -> Unit) = TextView(this).apply {
        text = rotulo; gravity = Gravity.CENTER; textSize = 22f
        setTextColor(texto); background = fundo(cartao, cartao)
        setOnClickListener { acao() }
    }

    private fun atualizarBotoes() {
        for ((v, b) in botoesModo) {
            val ativo = v == modo
            b.background = fundo(if (ativo) Color.parseColor("#163A27") else cartao, if (ativo) verde else cartao)
            b.setTextColor(if (ativo) verde else texto)
        }
    }

    private fun mudarLa(d: Int) {
        la = (la + d).coerceIn(430, 450)
        atualizarLa()
    }

    private fun atualizarLa() {
        laTxt.text = "Calibração: Lá = $la Hz"
    }

    // ---------- Áudio ----------

    private fun iniciarAudio() {
        if (rodando) return
        rodando = true
        thread = Thread {
            val sr = 44100
            val tam = 4096
            val passo = 1024
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, tam * 2))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                runOnUiThread { dica.text = "Não foi possível abrir o microfone" }
                rodando = false
                rec?.release()
                return@Thread
            }
            val detector = PitchDetector(sr, tam)
            val janela = FloatArray(tam)
            val leitura = ShortArray(passo)
            rec.startRecording()
            while (rodando) {
                val n = rec.read(leitura, 0, passo)
                if (n <= 0) continue
                System.arraycopy(janela, n, janela, 0, tam - n)
                for (i in 0 until n) janela[tam - n + i] = leitura[i] / 32768f
                val volume = detector.rms(janela)
                val f = if (volume > 0.008f) detector.detect(janela) else -1f
                runOnUiThread { mostrar(f) }
            }
            rec.stop()
            rec.release()
        }.also { it.start() }
    }

    private fun pararAudio() {
        rodando = false
        thread?.join(500)
        thread = null
    }

    // ---------- Cálculo e exibição ----------

    private fun freqDe(semitons: Int) = la * 2.0.pow(semitons / 12.0)
    private fun cents(f: Double, alvo: Double) = 1200.0 * ln(f / alvo) / ln(2.0)

    private fun mostrar(fBruta: Float) {
        val agora = SystemClock.elapsedRealtime()
        if (fBruta in 60f..1100f) {
            historico.addLast(fBruta)
            while (historico.size > 5) historico.removeFirst()
            ultimoSom = agora
        } else {
            if (agora - ultimoSom > 1500) {
                historico.clear()
                tuner.hasSignal = false
                tuner.noteName = when (modo) {
                    -1, -2 -> "--"
                    else -> cordas[modo].nome
                }
                tuner.subText = if (modo >= 0) "${cordas[modo].numero}ª corda · ${cordas[modo].letra}" else ""
                tuner.freqText = ""
                dica.text = "Toque uma corda"
                dica.setTextColor(texto)
                tuner.invalidate()
            }
            return
        }

        val f = historico.sorted()[historico.size / 2].toDouble()
        val c: Double
        when (modo) {
            -2 -> {
                val midi = 69 + 12 * ln(f / la) / ln(2.0)
                val n = midi.roundToInt()
                c = (midi - n) * 100
                val oitava = n / 12 - 1
                tuner.noteName = notasPt[((n % 12) + 12) % 12]
                tuner.subText = "oitava $oitava · ${formatarCents(c)}"
            }
            else -> {
                val corda = if (modo >= 0) cordas[modo]
                else cordas.minByOrNull { abs(cents(f, freqDe(it.semitons))) }!!
                c = cents(f, freqDe(corda.semitons))
                tuner.noteName = corda.nome
                tuner.subText = "${corda.numero}ª corda · ${corda.letra} · ${formatarCents(c)}"
                if (modo == -1) destacarAuto(cordas.indexOf(corda))
            }
        }
        tuner.freqText = String.format("%.1f Hz", f)
        tuner.hasSignal = true
        tuner.targetCents = c.toFloat()
        tuner.invalidate()

        when {
            abs(c) <= 5 -> { dica.text = "Afinado!"; dica.setTextColor(verde) }
            c < 0 -> { dica.text = if (c < -50) "Muito grave · aperte a corda" else "Grave · aperte um pouco"; dica.setTextColor(tuner.colorFor(c.toFloat())) }
            else -> { dica.text = if (c > 50) "Muito agudo · afrouxe a corda" else "Agudo · afrouxe um pouco"; dica.setTextColor(tuner.colorFor(c.toFloat())) }
        }
    }

    /** No modo Auto, contorna a corda reconhecida. */
    private fun destacarAuto(indice: Int) {
        for ((v, b) in botoesModo) {
            if (v < 0) continue
            val ativo = v == indice
            b.background = fundo(cartao, if (ativo) verde else cartao)
            b.setTextColor(if (ativo) verde else texto)
        }
    }

    private fun formatarCents(c: Double): String {
        val r = c.roundToInt()
        return if (r > 0) "+$r cents" else "$r cents"
    }
}
