package com.example.miasistente.rutina

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.miasistente.MainActivity
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Resumen matutino: cada día a la hora elegida aparece una notificación;
 * al tocarla se abre el asistente y lee el resumen (agenda + notificaciones).
 *
 * Android no deja abrir una app sola desde segundo plano, por eso se avisa
 * con una notificación en vez de hablar directamente.
 */
class ResumenMatutino(private val context: Context) {

    private val prefs = context.getSharedPreferences("resumen_matutino", Context.MODE_PRIVATE)

    var activo: Boolean
        get() = prefs.getBoolean(CLAVE_ACTIVO, false)
        set(valor) {
            prefs.edit { putBoolean(CLAVE_ACTIVO, valor) }
            programar()
        }

    var hora: LocalTime
        get() = LocalTime.of(prefs.getInt(CLAVE_HORA, 8), prefs.getInt(CLAVE_MINUTO, 0))
        set(valor) {
            prefs.edit {
                putInt(CLAVE_HORA, valor.hour)
                putInt(CLAVE_MINUTO, valor.minute)
            }
            programar()
        }

    /** (Re)programa la alarma diaria, o la cancela si el resumen está desactivado. */
    fun programar() {
        val alarmas = context.getSystemService(AlarmManager::class.java)
        val disparo = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReceptorResumen::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmas.cancel(disparo)
        if (!activo) return

        val ahora = ZonedDateTime.now()
        var siguiente = ahora.with(hora).withSecond(0).withNano(0)
        if (!siguiente.isAfter(ahora)) siguiente = siguiente.plusDays(1)
        // Ventana de 5 minutos: no necesita el permiso de alarmas exactas.
        alarmas.setWindow(AlarmManager.RTC_WAKEUP, siguiente.toInstant().toEpochMilli(), VENTANA_MS, disparo)
    }

    fun avisar() {
        val gestor = context.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(
            NotificationChannel(CANAL, "Resumen matutino", NotificationManager.IMPORTANCE_HIGH),
        )
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val abrir = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_RESUMEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificacion = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("☀️ Buenos días")
            .setContentText("Toca para escuchar tu resumen del día.")
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(ID_NOTIFICACION, notificacion)
    }

    companion object {
        const val EXTRA_RESUMEN = "com.example.miasistente.RESUMEN"
        private const val CANAL = "resumen_matutino"
        private const val ID_NOTIFICACION = 1
        private const val VENTANA_MS = 5 * 60 * 1000L
        private const val CLAVE_ACTIVO = "activo"
        private const val CLAVE_HORA = "hora"
        private const val CLAVE_MINUTO = "minuto"
    }
}

/** Salta con la alarma diaria: avisa y programa la del día siguiente. */
class ReceptorResumen : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val resumen = ResumenMatutino(context)
        resumen.avisar()
        resumen.programar()
    }
}

/** Las alarmas se borran al reiniciar el móvil o actualizar la app: se vuelven a programar. */
class ReceptorArranque : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ResumenMatutino(context).programar()
        }
    }
}
