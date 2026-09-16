package graph.core.contrato

import graph.core.contrato.Contrato006PreguntaAntes.Canal
import graph.core.contrato.Contrato006PreguntaAntes.Companion.corrida
import graph.core.contrato.Contrato006PreguntaAntes.Companion.pantallaCon
import graph.core.contrato.Contrato006PreguntaAntes.Companion.promesa
import graph.core.domain.AgentAction
import graph.core.domain.BrainTurn
import graph.core.pregunta.Vista
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CONTRATO 006 — LO QUE EL CLIENTE VE (docs/specs/006-pregunta-antes-de-ejecutar.md, promesa 602).
 *
 * Preguntar «¿cuál?» solo vale si las opciones son las que el cliente VIO: una lista inventada es peor que no preguntar.
 * Por eso esta promesa juzga dos cosas a la vez: el comportamiento (con el armado de verdad) y el formato del que salen las
 * etiquetas, leyendo las fuentes de la app —en jvm, porque solo jvm lee disco—. Si alguien cambia cómo `uiContext` escribe
 * lo que se ve, el cliente deja de ver candidatos en silencio, y eso es justo lo que esta promesa atrapa.
 */
class Contrato006LoQueVe {

    @Test
    fun promesa602() = corre {
        val p = 602
        val apps = listOf("Bancolombia", "Banco de Bogotá", "Nequi")

        // Varias apps coinciden con el nombre: pregunta con ESAS, en el orden en que las vio, y sin las que no coinciden.
        val ambigua = corrida(
            "abre el banco",
            BrainTurn(actions = listOf(AgentAction.Mcp("launch_app", mapOf("app" to "Banco")))),
            BrainTurn(done = true, text = "fin"),
            canal = Canal("Bancolombia"),
            apps = apps,
        )
        val pregunta = ambigua.preguntas.singleOrNull() ?: fail(promesa(p) + " · no preguntó cuál: ${ambigua.preguntas}")
        assertEquals(emptyList(), ambigua.entradas, promesa(p) + " · abrió una app sin saber cuál")
        assertEquals(1, ambigua.preguntasDeClase("cual"), promesa(p) + " · ${ambigua.diario.lineas}")
        assertTrue("Bancolombia" in pregunta && "Banco de Bogotá" in pregunta, promesa(p) + " · no ofreció lo que vio: $pregunta")
        assertTrue("Nequi" !in pregunta, promesa(p) + " · ofreció una opción que no coincide: $pregunta")
        assertTrue(pregunta.indexOf("Bancolombia") < pregunta.indexOf("Banco de Bogotá"), promesa(p) + " · no las ofreció en el orden que las vio: $pregunta")

        // Un solo candidato, o uno igual a lo pedido: no hay nada que elegir.
        for (nombre in listOf("Nequi", "Bancolombia")) {
            val clara = corrida(
                "abre $nombre",
                BrainTurn(actions = listOf(AgentAction.Mcp("launch_app", mapOf("app" to nombre)))),
                BrainTurn(done = true, text = "fin"),
                apps = apps,
            )
            assertEquals(emptyList(), clara.preguntas, promesa(p) + " · preguntó por «$nombre», que no es ambiguo")
            assertEquals(listOf("sistema.openApp"), clara.entradas, promesa(p) + " · no abrió «$nombre»")
        }

        // Y con las etiquetas de la pantalla: dos parecidas se preguntan, una que es la de siempre no.
        val chats = corrida(
            "abre el chat",
            BrainTurn(actions = listOf(AgentAction.Mcp("contactos", mapOf("taps" to "Juan")))),
            BrainTurn(done = true, text = "fin"),
            canal = Canal("Juan Pérez"),
            pantalla = pantallaCon("Juan Pérez", "Juan Carlos", "Archivar"),
        )
        val cual = chats.preguntas.singleOrNull() ?: fail(promesa(p) + " · no preguntó cuál chat: ${chats.preguntas}")
        assertEquals(emptyList(), chats.entradas, promesa(p) + " · tocó un chat sin saber cuál")
        assertTrue("Juan Pérez" in cual && "Juan Carlos" in cual, promesa(p) + " · no ofreció los chats que vio: $cual")
        assertTrue("Archivar" !in cual, promesa(p) + " · ofreció una etiqueta que no coincide: $cual")

        val unica = corrida(
            "archiva",
            BrainTurn(actions = listOf(AgentAction.Mcp("contactos", mapOf("taps" to "Archivar")))),
            BrainTurn(done = true, text = "fin"),
            pantalla = pantallaCon("Juan Pérez", "Juan Carlos", "Archivar"),
        )
        assertEquals(emptyList(), unica.preguntas, promesa(p) + " · preguntó por una etiqueta que no es ambigua")
        assertEquals(listOf("tapLabel"), unica.entradas, promesa(p) + " · no tocó la etiqueta que sí era clara")

        // El formato del que salen esas etiquetas es el que la app escribe de verdad.
        val servicio = fuenteDeLaApp("GraphAccessibilityService.kt")
        assertTrue(Vista.ETIQUETAS in servicio, promesa(p) + " · la app ya no escribe «${Vista.ETIQUETAS}»: el cliente no vería nada")
        assertTrue("joinToString(\"${Vista.SEPARADOR}\")" in servicio, promesa(p) + " · la app ya no une las etiquetas con «${Vista.SEPARADOR}»")
        assertEquals(
            listOf("Juan Pérez", "Juan Carlos", "Archivar"),
            Vista.etiquetasDe(pantallaCon("Juan Pérez", "Juan Carlos", "Archivar")),
            promesa(p) + " · el cliente no lee las etiquetas del formato de la app",
        )
        assertEquals(emptyList(), Vista.etiquetasDe("sin contenido accesible (pantalla vacía o protegida)"), promesa(p) + " · sin etiquetas no se inventa ninguna")
    }

    /**
     * `:core:jvmTest` corre con el directorio de trabajo en `core/`, pero no se supone: se sube desde donde esté hasta
     * encontrar `app/src/main/kotlin`. Si el archivo no aparece, la promesa falla: una lectura de cero archivos no da verde.
     */
    private fun fuenteDeLaApp(nombre: String): String {
        val desde = File("").absoluteFile
        val raiz = generateSequence(desde) { it.parentFile }
            .map { File(it, "app/src/main/kotlin") }
            .firstOrNull { it.isDirectory }
            ?: fail("no encuentro app/src/main/kotlin subiendo desde $desde")
        val archivo = raiz.walkTopDown().firstOrNull { it.isFile && it.name == nombre }
            ?: fail("no encuentro $nombre en $raiz")
        return archivo.readText()
    }
}
