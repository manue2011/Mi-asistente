package com.example.miasistente.contexto

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Servicio del sistema que da acceso a las notificaciones pendientes.
 * No guarda nada: solo se consultan cuando el usuario las pide, y el texto
 * únicamente se pasa al modelo local.
 */
class OyenteNotificaciones : NotificationListenerService() {

    /** Último mensaje ya anunciado de cada notificación, para no repetir los que se vuelven a publicar. */
    private val anunciados = mutableMapOf<String, Long>()

    override fun onListenerConnected() {
        instancia = this
        // Lo que ya estaba en la barra al conectarse no es nuevo: no se anuncia.
        runCatching { activeNotifications }.getOrNull().orEmpty().forEach { sbn ->
            ultimoMensaje(sbn)?.let { anunciados[sbn.key] = it.timestamp }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val aviso = alLlegarMensaje ?: return
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Respeta el modo No molestar.
        if (currentInterruptionFilter > INTERRUPTION_FILTER_ALL) return

        val chat = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) ?: return
        val ultimo = chat.messages.lastOrNull() ?: return
        // Sin remitente = mensaje enviado por el propio usuario.
        val remitente = ultimo.person?.name ?: return
        if ((anunciados[sbn.key] ?: Long.MIN_VALUE) >= ultimo.timestamp) return
        if (System.currentTimeMillis() - ultimo.timestamp > ANTIGUEDAD_MAXIMA_MS) return
        anunciados[sbn.key] = ultimo.timestamp

        val app = nombreApp(this, sbn.packageName)
        val grupo = chat.conversationTitle?.takeIf { chat.isGroupConversation }?.let { " en el grupo $it" }.orEmpty()
        aviso("Mensaje de $app de $remitente$grupo: «${limpiar(ultimo.text)}».")
    }

    private fun ultimoMensaje(sbn: StatusBarNotification) =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.messages?.lastOrNull()

    override fun onListenerDisconnected() {
        if (instancia === this) instancia = null
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instancia: OyenteNotificaciones? = null

        /** Si no es null, se llama con el texto a leer cada vez que llega un mensaje nuevo. */
        @Volatile
        var alLlegarMensaje: ((String) -> Unit)? = null

        private const val ANTIGUEDAD_MAXIMA_MS = 2 * 60 * 1000L
        private const val MAX_NOTIFICACIONES = 15
        private const val MAX_CARACTERES = 160

        fun tieneAcceso(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /**
         * Chats pendientes que se pueden contestar desde su notificación (WhatsApp,
         * Telegram, SMS…), del más reciente al más antiguo.
         */
        fun chatsRespondibles(context: Context): List<ChatRespondible> {
            val servicio = instancia ?: return emptyList()
            return runCatching { servicio.activeNotifications }.getOrNull().orEmpty()
                .filter { it.packageName != context.packageName }
                .sortedByDescending { it.postTime }
                .mapNotNull { sbn ->
                    val n = sbn.notification
                    val accion = n.actions?.firstOrNull { a ->
                        a.remoteInputs?.any { it.allowFreeFormInput } == true
                    } ?: return@mapNotNull null
                    val chat = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
                    val nombre = (chat?.conversationTitle ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))
                        ?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ChatRespondible(nombreApp(context, sbn.packageName), nombre, accion)
                }
                .distinctBy { it.app to it.nombre }
        }

        /** Devuelve las notificaciones pendientes como texto listo para el modelo. */
        fun describir(context: Context): String {
            if (!tieneAcceso(context)) return "Notificaciones: no tengo acceso a las notificaciones."
            val servicio = instancia
            if (servicio == null) {
                requestRebind(ComponentName(context, OyenteNotificaciones::class.java))
                return "Notificaciones: el acceso se está iniciando, prueba de nuevo en unos segundos."
            }

            val pendientes = runCatching { servicio.activeNotifications }.getOrNull().orEmpty()
                .filter { sbn ->
                    val n = sbn.notification
                    sbn.packageName != context.packageName &&
                        !sbn.isOngoing &&
                        n.flags and Notification.FLAG_GROUP_SUMMARY == 0
                }
                .sortedByDescending { it.postTime }
                .take(MAX_NOTIFICACIONES)
                .mapNotNull { sbn -> describirUna(context, sbn) }

            return if (pendientes.isEmpty()) {
                "Notificaciones: no hay notificaciones pendientes."
            } else {
                "Notificaciones pendientes (${pendientes.size}):\n" + pendientes.joinToString("\n") +
                    "\nAl hablar de cada notificación di siempre de qué app es y quién la envía, y lee el " +
                    "mensaje tal cual, por ejemplo: «Tienes un mensaje de WhatsApp de Ana que dice: …»."
            }
        }

        /** Una línea por notificación: app, remitente (y grupo) y el texto literal. */
        private fun describirUna(context: Context, sbn: StatusBarNotification): String? {
            val app = nombreApp(context, sbn.packageName)

            // Apps de mensajería (WhatsApp, Telegram, SMS…): remitente de cada mensaje.
            val chat = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
            if (chat != null && chat.messages.isNotEmpty()) {
                val grupo = chat.conversationTitle?.takeIf { chat.isGroupConversation }?.let { " en el grupo «$it»" }.orEmpty()
                return chat.messages.takeLast(MAX_MENSAJES_POR_CHAT).joinToString("\n") { m ->
                    val quien = m.person?.name ?: chat.user.name ?: "alguien"
                    "- Mensaje de $app$grupo de $quien: «${limpiar(m.text)}»"
                }
            }

            val extras = sbn.notification.extras
            val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val texto = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            if (titulo.isBlank() && texto.isNullOrBlank()) return null
            return "- Notificación de $app, «$titulo»: «${limpiar(texto)}»"
        }

        private fun limpiar(texto: CharSequence?): String =
            texto?.toString().orEmpty().replace('\n', ' ').take(MAX_CARACTERES)

        private fun nombreApp(context: Context, paquete: String): String =
            NOMBRES_CONOCIDOS[paquete] ?: runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(paquete, 0)).toString()
            }.getOrElse { paquete.substringAfterLast('.') }

        private const val MAX_MENSAJES_POR_CHAT = 3

        /** Nombres tal como se pronuncian, para las apps más habituales. */
        private val NOMBRES_CONOCIDOS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Gmail",
            "com.samsung.android.messaging" to "Mensajes",
            "com.google.android.apps.messaging" to "Mensajes",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
        )
    }
}
