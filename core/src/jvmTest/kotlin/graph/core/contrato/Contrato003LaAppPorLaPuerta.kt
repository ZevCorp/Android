package graph.core.contrato

import graph.core.contrato.Contrato003FrenoYPuerta.Bitacora
import graph.core.contrato.Contrato003FrenoYPuerta.CerebroGuionado
import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.contrato.Contrato003FrenoYPuerta.Voz
import graph.core.domain.AgentAction
import graph.core.domain.Brain
import graph.core.domain.BrainTurn
import graph.core.domain.LearnedTool
import graph.core.domain.ScreenState
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.Freno
import graph.core.precision.Paraste
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * CONTRATO 003, FASE 3B — LA APP SOLO EJECUTA POR LA PUERTA (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md).
 *
 * La lección de U, otra vez: su freno era correcto como clase y nadie lo cableaba. Una clase bien probada
 * no dice nada del cableado, así que estas dos promesas leen las FUENTES de la app (en jvm, porque solo
 * jvm lee disco) y además juzgan el [ArmadoDeEjecucion] real que la app usa.
 */
class Contrato003LaAppPorLaPuerta {

    companion object {
        val PROMESAS = mapOf(
            307 to "El motor y el MCP solo se arman sobre la puerta: ningún archivo de la app construye un ExecutionEngine o un Mcp, ni entrega el servicio de accesibilidad crudo como manos.",
            308 to "La píldora, la notificación y cualquier otra orden de parar usan el mismo alto: frenan la corrida en curso por la misma puerta.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** El único archivo de la app que entrega las manos crudas, y las entrega al armado. */
        const val EJECUCION = "Ejecucion.kt"
    }

    /* ---------- Las fuentes de la app ---------- */

    private class Fuente(val nombre: String, val ruta: String, val codigo: String) {
        /** Las líneas (1-based) donde aparece [patron], para decir dónde está lo que no debe estar. */
        fun donde(patron: Regex): List<String> =
            codigo.lines().mapIndexedNotNull { i, l -> if (patron.containsMatchIn(l)) "$ruta:${i + 1}: ${l.trim()}" else null }
    }

    /**
     * `:core:jvmTest` corre con el directorio de trabajo en `core/`, pero no se supone: se sube desde donde
     * esté hasta encontrar `app/src/main/kotlin`. Si no aparece, la promesa falla: una lectura de cero
     * archivos no puede dar verde.
     */
    private fun fuentesDeLaApp(): List<Fuente> {
        val desde = File("").absoluteFile
        val raiz = generateSequence(desde) { it.parentFile }
            .map { File(it, "app/src/main/kotlin") }
            .firstOrNull { it.isDirectory }
            ?: fail("no encuentro app/src/main/kotlin subiendo desde $desde")
        val fuentes = raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { Fuente(it.name, it.relativeTo(raiz).path, sinComentarios(it.readText())) }
            .toList()
        for (esperado in listOf("GraphApp.kt", "StopReceiver.kt", "FloatingBubble.kt", EJECUCION))
            if (fuentes.none { it.nombre == esperado }) fail("no encuentro $esperado en $raiz: ${fuentes.map { it.nombre }}")
        return fuentes
    }

    /** Quita comentarios conservando las líneas: un KDoc que nombra `Mcp(` no construye nada. */
    private fun sinComentarios(s: String): String =
        s.replace(Regex("""/\*[\s\S]*?\*/""")) { m -> "\n".repeat(m.value.count { it == '\n' }) }
            .lines().joinToString("\n") { it.replace(Regex("""(?<!:)//.*$"""), "") }

    private fun List<Fuente>.uno(nombre: String) = single { it.nombre == nombre }

    private fun manos(mano: Mano) = ArmadoDeEjecucion.Manos(mano.telefono, mano.gestos, mano.sistema, mano.reproductor)

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa307() = corre {
        val fuentes = fuentesDeLaApp()

        // Nadie en la app construye un ejecutor: ni el motor, ni el MCP, ni el reproductor, ni otra puerta.
        val construye = Regex("""(?<![\w.])(ExecutionEngine|Mcp|WorkflowRunner|Puerta)\s*\(""")
        val construidos = fuentes.flatMap { it.donde(construye) }
        assertEquals(emptyList(), construidos, promesa(307) + " · se arma un ejecutor fuera de ArmadoDeEjecucion")

        // Las manos crudas (servicio + sistema) solo se entregan en Ejecucion.kt, y ahí al armado.
        val entregaManos = Regex("""(?<!class )\b(ArmadoDeEjecucion|Manos|AndroidSystemApi)\s*\(""")
        val fuera = fuentes.filter { it.nombre != EJECUCION }.flatMap { it.donde(entregaManos) }
        assertEquals(emptyList(), fuera, promesa(307) + " · las manos crudas se entregan fuera de $EJECUCION")
        assertTrue(fuentes.uno(EJECUCION).donde(Regex("""\bArmadoDeEjecucion\s*\(""")).isNotEmpty(),
            promesa(307) + " · $EJECUCION no arma nada con ArmadoDeEjecucion")

        // Y nadie pasa el servicio de accesibilidad como teléfono, gestos o reproductor por nombre.
        val servicioComoManos = Regex("""\b(phone|gestures|player|system)\s*=\s*(service|ui|this)\b""")
        val pasados = fuentes.flatMap { it.donde(servicioComoManos) }
        assertEquals(emptyList(), pasados, promesa(307) + " · el servicio crudo se entrega como manos")

        // El armado real, con manos falsas y sin tarea abierta: ni el motor, ni una herramienta MCP (gesto,
        // sistema, aprendida), ni el reproductor de workflows llegan a las manos.
        val bitacora = Bitacora()
        val freno = Freno(log = bitacora)
        val armado = ArmadoDeEjecucion(freno, bitacora)
        val mano = Mano()
        val aprendidas = listOf(LearnedTool("calc", "calculadora", listOf("5")))
        val workflows = ArmadoDeEjecucion.Workflows(
            lista = listOf(Workflow("guardar", "toca guardar", listOf(WorkflowStep("toca Guardar", target = "Guardar", subconscious = true)))),
            elementos = { listOf("Guardar") },
            consciente = { _, _, _ -> error("este workflow no tiene pasos conscientes") },
        )
        fun guion() = CerebroGuionado(
            BrainTurn(actions = listOf(
                AgentAction.Tap(1, 1),
                AgentAction.OpenApp("Ajustes"),
                AgentAction.Mcp("go_home", emptyMap()),
                AgentAction.Mcp("set_alarm", mapOf("hour" to "7")),
                AgentAction.Mcp("calc", mapOf("taps" to "5")),
                AgentAction.Mcp("workflow_guardar", mapOf("context" to "")),
            )),
            BrainTurn(done = true, text = "fin"),
        )
        armado.arma(manos(mano), { guion() }, Voz(), pausa = { 0 }, aprendidas = aprendidas, workflows = workflows).motor.run("sin tarea")
        assertEquals(emptyList(), mano.entradas, promesa(307) + " · el armado tocó las manos sin tarea abierta")
        assertTrue(bitacora.lineas.count { it.startsWith("puerta: sin tarea abierta, no paso «") } >= 6,
            promesa(307) + " · alguna acción no pasó por la puerta: ${bitacora.lineas}")
        val catalogo = armado.herramientas(manos(mano), aprendidas)
        assertTrue(catalogo.any { it.name == "calc" }, promesa(307) + " · el catálogo no trae las aprendidas")
        assertFalse(catalogo.first { it.name == "go_home" }.run(emptyMap()), promesa(307) + " · una herramienta del catálogo no pasó por la puerta")
        assertEquals(emptyList(), mano.entradas, promesa(307) + " · el catálogo tocó las manos sin tarea abierta")

        // Con la tarea abierta las mismas acciones llegan, cada una por su vista: la fábrica no está rota.
        armado.correr("con tarea") {
            armado.arma(manos(mano), { guion() }, Voz(), pausa = { 0 }, aprendidas = aprendidas, workflows = workflows).motor.run("con tarea")
        }
        assertEquals(listOf("tap", "telefono.openApp", "home", "setAlarm", "tapLabel", "tapLabel"), mano.entradas, promesa(307))
    }

    @Test
    fun promesa308() = corre {
        val fuentes = fuentesDeLaApp()

        val notificacion = fuentes.uno("StopReceiver.kt")
        assertTrue(notificacion.donde(Regex("""\bEjecucion\.parar\("notificación"\)""")).isNotEmpty(),
            promesa(308) + " · la notificación no pide el alto con Ejecucion.parar(\"notificación\")")
        assertEquals(emptyList(), notificacion.donde(Regex("""stopExecution\s*\(|\.cancel\s*\(""")), promesa(308) + " · la notificación tiene otro camino")

        val pildora = fuentes.uno("FloatingBubble.kt")
        assertTrue(pildora.donde(Regex("""\bEjecucion\.parar\("píldora"\)""")).isNotEmpty(),
            promesa(308) + " · la píldora no pide el alto con Ejecucion.parar(\"píldora\")")
        assertEquals(emptyList(), pildora.donde(Regex("""stopExecution\s*\(""")), promesa(308) + " · la píldora tiene otro camino")

        val app = fuentes.uno("GraphApp.kt")
        val stop = Regex("""fun stopExecution\s*\([^)]*\)[^\n]*(\n[^\n]*){0,3}""").find(app.codigo)?.value
            ?: fail(promesa(308) + " · GraphApp ya no tiene stopExecution")
        assertTrue("Ejecucion.parar(\"botón\")" in stop, promesa(308) + " · stopExecution no pide el alto: $stop")
        assertFalse(".cancel(" in stop, promesa(308) + " · stopExecution cancela en vez de pedir el alto: $stop")

        // Nadie cancela el trabajo de la corrida por su cuenta: el único corte vive en el armado, tras el alto.
        val cortaSinAlto = Regex("""\brunJob\b[^\n]*\.cancel\s*\(|\.cancel\s*\(\s*Paraste\s*\(""")
        assertEquals(emptyList(), fuentes.flatMap { it.donde(cortaSinAlto) }, promesa(308) + " · se cancela la corrida sin pedir el alto")

        // Sin corrida, parar no arma nada ni hay trabajo que cortar.
        val bitacora = Bitacora()
        val avisos = mutableListOf<String>()
        val freno = Freno(log = bitacora, avisa = { avisos += it })
        val armado = ArmadoDeEjecucion(freno, bitacora)
        armado.parar("píldora")
        assertFalse(freno.pedido, promesa(308) + " · parar sin corrida armó el freno")
        assertFalse(armado.enCurso, promesa(308))

        // Dentro de la corrida, las tres órdenes van al mismo alto: arma el freno de ESA tarea y avisa una vez.
        var armoLaPildora = false
        var enCurso = false
        val salida = runCatching {
            armado.correr("abre ajustes") {
                enCurso = armado.enCurso
                armado.parar("píldora")
                armoLaPildora = freno.pedido && freno.tarea == "abre ajustes"
                armado.parar("notificación")
                armado.parar("voz")
            }
        }
        assertTrue(enCurso, promesa(308) + " · la corrida no se sabe en curso")
        assertTrue(armoLaPildora, promesa(308) + " · la píldora no armó el freno de la tarea abierta")
        assertIs<Paraste>(salida.exceptionOrNull(), promesa(308) + " · una corrida con alto no terminó como cancelación: $salida")
        assertEquals(listOf(Freno.ALTO, Freno.DEVUELVO_EL_CONTROL), avisos, promesa(308) + " · tres órdenes, avisos: $avisos")
        assertEquals(listOf("freno: alto pedido (píldora); paro «abre ajustes»"), bitacora.lineas.filter { "alto pedido" in it }, promesa(308))
        assertFalse(armado.enCurso, promesa(308) + " · la corrida acabó y sigue en curso")

        // Un turno de Graph colgado en red tras el alto: pasada la gracia se corta el trabajo y la corrida
        // termina como cancelación, sin tocar nada y soltando el freno.
        val pensando = CompletableDeferred<Unit>()
        val colgado = object : Brain {
            override fun begin(goal: String) {}
            override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
                pensando.complete(Unit)
                awaitCancellation()
            }
            override fun inform(message: String) {}
        }
        val mano = Mano()
        val sesion = armado.arma(manos(mano), { colgado }, Voz(), pausa = { 0 })
        var salioColgada: Throwable? = null
        val inicio = TimeSource.Monotonic.markNow()
        coroutineScope {
            val corrida = launch {
                salioColgada = runCatching { armado.correr("colgada") { sesion.motor.run("colgada") } }.exceptionOrNull()
            }
            pensando.await()
            armado.parar("notificación")
            armado.cortaSiNoSuelta(50)
            corrida.join()
        }
        val tardo = inicio.elapsedNow()
        assertIs<CancellationException>(salioColgada, promesa(308) + " · la corrida colgada no terminó como cancelación")
        assertTrue(tardo < 1.seconds, promesa(308) + " · cortar la corrida colgada tardó $tardo")
        assertEquals(emptyList(), mano.entradas, promesa(308))
        assertFalse(freno.abierta, promesa(308) + " · la corrida cortada no soltó el freno")
        assertTrue(bitacora.lineas.any { it.startsWith("freno:") && "corto" in it }, promesa(308) + " · el corte no quedó en el log: ${bitacora.lineas}")
    }
}
