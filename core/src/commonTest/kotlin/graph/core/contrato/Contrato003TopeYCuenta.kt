package graph.core.contrato

import graph.core.contrato.Contrato003FrenoYPuerta.Bitacora
import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.domain.GraphLog
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.domain.UiPlayer
import graph.core.precision.CuentaDePeticion
import graph.core.precision.Freno
import graph.core.precision.NodoVivo
import graph.core.precision.Puerta
import graph.core.precision.QuienHabla
import graph.core.precision.TopeDeIntentos
import graph.core.precision.TopeDeIntentos.Destino
import graph.core.precision.TopeDeIntentos.Salida
import graph.core.precision.abrePeticion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * CONTRATO 003, FASE 3C — DOS INTENTOS Y NO TRES, Y LA MEDIDA DE CADA PETICIÓN
 * (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md, promesas 310-315).
 *
 * Nacen de TopeDeIntentos.cs y CuentaDelTurno.cs de U (promesas 204, 205 y 207). Se escribieron ANTES
 * que el código: nacieron rojas. Juzgan la regla pura y, donde importa, la [Puerta] de verdad con un
 * teléfono falso que tiene nodos vivos, textos visibles y una huella que los incluye.
 *
 * La lección de U que las sostiene: su «Guardar» que no cambia la pantalla se frenaba a la tercera, y
 * con una huella sin textos lo mismo le pasaría a la persona que aprieta tres veces el «7» de la
 * calculadora. Por eso la 312 juzga con una calculadora cuya huella sin textos NO cambia.
 */
class Contrato003TopeYCuenta {

    companion object {
        val PROMESAS = mapOf(
            310 to "Dos intentos y no tres: en una misma petición, la tercera entrada hacia un destino que ya falló dos veces no toca el teléfono y dice qué salió en cada una.",
            311 to "El destino es el nodo realmente tocado, se pida como se pida; sin nodo bajo el punto, la misma celda de 48 dp es el mismo destino; al escribir, el campo cuenta tal como se pidió.",
            312 to "Un intento se logra si la acción se dio y escribió o cambió la pantalla, textos incluidos; tocar tres veces un botón que cambia el texto visible nunca se bloquea; una lista de homónimos no es intento.",
            313 to "Elegir por número solo vale con una lista previa de ese nombre y se compara como número; sin lista, el número no abre un destino nuevo.",
            314 to "Una petición nueva devuelve el tope a cero; un aviso del sistema o una llamada retirada no.",
            315 to "Cada petición deja una línea `peticion:` con su medida; lo rechazado y lo retirado también cuentan; sin llamadas no se emite.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** 48 dp a densidad 3 (xxhdpi). */
        const val CELDA = 144

        /** Las frases escritas aquí y no leídas de producción: si alguien las cambia allí, esto se pone rojo. */
        const val TERCERA = "no lo intento una tercera vez: "
        const val CAMBIA_DE_VIA = "Cambia de vía: mira la pantalla y toca otra cosa, o dile al usuario qué está pasando."
        const val OTRO_CANDIDATO = "prueba OTRO candidato con which"

        fun nodo(selector: String, etiqueta: String, l: Int, t: Int, r: Int, b: Int, tipo: String = "Button") =
            NodoVivo(selector, etiqueta, tipo, NodoVivo.Bounds(l, t, r, b), accionable = true)

        val GUARDAR = nodo("a11y:id=save;text=Guardar;cls=Button;path=0.2.4", "Guardar", 400, 800, 700, 1000)
        val CANCELAR = nodo("a11y:id=cancel;text=Cancelar;cls=Button;path=0.2.3", "Cancelar", 0, 0, 300, 200)
        val SIETE = nodo("a11y:id=digit_7;text=7;cls=Button;path=0.3.1", "7", 0, 1500, 360, 1700)
        val CAMPO = nodo("a11y:id=name;cls=EditText;path=0.1", "Nombre", 0, 300, 1080, 440, tipo = "EditText")

        const val S1 = "a11y:id=tab_dl;text=Descargas;cls=Tab;path=0.0.1"
        const val S2 = "a11y:id=row_dl;text=Descargas;cls=TextView;path=0.4.2"
        const val LISTA = "hay 2 «Descargas» vivas: 1) Tab (120,90) · 2) TextView (540,900)"

        val NO_CAMBIO = Salida.Intento(dio = true, escribio = false, cambio = false, queSalio = "se dio y la pantalla no cambió")
    }

    /* ---------- El teléfono a mano: nodos vivos, textos visibles y una huella que los incluye ---------- */

    /**
     * Un teléfono falso con árbol vivo. [alTocar] decide si el toque se dio y puede dejar un cambio
     * [pendiente]: la pantalla real no se actualiza al instante, y el cambio solo se ve tras [asienta].
     */
    class Telefono(
        vararg vivos: NodoVivo,
        val textos: MutableList<String> = mutableListOf(),
        val alTocar: Telefono.(NodoVivo?) -> Boolean = { true },
        val alEscribir: Telefono.() -> Boolean = { true },
        val alTocarEtiqueta: Telefono.(String) -> Boolean = { true },
    ) {
        val nodos = vivos.toList()
        val entradas = mutableListOf<String>()
        var pendiente: (() -> Unit)? = null

        fun nodoEn(x: Int, y: Int): NodoVivo? = nodos
            .filter { x in it.bounds.left until it.bounds.right && y in it.bounds.top until it.bounds.bottom }
            .minByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top) }

        fun asienta() { pendiente?.invoke(); pendiente = null }

        /** Lo que no son textos vivos: paquete, ventana y nodos. */
        fun estructura() = "com.miui.calculator · Calculadora | " + nodos.joinToString(" · ") { it.selector }

        /** La huella de la decisión de 3C: con los textos visibles dentro. */
        fun huella() = estructura() + " | textos: " + textos.joinToString(" · ")

        val telefono = object : Phone {
            override suspend fun state(withScreenshot: Boolean) = ScreenState("com.miui.calculator · Calculadora", huella(), 1080, 2400)
            override suspend fun tap(x: Int, y: Int): Boolean { entradas += "tap"; return alTocar(nodoEn(x, y)) }
            override suspend fun type(x: Int, y: Int, text: String): Boolean { entradas += "type"; return alEscribir() }
            override suspend fun openApp(query: String): Boolean { entradas += "openApp"; return true }
            override suspend fun scroll(down: Boolean): Boolean { entradas += "scroll"; return true }
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean { entradas += "swipe"; return true }
            override suspend fun pressKey(key: String): Boolean { entradas += "pressKey"; return true }
        }
        val reproductor = object : UiPlayer {
            override suspend fun tapLabel(label: String): Boolean { entradas += "tapLabel"; return alTocarEtiqueta(label) }
        }
    }

    private fun puerta(
        tel: Telefono,
        log: GraphLog,
        tope: TopeDeIntentos?,
        cuenta: CuentaDePeticion? = null,
        conNodos: Boolean = true,
        conHuella: Boolean = true,
        asentar: suspend () -> Unit = { tel.asienta() },
    ): Puerta {
        val freno = Freno().also { it.empezar("una petición") }
        val mano = Mano()
        return Puerta(
            freno, tel.telefono, mano.gestos, mano.sistema, tel.reproductor, log,
            nodoEn = if (conNodos) ({ x, y -> tel.nodoEn(x, y) }) else null,
            huella = if (conHuella) ({ tel.huella() }) else null,
            tope = tope,
            cuenta = cuenta,
            asentar = asentar,
        )
    }

    private fun Bitacora.rechazos() = lineas.filter { it.startsWith("tope: no paso «") }

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa310() = corre {
        // La regla, pura: qué salió en cada una, y la vía.
        run {
            val tope = TopeDeIntentos(CELDA)
            val guardar = tope.alTocar(550, 900, GUARDAR)
            assertNull(tope.rechazo(guardar), promesa(310) + " · el primero no pasó")
            tope.despues(guardar, NO_CAMBIO)
            assertNull(tope.rechazo(guardar), promesa(310) + " · el segundo no pasó")
            tope.despues(guardar, Salida.Revento("reventó: sin servicio de accesibilidad"))
            assertEquals(
                TERCERA + "«Guardar» ya falló dos veces en esta petición — 1) se dio y la pantalla no cambió · " +
                    "2) reventó: sin servicio de accesibilidad. " + CAMBIA_DE_VIA,
                tope.rechazo(guardar),
                promesa(310),
            )
            assertNull(tope.rechazo(tope.alTocar(100, 100, CANCELAR)), promesa(310) + " · otro destino heredó el castigo")
        }

        // Por la puerta de verdad: tocar, escribir y tocar por etiqueta.
        val bitacora = Bitacora()
        val tel = Telefono(GUARDAR, CANCELAR, alEscribir = { false }, alTocarEtiqueta = { false })
        val p = puerta(tel, bitacora, TopeDeIntentos(CELDA))

        assertTrue(p.telefono.tap(550, 900), promesa(310))
        assertTrue(p.telefono.tap(560, 910), promesa(310))
        assertFalse(p.telefono.tap(550, 900), promesa(310) + " · la tercera dijo que se hizo")
        assertFalse(p.telefono.tap(551, 901), promesa(310) + " · la cuarta dijo que se hizo")
        assertEquals(2, tel.entradas.count { it == "tap" }, promesa(310) + " · la tercera tocó el teléfono: ${tel.entradas}")
        // El log dice qué no pasó y por qué; qué salió en cada una va en el rechazo (arriba), no en el log (promesa 317).
        val alTocar = bitacora.rechazos().filter { it.startsWith("tope: no paso «tap(550,900)»: ") }
        assertEquals(1, alTocar.size, promesa(310) + " · el log no dice el rechazo: ${bitacora.lineas}")
        assertTrue("ya falló dos veces" in alTocar.single(), promesa(310) + " · ${alTocar.single()}")
        assertTrue(p.telefono.tap(100, 100), promesa(310) + " · otro botón no pasó")

        assertFalse(p.telefono.type(100, 310, "Ana"), promesa(310))
        assertFalse(p.telefono.type(100, 310, "Ana"), promesa(310))
        assertFalse(p.telefono.type(100, 310, "Ana"), promesa(310))
        assertEquals(2, tel.entradas.count { it == "type" }, promesa(310) + " · la tercera escritura tocó el teléfono")
        assertTrue(bitacora.rechazos().any { it.startsWith("tope: no paso «type(100,310)»: ") }, promesa(310) + " · ${bitacora.lineas}")
        assertTrue(bitacora.lineas.none { "Ana" in it }, promesa(310) + " · el log dice lo que se escribió: ${bitacora.lineas}")

        repeat(3) { p.reproductor.tapLabel("Guardar") }
        assertEquals(2, tel.entradas.count { it == "tapLabel" }, promesa(310) + " · la tercera por etiqueta tocó el teléfono")
        assertTrue(bitacora.rechazos().any { it.startsWith("tope: no paso «tap_label") }, promesa(310) + " · ${bitacora.lineas}")
    }

    @Test
    fun promesa311() = corre {
        assertEquals(144, TopeDeIntentos.celdaPx(3f), promesa(311))
        assertEquals(132, TopeDeIntentos.celdaPx(2.75f), promesa(311))

        run {
            val tope = TopeDeIntentos(CELDA)
            assertEquals(GUARDAR.selector, tope.clave(tope.alTocar(450, 850, GUARDAR)), promesa(311))
            assertEquals("celda:0,14", tope.clave(tope.alTocar(10, 2020, null)), promesa(311))
            assertEquals("celda:1,14", tope.clave(tope.alTocar(144, 2020, null)), promesa(311))
            assertEquals("celda:0,14", tope.clave(tope.alTocar(10, 2020, GUARDAR.copy(selector = ""))), promesa(311) + " · un nodo sin selector no es identidad")

            // Al escribir, el campo tal como se pidió; y escribir no hereda los fallos de tocar el mismo sitio.
            tope.despues(tope.alEscribir("Nombre"), Salida.Intento(dio = false, escribio = false, cambio = null, queSalio = "no se dio"))
            tope.despues(tope.alEscribir("Nombre"), Salida.Intento(dio = false, escribio = false, cambio = null, queSalio = "no se dio"))
            val r = tope.rechazo(tope.alEscribir("Nombre"))
            assertNotNull(r, promesa(311) + " · el campo pedido por tercera vez pasó")
            assertTrue(r.endsWith("Cambia de vía: mira la pantalla y escribe en otro campo, o dile al usuario qué está pasando."), promesa(311) + " · «$r»")
            assertNull(tope.rechazo(tope.alEscribir("Apellido")), promesa(311))
            repeat(2) { tope.despues(tope.alTocar(100, 310, null), NO_CAMBIO) }
            assertNotNull(tope.rechazo(tope.alTocar(100, 310, null)), promesa(311))
            assertNull(tope.rechazo(tope.alEscribirEn(100, 310)), promesa(311) + " · escribir heredó los fallos de tocar")
        }

        // El nodo realmente tocado: dos puntos (de celdas distintas) del mismo nodo son un destino.
        run {
            val bitacora = Bitacora()
            val uno = nodo("a11y:id=uno;path=0.9.0", "1", 0, 1440, 60, 1500)
            val dos = nodo("a11y:id=dos;path=0.9.1", "2", 70, 1440, 130, 1500)
            val tel = Telefono(GUARDAR, uno, dos)
            val p = puerta(tel, bitacora, TopeDeIntentos(CELDA))
            p.telefono.tap(450, 850)                                  // celda 3,5
            p.telefono.tap(690, 990)                                  // celda 4,6
            assertFalse(p.telefono.tap(550, 900), promesa(311) + " · pedir el mismo nodo por otro punto esquivó el tope")
            // Dos nodos pequeños en la misma celda son dos destinos.
            p.telefono.tap(30, 1470); p.telefono.tap(10, 1450)
            assertFalse(p.telefono.tap(50, 1490), promesa(311))
            assertTrue(p.telefono.tap(100, 1470), promesa(311) + " · el nodo vecino de la misma celda heredó el castigo")
            // Sin nodo bajo el punto: la celda de 48 dp.
            p.telefono.tap(10, 2020); p.telefono.tap(140, 2150)
            assertFalse(p.telefono.tap(143, 2016), promesa(311) + " · la misma celda sin nodo es otro destino")
            assertTrue(p.telefono.tap(144, 2020), promesa(311) + " · la celda vecina heredó el castigo")
        }

        // Sin nodoEn, la celda: el mismo nodo en otra celda es otro destino.
        run {
            val tel = Telefono(GUARDAR)
            val p = puerta(tel, Bitacora(), TopeDeIntentos(CELDA), conNodos = false)
            p.telefono.tap(450, 820); p.telefono.tap(460, 830)                    // celda 3,5
            assertFalse(p.telefono.tap(470, 840), promesa(311))
            assertTrue(p.telefono.tap(690, 990), promesa(311) + " · sin nodoEn no se usó la celda")
        }

        // Al escribir por coordenada cuenta el punto pedido, no el nodo que lo resuelve.
        run {
            val tel = Telefono(CAMPO, alEscribir = { false })
            val p = puerta(tel, Bitacora(), TopeDeIntentos(CELDA))
            p.telefono.type(100, 310, "x"); p.telefono.type(110, 320, "x")
            assertFalse(p.telefono.type(120, 330, "x"), promesa(311) + " · el mismo punto pedido esquivó el tope")
            p.telefono.type(1000, 430, "x")                                        // mismo nodo, otra celda pedida
            assertEquals(3, tel.entradas.count { it == "type" }, promesa(311) + " · al escribir contó el nodo y no lo pedido")
        }
    }

    @Test
    fun promesa312() = corre {
        assertEquals(true, TopeDeIntentos.logro(dio = true, escribio = false, cambio = true), promesa(312))
        assertEquals(true, TopeDeIntentos.logro(dio = true, escribio = true, cambio = null), promesa(312))
        assertEquals(false, TopeDeIntentos.logro(dio = true, escribio = false, cambio = false), promesa(312))
        assertEquals(false, TopeDeIntentos.logro(dio = false, escribio = true, cambio = true), promesa(312))
        assertNull(TopeDeIntentos.logro(dio = true, escribio = false, cambio = null), promesa(312) + " · sin huella se adivinó")

        // La calculadora: cada «7» cambia solo el display, y el cambio se ve tras asentar.
        run {
            val bitacora = Bitacora()
            val tel = Telefono(SIETE, textos = mutableListOf("0"), alTocar = { n ->
                if (n == SIETE) pendiente = { textos[0] = textos[0].trimStart('0') + "7" }
                true
            })
            val p = puerta(tel, bitacora, TopeDeIntentos(CELDA))
            val estructura = tel.estructura()
            repeat(5) { assertTrue(p.telefono.tap(180, 1600), promesa(312) + " · el «7» número ${it + 1} se bloqueó") }
            assertEquals(5, tel.entradas.count { it == "tap" }, promesa(312))
            assertEquals(emptyList(), bitacora.rechazos(), promesa(312) + " · la calculadora se bloqueó")
            assertEquals("77777", tel.textos[0], promesa(312))
            assertEquals(estructura, tel.estructura(), promesa(312) + " · el caso no es el de la calculadora: cambió algo más que el texto")
        }

        // «Guardar»: tres toques sin ningún cambio, el tercero no llega.
        run {
            val tel = Telefono(GUARDAR)
            val p = puerta(tel, Bitacora(), TopeDeIntentos(CELDA))
            repeat(3) { p.telefono.tap(550, 900) }
            assertEquals(2, tel.entradas.count { it == "tap" }, promesa(312) + " · el tercer «Guardar» sin cambio llegó")
        }

        // Escribir que escribe es logro, aunque la huella no cambie.
        run {
            val tel = Telefono(CAMPO)
            val p = puerta(tel, Bitacora(), TopeDeIntentos(CELDA))
            repeat(3) { assertTrue(p.telefono.type(100, 310, "hola"), promesa(312) + " · escribir con éxito se bloqueó") }
        }

        // Un toque que no se dio es fallo aunque la pantalla cambie (un reloj que avanza).
        run {
            val tel = Telefono(GUARDAR, alTocar = { textos += "tic"; false })
            val p = puerta(tel, Bitacora(), TopeDeIntentos(CELDA))
            repeat(3) { p.telefono.tap(550, 900) }
            assertEquals(2, tel.entradas.count { it == "tap" }, promesa(312) + " · un toque que no se dio contó como logro")
        }

        // Una lista de homónimos no es intento.
        run {
            val tope = TopeDeIntentos(CELDA)
            repeat(3) { tope.despues(Destino.Nombre("Descargas"), Salida.Lista(LISTA, listOf(S1, S2))) }
            assertNull(tope.rechazo(Destino.Nombre("Descargas")), promesa(312) + " · tres listas contaron como intentos")
        }

        // Sin huella no se juzga «cambió»: no bloquea, y lo dice una vez.
        run {
            val bitacora = Bitacora()
            val tel = Telefono(GUARDAR)
            val p = puerta(tel, bitacora, TopeDeIntentos(CELDA), conHuella = false)
            repeat(3) { assertTrue(p.telefono.tap(550, 900), promesa(312) + " · sin huella bloqueó") }
            assertEquals(3, tel.entradas.count { it == "tap" }, promesa(312))
            assertEquals(1, bitacora.lineas.count { it.startsWith("tope: sin huella") }, promesa(312) + " · ${bitacora.lineas}")
        }
    }

    @Test
    fun promesa313() {
        fun falla(tope: TopeDeIntentos, d: Destino) = tope.despues(d, NO_CAMBIO)

        // Sin lista, el número no abre destino.
        run {
            val tope = TopeDeIntentos(CELDA)
            falla(tope, Destino.Nombre("Descargas")); falla(tope, Destino.Nombre("Descargas"))
            val r = tope.rechazo(Destino.Nombre("Descargas", "1"))
            assertNotNull(r, promesa(313) + " · sin lista, which=1 abrió un destino nuevo")
            assertFalse("which" in r.substringAfter("en esta petición"), promesa(313) + " · sin lista sugirió which: «$r»")
            assertTrue(r.endsWith(CAMBIA_DE_VIA), promesa(313))
            assertNotNull(tope.rechazo(Destino.Nombre("Descargas", "2")), promesa(313))
            assertEquals(tope.clave(Destino.Nombre("Descargas")), tope.clave(Destino.Nombre("descargas", "7")), promesa(313))
        }

        // Con lista de ese nombre: el número elige candidato, y se lee como número.
        run {
            val tope = TopeDeIntentos(CELDA)
            tope.despues(Destino.Nombre("Descargas"), Salida.Lista(LISTA, listOf(S1, S2)))
            falla(tope, Destino.Nombre("Descargas", "1")); falla(tope, Destino.Nombre("descargas", "1"))
            for (w in listOf("1", "01", "+1", " 1 ")) {
                val r = tope.rechazo(Destino.Nombre("Descargas", w))
                assertNotNull(r, promesa(313) + " · which=«$w» no es el 1")
                assertTrue(OTRO_CANDIDATO in r && LISTA in r, promesa(313) + " · con lista no sugirió otro candidato: «$r»")
            }
            assertNull(tope.rechazo(Destino.Nombre("Descargas", "2")), promesa(313) + " · el otro candidato heredó el castigo")
            falla(tope, Destino.Nombre("Descargas", "2")); falla(tope, Destino.Nombre("Descargas", "2"))
            assertNotNull(tope.rechazo(Destino.Nombre("Descargas", "02")), promesa(313) + " · «02» se comparó como texto")
            assertNotNull(tope.rechazo(Destino.Nombre("Descargas", "+2")), promesa(313) + " · «+2» se comparó como texto")
            // Lo que no es un número del 1 al N no elige: es el nombre.
            falla(tope, Destino.Nombre("Descargas")); falla(tope, Destino.Nombre("Descargas"))
            for (w in listOf("dos", "0", "3")) assertNotNull(tope.rechazo(Destino.Nombre("Descargas", w)), promesa(313) + " · which=«$w» abrió un destino nuevo")
        }

        // El candidato es su nodo: tocarlo por coordenada y elegirlo por número son el mismo destino.
        run {
            val tope = TopeDeIntentos(CELDA)
            tope.despues(Destino.Nombre("Descargas"), Salida.Lista(LISTA, listOf(S1, S2)))
            val fila = nodo(S2, "Descargas", 0, 850, 1080, 950, tipo = "TextView")
            falla(tope, tope.alTocar(540, 900, fila)); falla(tope, tope.alTocar(20, 940, fila))
            val r = tope.rechazo(Destino.Nombre("Descargas", "2"))
            assertNotNull(r, promesa(313) + " · el candidato 2 no es el nodo que se tocó")
            assertTrue(OTRO_CANDIDATO in (tope.rechazo(tope.alTocar(540, 900, fila)) ?: ""), promesa(313) + " · tocar el candidato olvidó su lista")
        }

        // Una lista de otro nombre no habilita el número.
        run {
            val tope = TopeDeIntentos(CELDA)
            tope.despues(Destino.Nombre("Documentos"), Salida.Lista("hay 2 «Documentos»", listOf("a", "b")))
            falla(tope, Destino.Nombre("Descargas")); falla(tope, Destino.Nombre("Descargas"))
            assertNotNull(tope.rechazo(Destino.Nombre("Descargas", "2")), promesa(313) + " · la lista de «Documentos» abrió destinos en «Descargas»")
        }
    }

    @Test
    fun promesa314() = corre {
        val bitacora = Bitacora()
        val reloj = TestTimeSource()
        val tope = TopeDeIntentos(CELDA)
        val cuenta = CuentaDePeticion(reloj, bitacora)
        val tel = Telefono(GUARDAR)
        val p = puerta(tel, bitacora, tope, cuenta)

        assertTrue(abrePeticion(QuienHabla.PERSONA, tope, cuenta), promesa(314))
        p.telefono.tap(550, 900); p.telefono.tap(550, 900)
        assertFalse(p.telefono.tap(550, 900), promesa(314))

        assertFalse(abrePeticion(QuienHabla.SISTEMA, tope, cuenta), promesa(314) + " · un aviso del sistema abrió una petición")
        cuenta.llamada("pulsar", "Guardar"); cuenta.retirada("pulsar")
        assertFalse(p.telefono.tap(550, 900), promesa(314) + " · un aviso del sistema o una llamada retirada devolvió el tope a cero")
        assertTrue(bitacora.lineas.none { it.startsWith("peticion:") }, promesa(314) + " · un aviso del sistema cerró la petición: ${bitacora.lineas}")

        assertTrue(abrePeticion(QuienHabla.PERSONA, tope, cuenta), promesa(314))
        val medidas = bitacora.lineas.filter { it.startsWith("peticion:") }
        assertEquals(1, medidas.size, promesa(314) + " · la petición nueva no dejó la medida de la anterior: ${bitacora.lineas}")
        assertTrue("llamadas=5 " in medidas.single() && "rechazadas=2 retiradas=1" in medidas.single(), promesa(314) + " · ${medidas.single()}")
        assertTrue(p.telefono.tap(550, 900), promesa(314) + " · la petición nueva no devolvió el tope a cero")
        assertEquals(3, tel.entradas.count { it == "tap" }, promesa(314))

        // Las listas de la petición vieja también se van: el número vuelve a no abrir destino.
        tope.despues(Destino.Nombre("Descargas"), Salida.Lista(LISTA, listOf(S1, S2)))
        tope.nuevaPeticion()
        tope.despues(Destino.Nombre("Descargas"), NO_CAMBIO); tope.despues(Destino.Nombre("Descargas"), NO_CAMBIO)
        assertNotNull(tope.rechazo(Destino.Nombre("Descargas", "2")), promesa(314) + " · la lista de la petición anterior sigue viva")
    }

    @Test
    fun promesa315() = corre {
        run {
            val reloj = TestTimeSource()
            val bitacora = Bitacora()
            val c = CuentaDePeticion(reloj, bitacora)
            reloj += 1000.milliseconds
            assertNull(c.nuevaPeticion(), promesa(315))
            assertTrue(bitacora.lineas.isEmpty(), promesa(315) + " · una petición sin llamadas emitió: ${bitacora.lineas}")

            reloj += 200.milliseconds; c.llamada("tap", "celda:1,1")                            // 1200: primera llamada
            reloj += 300.milliseconds; c.resultado("tap", actuo = false)
            reloj += 100.milliseconds; c.llamada("tap", "celda:1,1")
            reloj += 400.milliseconds; c.resultado("tap", actuo = true)                          // 2000: primera que actuó
            reloj += 100.milliseconds; c.llamada("tap", "celda:1,1"); c.rechazada("tap")
            reloj += 100.milliseconds; c.llamada("type", "Nombre"); c.retirada("type")
            reloj += 100.milliseconds; c.llamada("scroll")
            reloj += 300.milliseconds; c.resultado("scroll", actuo = true)                       // 2600: última que actuó
            val linea = c.cerrar()
            assertEquals(
                "llamadas=5 distintas=3 intentos_max=3 «celda:1,1» primera=800 ms ultima=1400 ms desde_peticion=1000 ms rechazadas=1 retiradas=1",
                linea,
                promesa(315),
            )
            assertEquals(listOf("peticion: $linea"), bitacora.lineas, promesa(315))
            assertNull(c.cerrar(), promesa(315) + " · cerrar dos veces emitió dos medidas")
            assertEquals(1, bitacora.lineas.size, promesa(315))

            // Sin acción que actuó no se inventa un tiempo; sin destino, intentos_max es 0.
            c.llamada("scroll"); c.resultado("scroll", actuo = false)
            assertEquals(
                "llamadas=1 distintas=1 intentos_max=0 «—» primera=— ultima=— desde_peticion=— rechazadas=0 retiradas=0",
                c.cerrar(),
                promesa(315),
            )

            // Un resultado sin llamada en la petición no cuenta ni da tiempos negativos.
            reloj += 50.milliseconds; c.resultado("tap", actuo = true)
            assertNull(c.nuevaPeticion(), promesa(315) + " · un resultado suelto emitió una medida")
            reloj += 100.milliseconds; c.llamada("tap", "x")
            reloj += 250.milliseconds; c.resultado("tap", actuo = true)
            val sola = c.cerrar()
            assertNotNull(sola, promesa(315))
            assertTrue("primera=250 ms ultima=250 ms desde_peticion=350 ms" in sola && "=-" !in sola, promesa(315) + " · «$sola»")
        }

        // Un destino que no es una celda (el selector de un nodo, un nombre) sale como un hash corto: sin lo que el
        // nodo muestra, y el mismo destino con el mismo hash para poder seguirlo de una petición a otra.
        run {
            val c = CuentaDePeticion(TestTimeSource())
            val selector = "a11y:id=contact_row;text=Juan Pérez;cls=TextView;path=0.3.2"
            val hash = Regex("""intentos_max=\d+ «(#[0-9a-f]{8})» """)
            repeat(2) { c.llamada("tap", selector) }
            val una = c.cerrar()
            c.llamada("tap", selector)
            val otra = c.cerrar()
            assertNotNull(una, promesa(315)); assertNotNull(otra, promesa(315))
            val h1 = hash.find(una)?.groupValues?.get(1)
            assertNotNull(h1, promesa(315) + " · «$una»")
            assertEquals(h1, hash.find(otra)?.groupValues?.get(1), promesa(315) + " · el mismo selector dio otro hash: «$una» · «$otra»")
            assertFalse("Juan" in una || "text=" in una, promesa(315) + " · «$una»")
            c.llamada("tap", "celda:2,3")
            assertTrue("intentos_max=1 «celda:2,3» " in (c.cerrar() ?: ""), promesa(315) + " · la celda no va tal cual")
        }

        // Por la puerta: lo rechazado cuenta, y cada entrada es una llamada.
        run {
            val bitacora = Bitacora()
            val cuenta = CuentaDePeticion(TestTimeSource(), bitacora)
            val tel = Telefono(GUARDAR)
            val p = puerta(tel, bitacora, TopeDeIntentos(CELDA), cuenta)
            cuenta.nuevaPeticion()
            repeat(3) { p.telefono.tap(550, 900) }
            p.telefono.scroll(true)
            val linea = cuenta.cerrar()
            assertNotNull(linea, promesa(315))
            assertTrue(Regex("""^llamadas=4 distintas=2 intentos_max=3 «#[0-9a-f]{8}» """).containsMatchIn(linea), promesa(315) + " · el destino no va como hash corto: «$linea»")
            assertTrue(linea.endsWith(" rechazadas=1 retiradas=0"), promesa(315) + " · «$linea»")
            assertTrue(bitacora.lineas.contains("peticion: $linea"), promesa(315) + " · ${bitacora.lineas}")
        }
    }
}
