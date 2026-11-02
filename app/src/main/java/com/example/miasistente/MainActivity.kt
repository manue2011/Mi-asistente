package com.example.miasistente

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.miasistente.contexto.OyenteNotificaciones
import com.example.miasistente.reloj.ServicioReloj
import com.example.miasistente.rutina.ResumenMatutino
import com.example.miasistente.ui.PanelAjustes
import com.example.miasistente.ui.PantallaAsistente
import com.example.miasistente.ui.TemaAsistente

class MainActivity : ComponentActivity() {

    private val viewModel: Asistente by lazy { (application as MiAsistenteApp).asistente }

    private val pedirMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) viewModel.alternar()
    }

    private val pedirCalendario = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refrescarAjustes()
    }

    private val pedirAvisos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) viewModel.activarResumen(true)
    }

    private val pedirMicrofonoReloj = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) viewModel.activarModoReloj(true)
    }

    private val elegirModelo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importarModelo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) atenderIntent(intent)
        // Si el modo reloj estaba activo y el sistema cerró la app, se vuelve a arrancar ahora que está visible.
        if (ServicioReloj.activo(this) && tiene(Manifest.permission.RECORD_AUDIO)) ServicioReloj.iniciar(this)
        setContent {
            TemaAsistente {
                val estado = viewModel.ui.collectAsStateWithLifecycle().value
                val ajustes = viewModel.ajustes.collectAsStateWithLifecycle().value
                var mostrarAjustes by remember { mutableStateOf(false) }

                PantallaAsistente(
                    estado = estado,
                    alTocarOrbe = ::alTocarOrbe,
                    alNuevaConversacion = viewModel::nuevaConversacion,
                    alElegirModelo = { elegirModelo.launch(arrayOf("*/*")) },
                    alReintentarCarga = viewModel::cargarModelo,
                    alDescartarAviso = viewModel::descartarAviso,
                    alAbrirAjustes = {
                        viewModel.refrescarAjustes()
                        mostrarAjustes = true
                    },
                )
                if (mostrarAjustes) {
                    PanelAjustes(
                        ajustes = ajustes,
                        alCerrar = { mostrarAjustes = false },
                        alActivarNotificaciones = ::abrirAccesoNotificaciones,
                        alPermitirCalendario = { pedirCalendario.launch(Manifest.permission.READ_CALENDAR) },
                        alCambiarResumen = ::cambiarResumen,
                        alCambiarHora = viewModel::cambiarHoraResumen,
                        alEscucharResumen = {
                            mostrarAjustes = false
                            viewModel.pedirResumen()
                        },
                        alCambiarModoReloj = ::cambiarModoReloj,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refrescarAjustes()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        atenderIntent(intent)
    }

    /** Al abrir la app desde la notificación del resumen matutino, lo lee directamente. */
    private fun atenderIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ResumenMatutino.EXTRA_RESUMEN, false) == true) {
            intent.removeExtra(ResumenMatutino.EXTRA_RESUMEN)
            viewModel.pedirResumen()
        }
    }

    private fun alTocarOrbe() {
        val necesitaPermiso = viewModel.ui.value.fase == Fase.INACTIVO && !tiene(Manifest.permission.RECORD_AUDIO)
        if (necesitaPermiso) pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO) else viewModel.alternar()
    }

    /** El servicio del reloj usa el micrófono: hace falta el permiso antes de arrancarlo. */
    private fun cambiarModoReloj(activo: Boolean) {
        if (activo && !tiene(Manifest.permission.RECORD_AUDIO)) {
            pedirMicrofonoReloj.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.activarModoReloj(activo)
        }
    }

    private fun cambiarResumen(activo: Boolean) {
        if (activo && !tiene(Manifest.permission.POST_NOTIFICATIONS)) {
            pedirAvisos.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.activarResumen(activo)
        }
    }

    /** El acceso a notificaciones no es un permiso normal: se concede en los Ajustes del sistema. */
    private fun abrirAccesoNotificaciones() {
        val detalle = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this, OyenteNotificaciones::class.java).flattenToString(),
            )
        runCatching { startActivity(detalle) }
            .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun tiene(permiso: String) =
        ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED
}
