package com.example.miasistente.acciones

import android.Manifest
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.miasistente.contexto.GestorContexto.Companion.normalizar
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Lo que el asistente puede hacer en el móvil. Todo es local: usa las apps
 * del propio teléfono (Reloj, Teléfono, Calendario…) sin servicios externos.
 */
class AccionesTelefono(private val context: Context) {

    data class Contacto(val nombre: String, val numero: String)
    data class App(val nombre: String, val paquete: String)

    fun tiene(permiso: String) =
        ContextCompat.checkSelfPermission(context, permiso) == PackageManager.PERMISSION_GRANTED

    /** Con la app cerrada, Android solo deja abrir otras pantallas con el permiso "Mostrar sobre otras apps". */
    fun puedeAbrirPantallas(appVisible: Boolean) = appVisible || Settings.canDrawOverlays(context)

    val bloqueado: Boolean
        get() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun lanzar(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w(TAG, "No se pudo lanzar $intent", it) }.isSuccess

    fun ponerAlarma(hora: LocalTime, etiqueta: String?): Boolean = lanzar(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hora.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, hora.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (!etiqueta.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, etiqueta) },
    )

    fun ponerTemporizador(duracion: Duration): Boolean = lanzar(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, duracion.seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Mi asistente"),
    )

    /** Llama directamente (sin pasar por el marcador), también con el móvil bloqueado. */
    fun llamar(numero: String): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", numero, null), null)
    }.onFailure { Log.w(TAG, "No se pudo llamar", it) }.isSuccess

    fun buscarContacto(nombre: String): Contacto? {
        if (!tiene(Manifest.permission.READ_CONTACTS)) return null
        val buscado = normalizar(nombre).trim()
        val candidatos = mutableListOf<Contacto>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            null, null, "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val numero = c.getString(1) ?: continue
                candidatos += Contacto(n, numero)
            }
        }
        // Exacto, luego empieza por, luego contiene.
        return candidatos.firstOrNull { normalizar(it.nombre) == buscado }
            ?: candidatos.firstOrNull { normalizar(it.nombre).startsWith(buscado) }
            ?: candidatos.firstOrNull { normalizar(it.nombre).contains(buscado) }
    }

    fun buscarApp(nombre: String): App? {
        val buscado = normalizar(nombre).trim()
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.paquete != context.packageName }
        return apps.firstOrNull { normalizar(it.nombre) == buscado }
            ?: apps.firstOrNull { normalizar(it.nombre).startsWith(buscado) }
            ?: apps.firstOrNull { normalizar(it.nombre).contains(buscado) }
    }

    fun abrirApp(app: App): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.paquete) ?: return false
        return lanzar(intent)
    }

    /** Crea un evento de [duracion] en el calendario principal del móvil. */
    fun crearEvento(titulo: String, fecha: LocalDate, hora: LocalTime?, duracion: Duration = Duration.ofHours(1)): Boolean {
        if (!tiene(Manifest.permission.WRITE_CALENDAR)) return false
        val calendario = calendarioPrincipal() ?: return false
        val zona = ZoneId.systemDefault()
        val valores = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendario)
            put(CalendarContract.Events.TITLE, titulo)
            if (hora == null) {
                // Evento de todo el día: en UTC, como exige el proveedor de calendario.
                val inicio = fecha.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, inicio)
                put(CalendarContract.Events.DTEND, inicio + Duration.ofDays(1).toMillis())
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val inicio = fecha.atTime(hora).atZone(zona).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, inicio)
                put(CalendarContract.Events.DTEND, inicio + duracion.toMillis())
                put(CalendarContract.Events.EVENT_TIMEZONE, zona.id)
            }
        }
        return runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valores) }
            .onFailure { Log.w(TAG, "No se pudo crear el evento", it) }
            .getOrNull() != null
    }

    private fun calendarioPrincipal(): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    companion object {
        private const val TAG = "AccionesTelefono"
    }
}
