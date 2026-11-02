package com.example.miasistente

import android.app.Application

/** Mantiene un único [Asistente] para toda la app (pantalla y, más adelante, el servicio del reloj). */
class MiAsistenteApp : Application() {
    val asistente: Asistente by lazy { Asistente(this) }
}
