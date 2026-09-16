package graph.core.contrato

import graph.core.contrato.Contrato006PreguntaAntes.Canal
import graph.core.contrato.Contrato006PreguntaAntes.Companion.CORREO_DE_ANA
import graph.core.contrato.Contrato006PreguntaAntes.Companion.LLEGO_TARDE
import graph.core.contrato.Contrato006PreguntaAntes.Companion.MANDA
import graph.core.contrato.Contrato006PreguntaAntes.Companion.MIRA
import graph.core.contrato.Contrato006PreguntaAntes.Companion.NUMERO
import graph.core.contrato.Contrato006PreguntaAntes.Companion.PASO_DEL_WORKFLOW
import graph.core.contrato.Contrato006PreguntaAntes.Companion.corrida
import graph.core.contrato.Contrato006PreguntaAntes.Companion.paso
import graph.core.contrato.Contrato006PreguntaAntes.Companion.pantallaCon
import graph.core.contrato.Contrato006PreguntaAntes.Companion.promesa
import graph.core.domain.AgentAction
import graph.core.domain.BrainTurn
import graph.core.pregunta.CompuertaDePregunta
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
     * QUIÉN ESCRIBIÓ EL PEDIDO (promesa 611). El objetivo que llega al motor puede llevar texto que redactó el modelo —la
     * acción anticipada autónoma y el `CONTEXTO INMEDIATO` de una propuesta—, y la compuerta lo leía como si fuera el
     * permiso de la persona. Se juzga con las dos cosas: el comportamiento, y que la app marque de verdad el origen.
     */
    @Test
    fun promesa611() = corre {
        val p = 611
        val fin = BrainTurn(done = true, text = "fin")
        val accion = AgentAction.Mcp("send_email", mapOf("to" to CORREO_DE_ANA, "body" to LLEGO_TARDE))

        // Lo que la persona dictó autoriza: es el caso de siempre, y tiene que seguir pasando sin preguntar.
        val suyo = corrida(MANDA, BrainTurn(actions = listOf(accion)), fin)
        assertEquals(listOf("sendEmail"), suyo.entradas, promesa(p) + " · no hizo lo que la persona le pidió")
        assertEquals(emptyList(), suyo.preguntas, promesa(p) + " · preguntó por lo que la persona sí pidió")

        // La acción anticipada autónoma: el objetivo lo redacta el modelo (`GraphApp.anticipate`) y no autoriza nada.
        val autonoma = corrida(
            MANDA,
            BrainTurn(actions = listOf(accion)),
            fin,
            objetivo = "ACCIÓN PREVENTIVA AUTÓNOMA (el usuario no la pidió explícito pero es de certeza total y le " +
                "conviene): $MANDA. Hazla de forma directa y para.",
            dijoLaPersona = null,
        )
        assertEquals(emptyList(), autonoma.entradas, promesa(p) + " · el objetivo que escribió el modelo se autorizó a sí mismo")
        assertEquals(1, autonoma.preguntas.size, promesa(p) + " · no preguntó por lo que nadie le pidió: ${autonoma.preguntas}")

        // Peor: la propuesta que la persona RECHAZÓ seguía autorizando, porque el contexto pendiente lleva la frase.
        val rechazada = corrida(
            "no, déjalo",
            BrainTurn(actions = listOf(accion)),
            fin,
            objetivo = "no, déjalo\n\nCONTEXTO INMEDIATO: hace un momento le PROPUSISTE por voz al usuario: " +
                "«¿Le mando el mensaje a Ana?» (la tarea que harías, en la app Mensajes: «$MANDA»).",
            dijoLaPersona = "no, déjalo",
        )
        assertEquals(emptyList(), rechazada.entradas, promesa(p) + " · una propuesta rechazada autorizó la acción")
        assertEquals(1, rechazada.preguntas.size, promesa(p) + " · no preguntó: ${rechazada.preguntas}")

        // Y la app marca el origen en sus DOS llamadas al motor: sin eso, el core no tiene con qué distinguirlo.
        val graphApp = fuenteDeLaApp("GraphApp.kt")
        assertEquals(2, Regex("""\.run\(goal[^)]*dijoLaPersona""").findAll(graphApp).count(),
            promesa(p) + " · GraphApp no marca el origen del pedido en sus dos llamadas a run")
        assertTrue("dijoLaPersona = null" in graphApp,
            promesa(p) + " · la acción anticipada autónoma no dice que no la pidió nadie")
    }

    /**
     * UN PASO CONSCIENTE NO SE AUTORIZA A SÍ MISMO (promesa 615). El objetivo de un paso lo arma el workflow con el nombre,
     * la descripción y la acción del paso (`GraphApp.consciousStep`), así que nombra la acción sensible que va a hacer: leído
     * como pedido, se daba el permiso a sí mismo. Es el mismo defecto que la 611 cerró en las otras dos vías de correr, y hoy
     * está inerte solo porque el subconsciente está apagado. Se juzga con el comportamiento y con el cable de la app.
     */
    @Test
    fun promesa615() = corre {
        val p = 615
        val fin = BrainTurn(done = true, text = "fin")
        val accion = AgentAction.Mcp("send_email", mapOf("to" to CORREO_DE_ANA, "body" to LLEGO_TARDE))

        // El objetivo del paso nombra la acción, el destinatario y el contenido: y aun así no lo pidió nadie.
        val delWorkflow = paso(PASO_DEL_WORKFLOW, BrainTurn(actions = listOf(accion)), fin)
        assertEquals(emptyList(), delWorkflow.entradas, promesa(p) + " · el paso consciente se autorizó con el objetivo que escribió el workflow")
        assertEquals(1, delWorkflow.preguntas.size, promesa(p) + " · no preguntó por lo que nadie le pidió: ${delWorkflow.preguntas}")

        // Y lo que la persona sí pidió sigue pasando, aunque el objetivo del paso sea otro texto.
        val suyo = paso(PASO_DEL_WORKFLOW, BrainTurn(actions = listOf(accion)), fin, dijoLaPersona = MANDA)
        assertEquals(listOf("sendEmail"), suyo.entradas, promesa(p) + " · no hizo el paso que la persona sí había pedido")
        assertEquals(emptyList(), suyo.preguntas, promesa(p) + " · preguntó por lo que la persona pidió")

        // Y la app lo pasa de verdad: sin ese cable, el core no tiene con qué distinguirlo.
        val graphApp = fuenteDeLaApp("GraphApp.kt")
        assertTrue(Regex("""Ejecucion\.pasoConsciente\([^)]*dichoPorLaPersona\(\)""").containsMatchIn(graphApp),
            promesa(p) + " · consciousStep no le pasa al paso lo que dijo la persona")
        val ejecucion = fuenteDeLaApp("Ejecucion.kt")
        assertTrue(Regex("""armado\.pasoConsciente\([^)]*dijoLaPersona""").containsMatchIn(ejecucion),
            promesa(p) + " · Ejecucion no reenvía al armado lo que dijo la persona")
    }

    /**
     * UNA DUDA NO PUEDE TRABAR LA APP (promesa 613). El diálogo de la pantalla era `setCancelable(false)` y vivía atado al
     * Activity: girar el teléfono con la duda puesta dejaba la corrida viva para siempre y todo lo demás contestando «ya hay
     * una tarea en curso». Se juzga el comportamiento del núcleo y, por fuente, que la pantalla deje cerrarla y la suelte.
     */
    @Test
    fun promesa613() = corre {
        val p = 613
        val fin = BrainTurn(done = true, text = "fin")
        val sensible = AgentAction.Mcp("send_sms", mapOf("number" to NUMERO, "message" to LLEGO_TARDE))

        // El canal se fue con la pantalla: lo sensible no se hace, y la corrida TERMINA en vez de quedarse colgada.
        val sinPantalla = corrida(
            MIRA,
            BrainTurn(actions = listOf(sensible, AgentAction.Tap(1, 1))),
            fin,
            canal = Canal(revienta = true),
        )
        assertEquals(listOf("tap"), sinPantalla.entradas, promesa(p) + " · mandó el mensaje con la duda sin canal")
        assertTrue(sinPantalla.salida.isSuccess, promesa(p) + " · la corrida reventó o quedó colgada: ${sinPantalla.salida}")
        assertEquals(2, sinPantalla.cerebro.recibidos.size, promesa(p) + " · no pidió el turno siguiente")
        val resultado = sinPantalla.resultados(1).first()
        assertTrue(resultado.startsWith(CompuertaDePregunta.SIN_CANAL), promesa(p) + " · el cerebro no se enteró: $resultado")

        // Cerrar la duda es contestar «»: cuenta como no, y lo sensible no se ejecuta.
        val cerrada = corrida(MIRA, BrainTurn(actions = listOf(sensible)), fin, canal = Canal(""))
        assertEquals(emptyList(), cerrada.entradas, promesa(p) + " · cerrar la duda dejó pasar la acción")
        assertEquals(1, cerrada.preguntas.size, promesa(p) + " · ${cerrada.preguntas}")

        // Y la pantalla de la app: la duda se puede cerrar, cerrarla contesta «», y la pantalla que muere la suelta.
        val ask = bloqueDeAsk(fuenteDeLaApp("MainActivity.kt"))
        assertTrue("setCancelable(false)" !in ask, promesa(p) + " · la duda vuelve a ser imposible de cerrar")
        assertTrue("setOnCancelListener" in ask, promesa(p) + " · cerrar la duda no contesta nada: la corrida se queda esperando")
        assertTrue(DUDAS in ask, promesa(p) + " · la duda no queda anotada, así que nadie puede soltarla si la pantalla muere")
        val pantalla = fuenteDeLaApp("MainActivity.kt")
        assertTrue(Regex("""onDestroy\(\)[\s\S]{0,600}""" + DUDAS).containsMatchIn(pantalla),
            promesa(p) + " · la pantalla que muere deja la duda en el aire y la corrida colgada")

        // Ningún plazo la resuelve por su cuenta: eso lo prohíbe la 604, y vale para las dos vías de preguntar.
        for (nombre in listOf("MainActivity.kt", "FloatingBubble.kt")) {
            val bloque = bloqueDeAsk(fuenteDeLaApp(nombre))
            assertTrue("withTimeout" !in bloque, promesa(p) + " · «$nombre» resuelve la duda con un plazo (prohibido por la 604)")
        }
    }

    /** Dónde la pantalla anota las dudas en el aire para poder contestarlas si se muere (promesa 613). */
    private val DUDAS = "dudasEnElAire"

    /** El `ask` de un canal de la app, hasta el separador de sección siguiente: se juzga ESE bloque, no el archivo entero. */
    private fun bloqueDeAsk(fuente: String): String {
        val desde = fuente.indexOf("override suspend fun ask(")
        if (desde < 0) fail("no encuentro el «ask» del canal en la fuente")
        val hasta = fuente.indexOf("\n    /* ----------", desde)
        return fuente.substring(desde, if (hasta > desde) hasta else fuente.length)
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
