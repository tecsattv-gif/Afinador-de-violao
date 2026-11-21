package com.tecsattv.afinador

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Lê o texto de um PDF (songbook) e descobre onde começa cada música e o título.
 * Também separa páginas num PDF novo mantendo o texto original.
 */
object Separador {

    /** Um trecho do PDF: páginas de ini até fim (contando de 0), título e se parece música (tem cifras). */
    data class Trecho(var titulo: String, var ini: Int, var fim: Int, var musica: Boolean, var incluir: Boolean = true)

    class Resultado(
        val trechos: MutableList<Trecho>,
        val paginas: Int,
        val semTexto: Boolean,
        val tituloPagina: List<String> = emptyList(),   // 1ª linha útil de cada página
        val musicaPagina: List<Boolean> = emptyList()
    )

    /** Lê o texto de uma página linha a linha, junto com o tamanho da letra de cada linha. */
    private class Coletor : PDFTextStripper() {
        val linhas = mutableListOf<Pair<String, Float>>()
        private val buf = StringBuilder()
        private val tams = mutableListOf<Float>()

        init { sortByPosition = true }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (text != null) buf.append(text)
            textPositions?.forEach { p ->
                if (!p.unicode.isNullOrBlank()) {
                    val h = if (p.heightDir > 0f) p.heightDir else p.fontSizeInPt
                    tams.add(h)
                }
            }
            super.writeString(text, textPositions)
        }
        override fun writeWordSeparator() { buf.append(' '); super.writeWordSeparator() }
        override fun writeLineSeparator() { fechar(); super.writeLineSeparator() }

        private fun fechar() {
            val t = buf.toString().replace('\u00A0', ' ').trim()
            if (t.isNotEmpty()) {
                val ord = tams.sorted()
                linhas.add(t to (ord.getOrNull(ord.size / 2) ?: 0f))
            }
            buf.setLength(0); tams.clear()
        }

        fun pagina(doc: PDDocument, p: Int): List<Pair<String, Float>> {
            linhas.clear(); buf.setLength(0); tams.clear()
            startPage = p; endPage = p
            try { getText(doc) } catch (e: Exception) {}
            fechar()
            return linhas.toList()
        }
    }

    private val regexAcorde = Regex(
        "^[A-G](#|b|♯|♭)?(m|M|maj|min|dim|aug|sus|add|º|°|ø|\\+|-)?[0-9]{0,2}" +
            "(maj|M|sus|add|b|#|\\+|-)?[0-9]{0,2}(\\([^)]{0,12}\\))?(/[A-G](#|b|♯|♭)?)?$"
    )
    private val regexTom = Regex("^\\s*(tom|tono|key|tonalidade)\\s*[:：-]", RegexOption.IGNORE_CASE)
    private val regexCont = Regex("cont\\.|continua|continuação|\\(cont", RegexOption.IGNORE_CASE)
    private val regexSoNumero = Regex("^(p[áa]g(ina)?\\.?\\s*)?\\d+(\\s*(/|de)\\s*\\d+)?$", RegexOption.IGNORE_CASE)

    /** Linha só de acordes (ex.: "G  D/F#  Em  C") — também aceita marcações como | e x2. */
    fun linhaDeAcordes(l: String): Boolean {
        val toks = l.split(Regex("\\s+")).filter { it.isNotBlank() && it != "|" && it != "-" && !it.matches(Regex("\\(?x\\d\\)?", RegexOption.IGNORE_CASE)) }
        if (toks.isEmpty()) return false
        val ok = toks.count { regexAcorde.matches(it.trim('|', ',', '.')) }
        return ok >= 1 && ok >= toks.size * 0.75
    }

    private fun normal(s: String) = s.lowercase().replace(Regex("[^\\p{L}]"), "")

    private fun tituloCapitalizado(s: String): Boolean {
        val letras = s.filter { it.isLetter() }
        if (letras.isEmpty()) return false
        if (letras.count { it.isUpperCase() } >= letras.length * 0.7) return true      // TUDO MAIÚSCULO
        val palavras = s.split(Regex("\\s+")).filter { it.length > 3 }
        if (palavras.isEmpty()) return letras.first().isUpperCase()
        return palavras.count { it.first().isUpperCase() } >= palavras.size * 0.6        // Título Assim
    }

    fun analisar(arquivo: File): Resultado {
        PDDocument.load(arquivo, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val n = doc.numberOfPages
            val col = Coletor()
            val brutas = (1..n).map { p -> col.pagina(doc, p) }
            val totalLetras = brutas.sumOf { l -> l.sumOf { (t, _) -> t.count { it.isLetter() } } }
            if (totalLetras < 30 * n) return Resultado(mutableListOf(), n, true)
            return montar(brutas, n)
        }
    }

    /**
     * PDF de imagem (escaneado): desenha cada página e reconhece o texto com OCR (ML Kit, offline).
     * O tamanho da letra vem da altura de cada linha reconhecida.
     */
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
                        val linhas = try {
                            val res = com.google.android.gms.tasks.Tasks.await(
                                rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                            )
                            res.textBlocks.flatMap { it.lines }
                                .filter { it.boundingBox != null }
                                .sortedWith(compareBy({ it.boundingBox!!.top / 12 }, { it.boundingBox!!.left }))
                                .map { it.text.trim() to it.boundingBox!!.height().toFloat() }
                        } catch (e: Exception) { emptyList() }
                        bmp.recycle()
                        linhas
                    }
                    val totalLetras = brutas.sumOf { l -> l.sumOf { (t, _) -> t.count { it.isLetter() } } }
                    if (totalLetras < 20 * n) return Resultado(mutableListOf(), n, true)
                    return montar(brutas, n)
                }
            }
        } finally {
            rec.close()
        }
    }

    private fun montar(brutas: List<List<Pair<String, Float>>>, n: Int): Resultado {
        run {
            // tira números de página e cabeçalhos/rodapés que se repetem em muitas páginas
            val semNum = brutas.map { l -> l.filter { (t, _) -> !regexSoNumero.matches(t) } }
            val repetidas = if (n >= 4) {
                semNum.flatMap { l -> (l.take(3) + l.takeLast(3)).map { normal(it.first.replace(Regex("\\d"), "")) }.toSet() }
                    .filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                    .filter { it.value >= n * 0.4 }.keys
            } else emptySet()
            val pags = semNum.map { l -> l.filter { normal(it.first.replace(Regex("\\d"), "")) !in repetidas } }
            val limpas = pags.map { l -> l.map { it.first } }

            // tamanho normal da letra (corpo do texto) no documento todo
            val tamanhos = pags.flatten().filter { (t, h) -> h > 0f && t.any { it.isLetter() } }.map { it.second }.sorted()
            val corpo = tamanhos.getOrNull(tamanhos.size / 2) ?: 0f

            fun ehTitulo(t: String) = !linhaDeAcordes(t) && t.any { c -> c.isLetter() } && t.length in 2..80

            // título = linha em letra maior que o corpo, no topo da página
            fun tituloGrande(l: List<Pair<String, Float>>): String? =
                if (corpo <= 0f) null
                else l.take(6).firstOrNull { (t, h) -> h >= corpo * 1.18f && ehTitulo(t) }?.first

            fun primeiraLinha(l: List<String>): String? = l.take(4).firstOrNull { ehTitulo(it) }

            val comTitulo = pags.count { tituloGrande(it) != null }
            val modoTamanho = comTitulo >= maxOf(2, (n * 0.12).toInt())

            fun comeca(i: Int, tituloAtual: String?): String? {
                val l = limpas[i]
                if (l.isEmpty()) return null
                val tom = l.take(8).any { regexTom.containsMatchIn(it) }
                val t: String? = if (modoTamanho) tituloGrande(pags[i]) ?: (if (tom) primeiraLinha(l) else null)
                else {
                    val tt = primeiraLinha(l) ?: return null
                    var pontos = 0
                    if (tom) pontos += 3
                    val primeira = l.first()
                    if (!linhaDeAcordes(primeira) && primeira.length <= 50) pontos += 1
                    val segunda = l.getOrNull(1)
                    if (segunda != null && !linhaDeAcordes(segunda) && segunda.length <= 50) pontos += 1
                    val ok = pontos >= 3 || (pontos == 2 && tituloCapitalizado(primeira) && segunda != null && tituloCapitalizado(segunda))
                    if (ok) tt else null
                }
                if (t == null || regexCont.containsMatchIn(t)) return null
                if (tituloAtual != null && normal(t) == normal(tituloAtual)) return null
                return t
            }

            val trechos = mutableListOf<Trecho>()
            for (i in 0 until n) {
                val atual = trechos.lastOrNull()
                val t = if (atual == null) (comeca(i, null) ?: primeiraLinha(limpas[i]) ?: "Página 1") else comeca(i, atual.titulo)
                if (atual == null || t != null) trechos.add(Trecho(limparTitulo(t!!), i, i, false))
                else atual.fim = i
            }
            val musicaPag = limpas.map { l -> l.count { linhaDeAcordes(it) } >= 2 }
            for (t in trechos) t.musica = (t.ini..t.fim).any { musicaPag[it] }
            val titPag = limpas.mapIndexed { i, l -> limparTitulo(tituloGrande(pags[i]) ?: primeiraLinha(l) ?: "Página ${i + 1}") }
            return Resultado(trechos, n, false, titPag, musicaPag)
        }
    }

    private fun limparTitulo(t: String): String =
        t.replace(Regex("^\\d+\\s*[-.)]\\s*"), "")       // "12 - Música" -> "Música"
            .replace(Regex("\\s{2,}"), " ").trim().take(80)

    /** Cria um PDF novo com as páginas de ini até fim (contando de 0), mantendo texto e qualidade originais. */
    fun extrair(origem: File, ini: Int, fim: Int, destino: File) {
        PDDocument.load(origem, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            PDDocument().use { novo ->
                for (p in ini..fim) novo.importPage(doc.getPage(p))
                novo.save(destino)
            }
        }
    }

    /** Vários trechos de uma vez (abre o PDF original só uma vez). */
    fun extrairVarios(origem: File, partes: List<Pair<Trecho, File>>, progresso: (Int) -> Unit) {
        PDDocument.load(origem, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            partes.forEachIndexed { k, (t, destino) ->
                PDDocument().use { novo ->
                    for (p in t.ini..t.fim) novo.importPage(doc.getPage(p))
                    novo.save(destino)
                }
                progresso(k + 1)
            }
        }
    }
}
