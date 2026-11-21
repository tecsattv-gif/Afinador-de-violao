package com.tecsattv.afinador

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Importa songbooks: lê cada PDF, encontra as músicas e os títulos,
 * mostra a lista para conferir e cria um PDF por música (ou aula).
 */
class SeparacaoFluxo(
    private val act: MainActivity,
    private val uris: List<Uri>,
    private val nomes: List<String>,
    private val cifraPadrao: String,
    private val aulaPadrao: String,
    private val aoTerminar: () -> Unit
) {
    private val dp = act.resources.displayMetrics.density
    private val texto = Color.parseColor("#ECEFF1")
    private val fraco = Color.parseColor("#8A949E")
    private val verde = Color.parseColor("#2ECC71")
    private val cartao = Color.parseColor("#1E2329")
    private var k = 0

    fun iniciar() = proximo()

    private fun proximo() {
        if (k >= uris.size) { aoTerminar(); return }
        val uri = uris[k]
        val nome = nomes[k].ifBlank { "PDF ${k + 1}" }
        k++
        val espera = esperando("Lendo \"$nome\"…")
        Thread {
            val tmp = File(act.cacheDir, "importar-${System.currentTimeMillis()}.pdf")
            val res = try {
                act.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                Separador.analisar(tmp)
            } catch (e: Throwable) { null }
            act.runOnUiThread {
                espera.dismiss()
                when {
                    res == null -> { aviso("Não foi possível ler \"$nome\""); tmp.delete(); proximo() }
                    res.semTexto -> semTexto(tmp, nome)
                    else -> revisar(tmp, nome, res)
                }
            }
        }.start()
    }

    /** PDF escaneado (só imagem): não há texto para ler. */
    private fun semTexto(tmp: File, nome: String) {
        AlertDialog.Builder(act).setTitle(nome)
            .setMessage("Este PDF é uma imagem (escaneado), então não dá para ler os títulos automaticamente.\n\nPosso guardá-lo inteiro em Cifras › $cifraPadrao; depois use ✂ Separar no leitor para dividir as músicas.")
            .setPositiveButton("Guardar inteiro") { _, _ ->
                val dir = Biblioteca.pasta(act, "cifras", cifraPadrao).apply { mkdirs() }
                tmp.copyTo(Biblioteca.arquivoLivre(dir, Biblioteca.nomeSeguro(nome.removeSuffix(".pdf"))))
                tmp.delete(); proximo()
            }
            .setNegativeButton("Pular") { _, _ -> tmp.delete(); proximo() }
            .setCancelable(false).show()
    }

    private fun revisar(tmp: File, nome: String, res: Separador.Resultado) {
        val trechos = res.trechos
        val caixa = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        val resumo = TextView(act).apply { setTextColor(fraco); textSize = 13f }
        caixa.addView(resumo)

        caixa.addView(rotulo("Músicas vão para o gênero"))
        val spGenero = spinner(Biblioteca.categorias(act, "cifras"), cifraPadrao)
        caixa.addView(spGenero)
        val novoGenero = EditText(act).apply { hint = "ou escreva um gênero novo"; isSingleLine = true; textSize = 14f }
        caixa.addView(novoGenero)
        caixa.addView(rotulo("Aulas vão para a categoria"))
        val spCat = spinner(Biblioteca.categorias(act, "aulas"), aulaPadrao)
        caixa.addView(spCat)
        val guardarInteiro = CheckBox(act).apply { text = "Guardar também o PDF completo"; textSize = 14f }
        caixa.addView(guardarInteiro)

        caixa.addView(rotulo("Confira os títulos (toque em Música/Aula para trocar, ⤒ junta com a de cima)"))
        val linhas = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        caixa.addView(linhas)

        fun desenhar() {
            linhas.removeAllViews()
            val nMus = trechos.count { it.incluir && it.musica }
            val nAula = trechos.count { it.incluir && !it.musica }
            resumo.text = "${res.paginas} páginas · $nMus música(s) e $nAula aula(s) encontradas.\nOs títulos foram lidos do PDF — corrija se precisar."
            trechos.forEachIndexed { i, t ->
                val linha = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                    background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(cartao) }
                }
                val topo = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
                topo.addView(CheckBox(act).apply {
                    isChecked = t.incluir
                    setOnCheckedChangeListener { _, c -> t.incluir = c }
                })
                topo.addView(EditText(act).apply {
                    setText(t.titulo); isSingleLine = true; textSize = 15f; setTextColor(texto)
                    typeface = Typeface.DEFAULT_BOLD
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { t.titulo = s.toString() }
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                linha.addView(topo)

                val baixo = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
                baixo.addView(TextView(act).apply {
                    text = if (t.ini == t.fim) "pág. ${t.ini + 1}" else "pág. ${t.ini + 1}–${t.fim + 1}"
                    setTextColor(fraco); textSize = 13f
                    setPadding((8 * dp).toInt(), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                baixo.addView(pilula(if (t.musica) "Música" else "Aula", t.musica) {
                    t.musica = !t.musica; desenhar()
                })
                if (i > 0) baixo.addView(pilula("⤒ Juntar", false) {
                    val ant = trechos[i - 1]
                    ant.fim = t.fim
                    trechos.removeAt(i)
                    desenhar()
                })
                linha.addView(baixo)
                linhas.addView(linha, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (6 * dp).toInt()
                })
            }
        }
        desenhar()

        AlertDialog.Builder(act).setTitle(nome)
            .setView(ScrollView(act).apply { addView(caixa) })
            .setPositiveButton("Separar") { _, _ ->
                val genero = if (novoGenero.text.isNotBlank()) Biblioteca.nomeSeguro(novoGenero.text.toString())
                else (spGenero.selectedItem as? String) ?: "Geral"
                val cat = (spCat.selectedItem as? String) ?: "Geral"
                separar(tmp, nome, trechos.filter { it.incluir }, genero, cat, guardarInteiro.isChecked)
            }
            .setNegativeButton("Cancelar") { _, _ -> tmp.delete(); proximo() }
            .setCancelable(false).show()
    }

    private fun separar(tmp: File, nome: String, lista: List<Separador.Trecho>, genero: String, cat: String, inteiro: Boolean) {
        val espera = esperando("Criando ${lista.size} PDF(s)…")
        Thread {
            var ok = 0
            try {
                val partes = lista.map { t ->
                    val tipo = if (t.musica) "cifras" else "aulas"
                    val dir = Biblioteca.pasta(act, tipo, if (t.musica) genero else cat).apply { mkdirs() }
                    t to Biblioteca.arquivoLivre(dir, Biblioteca.nomeSeguro(t.titulo))
                }
                Separador.extrairVarios(tmp, partes) { feito ->
                    ok = feito
                    act.runOnUiThread { espera.setMessage("Criando PDFs… $feito de ${lista.size}") }
                }
                if (inteiro) {
                    val dir = Biblioteca.pasta(act, "cifras", genero).apply { mkdirs() }
                    tmp.copyTo(Biblioteca.arquivoLivre(dir, Biblioteca.nomeSeguro(nome.removeSuffix(".pdf")) + " (completo)"))
                }
            } catch (e: Throwable) {}
            tmp.delete()
            act.runOnUiThread {
                espera.dismiss()
                aviso("$ok de ${lista.size} PDF(s) criados")
                act.recarregarBibliotecas()
                proximo()
            }
        }.start()
    }

    // ---------- componentes ----------

    private fun esperando(msg: String): AlertDialog {
        val caixa = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            addView(ProgressBar(act))
        }
        val d = AlertDialog.Builder(act).setView(caixa).setMessage(msg).setCancelable(false).create()
        d.show()
        return d
    }

    private fun rotulo(t: String) = TextView(act).apply {
        text = t; setTextColor(fraco); textSize = 13f; setPadding(0, (10 * dp).toInt(), 0, 0)
    }

    private fun spinner(itens: List<String>, sel: String) = Spinner(act).apply {
        adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, itens)
        setSelection(itens.indexOf(sel).coerceAtLeast(0))
    }

    private fun pilula(t: String, ativo: Boolean, acao: () -> Unit) = TextView(act).apply {
        text = t; textSize = 13f; gravity = Gravity.CENTER
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        setTextColor(if (ativo) verde else texto)
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(Color.TRANSPARENT)
            setStroke((1.2f * dp).toInt(), if (ativo) verde else fraco)
        }
        setOnClickListener { acao() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (6 * dp).toInt()
        }
    }

    private fun aviso(t: String) = Toast.makeText(act, t, Toast.LENGTH_LONG).show()
}
