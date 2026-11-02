package com.example.miasistente.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.miasistente.EstadoUi
import com.example.miasistente.Fase
import com.example.miasistente.MensajeChat
import com.example.miasistente.cerebro.ArchivoModelo
import com.example.miasistente.cerebro.EstadoCerebro
import com.example.miasistente.cerebro.Rol

private val Fondo = Color(0xFF02060D)
private val Acento = Color(0xFF29B6F6)
private val TextoTenue = Color(0xFF8FA7C0)

@Composable
fun PantallaAsistente(
    estado: EstadoUi,
    alTocarOrbe: () -> Unit,
    alNuevaConversacion: () -> Unit,
    alElegirModelo: () -> Unit,
    alReintentarCarga: () -> Unit,
    alDescartarAviso: () -> Unit,
    alAbrirAjustes: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF071A2E), Fondo), radius = 1400f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Cabecera(estado, alNuevaConversacion, alAbrirAjustes)

            Conversacion(
                mensajes = estado.mensajes,
                parcial = estado.transcripcionParcial,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            when (val cerebro = estado.cerebro) {
                is EstadoCerebro.SinModelo -> PanelSinModelo(cerebro.carpeta, estado.importando, alElegirModelo, alReintentarCarga)
                is EstadoCerebro.Error -> PanelError(cerebro.mensaje, alElegirModelo, alReintentarCarga)
                else -> Unit
            }

            estado.aviso?.let { Aviso(it, alDescartarAviso) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                OrbeHolografico(
                    fase = estado.fase,
                    nivelVoz = estado.nivelVoz,
                    alTocar = alTocarOrbe,
                    modifier = Modifier.size(280.dp),
                )
                if (estado.cerebro is EstadoCerebro.Cargando || estado.cerebro is EstadoCerebro.SinIniciar) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp)
                }
            }

            Text(
                text = textoEstado(estado),
                color = TextoTenue,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )
        }
    }
}

private fun textoEstado(estado: EstadoUi): String = when (estado.fase) {
    Fase.CARGANDO -> when (val c = estado.cerebro) {
        is EstadoCerebro.Cargando -> c.detalle
        is EstadoCerebro.SinModelo -> "Falta el modelo"
        is EstadoCerebro.Error -> "No se pudo cargar el modelo"
        else -> "Preparando el cerebro…"
    }
    Fase.INACTIVO -> "Toca el círculo para hablar"
    Fase.ESCUCHANDO -> "Te escucho…"
    Fase.PENSANDO -> "Pensando…"
    Fase.HABLANDO -> "Hablando · toca para interrumpir"
}

@Composable
private fun Cabecera(estado: EstadoUi, alNuevaConversacion: () -> Unit, alAbrirAjustes: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mi asistente", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val detalle = (estado.cerebro as? EstadoCerebro.Listo)?.descripcion ?: "100 % en el dispositivo"
            Text(detalle, color = TextoTenue, fontSize = 12.sp)
        }
        if (estado.mensajes.isNotEmpty()) {
            TextButton(onClick = alNuevaConversacion) { Text("Nueva", color = Acento) }
        }
        TextButton(onClick = alAbrirAjustes) { Text("Ajustes", color = Acento) }
    }
}

@Composable
private fun Conversacion(mensajes: List<MensajeChat>, parcial: String, modifier: Modifier) {
    val lista = rememberLazyListState()
    val total = mensajes.size + if (parcial.isNotEmpty()) 1 else 0
    LaunchedEffect(total, mensajes.lastOrNull()?.texto?.length) {
        if (total > 0) lista.animateScrollToItem(total - 1)
    }
    LazyColumn(
        state = lista,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(mensajes, key = { it.id }) { Burbuja(it.rol, it.texto.ifEmpty { "…" }, provisional = false) }
        if (parcial.isNotEmpty()) {
            item(key = "parcial") { Burbuja(Rol.USUARIO, parcial, provisional = true) }
        }
    }
}

@Composable
private fun Burbuja(rol: Rol, texto: String, provisional: Boolean) {
    val esUsuario = rol == Rol.USUARIO
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (esUsuario) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (esUsuario) 16.dp else 4.dp,
                bottomEnd = if (esUsuario) 4.dp else 16.dp,
            ),
            color = if (esUsuario) Color(0xFF0D47A1).copy(alpha = if (provisional) 0.4f else 0.75f) else Color(0xFF0B2236),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                texto,
                color = if (provisional) TextoTenue else Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PanelSinModelo(carpeta: String, importando: Float?, alElegirModelo: () -> Unit, alReintentar: () -> Unit) {
    Panel {
        Text("No encuentro el modelo de IA", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Copia ${ArchivoModelo.NOMBRE_RECOMENDADO} a:\n$carpeta\n\no elígelo desde el móvil (se copiará a esa carpeta).",
            color = TextoTenue,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (importando != null) {
            Text("Copiando modelo… ${(importando * 100).toInt()} %", color = TextoTenue, fontSize = 13.sp)
            LinearProgressIndicator(progress = { importando }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = alElegirModelo, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1), contentColor = Color.White)) {
                    Text("Elegir archivo")
                }
                TextButton(onClick = alReintentar) { Text("Ya lo he copiado", color = Acento) }
            }
        }
    }
}

@Composable
private fun PanelError(mensaje: String, alElegirModelo: () -> Unit, alReintentar: () -> Unit) {
    Panel {
        Text(mensaje, color = Color(0xFFFF8A80))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = alReintentar) { Text("Reintentar", color = Acento) }
            TextButton(onClick = alElegirModelo) { Text("Elegir otro modelo", color = Acento) }
        }
    }
}

@Composable
private fun Aviso(texto: String, alDescartar: () -> Unit) {
    Panel(color = Color(0xFF3E2723).copy(alpha = 0.85f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(texto, color = Color(0xFFFFE0B2), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = alDescartar) { Text("OK", color = Color(0xFFFFCC80)) }
        }
    }
}

@Composable
private fun Panel(color: Color = Color(0xFF0B2236), contenido: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) { contenido() }
    }
}

@Composable
fun TemaAsistente(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(primary = Acento, background = Fondo, surface = Fondo),
        content = contenido,
    )
}
