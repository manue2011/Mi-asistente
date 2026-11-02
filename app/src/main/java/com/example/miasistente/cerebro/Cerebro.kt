package com.example.miasistente.cerebro

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class Rol { USUARIO, ASISTENTE }

/** Un mensaje del historial de la conversación. */
data class Turno(val rol: Rol, val texto: String)

sealed interface EstadoCerebro {
    data object SinIniciar : EstadoCerebro
    data class Cargando(val detalle: String) : EstadoCerebro
    data class Listo(val descripcion: String) : EstadoCerebro

    /** No se encontró el archivo del modelo; [carpeta] es donde se espera. */
    data class SinModelo(val carpeta: String) : EstadoCerebro
    data class Error(val mensaje: String) : EstadoCerebro
}

/**
 * Módulo "cerebro": genera respuestas a partir del historial.
 *
 * El historial lo guarda quien llama, de modo que se puede cambiar de cerebro
 * (local con LiteRT-LM, o un servidor Ollama en el PC cuando esté en la Wi-Fi)
 * sin perder el contexto. Un futuro `CerebroHibrido` solo tendría que
 * implementar esta interfaz y delegar en uno u otro.
 */
interface Cerebro : AutoCloseable {
    val estado: StateFlow<EstadoCerebro>

    /** Carga el modelo. Es lento: se ejecuta fuera del hilo principal. */
    suspend fun preparar()

    /**
     * Responde a [mensaje] teniendo en cuenta [historial] (turnos anteriores,
     * sin incluir [mensaje]). Emite la respuesta en trozos según se genera.
     */
    fun responder(historial: List<Turno>, mensaje: String): Flow<String>

    /** Olvida el contexto de la conversación actual. */
    suspend fun olvidar()
}
