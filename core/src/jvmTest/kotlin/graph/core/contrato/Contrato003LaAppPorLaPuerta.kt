package graph.core.contrato

import graph.core.contrato.Contrato003FrenoYPuerta.Bitacora
import graph.core.contrato.Contrato003FrenoYPuerta.CerebroGuionado
import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.contrato.Contrato003FrenoYPuerta.Voz
import graph.core.contrato.Contrato003TopeYCuenta.Telefono
import graph.core.domain.AgentAction
import graph.core.domain.Brain
import graph.core.domain.BrainTurn
import graph.core.domain.LearnedTool
import graph.core.domain.ScreenState
import graph.core.domain.Voice
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.CuentaDePeticion
import graph.core.precision.Freno
import graph.core.precision.Paraste
import graph.core.precision.TopeDeIntentos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * CONTRATO 003, FASE 3B — LA APP SOLO EJECUTA POR LA PUERTA (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md).
 *
 * La lección de U, otra vez: su freno era correcto como clase y nadie lo cableaba. Una clase bien probada
 * no dice nada del cableado, así que estas promesas leen las FUENTES de la app (en jvm, porque solo
 * jvm lee disco) y además juzgan el [ArmadoDeEjecucion] real que la app usa. La 320 hace lo mismo con el tope y la
 * cuenta de la 3C: el armado los comparte, y la app le da uno de cada por proceso.
 */
class Contrato003LaAppPorLaPuerta {

    companion object {
        val PROMESAS = mapOf(
            307 to "El motor y el MCP solo se arman sobre la puerta: ningún archivo de la app construye un ExecutionEngine o un Mcp, ni entrega el servicio de accesibilidad crudo como manos.",
            308 to "La píldora, la notificación y cualquier otra orden de parar usan el mismo alto: frenan la corrida en curso por la misma puerta.",
            320 to "Un paso consciente dentro de una corrida no devuelve el tope a cero ni abre otra petición; una corrida nueva de fuera sí.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** El único archivo de la app que entrega las manos crudas, y las entrega al armado. */
        const val EJECUCION = "Ejecucion.kt"

        /** La gracia tras el alto antes de cortar, escrita aquí y no leída de producción. */
        const val GRACIA = 1_500L

        /** Lo que narra el motor antes de la acción del guion de la 307: ahí se cierra la tarea. */
        const val CIERRA = "cierro la tarea"

        /** Los ejecutores que solo arma el [ArmadoDeEjecucion]. */
        const val EJECUTOR = "ExecutionEngine|Mcp|WorkflowRunner|Puerta"
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

    /** El cuerpo de la función que abre [firma], hasta su llave de cierre: la primera `}` con su misma sangría. */
    private fun cuerpo(codigo: String, firma: Regex): String {
        val lineas = codigo.lines()
        val i = lineas.indexOfFirst { firma.containsMatchIn(it) }
        if (i < 0) fail("no encuentro «${firma.pattern}»")
        val sangria = lineas[i].takeWhile { it == ' ' }
        val fin = (i + 1 until lineas.size).firstOrNull { lineas[it] == "$sangria}" } ?: fail("«${firma.pattern}» no cierra")
        return lineas.subList(i, fin + 1).joinToString("\n")
    }

    /** El bloque `{ … }` que abre la primera llave tras [inicio], con sus llaves anidadas; `null` si no está o no cierra. */
    private fun bloqueDe(codigo: String, inicio: String): String? {
        val desde = codigo.indexOf(inicio).takeIf { it >= 0 } ?: return null
        val abre = codigo.indexOf('{', desde).takeIf { it >= 0 } ?: return null
        var nivel = 0
        for (i in abre until codigo.length) {
            when (codigo[i]) {
                '{' -> nivel++
                '}' -> if (--nivel == 0) return codigo.substring(abre, i + 1)
            }
        }
        return null
    }

    private fun manos(mano: Mano) = ArmadoDeEjecucion.Manos(mano.telefono, mano.gestos, mano.sistema, mano.reproductor)

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa307() = corre {
        val fuentes = fuentesDeLaApp()

        // Nadie en la app construye un ejecutor: ni el motor, ni el MCP, ni el reproductor, ni otra puerta. Tampoco con
        // el nombre calificado (`graph.core.application.ExecutionEngine(`): lo único con punto delante que se llama
        // así y no es un ejecutor es la acción `AgentAction.Mcp(` que traen los cerebros.
        val construye = Regex("""(?<!\w)(?<!AgentAction\.)($EJECUTOR)\s*\(""")
        val construidos = fuentes.flatMap { it.donde(construye) }
        assertEquals(emptyList(), construidos, promesa(307) + " · se arma un ejecutor fuera de ArmadoDeEjecucion")

        // Ni tras un nombre que lo esconda: `typealias Motor = ExecutionEngine`, `import … ExecutionEngine as Motor`
        // o la referencia al constructor `::ExecutionEngine`.
        val escondido = Regex("""\btypealias\s+\w+(\s*<[^=]*>)?\s*=\s*([\w.]*\.)?($EJECUTOR)\b|\bimport\s+[\w.]*\b($EJECUTOR)\s+as\s+\w+|::\s*($EJECUTOR)\b""")
        assertEquals(emptyList(), fuentes.flatMap { it.donde(escondido) }, promesa(307) + " · un ejecutor se esconde tras un alias")

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

        // El armado real, con manos falsas. El motor mira la tarea antes de pedir cada turno y la puerta en cada
        // entrada; entre una y otra, el motor narra la intención de la acción, y ahí se cierra la tarea. Lo que
        // llegue a las manos después solo lo pudo frenar la puerta: ni el motor, ni una herramienta MCP (gesto,
        // sistema, aprendida), ni el reproductor de workflows llegan a las manos sin pasar por ella.
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
        fun guion(cierra: Boolean) = CerebroGuionado(
            BrainTurn(
                actions = listOf(
                    AgentAction.Tap(1, 1),
                    AgentAction.OpenApp("Ajustes"),
                    AgentAction.Mcp("go_home", emptyMap()),
                    AgentAction.Mcp("set_alarm", mapOf("hour" to "7")),
                    AgentAction.Mcp("calc", mapOf("taps" to "5")),
                    AgentAction.Mcp("workflow_guardar", mapOf("context" to "")),
                ),
                intents = if (cierra) listOf(CIERRA) else emptyList(),
            ),
            BrainTurn(done = true, text = "fin"),
        )
        val cierraLaTarea = object : Voice {
            override fun narrate(text: String) { if (text == CIERRA) freno.termine() }
            override fun speak(text: String) {}
        }
        armado.correr("se cierra a mitad") {
            armado.arma(manos(mano), { guion(cierra = true) }, cierraLaTarea, pausa = { 0 }, aprendidas = aprendidas, workflows = workflows).motor.run("se cierra a mitad")
        }
        assertEquals(emptyList(), mano.entradas, promesa(307) + " · el armado tocó las manos sin tarea abierta")
        assertTrue(bitacora.lineas.count { it.startsWith("puerta: sin tarea abierta, no paso «") } >= 6,
            promesa(307) + " · alguna acción no pasó por la puerta: ${bitacora.lineas}")
        val catalogo = armado.herramientas(manos(mano), aprendidas)
        assertTrue(catalogo.any { it.name == "calc" }, promesa(307) + " · el catálogo no trae las aprendidas")
        assertFalse(catalogo.first { it.name == "go_home" }.run(emptyMap()), promesa(307) + " · una herramienta del catálogo no pasó por la puerta")
        assertEquals(emptyList(), mano.entradas, promesa(307) + " · el catálogo tocó las manos sin tarea abierta")

        // Con la tarea abierta las mismas acciones llegan, cada una por su vista: la fábrica no está rota.
        armado.correr("con tarea") {
            armado.arma(manos(mano), { guion(cierra = false) }, Voz(), pausa = { 0 }, aprendidas = aprendidas, workflows = workflows).motor.run("con tarea")
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

        // La corrida de la app es la de ESE freno. Sin tarea abierta el alto no arma nada (302): una corrida que no
        // entrara en Ejecucion.correr no se podría parar. Y con el alto pedido, tras el motor no se reencamina ni se
        // anticipa: sería seguir, y pagar otra llamada al modelo, después de que la persona dijo basta. Todo eso
        // DENTRO del bloque de la corrida: anticipar después de soltar la tarea es anticipar sin alto posible.
        val run = cuerpo(app.codigo, Regex("""suspend fun run\s*\(prompt"""))
        val corrida = bloqueDe(run, "Ejecucion.correr(") ?: fail(promesa(308) + " · GraphApp.run no corre dentro de Ejecucion.correr")
        val motor = corrida.indexOf("engine.run(")
        assertTrue(motor >= 0, promesa(308) + " · el motor de GraphApp.run no corre dentro del bloque de Ejecucion.correr")
        val sigueDespues = listOf("round++", "anticipate(").associateWith { corrida.indexOf(it, motor) }
        assertTrue(sigueDespues.values.all { it > motor }, promesa(308) + " · reencaminar o anticipar ya no están dentro de la corrida tras el motor: $sigueDespues")
        val mira = corrida.indexOf("Ejecucion.sigue()", motor)
        assertTrue(mira > motor && sigueDespues.values.all { mira < it },
            promesa(308) + " · tras el motor GraphApp.run reencamina o anticipa sin mirar el alto con Ejecucion.sigue()")

        // El paso consciente de un workflow corre con Ejecucion.pasoConsciente: parado dentro, no se da por hecho (316).
        val consciente = cuerpo(app.codigo, Regex("""suspend fun consciousStep\s*\("""))
        assertTrue(Regex("""\breturn\s+Ejecucion\.pasoConsciente\(""").containsMatchIn(consciente),
            promesa(308) + " · consciousStep no devuelve Ejecucion.pasoConsciente: $consciente")
        assertFalse(Regex("""\.run\s*\(""").containsMatchIn(consciente), promesa(308) + " · consciousStep corre un motor por su cuenta: $consciente")
        assertTrue("consciousStep(" in cuerpo(app.codigo, Regex("""fun newSession\s*\(""")), promesa(308) + " · los workflows ya no dan sus pasos conscientes con consciousStep")

        // Tras pensar la propuesta tampoco se sigue con el alto pedido: la anticipación tarda (Gemini reintenta) y un alto que
        // cae ahí no puede acabar en una propuesta dicha y pendiente. La sentencia siguiente a `anticipation.consider(` es
        // `Ejecucion.sigue()` sola y a su misma sangría: ni dentro de un `runCatching` que se trague la parada, ni tras un
        // `if`, ni con otro nombre delante.
        val anticipa = cuerpo(app.codigo, Regex("""private suspend fun anticipate\s*\(""")).lines()
        val piensa = anticipa.indexOfFirst { "anticipation.consider(" in it }
        assertTrue(piensa >= 0, promesa(308) + " · anticipate ya no piensa con anticipation.consider")
        val trasPensar = anticipa.drop(piensa + 1).firstOrNull { it.isNotBlank() }?.trimEnd()
        assertEquals(anticipa[piensa].takeWhile { it == ' ' } + "Ejecucion.sigue()", trasPensar,
            promesa(308) + " · tras anticipation.consider se propone sin mirar antes el alto con Ejecucion.sigue()")
        // Y `Ejecucion` es el objeto de Ejecucion.kt: nada en la app se declara con ese nombre ni lo trae de otro sitio o con alias.
        val otroEjecucion = Regex("""\bimport\s+[\w.]+\s+as\s+Ejecucion\b|\btypealias\s+Ejecucion\b|\bimport\s+(?!com\.zevcorp\.graph\.Ejecucion\b)[\w.]*\.Ejecucion\b|\b(object|class|interface|val|var)\s+Ejecucion\b|\bfun\s+Ejecucion\s*\(|[(,]\s*Ejecucion\s*:""")
        val sombras = fuentes.flatMap { f -> f.donde(otroEjecucion).filterNot { f.nombre == EJECUCION && it.endsWith(": object Ejecucion {") } }
        assertEquals(emptyList(), sombras, promesa(308) + " · algo en la app se llama Ejecucion sin ser el objeto de $EJECUCION")

        // Ejecucion.parar es el alto del armado, y nada más: el corte tras la gracia es del armado (se juzga abajo por
        // comportamiento), y la app solo le da con qué lanzarlo, sin cambiarle la gracia.
        val ejecucion = fuentes.uno(EJECUCION)
        val parar = cuerpo(ejecucion.codigo, Regex("""fun parar\s*\(porque"""))
        assertTrue("armado.parar(porque)" in parar, promesa(308) + " · Ejecucion.parar no pide el alto al armado: $parar")
        assertFalse(Regex("""\.cancel\s*\(|cortaSiNoSuelta|(?i:gracia)""").containsMatchIn(parar), promesa(308) + " · Ejecucion.parar decide el corte por su cuenta: $parar")
        val armadoDeLaApp = ejecucion.donde(Regex("""\bArmadoDeEjecucion\s*\(""")).singleOrNull() ?: fail(promesa(308) + " · $EJECUCION no arma un único ArmadoDeEjecucion")
        assertTrue(Regex("""lanza\s*=\s*\{\s*(\w+)\s*->\s*[\w.]*\.launch\s*\{\s*\1\s*\(\s*\)\s*\}""").containsMatchIn(armadoDeLaApp),
            promesa(308) + " · el armado de la app no tiene con qué lanzar el corte tras el alto: $armadoDeLaApp")
        assertFalse(Regex("""(?i:gracia)""").containsMatchIn(armadoDeLaApp), promesa(308) + " · la app cambia la gracia del armado: $armadoDeLaApp")
        assertTrue(ejecucion.donde(Regex("""fun <T> correr\s*\([^\n]*=\s*armado\.correr\(""")).isNotEmpty(),
            promesa(308) + " · Ejecucion.correr no abre la tarea del armado")
        val frenos = fuentes.flatMap { it.donde(Regex("""(?<![\w.])Freno\s*\(""")) }
        assertEquals(1, frenos.size, promesa(308) + " · la app no tiene un único freno: $frenos")
        assertTrue(frenos.single().substringBefore(":").endsWith(EJECUCION), promesa(308) + " · el freno no vive en $EJECUCION: $frenos")

        // Sin corrida, parar no arma nada ni lanza un corte.
        val bitacora = Bitacora()
        val avisos = mutableListOf<String>()
        val freno = Freno(log = bitacora, avisa = { avisos += it })
        var alcance: CoroutineScope? = null
        var cortes = 0
        val armado = ArmadoDeEjecucion(freno, bitacora, lanza = { corte -> cortes++; alcance?.launch { corte() } })
        armado.parar("píldora")
        assertFalse(freno.pedido, promesa(308) + " · parar sin corrida armó el freno")
        assertFalse(armado.enCurso, promesa(308))
        assertEquals(0, cortes, promesa(308) + " · parar sin corrida lanzó un corte")

        // Dentro de la corrida, las tres órdenes van al mismo alto: arma el freno de ESA tarea y avisa una vez.
        var armoLaPildora = false
        var enCurso = false
        val salida = coroutineScope {
            alcance = this
            runCatching {
                armado.correr("abre ajustes") {
                    enCurso = armado.enCurso
                    armado.parar("píldora")
                    armoLaPildora = freno.pedido && freno.tarea == "corrida"
                    armado.parar("notificación")
                    armado.parar("voz")
                }
            }
        }
        assertTrue(enCurso, promesa(308) + " · la corrida no se sabe en curso")
        assertTrue(armoLaPildora, promesa(308) + " · la píldora no armó el freno de la tarea abierta")
        assertIs<Paraste>(salida.exceptionOrNull(), promesa(308) + " · una corrida con alto no terminó como cancelación: $salida")
        assertEquals(listOf(Freno.ALTO, Freno.DEVUELVO_EL_CONTROL), avisos, promesa(308) + " · tres órdenes, avisos: $avisos")
        assertEquals(listOf("freno: alto pedido (píldora); paro «corrida»"), bitacora.lineas.filter { "alto pedido" in it }, promesa(308))
        assertFalse(armado.enCurso, promesa(308) + " · la corrida acabó y sigue en curso")

        // Una corrida que suelta sola tras el alto (su turno de Graph vuelve a los 100 ms) no se corta: la gracia es
        // para soltar, y la corrida termina por el alto, no por el corte.
        run {
            val desde = bitacora.lineas.size
            val pensando = CompletableDeferred<Unit>()
            val vuelve = object : Brain {
                override fun begin(goal: String) {}
                override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
                    pensando.complete(Unit)
                    delay(100)
                    return BrainTurn(done = true, text = "tarde")
                }
                override fun inform(message: String) {}
            }
            val sesion = armado.arma(manos(Mano()), { vuelve }, Voz(), pausa = { 0 })
            coroutineScope {
                alcance = this
                val corrida = launch { runCatching { armado.correr("suelta sola") { sesion.motor.run("suelta sola") } } }
                pensando.await()
                armado.parar("píldora")
                corrida.join()
            }
            val nuevas = bitacora.lineas.drop(desde)
            assertTrue(nuevas.any { it.startsWith("run: ✋") }, promesa(308) + " · la corrida no soltó sola por el alto: $nuevas")
            assertTrue(nuevas.none { it.startsWith("freno:") && "corto" in it }, promesa(308) + " · parar cortó una corrida que soltaba sola: $nuevas")
        }

        // Un turno de Graph colgado en red tras el alto: parar corta el trabajo pasada la gracia, no antes, y la corrida
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
        var tardo = 0.milliseconds
        var soltó = true
        coroutineScope {
            alcance = this
            val corrida = launch {
                salioColgada = runCatching { armado.correr("colgada") { sesion.motor.run("colgada") } }.exceptionOrNull()
            }
            pensando.await()
            val alto = TimeSource.Monotonic.markNow()
            armado.parar("notificación")
            soltó = withTimeoutOrNull((GRACIA + 2_000).milliseconds) { corrida.join() } != null
            tardo = alto.elapsedNow()
            if (!soltó) corrida.cancel()
        }
        assertTrue(soltó, promesa(308) + " · parar no cortó la corrida colgada en ${GRACIA + 2_000} ms")
        assertIs<CancellationException>(salioColgada, promesa(308) + " · la corrida colgada no terminó como cancelación")
        assertTrue(tardo >= GRACIA.milliseconds, promesa(308) + " · cortó a los $tardo, antes de la gracia de $GRACIA ms: cortó en vez de pedir el alto")
        assertTrue(tardo < (GRACIA + 1_000).milliseconds, promesa(308) + " · cortar la corrida colgada tardó $tardo")
        assertEquals(emptyList(), mano.entradas, promesa(308))
        assertFalse(freno.abierta, promesa(308) + " · la corrida cortada no soltó el freno")
        assertTrue(bitacora.lineas.any { it.startsWith("freno:") && "corto" in it }, promesa(308) + " · el corte no quedó en el log: ${bitacora.lineas}")
    }

    @Test
    fun promesa320() = corre {
        // El armado real con un tope y una cuenta compartidos, como los de la app. «Guardar» no responde: cada toque que
        // llega al teléfono es un fallo, y el tercero de una misma petición no tiene que llegar.
        val bitacora = Bitacora()
        val freno = Freno(log = bitacora)
        val armado = ArmadoDeEjecucion(
            freno, bitacora, tope = TopeDeIntentos(Contrato003TopeYCuenta.CELDA), cuenta = CuentaDePeticion(TestTimeSource(), bitacora),
        )
        val tel = Telefono(alTocar = { false })
        val mano = Mano()
        val manos = ArmadoDeEjecucion.Manos(tel.telefono, mano.gestos, mano.sistema, tel.reproductor)
        fun toca(veces: Int) = CerebroGuionado(BrainTurn(actions = List(veces) { AgentAction.Tap(550, 900) }), BrainTurn(done = true, text = "hecho"))
        fun toques() = tel.entradas.count { it == "tap" }
        fun medidas() = bitacora.lineas.filter { it.startsWith("peticion: ") }

        var trasElPaso = -1
        var medidasDentro = -1
        armado.correr("guarda el documento") {
            armado.arma(manos, { toca(2) }, Voz(), pausa = { 0 }).motor.run("guarda el documento")
            // Una corrida que se intenta abrir encima no es una petición nueva.
            runCatching { armado.correr("guárdalo otra vez") { "encima" } }
            // El paso consciente arma su motor sobre otra puerta y vuelve a tocar «Guardar»: es la tercera de la petición.
            armado.pasoConsciente("toca guardar", armado.arma(manos, { toca(1) }, Voz(), maxTurnos = 8, pausa = { 0 }).motor)
            trasElPaso = toques()
            medidasDentro = medidas().size
        }
        assertEquals(2, trasElPaso, promesa(320) + " · el paso consciente o la corrida de encima devolvieron el tope a cero: el tercer toque llegó")
        assertEquals(0, medidasDentro, promesa(320) + " · dentro de la corrida se cerró una petición: ${medidas()}")
        val primera = medidas()
        assertEquals(1, primera.size, promesa(320) + " · la corrida no dejó su medida al acabar: ${bitacora.lineas}")
        assertTrue(primera.single().startsWith("peticion: llamadas=3 ") && primera.single().endsWith(" rechazadas=1 retiradas=0"),
            promesa(320) + " · ${primera.single()}")

        // Una corrida nueva de fuera sí abre otra petición: el mismo toque vuelve a llegar y deja su propia medida.
        armado.correr("guarda el documento") { armado.arma(manos, { toca(1) }, Voz(), pausa = { 0 }).motor.run("guarda el documento") }
        assertEquals(3, toques(), promesa(320) + " · una corrida nueva de fuera no devolvió el tope a cero")
        assertEquals(2, medidas().size, promesa(320) + " · la corrida nueva no dejó su propia medida: ${medidas()}")
        assertTrue(medidas().last().startsWith("peticion: llamadas=1 "), promesa(320) + " · ${medidas().last()}")

        // Y la app los comparte de verdad: Ejecucion tiene UN tope y UNA cuenta por proceso y se los da a su único armado.
        // Nadie más en la app construye otro, los esconde tras un alias, ni abre o reinicia una petición: eso es de `correr`.
        val fuentes = fuentesDeLaApp()
        val ejecucion = fuentes.uno(EJECUCION)
        fun compartido(clase: String): String {
            val construidos = fuentes.flatMap { it.donde(Regex("""(?<![\w.])$clase\s*\(""")) }
            assertEquals(1, construidos.size, promesa(320) + " · la app no tiene un único $clase: $construidos")
            return Regex("""^    private val (\w+)\s*=\s*$clase\s*\(""", RegexOption.MULTILINE).find(ejecucion.codigo)?.groupValues?.get(1)
                ?: fail(promesa(320) + " · el $clase de la app no es un `private val` de $EJECUCION: $construidos")
        }
        val tope = compartido("TopeDeIntentos")
        val cuenta = compartido("CuentaDePeticion")
        val armadoDeLaApp = ejecucion.donde(Regex("""\bArmadoDeEjecucion\s*\(""")).singleOrNull() ?: fail(promesa(320) + " · $EJECUCION no arma un único ArmadoDeEjecucion")
        assertTrue(Regex("""\btope\s*=\s*$tope\b""").containsMatchIn(armadoDeLaApp) && Regex("""\bcuenta\s*=\s*$cuenta\b""").containsMatchIn(armadoDeLaApp),
            promesa(320) + " · el armado de la app no recibe el tope y la cuenta del proceso: $armadoDeLaApp")
        val escondido = Regex("""\btypealias\s+\w+\s*=\s*([\w.]*\.)?(TopeDeIntentos|CuentaDePeticion)\b|\bimport\s+[\w.]*\b(TopeDeIntentos|CuentaDePeticion)\s+as\s+\w+|::\s*(TopeDeIntentos|CuentaDePeticion)\b""")
        assertEquals(emptyList(), fuentes.flatMap { it.donde(escondido) }, promesa(320) + " · un tope o una cuenta se esconden tras un alias")
        assertEquals(emptyList(), fuentes.flatMap { it.donde(Regex("""\b(abrePeticion|nuevaPeticion)\s*\(""")) },
            promesa(320) + " · la app abre o reinicia una petición por su cuenta")
    }
}
