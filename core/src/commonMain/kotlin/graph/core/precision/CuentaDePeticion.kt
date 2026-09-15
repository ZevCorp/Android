package graph.core.precision

import graph.core.domain.GraphLog
import kotlin.time.TimeSource

/** Quién habla. Solo la persona abre una petición ([abrePeticion]). */
enum class QuienHabla { PERSONA, SISTEMA }

/**
 * LA MEDIDA DE UNA PETICIÓN (spec 003, promesa 315): cuántas llamadas, cuántas herramientas distintas, el
 * máximo de intentos a un mismo destino y los milisegundos hasta la primera y la última acción que actuó.
 * Una línea `peticion:` en el log por petición.
 *
 * Nace de U (`CuentaDelTurno.cs`, promesa 205): sin una unidad «petición», saber si algo se hizo a la
 * primera era reconstruirlo sumando líneas sueltas con un script.
 *
 * EL DENOMINADOR ES LO PEDIDO. Lo que el tope rechazó y lo que se retiró también son llamadas: una cuenta
 * que solo sumara lo ejecutado encogería justo cuando la petición se enredó.
 *
 * «ACTUÓ» ES LO QUE SE SABE, NO LO QUE SE SUPONE. Quien llama decide si un resultado actuó; mejor un
 * «primera=—» honesto que un tiempo que mide una respuesta y la llama acción.
 *
 * Límite conocido, como el [Freno]: sin candados; dos entradas simultáneas desde hilos distintos podrían
 * contarse mal.
 */
class CuentaDePeticion(
    /** Reloj de las medidas; el contrato lo cambia por uno de prueba. */
    reloj: TimeSource = TimeSource.Monotonic,
    private val log: GraphLog = GraphLog { _, _ -> },
) {
    private val cero = reloj.markNow()
    private fun ahora() = cero.elapsedNow().inWholeMilliseconds

    private var primeraLlamada: Long? = null
    private var primera: Long? = null
    private var ultima: Long? = null
    private var peticion: Long? = null
    private var llamadas = 0
    private var rechazadas = 0
    private var retiradas = 0
    private val distintas = LinkedHashSet<String>()
    private val intentos = LinkedHashMap<String, Int>()

    /** Se pidió una herramienta. Cuenta aunque luego se rechace o se retire. [destino]: la clave del tope, o vacío. */
    fun llamada(herramienta: String, destino: String = "") {
        if (primeraLlamada == null) primeraLlamada = ahora()
        llamadas++
        distintas += herramienta
        if (destino.isNotBlank()) intentos[destino] = (intentos[destino] ?: 0) + 1
    }

    /** Una herramienta devolvió su resultado; [actuo] lo decide quien sabe leerlo. */
    fun resultado(herramienta: String, actuo: Boolean) {
        // Un resultado sin llamada en esta petición es de otra: no cuenta ni da tiempos negativos.
        if (!actuo || primeraLlamada == null) return
        val t = ahora()
        if (primera == null) primera = t
        ultima = t
    }

    fun rechazada(herramienta: String) { rechazadas++ }

    fun retirada(herramienta: String) { retiradas++ }

    /** La persona acaba de pedir algo: deja la medida de la anterior y mide desde aquí `desde_peticion`. */
    fun nuevaPeticion(): String? {
        val anterior = cerrar()
        peticion = ahora()
        return anterior
    }

    /** Emite la línea de la petición y vuelve a cero. `null` y sin línea si no hubo llamadas. */
    fun cerrar(): String? {
        if (llamadas == 0) {
            reiniciar()
            return null
        }
        val max = intentos.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()
        val linea = "llamadas=$llamadas distintas=${distintas.size} " +
            (if (max == null) "intentos_max=0 «—» " else "intentos_max=${max.value} «${max.key}» ") +
            "primera=${ms(primera, primeraLlamada)} ultima=${ms(ultima, primeraLlamada)} " +
            "desde_peticion=${ms(primera, peticion)} rechazadas=$rechazadas retiradas=$retiradas"
        reiniciar()
        log.log("peticion", linea)
        return linea
    }

    private fun ms(t: Long?, desde: Long?) = if (t != null && desde != null) "${t - desde} ms" else "—"

    /** A cero SIEMPRE, también sin llamadas: si no, lo que quedara de la anterior se colaría en la siguiente. */
    private fun reiniciar() {
        primeraLlamada = null; primera = null; ultima = null; peticion = null
        llamadas = 0; rechazadas = 0; retiradas = 0
        distintas.clear()
        intentos.clear()
    }
}

/**
 * EL ÚNICO SITIO QUE DECIDE QUÉ ABRE UNA PETICIÓN. La persona sí: deja la medida de la anterior y el
 * tope vuelve a cero. Un aviso del sistema no (en U el eco del altavoz vaciaba el tope a mitad de una
 * petición). Una llamada retirada tampoco: se cuenta con [CuentaDePeticion.retirada], no pasa por aquí.
 * Devuelve si abrió una.
 */
fun abrePeticion(quien: QuienHabla, tope: TopeDeIntentos?, cuenta: CuentaDePeticion?): Boolean {
    if (quien != QuienHabla.PERSONA) return false
    cuenta?.nuevaPeticion()
    tope?.nuevaPeticion()
    return true
}
