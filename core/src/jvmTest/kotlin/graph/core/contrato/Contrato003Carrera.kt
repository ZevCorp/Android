package graph.core.contrato

import graph.core.precision.Freno
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * CONTRATO 003, REVISIÓN DE 3A-3C — EL ALTO CONTRA EL FIN DE LA TAREA, CON HILOS DE VERDAD
 * (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md, promesa 319).
 *
 * La píldora pide el alto desde el hilo de la UI; la corrida termina su tarea y empieza la siguiente en el suyo.
 * Sin sección crítica, `pide` leía «abierta», `termine` corría entero y `pide` armaba el alto después: el freno
 * quedaba armado sin tarea, el aviso «Vale, paro.» salía sin su «Listo, tienes el control de vuelta.», y la
 * tarea siguiente podía nacer con ese alto. Medido por el revisor de 3A: 492.958 altos sin devolver el control
 * en 3 millones de vueltas.
 *
 * Solo jvm tiene hilos: por eso vive aquí y no en commonTest.
 */
class Contrato003Carrera {

    companion object {
        val PROMESAS = mapOf(
            319 to "Pedir el alto al mismo tiempo que termina la tarea nunca deja el freno armado sin tarea ni hace nacer parada a la siguiente.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** Las frases escritas aquí y no leídas de producción. */
        const val ALTO = "Vale, paro."
        const val LISTO = "Listo, tienes el control de vuelta."

        /** Hasta aquí se estresa: lo que llegue antes, las vueltas o el tiempo. */
        const val VUELTAS = 1_000_000
        val TIEMPO = 3.seconds
    }

    @Test
    fun promesa319() {
        // Cada alto que se avisa es de una tarea abierta y esa misma tarea lo devuelve al soltar: los avisos
        // alternan «alto, listo, alto, listo». Un alto sin su «listo» es un freno que quedó armado sin tarea y
        // que la tarea siguiente heredaría; un «listo» antes de su alto, un alto armado a destiempo.
        val cerrojo = Any()
        var altos = 0
        var listos = 0
        var fueraDeOrden = 0
        var ultimo = LISTO
        val freno = Freno(avisa = { texto ->
            synchronized(cerrojo) {
                if (texto == ALTO) { altos++; if (ultimo != LISTO) fueraDeOrden++ }
                if (texto == LISTO) { listos++; if (ultimo != ALTO) fueraDeOrden++ }
                ultimo = texto
            }
        })

        val fin = AtomicBoolean(false)
        val pildora = Thread { while (!fin.get()) freno.pide("píldora") }.apply { isDaemon = true; start() }
        var vueltas = 0
        var armadoSinTarea = 0
        val inicio = TimeSource.Monotonic.markNow()
        try {
            while (vueltas < VUELTAS && inicio.elapsedNow() < TIEMPO) {
                freno.empezar("tarea $vueltas")
                freno.termine()
                // Nadie más abre tareas: tras soltar, ni la tarea sigue abierta ni un alto queda armado.
                if (freno.pedido || freno.abierta) armadoSinTarea++
                vueltas++
            }
        } finally {
            fin.set(true)
            pildora.join(5_000)
        }
        val (a, l, f) = synchronized(cerrojo) { Triple(altos, listos, fueraDeOrden) }

        assertTrue(a > 0, promesa(319) + " · en $vueltas vueltas la píldora no llegó a pedir ningún alto: no se juzgó nada")
        assertEquals(0, armadoSinTarea, promesa(319) + " · $armadoSinTarea veces el freno quedó armado sin tarea en $vueltas vueltas")
        assertEquals(a, l, promesa(319) + " · $a altos y $l veces se devolvió el control en $vueltas vueltas")
        assertEquals(0, f, promesa(319) + " · $f avisos fuera de orden en $vueltas vueltas ($a altos)")

        // Y sin nadie pidiendo, la siguiente nace suelta y deja pasar.
        freno.empezar("la siguiente")
        assertTrue(freno.abierta && !freno.pedido, promesa(319) + " · la tarea siguiente nació parada")
        freno.termine()
    }
}
