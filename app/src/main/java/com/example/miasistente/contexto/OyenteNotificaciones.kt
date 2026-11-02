package com.example.miasistente.contexto

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Servicio del sistema que da acceso a las notificaciones pendientes.
 * No guarda nada: solo se consultan cuando el usuario las pide, y el texto
 * únicamente se pasa al modelo local.
 */
class OyenteNotificaciones : NotificationListenerService() {

    override fun onListenerConnected() {
        instancia = this
    }

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

        private const val MAX_NOTIFICACIONES = 15
        private const val MAX_CARACTERES = 160

        fun tieneAcceso(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

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
                .mapNotNull { sbn ->
                    val extras = sbn.notification.extras
                    val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val texto = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                    if (titulo.isBlank() && texto.isBlank()) return@mapNotNull null
                    val app = nombreApp(context, sbn.packageName)
                    "- $app · $titulo: ${texto.replace('\n', ' ').take(MAX_CARACTERES)}"
                }

            return if (pendientes.isEmpty()) {
                "Notificaciones: no hay notificaciones pendientes."
            } else {
                "Notificaciones pendientes (${pendientes.size}):\n" + pendientes.joinToString("\n")
            }
        }

        private fun nombreApp(context: Context, paquete: String): String =
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(paquete, 0)).toString()
            }.getOrElse { paquete.substringAfterLast('.') }
    }
}
