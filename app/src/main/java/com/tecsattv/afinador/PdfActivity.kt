package com.tecsattv.afinador

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Leitor de PDF com zoom, rolagem automática, última página guardada e separação de páginas. */
class PdfActivity : Activity() {

    private lateinit var arquivo: File
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var paginas = 0

    private val dp by lazy { resources.displayMetrics.density }
    private val texto = Color.parseColor("#ECEFF1")
    private val fraco = Color.parseColor("#8A949E")
    private val cartao = Color.parseColor("#1E2329")
    private val verde = Color.parseColor("#2ECC71")

    private lateinit var lista: ListView
    private lateinit var caixaLista: FrameLayout
    private lateinit var paginaTxt: TextView
    private lateinit var zoomTxt: TextView
    private lateinit var btRolar: TextView
    private val zoomMin = 1f
    private val zoomMax = 4f
    private var zoom = 1f
    // zoom com dois dedos (pinça) e toque duplo
    private var zoomGesto = 1f
    private var cancelouFilhos = false
    private val pinca by lazy {
        android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                pararRolagem()
                zoomGesto = zoom
                caixaLista.pivotX = d.focusX + (caixaLista.parent as View).scrollX
                caixaLista.pivotY = d.focusY
                return true
            }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                zoomGesto = (zoomGesto * d.scaleFactor).coerceIn(zoomMin, zoomMax)
                val f = zoomGesto / zoom
                caixaLista.scaleX = f; caixaLista.scaleY = f
                zoomTxt.text = "${(zoomGesto * 100).toInt()}%"
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                caixaLista.scaleX = 1f; caixaLista.scaleY = 1f
                aplicarZoom(zoomGesto, d.focusX)
            }
        })
    }
    private val toqueDuplo by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                aplicarZoom(if (zoom < 1.5f) 2f else 1f, e.x)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!::caixaLista.isInitialized) return super.dispatchTouchEvent(ev)
        // só trata gestos na área das páginas
        val area = caixaLista.parent as View
        val loc = IntArray(2); area.getLocationOnScreen(loc)
        val dentro = ev.rawY >= loc[1] && ev.rawY <= loc[1] + area.height
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) cancelouFilhos = false
        if (dentro || pinca.isInProgress) {
            val local = android.view.MotionEvent.obtain(ev).apply { offsetLocation(-loc[0].toFloat(), -loc[1].toFloat()) }
            pinca.onTouchEvent(local)
            if (ev.pointerCount == 1) toqueDuplo.onTouchEvent(local)
            local.recycle()
        }
        if (ev.pointerCount > 1 || pinca.isInProgress) {
            if (!cancelouFilhos) {
                val c = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c); c.recycle()
                cancelouFilhos = true
            }
            return true
        }
        if (cancelouFilhos) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) cancelouFilhos = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Muda o zoom mantendo a página e a posição horizontal sob o dedo. */
    private fun aplicarZoom(novo: Float, focoX: Float) {
        val z = (Math.round(novo * 20) / 20f).coerceIn(zoomMin, zoomMax)
        val rolH = caixaLista.parent as HorizontalScrollView
        val pos = lista.firstVisiblePosition
        val topo = lista.getChildAt(0)?.top ?: 0
        val xConteudo = (rolH.scrollX + focoX) / zoom
        val fator = z / zoom
        zoom = z
        atualizarZoomTxt()
        ajustarLargura()
        lista.post {
            lista.setSelectionFromTop(pos, (topo * fator).toInt())
            rolH.scrollTo((xConteudo * zoom - focoX).toInt().coerceAtLeast(0), 0)
        }
    }
    private var rolando = false
    private var velocidade = 3
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("leitor", MODE_PRIVATE) }

    private val passoRolagem = object : Runnable {
        override fun run() {
            if (!rolando) return
            lista.scrollListBy((velocidade * 0.6f * dp).toInt().coerceAtLeast(1))
            if (!lista.canScrollVertically(1)) { pararRolagem(); return }
            handler.postDelayed(this, 30)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        arquivo = File(intent.getStringExtra("caminho") ?: run { finish(); return })
        try {
            pfd = ParcelFileDescriptor.open(arquivo, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            paginas = renderer!!.pageCount
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir este PDF", Toast.LENGTH_LONG).show()
            finish(); return
        }
        zoom = prefs.getFloat("zoomf:" + arquivo.absolutePath, 1f).coerceIn(zoomMin, zoomMax)
        velocidade = prefs.getInt("velocidade", 3)
        setContentView(montar())
        lista.post {
            ajustarLargura()
            lista.setSelection(prefs.getInt("pag:" + arquivo.absolutePath, 0).coerceIn(0, maxOf(0, paginas - 1)))
        }
    }

    override fun onPause() {
        super.onPause()
        pararRolagem()
        if (::lista.isInitialized) prefs.edit()
            .putInt("pag:" + arquivo.absolutePath, lista.firstVisiblePosition)
            .putFloat("zoomf:" + arquivo.absolutePath, zoom)
            .putInt("velocidade", velocidade).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
    }

    // ---------- tela ----------

    private fun montar(): View {
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // barra de cima
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        topo.addView(botao("←") { finish() }, LinearLayout.LayoutParams((48 * dp).toInt(), (44 * dp).toInt()))
        topo.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), 0, 0, 0)
            addView(TextView(this@PdfActivity).apply {
                text = Biblioteca.titulo(arquivo); setTextColor(texto); textSize = 16f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            paginaTxt = TextView(this@PdfActivity).apply { setTextColor(fraco); textSize = 12f }
            addView(paginaTxt)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topo.addView(botao("✂ Separar") { dialogoSeparar() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()))
        raiz.addView(topo)

        // páginas
        lista = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = Paginas()
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {
                    if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL && rolando) pararRolagem()
                }
                override fun onScroll(v: AbsListView?, first: Int, vis: Int, total: Int) {
                    paginaTxt.text = "Página ${first + 1} de $paginas"
                }
            })
        }
        caixaLista = FrameLayout(this).apply { addView(lista) }
        val rolH = HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(caixaLista, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        raiz.addView(rolH, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // barra de baixo: zoom + rolagem automática
        val baixo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        baixo.addView(botao("−") { mudarZoom(-1) }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        zoomTxt = TextView(this).apply { setTextColor(texto); textSize = 13f; gravity = Gravity.CENTER }
        baixo.addView(zoomTxt, LinearLayout.LayoutParams((52 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        baixo.addView(botao("+") { mudarZoom(1) }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        btRolar = botao("▶ Rolar") { if (rolando) pararRolagem() else iniciarRolagem() }
        baixo.addView(btRolar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()).apply { leftMargin = (8 * dp).toInt() })
        baixo.addView(SeekBar(this).apply {
            max = 9; progress = velocidade - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { velocidade = p + 1 }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(baixo)
        atualizarZoomTxt()
        return raiz
    }

    private fun botao(t: String, acao: () -> Unit) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER; textSize = 15f; setTextColor(texto)
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(cartao) }
        setOnClickListener { acao() }
    }

    // ---------- zoom e rolagem ----------

    private fun larguraBase(): Int = (lista.parent.parent as View).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    private fun ajustarLargura() {
        val w = (larguraBase() * zoom).toInt()
        caixaLista.layoutParams = caixaLista.layoutParams.apply { width = w }
        lista.layoutParams = (lista.layoutParams as FrameLayout.LayoutParams).apply { width = w }
        caixaLista.requestLayout()
        (lista.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun mudarZoom(d: Int) {
        aplicarZoom(zoom + d * 0.25f, larguraBase() / 2f)
    }

    private fun atualizarZoomTxt() { zoomTxt.text = "${(zoom * 100).toInt()}%" }

    private fun iniciarRolagem() {
        rolando = true
        btRolar.text = "❚❚ Parar"
        btRolar.setTextColor(verde)
        handler.post(passoRolagem)
    }

    private fun pararRolagem() {
        rolando = false
        handler.removeCallbacks(passoRolagem)
        if (::btRolar.isInitialized) { btRolar.text = "▶ Rolar"; btRolar.setTextColor(texto) }
    }

    // ---------- páginas ----------

    private inner class Paginas : BaseAdapter() {
        override fun getCount() = paginas
        override fun getItem(p: Int) = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, conv: View?, parent: ViewGroup?): View {
            val iv = (conv as? ImageView) ?: ImageView(this@PdfActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_XY
                setPadding(0, 0, 0, (6 * dp).toInt())
            }
            val w = (larguraBase() * zoom).toInt().coerceAtLeast(100)
            iv.layoutParams = AbsListView.LayoutParams(w, AbsListView.LayoutParams.WRAP_CONTENT)
            iv.setImageBitmap(renderizar(p, w))
            return iv
        }
    }

    /** Desenha uma página com a largura w (em pixels). Limita o tamanho para não faltar memória. */
    @Synchronized
    private fun renderizar(p: Int, wPedido: Int): Bitmap? {
        val r = renderer ?: return null
        return try {
            r.openPage(p).use { pg ->
                var w = wPedido
                var h = (w.toLong() * pg.height / pg.width).toInt()
                val maxPx = 6_000_000
                if (w.toLong() * h > maxPx) {
                    val f = Math.sqrt(maxPx.toDouble() / (w.toLong() * h))
                    w = (w * f).toInt(); h = (h * f).toInt()
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (e: Exception) { null }
    }

    // ---------- separar páginas em outro PDF ----------

    private fun dialogoSeparar() {
        pararRolagem()
        val atual = lista.firstVisiblePosition + 1
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun rotulo(t: String) = TextView(this).apply { text = t; setTextColor(fraco); textSize = 13f; setPadding(0, (8 * dp).toInt(), 0, 0) }
        fun numero(v: Int) = EditText(this).apply { setText("$v"); inputType = InputType.TYPE_CLASS_NUMBER }

        caixa.addView(rotulo("Páginas (este PDF tem $paginas)"))
        val linhaPag = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        val de = numero(atual); val ate = numero(atual)
        linhaPag.addView(TextView(this).apply { text = "De "; setTextColor(texto) })
        linhaPag.addView(de, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        linhaPag.addView(TextView(this).apply { text = "  até "; setTextColor(texto) })
        linhaPag.addView(ate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        caixa.addView(linhaPag)

        caixa.addView(rotulo("É uma…"))
        val grupo = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rCifra = RadioButton(this).apply { text = "Música (cifra)"; id = View.generateViewId() }
        val rAula = RadioButton(this).apply { text = "Aula / tutorial"; id = View.generateViewId() }
        grupo.addView(rCifra); grupo.addView(rAula)
        val tipoAtual = arquivo.parentFile?.parentFile?.name
        grupo.check(if (tipoAtual == "aulas") rAula.id else rCifra.id)
        caixa.addView(grupo)

        val rotCat = rotulo("")
        caixa.addView(rotCat)
        val spinner = Spinner(this)
        caixa.addView(spinner)
        val novaCat = EditText(this).apply { hint = "ou escreva um novo"; isSingleLine = true }
        caixa.addView(novaCat)

        caixa.addView(rotulo("Nome do novo PDF"))
        val nome = EditText(this).apply { hint = "ex.: nome da música"; isSingleLine = true }
        caixa.addView(nome)

        fun tipoEscolhido() = if (grupo.checkedRadioButtonId == rAula.id) "aulas" else "cifras"
        fun carregarCats() {
            val t = tipoEscolhido()
            rotCat.text = if (t == "aulas") "Categoria" else "Gênero"
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Biblioteca.categorias(this, t))
        }
        grupo.setOnCheckedChangeListener { _, _ -> carregarCats() }
        carregarCats()

        val dlg = AlertDialog.Builder(this).setTitle("✂ Separar páginas")
            .setView(android.widget.ScrollView(this).apply { addView(caixa) })
            .setPositiveButton("Criar PDF", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = de.text.toString().toIntOrNull() ?: 0
                val b = ate.text.toString().toIntOrNull() ?: 0
                if (a < 1 || b < a || b > paginas) { de.error = "Páginas de 1 a $paginas"; return@setOnClickListener }
                if (nome.text.isBlank()) { nome.error = "Escreva um nome"; return@setOnClickListener }
                val t = tipoEscolhido()
                val cat = if (novaCat.text.isNotBlank()) Biblioteca.nomeSeguro(novaCat.text.toString())
                else (spinner.selectedItem as? String) ?: "Geral"
                dlg.dismiss()
                criarPdf(a - 1, b - 1, t, cat, Biblioteca.nomeSeguro(nome.text.toString()))
            }
        }
        dlg.show()
    }

    private fun criarPdf(ini: Int, fim: Int, tipo: String, cat: String, nome: String) {
        Toast.makeText(this, "Criando PDF…", Toast.LENGTH_SHORT).show()
        Thread {
            val dir = Biblioteca.pasta(this, tipo, cat).apply { mkdirs() }
            val alvo = Biblioteca.arquivoLivre(dir, nome)
            val ok = try {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                Separador.extrair(arquivo, ini, fim, alvo)
                true
            } catch (e: Throwable) { alvo.delete(); false } || try {
                // plano B: copia as páginas como imagem
                val doc = PdfDocument()
                for (p in ini..fim) {
                    var wPt = 0; var hPt = 0
                    synchronized(this) { renderer!!.openPage(p).use { wPt = it.width; hPt = it.height } }
                    // desenha a página em alta resolução (2x) e coloca no novo PDF
                    val bmp = renderizar(p, wPt * 2) ?: continue
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, p - ini + 1).create())
                    val m = Matrix().apply { setScale(wPt.toFloat() / bmp.width, hPt.toFloat() / bmp.height) }
                    page.canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                    bmp.recycle()
                }
                alvo.outputStream().use { doc.writeTo(it) }
                doc.close()
                true
            } catch (e: Exception) { alvo.delete(); false }
            runOnUiThread {
                val onde = "${if (tipo == "aulas") "Aulas" else "Cifras"} › $cat"
                Toast.makeText(this, if (ok) "Criado \"$nome\" em $onde" else "Não foi possível criar o PDF", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
