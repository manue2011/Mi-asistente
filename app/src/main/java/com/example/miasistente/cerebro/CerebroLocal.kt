package com.example.miasistente.cerebro

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Cerebro local: un modelo Gemma ejecutado en el móvil con LiteRT-LM
 * (Google AI Edge). Todo ocurre en el dispositivo.
 *
 * Todas las llamadas al motor se hacen en un único hilo dedicado, así la
 * carga del modelo nunca bloquea la interfaz y el motor no se usa en paralelo.
 */
class CerebroLocal(
    private val context: Context,
    private val archivo: ArchivoModelo = ArchivoModelo(context),
) : Cerebro {

    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "cerebro-local") }
        .asCoroutineDispatcher()

    private val _estado = MutableStateFlow<EstadoCerebro>(EstadoCerebro.SinIniciar)
    override val estado: StateFlow<EstadoCerebro> = _estado.asStateFlow()

    private var motor: Engine? = null
    private var conversacion: Conversation? = null

    /** Tamaño del historial con el que está sincronizada [conversacion]; -1 si hay que reconstruirla. */
    private var historialSincronizado = -1

    /** Mensajes que contiene la conversación nativa (para no desbordar la ventana de contexto). */
    private var mensajesEnSesion = 0

    override suspend fun preparar() = withContext(hilo) {
        if (motor != null) return@withContext
        val modelo = archivo.buscar()
        if (modelo == null) {
            _estado.value = EstadoCerebro.SinModelo(archivo.carpeta.absolutePath)
            return@withContext
        }

        // GPU primero (mucho más rápida en el Snapdragon 8 Gen 3); si falla, CPU.
        for (backend in listOf(Backend.GPU(), Backend.CPU())) {
            _estado.value = EstadoCerebro.Cargando("Cargando ${modelo.name} (${backend.name})…")
            try {
                val nuevo = Engine(
                    EngineConfig(
                        modelPath = modelo.absolutePath,
                        backend = backend,
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                )
                nuevo.initialize()
                motor = nuevo
                _estado.value = EstadoCerebro.Listo("${modelo.nameWithoutExtension} · ${backend.name}")
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "No se pudo cargar con ${backend.name}", e)
            }
        }
        _estado.value = EstadoCerebro.Error("No se pudo cargar el modelo ${modelo.name}.")
    }

    override fun responder(historial: List<Turno>, mensaje: String): Flow<String> = flow {
        val motor = checkNotNull(motor) { "El modelo aún no está cargado." }

        // Si el historial no coincide con la conversación nativa, o esta ya es
        // demasiado larga, se reconstruye con los últimos turnos.
        if (conversacion == null || historialSincronizado != historial.size || mensajesEnSesion >= MAX_MENSAJES) {
            val recientes = historial.takeLast(MENSAJES_AL_RECORTAR).dropWhile { it.rol != Rol.USUARIO }
            conversacion?.close()
            conversacion = motor.createConversation(configuracion(recientes))
            mensajesEnSesion = recientes.size
        }
        val conv = checkNotNull(conversacion)

        // Hasta terminar bien, la conversación nativa no se considera sincronizada.
        historialSincronizado = -1
        try {
            conv.sendMessageAsync(mensaje).collect { trozo -> emit(trozo.toString()) }
        } catch (e: CancellationException) {
            // El usuario ha interrumpido: detiene la generación nativa.
            conv.cancelProcess()
            throw e
        }
        historialSincronizado = historial.size + 2
        mensajesEnSesion += 2
    }.flowOn(hilo)

    private fun configuracion(previos: List<Turno>) = ConversationConfig(
        systemInstruction = Contents.of(Personalidad.INSTRUCCIONES_SISTEMA),
        initialMessages = previos.map {
            if (it.rol == Rol.USUARIO) Message.user(it.texto) else Message.model(it.texto)
        },
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
    )

    override suspend fun olvidar() = withContext(hilo) {
        conversacion?.close()
        conversacion = null
        historialSincronizado = -1
        mensajesEnSesion = 0
    }

    override fun close() {
        hilo.executor.execute {
            conversacion?.close()
            motor?.close()
            conversacion = null
            motor = null
        }
        hilo.close()
    }

    companion object {
        private const val TAG = "CerebroLocal"
        private const val MAX_TOKENS = 4096
        private const val MAX_MENSAJES = 30
        private const val MENSAJES_AL_RECORTAR = 12
    }
}
