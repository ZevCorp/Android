package graph.core.pregunta

import graph.core.domain.AgentAction
import graph.core.domain.GraphLog
import graph.core.domain.ScreenState
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import kotlin.concurrent.Volatile

/**
 * LO QUE EL CLIENTE PUEDE VER AHORA: las etiquetas de la pantalla y las apps instaladas. Con esto se cuentan los candidatos
 * de un nombre ambiguo, y se ofrecen LOS QUE VIO: una lista inventada es peor que no preguntar (promesa 602).
 */
class Vista(val etiquetas: List<String> = emptyList(), val apps: List<String> = emptyList()) {
    companion object {
        /** Cómo escribe la pantalla lo que se ve (`GraphAccessibilityService.uiContext`, `app/…:213-214`). */
        const val ETIQUETAS = "etiquetas visibles: "
        const val SEPARADOR = " · "

        /** Lo que la pantalla dice cuando no hay ninguna etiqueta: no es una etiqueta. */
        private const val NINGUNA = "(ninguna)"

        /** Las etiquetas de un `uiContext`. Sin la línea que las lista, ninguna: el cliente no inventa lo que no vio. */
        fun etiquetasDe(uiContext: String): List<String> {
            val linea = uiContext.lines().firstOrNull { ETIQUETAS in it } ?: return emptyList()
            return linea.substringAfter(ETIQUETAS).split(SEPARADOR)
                .map { it.trim() }
                .filter { it.isNotBlank() && it != NINGUNA }
        }
    }
}

/**
 * PREGUNTA ANTES DE EJECUTAR (docs/specs/006). Va entre el motor y la acción, y decide en este orden:
 *  1. **permiso** — lo sensible ([AccionSensible]) que el pedido no autorizó no se ejecuta: se pregunta;
 *  2. **cuál** — un nombre que coincide con dos o más de los que vio se pregunta, ofreciendo esos;
 *  3. **dato** — un campo que falta se pide, uno por pregunta y el más importante primero.
 * El permiso va primero porque es el que evita el daño; los otros dos son para hacerlo bien.
 *
 * NO INTERPRETA LA RESPUESTA. Lo único que lee es si [Respuesta.niega]; el texto vuelve al cerebro dentro del `results` de la
 * acción frenada y el cerebro decide el turno siguiente (promesa 605). Así no hay bucle: un asunto se pregunta UNA vez por
 * corrida, y si el cerebro insiste, la acción pasa o no según lo que la persona contestó (promesa 606).
 *
 * MIENTRAS ESPERA NO HACE NADA. `revisa` no vuelve hasta que la persona contesta: la corrida queda viva y quieta, sin tope
 * ni plazo que la resuelva por su cuenta, y el alto de siempre la corta por donde corta todo lo demás (promesa 604).
 *
 * DE AQUÍ SOLO SALE MEDIDA. Al log van la clase de la pregunta y los largos; nunca el texto, la respuesta, el destinatario
 * ni las opciones, porque el log sale del teléfono por la telemetría (spec 005, promesa 608).
 */
class CompuertaDePregunta(
    private val usuario: UserChannel? = null,
    private val voz: Voice? = null,
    private val log: GraphLog = GraphLog { _, _ -> },
    /** Las apps instaladas. `suspend` y una sola vez por corrida: la consulta es cara (igual que en `GraphBrain`). */
    private val apps: suspend () -> List<String> = { emptyList() },
) {
    private var pedido = ""
    private var etiquetas: List<String> = emptyList()
    private var appsVistas: List<String>? = null

    /** Lo ya preguntado en esta corrida: el asunto y si quedó autorizado. */
    private val contestado = mutableMapOf<String, Boolean>()

    @Volatile private var enElAire: PreguntaPendiente? = null

    /** La pregunta que está en el aire: mientras existe, la corrida está viva y quieta (promesa 604). */
    val pendiente: PreguntaPendiente? get() = enElAire

    /** Corrida nueva: este es el pedido con el que se compara cada acción, y lo preguntado antes ya no cuenta. */
    fun empieza(pedido: String) {
        this.pedido = pedido
        contestado.clear()
        etiquetas = emptyList()
        appsVistas = null
        enElAire = null
    }

    /** La última pantalla que el motor leyó: de ahí salen las etiquetas que el cliente ve. */
    fun vio(estado: ScreenState) {
        etiquetas = Vista.etiquetasDe(estado.uiContext)
    }

    /**
     * `null` si la acción puede ejecutarse. Si no, el resultado de una acción que NO se hizo: delante del «—» va la medida
     * que el motor loguea; detrás, el detalle con la pregunta y la respuesta, que es para el cerebro y no sale del teléfono.
     */
    suspend fun revisa(accion: AgentAction): String? {
        val pregunta = decide(accion) ?: return null
        contestado[pregunta.asunto]?.let { autorizado ->
            if (autorizado) return null // ya lo autorizó: no se pregunta dos veces lo mismo
            log.log(TAG, "⏭ ${pregunta.clase.enLog} · $DIJISTE_QUE_NO")
            return "$DIJISTE_QUE_NO — ya te pregunté por esto en esta corrida y dijiste que no"
        }
        val canal = usuario
        if (canal == null) {
            // Sin a quién preguntarle, lo sensible NO se hace: «usa tu mejor criterio» no es un permiso (promesa 607).
            log.log(TAG, "❓ ${pregunta.clase.enLog} · $SIN_CANAL")
            contestado[pregunta.asunto] = false
            return "$SIN_CANAL — no la hice: no tengo cómo preguntarte «${pregunta.texto}»"
        }
        log.log(
            TAG,
            "❓ ${pregunta.clase.enLog} · pregunta de ${pregunta.texto.length} caracteres" +
                if (pregunta.opciones.isEmpty()) "" else " · ${pregunta.opciones.size} opciones",
        )
        enElAire = PreguntaPendiente(pregunta, accion)
        val dicho = try {
            voz?.speak(pregunta.texto)
            canal.ask(pregunta.texto)
        } finally {
            enElAire = null
        }
        val autoriza = !Respuesta.niega(dicho)
        contestado[pregunta.asunto] = autoriza
        log.log(TAG, "✔ respuesta de ${dicho.length} caracteres · ${if (autoriza) "autoriza" else "niega"}")
        val detalle = if (dicho.isBlank()) "no contestaste" else "contestaste «$dicho»"
        return "$PREGUNTE — te pregunté «${pregunta.texto}» y $detalle"
    }

    /* ---------- Las tres decisiones ---------- */

    private suspend fun decide(accion: AgentAction): Pregunta? = permiso(accion) ?: cual(accion) ?: dato(accion)

    /** Lo sensible que el pedido no autorizó. */
    private fun permiso(accion: AgentAction): Pregunta? {
        val sensible = AccionSensible.de(accion) ?: return null
        if (AccionSensible.loPidio(pedido, sensible)) return null
        return Pregunta(Pregunta.Clase.PERMISO, "permiso:${sensible.clase}:${plano(sensible.destino).trim()}", texto(sensible))
    }

    /** Un nombre que coincide con dos o más de los que el cliente vio. */
    private suspend fun cual(accion: AgentAction): Pregunta? {
        val nombrado = nombrado(accion) ?: return null
        val candidatos = candidatos(nombrado.nombre, if (nombrado.deApp) catalogo() else etiquetas)
        if (candidatos.size < 2) return null
        val texto = "Veo ${candidatos.size} que coinciden con «${nombrado.nombre}»: ${candidatos.joinToString(", ")}. ¿Cuál es?"
        return Pregunta(Pregunta.Clase.CUAL, "cual:${plano(nombrado.nombre).trim()}", texto, candidatos)
    }

    /** El primer campo que falta, que es el más importante de los que faltan. */
    private fun dato(accion: AgentAction): Pregunta? {
        if (accion !is AgentAction.Mcp) return null
        val campos = CAMPOS[accion.tool] ?: return null
        val falta = campos.firstOrNull { accion.args[it.nombre].isNullOrBlank() } ?: return null
        return Pregunta(Pregunta.Clase.DATO, "dato:${accion.tool}:${falta.nombre}", falta.pregunta)
    }

    /* ---------- Lo que se mira para decidir ---------- */

    /** Un nombre que el cliente puede resolver mirando: una app del catálogo o una etiqueta de la pantalla. */
    private class Nombrado(val nombre: String, val deApp: Boolean)

    private fun nombrado(accion: AgentAction): Nombrado? = when {
        accion is AgentAction.OpenApp -> Nombrado(accion.name.trim(), true).takeIf { accion.name.isNotBlank() }
        accion !is AgentAction.Mcp -> null
        accion.tool == "launch_app" -> accion.args["app"]?.trim()?.takeIf { it.isNotBlank() }?.let { Nombrado(it, true) }
        // Una aprendida toca por etiqueta: se mira la primera, que es por donde empieza (ver «límites conocidos»).
        else -> accion.args["taps"]?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotBlank() }?.let { Nombrado(it, false) }
    }

    /**
     * Los de [lista] que coinciden con [nombre]. Uno igual gana: «Nequi» con «Nequi» y «Nequi Empresas» delante no es una
     * duda, es el de siempre. Con ninguno tampoco se pregunta: no habría nada que ofrecer, y la acción falla como siempre.
     */
    private fun candidatos(nombre: String, lista: List<String>): List<String> {
        val buscado = plano(nombre).trim()
        if (buscado.isEmpty()) return emptyList()
        if (lista.any { plano(it).trim() == buscado }) return emptyList()
        return lista.filter { buscado in plano(it) }.take(OPCIONES_MAXIMAS)
    }

    /** El catálogo de apps, una vez por corrida. */
    private suspend fun catalogo(): List<String> = appsVistas ?: apps().also { appsVistas = it }

    /** Lo que se le dice a la persona. Nombra lo suyo —es su teléfono y su dato—; al log va solo la clase (promesa 608). */
    private fun texto(sensible: AccionSensible): String {
        val a = if (sensible.destino.isBlank()) "" else " a «${sensible.destino}»"
        return when (sensible.clase) {
            AccionSensible.Clase.MENSAJE -> "No me pediste mandar esto. ¿Se lo mando$a?"
            AccionSensible.Clase.LLAMADA -> "No me pediste llamar. ¿Llamo$a?"
            AccionSensible.Clase.BORRAR -> "Esto borra algo y no me lo pediste. ¿Lo hago$a?"
            AccionSensible.Clase.PAGO -> "Esto paga o compra algo y no me lo pediste. ¿Sigo$a?"
            AccionSensible.Clase.COMPARTIR -> "No me pediste compartir esto. ¿Lo comparto$a?"
            AccionSensible.Clase.AJUSTE -> "Esto cambia un ajuste que otros notan y no me lo pediste. ¿Lo hago$a?"
        }
    }

    /** Un campo que una herramienta necesita, y cómo se pide. El orden de la lista ES el de importancia. */
    private class Campo(val nombre: String, val pregunta: String)

    companion object {
        /** El tag de la compuerta en el log. De aquí solo sale medida (promesa 608). */
        const val TAG = "pregunta"

        /** Lo que el cerebro lee de una acción frenada. Antes del «—» va la medida; el detalle es para él. */
        const val PREGUNTE = "pregunté primero y no la hice"
        const val DIJISTE_QUE_NO = "dijiste que no"
        const val SIN_CANAL = "no hay a quién preguntarle"

        /** Cuántas opciones se le ofrecen a la persona: más que esto ya no se lee, se adivina. */
        const val OPCIONES_MAXIMAS = 6

        /**
         * Lo que cada herramienta necesita para hacer bien su trabajo, EN ORDEN DE IMPORTANCIA. Sin esto, `set_alarm` sin
         * hora pone las 8:00 y `set_timer` sin segundos pone 60 (`Model.kt:82-87`): un default silencioso es una tarea mal
         * hecha que nadie reporta.
         */
        private val CAMPOS: Map<String, List<Campo>> = mapOf(
            "set_alarm" to listOf(Campo("hour", "¿A qué hora la pongo?")),
            "set_timer" to listOf(Campo("seconds", "¿De cuánto tiempo lo pongo?")),
            "create_event" to listOf(Campo("start", "¿Para cuándo lo agendo?"), Campo("title", "¿Qué título le pongo?")),
            "send_sms" to listOf(Campo("number", "¿A quién le mando el mensaje?")),
            "send_email" to listOf(Campo("to", "¿A quién le mando el correo?")),
            "call" to listOf(Campo("number", "¿A quién llamo?")),
            "launch_app" to listOf(Campo("app", "¿Qué app abro?")),
            "web_search" to listOf(Campo("query", "¿Qué busco?")),
            "open_maps" to listOf(Campo("query", "¿Qué lugar busco?")),
            "directions" to listOf(Campo("destination", "¿A dónde vamos?")),
            "open_url" to listOf(Campo("url", "¿Qué página abro?")),
        )
    }
}
