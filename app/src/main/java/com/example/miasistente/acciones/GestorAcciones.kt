package com.example.miasistente.acciones

import android.Manifest
import android.content.Context
import com.example.miasistente.contexto.ChatRespondible
import com.example.miasistente.contexto.GestorContexto.Companion.normalizar
import com.example.miasistente.contexto.OyenteNotificaciones
import java.time.LocalDate
import java.time.LocalTime

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
 * consecuencias (enviar un mensaje o llamar a quien no toca). Lo que sale del
 * móvil (mensajes, llamadas) o cambia tus datos (calendario) pide confirmación.
 */
class GestorAcciones(private val context: Context) {

    private val telefono = AccionesTelefono(context)

    /** Acción a la espera de un "sí": [descripcion] es lo que se confirma, [ejecutar] devuelve lo que se dice después. */
    private class Pendiente(val cancelada: String, val ejecutar: () -> String)

    private var pendiente: Pendiente? = null

    /**
     * Devuelve la respuesta si [texto] es una acción; null si debe ir al modelo.
     * [appVisible] indica si la pantalla del asistente está delante.
     */
    fun atender(texto: String, appVisible: Boolean): RespuestaAccion? {
        val original = texto.trim()
        val normalizado = normalizar(original)
        val t = normalizado.trimEnd('.', '!', '?')

        pendiente?.let { p ->
            pendiente = null
            return when {
                AFIRMACIONES.any { t == it || t.startsWith("$it ") } -> RespuestaAccion(p.ejecutar())
                NEGACIONES.any { t == it || t.startsWith("$it ") } -> RespuestaAccion(p.cancelada)
                else -> RespuestaAccion("No lo he hecho. Si quieres, pídemelo otra vez.")
            }
        }

        return responderMensaje(original, normalizado)
            ?: temporizador(t, appVisible)
            ?: alarma(t, appVisible)
            ?: llamar(t)
            ?: abrirApp(t, appVisible)
            ?: apuntarEvento(original, normalizado, t)
    }

    private fun confirmar(pregunta: String, cancelada: String, ejecutar: () -> String): RespuestaAccion {
        pendiente = Pendiente(cancelada, ejecutar)
        return RespuestaAccion("$pregunta Di sí o no.", escucharDespues = true)
    }

    // --- Mensajes -----------------------------------------------------------------------

    private fun responderMensaje(original: String, normalizado: String): RespuestaAccion? {
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
        // "Respóndele que voy" con varios chats pendientes: mejor preguntar que adivinar.
        if (destinatario.isEmpty() && chats.size > 1) {
            val nombres = chats.take(4).map { it.nombre }
            val lista = if (nombres.size == 1) nombres[0] else nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
            return RespuestaAccion(
                "¿A quién? Tienes mensajes de $lista. Dime, por ejemplo: respóndele a ${nombres[0]} que $mensaje.",
                escucharDespues = true,
            )
        }
        val chat = elegirChat(chats, destinatario)
            ?: return RespuestaAccion(
                if (destinatario.isEmpty() || destinatario in ULTIMO) {
                    "No veo ningún mensaje pendiente al que pueda responder."
                } else {
                    "No veo ningún mensaje pendiente de ${capitalizar(destinatario)}. Solo puedo contestar mensajes que sigan en la barra de notificaciones."
                },
            )
        return confirmar("¿Envío a ${chat.nombre} por ${chat.app}: «$mensaje»?", "Vale, no lo envío.") {
            if (chat.enviar(context, mensaje)) "Hecho, enviado a ${chat.nombre}." else "No he podido enviarlo. Puede que la notificación ya no esté."
        }
    }

    private fun elegirChat(chats: List<ChatRespondible>, destinatario: String): ChatRespondible? {
        if (chats.isEmpty()) return null
        if (destinatario.isEmpty() || destinatario in ULTIMO) return chats.first()
        return BuscadorNombres.mejor(chats, destinatario) { it.nombre }
    }

    // --- Reloj: temporizador y alarma ------------------------------------------------------

    private fun temporizador(t: String, appVisible: Boolean): RespuestaAccion? {
        if (!ORDEN_TEMPORIZADOR.containsMatchIn(t)) return null
        val duracion = InterpreteTiempo.duracion(t)?.valor
            ?: return RespuestaAccion("¿De cuánto tiempo pongo el temporizador?")
        if (!telefono.puedeAbrirPantallas(appVisible)) return RespuestaAccion(SIN_PERMISO_PANTALLAS)
        return RespuestaAccion(
            if (telefono.ponerTemporizador(duracion)) {
                "Temporizador de ${InterpreteTiempo.decirDuracion(duracion)} en marcha."
            } else {
                "No he podido poner el temporizador."
            },
        )
    }

    private fun alarma(t: String, appVisible: Boolean): RespuestaAccion? {
        if (!ORDEN_ALARMA.containsMatchIn(t)) return null
        val hora = InterpreteTiempo.hora(t, tardePorDefecto = false)?.valor
            ?: return RespuestaAccion("¿A qué hora pongo la alarma?")
        if (!telefono.puedeAbrirPantallas(appVisible)) return RespuestaAccion(SIN_PERMISO_PANTALLAS)
        return RespuestaAccion(
            if (telefono.ponerAlarma(hora, null)) {
                "Alarma puesta ${InterpreteTiempo.decirHora(hora)}."
            } else {
                "No he podido poner la alarma."
            },
        )
    }

    // --- Llamadas -------------------------------------------------------------------------

    private fun llamar(t: String): RespuestaAccion? {
        val orden = ORDEN_LLAMAR.find(t) ?: return null
        val quien = orden.groupValues[1].trim()
        if (!telefono.tiene(Manifest.permission.CALL_PHONE)) {
            return RespuestaAccion("Para llamar necesito el permiso de llamadas. Actívalo en Ajustes.")
        }
        val digitos = quien.filter { it.isDigit() || it == '+' }
        val (nombre, numero) = if (digitos.count { it.isDigit() } >= 3) {
            digitos.chunked(3).joinToString(" ") to digitos
        } else {
            if (!telefono.tiene(Manifest.permission.READ_CONTACTS)) {
                return RespuestaAccion("Para llamar a tus contactos necesito permiso de contactos. Actívalo en Ajustes.")
            }
            val contacto = telefono.buscarContacto(quien) ?: return RespuestaAccion("No encuentro a ${capitalizar(quien)} en tus contactos.")
            contacto.nombre to contacto.numero
        }
        return confirmar("¿Llamo a $nombre?", "Vale, no llamo.") {
            if (telefono.llamar(numero)) "Llamando a $nombre." else "No he podido hacer la llamada."
        }
    }

    // --- Abrir apps -----------------------------------------------------------------------

    private fun abrirApp(t: String, appVisible: Boolean): RespuestaAccion? {
        val orden = ORDEN_ABRIR.find(t) ?: return null
        val nombre = orden.groupValues[1].trim()
        val app = telefono.buscarApp(nombre) ?: return RespuestaAccion("No encuentro ninguna app llamada ${capitalizar(nombre)}.")
        if (!telefono.puedeAbrirPantallas(appVisible)) return RespuestaAccion(SIN_PERMISO_PANTALLAS)
        if (!telefono.abrirApp(app)) return RespuestaAccion("No he podido abrir ${app.nombre}.")
        return RespuestaAccion(
            if (telefono.bloqueado) "Te abro ${app.nombre}. Desbloquea el móvil para verla." else "Abriendo ${app.nombre}.",
        )
    }

    // --- Calendario -----------------------------------------------------------------------

    private fun apuntarEvento(original: String, normalizado: String, t: String): RespuestaAccion? {
        val orden = ORDEN_APUNTAR.find(t) ?: return null
        val fecha = InterpreteTiempo.fecha(t)
        val hora = InterpreteTiempo.hora(t, tardePorDefecto = true)
        if (fecha == null && hora == null) return null // sin cuándo, no es una cita: que conteste el modelo

        if (!telefono.tiene(Manifest.permission.WRITE_CALENDAR)) {
            return RespuestaAccion("Para apuntar en el calendario necesito permiso de calendario. Actívalo en Ajustes.")
        }

        // El título es lo que queda al quitar la orden, la fecha, la hora y las muletillas.
        val quitar = buildList {
            add(orden.range)
            fecha?.let { f -> normalizado.indexOf(f.fragmento).takeIf { it >= 0 }?.let { add(it until it + f.fragmento.length) } }
            hora?.let { h -> normalizado.indexOf(h.fragmento).takeIf { it >= 0 }?.let { add(it until it + h.fragmento.length) } }
            MULETILLAS.findAll(normalizado).forEach { add(it.range) }
        }
        // Se recorta del texto original (mismas posiciones) para conservar tildes y mayúsculas.
        val base = if (original.length == normalizado.length) original else normalizado
        val titulo = base.filterIndexed { i, _ -> quitar.none { i in it } }
            .replace(Regex("""\s+"""), " ").trim(' ', ',', '.')
            .replaceFirstChar { it.uppercase() }
            .ifEmpty { "Recordatorio" }

        val ahora = LocalTime.now()
        val dia = fecha?.valor ?: if (hora!!.valor.isAfter(ahora)) LocalDate.now() else LocalDate.now().plusDays(1)
        val cuando = InterpreteTiempo.decirFecha(dia) + (hora?.let { " " + InterpreteTiempo.decirHora(it.valor) } ?: "")
        return confirmar("¿Apunto «$titulo» $cuando?", "Vale, no lo apunto.") {
            if (telefono.crearEvento(titulo, dia, hora?.valor)) "Apuntado: $titulo, $cuando." else "No he podido apuntarlo en el calendario."
        }
    }

    companion object {
        /** Lo reconocido está en minúsculas y sin tildes: al menos, nombres con mayúscula. */
        private fun capitalizar(texto: String) = texto.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

        private const val SIN_PERMISO_PANTALLAS =
            "Para hacerlo con la app cerrada necesito el permiso «Mostrar sobre otras apps». Actívalo en Ajustes."

        /** "respondele a Ana que …", "contesta al grupo Familia: …", "dile a Juan por WhatsApp que …". */
        private val ORDEN_RESPONDER = Regex(
            """^(?:por favor,?\s+)?(?:responde|respondele|contesta|contestale|dile|escribele|mandale|enviale)""" +
                """(?:\s+(?:a|al)\s+(.*?))?\s*(?:\s(?:que|diciendo(?:\s+que)?)\s+|:\s*)(.+)$""",
        )
        private val SOBRANTE_DESTINATARIO = Regex("""^(?:el|la)\s+|^grupo\s+|\s+(?:por|en)\s+\w+$""")
        private val ULTIMO = setOf("ultimo", "ultimo mensaje", "el ultimo", "ultimo chat")

        private val ORDEN_TEMPORIZADOR = Regex("""\b(?:temporizador|cuenta atras|avisame en|avisame dentro de)\b""")
        private val ORDEN_ALARMA = Regex("""\b(?:alarma|despiertame|despertador)\b""")
        private val ORDEN_LLAMAR = Regex("""^(?:por favor,?\s+)?(?:llama|llamar|marca|telefonea)(?:\s+(?:a|al))?\s+(.+)$""")
        private val ORDEN_ABRIR = Regex(
            """^(?:por favor,?\s+)?(?:abre|abreme|abrir|lanza|inicia)\s+(?:la\s+(?:app|aplicacion)\s+(?:de\s+)?|el\s+|la\s+)?(.+)$""",
        )
        private val ORDEN_APUNTAR = Regex(
            """^(?:por favor,?\s+)?(?:apuntame|apunta|anotame|anota|agendame|agenda|anade|anademe|crea|creame|recuerdame|ponme|pon)\b""",
        )
        private val MULETILLAS = Regex(
            """\b(?:en (?:el|mi) calendario|en (?:la|mi) agenda|un evento|una cita|un recordatorio|que tengo|para)\b""",
        )

        private val AFIRMACIONES = listOf("si", "vale", "envialo", "envia", "dale", "adelante", "ok", "claro", "correcto", "de acuerdo", "llama", "hazlo", "apuntalo")
        private val NEGACIONES = listOf("no", "cancela", "cancelalo", "dejalo", "para", "olvidalo")
    }
}
