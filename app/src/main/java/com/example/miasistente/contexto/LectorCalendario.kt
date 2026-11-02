package com.example.miasistente.contexto

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Lee los eventos del calendario del móvil (solo lectura, todo local). */
class LectorCalendario(private val context: Context) {

    enum class Rango(val dias: Long, val titulo: String) {
        HOY(1, "Agenda de hoy"),
        MANANA(1, "Agenda de mañana"),
        SEMANA(7, "Agenda de los próximos 7 días"),
    }

    val tienePermiso: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Devuelve la agenda como texto listo para el modelo. */
    fun describir(rango: Rango): String {
        if (!tienePermiso) return "${rango.titulo}: no tengo permiso para leer el calendario."

        val zona = ZoneId.systemDefault()
        val primerDia = LocalDate.now(zona).plusDays(if (rango == Rango.MANANA) 1 else 0)
        val inicio = primerDia.atStartOfDay(zona).toInstant().toEpochMilli()
        val fin = primerDia.plusDays(rango.dias).atStartOfDay(zona).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, inicio)
            ContentUris.appendId(it, fin)
        }.build()
        val columnas = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val lineas = mutableListOf<String>()
        context.contentResolver.query(uri, columnas, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && lineas.size < MAX_EVENTOS) {
                val titulo = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(sin título)"
                val empieza = Instant.ofEpochMilli(c.getLong(1)).atZone(zona)
                val termina = Instant.ofEpochMilli(c.getLong(2)).atZone(zona)
                val todoElDia = c.getInt(3) == 1
                val lugar = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " (en $it)" }.orEmpty()

                val dia = if (rango == Rango.SEMANA) "${empieza.format(FORMATO_DIA)}, " else ""
                val cuando = if (todoElDia) "todo el día" else "${empieza.format(FORMATO_HORA)}–${termina.format(FORMATO_HORA)}"
                lineas += "- $dia$cuando: $titulo$lugar"
            }
        }
        return if (lineas.isEmpty()) "${rango.titulo}: no hay eventos." else "${rango.titulo}:\n" + lineas.joinToString("\n")
    }

    companion object {
        private const val MAX_EVENTOS = 15
        private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")
        private val FORMATO_DIA = DateTimeFormatter.ofPattern("EEEE d", Locale.forLanguageTag("es-ES"))
    }
}
