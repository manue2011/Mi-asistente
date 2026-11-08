package com.example.miasistente.rutina

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.miasistente.Fase
import com.example.miasistente.MainActivity
import com.example.miasistente.MiAsistenteApp
import com.example.miasistente.cerebro.EstadoCerebro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lee el resumen matutino en voz alta aunque el móvil esté bloqueado.
 *
 * Lo arranca la alarma exacta del resumen (Android permite iniciar un
 * servicio en primer plano desde una alarma exacta). Mantiene la CPU
 * despierta mientras carga el modelo, descarga las noticias y habla, y se
 * detiene solo al terminar. No usa el micrófono.
 */
class ServicioResumen : Service() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bloqueo: PowerManager.WakeLock? = null

    private val asistente get() = (application as MiAsistenteApp).asistente

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Lectura del resumen", NotificationManager.IMPORTANCE_LOW),
        )
        try {
            startForeground(ID_NOTIFICACION, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: IllegalStateException) {
            // Android no dejó arrancar en segundo plano: queda la notificación de siempre.
            ResumenMatutino(this).avisar()
            stopSelf()
            return
        }
        bloqueo = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiAsistente:resumen")
            .apply { acquire(DURACION_MAXIMA_MS) }

        alcance.launch {
            asistente.pedirResumen()
            withTimeoutOrNull(DURACION_MAXIMA_MS) {
                // Primero espera a que cargue el modelo y empiece; luego, a que termine de hablar.
                val estado = asistente.ui.first {
                    it.fase == Fase.PENSANDO || it.fase == Fase.HABLANDO ||
                        it.cerebro is EstadoCerebro.Error || it.cerebro is EstadoCerebro.SinModelo
                }
                if (estado.fase == Fase.PENSANDO || estado.fase == Fase.HABLANDO) {
                    asistente.ui.first { it.fase == Fase.INACTIVO || it.fase == Fase.ESCUCHANDO }
                } else {
                    ResumenMatutino(this@ServicioResumen).avisar()
                }
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) {
            val fase = asistente.ui.value.fase
            if (fase == Fase.PENSANDO || fase == Fase.HABLANDO) asistente.alternar()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, ServicioResumen::class.java).setAction(ACCION_PARAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("☀️ Buenos días")
            .setContentText("Leyendo tu resumen del día…")
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", parar).build())
            .build()
    }

    override fun onDestroy() {
        alcance.cancel()
        bloqueo?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CANAL = "lectura_resumen"
        private const val ID_NOTIFICACION = 3
        private const val ACCION_PARAR = "com.example.miasistente.PARAR_RESUMEN"
        private const val DURACION_MAXIMA_MS = 5 * 60 * 1000L

        /** Devuelve false si Android no deja arrancarlo (entonces hay que avisar con una notificación). */
        fun iniciar(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ServicioResumen::class.java))
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
}
