package graph.core.precision

import graph.core.application.ExecutionEngine
import graph.core.application.WorkflowRunner
import graph.core.domain.Brain
import graph.core.domain.ExecutionMode
import graph.core.domain.Gestures
import graph.core.domain.GraphLog
import graph.core.domain.LearnedTool
import graph.core.domain.Mcp
import graph.core.domain.McpTool
import graph.core.domain.Phone
import graph.core.domain.SystemApi
import graph.core.domain.UiPlayer
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile

/**
 * EL ÚNICO ARMADO DE UNA CORRIDA (spec 003, fase 3B). Recibe las manos crudas de la plataforma —el teléfono,
 * los gestos, el sistema y el reproductor por etiqueta— y devuelve el motor, el MCP y el reproductor de
 * workflows construidos SOBRE LAS VISTAS DE LA [Puerta], con el [freno] dentro del motor. Las manos crudas
 * entran aquí y no salen: nadie más construye un `ExecutionEngine`, un `Mcp` ni un `WorkflowRunner`
 * (promesa 307). Así una vía nueva de ejecutar no puede olvidarse de la puerta: no tiene con qué saltársela.
 *
 * Y ES DONDE SE CORRE Y SE PARA. [correr] abre la tarea de la corrida; [parar] pide el alto de la tarea
 * abierta, venga de la píldora, la notificación o la voz: un freno, una puerta (promesa 308). Si tras el
 * alto un turno de red no suelta, [cortaSiNoSuelta] corta el trabajo DESPUÉS del alto, nunca en su lugar.
 *
 * UNA CORRIDA DE FUERA A LA VEZ. [correr] no abre una corrida encima de otra: la segunda lanza [CorridaEnCurso]
 * sin pedir un turno ni tocar (promesa 318). Antes corría anidada en la primera: cuando la primera cerraba la tarea,
 * la segunda seguía pagando turnos a Graph sin poder pararse. Lo único que anida es [pasoConsciente].
 *
 * UNA CORRIDA DE FUERA ES UNA PETICIÓN, AUNQUE ARME MUCHAS PUERTAS. Cada [arma] hace una puerta nueva (cada ronda, cada
 * paso consciente, el catálogo), pero el [tope] y la [cuenta] son los del armado. [correr] abre la petición al abrir la
 * corrida y la cierra al acabarla, con su línea `peticion:`; un paso consciente no devuelve el tope a cero (promesa 320).
 * Antes cada puerta nacía sin tope: el paso consciente de un workflow podía insistir por tercera vez en el mismo botón.
 */
class ArmadoDeEjecucion(
    val freno: Freno,
    private val log: GraphLog = GraphLog { _, _ -> },
    /**
     * Cómo lanzar el corte tras el alto sin detener a quien lo pidió (la app: su scope de proceso; la píldora pide
     * desde el hilo de la UI). Sin él, [parar] solo pide el alto.
     */
    private val lanza: ((suspend () -> Unit) -> Unit)? = null,
    /** Lo que se deja a la corrida soltar sola tras el alto antes de cortar su trabajo. */
    private val graciaMs: Long = GRACIA_MS,
    /** Dos intentos y no tres (3C), compartido por todas las puertas que arma: la app le da uno por proceso. */
    private val tope: TopeDeIntentos? = null,
    /** La medida de la petición (3C), compartida igual que [tope]. */
    private val cuenta: CuentaDePeticion? = null,
) {
    companion object {
        /**
         * La gracia tras el alto. La corrida suelta en milisegundos salvo que esté esperando un turno de Graph en
         * vuelo: ese es el único caso que llega a cortarse.
         */
        const val GRACIA_MS = 1_500L

        /** El nombre de la tarea de una corrida de fuera: nunca el pedido, que es lo que la persona dijo (promesa 317). */
        const val CORRIDA = "corrida"

        /** El nombre de la tarea de un paso consciente cuando corre suelto, fuera de una corrida. */
        const val PASO_CONSCIENTE = "paso consciente"
    }

    /** Las manos crudas de la plataforma. Entran al armado y no salen: afuera solo circulan las vistas de la puerta. */
    class Manos(val telefono: Phone, val gestos: Gestures, val sistema: SystemApi, val reproductor: UiPlayer?)

    /** Los workflows que el MCP expone como herramientas y cómo se reproducen. */
    class Workflows(
        val lista: List<Workflow>,
        /** Las etiquetas tocables de la pantalla actual. Leer no es actuar: no pasa por la puerta. */
        val elementos: suspend () -> List<String>,
        /** Un paso consciente: quien lo implementa arma su motor con [arma] y lo corre con [pasoConsciente]. */
        val consciente: suspend (Workflow, WorkflowStep, String) -> Boolean,
    )

    /** Lo armado para una corrida: el motor, el MCP del que sale el catálogo del cerebro, y el cerebro. */
    class Sesion<B : Brain>(val motor: ExecutionEngine, val mcp: Mcp, val cerebro: B)

    /** El trabajo de la corrida de fuera, para cortarlo si tras el alto no suelta. */
    @Volatile private var trabajo: Job? = null

    /** ¿Hay una corrida en marcha? Es lo mismo que una tarea abierta: sin corrida no hay nada que parar. */
    val enCurso: Boolean get() = freno.abierta

    /**
     * Arma una corrida sobre una puerta nueva a las [manos], con el [tope] y la [cuenta] del armado: una puerta nueva no es
     * una petición nueva. El cerebro se crea con el MCP ya armado: su catálogo sale de ahí.
     */
    fun <B : Brain> arma(
        manos: Manos,
        cerebro: (Mcp) -> B,
        voz: Voice,
        usuario: UserChannel? = null,
        maxTurnos: Int = 40,
        modo: ExecutionMode? = null,
        pausa: () -> Long = { 350 },
        aprendidas: List<LearnedTool> = emptyList(),
        workflows: Workflows? = null,
    ): Sesion<B> {
        val puerta = puerta(manos)
        val reproductor = workflows?.let {
            WorkflowRunner(
                player = puerta.reproductor, elements = it.elementos, conscious = it.consciente,
                mode = modo, stepDelay = pausa, log = log,
            )
        }
        val mcp = Mcp(
            puerta.gestos, puerta.sistema, aprendidas, puerta.reproductor, pausa, log,
            workflows = workflows?.lista ?: emptyList(), workflowExecutor = reproductor,
        )
        val elCerebro = cerebro(mcp)
        val motor = ExecutionEngine(
            brain = { elCerebro }, phone = puerta.telefono, mcp = mcp, user = usuario, voice = voz, log = log,
            maxTurns = maxTurnos, mode = modo, stepDelay = pausa, freno = freno,
        )
        return Sesion(motor, mcp, elCerebro)
    }

    /** El catálogo MCP tal como lo vería una corrida, armado sobre la puerta (para anticipar, sin ejecutar). */
    fun herramientas(manos: Manos, aprendidas: List<LearnedTool>): List<McpTool> {
        val puerta = puerta(manos)
        return Mcp(puerta.gestos, puerta.sistema, aprendidas, puerta.reproductor, log = log).tools
    }

    private fun puerta(manos: Manos) =
        Puerta(freno, manos.telefono, manos.gestos, manos.sistema, manos.reproductor, log, tope = tope, cuenta = cuenta)

    /**
     * Corre [bloque] como la corrida de fuera de [pedido]: abre la tarea, recuerda su trabajo para [cortaSiNoSuelta]
     * y, si hubo alto, termina en [Paraste] aunque el motor ya haya devuelto «paraste: …»: quien la llamó no sigue
     * con lo de después (reencaminar, anticipar).
     *
     * CON OTRA CORRIDA ABIERTA NO ABRE: lanza [CorridaEnCurso] sin correr el bloque. Mirar y abrir es un solo paso
     * del freno. El paso consciente de un workflow no entra por aquí: anida con [pasoConsciente].
     *
     * EL PEDIDO NO SE NOMBRA. La tarea se llama [CORRIDA] y el log dice el largo del pedido: el log sale del
     * teléfono por la telemetría (promesa 317).
     *
     * ABRE Y CIERRA LA PETICIÓN. Abierta la tarea, [abrePeticion] como la persona: el [tope] vuelve a cero y la [cuenta]
     * mide desde aquí. Al acabar —bien, parada o reventada— la cuenta deja su línea, dentro de la sesión de telemetría de
     * esta corrida y no al empezar la siguiente. Una corrida rechazada encima no abre ni cierra nada (promesa 320).
     *
     * ABIERTA LA TAREA, NADA FUERA DEL `try`. Un log que revienta entre abrir y el `try` dejaba la corrida en curso para
     * siempre: ninguna otra se abría (promesa 318).
     */
    suspend fun <T> correr(pedido: String, bloque: suspend () -> T): T {
        val suyo = currentCoroutineContext()[Job]
        if (!freno.empiezaSiNoHayOtra(CORRIDA)) {
            log.log("freno", "${CorridaEnCurso.MENSAJE}: no abro otra corrida encima (pedido de ${pedido.length} caracteres)")
            throw CorridaEnCurso()
        }
        try {
            trabajo = suyo
            log.log("freno", "tarea abierta «$CORRIDA» (pedido de ${pedido.length} caracteres)")
            abrePeticion(QuienHabla.PERSONA, tope, cuenta)
            return bloque().also { sigue() }
        } finally {
            // Soltar puede lanzar una cancelación (un aviso cancelado sale de `termine`, 306): lo de detrás corre igual y la
            // cancelación sigue su curso. Si no, la cuenta no cerraba y su línea salía en la corrida siguiente (322).
            try {
                freno.termine()
            } finally {
                if (trabajo === suyo) trabajo = null
                cuenta?.cerrar()
            }
        }
    }

    /**
     * Pide el alto de la corrida en curso. Toda orden de parar entra aquí; sin corrida no arma nada (promesa 302).
     * Con la corrida en curso lanza [cortaSiNoSuelta] con la gracia: pedir el alto primero, cortar solo si no suelta.
     */
    fun parar(porque: String) {
        freno.pide(porque)
        if (enCurso) lanza?.invoke { cortaSiNoSuelta(graciaMs) }
    }

    /** Con el alto pedido no se sigue: lanza [Paraste]. Lo mira quien tiene algo más que hacer tras un motor. */
    fun sigue() {
        if (freno.pedido) throw Paraste(PARASTE_TU)
    }

    /**
     * Tras [parar]: si pasados [graciaMs] la misma corrida sigue viva con el alto pedido, corta su trabajo.
     * El motor mira el freno entre acciones y en cada espera, así que suelta en milisegundos; lo que no mira
     * es un turno de Graph en vuelo, que puede tardar minutos. Ese turno se abandona (spec 001, promesa 14).
     */
    suspend fun cortaSiNoSuelta(graciaMs: Long) {
        val suyo = trabajo ?: return
        if (!freno.pedido) return
        delay(graciaMs)
        if (trabajo === suyo && suyo.isActive && freno.pedido) {
            log.log("freno", "la corrida no soltó en $graciaMs ms tras el alto (un turno colgado en red): corto su trabajo")
            suyo.cancel(Paraste(PARASTE_TU))
        }
    }

    /**
     * Un paso consciente de un workflow: corre [motor] dentro de la corrida. Si lo paraste dentro NO devuelve
     * «paso hecho»: lanza [Paraste] y la corrida entera termina, sin seguir con el paso siguiente (promesa 316).
     * Un fallo que no es parada se queda en el paso: `false`, y el workflow decide. No abre petición: sigue la de la
     * corrida, con su tope y su cuenta (promesa 320).
     */
    suspend fun pasoConsciente(objetivo: String, motor: ExecutionEngine): Boolean = freno.enTarea(PASO_CONSCIENTE) {
        try {
            motor.run(objetivo, announce = false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            log.log("workflow", "step consciente falló (${t::class.simpleName})")   // el mensaje puede nombrar la pantalla
            return@enTarea false
        }
        sigue()
        true
    }
}

/** Ya hay una corrida de fuera abierta: la segunda no se abre encima (promesa 318). No es una cancelación: no llegó a correr. */
class CorridaEnCurso : IllegalStateException(MENSAJE) {
    companion object {
        /** Lo que se le dice a la persona. */
        const val MENSAJE = "ya hay una tarea en curso"
    }
}
