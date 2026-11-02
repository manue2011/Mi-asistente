package com.example.miasistente.voz

/** Lo que va ocurriendo mientras se escucha al usuario. */
sealed interface EventoVoz {
    /** El micrófono está abierto y listo. */
    data object Listo : EventoVoz

    /** Nivel de volumen normalizado (0..1), útil para animar la interfaz. */
    data class Nivel(val valor: Float) : EventoVoz

    /** Transcripción provisional mientras el usuario sigue hablando. */
    data class Parcial(val texto: String) : EventoVoz

    /** Transcripción definitiva. */
    data class Final(val texto: String) : EventoVoz

    data class Error(val mensaje: String) : EventoVoz
}

/**
 * Módulo "voz": convierte lo que dice el usuario en texto.
 *
 * Cualquier otra fuente de entrada (por ejemplo, un disparador desde un reloj
 * Huawei vía Wear Engine) solo tiene que pedir al asistente que empiece a
 * escuchar; no necesita conocer la implementación concreta.
 */
interface OyenteVoz {
    /** Indica si el reconocimiento en el dispositivo está disponible. */
    val disponible: Boolean

    /** Empieza a escuchar. Debe llamarse desde el hilo principal. */
    fun escuchar(alEvento: (EventoVoz) -> Unit)

    /** Deja de escuchar y procesa lo oído hasta ahora. */
    fun detener()

    /** Deja de escuchar descartando lo oído. */
    fun cancelar()

    fun liberar()
}
