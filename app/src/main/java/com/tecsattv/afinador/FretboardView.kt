package com.tecsattv.afinador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Braço do violão (casas 0 a [casas]) com as notas marcadas.
 * Cada nota: corda (0 = 6ª/bordão … 5 = 1ª/prima), casa, nome e se é tônica.
 */
class FretboardView(context: Context) : View(context) {

    data class Nota(val corda: Int, val casa: Int, val nome: String, val tonica: Boolean, val ativa: Boolean, val midi: Int)

    /** Chamado quando o usuário toca numa bolinha. */
    var aoTocar: ((Nota) -> Unit)? = null
    private var acesa: Nota? = null
    private val apagar = Runnable { acesa = null; invalidate() }

    /** Acende a bolinha por um instante (ao tocar o som). */
    fun acender(n: Nota) {
        acesa = n
        removeCallbacks(apagar)
        invalidate()
        postDelayed(apagar, 350)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val alvo = notas.minByOrNull { hypot(xCasa(it.casa) - e.x, yCorda(it.corda) - e.y) }
                if (alvo != null && hypot(xCasa(alvo.casa) - e.x, yCorda(alvo.corda) - e.y) < 22 * dp) {
                    acender(alvo)
                    aoTocar?.invoke(alvo)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    var notas: List<Nota> = emptyList()
        set(v) { field = v; invalidate() }
    var bordaoEmCima = true
        set(v) { field = v; invalidate() }

    val casas = 17
    private val dp = resources.displayMetrics.density
    val margemEsq = 44 * dp        // coluna das cordas soltas
    val larguraCasa = 54 * dp
    private val espCorda = 32 * dp
    private val topo = 24 * dp

    private val nomesCordas = arrayOf("E", "A", "D", "G", "B", "e")

    private val linha = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circ = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val corTexto = Color.parseColor("#ECEFF1")
    private val corFraco = Color.parseColor("#8A949E")
    private val corTraste = Color.parseColor("#59626C")
    private val corCorda = Color.parseColor("#B0B7BE")
    private val corTonica = Color.parseColor("#E67E22")
    private val corNota = Color.parseColor("#1ABC9C")
    private val corInativa = Color.parseColor("#2A3037")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (margemEsq + casas * larguraCasa + 16 * dp).toInt()
        val h = (topo + 5 * espCorda + 56 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    /** posição x do centro de uma casa (0 = solta) */
    fun xCasa(casa: Int): Float =
        if (casa == 0) margemEsq / 2 + 6 * dp else margemEsq + (casa - 0.5f) * larguraCasa

    fun yCorda(corda: Int): Float {
        val linhaVisual = if (bordaoEmCima) corda else 5 - corda
        return topo + linhaVisual * espCorda
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y0 = topo
        val y5 = topo + 5 * espCorda
        val xFim = margemEsq + casas * larguraCasa

        // marcações do braço (3, 5, 7, 9, 12, 15)
        circ.color = Color.parseColor("#2A3037")
        val meio = (y0 + y5) / 2
        for (c in intArrayOf(3, 5, 7, 9, 15)) canvas.drawCircle(margemEsq + (c - 0.5f) * larguraCasa, meio, 6 * dp, circ)
        canvas.drawCircle(margemEsq + 11.5f * larguraCasa, meio - espCorda, 6 * dp, circ)
        canvas.drawCircle(margemEsq + 11.5f * larguraCasa, meio + espCorda, 6 * dp, circ)

        // trastes
        linha.color = corTexto
        linha.strokeWidth = 5 * dp
        canvas.drawLine(margemEsq, y0, margemEsq, y5, linha)          // pestana
        linha.color = corTraste
        linha.strokeWidth = 1.5f * dp
        for (c in 1..casas) {
            val x = margemEsq + c * larguraCasa
            canvas.drawLine(x, y0, x, y5, linha)
        }
        // cordas (mais grossas no bordão)
        for (corda in 0..5) {
            val y = yCorda(corda)
            linha.color = corCorda
            linha.strokeWidth = (2.4f - corda * 0.3f) * dp
            canvas.drawLine(margemEsq, y, xFim, y, linha)
            txt.textSize = 12 * dp; txt.color = corFraco; txt.typeface = Typeface.DEFAULT
            canvas.drawText(nomesCordas[corda], 10 * dp, y + 4 * dp, txt)
        }
        // números das casas
        txt.textSize = 12 * dp; txt.color = corFraco
        for (c in 0..casas) canvas.drawText("$c", xCasa(c), y5 + 30 * dp, txt)

        // notas: primeiro as inativas, depois as do shape
        for (passo in 0..1) for (n in notas) {
            if ((passo == 0) == n.ativa) continue
            val x = xCasa(n.casa)
            val y = yCorda(n.corda)
            circ.style = Paint.Style.FILL
            circ.color = when {
                !n.ativa -> corInativa
                n.tonica -> corTonica
                else -> corNota
            }
            val viva = acesa?.let { it.corda == n.corda && it.casa == n.casa } == true
            if (viva) {
                circ.color = Color.WHITE
                canvas.drawCircle(x, y, 17 * dp, circ)
                circ.color = if (n.tonica) corTonica else corNota
            }
            canvas.drawCircle(x, y, 13 * dp, circ)
            txt.typeface = Typeface.DEFAULT_BOLD
            txt.textSize = (if (n.nome.length > 1) 10 else 12) * dp
            txt.color = if (n.ativa) Color.WHITE else corFraco
            canvas.drawText(n.nome, x, y + 4 * dp, txt)
        }
    }
}
