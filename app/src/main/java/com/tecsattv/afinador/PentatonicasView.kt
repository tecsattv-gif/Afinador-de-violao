package com.tecsattv.afinador

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Aba das escalas pentatônicas: 12 tônicas, maior/menor, 5 shapes e braço todo. */
class PentatonicasView(context: Context, private val prefs: SharedPreferences) : ScrollView(context) {

    private val letras = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val nomesPt = arrayOf("Dó", "Dó♯", "Ré", "Ré♯", "Mi", "Fá", "Fá♯", "Sol", "Sol♯", "Lá", "Lá♯", "Si")
    private val intervMenor = intArrayOf(0, 3, 5, 7, 10)
    private val intervMaior = intArrayOf(0, 2, 4, 7, 9)
    private val nomesGrausMenor = arrayOf("1", "♭3", "4", "5", "♭7")
    private val nomesGrausMaior = arrayOf("1", "2", "3", "5", "6")
    private val cordaSolta = intArrayOf(40, 45, 50, 55, 59, 64)   // E2 A2 D3 G3 B3 E4

    private var tonica = prefs.getInt("p_tonica", 9)        // Lá
    private var menor = prefs.getBoolean("p_menor", true)
    private var shape = prefs.getInt("p_shape", 1)          // 0 = braço todo, 1..5
    private var bordaoEmCima = prefs.getBoolean("p_bordao", true)

    private val dp = resources.displayMetrics.density
    private val verde = Color.parseColor("#2ECC71")
    private val texto = Color.parseColor("#ECEFF1")
    private val fraco = Color.parseColor("#8A949E")
    private val cartao = Color.parseColor("#1E2329")

    private val titulo = TextView(context)
    private val info = TextView(context)
    private val braco = FretboardView(context)
    private val rolagemBraco = HorizontalScrollView(context)
    private val btBordao = TextView(context)
    private val botoesTonica = mutableListOf<TextView>()
    private val botoesTipo = mutableListOf<TextView>()
    private val botoesShape = mutableListOf<TextView>()

    init {
        isFillViewport = true
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        addView(col)

        titulo.apply { setTextColor(texto); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        info.apply { setTextColor(fraco); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt()) }
        col.addView(titulo)
        col.addView(info)

        // tônicas em duas linhas de 6
        rotulo(col, "Tônica")
        for (linhaIdx in 0..1) {
            val l = linha()
            for (i in linhaIdx * 6 until linhaIdx * 6 + 6) {
                val b = botao("${nomesPt[i]}\n${letras[i]}") { tonica = i; atualizar() }
                botoesTonica.add(b)
                l.addView(b, peso())
            }
            col.addView(l)
        }

        // maior / menor
        rotulo(col, "Tipo")
        val lt = linha()
        val bMaior = botao("Maior") { menor = false; atualizar() }
        val bMenor = botao("Menor") { menor = true; atualizar() }
        botoesTipo.add(bMaior); botoesTipo.add(bMenor)
        lt.addView(bMaior, peso()); lt.addView(bMenor, peso())
        col.addView(lt)

        // shapes
        rotulo(col, "Shape")
        val ls = linha()
        val bTodo = botao("Braço\ntodo") { shape = 0; atualizar() }
        botoesShape.add(bTodo)
        ls.addView(bTodo, peso(1.4f))
        for (s in 1..5) {
            val b = botao("$s") { shape = s; atualizar() }
            botoesShape.add(b)
            ls.addView(b, peso())
        }
        col.addView(ls)

        // braço
        rolagemBraco.isHorizontalScrollBarEnabled = true
        rolagemBraco.addView(braco)
        col.addView(rolagemBraco, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * dp).toInt()
        })

        // inverter bordão
        btBordao.apply {
            gravity = Gravity.CENTER; textSize = 15f; setTextColor(texto)
            background = fundo(cartao, cartao)
            setOnClickListener { bordaoEmCima = !bordaoEmCima; atualizar(rolar = false) }
        }
        // botões: tocar shape + inverter bordão
        val lb = linha()
        val btTocar = TextView(context).apply {
            text = "▶  Tocar shape"; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(texto); background = fundo(cartao, cartao)
            minHeight = (48 * dp).toInt()
            setOnClickListener { tocarShape() }
        }
        btBordao.minHeight = (48 * dp).toInt()
        lb.addView(btTocar, peso())
        lb.addView(btBordao, peso())
        col.addView(lb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        })

        braco.aoTocar = { n -> Som.tocar(n.midi) }

        col.addView(TextView(context).apply {
            text = "Toque numa bolinha para ouvir a nota\nLaranja = tônica · verde-água = shape · cinza = resto da escala\nArraste o braço para os lados para ver todas as casas"
            setTextColor(fraco); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })

        atualizar()
    }

    // ---------- cálculo ----------

    private fun intervalos() = if (menor) intervMenor else intervMaior
    private fun pcs(): Set<Int> = intervalos().map { (tonica + it) % 12 }.toSet()

    /** Notas do shape [s] (1..5): duas notas por corda, subindo da 6ª à 1ª. */
    private fun notasShape(s: Int): List<Pair<Int, Int>> {
        val iv = intervalos()
        val pcInicio = (tonica + iv[s - 1]) % 12
        val casa6 = (pcInicio - 4 + 12) % 12
        val escala = pcs()
        val alturas = mutableListOf<Int>()
        var p = cordaSolta[0] + casa6
        while (alturas.size < 12) { if ((p % 12) in escala) alturas.add(p); p++ }
        var res = (0..5).flatMap { c -> listOf(c to alturas[2 * c] - cordaSolta[c], c to alturas[2 * c + 1] - cordaSolta[c]) }
        if (res.minOf { it.second } < 0) res = res.map { it.first to it.second + 12 }
        if (res.maxOf { it.second } > braco.casas && res.minOf { it.second } >= 12) res = res.map { it.first to it.second - 12 }
        return res
    }

    private fun nomeNota(pc: Int) = letras[pc]

    /** Toca as notas do shape subindo e depois descendo (no braço todo: 1ª oitava a partir da tônica mais grave). */
    private fun tocarShape() {
        val ativas = braco.notas.filter { it.ativa }
        val seq: List<FretboardView.Nota> = if (shape != 0) {
            ativas.sortedWith(compareBy({ it.midi }, { it.corda }))
                .distinctBy { it.midi }
        } else {
            val porMidi = ativas.sortedWith(compareBy({ it.midi }, { -it.corda })).distinctBy { it.midi }
            val ini = porMidi.indexOfFirst { it.tonica }.coerceAtLeast(0)
            porMidi.drop(ini).take(11)
        }
        if (seq.isEmpty()) return
        val idaVolta = seq + seq.dropLast(1).reversed()
        Som.tocarSequencia(idaVolta.map { it.midi })
        idaVolta.forEachIndexed { i, n -> braco.postDelayed({ braco.acender(n) }, i * 320L) }
    }

    private fun atualizar(rolar: Boolean = true) {
        prefs.edit().putInt("p_tonica", tonica).putBoolean("p_menor", menor)
            .putInt("p_shape", shape).putBoolean("p_bordao", bordaoEmCima).apply()

        val escala = pcs()
        val noShape: Set<Pair<Int, Int>> = if (shape == 0) emptySet() else notasShape(shape).toSet()
        val lista = mutableListOf<FretboardView.Nota>()
        for (c in 0..5) for (casa in 0..braco.casas) {
            val pc = (cordaSolta[c] + casa) % 12
            if (pc !in escala) continue
            val ativa = shape == 0 || (c to casa) in noShape
            lista.add(FretboardView.Nota(c, casa, nomeNota(pc), pc == tonica, ativa, cordaSolta[c] + casa))
        }
        braco.bordaoEmCima = bordaoEmCima
        braco.notas = lista

        val tipo = if (menor) "menor" else "maior"
        titulo.text = "${nomesPt[tonica]} $tipo pentatônica"
        val relativa = if (menor) "${nomesPt[(tonica + 3) % 12]} maior" else "${nomesPt[(tonica + 9) % 12]} menor"
        val graus = if (menor) nomesGrausMenor else nomesGrausMaior
        val notasTxt = intervalos().mapIndexed { i, iv -> "${letras[(tonica + iv) % 12]} (${graus[i]})" }.joinToString("  ")
        info.text = "$notasTxt\nRelativa: $relativa pentatônica (mesmas notas)"

        btBordao.text = if (bordaoEmCima) "⇅  Mi bordão em cima" else "⇅  Mi bordão embaixo"

        botoesTonica.forEachIndexed { i, b -> marcar(b, i == tonica) }
        marcar(botoesTipo[0], !menor); marcar(botoesTipo[1], menor)
        botoesShape.forEachIndexed { i, b -> marcar(b, i == shape) }

        if (rolar) rolagemBraco.post {
            val alvo = if (shape == 0) 0 else
                (braco.xCasa(noShape.minOf { it.second }) - braco.margemEsq).toInt().coerceAtLeast(0)
            rolagemBraco.smoothScrollTo(alvo, 0)
        }
    }

    // ---------- componentes ----------

    private fun m() = (3 * dp).toInt()
    private fun linha() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun peso(p: Float = 1f) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, p).apply { setMargins(m(), m(), m(), m()) }

    private fun rotulo(col: LinearLayout, t: String) {
        col.addView(TextView(context).apply {
            text = t; setTextColor(fraco); textSize = 13f
            setPadding(m(), (8 * dp).toInt(), 0, 0)
        })
    }

    private fun fundo(cor: Int, borda: Int) = GradientDrawable().apply {
        cornerRadius = 12 * dp; setColor(cor); setStroke((1.5f * dp).toInt(), borda)
    }

    private fun botao(rot: String, acao: () -> Unit) = TextView(context).apply {
        text = rot; gravity = Gravity.CENTER; textSize = 13f
        minHeight = (44 * dp).toInt()
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        setOnClickListener { acao() }
    }

    private fun marcar(b: TextView, ativo: Boolean) {
        b.background = fundo(if (ativo) Color.parseColor("#163A27") else cartao, if (ativo) verde else cartao)
        b.setTextColor(if (ativo) verde else texto)
    }
}
