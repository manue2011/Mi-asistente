package com.example.miasistente.habla

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Envía la voz del asistente a un manos libres Bluetooth (por ejemplo, el
 * altavoz del Huawei Watch GT 3 Pro) usando el canal de llamadas (SCO).
 * La calidad es la de una llamada telefónica.
 */
class SalidaManosLibres(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var abierta = false

    /** Dispositivo manos libres conectado, o null si no hay ninguno. */
    private fun manosLibres(): AudioDeviceInfo? =
        audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    val disponible: Boolean get() = manosLibres() != null

    /** Abre el canal hacia el manos libres. Devuelve false si no hay ninguno. */
    fun abrir(): Boolean {
        if (abierta) return true
        val dispositivo = manosLibres() ?: return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        abierta = audio.setCommunicationDevice(dispositivo)
        if (abierta) {
            // Android acepta la petición y puede fallar justo después (p. ej. si las llamadas
            // Bluetooth del reloj están desactivadas): se comprueba antes de confiar en ella.
            Thread.sleep(ESPERA_CONFIRMACION_MS)
            if (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audio.clearCommunicationDevice()
                abierta = false
            }
        }
        if (!abierta) audio.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "Canal manos libres hacia ${dispositivo.productName}: ${if (abierta) "abierto" else "rechazado"}")
        return abierta
    }

    fun cerrar() {
        if (!abierta) return
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
        abierta = false
        Log.i(TAG, "Canal manos libres cerrado")
    }

    companion object {
        private const val TAG = "ManosLibres"
        private const val ESPERA_CONFIRMACION_MS = 150L
    }
}
