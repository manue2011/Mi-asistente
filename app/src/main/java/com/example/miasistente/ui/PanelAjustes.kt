package com.example.miasistente.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.miasistente.EstadoAjustes
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val FondoPanel = Color(0xFF0B1A2A)
private val Celeste = Color(0xFF29B6F6)
private val Tenue = Color(0xFF8FA7C0)
private val Correcto = Color(0xFF69F0AE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelAjustes(
    ajustes: EstadoAjustes,
    alCerrar: () -> Unit,
    alActivarNotificaciones: () -> Unit,
    alPermitirCalendario: () -> Unit,
    alCambiarResumen: (Boolean) -> Unit,
    alCambiarHora: (LocalTime) -> Unit,
    alEscucharResumen: () -> Unit,
    alCambiarModoReloj: (Boolean) -> Unit,
    alPermitirLlamadas: () -> Unit,
    alPermitirPantallas: () -> Unit,
) {
    var eligiendoHora by remember { mutableStateOf(false) }
    val separador = Color.White.copy(alpha = 0.08f)

    ModalBottomSheet(
        onDismissRequest = alCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = FondoPanel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Ajustes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Todo se procesa en el móvil. Nada sale del dispositivo.",
                color = Tenue,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            FilaPermiso(
                titulo = "Leer notificaciones",
                descripcion = "Pregúntame \"¿qué notificaciones tengo?\"",
                concedido = ajustes.accesoNotificaciones,
                textoBoton = "Activar",
                alPulsar = alActivarNotificaciones,
            )
            HorizontalDivider(color = separador)
            FilaPermiso(
                titulo = "Calendario",
                descripcion = "\"¿Qué tengo hoy?\" o \"apúntame dentista el jueves a las 5\"",
                concedido = ajustes.permisoCalendario,
                textoBoton = "Permitir",
                alPulsar = alPermitirCalendario,
            )
            HorizontalDivider(color = separador)
            FilaPermiso(
                titulo = "Llamadas y contactos",
                descripcion = "\"Llama a mamá\"",
                concedido = ajustes.permisoLlamadas,
                textoBoton = "Permitir",
                alPulsar = alPermitirLlamadas,
            )
            HorizontalDivider(color = separador)
            FilaPermiso(
                titulo = "Mostrar sobre otras apps",
                descripcion = "Para alarmas, temporizadores y abrir apps con el asistente cerrado",
                concedido = ajustes.permisoPantallas,
                textoBoton = "Activar",
                alPulsar = alPermitirPantallas,
            )
            HorizontalDivider(color = separador)

            FilaInterruptor(
                titulo = "Resumen matutino",
                descripcion = "Cada día a las ${ajustes.horaResumen.format(FORMATO_HORA)} te leo tu agenda, notificaciones " +
                    "y titulares de noticias, aunque el móvil esté bloqueado",
                activo = ajustes.resumenActivo,
                alCambiar = alCambiarResumen,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { eligiendoHora = true }) { Text("Cambiar hora", color = Celeste) }
                Button(
                    onClick = alEscucharResumen,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1), contentColor = Color.White),
                ) { Text("Escuchar resumen ahora") }
            }
            HorizontalDivider(color = separador, modifier = Modifier.padding(top = 8.dp))

            FilaInterruptor(
                titulo = "Modo reloj",
                descripcion = "Pulsa ▶ en los controles de música del reloj (o de los auriculares) para hablar, " +
                    "aunque la app esté cerrada o el móvil bloqueado. Deja una notificación fija.",
                activo = ajustes.modoReloj,
                alCambiar = alCambiarModoReloj,
            )
        }
    }

    if (eligiendoHora) {
        val estadoHora = rememberTimePickerState(
            initialHour = ajustes.horaResumen.hour,
            initialMinute = ajustes.horaResumen.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { eligiendoHora = false },
            confirmButton = {
                TextButton(onClick = {
                    alCambiarHora(LocalTime.of(estadoHora.hour, estadoHora.minute))
                    eligiendoHora = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { eligiendoHora = false }) { Text("Cancelar") } },
            text = { TimePicker(state = estadoHora) },
        )
    }
}

@Composable
private fun FilaInterruptor(titulo: String, descripcion: String, activo: Boolean, alCambiar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Color.White, fontSize = 16.sp)
            Text(descripcion, color = Tenue, fontSize = 12.sp)
        }
        Switch(
            checked = activo,
            onCheckedChange = alCambiar,
            colors = SwitchDefaults.colors(checkedTrackColor = Celeste),
        )
    }
}

@Composable
private fun FilaPermiso(
    titulo: String,
    descripcion: String,
    concedido: Boolean,
    textoBoton: String,
    alPulsar: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Color.White, fontSize = 16.sp)
            Text(descripcion, color = Tenue, fontSize = 12.sp)
        }
        if (concedido) {
            Text("Activado", color = Correcto, fontSize = 13.sp)
        } else {
            TextButton(onClick = alPulsar) { Text(textoBoton, color = Celeste) }
        }
    }
}

private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")
