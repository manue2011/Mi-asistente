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
import com.example.miasistente.contexto.GestorContexto
import com.example.miasistente.contexto.LectorCalendario
import com.example.miasistente.contexto.OyenteNotificaciones
import com.example.miasistente.rutina.ResumenMatutino
import java.time.LocalTime
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

/** Estado de las funciones opcionales (pantalla de ajustes). */
data class EstadoAjustes(
    val accesoNotificaciones: Boolean = false,
    val permisoCalendario: Boolean = false,
    val resumenActivo: Boolean = false,
    val horaResumen: LocalTime = LocalTime.of(8, 0),
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
    private val contexto = GestorContexto(app)
    private val resumenMatutino = ResumenMatutino(app)

    private val _ajustes = MutableStateFlow(EstadoAjustes())
    val ajustes: StateFlow<EstadoAjustes> = _ajustes.asStateFlow()

    private val _ui = MutableStateFlow(EstadoUi())
    val ui: StateFlow<EstadoUi> = _ui.asStateFlow()

    /** Historial completo de la conversación (turnos terminados). */
    private val historial = mutableListOf<Turno>()
    private var siguienteId = 0L
    private var trabajoRespuesta: Job? = null
    private var generando = false

    /** Resumen pedido antes de que el modelo terminara de cargar. */
    private var resumenPendiente = false

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

    /** Relee permisos y preferencias (al volver de los Ajustes del sistema, por ejemplo). */
    fun refrescarAjustes() {
        val app = getApplication<Application>()
        _ajustes.value = EstadoAjustes(
            accesoNotificaciones = OyenteNotificaciones.tieneAcceso(app),
            permisoCalendario = LectorCalendario(app).tienePermiso,
            resumenActivo = resumenMatutino.activo,
            horaResumen = resumenMatutino.hora,
        )
    }

    fun activarResumen(activo: Boolean) {
        resumenMatutino.activo = activo
        refrescarAjustes()
    }

    fun cambiarHoraResumen(hora: LocalTime) {
        resumenMatutino.hora = hora
        refrescarAjustes()
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
        if (estado is EstadoCerebro.Listo && resumenPendiente) pedirResumen()
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

    /** Pide el resumen matutino; si el modelo aún carga, se hará en cuanto esté listo. */
    fun pedirResumen() {
        if (_ui.value.cerebro !is EstadoCerebro.Listo) {
            resumenPendiente = true
            return
        }
        resumenPendiente = false
        when (_ui.value.fase) {
            Fase.ESCUCHANDO -> {
                oyente.cancelar()
                _ui.update { it.copy(fase = Fase.INACTIVO, transcripcionParcial = "", nivelVoz = 0f) }
            }
            Fase.PENSANDO, Fase.HABLANDO -> interrumpir()
            Fase.CARGANDO, Fase.INACTIVO -> Unit
        }
        procesar(GestorContexto.PETICION_RESUMEN, visible = "☀️ Resumen del día")
    }

    /**
     * Envía [texto] al cerebro (con la información del móvil que haga falta)
     * y lee la respuesta en voz alta según se genera. [visible] es lo que se
     * muestra en la burbuja del usuario.
     */
    fun procesar(texto: String, visible: String = texto) {
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
                    MensajeChat(siguienteId++, Rol.USUARIO, visible) +
                    MensajeChat(siguienteId++, Rol.ASISTENTE, ""),
            )
        }

        val previos = historial.toList()
        generando = true
        trabajoRespuesta = viewModelScope.launch {
            val respuesta = StringBuilder()
            val porDecir = StringBuilder()
            try {
                // El historial guarda el mensaje enriquecido para no perder el contexto en preguntas de seguimiento.
                val mensaje = contexto.enriquecer(texto)
                cerebro.responder(previos, mensaje).collect { trozo ->
                    respuesta.append(trozo)
                    porDecir.append(trozo)
                    actualizarMensaje(idRespuesta, respuesta.toString())
                    // Se lee frase a frase para empezar a hablar cuanto antes.
                    extraerFrasesCompletas(porDecir).forEach(locutor::decir)
                }
                locutor.decir(porDecir.toString())
                val final = respuesta.toString().trim()
                historial += Turno(Rol.USUARIO, mensaje)
                historial += Turno(Rol.ASISTENTE, final)
                if (final.isEmpty()) actualizarMensaje(idRespuesta, "…")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                actualizarMensaje(idRespuesta, respuesta.toString().ifEmpty { "(sin respuesta)" })
                avisar("Error al generar la respuesta: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // Si se interrumpió y ya hay otra respuesta en marcha, no hay que tocar su estado.
                if (trabajoRespuesta == coroutineContext[Job]) {
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
