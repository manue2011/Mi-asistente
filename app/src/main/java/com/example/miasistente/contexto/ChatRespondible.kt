package com.example.miasistente.contexto

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Un chat al que se puede contestar desde su notificación, como hace Android
 * Auto: se rellena el campo "Responder" de la propia notificación y la app de
 * mensajería (WhatsApp, Telegram…) lo envía. No se usa ninguna API externa.
 */
class ChatRespondible(
    val app: String,
    /** Contacto o nombre del grupo, tal como aparece en la notificación. */
    val nombre: String,
    private val accion: Notification.Action,
) {
    fun enviar(context: Context, texto: String): Boolean = runCatching {
        val entradas = accion.remoteInputs
        val datos = Bundle().apply { entradas.forEach { putCharSequence(it.resultKey, texto) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(entradas, intent, datos)
        accion.actionIntent.send(context, 0, intent)
    }.onFailure { Log.w("ChatRespondible", "No se pudo responder en $app", it) }.isSuccess
}
