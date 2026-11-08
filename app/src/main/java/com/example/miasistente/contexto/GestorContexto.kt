package com.example.miasistente.contexto

import android.content.Context
import com.example.miasistente.noticias.LectorNoticias
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Módulo "contexto": añade al mensaje del usuario la información del móvil
 * que necesite (fecha, agenda, notificaciones, noticias) según de qué esté hablando.
 *
 * Se decide con palabras clave en lugar de dejar que el modelo pida
 * herramientas: es más fiable con un modelo pequeño y funciona igual con
 * cualquier cerebro (local u Ollama).
 */
class GestorContexto(private val context: Context) {

    private val calendario = LectorCalendario(context)
    private val noticias = LectorNoticias(context)

    /** Devuelve [texto] precedido de la información relevante, o [texto] tal cual si no hace falta nada. */
    suspend fun enriquecer(texto: String): String = withContext(Dispatchers.IO) {
        val t = normalizar(texto)
        val esResumen = "resumen" in t
        val quiereAgenda = esResumen || PALABRAS_AGENDA.any { it in t }
        val quiereNotificaciones = esResumen || PALABRAS_NOTIFICACIONES.any { it in t }
        val quiereNoticias = esResumen || PALABRAS_NOTICIAS.any { it in t }
        val palabras = t.split(Regex("[^a-z0-9]+")).toSet()
        val quiereFecha = quiereAgenda || quiereNotificaciones || quiereNoticias ||
            "que dia" in t || PALABRAS_FECHA.any { it in palabras }
        if (!quiereFecha) return@withContext texto

        val bloques = mutableListOf("Fecha y hora actual: ${ZonedDateTime.now().format(FORMATO_FECHA)}.")
        if (quiereAgenda) {
            val rango = when {
                esResumen -> LectorCalendario.Rango.HOY
                "semana" in t -> LectorCalendario.Rango.SEMANA
                "manana" in t && "esta manana" !in t -> LectorCalendario.Rango.MANANA
                else -> LectorCalendario.Rango.HOY
            }
            bloques += calendario.describir(rango)
        }
        if (quiereNotificaciones) bloques += OyenteNotificaciones.describir(context)
        if (quiereNoticias) bloques += noticias.describir()

        buildString {
            appendLine("[Información de mi móvil]")
            bloques.forEach(::appendLine)
            appendLine("[Fin de la información]")
            appendLine()
            append(texto)
        }
    }

    companion object {
        const val PETICION_RESUMEN =
            "Dame mi resumen matutino: salúdame con energía, dime la fecha, repasa mis eventos de hoy, " +
                "las notificaciones que parezcan importantes y los tres o cuatro titulares más relevantes. " +
                "Máximo ocho frases."

        private val PALABRAS_AGENDA = listOf(
            "agenda", "calendario", "cita", "evento", "reunion", "que tengo", "planes", "ocupad", "libre",
        )
        private val PALABRAS_NOTIFICACIONES = listOf(
            "notificacion", "mensaje", "whatsapp", "telegram", "correo", "email", "aviso", "me han escrito",
        )
        private val PALABRAS_NOTICIAS = listOf(
            "noticia", "titular", "actualidad", "periodico", "que ha pasado", "que esta pasando",
        )
        /** Palabras sueltas (no subcadenas: "hora" no debe saltar con "ahora"). */
        private val PALABRAS_FECHA = listOf("hora", "fecha", "hoy", "manana")

        private val FORMATO_FECHA =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-ES"))

        /** Minúsculas y sin tildes, para comparar palabras clave. */
        fun normalizar(texto: String): String =
            Normalizer.normalize(texto.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
    }
}
