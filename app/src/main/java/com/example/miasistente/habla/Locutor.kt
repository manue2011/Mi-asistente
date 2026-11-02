package com.example.miasistente.habla

import kotlinx.coroutines.flow.StateFlow

/**
 * Módulo "habla": lee texto en voz alta.
 *
 * Las frases se encolan, así se puede empezar a hablar mientras el modelo
 * aún está generando el resto de la respuesta.
 */
interface Locutor {
    /** true mientras quede algo por decir. */
    val hablando: StateFlow<Boolean>

    /** Mensaje de error de inicialización, si lo hay. */
    val error: StateFlow<String?>

    /** Si es true, habla por el manos libres Bluetooth conectado (p. ej. el reloj) en vez del altavoz. */
    var usarManosLibres: Boolean

    /** Añade una frase a la cola de lectura. */
    fun decir(texto: String)

    /** Calla y vacía la cola. */
    fun callar()

    fun liberar()
}
