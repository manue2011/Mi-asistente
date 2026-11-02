package com.example.miasistente.acciones

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Entiende horas, fechas y duraciones dichas en español, sobre texto ya
 * normalizado (minúsculas y sin tildes): "a las 7 y media", "a las 5 de la
 * tarde", "el jueves", "pasado manana", "el 15 de noviembre", "10 minutos",
 * "una hora y media"…
 *
 * Cada función devuelve también el fragmento reconocido, para poder quitarlo
 * del texto (por ejemplo, para quedarse con el título de un evento).
 */
object InterpreteTiempo {

    data class Encontrado<T>(val valor: T, val fragmento: String)

    private val NUMEROS = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
        "quince" to 15, "veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45,
    )
    private const val NUM = """(\d{1,2}|una|uno|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|quince|veinte|treinta|cuarenta y cinco|cuarenta)"""

    private fun numero(s: String): Int? = s.toIntOrNull() ?: NUMEROS[s]

    // "a las 7", "a la 1", "a las 7:30", "a las 7 y media", "a las 8 menos cuarto", "a las 5 de la tarde", "a mediodia"
    private val HORA = Regex(
        """\b(?:a\s+)?(?:las|la)\s+$NUM(?::(\d{2}))?(?:\s+y\s+(media|cuarto|$NUM)|\s+menos\s+(cuarto|$NUM))?""" +
            """(?:\s+(?:de\s+la\s+|por\s+la\s+)?(manana|tarde|noche|madrugada))?(?:\s+en\s+punto)?""",
    )
    private val MEDIODIA = Regex("""\b(?:a\s+)?(?:mediodia|medio\s+dia)\b""")

    /**
     * [tardePorDefecto]: si es true, "a las 5" sin más se entiende como 17:00
     * (lo natural para citas); para alarmas se deja la hora tal cual.
     */
    fun hora(t: String, tardePorDefecto: Boolean): Encontrado<LocalTime>? {
        MEDIODIA.find(t)?.let { return Encontrado(LocalTime.NOON, it.value) }
        val m = HORA.find(t) ?: return null
        var h = numero(m.groupValues[1]) ?: return null
        var min = m.groupValues[2].toIntOrNull() ?: 0
        val mas = m.groupValues[3]
        val menos = m.groupValues[5]
        when {
            mas == "media" -> min = 30
            mas == "cuarto" -> min = 15
            mas.isNotEmpty() -> min = numero(mas) ?: 0
        }
        if (menos.isNotEmpty()) {
            h -= 1
            min = 60 - (if (menos == "cuarto") 15 else numero(menos) ?: 0)
        }
        when (m.groupValues[7]) {
            "tarde", "noche" -> if (h < 12) h += 12
            "manana", "madrugada" -> if (h == 12) h = 0
            else -> if (tardePorDefecto && h in 1..7) h += 12
        }
        if (h !in 0..23 || min !in 0..59) return null
        return Encontrado(LocalTime.of(h, min), m.value)
    }

    private val DIAS = mapOf(
        "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY,
    )
    private val MESES = mapOf(
        "enero" to Month.JANUARY, "febrero" to Month.FEBRUARY, "marzo" to Month.MARCH, "abril" to Month.APRIL,
        "mayo" to Month.MAY, "junio" to Month.JUNE, "julio" to Month.JULY, "agosto" to Month.AUGUST,
        "septiembre" to Month.SEPTEMBER, "setiembre" to Month.SEPTEMBER, "octubre" to Month.OCTOBER,
        "noviembre" to Month.NOVEMBER, "diciembre" to Month.DECEMBER,
    )
    private val RELATIVA = Regex("""\b(pasado\s+manana|manana|hoy|esta\s+noche|esta\s+tarde)\b""")
    private val DIA_SEMANA = Regex("""\b(?:el\s+|este\s+|el\s+proximo\s+|proximo\s+)?(lunes|martes|miercoles|jueves|viernes|sabado|domingo)(?:\s+que\s+viene)?\b""")
    private val DIA_MES = Regex("""\b(?:el\s+(?:dia\s+)?)?(\d{1,2})\s+de\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)\b""")

    fun fecha(t: String, hoy: LocalDate = LocalDate.now()): Encontrado<LocalDate>? {
        DIA_MES.find(t)?.let { m ->
            val dia = m.groupValues[1].toInt()
            val mes = MESES.getValue(m.groupValues[2])
            var f = runCatching { LocalDate.of(hoy.year, mes, dia) }.getOrNull() ?: return null
            if (f.isBefore(hoy)) f = f.plusYears(1)
            return Encontrado(f, m.value)
        }
        // "pasado mañana" / "mañana" (sin confundir con "de la mañana", que es una hora).
        RELATIVA.findAll(t).firstOrNull { !t.substring(0, it.range.first).trimEnd().endsWith("de la") && !t.substring(0, it.range.first).trimEnd().endsWith("por la") }
            ?.let { m ->
                val f = when {
                    m.value.startsWith("pasado") -> hoy.plusDays(2)
                    m.value == "manana" -> hoy.plusDays(1)
                    else -> hoy
                }
                return Encontrado(f, m.value)
            }
        DIA_SEMANA.find(t)?.let { m ->
            val objetivo = DIAS.getValue(m.groupValues[1])
            var dias = (objetivo.value - hoy.dayOfWeek.value + 7) % 7
            if (dias == 0) dias = 7 // "el lunes" dicho un lunes = el de la semana que viene
            return Encontrado(hoy.plusDays(dias.toLong()), m.value)
        }
        return null
    }

    // "10 minutos", "una hora", "1 hora y 30 minutos", "media hora", "hora y media", "30 segundos"
    private val DURACION = Regex(
        """\b(?:(media)\s+hora|(?:$NUM\s+)?horas?(?:\s+y\s+(media|$NUM\s+minutos?))?|$NUM\s+minutos?(?:\s+y\s+$NUM\s+segundos?)?|$NUM\s+segundos?)\b""",
    )

    fun duracion(t: String): Encontrado<Duration>? {
        for (m in DURACION.findAll(t)) {
            val v = m.value
            val d = when {
                m.groupValues[1] == "media" -> Duration.ofMinutes(30)
                v.contains("hora") -> {
                    val horas = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::numero) ?: 1
                    val extra = m.groupValues[3]
                    val minutos = when {
                        extra == "media" -> 30
                        extra.isNotEmpty() -> numero(m.groupValues[4]) ?: 0
                        else -> 0
                    }
                    Duration.ofHours(horas.toLong()).plusMinutes(minutos.toLong())
                }
                v.contains("minuto") -> {
                    val minutos = numero(m.groupValues[5]) ?: continue
                    val segundos = m.groupValues[6].takeIf { it.isNotEmpty() }?.let(::numero) ?: 0
                    Duration.ofMinutes(minutos.toLong()).plusSeconds(segundos.toLong())
                }
                else -> Duration.ofSeconds((numero(m.groupValues[7]) ?: continue).toLong())
            }
            if (!d.isZero) return Encontrado(d, v)
        }
        return null
    }

    private val ES = Locale.forLanguageTag("es-ES")
    private val FORMATO_FECHA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES)

    fun decirFecha(f: LocalDate, hoy: LocalDate = LocalDate.now()): String = when (f) {
        hoy -> "hoy"
        hoy.plusDays(1) -> "mañana"
        else -> "el " + f.format(FORMATO_FECHA)
    }

    fun decirHora(h: LocalTime): String = when {
        h == LocalTime.NOON -> "a mediodía"
        h.hour == 1 || h.hour == 13 -> "a la " + h.format(DateTimeFormatter.ofPattern("H:mm"))
        else -> "a las " + h.format(DateTimeFormatter.ofPattern("H:mm"))
    }

    fun decirDuracion(d: Duration): String {
        val partes = mutableListOf<String>()
        val h = d.toHours()
        val m = d.toMinutesPart()
        val s = d.toSecondsPart()
        if (h > 0) partes += if (h == 1L) "1 hora" else "$h horas"
        if (m > 0) partes += if (m == 1) "1 minuto" else "$m minutos"
        if (s > 0) partes += if (s == 1) "1 segundo" else "$s segundos"
        return partes.joinToString(" y ")
    }
}
