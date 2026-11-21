package com.tecsattv.afinador

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Biblioteca de PDFs guardada só no celular (pasta interna do app):
 *   biblioteca/aulas/<categoria>/<arquivo>.pdf
 *   biblioteca/cifras/<gênero>/<arquivo>.pdf
 */
object Biblioteca {

    fun base(ctx: Context) = File(ctx.filesDir, "biblioteca")

    fun raiz(ctx: Context, tipo: String) = File(base(ctx), tipo).apply { mkdirs() }

    fun categorias(ctx: Context, tipo: String): List<String> {
        val r = raiz(ctx, tipo)
        var dirs = r.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        if (dirs.isEmpty()) {
            File(r, "Geral").mkdirs()
            dirs = listOf(File(r, "Geral"))
        }
        return dirs.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun pasta(ctx: Context, tipo: String, cat: String) = File(raiz(ctx, tipo), cat)

    fun arquivos(ctx: Context, tipo: String, cat: String): List<File> =
        pasta(ctx, tipo, cat).listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).orEmpty()

    fun todos(ctx: Context, tipo: String): List<File> =
        categorias(ctx, tipo).flatMap { arquivos(ctx, tipo, it) }

    fun nomeSeguro(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('.').ifEmpty { "sem nome" }.take(100)

    /** Nome sem a extensão .pdf, para mostrar. */
    fun titulo(f: File) = f.name.removeSuffix(".pdf").removeSuffix(".PDF")

    fun arquivoLivre(dir: File, nomeBase: String): File {
        var f = File(dir, "$nomeBase.pdf")
        var i = 2
        while (f.exists()) { f = File(dir, "$nomeBase ($i).pdf"); i++ }
        return f
    }

    /** Copia um PDF escolhido no celular para dentro da biblioteca. */
    fun importar(ctx: Context, uri: Uri, destino: File): File? {
        return try {
            var nome = "documento"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { nome = it }
            }
            val base = nomeSeguro(nome.removeSuffix(".pdf").removeSuffix(".PDF"))
            destino.mkdirs()
            val alvo = arquivoLivre(destino, base)
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                alvo.outputStream().use { out -> inp.copyTo(out) }
            } ?: return null
            alvo
        } catch (e: Exception) {
            null
        }
    }

    /** Abre a janela do Android para enviar um ou vários PDFs (WhatsApp, e-mail, Drive, Bluetooth…). */
    fun enviar(ctx: Context, arquivos: List<File>) {
        if (arquivos.isEmpty()) return
        val aut = ctx.packageName + ".arquivos"
        val uris = ArrayList(arquivos.map { androidx.core.content.FileProvider.getUriForFile(ctx, aut, it) })
        val i = if (uris.size == 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
        }
        i.type = "application/pdf"
        i.putExtra(android.content.Intent.EXTRA_SUBJECT, arquivos.joinToString(", ") { titulo(it) }.take(200))
        // dá permissão de leitura a quem receber
        val clip = android.content.ClipData.newUri(ctx.contentResolver, titulo(arquivos[0]), uris[0])
        for (k in 1 until uris.size) clip.addItem(android.content.ClipData.Item(uris[k]))
        i.clipData = clip
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val titulo = if (uris.size == 1) "Enviar PDF" else "Enviar ${uris.size} PDFs"
        ctx.startActivity(android.content.Intent.createChooser(i, titulo))
    }

    /** Grava toda a biblioteca num arquivo .zip escolhido pelo usuário. */
    fun exportarZip(ctx: Context, uri: Uri): Int {
        val b = base(ctx)
        var n = 0
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                b.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(b).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    n++
                }
            }
        }
        return n
    }

    /** Lê um backup .zip e junta à biblioteca (não apaga o que já existe). */
    fun importarZip(ctx: Context, uri: Uri): Int {
        val b = base(ctx)
        val bCan = b.canonicalPath + File.separator
        var n = 0
        ctx.contentResolver.openInputStream(uri)?.use { inp ->
            ZipInputStream(inp.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && e.name.endsWith(".pdf", true)) {
                        val alvo = File(b, e.name)
                        // proteção: só aceita caminhos dentro da biblioteca
                        // arquivo que já existe com o mesmo nome é mantido (não duplica)
                        if (alvo.canonicalPath.startsWith(bCan) && !alvo.exists()) {
                            alvo.parentFile?.mkdirs()
                            alvo.outputStream().use { zip.copyTo(it) }
                            n++
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return n
    }
}
