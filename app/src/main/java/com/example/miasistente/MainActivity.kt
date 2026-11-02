package com.example.miasistente

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.miasistente.ui.PantallaAsistente
import com.example.miasistente.ui.TemaAsistente

class MainActivity : ComponentActivity() {

    private val viewModel: AsistenteViewModel by viewModels()

    private val pedirMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) viewModel.alternar()
    }

    private val elegirModelo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importarModelo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaAsistente {
                val estado = viewModel.ui.collectAsStateWithLifecycle().value
                PantallaAsistente(
                    estado = estado,
                    alTocarOrbe = ::alTocarOrbe,
                    alNuevaConversacion = viewModel::nuevaConversacion,
                    alElegirModelo = { elegirModelo.launch(arrayOf("*/*")) },
                    alReintentarCarga = viewModel::cargarModelo,
                    alDescartarAviso = viewModel::descartarAviso,
                )
            }
        }
    }

    private fun alTocarOrbe() {
        val necesitaPermiso = viewModel.ui.value.fase == Fase.INACTIVO &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (necesitaPermiso) pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO) else viewModel.alternar()
    }
}
