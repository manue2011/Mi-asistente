package com.example.miasistente.acciones

import com.example.miasistente.contexto.GestorContexto.Companion.normalizar

/**
 * Elige el elemento cuyo nombre encaja mejor con lo dicho, priorizando palabras
 * completas: "mamá" encuentra "Mamá" o "Mi mamá" antes que "Mamadu".
 *
 * Orden: nombre exacto › palabras completas › empieza por › contiene. A igualdad,
 * gana el primero de la lista (por ejemplo, el chat más reciente).
 */
object BuscadorNombres {

    fun <T> mejor(elementos: List<T>, dicho: String, nombre: (T) -> String): T? {
        val buscado = normalizar(dicho).trim()
        if (buscado.isEmpty()) return null
        val palabraCompleta = Regex("""(^|\W)${Regex.escape(buscado)}(\W|$)""")
        val palabras = buscado.split(' ').filter { it.length > 2 }

        return elementos.minByOrNull { e ->
            val n = normalizar(nombre(e))
            when {
                n == buscado -> 0
                palabraCompleta.containsMatchIn(n) -> 1
                n.startsWith(buscado) -> 2
                n.contains(buscado) -> 3
                palabras.isNotEmpty() && palabras.all { n.contains(it) } -> 4
                palabras.any { n.contains(it) } -> 5
                else -> Int.MAX_VALUE
            }
        }?.takeIf { e ->
            val n = normalizar(nombre(e))
            n.contains(buscado) || palabras.any { n.contains(it) }
        }
    }
}
