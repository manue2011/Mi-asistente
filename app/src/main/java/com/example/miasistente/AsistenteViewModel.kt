package com.example.miasistente

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.miasistente.cerebro.ArchivoModelo
import com.example.miasistente.cerebro.Cerebro
import com.example.miasistente.cerebro.CerebroLocal
import com.example.miasistente.cerebro.EstadoCerebro
import com.example.miasistente.cerebro.Rol
import com.example.miasistente.cerebro.Turno
import com.example.miasistente.habla.Locutor
import com.example.miasistente.habla.LocutorTts
import com.example.miasistente.voz.EventoVoz
import com.example.miasistente.voz.OyenteVoz
import com.example.miasistente.voz.ReconocedorOnDevice
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Fase { CARGANDO, INACTIVO, ESCUCHANDO, PENSANDO, HABLANDO }

data class MensajeChat(val id: Long, val rol: Rol, val texto: String)

data class EstadoUi(
    val fase: Fase = Fase.CARGANDO,
    val mensajes: List<MensajeChat> = emptyList(),
    /** Lo que el reconocedor va entendiendo mientras el usuario habla. */
    val transcripcionParcial: String = "",
    val nivelVoz: Float = 0f,
    val aviso: String? = null,
    val cerebro: EstadoCerebro = EstadoCerebro.SinIniciar,
    val vozDisponible: Boolean = true,
    /** Progreso al importar un modelo desde el selector de archivos (0..1), o null. */
    val importando: Float? = null,
)

/**
 * Orquesta los tres módulos: voz (oír) → cerebro (pensar) → habla (responder).
 *
 * [alternar] es el punto de entrada único para activar el asistente; un
 * disparador externo (reloj Huawei, notificación, resumen matutino…) puede
 * reutilizarlo, o usar [procesar] para enviar texto directamente.
 */
class AsistenteViewModel(app: Application) : AndroidViewModel(app) {

    private val oyente: OyenteVoz = ReconocedorOnDevice(app)
    private val archivoModelo = ArchivoModelo(app)
    private val cerebro: Cerebro = CerebroLocal(app, archivoModelo)
    private val locutor: Locutor = LocutorTts(app)

    private val _ui = MutableStateFlow(EstadoUi())
    val ui: StateFlow<EstadoUi> = _ui.asStateFlow()

    /** Historial completo de la conversación (turnos terminados). */
    private val historial = mutableListOf<Turno>()
    private var siguienteId = 0L
    private var trabajoRespuesta: Job? = null
    private var generando = false

    init {
        val vozDisponible = oyente.disponible
        _ui.update {
            it.copy(
                vozDisponible = vozDisponible,
                aviso = if (vozDisponible) null else ReconocedorOnDevice.MENSAJE_NO_DISPONIBLE,
            )
        }
        viewModelScope.launch { cerebro.estado.collect(::alCambiarCerebro) }
        viewModelScope.launch { locutor.hablando.collect(::alCambiarHabla) }
        viewModelScope.launch { locutor.error.collect { e -> if (e != null) avisar(e) } }
        cargarModelo()
    }

    fun cargarModelo() {
        viewModelScope.launch { cerebro.preparar() }
    }

    private fun alCambiarCerebro(estado: EstadoCerebro) {
        _ui.update {
            val fase = when (estado) {
                is EstadoCerebro.Listo -> if (it.fase == Fase.CARGANDO) Fase.INACTIVO else it.fase
                else -> Fase.CARGANDO
            }
            it.copy(cerebro = estado, fase = fase)
        }
    }

    private fun alCambiarHabla(hablando: Boolean) {
        _ui.update {
            when {
                hablando && it.fase == Fase.PENSANDO -> it.copy(fase = Fase.HABLANDO)
                !hablando && !generando && it.fase == Fase.HABLANDO -> it.copy(fase = Fase.INACTIVO)
                else -> it
            }
        }
    }

    /** Toque en el círculo: escuchar, terminar de escuchar o interrumpir. */
    fun alternar() {
        when (_ui.value.fase) {
            Fase.CARGANDO -> avisar("Un momento, aún estoy despertando.")
            Fase.INACTIVO -> escuchar()
            Fase.ESCUCHANDO -> oyente.detener()
            Fase.PENSANDO, Fase.HABLANDO -> interrumpir()
        }
    }

    private fun escuchar() {
        if (!oyente.disponible) {
            avisar(ReconocedorOnDevice.MENSAJE_NO_DISPONIBLE)
            return
        }
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            avisar("Necesito permiso para usar el micrófono.")
            return
        }
        _ui.update { it.copy(fase = Fase.ESCUCHANDO, transcripcionParcial = "", aviso = null, nivelVoz = 0f) }
        oyente.escuchar { evento ->
            when (evento) {
                EventoVoz.Listo -> Unit
                is EventoVoz.Nivel -> _ui.update { it.copy(nivelVoz = evento.valor) }
                is EventoVoz.Parcial -> _ui.update { it.copy(transcripcionParcial = evento.texto) }
                is EventoVoz.Final -> {
                    _ui.update { it.copy(transcripcionParcial = "", nivelVoz = 0f) }
                    procesar(evento.texto)
                }
                is EventoVoz.Error -> {
                    _ui.update {
                        it.copy(fase = Fase.INACTIVO, transcripcionParcial = "", nivelVoz = 0f, aviso = evento.mensaje)
                    }
                }
            }
        }
    }

    /** Envía [texto] al cerebro y lee la respuesta en voz alta según se genera. */
    fun procesar(texto: String) {
        if (_ui.value.cerebro !is EstadoCerebro.Listo) {
            avisar("El modelo aún no está listo.")
            return
        }
        val idRespuesta = siguienteId + 1
        _ui.update {
            it.copy(
                fase = Fase.PENSANDO,
                aviso = null,
                mensajes = it.mensajes +
                    MensajeChat(siguienteId++, Rol.USUARIO, texto) +
                    MensajeChat(siguienteId++, Rol.ASISTENTE, ""),
            )
        }

        val previos = historial.toList()
        generando = true
        trabajoRespuesta = viewModelScope.launch {
            val respuesta = StringBuilder()
            val porDecir = StringBuilder()
            try {
                cerebro.responder(previos, texto).collect { trozo ->
                    respuesta.append(trozo)
                    porDecir.append(trozo)
                    actualizarMensaje(idRespuesta, respuesta.toString())
                    // Se lee frase a frase para empezar a hablar cuanto antes.
                    extraerFrasesCompletas(porDecir).forEach(locutor::decir)
                }
                locutor.decir(porDecir.toString())
                val final = respuesta.toString().trim()
                historial += Turno(Rol.USUARIO, texto)
                historial += Turno(Rol.ASISTENTE, final)
                if (final.isEmpty()) actualizarMensaje(idRespuesta, "…")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                actualizarMensaje(idRespuesta, respuesta.toString().ifEmpty { "(sin respuesta)" })
                avisar("Error al generar la respuesta: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                generando = false
                _ui.update {
                    if (!locutor.hablando.value && it.fase in setOf(Fase.PENSANDO, Fase.HABLANDO)) {
                        it.copy(fase = Fase.INACTIVO)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun interrumpir() {
        trabajoRespuesta?.cancel()
        locutor.callar()
        generando = false
        _ui.update { it.copy(fase = Fase.INACTIVO) }
    }

    /** Empieza una conversación nueva, sin contexto previo. */
    fun nuevaConversacion() {
        interrumpir()
        oyente.cancelar()
        historial.clear()
        _ui.update { it.copy(mensajes = emptyList(), transcripcionParcial = "", aviso = null) }
        viewModelScope.launch { cerebro.olvidar() }
    }

    /** Copia un modelo elegido por el usuario y lo carga. */
    fun importarModelo(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(importando = 0f, aviso = null) }
            try {
                archivoModelo.importar(uri) { p -> _ui.update { it.copy(importando = p) } }
                cerebro.preparar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                avisar("No se pudo importar el modelo: ${e.message}")
            } finally {
                _ui.update { it.copy(importando = null) }
            }
        }
    }

    fun descartarAviso() = _ui.update { it.copy(aviso = null) }

    private fun avisar(mensaje: String) = _ui.update { it.copy(aviso = mensaje) }

    private fun actualizarMensaje(id: Long, texto: String) = _ui.update { estado ->
        estado.copy(mensajes = estado.mensajes.map { if (it.id == id) it.copy(texto = texto) else it })
    }

    override fun onCleared() {
        trabajoRespuesta?.cancel()
        oyente.liberar()
        locutor.liberar()
        cerebro.close()
    }

    companion object {
        private val finDeFrase = Regex("""(?<=[.!?…;:])\s+|\n+""")

        /** Saca de [buffer] las frases ya terminadas y deja el resto. */
        fun extraerFrasesCompletas(buffer: StringBuilder): List<String> {
            val ultimo = finDeFrase.findAll(buffer).lastOrNull() ?: return emptyList()
            val completas = buffer.substring(0, ultimo.range.last + 1)
            buffer.delete(0, ultimo.range.last + 1)
            return completas.split(finDeFrase).filter { it.isNotBlank() }
        }
    }
}
