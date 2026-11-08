package com.example.miasistente.noticias

import android.content.Context
import android.util.Xml
import androidx.core.content.edit
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Módulo "noticias": titulares de portada de varios periódicos por RSS.
 *
 * Es la única parte de la app que usa internet, y solo para descargar (GET)
 * feeds públicos: no se envía ningún dato personal. El resumen lo sigue
 * haciendo el modelo local. La última descarga se guarda para tener algo
 * que contar si no hay conexión, y se borra pasados [CADUCIDAD_MS] (solo se
 * conserva la última descarga, nunca se acumulan).
 */
class LectorNoticias(private val context: Context) {

    data class Titular(val fuente: String, val titulo: String)

    private val prefs = context.getSharedPreferences("noticias", Context.MODE_PRIVATE)

    /** Bloque de texto con los titulares para añadir al mensaje del modelo. */
    suspend fun describir(): String {
        val (titulares, descargados) = titulares()
        if (titulares.isEmpty()) return "Noticias: no he podido descargar titulares (sin conexión)."
        val hora = FORMATO_HORA.format(Instant.ofEpochMilli(descargados).atZone(ZoneId.systemDefault()))
        return buildString {
            appendLine("Titulares de portada (descargados el $hora):")
            titulares.forEach { appendLine("- ${it.fuente}: ${it.titulo}") }
        }.trimEnd()
    }

    /** Descarga los titulares; si falla, devuelve los últimos guardados. También devuelve cuándo se descargaron. */
    suspend fun titulares(): Pair<List<Titular>, Long> = withContext(Dispatchers.IO) {
        var nuevos = descargarTodos()
        if (nuevos.isEmpty()) {
            // Al salir del reposo profundo la red puede tardar unos segundos en volver.
            delay(REINTENTO_MS)
            nuevos = descargarTodos()
        }
        if (nuevos.isNotEmpty()) {
            val ahora = System.currentTimeMillis()
            guardar(nuevos, ahora)
            nuevos to ahora
        } else {
            cargarGuardados()
        }
    }

    /** Intercala las fuentes (el primero de cada una, luego el segundo…) y quita repetidos. */
    private suspend fun descargarTodos(): List<Titular> = coroutineScope {
        val porFuente = FUENTES.map { (nombre, url) -> async { descargar(nombre, url) } }.awaitAll()
        val vistos = mutableSetOf<String>()
        (0 until POR_FUENTE)
            .flatMap { i -> porFuente.mapNotNull { it.getOrNull(i) } }
            .filter { vistos.add(it.titulo.lowercase()) }
    }

    private fun descargar(fuente: String, direccion: String): List<Titular> = runCatching {
        val conexion = URL(direccion).openConnection() as HttpURLConnection
        conexion.connectTimeout = TIEMPO_MS
        conexion.readTimeout = TIEMPO_MS
        conexion.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MiAsistente")
        try {
            conexion.inputStream.use { entrada ->
                val parser = Xml.newPullParser()
                parser.setInput(entrada, null)
                val titulos = mutableListOf<String>()
                var dentroDeItem = false
                while (titulos.size < POR_FUENTE && parser.next() != XmlPullParser.END_DOCUMENT) {
                    when {
                        parser.eventType == XmlPullParser.START_TAG && parser.name == "item" -> dentroDeItem = true
                        parser.eventType == XmlPullParser.END_TAG && parser.name == "item" -> dentroDeItem = false
                        parser.eventType == XmlPullParser.START_TAG && parser.name == "title" && dentroDeItem ->
                            parser.nextText().trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                                ?.let(titulos::add)
                    }
                }
                titulos.map { Titular(fuente, it) }
            }
        } finally {
            conexion.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun guardar(titulares: List<Titular>, momento: Long) = prefs.edit {
        putString(CLAVE_TITULARES, titulares.joinToString("\n") { "${it.fuente}\t${it.titulo}" })
        putLong(CLAVE_MOMENTO, momento)
    }

    private fun cargarGuardados(): Pair<List<Titular>, Long> {
        val momento = prefs.getLong(CLAVE_MOMENTO, 0L)
        if (System.currentTimeMillis() - momento > CADUCIDAD_MS) {
            prefs.edit { clear() }
            return emptyList<Titular>() to 0L
        }
        val texto = prefs.getString(CLAVE_TITULARES, null).orEmpty()
        val titulares = texto.lines().mapNotNull { linea ->
            linea.split('\t', limit = 2).takeIf { it.size == 2 }?.let { Titular(it[0], it[1]) }
        }
        return titulares to momento
    }

    companion object {
        /** Portadas en español. Cambia o añade periódicos aquí (cualquier feed RSS sirve). */
        private val FUENTES = listOf(
            "Europa Press" to "https://www.europapress.es/rss/rss.aspx",
            "El País" to "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada",
            "El Mundo" to "https://e00-elmundo.uecdn.es/elmundo/rss/portada.xml",
        )
        private const val POR_FUENTE = 3
        private const val TIEMPO_MS = 10_000
        private const val REINTENTO_MS = 5_000L
        private const val CADUCIDAD_MS = 2 * 24 * 60 * 60 * 1000L
        private const val CLAVE_TITULARES = "titulares"
        private const val CLAVE_MOMENTO = "momento"
        private val FORMATO_HORA = DateTimeFormatter.ofPattern("d/M 'a las' HH:mm")
    }
}
