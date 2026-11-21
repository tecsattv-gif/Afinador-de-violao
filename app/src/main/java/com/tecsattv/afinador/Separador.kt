package com.tecsattv.afinador

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Lê um songbook (PDF de texto ou escaneado), encontra onde começa cada música e o título,
 * inclusive quando há duas ou mais músicas na mesma página, e separa cada uma num PDF.
 */
object Separador {

    /** Pedaço de uma página: de y0 até y1 (0 = topo, 1 = base da página). */
    data class Pedaco(val pag: Int, var y0: Float, var y1: Float)

    /** Uma música (ou aula): título e os pedaços de página que a formam. */
    data class Trecho(
        var titulo: String,
        val partes: MutableList<Pedaco>,
        var musica: Boolean,
        var incluir: Boolean = true
    ) {
        val ini get() = partes.first().pag
        val fim get() = partes.last().pag

        /** Cópia independente (para "Desfazer"). */
        fun copia() = Trecho(titulo, partes.map { it.copy() }.toMutableList(), musica, incluir)

        /** Junta outro trecho no fim deste (une pedaços contíguos da mesma página). */
        fun juntar(outro: Trecho) {
            for (p in outro.partes) {
                val u = partes.last()
                if (u.pag == p.pag && p.y0 <= u.y1 + 0.03f) u.y1 = maxOf(u.y1, p.y1) else partes.add(p.copy())
            }
        }
    }

    class Resultado(
        val trechos: MutableList<Trecho>,
        val paginas: Int,
        val semTexto: Boolean,
        val tituloPagina: List<String> = emptyList(),
        val musicaPagina: List<Boolean> = emptyList()
    )

    /** Uma linha de texto lida da página: texto, tamanho da letra e posição vertical (0..1). */
    data class Linha(val texto: String, val tam: Float, val y0: Float, val y1: Float)

    // ---------- reconhecimento de padrões ----------

    private val regexAcorde = Regex(
        "^[A-G](#|b|♯|♭)?(m|M|maj|min|dim|aug|sus|add|º|°|ø|\\+|-)?[0-9]{0,2}" +
            "(maj|M|sus|add|b|#|\\+|-)?[0-9]{0,2}(\\([^)]{0,12}\\))?(/[A-G](#|b|♯|♭)?)?$"
    )
    /** Linha de informações que vem logo depois do título: "Introdução:", "Tom:", "Ritmo:"… */
    private val regexMeta = Regex(
        "^\\(?\\s*(intro|introdu[çc][ãa]o|tom|tom original|ritmo|tonalidade|capotraste|capo|afina[çc][ãa]o|tempo|compasso)\\s*[:：]",
        RegexOption.IGNORE_CASE
    )
    private val regexTomNaLinha = Regex("\\(\\s*tom\\s*[:：]", RegexOption.IGNORE_CASE)
    /** Título colado com "Ritmo:/Tom:" na mesma linha. */
    private val regexCorteTitulo = Regex(
        "\\s+(ritmo|tom|tonalidade|intro|introdu[çc][ãa]o|capotraste)\\s*[:：(].*$",
        RegexOption.IGNORE_CASE
    )
    private val regexSeparadorLinha = Regex("^[-_=–—•.*·\\s]{8,}$")
    private val regexCont = Regex("cont\\.|continua|continuação|\\(cont", RegexOption.IGNORE_CASE)
    private val regexSoNumero = Regex("^(p[áa]g(ina)?\\.?\\s*)?\\d+(\\s*(/|de)\\s*\\d+)?$", RegexOption.IGNORE_CASE)
    private val regexPrefixo = Regex("^(intro|introdu[çc][ãa]o|solo|riff|base)\\s*[:：]", RegexOption.IGNORE_CASE)
    private val regexRepeticao = Regex("\\(?x\\d\\)?", RegexOption.IGNORE_CASE)

    /** Linha só de acordes (ex.: "G  D/F#  Em  C", "Introdução: D G A D"). */
    fun linhaDeAcordes(l: String): Boolean {
        val toks = l.replace(regexPrefixo, " ")
            .split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() && it != "|" && it != "-" && !it.matches(regexRepeticao) }
        if (toks.isEmpty()) return false
        val ok = toks.count { tk ->
            val a = tk.trim('|', '.')
            regexAcorde.matches(a) || regexAcorde.matches(a.removePrefix("(").removeSuffix(")"))
        }
        return ok >= 1 && ok >= toks.size * 0.75
    }

    private fun ehMeta(t: String) = regexMeta.containsMatchIn(t) || regexTomNaLinha.containsMatchIn(t.take(40))
    private fun normal(s: String) = s.lowercase().replace(Regex("[^\\p{L}]"), "")
    private fun temLetras(t: String) = t.count { it.isLetter() } >= 2
    private fun cortarTitulo(t: String) = t.replace(regexCorteTitulo, "").trim()

    private fun podeSerTitulo(t: String): Boolean {
        val limpo = cortarTitulo(t)
        return temLetras(limpo) && limpo.length <= 90 && !linhaDeAcordes(limpo) && !ehMeta(limpo) &&
            !regexSeparadorLinha.matches(t) && !regexCont.containsMatchIn(limpo)
    }

    private fun tituloCapitalizado(s: String): Boolean {
        val letras = s.filter { it.isLetter() }
        if (letras.isEmpty()) return false
        if (letras.count { it.isUpperCase() } >= letras.length * 0.7) return true
        val palavras = s.split(Regex("\\s+")).filter { it.length > 3 }
        if (palavras.isEmpty()) return letras.first().isUpperCase()
        return palavras.count { it.first().isUpperCase() } >= palavras.size * 0.6
    }

    /** "12 - Teu olhar (Leandro e Leonardo)  Ritmo: Pop" -> "Teu olhar - Leandro e Leonardo" */
    fun limparTitulo(t: String): String {
        var s = cortarTitulo(t)
            .replace(Regex("^\\d+\\s*[-.)–]\\s*"), "")
            .replace(Regex("\\s{2,}"), " ").trim()
        val m = Regex("^(.*?)\\s*\\(([^()]{2,40})\\)\\s*$").find(s)
        if (m != null && temLetras(m.groupValues[1])) s = m.groupValues[1].trim() + " - " + m.groupValues[2].trim()
        return s.trim('-', ' ', '–').take(80).ifEmpty { "Sem título" }
    }

    // ---------- leitura do texto ----------

    /** Lê as linhas de uma página com tamanho da letra e posição. */
    private class Coletor : PDFTextStripper() {
        val linhas = mutableListOf<Linha>()
        private val buf = StringBuilder()
        private val tams = mutableListOf<Float>()
        private var yMin = Float.MAX_VALUE
        private var yMax = 0f
        private var altura = 1f

        init { sortByPosition = true }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (text != null) buf.append(text)
            textPositions?.forEach { p ->
                val u = p.unicode
                if (!u.isNullOrBlank()) {
                    val h = if (p.heightDir > 0f) p.heightDir else p.fontSizeInPt
                    if (tams.size < 10 && u.any { it.isLetter() }) tams.add(h)   // tamanho do começo da linha
                    if (p.pageHeight > 0f) altura = p.pageHeight
                    yMin = minOf(yMin, p.yDirAdj - h)
                    yMax = maxOf(yMax, p.yDirAdj)
                }
            }
            super.writeString(text, textPositions)
        }
        override fun writeWordSeparator() { buf.append(' '); super.writeWordSeparator() }
        override fun writeLineSeparator() { fechar(); super.writeLineSeparator() }

        private fun fechar() {
            val t = buf.toString().replace(' ', ' ').trim()
            if (t.isNotEmpty()) {
                val ord = tams.sorted()
                val y0 = if (yMin == Float.MAX_VALUE) 0f else (yMin / altura).coerceIn(0f, 1f)
                val y1 = (yMax / altura).coerceIn(y0, 1f)
                linhas.add(Linha(t, ord.getOrNull(ord.size / 2) ?: 0f, y0, y1))
            }
            buf.setLength(0); tams.clear(); yMin = Float.MAX_VALUE; yMax = 0f
        }

        fun pagina(doc: PDDocument, p: Int): List<Linha> {
            linhas.clear(); buf.setLength(0); tams.clear(); yMin = Float.MAX_VALUE; yMax = 0f
            startPage = p; endPage = p
            try { getText(doc) } catch (e: Exception) {}
            fechar()
            return linhas.sortedBy { it.y0 }
        }
    }

    fun analisar(arquivo: File): Resultado {
        PDDocument.load(arquivo, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val n = doc.numberOfPages
            val col = Coletor()
            val brutas = (1..n).map { p -> col.pagina(doc, p) }
            val totalLetras = brutas.sumOf { l -> l.sumOf { it.texto.count { c -> c.isLetter() } } }
            if (totalLetras < 30 * n) return Resultado(mutableListOf(), n, true)
            return montar(brutas, n)
        }
    }

    private class Peca(val texto: String, val caixa: android.graphics.Rect)

    /** PDF de imagem: desenha cada página e reconhece o texto com OCR (ML Kit, offline). */
    fun analisarOcr(ctx: android.content.Context, arquivo: File, progresso: (Int, Int) -> Unit): Resultado {
        val rec = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        try {
            android.os.ParcelFileDescriptor.open(arquivo, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { r ->
                    val n = r.pageCount
                    val brutas = (0 until n).map { i ->
                        progresso(i + 1, n)
                        val bmp = r.openPage(i).use { pg ->
                            val w = 1400
                            val h = (w.toLong() * pg.height / pg.width).toInt().coerceIn(200, 4000)
                            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                pg.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                        val alt = bmp.height.toFloat()
                        val linhas = try {
                            val res = com.google.android.gms.tasks.Tasks.await(
                                rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                            )
                            val pecas = res.textBlocks.flatMap { it.lines }
                                .mapNotNull { ln -> ln.boundingBox?.let { Peca(ln.text.trim(), it) } }
                                .sortedWith(compareBy({ it.caixa.centerY() }, { it.caixa.left }))
                            // junta pedaços da mesma linha (ex.: título à esquerda e "Ritmo:" à direita)
                            val grupos = mutableListOf<MutableList<Peca>>()
                            for (pc in pecas) {
                                val g = grupos.lastOrNull()
                                if (g != null && Math.abs(g[0].caixa.centerY() - pc.caixa.centerY()) < pc.caixa.height() * 0.5f) g.add(pc)
                                else grupos.add(mutableListOf(pc))
                            }
                            grupos.map { g0 ->
                                val g = g0.sortedBy { it.caixa.left }
                                Linha(
                                    g.joinToString("   ") { it.texto },
                                    g.first().caixa.height().toFloat(),     // tamanho = começo da linha
                                    g.minOf { it.caixa.top } / alt,
                                    g.maxOf { it.caixa.bottom } / alt
                                )
                            }
                        } catch (e: Exception) { emptyList() }
                        bmp.recycle()
                        linhas
                    }
                    val totalLetras = brutas.sumOf { l -> l.sumOf { it.texto.count { c -> c.isLetter() } } }
                    if (totalLetras < 20 * n) return Resultado(mutableListOf(), n, true)
                    return montar(brutas, n)
                }
            }
        } finally {
            rec.close()
        }
    }

    // ---------- encontrar as músicas ----------

    private data class Inicio(val pag: Int, val y: Float, val titulo: String)

    private fun montar(brutas: List<List<Linha>>, n: Int): Resultado {
        // tira números de página e cabeçalhos/rodapés repetidos
        val semNum = brutas.map { l -> l.filter { !regexSoNumero.matches(it.texto) } }
        fun chave(t: String) = normal(t.replace(Regex("\\d"), ""))
        val repetidas = if (n >= 4) {
            semNum.flatMap { l -> (l.take(3) + l.takeLast(3)).map { chave(it.texto) }.toSet() }
                .filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                .filter { it.value >= n * 0.4 }.keys
        } else emptySet()
        val pags = semNum.map { l -> l.filter { chave(it.texto) !in repetidas } }

        val tamanhos = pags.flatten().filter { it.tam > 0f && temLetras(it.texto) }.map { it.tam }.sorted()
        val corpo = tamanhos.getOrNull(tamanhos.size / 2) ?: 0f

        // 1) título = linha antes de "Introdução/Tom/Ritmo", com "Ritmo:/Tom:" colado, ou depois de "-----"
        fun iniciosPorMeta(): List<Inicio> {
            val r = mutableListOf<Inicio>()
            for ((p, l) in pags.withIndex()) {
                var i = 0
                while (i < l.size) {
                    val t = l[i].texto
                    val coladoComMeta = regexCorteTitulo.containsMatchIn(t) && !ehMeta(t)
                    val antesDeMeta = (1..2).any { k -> l.getOrNull(i + k)?.let { ehMeta(it.texto) } == true }
                    val depoisDeSeparador = i > 0 && regexSeparadorLinha.matches(l[i - 1].texto) &&
                        (1..3).any { k -> l.getOrNull(i + k)?.let { x -> ehMeta(x.texto) || linhaDeAcordes(x.texto) } == true }
                    if (podeSerTitulo(t) && (coladoComMeta || antesDeMeta || depoisDeSeparador)) {
                        val y = l[i].y0
                        r.add(Inicio(p, y, t))
                        // pula as linhas de informação logo abaixo do título
                        i++
                        while (i < l.size && (ehMeta(l[i].texto) || l[i].y0 - y < 0.015f)) i++
                        continue
                    }
                    i++
                }
            }
            return r
        }

        // 2) título = linha em letra maior que o corpo
        fun iniciosPorTamanho(): List<Inicio> {
            if (corpo <= 0f) return emptyList()
            val r = mutableListOf<Inicio>()
            for ((p, l) in pags.withIndex()) {
                var ultimoY = -1f
                for (ln in l) {
                    if (ln.tam >= corpo * 1.2f && podeSerTitulo(ln.texto)) {
                        // linhas grandes seguidas (título + subtítulo) contam como um título só
                        if (ultimoY >= 0f && ln.y0 - ultimoY < 0.06f) { ultimoY = ln.y1; continue }
                        r.add(Inicio(p, ln.y0, ln.texto)); ultimoY = ln.y1
                    }
                }
            }
            return r
        }

        // 3) uma página = uma música, quando a página começa com título + artista capitalizados
        fun iniciosPorPagina(): List<Inicio> = pags.withIndex().mapNotNull { (p, l) ->
            val a = l.getOrNull(0)?.texto ?: return@mapNotNull null
            val b = l.getOrNull(1)?.texto
            if (podeSerTitulo(a) && a.length <= 50 && tituloCapitalizado(a) && b != null &&
                !linhaDeAcordes(b) && b.length <= 50 && tituloCapitalizado(b)) Inicio(p, l[0].y0, a) else null
        }

        val porMeta = iniciosPorMeta()
        val porTamanho = if (porMeta.size >= 2) emptyList() else iniciosPorTamanho()
        var inicios = when {
            porMeta.size >= 2 -> porMeta
            porTamanho.size >= 2 -> porTamanho
            else -> iniciosPorPagina()
        }
        // não repete o mesmo título seguido (título repetido no topo da página seguinte)
        inicios = inicios.filterIndexed { i, s -> i == 0 || normal(cortarTitulo(s.titulo)) != normal(cortarTitulo(inicios[i - 1].titulo)) }

        val trechos = mutableListOf<Trecho>()
        val margem = 0.012f

        // o que vem antes do primeiro título (capa, índice…) vira um trecho próprio, desmarcado
        val primeiro = inicios.firstOrNull()
        if (primeiro == null || primeiro.pag > 0 || primeiro.y > 0.25f) {
            val partes = mutableListOf<Pedaco>()
            val ate = primeiro?.pag ?: (n - 1)
            for (p in 0..ate) {
                val y1 = if (primeiro != null && p == primeiro.pag) primeiro.y - margem else 1f
                if (y1 > 0.08f) partes.add(Pedaco(p, 0f, y1))
            }
            if (partes.isNotEmpty()) {
                val t = pags[0].firstOrNull { podeSerTitulo(it.texto) }?.texto ?: "Início"
                trechos.add(Trecho(limparTitulo(t), partes, false, incluir = primeiro == null))
            }
        }

        for ((k, s) in inicios.withIndex()) {
            val prox = inicios.getOrNull(k + 1)
            val partes = mutableListOf<Pedaco>()
            val ultimaPag = prox?.pag ?: (n - 1)
            for (p in s.pag..ultimaPag) {
                val y0 = if (p == s.pag) (s.y - margem).coerceAtLeast(0f) else 0f
                val y1 = if (prox != null && p == prox.pag) (prox.y - margem) else 1f
                // pedaço sem conteúdo (ex.: o próximo título está no topo da página) não entra
                val conteudo = pags[p].any { it.y0 >= y0 - 0.005f && it.y1 <= y1 + 0.005f && !regexSeparadorLinha.matches(it.texto) }
                if (y1 - y0 > 0.04f && conteudo) partes.add(Pedaco(p, y0, y1))
            }
            if (partes.isEmpty()) partes.add(Pedaco(s.pag, (s.y - margem).coerceAtLeast(0f), 1f))
            trechos.add(Trecho(limparTitulo(s.titulo), partes, false))
        }

        // música = tem linhas de acordes no seu pedaço
        for (t in trechos) {
            val acordes = t.partes.sumOf { pd ->
                pags[pd.pag].count { it.y0 >= pd.y0 - 0.005f && it.y1 <= pd.y1 + 0.005f && linhaDeAcordes(it.texto) }
            }
            t.musica = acordes >= 2
        }
        val musicaPag = pags.map { l -> l.count { linhaDeAcordes(it.texto) } >= 2 }
        val titPag = pags.mapIndexed { i, l ->
            limparTitulo(l.firstOrNull { podeSerTitulo(it.texto) }?.texto ?: "Página ${i + 1}")
        }
        return Resultado(trechos, n, false, titPag, musicaPag)
    }

    // ---------- criar os PDFs ----------

    /** Cria um PDF novo com as páginas de ini até fim (contando de 0), mantendo texto e qualidade. */
    fun extrair(origem: File, ini: Int, fim: Int, destino: File) {
        PDDocument.load(origem, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            PDDocument().use { novo ->
                for (p in ini..fim) novo.importPage(doc.getPage(p))
                novo.save(destino)
            }
        }
    }

    /** Cria um PDF por trecho; pedaços de página são recortados (várias músicas na mesma página). */
    fun extrairVarios(origem: File, partes: List<Pair<Trecho, File>>, progresso: (Int) -> Unit) {
        PDDocument.load(origem, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            partes.forEachIndexed { k, (t, destino) ->
                PDDocument().use { novo ->
                    for (pd in t.partes) {
                        val src = doc.getPage(pd.pag)
                        val cb = src.cropBox
                        val pg = novo.importPage(src)
                        val recortar = (pd.y0 > 0.005f || pd.y1 < 0.995f) && src.rotation % 360 == 0
                        if (recortar) {
                            val h = cb.height
                            val r = PDRectangle(cb.lowerLeftX, cb.lowerLeftY + h * (1f - pd.y1), cb.width, h * (pd.y1 - pd.y0))
                            pg.cropBox = r
                            pg.mediaBox = r
                        }
                    }
                    novo.save(destino)
                }
                progresso(k + 1)
            }
        }
    }
}
