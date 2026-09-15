package graph.core.voz

/**
 * LOS TURNOS DE UNA VOZ QUE NO LOS MARCA (docs/specs/002): cuándo empieza una petición del usuario y
 * cuándo toca dar el turno por cerrado, a falta de speech_started y response.done. Espejo de
 * `U-Windows-App/windows-client/src/Voice/TurnosSinMarca.cs`.
 *
 * QUÉ SUJETA EL TURNO: lo dicho por los dos, la voz de Ü mientras SUENA, y el trabajo en marcha — una
 * llamada entre que se pide y se devuelve no deja cerrar, y devolverla cuenta como actividad, porque es
 * cuando el delegado sigue. El audio en silencio NO cuenta: el servidor lo manda sin parar.
 *
 * EL SILENCIO SON 2000 ms, MEDIDOS AL BORDE: entre devolver una herramienta y lo siguiente que dice Ü
 * pasaron 1566-1733 ms y una vez 2009. Con 1500 el turno se cerraba a mitad de la tarea.
 *
 * Sin reloj del sistema, para juzgarlo sin esperar. SIN CANDADO, a diferencia de Windows: commonMain no
 * tiene `synchronized`, y quien lo use (A2) lo confina a un solo hilo o corrutina.
 */
class TurnosSinMarca(private val reloj: () -> Long, private val silencioMs: Long = SILENCIO_MS) {

    companion object {
        const val SILENCIO_MS = 2000L

        /** Desde qué pico un trozo es voz: el silencio del servidor pica en 45 y la palabra más floja en 1152. */
        const val PICO_DE_VOZ = 1000

        /** Cuántas devoluciones sin oír se recuerdan. Una tanda del delegado trae una o dos. */
        const val DEVUELTAS_QUE_SE_RECUERDAN = 64
    }

    /** Cuándo pasó por última vez algo que sujeta el turno desde el último cierre; null si nada. */
    private var ultimaActividad: Long? = null

    /** Hay una petición del usuario que Ü todavía no ha contestado con un cierre de por medio. */
    private var peticionAbierta = false

    /** Ü habló después de lo último que dijo el usuario. */
    private var uContesto = false

    /** Pedidas y sin devolver, POR INSTANCIA: la conversación devuelve las mismas que llegaron, y un call_id puede venir vacío. */
    private val enCurso = mutableListOf<Llamada>()

    /** Devueltas antes de oírse: el hilo que ejecuta puede ganarle al que recibe. */
    private val devueltasSinOir = mutableListOf<Llamada>()

    /** Llamadas pedidas y todavía sin devolver. */
    val llamadasEnCurso: Int get() = enCurso.size

    /** Un hecho que llegó del servidor. Verdadero si abre una petición nueva del usuario. */
    fun oye(hecho: Hecho): Boolean {
        when (hecho) {
            // UNA PAUSA SIN RESPUESTA NO ES OTRA PETICIÓN: lo dicho tras un cierre abre turno solo si Ü había
            // contestado. «Contestado» no es «habló desde el último cierre»: lo que contesta Ü cae DENTRO del
            // turno sintético, que solo se cierra cuando callan los dos.
            is Hecho.DiceElUsuario -> {
                ultimaActividad = reloj()
                uContesto = false
                if (peticionAbierta) return false
                peticionAbierta = true
                return true
            }

            // Lo que dice Ü sujeta el turno y lo contesta, pero no es una petición.
            is Hecho.DiceU -> {
                ultimaActividad = reloj()
                if (peticionAbierta) uContesto = true
            }

            // LA VOZ MIENTRAS SUENA: su transcripción llega 650-750 ms antes de que calle. Solo alarga lo que
            // ya hay: sonido sin nada dicho no abre un turno que cerrar.
            is Hecho.Suena -> if (ultimaActividad != null && pico(hecho.pcm) > PICO_DE_VOZ) ultimaActividad = reloj()

            is Hecho.Pide -> for (llamada in hecho.llamadas) {
                if (!devueltasSinOir.quitar(llamada)) enCurso.poner(llamada)
            }

            else -> {}
        }
        return false
    }

    /** Las llamadas de un Pide ya contestadas (o descartadas). El silencio vuelve a contar desde aquí. */
    fun devuelta(llamadas: List<Llamada>) {
        var eranDeEsteTurno = false
        for (llamada in llamadas) {
            if (enCurso.quitar(llamada)) {
                eranDeEsteTurno = true
                continue
            }
            if (devueltasSinOir.size >= DEVUELTAS_QUE_SE_RECUERDAN) devueltasSinOir.clear()
            devueltasSinOir.poner(llamada)
        }
        // Una devolución de otra sesión, sin nada dicho en esta, no abre un turno que cerrar.
        if (eranDeEsteTurno || ultimaActividad != null) ultimaActividad = reloj()
    }

    /** Verdadero UNA vez cuando hubo algo y lleva el silencio entero sin nada nuevo ni llamadas en curso. */
    fun tocaCerrar(): Boolean {
        if (enCurso.isNotEmpty()) return false
        val ultima = ultimaActividad ?: return false
        if (reloj() - ultima < silencioMs) return false
        ultimaActividad = null
        if (uContesto) {
            peticionAbierta = false
            uContesto = false
        }
        return true
    }

    private fun MutableList<Llamada>.poner(llamada: Llamada) {
        if (none { it === llamada }) add(llamada)
    }

    private fun MutableList<Llamada>.quitar(llamada: Llamada): Boolean {
        val i = indexOfFirst { it === llamada }
        if (i < 0) return false
        removeAt(i)
        return true
    }
}
