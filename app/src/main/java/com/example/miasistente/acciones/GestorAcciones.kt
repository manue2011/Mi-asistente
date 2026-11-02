package com.example.miasistente.acciones

import android.content.Context
import com.example.miasistente.contexto.ChatRespondible
import com.example.miasistente.contexto.GestorContexto.Companion.normalizar
import com.example.miasistente.contexto.OyenteNotificaciones

/** Respuesta de una acción que se resuelve sin pasar por el modelo. */
data class RespuestaAccion(
    val texto: String,
    /** Si es true, el asistente vuelve a escuchar al terminar de hablar (para confirmar). */
    val escucharDespues: Boolean = false,
)

/**
 * Módulo "acciones": órdenes que hacen algo en el móvil en lugar de solo conversar.
 *
 * Se reconocen con reglas, no con el modelo, porque un error aquí tiene
 * consecuencias (enviar un mensaje a quien no toca). Toda acción que sale del
 * móvil pide confirmación antes de ejecutarse.
 *
 * De momento: responder mensajes ("respóndele a Ana que llego en diez minutos").
 */
class GestorAcciones(private val context: Context) {

    private data class EnvioPendiente(val chat: ChatRespondible, val texto: String)

    private var pendiente: EnvioPendiente? = null

    /** Devuelve la respuesta si [texto] es una acción; null si debe ir al modelo. */
    fun atender(texto: String): RespuestaAccion? {
        val original = texto.trim()
        val normalizado = normalizar(original)
        val t = normalizado.trimEnd('.', '!', '?')

        pendiente?.let { p ->
            pendiente = null
            return when {
                AFIRMACIONES.any { t == it || t.startsWith("$it ") } ->
                    if (p.chat.enviar(context, p.texto)) {
                        RespuestaAccion("Hecho, enviado a ${p.chat.nombre}.")
                    } else {
                        RespuestaAccion("No he podido enviarlo. Puede que la notificación ya no esté.")
                    }
                NEGACIONES.any { t == it || t.startsWith("$it ") } -> RespuestaAccion("Vale, no lo envío.")
                else -> RespuestaAccion("No lo he enviado. Si quieres, pídemelo otra vez.")
            }
        }

        val orden = ORDEN_RESPONDER.find(normalizado) ?: return null
        // Se recorta el mensaje del texto original para conservar tildes y mayúsculas.
        val rangoMensaje = orden.groups[2]!!.range
        val mensaje = (if (original.length == normalizado.length) original.substring(rangoMensaje) else orden.groupValues[2]).trim()
        val destinatario = orden.groupValues[1].replace(SOBRANTE_DESTINATARIO, "").trim()
        if (mensaje.isEmpty()) return null

        if (!OyenteNotificaciones.tieneAcceso(context)) {
            return RespuestaAccion("Para responder mensajes necesito acceso a las notificaciones. Actívalo en Ajustes.")
        }
        val chats = OyenteNotificaciones.chatsRespondibles(context)
        val chat = elegirChat(chats, destinatario)
            ?: return RespuestaAccion(
                if (destinatario.isEmpty() || destinatario in ULTIMO) {
                    "No veo ningún mensaje pendiente al que pueda responder."
                } else {
                    "No veo ningún mensaje pendiente de $destinatario. Solo puedo contestar mensajes que sigan en la barra de notificaciones."
                },
            )

        pendiente = EnvioPendiente(chat, mensaje)
        return RespuestaAccion("¿Envío a ${chat.nombre} por ${chat.app}: «$mensaje»? Di sí o no.", escucharDespues = true)
    }

    private fun elegirChat(chats: List<ChatRespondible>, destinatario: String): ChatRespondible? {
        if (chats.isEmpty()) return null
        if (destinatario.isEmpty() || destinatario in ULTIMO) return chats.first()
        val buscado = normalizar(destinatario)
        val palabras = buscado.split(' ').filter { it.length > 2 }
        // Primero coincidencia exacta, luego por contenido, luego por alguna palabra del nombre.
        return chats.firstOrNull { normalizar(it.nombre) == buscado }
            ?: chats.firstOrNull { normalizar(it.nombre).contains(buscado) }
            ?: chats.firstOrNull { c -> palabras.any { normalizar(c.nombre).contains(it) } }
    }

    companion object {
        /** "respondele a Ana que …", "contesta al grupo Familia: …", "dile a Juan por WhatsApp que …". */
        private val ORDEN_RESPONDER = Regex(
            """^(?:por favor,?\s+)?(?:responde|respondele|contesta|contestale|dile|escribele|mandale|enviale)""" +
                """(?:\s+(?:a|al)\s+(.*?))?\s*(?:\s(?:que|diciendo(?:\s+que)?)\s+|:\s*)(.+)$""",
        )
        private val SOBRANTE_DESTINATARIO = Regex("""^(?:el|la)\s+|^grupo\s+|\s+(?:por|en)\s+\w+$""")
        private val ULTIMO = setOf("ultimo", "ultimo mensaje", "el ultimo", "ultimo chat")
        private val AFIRMACIONES = listOf("si", "vale", "envialo", "envia", "dale", "adelante", "ok", "claro", "correcto", "de acuerdo")
        private val NEGACIONES = listOf("no", "cancela", "cancelalo", "dejalo", "para", "olvidalo")
    }
}
