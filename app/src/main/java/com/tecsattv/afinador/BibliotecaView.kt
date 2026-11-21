package com.tecsattv.afinador

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Aba de biblioteca de PDFs. tipo = "aulas" (categorias) ou "cifras" (gêneros).
 */
class BibliotecaView(private val act: MainActivity, val tipo: String) : LinearLayout(act) {

    private val dp = resources.displayMetrics.density
    private val verde = Color.parseColor("#2ECC71")
    private val texto = Color.parseColor("#ECEFF1")
    private val fraco = Color.parseColor("#8A949E")
    private val cartao = Color.parseColor("#1E2329")

    private val palavraCat = if (tipo == "aulas") "categoria" else "gênero"
    private val prefs = act.getPreferences(android.content.Context.MODE_PRIVATE)
    private var categoria = prefs.getString("b_${tipo}_cat", "Geral") ?: "Geral"

    private val linhaCats = LinearLayout(act).apply { orientation = HORIZONTAL }
    private val busca = EditText(act)
    private val lista = ListView(act)
    private val vazio = TextView(act)
    private var itens: List<File> = emptyList()
    private val adaptador = Adaptador()

    init {
        orientation = VERTICAL
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())

        addView(TextView(act).apply {
            text = if (tipo == "aulas") "Categorias" else "Gêneros"
            setTextColor(fraco); textSize = 13f
        })
        addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(linhaCats)
        })

        busca.apply {
            hint = if (tipo == "aulas") "Buscar aula…" else "Buscar música…"
            setHintTextColor(fraco); setTextColor(texto); textSize = 16f
            isSingleLine = true
            background = fundo(cartao, Color.parseColor("#3A4048"))
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { recarregarLista() }
            })
        }
        addView(busca, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(m(), (8 * dp).toInt(), m(), m())
        })

        val botoes = LinearLayout(act).apply { orientation = HORIZONTAL }
        botoes.addView(botao("＋  Importar PDF") { act.pedirPdfs(this) }, peso(1.6f))
        botoes.addView(botao("Backup") { dialogoBackup() }, peso(1f))
        addView(botoes)

        vazio.apply {
            setTextColor(fraco); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, 0)
        }
        addView(vazio)

        lista.apply {
            divider = null
            adapter = adaptador
            setOnItemClickListener { _, _, pos, _ -> abrir(itens[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> opcoesArquivo(itens[pos]); true }
        }
        addView(lista, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(TextView(act).apply {
            text = "Toque para abrir · toque longo para renomear, mover ou apagar"
            setTextColor(fraco); textSize = 12f; gravity = Gravity.CENTER
        })

        recarregar()
    }

    /** Atualiza categorias e lista (chamado também ao voltar do leitor). */
    fun recarregar() {
        val cats = Biblioteca.categorias(act, tipo)
        if (categoria !in cats) categoria = cats.first()
        prefs.edit().putString("b_${tipo}_cat", categoria).apply()
        linhaCats.removeAllViews()
        for (c in cats) {
            val n = Biblioteca.arquivos(act, tipo, c).size
            val b = chip("$c ($n)", c == categoria)
            b.setOnClickListener { categoria = c; recarregar() }
            b.setOnLongClickListener { opcoesCategoria(c); true }
            linhaCats.addView(b)
        }
        linhaCats.addView(chip("＋ Novo $palavraCat", false).apply { setOnClickListener { novaCategoria() } })
        recarregarLista()
    }

    private fun recarregarLista() {
        val q = busca.text.toString().trim()
        itens = if (q.isEmpty()) Biblioteca.arquivos(act, tipo, categoria)
        else Biblioteca.todos(act, tipo).filter { Biblioteca.titulo(it).contains(q, true) }
        adaptador.notifyDataSetChanged()
        vazio.visibility = if (itens.isEmpty()) View.VISIBLE else View.GONE
        vazio.text = if (q.isNotEmpty()) "Nada encontrado para \"$q\""
        else "Nenhum PDF em \"$categoria\".\nToque em ＋ Importar PDF para adicionar."
    }

    private fun abrir(f: File) {
        act.startActivity(Intent(act, PdfActivity::class.java).putExtra("caminho", f.absolutePath))
    }

    // ---------- importação ----------

    private val palavrasAula = listOf(
        "aula", "teoria", "tutorial", "licao", "lição", "curso", "metodo", "método", "apostila",
        "escala", "harmonia", "acorde", "exercicio", "exercício", "tecnica", "técnica", "ritmo",
        "campo harmonico", "campo harmônico", "intervalo", "modo", "leitura", "partitura", "dicionario", "dicionário"
    )

    fun pareceAula(nome: String): Boolean {
        val n = nome.lowercase()
        return palavrasAula.any { n.contains(it) }
    }

    /** Recebe os PDFs escolhidos e pergunta onde guardar. */
    fun receberPdfs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val nomes = uris.map { nomeDe(it) }
        val aulas = nomes.count { pareceAula(it) }
        val opcoes = arrayOf(
            "Separar automaticamente pelo nome ($aulas aula(s), ${uris.size - aulas} cifra(s))",
            "Tudo em ${if (tipo == "aulas") "Aulas" else "Cifras"} › $categoria"
        )
        AlertDialog.Builder(act)
            .setTitle("Importar ${uris.size} PDF(s)")
            .setItems(opcoes) { _, qual ->
                var ok = 0
                uris.forEachIndexed { i, u ->
                    val destino = if (qual == 1) Biblioteca.pasta(act, tipo, categoria) else {
                        val t = if (pareceAula(nomes[i])) "aulas" else "cifras"
                        // mantém a categoria atual se for do mesmo tipo; senão vai para "Geral"
                        Biblioteca.pasta(act, t, if (t == tipo) categoria else "Geral")
                    }
                    if (Biblioteca.importar(act, u, destino) != null) ok++
                }
                act.recarregarBibliotecas()
                aviso("$ok de ${uris.size} PDF(s) importado(s)")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nomeDe(u: Uri): String {
        var nome = ""
        try {
            act.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) nome = c.getString(0) ?: ""
            }
        } catch (e: Exception) {}
        return nome
    }

    // ---------- categorias ----------

    private fun novaCategoria() {
        pedirTexto("Novo $palavraCat", "") { nome ->
            val n = Biblioteca.nomeSeguro(nome)
            Biblioteca.pasta(act, tipo, n).mkdirs()
            categoria = n
            recarregar()
        }
    }

    private fun opcoesCategoria(c: String) {
        AlertDialog.Builder(act).setTitle(c)
            .setItems(arrayOf("Renomear", "Apagar")) { _, i ->
                if (i == 0) pedirTexto("Renomear $palavraCat", c) { novo ->
                    val n = Biblioteca.nomeSeguro(novo)
                    val alvo = Biblioteca.pasta(act, tipo, n)
                    if (alvo.exists()) aviso("Já existe \"$n\"")
                    else { Biblioteca.pasta(act, tipo, c).renameTo(alvo); categoria = n; recarregar() }
                } else {
                    val qtd = Biblioteca.arquivos(act, tipo, c).size
                    confirmar("Apagar \"$c\"?", if (qtd > 0) "Os $qtd PDF(s) dentro dela também serão apagados." else "A pasta está vazia.") {
                        Biblioteca.pasta(act, tipo, c).deleteRecursively()
                        recarregar()
                    }
                }
            }.show()
    }

    // ---------- arquivos ----------

    private fun opcoesArquivo(f: File) {
        AlertDialog.Builder(act).setTitle(Biblioteca.titulo(f))
            .setItems(arrayOf("Abrir", "Renomear", "Mover para…", "Apagar")) { _, i ->
                when (i) {
                    0 -> abrir(f)
                    1 -> pedirTexto("Renomear", Biblioteca.titulo(f)) { novo ->
                        val alvo = File(f.parentFile, Biblioteca.nomeSeguro(novo) + ".pdf")
                        if (alvo.exists()) aviso("Já existe um arquivo com esse nome") else { f.renameTo(alvo); recarregar() }
                    }
                    2 -> moverPara(f)
                    3 -> confirmar("Apagar \"${Biblioteca.titulo(f)}\"?", "Esta ação não pode ser desfeita.") {
                        f.delete(); act.recarregarBibliotecas()
                    }
                }
            }.show()
    }

    /** Move entre categorias e também entre Aulas e Cifras. */
    private fun moverPara(f: File) {
        val destinos = mutableListOf<Pair<String, String>>()
        for (t in listOf("aulas", "cifras")) for (c in Biblioteca.categorias(act, t)) destinos.add(t to c)
        val rotulos = destinos.map { (t, c) -> "${if (t == "aulas") "Aulas" else "Cifras"} › $c" }.toTypedArray()
        AlertDialog.Builder(act).setTitle("Mover para")
            .setItems(rotulos) { _, i ->
                val (t, c) = destinos[i]
                val dir = Biblioteca.pasta(act, t, c).apply { mkdirs() }
                val alvo = Biblioteca.arquivoLivre(dir, Biblioteca.titulo(f))
                if (f.renameTo(alvo)) aviso("Movido para ${rotulos[i]}")
                act.recarregarBibliotecas()
            }.show()
    }

    // ---------- backup ----------

    private fun dialogoBackup() {
        AlertDialog.Builder(act).setTitle("Backup da biblioteca")
            .setMessage("Guarda todas as aulas e cifras num arquivo .zip (por exemplo, no Google Drive ou na pasta Downloads). Para restaurar, escolha o arquivo .zip; o que já existe não é apagado.")
            .setPositiveButton("Exportar") { _, _ -> act.pedirExportarZip() }
            .setNeutralButton("Restaurar") { _, _ -> act.pedirImportarZip() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ---------- componentes ----------

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = itens.size
        override fun getItem(p: Int) = itens[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, conv: View?, parent: ViewGroup?): View {
            val v = (conv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                background = fundo(cartao, cartao)
                addView(TextView(act).apply { setTextColor(texto); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(act).apply { setTextColor(fraco); textSize = 12f })
                layoutParams = android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            val f = itens[p]
            (v.getChildAt(0) as TextView).text = Biblioteca.titulo(f)
            val kb = f.length() / 1024
            val tam = if (kb > 1024) String.format("%.1f MB", kb / 1024.0) else "$kb KB"
            (v.getChildAt(1) as TextView).text =
                if (busca.text.isNotEmpty()) "${f.parentFile?.name} · $tam" else tam
            return v
        }
    }

    private fun m() = (3 * dp).toInt()
    private fun peso(p: Float) = LayoutParams(0, (48 * dp).toInt(), p).apply { setMargins(m(), m(), m(), m()) }

    private fun fundo(cor: Int, borda: Int) = GradientDrawable().apply {
        cornerRadius = 12 * dp; setColor(cor); setStroke((1.5f * dp).toInt(), borda)
    }

    private fun botao(t: String, acao: () -> Unit) = TextView(act).apply {
        text = t; gravity = Gravity.CENTER; textSize = 15f
        setTextColor(texto); background = fundo(cartao, cartao)
        setOnClickListener { acao() }
    }

    private fun chip(t: String, ativo: Boolean) = TextView(act).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        background = fundo(if (ativo) Color.parseColor("#163A27") else cartao, if (ativo) verde else cartao)
        setTextColor(if (ativo) verde else texto)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins(m(), m(), m(), m()) }
    }

    private fun aviso(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()

    private fun pedirTexto(titulo: String, inicial: String, ok: (String) -> Unit) {
        val et = EditText(act).apply {
            setText(inicial); setSelection(inicial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val caixa = LinearLayout(act).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(et, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(act).setTitle(titulo).setView(caixa)
            .setPositiveButton("OK") { _, _ -> if (et.text.isNotBlank()) ok(et.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun confirmar(titulo: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(act).setTitle(titulo).setMessage(msg)
            .setPositiveButton("Apagar") { _, _ -> ok() }
            .setNegativeButton("Cancelar", null).show()
    }
}
