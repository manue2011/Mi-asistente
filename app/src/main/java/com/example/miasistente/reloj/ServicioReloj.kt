package com.example.miasistente.reloj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.miasistente.Fase
import com.example.miasistente.MainActivity
import com.example.miasistente.MiAsistenteApp
import com.example.miasistente.cerebro.Rol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Modo reloj: el asistente se presenta como un reproductor de música.
 *
 * El Huawei Watch GT 3 Pro (vía Huawei Salud) controla el reproductor activo
 * del móvil, así que al pulsar ▶/⏸ en el reloj el móvil empieza o deja de
 * escuchar. En el reloj, donde va el título de la canción, se ve el estado y
 * la última respuesta. También funciona con los botones de unos auriculares.
 *
 * Es un servicio en primer plano con micrófono: Android solo deja usar el
 * micrófono en segundo plano así, y solo si se arranca con la app abierta.
 */
class ServicioReloj : Service() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sesion: MediaSession

    private val asistente get() = (application as MiAsistenteApp).asistente

    override fun onCreate() {
        super.onCreate()
        sesion = MediaSession(this, "MiAsistente").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = asistente.alternar()
                override fun onPause() = asistente.alternar()
                override fun onStop() = asistente.alternar()
                override fun onSkipToNext() = asistente.alternar()
                override fun onSkipToPrevious() = asistente.alternar()
            })
            isActive = true
        }
        asistente.vozPorReloj = true

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Modo reloj", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            ID_NOTIFICACION,
            notificacion("Pulsa ▶ en el reloj para hablar"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        alcance.launch {
            asistente.ui.collect { estado ->
                val ultima = estado.mensajes.lastOrNull { it.rol == Rol.ASISTENTE }?.texto.orEmpty()
                val titulo = when (estado.fase) {
                    Fase.CARGANDO -> "Cargando el modelo…"
                    Fase.INACTIVO -> ultima.ifBlank { "Pulsa ▶ para hablar" }
                    Fase.ESCUCHANDO -> estado.transcripcionParcial.ifBlank { "Te escucho…" }
                    Fase.PENSANDO -> "Pensando…"
                    Fase.HABLANDO -> ultima
                }
                actualizar(estado.fase, titulo.take(MAX_TITULO))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            detener(this)
        }
        return START_NOT_STICKY
    }

    /** ▶ cuando está en reposo; ⏸ mientras escucha, piensa o habla (pulsar = parar). */
    private fun actualizar(fase: Fase, titulo: String) {
        val activo = fase == Fase.ESCUCHANDO || fase == Fase.PENSANDO || fase == Fase.HABLANDO
        sesion.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(if (activo) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f)
                .build(),
        )
        sesion.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Mi asistente")
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACION, notificacion(titulo))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, ServicioReloj::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Mi asistente · modo reloj")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Desactivar", parar).build())
            .setStyle(Notification.MediaStyle().setMediaSession(sesion.sessionToken))
            .build()
    }

    override fun onDestroy() {
        asistente.vozPorReloj = false
        alcance.cancel()
        sesion.isActive = false
        sesion.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CANAL = "modo_reloj"
        private const val ID_NOTIFICACION = 2
        private const val MAX_TITULO = 120
        private const val ACCION_DETENER = "com.example.miasistente.DETENER_RELOJ"
        private const val PREFS = "modo_reloj"
        private const val CLAVE_ACTIVO = "activo"

        fun activo(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_ACTIVO, false)

        /** Debe llamarse con la app en primer plano (requisito de Android para el micrófono). */
        fun iniciar(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(CLAVE_ACTIVO, true) }
            ContextCompat.startForegroundService(context, Intent(context, ServicioReloj::class.java))
        }

        fun detener(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(CLAVE_ACTIVO, false) }
            context.stopService(Intent(context, ServicioReloj::class.java))
        }
    }
}
