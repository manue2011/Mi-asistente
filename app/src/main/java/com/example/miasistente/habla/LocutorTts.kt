package com.example.miasistente.habla

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [Locutor] basado en el TextToSpeech del sistema, con voz española sin conexión. */
class LocutorTts(context: Context, private val idioma: Locale = Locale.forLanguageTag("es-ES")) : Locutor {

    private val _hablando = MutableStateFlow(false)
    override val hablando: StateFlow<Boolean> = _hablando.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val pendientes = AtomicInteger(0)
    private val siguienteId = AtomicLong(0)
    private var listo = false
    private val colaPrevia = mutableListOf<String>()
    private val manosLibres = SalidaManosLibres(context)

    override var usarManosLibres = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { estado ->
        if (estado == TextToSpeech.SUCCESS) configurar() else _error.value = "No se pudo iniciar la síntesis de voz."
    }

    private fun configurar() {
        val resultado = tts.setLanguage(idioma)
        if (resultado == TextToSpeech.LANG_MISSING_DATA || resultado == TextToSpeech.LANG_NOT_SUPPORTED) {
            _error.value = "Falta la voz en español. Instálala en Ajustes > Accesibilidad > Texto a voz."
            return
        }
        // Privacidad: prioriza una voz española que funcione sin red.
        tts.voices
            ?.filter { it.locale.language == idioma.language && !it.isNetworkConnectionRequired }
            ?.sortedWith(compareBy({ it.locale.country != idioma.country }, { -it.quality }))
            ?.firstOrNull()
            ?.let { tts.voice = it }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _hablando.value = true
            }

            override fun onDone(utteranceId: String?) = terminada()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = terminada()

            override fun onError(utteranceId: String?, errorCode: Int) = terminada()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = terminada()
        })

        synchronized(colaPrevia) {
            listo = true
            colaPrevia.forEach(::encolar)
            colaPrevia.clear()
        }
    }

    private fun terminada() {
        if (pendientes.decrementAndGet() <= 0) {
            pendientes.set(0)
            manosLibres.cerrar()
            _hablando.value = false
        }
    }

    override fun decir(texto: String) {
        val limpio = limpiar(texto)
        if (limpio.isBlank()) return
        synchronized(colaPrevia) {
            if (!listo) colaPrevia += limpio else encolar(limpio)
        }
    }

    private fun encolar(texto: String) {
        if (pendientes.getAndIncrement() == 0) prepararSalida()
        _hablando.value = true
        val id = "frase-${siguienteId.incrementAndGet()}"
        if (tts.speak(texto, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) terminada()
    }

    override fun callar() {
        synchronized(colaPrevia) { colaPrevia.clear() }
        tts.stop()
        pendientes.set(0)
        manosLibres.cerrar()
        _hablando.value = false
    }

    /**
     * Al empezar a hablar elige la salida: el manos libres (reloj) si está activado
     * y conectado, o el altavoz normal del móvil.
     */
    private fun prepararSalida() {
        val porReloj = usarManosLibres && manosLibres.abrir()
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (porReloj) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        if (porReloj) {
            // El enlace Bluetooth tarda un momento en abrirse: un silencio evita que se corten las primeras palabras.
            pendientes.incrementAndGet()
            tts.playSilentUtterance(RETARDO_MANOS_LIBRES_MS, TextToSpeech.QUEUE_ADD, "silencio-${siguienteId.incrementAndGet()}")
        }
    }

    override fun liberar() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val RETARDO_MANOS_LIBRES_MS = 900L
        private val markdown = Regex("""[*_#`>~|]+""")
        private val emojis = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{FE0F}\x{200D}]""")
        private val espacios = Regex("""\s+""")

        /** Quita formato Markdown y emojis, que suenan mal leídos en voz alta. */
        fun limpiar(texto: String): String =
            texto.replace(markdown, " ").replace(emojis, "").replace(espacios, " ").trim()
    }
}
