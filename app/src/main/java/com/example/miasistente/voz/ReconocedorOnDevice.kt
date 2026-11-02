package com.example.miasistente.voz

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconocimiento de voz 100 % en el dispositivo con
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer] (Android 12+).
 * El audio nunca sale del móvil.
 */
class ReconocedorOnDevice(
    private val context: Context,
    private val idioma: String = "es-ES",
) : OyenteVoz {

    private var reconocedor: SpeechRecognizer? = null

    override val disponible: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private val intent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    override fun escuchar(alEvento: (EventoVoz) -> Unit) {
        if (!disponible) {
            alEvento(EventoVoz.Error(MENSAJE_NO_DISPONIBLE))
            return
        }
        val r = reconocedor ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            .also { reconocedor = it }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = alEvento(EventoVoz.Listo)

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB suele moverse entre -2 y 10.
                alEvento(EventoVoz.Nivel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texto = partialResults.primerResultado() ?: return
                if (texto.isNotBlank()) alEvento(EventoVoz.Parcial(texto))
            }

            override fun onResults(results: Bundle?) {
                val texto = results.primerResultado()
                if (texto.isNullOrBlank()) {
                    alEvento(EventoVoz.Error("No te he entendido. ¿Lo repites?"))
                } else {
                    alEvento(EventoVoz.Final(texto))
                }
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                ) {
                    pedirDescargaIdioma(r)
                }
                // Tras un error grave conviene recrear el reconocedor.
                if (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ) {
                    liberar()
                }
                alEvento(EventoVoz.Error(describirError(error)))
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent)
    }

    override fun detener() {
        reconocedor?.stopListening()
    }

    override fun cancelar() {
        reconocedor?.cancel()
    }

    override fun liberar() {
        reconocedor?.destroy()
        reconocedor = null
    }

    /** Pide al sistema que descargue el paquete de idioma sin conexión (Android 13+). */
    private fun pedirDescargaIdioma(r: SpeechRecognizer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { r.triggerModelDownload(intent) }
        }
    }

    private fun Bundle?.primerResultado(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describirError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído. Toca el círculo y habla."
        SpeechRecognizer.ERROR_AUDIO -> "Problema con el micrófono."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Necesito permiso para usar el micrófono."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado. Inténtalo de nuevo."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Falta el paquete de voz en español sin conexión. He pedido su descarga; " +
                "si no empieza, instálalo en Ajustes > Gestión general > Reconocimiento de voz."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Demasiadas peticiones seguidas. Espera un momento."
        else -> "Error de reconocimiento de voz ($error)."
    }

    companion object {
        const val MENSAJE_NO_DISPONIBLE =
            "El reconocimiento de voz en el dispositivo no está disponible. " +
                "Instala o actualiza \"Speech Recognition & Synthesis\" / Google y descarga el español sin conexión."
    }
}
