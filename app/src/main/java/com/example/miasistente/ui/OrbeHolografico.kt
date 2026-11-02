package com.example.miasistente.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.miasistente.Fase
import kotlin.math.PI
import kotlin.math.sin

private fun colorDe(fase: Fase): Color = when (fase) {
    Fase.CARGANDO -> Color(0xFF3D5AFE)
    Fase.INACTIVO -> Color(0xFF29B6F6)
    Fase.ESCUCHANDO -> Color(0xFF00E5FF)
    Fase.PENSANDO -> Color(0xFF7C4DFF)
    Fase.HABLANDO -> Color(0xFF40C4FF)
}

/**
 * Círculo animado estilo holograma. Es el botón principal: reacciona al
 * volumen de la voz mientras escucha, gira deprisa mientras piensa y emite
 * ondas mientras habla.
 */
@Composable
fun OrbeHolografico(
    fase: Fase,
    nivelVoz: Float,
    alTocar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(colorDe(fase), tween(600), label = "color")
    val nivel by animateFloatAsState(
        targetValue = if (fase == Fase.ESCUCHANDO) nivelVoz else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "nivel",
    )
    val velocidad by animateFloatAsState(
        targetValue = when (fase) {
            Fase.PENSANDO, Fase.CARGANDO -> 3.5f
            Fase.ESCUCHANDO, Fase.HABLANDO -> 1.6f
            Fase.INACTIVO -> 1f
        },
        animationSpec = tween(800),
        label = "velocidad",
    )
    val intensidadOndas by animateFloatAsState(
        targetValue = if (fase == Fase.HABLANDO) 1f else 0f,
        animationSpec = tween(500),
        label = "ondas",
    )

    val transicion = rememberInfiniteTransition(label = "orbe")
    val tiempo by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "tiempo",
    )
    val respiracion by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400), RepeatMode.Reverse),
        label = "respiracion",
    )
    val ondas by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "fase-ondas",
    )

    val descripcion = when (fase) {
        Fase.CARGANDO -> "Asistente cargando"
        Fase.INACTIVO -> "Tocar para hablar"
        Fase.ESCUCHANDO -> "Escuchando. Tocar para terminar"
        Fase.PENSANDO, Fase.HABLANDO -> "Tocar para interrumpir"
    }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = descripcion }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = alTocar,
            ),
    ) {
        val centro = center
        val radioBase = size.minDimension / 2f * 0.52f
        val escala = 1f + 0.04f * (respiracion - 0.5f) + 0.28f * nivel
        val radio = radioBase * escala
        val giro = tiempo * 360f * velocidad

        // Halo exterior.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.35f + 0.25f * nivel), Color.Transparent),
                center = centro,
                radius = radio * 1.9f,
            ),
            radius = radio * 1.9f,
        )

        // Ondas al hablar.
        if (intensidadOndas > 0.01f) {
            for (i in 0 until 3) {
                val p = (ondas + i / 3f) % 1f
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.5f * intensidadOndas),
                    radius = radio * (1f + 0.75f * p),
                    style = Stroke(width = 2.dp2px(this)),
                )
            }
        }

        // Núcleo.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.85f),
                0.25f to color.copy(alpha = 0.75f),
                0.75f to color.copy(alpha = 0.25f),
                1f to Color.Transparent,
                center = centro,
                radius = radio,
            ),
            radius = radio,
        )

        // Líneas de barrido tipo holograma, recortadas dentro del círculo.
        val circulo = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(centro, radio))
        }
        clipPath(circulo) {
            val separacion = 6.dp2px(this)
            val desplazamiento = (tiempo * 40f * velocidad * separacion) % separacion
            var y = centro.y - radio + desplazamiento
            while (y < centro.y + radio) {
                drawLine(
                    color = color.copy(alpha = 0.12f),
                    start = Offset(centro.x - radio, y),
                    end = Offset(centro.x + radio, y),
                    strokeWidth = 1f,
                )
                y += separacion
            }
        }

        // Anillos giratorios.
        anillo(color, centro, radio * 1.12f, giro, 0.9f, 3.dp2px(this), discontinuo = false, barrido = 250f)
        anillo(color, centro, radio * 1.26f, -giro * 1.4f, 0.6f, 1.5f.dp2px(this), discontinuo = true, barrido = 360f)
        anillo(color, centro, radio * 1.40f, giro * 0.7f, 0.4f, 2.dp2px(this), discontinuo = false, barrido = 90f)
        anillo(color, centro, radio * 1.40f, giro * 0.7f + 180f, 0.4f, 2.dp2px(this), discontinuo = false, barrido = 90f)

        // Pequeños destellos orbitando.
        for (i in 0 until 6) {
            val angulo = (giro * 0.8f + i * 60f) * PI.toFloat() / 180f
            val r = radio * (1.18f + 0.05f * sin(tiempo * 2f * PI.toFloat() * 3f + i))
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = 2.dp2px(this),
                center = Offset(centro.x + r * kotlin.math.cos(angulo), centro.y + r * sin(angulo)),
            )
        }
    }
}

private fun DrawScope.anillo(
    color: Color,
    centro: Offset,
    radio: Float,
    angulo: Float,
    alfa: Float,
    grosor: Float,
    discontinuo: Boolean,
    barrido: Float,
) {
    rotate(angulo, centro) {
        drawArc(
            color = color.copy(alpha = alfa),
            startAngle = 0f,
            sweepAngle = barrido,
            useCenter = false,
            topLeft = Offset(centro.x - radio, centro.y - radio),
            size = Size(radio * 2, radio * 2),
            style = Stroke(
                width = grosor,
                cap = StrokeCap.Round,
                pathEffect = if (discontinuo) PathEffect.dashPathEffect(floatArrayOf(grosor * 3, grosor * 5)) else null,
            ),
        )
    }
}

private fun Number.dp2px(scope: DrawScope): Float = toFloat() * scope.density
