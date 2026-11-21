package com.tecsattv.afinador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Mostrador com ponteiro de -50 a +50 cents. */
class TunerView(context: Context) : View(context) {

    var noteName: String = "--"
    var subText: String = ""
    var freqText: String = ""
    var hasSignal = false

    /** Desvio alvo em cents; o ponteiro anda suavemente até ele. */
    var targetCents = 0f
    private var shownCents = 0f

    private val dp = resources.displayMetrics.density
    private val verde = Color.parseColor("#2ECC71")
    private val amarelo = Color.parseColor("#F1C40F")
    private val vermelho = Color.parseColor("#E74C3C")
    private val cinza = Color.parseColor("#3A4048")
    private val texto = Color.parseColor("#ECEFF1")
    private val textoFraco = Color.parseColor("#8A949E")

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    fun colorFor(c: Float): Int = when {
        abs(c) <= 5f -> verde
        abs(c) <= 15f -> amarelo
        else -> vermelho
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // layout de cima para baixo: nota grande, arco com ponteiro, textos embaixo
        val baixo = 66 * dp                       // espaço para corda/cents e Hz
        val notaTam = min(min(w, h) * 0.20f, 84 * dp).coerceAtLeast(36 * dp)
        val r = min(w * 0.42f, h - baixo - notaTam - 24 * dp).coerceAtLeast(40 * dp)
        val cx = w / 2f
        val cy = h - baixo
        val notaY = cy - r - 16 * dp             // linha de base da nota, acima do arco

        // suavização do ponteiro
        val goal = if (hasSignal) targetCents.coerceIn(-50f, 50f) else 0f
        shownCents += (goal - shownCents) * 0.15f
        if (abs(goal - shownCents) > 0.1f) postInvalidateOnAnimation()

        // arco de fundo (180° a 360°, de -60° a +60° em torno do topo)
        val sweep = 120f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        arc.strokeWidth = 10 * dp
        arc.color = cinza
        canvas.drawArc(rect, 270f - sweep / 2, sweep, false, arc)
        // zona afinada
        arc.color = verde
        canvas.drawArc(rect, 270f - sweep / 2 * 0.1f, sweep * 0.1f, false, arc)

        // marcas a cada 10 cents
        for (c in -50..50 step 10) {
            val a = Math.toRadians((270f + c / 50f * sweep / 2).toDouble())
            val r1 = r - 18 * dp
            val r2 = if (c == 0) r - 40 * dp else r - 30 * dp
            tick.strokeWidth = if (c == 0) 4 * dp else 2 * dp
            tick.color = if (c == 0) texto else textoFraco
            canvas.drawLine(
                cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), tick
            )
        }

        // ponteiro
        val col = if (hasSignal) colorFor(shownCents) else textoFraco
        val a = Math.toRadians((270f + shownCents / 50f * sweep / 2).toDouble())
        needle.color = col
        needle.strokeWidth = 6 * dp
        val len = r - 6 * dp
        canvas.drawLine(cx, cy, cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), needle)
        canvas.drawCircle(cx, cy, 10 * dp, needle)

        // textos -50 / +50
        txt.textSize = 13 * dp
        txt.color = textoFraco
        txt.typeface = Typeface.DEFAULT
        txt.textSize = 18 * dp
        canvas.drawText("♭", cx - r * 0.92f, cy - r * 0.62f, txt)
        canvas.drawText("♯", cx + r * 0.92f, cy - r * 0.62f, txt)

        // nota grande
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.color = if (hasSignal) col else texto
        txt.textSize = notaTam
        canvas.drawText(noteName, cx, notaY, txt)

        // corda / cents
        txt.typeface = Typeface.DEFAULT
        txt.color = texto
        txt.textSize = 16 * dp
        canvas.drawText(subText, cx, cy + 34 * dp, txt)
        txt.color = textoFraco
        txt.textSize = 14 * dp
        canvas.drawText(freqText, cx, cy + 56 * dp, txt)
    }
}
