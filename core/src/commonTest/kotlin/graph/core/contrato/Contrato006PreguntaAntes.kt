package graph.core.contrato

import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.contrato.Contrato003FrenoYPuerta.Voz
import graph.core.domain.AgentAction
import graph.core.domain.Brain
import graph.core.domain.BrainTurn
import graph.core.domain.GraphLog
import graph.core.domain.LearnedTool
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.application.ExecutionEngine
import graph.core.domain.UserChannel
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.Freno
import graph.core.precision.Paraste
import graph.core.pregunta.CompuertaDePregunta
import graph.core.pregunta.huella
import graph.core.telemetria.PuertaDeTelemetria
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * CONTRATO 006 — PREGUNTA ANTES DE EJECUTAR (docs/specs/006-pregunta-antes-de-ejecutar.md).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Se escribieron ANTES que el
 * código que juzgan: nacieron rojas contra un esqueleto que deja pasar todo.
 *
 * Nada toca Android, red ni disco: el teléfono, los gestos y el sistema son los falsos de la 003 ([Mano]), el cerebro es un
 * guion que además graba los `results` que le llegan, y el canal de preguntar es un falso que anota cada pregunta. Todo pasa
 * por el [ArmadoDeEjecucion] de verdad: si la compuerta no está cableada ahí, estas promesas no se ponen verdes.
 */
class Contrato006PreguntaAntes {

    companion object {
        val PROMESAS = mapOf(
            601 to "Una acción sensible que el pedido no autorizó no se ejecuta: el cliente pregunta primero y nada llega al teléfono; si el pedido ya la pidió, con ese destinatario y ese contenido, se ejecuta sin preguntar.",
            602 to "Un destino ambiguo se pregunta ofreciendo las opciones que el cliente vio —las etiquetas de la pantalla o las apps instaladas—, nunca una lista inventada; con un solo candidato, o con uno que es igual a lo pedido, no pregunta.",
            603 to "Un dato que falta se pide, uno por pregunta y el más importante primero; una hora que falta no cae en el default de las 8, y lo que la acción ya trae no se pregunta.",
            604 to "Mientras espera la respuesta no se ejecuta nada ni se pide otro turno: la corrida queda viva y quieta, ningún tope ni plazo la resuelve actuando por su cuenta, y el alto de siempre la para.",
            605 to "La respuesta continúa la MISMA corrida: vuelve al cerebro como resultado de la acción frenada, en el mismo hilo y sin abrir otro, y sin repetir la pregunta ya hecha.",
            606 to "No se pregunta dos veces lo mismo en la misma corrida: contestado que sí, la misma acción pasa sin preguntar; contestado que no, o sin contestar, no se ejecuta ni se vuelve a preguntar.",
            607 to "Sin canal para preguntar, una acción sensible no se ejecuta: el cerebro se entera por el resultado y la corrida sigue.",
            608 to "De una pregunta del cliente solo sale la medida: su clase y los largos. Ni el texto de la pregunta, ni la respuesta, ni el destinatario ni las opciones salen al log, y lo que sale pasa entero la puerta de la telemetría.",
            609 to "Un pedido autoriza una acción sensible solo si el destinatario y el contenido de la acción se corresponden con los del pedido: un destinatario que no aparece en el pedido, o que no hay forma de comparar, no autoriza; un teléfono se compara sin separadores ni prefijo de país.",
            610 to "La autorización vale para esa acción con ese destinatario Y ese contenido: un segundo mensaje al mismo destinatario con otro texto vuelve a preguntar, y un «sí» a un compartir no autoriza el compartir siguiente.",
            611 to "Solo autoriza el texto que escribió o dictó la persona: lo que redactó el modelo —una acción anticipada autónoma, o el contexto de una propuesta que la persona rechazó— no autoriza nada, y lo sensible se pregunta igual.",
            612 to "«Ya contestado» solo salta el permiso: un dato que sigue faltando y un nombre que sigue siendo ambiguo no se dan por resueltos —la acción no se ejecuta ni cae en el default de las 8— y tampoco se preguntan en bucle.",
            613 to "Una duda sin respuesta no traba la app: la persona puede cerrarla y cerrarla cuenta como «no», y si el canal desaparece la corrida termina sin ejecutar lo sensible, sin plazo que decida por su cuenta.",
            614 to "Una respuesta ambigua no se asume: se vuelve a preguntar una vez y, si sigue ambigua, no se ejecuta. Una negación cuenta cuando abre la respuesta, no en cualquier posición, y una respuesta vacía sigue siendo un no.",
            615 to "Un paso consciente de un workflow no se autoriza a sí mismo: el objetivo que arma el workflow no es lo que dijo la persona, así que una acción sensible que ese objetivo nombra se pregunta igual.",
            616 to "Un número del pedido autoriza solo si es el destinatario de esa acción: con más de un destinatario posible en el pedido no se adivina cuál va con cuál y se pregunta.",
            617 to "La llave de lo ya contestado no guarda el contenido: es una huella acotada que distingue dos contenidos distintos, no cambia porque cambie el espaciado y no sale al log.",
            618 to "Un destino de menos de 7 cifras no se autoriza nunca por coincidir con un número del pedido: un monto o un código no dicen a quién va la acción, así que se pregunta; la igualdad exacta sirve para descartar, nunca para autorizar.",
            619 to "El permiso de un paso consciente viaja desde la corrida que disparó el workflow, no se vuelve a calcular: una corrida autónoma, que no autoriza nada, tampoco autoriza el paso que dispare.",
            620 to "Una fecha del pedido no cuenta como destinatario posible: un pedido con una fecha y un solo teléfono no se vuelve ambiguo y no pregunta de más.",
            621 to "Correr el motor obliga a decir qué dijo la persona: no hay default que autorice con el objetivo, así que un objetivo que redactó el modelo no puede autorizarse a sí mismo por descuido de quien lo corre.",
        )

        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /* Lo que la persona pide, y lo que el cerebro decide hacer con ello. Las palabras están elegidas para que ningún
           trozo de cuatro sea parte de una palabra del propio log («pregunta», «caracteres», «corrida», «abierta»…): un
           juez de fugas que da rojo con el log limpio no juzga nada (la lección de la 317). */
        const val MIRA = "mira el chat de Zorbax"
        const val MANDA = "mándale a Ana que llego tarde"
        const val NUMERO = "3104459821"
        const val OTRO_NUMERO = "3209988776"
        const val LLEGO_TARDE = "Llego tarde"
        const val KINVARA = "Kinvara al mediodía"
        const val CORREO = "qwyk@buzon.co"

        /* Lo que trajo la E3, tras el control: un destinatario que SÍ se cruza con el pedido, el mismo número escrito de
           otra forma, y una respuesta que dice que sí y que no a la vez. */

        /** El correo de quien el pedido nombra: «ana» está en el pedido, así que este destinatario sí se corresponde (609). */
        const val CORREO_DE_ANA = "ana@parlante.co"

        /** El pedido que trae el número: sin un número en el pedido, el de la acción no tiene con qué cruzarse (609). */
        const val MANDA_AL_NUMERO = "mándale al 310 445 9821 que llego tarde"

        /** El mismo [NUMERO] como lo escribe un contacto: separadores y prefijo de país no lo vuelven otro (609). */
        const val NUMERO_CON_PREFIJO = "+57 310-445-9821"

        /** Otro texto para compartir: el «sí» del primero no lo autoriza (610). */
        const val OTRO_TEXTO = "Wexel a las seis"

        /** Dice que no y que sí en la misma frase: no se asume ninguna de las dos, se vuelve a preguntar (614). */
        const val AMBIGUA = "claro, no hay problema, mándalo"

        /* Lo que cierra el control de la E3: un pedido con dos destinatarios, un destino corto, el mismo contenido con
           otro espaciado y el objetivo que un workflow le escribe a su paso consciente. */

        /** Dos destinatarios en un mismo pedido: el número de la acción está en el texto, pero puede ser el del otro (616). */
        const val DOS_DESTINOS = "llama a mi jefe al 3001112222 y mándale un mensaje a mi hermana al 3003334444"

        /** El de la hermana: aparece en [DOS_DESTINOS], que es justo lo que no alcanza para autorizar (616). */
        const val NUMERO_DE_LA_HERMANA = "3003334444"

        /** Un contenido cuyas palabras sí salen del pedido: así lo único que decide el caso es el destinatario (616). */
        const val PARA_LA_HERMANA = "Mensaje para mi hermana"

        /** Un destino corto, de los que no llegan a 7 cifras: un código de banco o de operadora es así (618). */
        const val CORTO = "3838"

        /** Otro código del mismo largo: corto no quiere decir parecido (618). */
        const val OTRO_CORTO = "9090"

        /** El pedido que nombra el destino corto: ni así autoriza, porque un número corto no dice a quién va (618). */
        const val MANDA_AL_CORTO = "mándale un mensaje al 3838 que llego tarde"

        /* Lo que cierra el control de la E4: un monto que se parece a un código corto, y una fecha que el guion fundía en
           una ristra de cifras. */

        /** El caso del control: «89000» es lo que la persona cobró, no a quién le manda el mensaje (618). */
        const val EL_MONTO = "cobré 89000 pesos, mándale un mensaje a mi mamá"

        /** El código corto al que iba el SMS: mismo largo y mismo valor que el monto, y aun así no es un destinatario (618). */
        const val CODIGO_DEL_MONTO = "89000"

        /** Un contenido que sí sale de [EL_MONTO]: así lo único que decide el caso es el destinatario (618). */
        const val PARA_MAMA = "Mensaje para mi mamá"

        /** Un pedido con una fecha y un teléfono inequívoco: la fecha no lo vuelve ambiguo (620). */
        const val CON_FECHA = "mándale al 310 445 9821 que llego tarde el 2026-03-15"

        /** Un pedido cuyo único número es una fecha: no hay destinatario con el que cruzar y se pregunta (620). */
        const val SOLO_FECHA = "mándale un mensaje el 2026-03-15 que llego tarde"

        /** El mismo [KINVARA] con otro espaciado: es el mismo contenido y no se vuelve a preguntar por él (617). */
        const val KINVARA_ESPACIADO = "Kinvara   al\n  mediodía "

        /** Lo que `GraphApp.consciousStep` le arma a un paso: nombra la acción del paso, y eso no es un permiso (615). */
        const val PASO_DEL_WORKFLOW = "Estás EN MEDIO del workflow \"avisos\" (avisa cuando llegues tarde). La pantalla ya " +
            "está donde la dejaron los pasos anteriores: NO reinicies la tarea ni vayas al home. Ejecuta SOLO este paso y " +
            "termina: $MANDA."

        /** Cómo la pantalla lista lo que se ve (`GraphAccessibilityService.uiContext`). */
        fun pantallaCon(vararg etiquetas: String) =
            "paquete: com.x\ntipo: aplicación\nclickeables: 9 · campos de texto: 1\netiquetas visibles: " + etiquetas.joinToString(" · ")

        /* Lo que ayuda a juzgar una fuga: los trozos de un dato y las marcas más cortas que un trozo. */

        val PALABRA = Regex("""[\p{L}\p{N}]+""")
        private val SIN_TILDE = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n')
        fun plano(s: String) = s.lowercase().map { SIN_TILDE[it] ?: it }.joinToString("")

        /** Lo que delata aunque sea más corto que un trozo de cuatro, como palabra entera. */
        val MARCAS = listOf("ana", "zorbax", "qwyk", "nequi", "kinvara", "3104459821")
            .map { Regex("""(?<![\p{L}\p{N}])$it(?![\p{L}\p{N}])""") }

        /**
         * Una corrida de fuera con el armado de verdad: el guion de turnos, el canal falso y, si hace falta, lo que el
         * cliente ve (la pantalla y el catálogo de apps). El reloj del freno es de prueba: las esperas del motor no duermen.
         */
        suspend fun corrida(
            pedido: String,
            vararg turnos: BrainTurn,
            canal: Canal = Canal("no"),
            conCanal: Boolean = true,
            apps: List<String> = emptyList(),
            pantalla: String = "",
            /** El objetivo que recibe el motor. Por defecto es el pedido; se separan para juzgar quién lo escribió (611). */
            objetivo: String = pedido,
            /** Lo que la persona escribió o dictó. `null` = nada suyo: el objetivo lo redactó entero el modelo (611). */
            dijoLaPersona: String? = pedido,
        ): Escena {
            val montaje = monta(canal, conCanal, apps, pantalla, turnos)
            val salida = runCatching {
                montaje.armado.correr(objetivo) { montaje.motor.run(objetivo, dijoLaPersona = dijoLaPersona) }
            }
            return montaje.escena(salida)
        }

        /**
         * UN PASO CONSCIENTE de un workflow, con el mismo armado de verdad y dentro de una corrida, que es como corre en la
         * app. El [objetivo] lo escribe el workflow —nombra el paso, su nota y sus datos—, así que lo único que puede
         * autorizar algo es [dijoLaPersona] (promesa 615). La salida es el `true`/`false` del paso, como texto.
         */
        suspend fun paso(
            objetivo: String,
            vararg turnos: BrainTurn,
            canal: Canal = Canal("no"),
            pedidoDeFuera: String = MIRA,
            dijoLaPersona: String? = null,
        ): Escena {
            val montaje = monta(canal, conCanal = true, apps = emptyList(), pantalla = "", turnos = turnos)
            val salida = runCatching {
                montaje.armado.correr(pedidoDeFuera) {
                    montaje.armado.pasoConsciente(objetivo, montaje.motor, dijoLaPersona).toString()
                }
            }
            return montaje.escena(salida)
        }

        /** El armado de verdad ya montado: de aquí sale tanto una corrida de fuera como un paso consciente. */
        private class Montaje(
            val mano: Mano,
            val canal: Canal,
            val cerebro: Cerebro,
            val diario: Diario,
            val armado: ArmadoDeEjecucion,
            val motor: ExecutionEngine,
        ) {
            fun escena(salida: Result<String>) = Escena(mano, canal, cerebro, diario, salida)
        }

        private fun monta(
            canal: Canal,
            conCanal: Boolean,
            apps: List<String>,
            pantalla: String,
            turnos: Array<out BrainTurn>,
        ): Montaje {
            val diario = Diario()
            val mano = Mano()
            val cerebro = Cerebro(*turnos)
            val conPantalla = object : Phone by mano.telefono {
                override suspend fun state(withScreenshot: Boolean) = ScreenState("com.x · X", pantalla, 1080, 2400)
            }
            val reloj = TestTimeSource()
            val armado = ArmadoDeEjecucion(Freno(log = diario, reloj = reloj, espera = { reloj += it.milliseconds }), diario)
            val sesion = armado.arma(
                ArmadoDeEjecucion.Manos(conPantalla, mano.gestos, mano.sistema, mano.reproductor),
                { cerebro }, Voz(), usuario = if (conCanal) canal else null, pausa = { 0 },
                aprendidas = listOf(LearnedTool("contactos", "abre un contacto", listOf("Zorbax Qwyk"))),
                apps = { apps },
            )
            return Montaje(mano, canal, cerebro, diario, armado, sesion.motor)
        }
    }

    /* ---------- El mapa a mano ---------- */

    /** El log de la corrida: «tag: mensaje» como la spec lo cita, y el par suelto para pasarlo por la puerta de la 005. */
    class Diario : GraphLog {
        val lineas = mutableListOf<String>()
        val pares = mutableListOf<Pair<String, String>>()
        override fun log(tag: String, message: String) {
            lineas += "$tag: $message"
            pares += tag to message
        }
    }

    /**
     * El canal que YA existe (`UserChannel`), falso: anota cada pregunta y contesta lo que le pusieron, en orden. Con
     * [revienta] se va al preguntar, como la pantalla que muere con la duda puesta (promesa 613).
     */
    class Canal(vararg respuestas: String, private val revienta: Boolean = false) : UserChannel {
        private val respuestas = respuestas.toList()
        val preguntas = mutableListOf<String>()
        override suspend fun ask(question: String): String {
            preguntas += question
            if (revienta) throw IllegalStateException("la pantalla se fue con la duda puesta")
            return respuestas.getOrElse(preguntas.size - 1) { respuestas.lastOrNull() ?: "" }
        }
    }

    /** El cerebro guionado que además graba los `results` que le llegan en cada turno (promesa 605). */
    class Cerebro(vararg guion: BrainTurn) : Brain {
        val recibidos = mutableListOf<List<String>>()
        val informado = mutableListOf<String>()
        var comenzado = 0
        private val cola = ArrayDeque(guion.toList())
        override fun begin(goal: String) { comenzado++ }
        override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
            recibidos += actionResults
            return cola.removeFirstOrNull() ?: BrainTurn(done = true, text = "guion agotado")
        }
        override fun inform(message: String) { informado += message }
    }

    class Escena(val mano: Mano, val canal: Canal, val cerebro: Cerebro, val diario: Diario, val salida: Result<String>) {
        val entradas get() = mano.entradas
        val preguntas get() = canal.preguntas
        /** Los `results` del turno [turno], tal como los recibió el turno siguiente. */
        fun resultados(turno: Int): List<String> = cerebro.recibidos.getOrElse(turno) { emptyList() }
        fun preguntasDeClase(clase: String) = diario.lineas.count { it.startsWith("pregunta: ❓ $clase") }
    }

    private fun sms(numero: String = NUMERO, texto: String = LLEGO_TARDE) =
        AgentAction.Mcp("send_sms", mapOf("number" to numero, "message" to texto))

    private fun correo(a: String = CORREO, texto: String = LLEGO_TARDE) =
        AgentAction.Mcp("send_email", mapOf("to" to a, "body" to texto))

    private fun llamada(numero: String = NUMERO) = AgentAction.Mcp("call", mapOf("number" to numero))

    private fun comparte(texto: String = KINVARA) = AgentAction.Mcp("share_text", mapOf("text" to texto))

    private fun fin() = BrainTurn(done = true, text = "fin")

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa601() = corre {
        val p = 601
        // Lo sensible que el pedido no pidió: ni un mensaje, ni un correo, ni una llamada, ni compartir, ni tocar «Enviar».
        val frenada = corrida(
            MIRA,
            BrainTurn(
                actions = listOf(
                    sms(),
                    AgentAction.Mcp("send_email", mapOf("to" to CORREO, "body" to KINVARA)),
                    AgentAction.Mcp("call", mapOf("number" to NUMERO)),
                    AgentAction.Mcp("share_text", mapOf("text" to KINVARA)),
                    AgentAction.Mcp("contactos", mapOf("taps" to "Enviar")),
                ),
            ),
            fin(),
            canal = Canal("no", "no", "no", "no", "no"),
        )
        assertEquals(emptyList(), frenada.entradas, promesa(p) + " · algo llegó al teléfono sin que el pedido lo autorizara")
        assertEquals(5, frenada.preguntas.size, promesa(p) + " · no preguntó por cada una: ${frenada.preguntas}")
        assertEquals(5, frenada.preguntasDeClase("permiso"), promesa(p) + " · ${frenada.diario.lineas}")
        assertEquals(5, frenada.resultados(1).count { it.startsWith(CompuertaDePregunta.PREGUNTE) },
            promesa(p) + " · el cerebro no se enteró: ${frenada.resultados(1)}")

        // Lo que el pedido SÍ pidió, con ese destinatario y ese contenido: se hace, y no se pregunta nada. El destinatario
        // tiene que cruzarse con el pedido —«ana» está en él—: un número que el pedido no nombra ya no basta (promesa 609).
        val pedida = corrida(MANDA, BrainTurn(actions = listOf(correo(CORREO_DE_ANA))), fin())
        assertEquals(listOf("sendEmail"), pedida.entradas, promesa(p) + " · no hizo lo que le pidieron: ${pedida.diario.lineas}")
        assertEquals(emptyList(), pedida.preguntas, promesa(p) + " · preguntó por lo que ya le habían pedido")

        // El mismo pedido no autoriza otro destinatario, ni otro contenido, ni otra clase de acción.
        for ((que, accion) in listOf(
            "otro destinatario" to AgentAction.Mcp("send_email", mapOf("to" to CORREO, "body" to LLEGO_TARDE)),
            "otro contenido" to sms(texto = KINVARA),
            "otra clase" to AgentAction.Mcp("call", mapOf("number" to NUMERO)),
        )) {
            val otra = corrida(MANDA, BrainTurn(actions = listOf(accion)), fin())
            assertEquals(emptyList(), otra.entradas, promesa(p) + " · $que pasó como si el pedido lo autorizara")
            assertEquals(1, otra.preguntas.size, promesa(p) + " · $que no se preguntó: ${otra.preguntas}")
        }

        // Lo que no es sensible pasa como siempre: marcar sin llamar (no llama), mirar, navegar.
        val normal = corrida(
            MIRA,
            BrainTurn(
                actions = listOf(
                    AgentAction.Mcp("dial", mapOf("number" to NUMERO)),
                    AgentAction.Tap(10, 20),
                    AgentAction.Scroll(true),
                    AgentAction.Mcp("go_home", emptyMap()),
                ),
            ),
            fin(),
        )
        assertEquals(listOf("dial", "tap", "scroll", "home"), normal.entradas, promesa(p) + " · frenó lo que no es sensible")
        assertEquals(emptyList(), normal.preguntas, promesa(p) + " · preguntó por lo que no es sensible")
    }

    @Test
    fun promesa603() = corre {
        val p = 603
        // La hora que falta no cae en el default de las 8: se pregunta y la alarma no se pone.
        val sinHora = corrida("pon una alarma", BrainTurn(actions = listOf(AgentAction.Mcp("set_alarm", emptyMap()))), fin(), canal = Canal("a las nueve"))
        assertEquals(emptyList(), sinHora.entradas, promesa(p) + " · la alarma de las 8 llegó al teléfono")
        assertEquals(1, sinHora.preguntas.size, promesa(p) + " · ${sinHora.preguntas}")
        assertTrue("hora" in sinHora.preguntas.single().lowercase(), promesa(p) + " · no preguntó por la hora: ${sinHora.preguntas}")
        assertEquals(1, sinHora.preguntasDeClase("dato"), promesa(p) + " · ${sinHora.diario.lineas}")

        // Lo que la acción ya trae no se pregunta.
        val conHora = corrida("pon una alarma a las 7", BrainTurn(actions = listOf(AgentAction.Mcp("set_alarm", mapOf("hour" to "7")))), fin())
        assertEquals(listOf("setAlarm"), conHora.entradas, promesa(p) + " · no puso la alarma que ya traía la hora")
        assertEquals(emptyList(), conHora.preguntas, promesa(p) + " · preguntó por un dato que ya venía")

        // Faltan dos: UNA sola pregunta, y la más importante primero (cuándo antes que cómo se llama).
        val evento = corrida("agéndame algo", BrainTurn(actions = listOf(AgentAction.Mcp("create_event", emptyMap()))), fin(), canal = Canal("mañana"))
        assertEquals(1, evento.preguntas.size, promesa(p) + " · preguntó por más de un dato a la vez: ${evento.preguntas}")
        assertTrue("cuándo" in evento.preguntas.single().lowercase(), promesa(p) + " · la primera no fue la más importante: ${evento.preguntas}")
        val conCuando = corrida(
            "agéndame algo", BrainTurn(actions = listOf(AgentAction.Mcp("create_event", mapOf("start" to "2026-09-17T09:00")))), fin(), canal = Canal("cita"),
        )
        assertTrue("título" in conCuando.preguntas.single().lowercase(), promesa(p) + " · con el cuándo puesto toca el título: ${conCuando.preguntas}")

        // Cada herramienta pide lo suyo, y mientras tanto no toca el teléfono.
        for ((accion, palabra) in listOf(
            AgentAction.Mcp("set_timer", emptyMap()) to "tiempo",
            sms(numero = "") to "quién",
            AgentAction.Mcp("web_search", emptyMap()) to "busco",
            AgentAction.Mcp("directions", emptyMap()) to "dónde",
            AgentAction.Mcp("open_url", emptyMap()) to "página",
            AgentAction.Mcp("launch_app", emptyMap()) to "app",
        )) {
            val escena = corrida(MANDA, BrainTurn(actions = listOf(accion)), fin(), canal = Canal("ya"))
            assertEquals(emptyList(), escena.entradas, promesa(p) + " · «${accion.tool}» se ejecutó sin el dato que le falta")
            assertEquals(1, escena.preguntas.size, promesa(p) + " · «${accion.tool}»: ${escena.preguntas}")
            assertTrue(palabra in escena.preguntas.single().lowercase(), promesa(p) + " · «${accion.tool}» no pidió «$palabra»: ${escena.preguntas}")
        }
    }

    @Test
    fun promesa604() = corre {
        val p = 604
        coroutineScope {
            val espera = CompletableDeferred<String>()
            val canal = object : UserChannel {
                val preguntas = mutableListOf<String>()
                override suspend fun ask(question: String): String {
                    preguntas += question
                    return espera.await()
                }
            }
            val diario = Diario()
            val mano = Mano()
            val freno = Freno(log = diario)
            val armado = ArmadoDeEjecucion(freno, diario, lanza = { corte -> launch { corte() } }, graciaMs = 50)
            val cerebro = Cerebro(
                BrainTurn(actions = listOf(sms(), AgentAction.Tap(1, 1))),
                fin(),
            )
            val sesion = armado.arma(
                ArmadoDeEjecucion.Manos(mano.telefono, mano.gestos, mano.sistema, mano.reproductor),
                { cerebro }, Voz(), usuario = canal, maxTurnos = 1, pausa = { 0 },
            )
            var salida: Result<String>? = null
            val trabajo = launch { salida = runCatching { armado.correr(MIRA) { sesion.motor.run(MIRA, dijoLaPersona = MIRA) } } }
            // Con tope: una compuerta que no pregunta deja la prueba colgada, y colgada no dice qué falló.
            assertEquals(true, withTimeoutOrNull(2_000) { while (canal.preguntas.isEmpty()) yield(); true },
                promesa(p) + " · no preguntó antes de ejecutar la acción sensible")

            // Viva y quieta: ni el tope de turnos ni el paso del tiempo la resuelven actuando por su cuenta.
            delay(300)
            assertTrue(trabajo.isActive, promesa(p) + " · la corrida se resolvió sola mientras esperaba")
            assertEquals(emptyList(), mano.entradas, promesa(p) + " · tocó el teléfono mientras esperaba")
            assertEquals(1, cerebro.recibidos.size, promesa(p) + " · pidió otro turno mientras esperaba")
            assertEquals(1, canal.preguntas.size, promesa(p) + " · preguntó más de una vez: ${canal.preguntas}")

            // El alto de siempre la para, aunque esté esperando una respuesta que nunca llega.
            armado.parar("píldora")
            assertEquals(true, withTimeoutOrNull(3_000) { trabajo.join(); true }, promesa(p) + " · el alto no soltó la corrida que esperaba")
            assertIs<Paraste>(salida?.exceptionOrNull(), promesa(p) + " · el alto no paró la corrida que esperaba: $salida")
            assertEquals(emptyList(), mano.entradas, promesa(p) + " · tocó el teléfono al pararla")

            // Y la respuesta que llega tarde, con la corrida ya parada, no ejecuta nada.
            espera.complete("sí, mándaselo")
            repeat(5) { yield() }
            assertEquals(emptyList(), mano.entradas, promesa(p) + " · la respuesta tardía ejecutó la acción frenada")
        }
    }

    @Test
    fun promesa605() = corre {
        val p = 605
        val escena = corrida(
            MIRA,
            BrainTurn(actions = listOf(sms())),
            BrainTurn(actions = listOf(sms())),
            fin(),
            canal = Canal("sí, mándaselo"),
        )
        // La respuesta vuelve al cerebro por el results de la acción frenada, en la misma corrida.
        val resultado = assertSingle(escena.resultados(1), promesa(p) + " · el turno 1 no dejó su resultado")
        assertTrue(resultado.startsWith(CompuertaDePregunta.PREGUNTE), promesa(p) + " · el resultado no dice que preguntó: $resultado")
        assertTrue("sí, mándaselo" in resultado, promesa(p) + " · la respuesta de la persona no llegó al cerebro: $resultado")
        assertTrue(escena.preguntas.single() in resultado, promesa(p) + " · el cerebro no sabe qué se preguntó: $resultado")

        // El mismo hilo: ni otra corrida, ni el carril de la pregunta de Graph, ni la pregunta repetida.
        assertEquals(1, escena.cerebro.comenzado, promesa(p) + " · abrió otro hilo para la respuesta")
        assertEquals(emptyList(), escena.cerebro.informado, promesa(p) + " · la respuesta se fue por inform, que es el carril de Graph")
        assertEquals(1, escena.preguntas.size, promesa(p) + " · repitió la pregunta ya hecha: ${escena.preguntas}")
        assertEquals(listOf("sendSms"), escena.entradas, promesa(p) + " · el turno 2 no siguió la corrida: ${escena.diario.lineas}")
    }

    @Test
    fun promesa606() = corre {
        val p = 606
        // Contestado que sí: la misma acción pasa en el turno siguiente, sin volver a preguntar.
        val si = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms())), fin(), canal = Canal("sí, mándaselo"))
        assertEquals(listOf("sendSms"), si.entradas, promesa(p) + " · lo autorizado no se hizo")
        assertEquals(1, si.preguntas.size, promesa(p) + " · preguntó dos veces lo mismo: ${si.preguntas}")

        // Contestado que no, o sin contestar: no se ejecuta y no se vuelve a preguntar.
        for (respuesta in listOf("no", "")) {
            val negada = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms())), fin(), canal = Canal(respuesta))
            assertEquals(emptyList(), negada.entradas, promesa(p) + " · «$respuesta» dejó pasar la acción")
            assertEquals(1, negada.preguntas.size, promesa(p) + " · «$respuesta» volvió a preguntar: ${negada.preguntas}")
            val segundo = assertSingle(negada.resultados(2), promesa(p) + " · el turno 2 no dejó resultado")
            assertTrue(segundo.startsWith(CompuertaDePregunta.DIJISTE_QUE_NO), promesa(p) + " · «$respuesta» no se recuerda: $segundo")
        }

        // Otro asunto sí se pregunta: lo ya contestado vale para ESA acción, no para cualquiera.
        val otro = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms(numero = OTRO_NUMERO))), fin(), canal = Canal("no", "no"))
        assertEquals(2, otro.preguntas.size, promesa(p) + " · otro destinatario no se preguntó: ${otro.preguntas}")
        assertEquals(emptyList(), otro.entradas, promesa(p) + " · pasó sin permiso")
    }

    @Test
    fun promesa607() = corre {
        val p = 607
        val escena = corrida(MIRA, BrainTurn(actions = listOf(sms(), AgentAction.Tap(1, 1))), fin(), conCanal = false)
        assertEquals(listOf("tap"), escena.entradas, promesa(p) + " · mandó el mensaje sin nadie a quién preguntarle")
        val resultado = escena.resultados(1).first()
        assertTrue(resultado.startsWith(CompuertaDePregunta.SIN_CANAL), promesa(p) + " · el cerebro no se enteró: $resultado")
        assertTrue(escena.salida.isSuccess, promesa(p) + " · la corrida no siguió: ${escena.salida}")
        assertEquals(2, escena.cerebro.recibidos.size, promesa(p) + " · no pidió el turno siguiente")
    }

    @Test
    fun promesa608() = corre {
        val p = 608
        val escena = corrida(
            MIRA,
            BrainTurn(
                actions = listOf(
                    sms(texto = KINVARA),
                    AgentAction.Mcp("launch_app", mapOf("app" to "Banco")),
                    AgentAction.Mcp("set_alarm", emptyMap()),
                ),
            ),
            fin(),
            canal = Canal("no", "el de Zorbax Qwyk", "a las nueve"),
            apps = listOf("Banco Zorbax", "Banco Qwyk", "Nequi"),
            pantalla = pantallaCon("Zorbax Qwyk", "Archivar"),
        )
        assertEquals(3, escena.preguntas.size, promesa(p) + " · el juez no llegó a las tres clases: ${escena.preguntas}")

        // Ni un trozo de lo que la persona pidió, dijo o le contestaron, ni de lo que la pantalla muestra.
        val datos = listOf(MIRA, KINVARA, NUMERO, "Zorbax Qwyk", "Banco Zorbax", "Banco Qwyk", "Nequi", "el de Zorbax Qwyk", "a las nueve", "Archivar")
        val trozos = datos.flatMap { dato -> PALABRA.findAll(plano(dato)).flatMap { it.value.windowed(4) }.toList() }.toSet()
        for (linea in escena.diario.lineas) {
            val limpia = plano(linea)
            for (trozo in trozos) assertTrue(trozo !in limpia, promesa(p) + " · «$trozo» salió en «$linea»")
            for (marca in MARCAS) assertTrue(!marca.containsMatchIn(limpia), promesa(p) + " · una marca salió en «$linea»")
        }

        // Y lo que sí sale pasa ENTERO la puerta de la telemetría: la clase y los largos son medidas.
        val suyas = escena.diario.pares.filter { it.first == CompuertaDePregunta.TAG }
        assertEquals(6, suyas.size, promesa(p) + " · la compuerta no dejó su medida de cada pregunta y respuesta: $suyas")
        assertEquals(CompuertaDePregunta.TAG, PuertaDeTelemetria.tag(CompuertaDePregunta.TAG), promesa(p) + " · el tag sale como «otro»")
        for ((_, mensaje) in suyas) {
            assertEquals(mensaje, PuertaDeTelemetria.mensaje(mensaje), promesa(p) + " · la puerta de la 005 se come la medida de «$mensaje»")
        }
        assertEquals(1, escena.preguntasDeClase("permiso"), promesa(p) + " · ${escena.diario.lineas}")
        assertEquals(1, escena.preguntasDeClase("cual"), promesa(p) + " · ${escena.diario.lineas}")
        assertEquals(1, escena.preguntasDeClase("dato"), promesa(p) + " · ${escena.diario.lineas}")
    }

    @Test
    fun promesa609() = corre {
        val p = 609
        // Los seis casos que el control reprodujo contra la tabla real: los seis ejecutaban sin preguntar.
        for ((que, pedido, accion) in listOf(
            Triple("leer mensajes no autoriza mandar uno", "léeme los mensajes de Zorbax", sms(texto = "Ya voy")),
            Triple("revisar el correo no autoriza mandar un SMS", "revisa mi correo", sms(texto = "Ok gracias")),
            Triple("llamar a mamá no autoriza llamar a otro número", "llama a mamá", llamada(OTRO_NUMERO)),
            Triple("«marca» no autoriza una llamada", "busca la marca de este producto", llamada(OTRO_NUMERO)),
            Triple("el número de otro no es el destinatario pedido", MANDA, sms(numero = OTRO_NUMERO)),
            Triple("el correo del jefe no es Ana", MANDA, correo("jefe@empresa.com")),
        )) {
            val escena = corrida(pedido, BrainTurn(actions = listOf(accion)), fin())
            assertEquals(emptyList(), escena.entradas, promesa(p) + " · $que: salió del teléfono sin preguntar")
            assertEquals(1, escena.preguntas.size, promesa(p) + " · $que: no preguntó · ${escena.preguntas}")
            assertEquals(1, escena.preguntasDeClase("permiso"), promesa(p) + " · $que · ${escena.diario.lineas}")
        }

        // Lo que sí se corresponde pasa: un destinatario con letras que el pedido nombra…
        val conNombre = corrida(MANDA, BrainTurn(actions = listOf(correo(CORREO_DE_ANA))), fin())
        assertEquals(listOf("sendEmail"), conNombre.entradas, promesa(p) + " · no mandó lo que el pedido sí pedía")
        assertEquals(emptyList(), conNombre.preguntas, promesa(p) + " · preguntó por el destinatario que el pedido nombra")

        // …y un teléfono, que se compara como número: ni los separadores ni el prefijo de país lo vuelven otro.
        val conNumero = corrida(MANDA_AL_NUMERO, BrainTurn(actions = listOf(sms(numero = NUMERO_CON_PREFIJO))), fin())
        assertEquals(listOf("sendSms"), conNumero.entradas, promesa(p) + " · el mismo número escrito distinto no autorizó: ${conNumero.preguntas}")
        assertEquals(emptyList(), conNumero.preguntas, promesa(p) + " · preguntó por el número que el propio pedido trae")

        // Y el número del pedido autoriza el SUYO, no cualquiera.
        val otro = corrida(MANDA_AL_NUMERO, BrainTurn(actions = listOf(sms(numero = OTRO_NUMERO))), fin())
        assertEquals(emptyList(), otro.entradas, promesa(p) + " · un número del pedido autorizó otro distinto")
        assertEquals(1, otro.preguntas.size, promesa(p) + " · ${otro.preguntas}")
    }

    @Test
    fun promesa610() = corre {
        val p = 610
        // Turno 1 pregunta y la persona autoriza; turno 2 repite la MISMA acción y pasa (promesas 605 y 606); turno 3 va al
        // mismo destinatario con OTRO texto, y ese «sí» no era de ese texto: vuelve a preguntar.
        val mensajes = corrida(
            MIRA,
            BrainTurn(actions = listOf(sms())),
            BrainTurn(actions = listOf(sms())),
            BrainTurn(actions = listOf(sms(texto = KINVARA))),
            fin(),
            canal = Canal("sí, mándaselo", "no"),
        )
        assertEquals(listOf("sendSms"), mensajes.entradas, promesa(p) + " · el segundo texto salió con el permiso del primero")
        assertEquals(2, mensajes.preguntas.size, promesa(p) + " · no preguntó por el texto nuevo: ${mensajes.preguntas}")

        // Y compartir, que no tiene destinatario: sin el contenido en la llave, su llave quedaba vacía y un «sí»
        // autorizaba cualquier compartir del resto de la corrida.
        val compartir = corrida(
            MIRA,
            BrainTurn(actions = listOf(comparte(KINVARA))),
            BrainTurn(actions = listOf(comparte(KINVARA))),
            BrainTurn(actions = listOf(comparte(OTRO_TEXTO))),
            fin(),
            canal = Canal("sí, compártelo", "no"),
        )
        assertEquals(listOf("shareText"), compartir.entradas, promesa(p) + " · el segundo compartir salió sin permiso propio")
        assertEquals(2, compartir.preguntas.size, promesa(p) + " · no preguntó por el segundo: ${compartir.preguntas}")
    }

    @Test
    fun promesa612() = corre {
        val p = 612
        // El dato que sigue faltando no se da por resuelto: ni cae en el default de las 8, ni se pregunta en bucle.
        val alarma = corrida(
            "pon una alarma",
            BrainTurn(actions = listOf(AgentAction.Mcp("set_alarm", emptyMap()))),
            BrainTurn(actions = listOf(AgentAction.Mcp("set_alarm", emptyMap()))),
            fin(),
            canal = Canal("a las nueve"),
        )
        assertEquals(emptyList(), alarma.entradas, promesa(p) + " · la alarma de las 8 llegó al teléfono")
        assertEquals(1, alarma.preguntas.size, promesa(p) + " · preguntó lo mismo en bucle: ${alarma.preguntas}")
        val segundo = assertSingle(alarma.resultados(2), promesa(p) + " · el turno 2 no dejó resultado")
        assertTrue(segundo.startsWith(CompuertaDePregunta.SIGUE_FALTANDO),
            promesa(p) + " · el cerebro no supo que el dato sigue faltando: $segundo")

        // Un nombre que sigue siendo ambiguo tampoco se da por resuelto: no abre ninguna de las dos.
        val banco = corrida(
            "abre el banco",
            BrainTurn(actions = listOf(AgentAction.Mcp("launch_app", mapOf("app" to "Banco")))),
            BrainTurn(actions = listOf(AgentAction.Mcp("launch_app", mapOf("app" to "Banco")))),
            fin(),
            canal = Canal("el primero"),
            apps = listOf("Banco Zorbax", "Banco Qwyk"),
        )
        assertEquals(emptyList(), banco.entradas, promesa(p) + " · abrió una app sin saber cuál")
        assertEquals(1, banco.preguntas.size, promesa(p) + " · preguntó lo mismo en bucle: ${banco.preguntas}")

        // Lo ÚNICO que el atajo salta es el permiso: contestado que sí, la misma acción pasa sin preguntar.
        val permiso = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms())), fin(), canal = Canal("sí, mándaselo"))
        assertEquals(listOf("sendSms"), permiso.entradas, promesa(p) + " · lo autorizado no se hizo")
        assertEquals(1, permiso.preguntas.size, promesa(p) + " · volvió a preguntar un permiso ya contestado")
    }

    @Test
    fun promesa614() = corre {
        val p = 614
        // Dos turnos con la misma acción: el primero pregunta, el segundo la repite y pasa si quedó autorizada (605, 606).
        val dosTurnos = arrayOf(BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms())))

        // Ambigua —niega y afirma a la vez—: se pregunta otra vez en vez de asumir, y aclarado que sí, se hace.
        val aclarada = corrida(MIRA, *dosTurnos, fin(), canal = Canal(AMBIGUA, "dale"))
        assertEquals(2, aclarada.preguntas.size, promesa(p) + " · no repreguntó ante una respuesta ambigua: ${aclarada.preguntas}")
        assertEquals(listOf("sendSms"), aclarada.entradas, promesa(p) + " · aclarado que sí, no lo hizo")

        // Si la segunda sigue ambigua, no se ejecuta, y no se pregunta una tercera vez: el lado seguro, sin bucle.
        val confusa = corrida(MIRA, *dosTurnos, fin(), canal = Canal(AMBIGUA))
        assertEquals(2, confusa.preguntas.size, promesa(p) + " · preguntó de más: ${confusa.preguntas}")
        assertEquals(emptyList(), confusa.entradas, promesa(p) + " · ejecutó con una respuesta que no se entendió")

        // Una negación que ABRE la respuesta es un no, a la primera. Y lo vacío sigue siendo un no.
        for (respuesta in listOf("no, déjalo", "")) {
            val negada = corrida(MIRA, *dosTurnos, fin(), canal = Canal(respuesta))
            assertEquals(1, negada.preguntas.size, promesa(p) + " · «$respuesta» repreguntó: ${negada.preguntas}")
            assertEquals(emptyList(), negada.entradas, promesa(p) + " · «$respuesta» dejó pasar la acción")
        }

        // Y un sí claro sigue siendo un sí, a la primera: ni se repregunta ni se deja de hacer.
        val clara = corrida(MIRA, *dosTurnos, fin(), canal = Canal("sí, mándaselo"))
        assertEquals(1, clara.preguntas.size, promesa(p) + " · repreguntó un sí claro: ${clara.preguntas}")
        assertEquals(listOf("sendSms"), clara.entradas, promesa(p) + " · no hizo lo autorizado")
    }

    @Test
    fun promesa616() = corre {
        val p = 616
        // Dos destinatarios en el pedido: el número de la acción está en el texto, pero nadie sabe si es el de ESTA
        // acción —el cerebro pudo cruzarlos—, así que se pregunta. Su contenido sí sale del pedido: lo único que
        // decide el caso es el destinatario.
        val cruzados = corrida(
            DOS_DESTINOS,
            BrainTurn(actions = listOf(sms(numero = NUMERO_DE_LA_HERMANA, texto = PARA_LA_HERMANA))),
            fin(),
        )
        assertEquals(emptyList(), cruzados.entradas, promesa(p) + " · un pedido con dos destinatarios autorizó el mensaje")
        assertEquals(1, cruzados.preguntas.size, promesa(p) + " · no preguntó: ${cruzados.preguntas}")

        // Con un solo destinatario posible sí se sabe cuál es el de la acción: eso tiene que seguir pasando sin preguntar.
        val uno = corrida(MANDA_AL_NUMERO, BrainTurn(actions = listOf(sms(numero = NUMERO_CON_PREFIJO))), fin())
        assertEquals(listOf("sendSms"), uno.entradas, promesa(p) + " · el único número del pedido no autorizó su propia acción")
        assertEquals(emptyList(), uno.preguntas, promesa(p) + " · preguntó por el único destinatario del pedido")
    }

    @Test
    fun promesa617() = corre {
        val p = 617
        // La llave de lo ya contestado no guarda el contenido: un correo largo o el texto de toda la pantalla dejaban
        // su copia entera en memoria durante la corrida.
        val enorme = KINVARA.repeat(4_000)
        assertTrue(huella(enorme).length <= TOPE_DE_HUELLA,
            promesa(p) + " · la huella de un contenido de ${enorme.length} caracteres mide ${huella(enorme).length}")

        // Y sigue distinguiendo dos contenidos distintos, que es lo que promete la 610.
        assertTrue(huella(KINVARA) != huella(OTRO_TEXTO), promesa(p) + " · dos contenidos distintos comparten huella")
        assertTrue(huella(enorme) != huella(enorme + "x"), promesa(p) + " · dos contenidos largos distintos comparten huella")

        // Nada del contenido vive en la huella: si un día la llave saliera al log, no filtraría lo que el log no filtra.
        val suya = huella(KINVARA)
        val texto = plano(KINVARA)
        for (i in 0..texto.length - 4) {
            val trozo = texto.substring(i, i + 4)
            assertTrue(trozo !in suya, promesa(p) + " · la huella lleva «$trozo» del contenido")
        }

        // El mismo contenido con otro espaciado es el mismo contenido: ni cambia la huella, ni se vuelve a preguntar.
        assertEquals(huella(KINVARA), huella(KINVARA_ESPACIADO), promesa(p) + " · el espaciado cambió la huella")
        val compartir = corrida(
            MIRA,
            BrainTurn(actions = listOf(comparte(KINVARA_ESPACIADO))),
            BrainTurn(actions = listOf(comparte(KINVARA))),
            fin(),
            canal = Canal("sí, compártelo"),
        )
        assertEquals(listOf("shareText"), compartir.entradas, promesa(p) + " · lo autorizado no se hizo")
        assertEquals(1, compartir.preguntas.size,
            promesa(p) + " · volvió a preguntar por el mismo contenido con otro espaciado: ${compartir.preguntas}")
        assertTrue(compartir.diario.lineas.none { suya in it }, promesa(p) + " · la huella salió al log: ${compartir.diario.lineas}")
    }

    /**
     * UN DESTINO CORTO NO SE AUTORIZA POR COINCIDIR (promesa 618). La E4 empezó a comparar los destinos de menos de 7 cifras
     * por igualdad exacta, y con eso un número cualquiera del pedido —un monto— autorizaba un envío a un código corto que
     * valía lo mismo. Los códigos de bancos y operadoras son justo así: se vuelve al lado seguro de antes.
     */
    @Test
    fun promesa618() = corre {
        val p = 618
        // El caso con que el control saltó la compuerta: el pedido trae un MONTO de cinco cifras y la acción va a un código
        // corto que vale lo mismo. Coincidir en el largo y en el valor no dice que ese número sea un destinatario.
        val monto = corrida(EL_MONTO, BrainTurn(actions = listOf(sms(numero = CODIGO_DEL_MONTO, texto = PARA_MAMA))), fin())
        assertEquals(emptyList(), monto.entradas, promesa(p) + " · un monto del pedido autorizó el mensaje al código corto")
        assertEquals(1, monto.preguntas.size, promesa(p) + " · no preguntó: ${monto.preguntas}")
        assertEquals(1, monto.preguntasDeClase("permiso"), promesa(p) + " · ${monto.diario.lineas}")

        // Ni siquiera el código que el propio pedido nombra como destino: lo corto no autoriza, se pregunta.
        for ((que, numero) in listOf("el que el pedido nombra" to CORTO, "otro del mismo largo" to OTRO_CORTO)) {
            val corto = corrida(MANDA_AL_CORTO, BrainTurn(actions = listOf(sms(numero = numero))), fin())
            assertEquals(emptyList(), corto.entradas, promesa(p) + " · $que salió del teléfono sin preguntar")
            assertEquals(1, corto.preguntas.size, promesa(p) + " · $que no se preguntó: ${corto.preguntas}")
        }

        // Y lo que sí se puede comparar sigue pasando: el teléfono que el pedido trae autoriza su propia acción (609, 616).
        val telefono = corrida(MANDA_AL_NUMERO, BrainTurn(actions = listOf(sms(numero = NUMERO_CON_PREFIJO))), fin())
        assertEquals(listOf("sendSms"), telefono.entradas, promesa(p) + " · el teléfono del pedido dejó de autorizar su acción")
        assertEquals(emptyList(), telefono.preguntas, promesa(p) + " · preguntó por el único teléfono del pedido")
    }

    /**
     * UNA FECHA NO ES UN DESTINATARIO (promesa 620). El guion separa un número escrito a trozos, así que «2026-03-15» se leía
     * como una ristra de 8 cifras: un pedido con fecha y un teléfono inequívoco quedaba con dos destinatarios posibles y
     * preguntaba de más. Es el lado seguro, pero es fricción que no hace falta.
     */
    @Test
    fun promesa620() = corre {
        val p = 620
        val conFecha = corrida(CON_FECHA, BrainTurn(actions = listOf(sms(numero = NUMERO_CON_PREFIJO))), fin())
        assertEquals(listOf("sendSms"), conFecha.entradas, promesa(p) + " · la fecha contó como otro destinatario: ${conFecha.preguntas}")
        assertEquals(emptyList(), conFecha.preguntas, promesa(p) + " · preguntó de más por una fecha del pedido")

        // Y la puerta sigue cerrada: el mismo pedido no autoriza un número que no nombra…
        val otro = corrida(CON_FECHA, BrainTurn(actions = listOf(sms(numero = OTRO_NUMERO))), fin())
        assertEquals(emptyList(), otro.entradas, promesa(p) + " · el pedido con fecha autorizó un número que no nombra")
        assertEquals(1, otro.preguntas.size, promesa(p) + " · ${otro.preguntas}")

        // …ni la fecha misma vale como número con el que cruzar: si es lo único que trae, no hay con qué comparar.
        val soloFecha = corrida(SOLO_FECHA, BrainTurn(actions = listOf(sms())), fin())
        assertEquals(emptyList(), soloFecha.entradas, promesa(p) + " · una fecha del pedido autorizó el mensaje")
        assertEquals(1, soloFecha.preguntas.size, promesa(p) + " · ${soloFecha.preguntas}")
    }

    /* ---------- Lo que ayuda a juzgar ---------- */

    /** Cuánto puede medir una huella: lo que no depende del largo del contenido cabe de sobra aquí (promesa 617). */
    private val TOPE_DE_HUELLA = 40

    private fun <T> assertSingle(lista: List<T>, mensaje: String): T {
        assertEquals(1, lista.size, "$mensaje · $lista")
        return lista.single()
    }
}
