package com.example.miasistente.cerebro

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Localiza el archivo `.litertlm` del modelo en el almacenamiento de la app.
 *
 * Carpeta esperada (accesible por `adb push` sin permisos extra):
 * `/sdcard/Android/data/com.example.miasistente/files/`
 */
class ArchivoModelo(private val context: Context) {

    val carpeta: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    fun buscar(): File? =
        listOf(carpeta, context.filesDir)
            .distinct()
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.extension == EXTENSION }.orEmpty().toList() }
            .sortedWith(compareBy({ !it.name.contains(PREFERIDO, ignoreCase = true) }, { it.name }))
            .firstOrNull()

    /** Copia un modelo elegido con el selector de archivos a la carpeta de la app. */
    suspend fun importar(uri: Uri, progreso: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var nombre = "modelo.$EXTENSION"
        var tamano = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { nombre = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { tamano = c.getLong(it) }
            }
        }
        require(nombre.endsWith(".$EXTENSION")) { "El archivo debe tener extensión .$EXTENSION" }

        val destino = File(carpeta, nombre)
        val temporal = File(carpeta, "$nombre.parcial")
        resolver.openInputStream(uri).use { entrada ->
            requireNotNull(entrada) { "No se pudo abrir el archivo." }
            temporal.outputStream().use { salida ->
                val buffer = ByteArray(1 shl 20)
                var copiado = 0L
                while (true) {
                    val leidos = entrada.read(buffer)
                    if (leidos < 0) break
                    salida.write(buffer, 0, leidos)
                    copiado += leidos
                    if (tamano > 0) progreso(copiado.toFloat() / tamano)
                }
            }
        }
        destino.delete()
        check(temporal.renameTo(destino)) { "No se pudo guardar el modelo." }
        destino
    }

    companion object {
        const val EXTENSION = "litertlm"
        const val NOMBRE_RECOMENDADO = "gemma-4-E2B-it.litertlm"
        private const val PREFERIDO = "gemma"
    }
}
