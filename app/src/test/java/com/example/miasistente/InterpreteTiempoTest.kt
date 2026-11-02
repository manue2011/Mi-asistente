package com.example.miasistente

import com.example.miasistente.acciones.InterpreteTiempo
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpreteTiempoTest {

    private fun hora(t: String, tarde: Boolean = false) = InterpreteTiempo.hora(t, tarde)?.valor

    @Test
    fun horas() {
        assertEquals(LocalTime.of(7, 0), hora("ponme una alarma a las 7"))
        assertEquals(LocalTime.of(7, 30), hora("despiertame a las 7 y media"))
        assertEquals(LocalTime.of(7, 30), hora("alarma a las 7:30"))
        assertEquals(LocalTime.of(7, 45), hora("alarma a las 8 menos cuarto"))
        assertEquals(LocalTime.of(17, 0), hora("dentista a las 5 de la tarde"))
        assertEquals(LocalTime.of(17, 0), hora("dentista el jueves a las 5", tarde = true))
        assertEquals(LocalTime.of(9, 0), hora("reunion a las 9", tarde = true))
        assertEquals(LocalTime.of(13, 0), hora("comida a la una", tarde = true))
        assertEquals(LocalTime.NOON, hora("comida a mediodia"))
        assertNull(hora("que tal estas"))
    }

    @Test
    fun fechas() {
        val lunes = LocalDate.of(2026, 11, 2) // lunes
        fun fecha(t: String) = InterpreteTiempo.fecha(t, lunes)?.valor
        assertEquals(lunes.plusDays(1), fecha("apuntame dentista manana a las 5"))
        assertEquals(lunes.plusDays(2), fecha("pasado manana"))
        assertEquals(LocalDate.of(2026, 11, 5), fecha("el jueves a las 5"))
        assertEquals(LocalDate.of(2026, 11, 9), fecha("el lunes"))
        assertEquals(LocalDate.of(2026, 11, 15), fecha("el 15 de noviembre"))
        assertEquals(LocalDate.of(2027, 1, 6), fecha("el 6 de enero"))
        // "de la mañana" es una hora, no "mañana" como día.
        assertNull(fecha("a las 9 de la manana"))
    }

    @Test
    fun duraciones() {
        fun dur(t: String) = InterpreteTiempo.duracion(t)?.valor
        assertEquals(Duration.ofMinutes(10), dur("pon un temporizador de 10 minutos"))
        assertEquals(Duration.ofMinutes(30), dur("temporizador de media hora"))
        assertEquals(Duration.ofHours(1), dur("temporizador de una hora"))
        assertEquals(Duration.ofMinutes(90), dur("temporizador de 1 hora y 30 minutos"))
        assertEquals(Duration.ofMinutes(90), dur("temporizador de una hora y media"))
        assertEquals(Duration.ofSeconds(45), dur("avisame en 45 segundos"))
        assertEquals(Duration.ofMinutes(5), dur("avisame en cinco minutos"))
    }
}
