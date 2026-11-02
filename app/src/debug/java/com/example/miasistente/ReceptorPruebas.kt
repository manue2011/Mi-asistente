package com.example.miasistente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.miasistente.cerebro.Rol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Solo debug. Envía texto al asistente como si se hubiera dicho por voz:
 *   adb shell am broadcast -a com.example.miasistente.PRUEBA --es texto "pon un temporizador de 10 segundos"
 * Las respuestas se escriben en logcat con la etiqueta "Pruebas".
 */
class ReceptorPruebas : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val texto = intent.getStringExtra("texto") ?: return
        val asistente = (context.applicationContext as MiAsistenteApp).asistente
        val pendiente = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            // Si está escuchando (p. ej. tras una pregunta de confirmación), se corta la escucha primero.
            if (asistente.ui.value.fase == Fase.ESCUCHANDO) {
                asistente.alternar()
                withTimeoutOrNull(8_000) { asistente.ui.first { it.fase != Fase.ESCUCHANDO } }
            }
            Log.i(TAG, "> $texto")
            asistente.procesar(texto)
            delay(300)
            withTimeoutOrNull(60_000) {
                asistente.ui
                    .map { it.mensajes.lastOrNull { m -> m.rol == Rol.ASISTENTE }?.texto to it.fase }
                    .distinctUntilChanged()
                    .collect { (respuesta, fase) ->
                        if (fase == Fase.INACTIVO || fase == Fase.ESCUCHANDO) {
                            Log.i(TAG, "< $respuesta")
                            pendiente.finish()
                            throw kotlinx.coroutines.CancellationException()
                        }
                    }
            }
            runCatching { pendiente.finish() }
        }
    }

    companion object {
        private const val TAG = "Pruebas"
    }
}
