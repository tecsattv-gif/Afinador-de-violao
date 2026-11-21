package com.tecsattv.afinador

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Lê o texto de um PDF (songbook) e descobre onde começa cada música e o título.
 * Também separa páginas num PDF novo mantendo o texto original.
 */
object Separador {

    /** Um trecho do PDF: páginas de ini até fim (contando de 0), título e se parece música (tem cifras). */
    data class Trecho(var titulo: String, var ini: Int, var fim: Int, var musica: Boolean, var incluir: Boolean = true)

    class Resultado(val trechos: MutableList<Trecho>, val paginas: Int, val semTexto: Boolean)

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
            val st = PDFTextStripper().apply { sortByPosition = true }
            val textos = (1..n).map { p ->
                st.startPage = p; st.endPage = p
                try { st.getText(doc) } catch (e: Exception) { "" }
            }
            val totalLetras = textos.sumOf { t -> t.count { it.isLetter() } }
            if (totalLetras < 30 * n) return Resultado(mutableListOf(), n, true)

            // linhas por página, sem vazias e sem números de página
            val linhas = textos.map { t ->
                t.lines().map { it.replace(' ', ' ').trim() }
                    .filter { it.isNotEmpty() && !regexSoNumero.matches(it) }
            }
            // cabeçalhos/rodapés repetidos (mesma linha em muitas páginas) são ignorados
            val repetidas = if (n >= 4) {
                linhas.flatMap { l -> (l.take(3) + l.takeLast(3)).map { normal(it.replace(Regex("\\d"), "")) }.toSet() }
                    .filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                    .filter { it.value >= n * 0.4 }.keys
            } else emptySet()
            val limpas = linhas.map { l -> l.filter { normal(it.replace(Regex("\\d"), "")) !in repetidas } }

            fun titulo(l: List<String>): String? =
                l.take(4).firstOrNull { !linhaDeAcordes(it) && it.any { c -> c.isLetter() } && it.length in 2..70 }

            fun comeca(i: Int, tituloAtual: String?): Boolean {
                if (i == 0) return true
                val l = limpas[i]
                if (l.isEmpty()) return false
                val t = titulo(l) ?: return false
                if (regexCont.containsMatchIn(t)) return false
                if (tituloAtual != null && normal(t) == normal(tituloAtual)) return false
                var pontos = 0
                if (l.take(8).any { regexTom.containsMatchIn(it) }) pontos += 3
                val primeira = l.first()
                if (!linhaDeAcordes(primeira) && primeira.length <= 50) pontos += 1
                val segunda = l.getOrNull(1)
                if (segunda != null && !linhaDeAcordes(segunda) && segunda.length <= 50) pontos += 1
                if (pontos >= 3) return true
                // sem "Tom:": título e artista capitalizados no topo da página
                return pontos == 2 && tituloCapitalizado(primeira) && segunda != null && tituloCapitalizado(segunda)
            }

            val trechos = mutableListOf<Trecho>()
            for (i in 0 until n) {
                val atual = trechos.lastOrNull()
                if (atual == null || comeca(i, atual.titulo)) {
                    val t = titulo(limpas[i]) ?: "Página ${i + 1}"
                    trechos.add(Trecho(limparTitulo(t), i, i, false))
                } else atual.fim = i
            }
            // música = tem pelo menos 2 linhas de acordes
            for (t in trechos) {
                val acordes = (t.ini..t.fim).sumOf { p -> limpas[p].count { linhaDeAcordes(it) } }
                t.musica = acordes >= 2
            }
            return Resultado(trechos, n, false)
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
