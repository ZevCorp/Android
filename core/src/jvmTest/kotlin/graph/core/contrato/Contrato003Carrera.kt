package graph.core.contrato

import graph.core.precision.Freno
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * Y dos vías que abren una corrida a la vez (la burbuja y la app principal) miraban «¿hay otra?» y abrían en dos pasos:
 * mirando fuera del candado, el revisor de la parte 2 midió 12 dobles aperturas en 20.000 pares de hilos.
 *
 * Solo jvm tiene hilos: por eso vive aquí y no en commonTest.
 */
class Contrato003Carrera {

    companion object {
        val PROMESAS = mapOf(
            319 to "Pedir el alto al mismo tiempo que termina la tarea nunca deja el freno armado sin tarea ni hace nacer parada a la siguiente, y dos vías que abren una corrida a la vez nunca abren las dos.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** Las frases escritas aquí y no leídas de producción. */
        const val ALTO = "Vale, paro."
        const val LISTO = "Listo, tienes el control de vuelta."

        /** Hasta aquí se estresa: lo que llegue antes, las vueltas o el tiempo. */
        const val VUELTAS = 1_000_000
        val TIEMPO = 3.seconds

        /** Las vías que abren a la vez: tres hilos que giran esperando la señal y el principal que la da, uno por núcleo. */
        const val VIAS = 3
        const val APERTURAS = 200_000
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

        // Dos vías que abren una corrida a la vez: mirar si hay otra y abrir es un solo paso del freno, así que nunca abren las
        // dos. Las vías viven toda la prueba, esperan girando a que todas estén listas y salen a la misma señal: con hilos
        // nuevos por par, la mayoría ni se cruzaba.
        val deLaVuelta = AtomicReference(Freno())
        val listas = AtomicInteger()
        val ya = AtomicBoolean(false)
        val abrieron = AtomicInteger()
        val basta = AtomicBoolean(false)
        val salen = CyclicBarrier(VIAS + 1)
        val llegan = CyclicBarrier(VIAS + 1)
        val vias = List(VIAS) {
            Thread {
                while (true) {
                    salen.await(5, TimeUnit.SECONDS)
                    if (basta.get()) break
                    listas.incrementAndGet()
                    while (!ya.get()) Thread.onSpinWait()
                    if (deLaVuelta.get().empiezaSiNoHayOtra("corrida")) abrieron.incrementAndGet()
                    llegan.await(5, TimeUnit.SECONDS)
                }
            }.apply { isDaemon = true; start() }
        }
        var aperturas = 0
        var dobles = 0
        var ninguna = 0
        val desde = TimeSource.Monotonic.markNow()
        try {
            while (aperturas < APERTURAS && desde.elapsedNow() < TIEMPO) {
                deLaVuelta.set(Freno()); abrieron.set(0); listas.set(0); ya.set(false)
                salen.await(5, TimeUnit.SECONDS)
                while (listas.get() < VIAS) Thread.onSpinWait()
                ya.set(true)
                llegan.await(5, TimeUnit.SECONDS)
                when (abrieron.get()) { 1 -> Unit; 0 -> ninguna++; else -> dobles++ }
                aperturas++
            }
        } finally {
            basta.set(true)
            runCatching { salen.await(5, TimeUnit.SECONDS) }
            vias.forEach { it.join(5_000) }
        }
        assertTrue(aperturas > 0, promesa(319) + " · ninguna vuelta de aperturas: no se juzgó nada")
        assertEquals(0, ninguna, promesa(319) + " · $ninguna veces ninguna de las $VIAS vías abrió la corrida en $aperturas vueltas")
        assertEquals(0, dobles, promesa(319) + " · $dobles veces dos vías abrieron la corrida a la vez en $aperturas vueltas")
    }
}
