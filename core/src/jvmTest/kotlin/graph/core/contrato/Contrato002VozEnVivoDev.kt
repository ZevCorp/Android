package graph.core.contrato

import graph.core.contrato.Contrato002VozGptLive.Companion.promesa
import graph.core.domain.LearnedTool
import graph.core.domain.Mcp
import graph.core.voz.CatalogoDeVoz
import graph.core.voz.HerramientasDeVoz
import graph.core.voz.Llamada
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * LA 246 SE JUZGA LEYENDO LAS FUENTES DE `app` (docs/specs/002, fase B1b). `app` es Android y no corre en jvmTest, pero lo que
 * promete está escrito: dónde vive el botón, de dónde sale la clave y por dónde pasa la telemetría. Se lee, sin compilar nada.
 *
 * ANCLADO CONTRA LOS ATAJOS QUE YA BURLARON UN TEST DE FUENTES: el nombre calificado y el `typealias`. No se busca «la línea
 * buena»: se cuenta CADA aparición del nombre —con paquete delante, en un alias o en un import con `as`— y cada una tiene que
 * estar donde debe. Los comentarios no cuentan; los textos sí, porque dentro de una plantilla `${…}` cabe código.
 */
class Contrato002VozEnVivoDev {

    private companion object {
        const val VOZ = "app/src/main/kotlin/com/zevcorp/graph/voice/live/VozEnVivoDev.kt"
        const val APP = "app/src/main/kotlin/com/zevcorp/graph/GraphApp.kt"
        const val PANTALLA = "app/src/main/kotlin/com/zevcorp/graph/ui/MainActivity.kt"
        const val BUS = "app/src/main/kotlin/com/zevcorp/graph/platform/LogBus.kt"
        const val TELEMETRIA = "app/src/main/kotlin/com/zevcorp/graph/platform/Telemetry.kt"
        const val VOICE_DOCK = "app/src/main/kotlin/com/zevcorp/graph/ui/VoiceDock.kt"
        const val BUBBLE = "app/src/main/kotlin/com/zevcorp/graph/ui/FloatingBubble.kt"
        const val BOTON = "Voz en vivo (prueba)"
        val RAMA_DEV = Regex("""\bif\s*\(\s*mode\s*==\s*MODE_DEV\s*\)\s*\{""")

        /** Lo único que puede nombrar la expresión del device_id: el ID del teléfono, nunca una key. */
        val NOMBRES_DEL_DEVICE_ID = setOf("GraphApp", "instance", "resolvedDeviceId", "trim", "ifBlank", "null")
    }

    private val raiz: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "app/src/main").isDirectory }
        ?: fail("no encuentro app/src/main subiendo desde ${System.getProperty("user.dir")}")

    /** Una fuente en dos vistas del mismo largo: sin comentarios, y además sin el contenido de los textos (para las llaves). */
    private class Fuente(val ruta: String, texto: String) {
        val sinComentarios: String
        val soloCodigo: String

        init {
            val a = StringBuilder(texto)
            val b = StringBuilder(texto)
            fun borrar(sb: StringBuilder, desde: Int, hasta: Int) {
                for (k in desde until minOf(hasta, sb.length)) if (sb[k] != '\n') sb.setCharAt(k, ' ')
            }
            val n = texto.length
            var i = 0
            while (i < n) {
                when {
                    texto.startsWith("//", i) -> {
                        val fin = texto.indexOf('\n', i).let { if (it < 0) n else it }
                        borrar(a, i, fin); borrar(b, i, fin); i = fin
                    }
                    texto.startsWith("/*", i) -> {
                        var nivel = 0
                        var j = i
                        while (j < n) {
                            if (texto.startsWith("/*", j)) { nivel++; j += 2 }
                            else if (texto.startsWith("*/", j)) { nivel--; j += 2; if (nivel == 0) break }
                            else j++
                        }
                        borrar(a, i, j); borrar(b, i, j); i = j
                    }
                    texto.startsWith("\"\"\"", i) -> {
                        var fin = texto.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                        while (fin < n && texto[fin] == '"') fin++
                        borrar(b, i + 3, fin - 3); i = fin
                    }
                    texto[i] == '"' || texto[i] == '\'' -> {
                        val cierre = texto[i]
                        var j = i + 1
                        while (j < n && texto[j] != cierre && texto[j] != '\n') j += if (texto[j] == '\\') 2 else 1
                        borrar(b, i + 1, j); i = j + 1
                    }
                    else -> i++
                }
            }
            sinComentarios = a.toString()
            soloCodigo = b.toString()
        }

        /** Cada aparición de [nombre] como palabra entera, con o sin paquete delante, textos incluidos. */
        fun apariciones(nombre: String): List<Int> =
            Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(nombre) + "(?![\\p{L}\\p{N}_])").findAll(sinComentarios).map { it.range.first }.toList()

        /** De la llave en [llave] a la que la cierra, contadas en el código sin textos. */
        fun bloque(llave: Int): IntRange {
            var nivel = 0
            for (k in llave until soloCodigo.length) {
                when (soloCodigo[k]) {
                    '{' -> nivel++
                    '}' -> if (--nivel == 0) return llave..k
                }
            }
            fail("$ruta: la llave de la posición $llave no cierra")
        }

        fun linea(pos: Int): String {
            val desde = sinComentarios.lastIndexOf('\n', pos) + 1
            val hasta = sinComentarios.indexOf('\n', pos).let { if (it < 0) sinComentarios.length else it }
            return sinComentarios.substring(desde, hasta).trim()
        }

        fun lineas(regex: Regex): List<String> = regex.findAll(soloCodigo).map { linea(it.range.first) }.toList()
    }

    private fun fuente(ruta: String): Fuente {
        val f = File(raiz, ruta)
        assertTrue(f.isFile, promesa(246) + " · falta $ruta")
        return Fuente(ruta, f.readText())
    }

    /** Todas las fuentes de `app`: un alias o una llamada pueden vivir en cualquiera. */
    private fun todas(): List<Fuente> = File(raiz, "app/src").walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java", "kts") }
        .map { Fuente(it.relativeTo(raiz).invariantSeparatorsPath, it.readText()) }
        .toList()

    @Test
    fun promesa246() {
        val voz = fuente(VOZ)
        val pantalla = fuente(PANTALLA)
        val bus = fuente(BUS)
        val telemetria = fuente(TELEMETRIA)
        val app = todas()

        // ── EL BOTÓN VIVE EN `if (mode == MODE_DEV) { … }`, y la voz no se arranca por ningún otro sitio ──────────────────
        val modos = Regex("""\bconst\s+val\s+(MODE_\w+)\s*=\s*("[^"]*")""").findAll(pantalla.sinComentarios).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("\"dev\""), modos.filter { it.first == "MODE_DEV" }.map { it.second }, promesa(246) + " · MODE_DEV es «dev», declarado una vez")
        assertEquals(modos.size, modos.map { it.second }.toSet().size, promesa(246) + " · ningún otro modo vale lo mismo: $modos")
        assertEquals(
            listOf("private var mode = MODE_CLOUD", "mode = app.prefs.getString(KEY_UI_MODE, MODE_CLOUD) ?: MODE_CLOUD"),
            pantalla.lineas(Regex("""(?<![\w.])mode\s*=(?!=)""")),
            promesa(246) + " · el modo solo sale de la preferencia: nadie lo pisa ni lo sombrea",
        )
        val ramas = RAMA_DEV.findAll(pantalla.soloCodigo).map { pantalla.bloque(it.range.last) }.toList()
        assertTrue(ramas.isNotEmpty(), promesa(246) + " · MainActivity no tiene ninguna rama `if (mode == MODE_DEV) { … }`")
        fun enRamaDev(pos: Int) = ramas.any { pos in it }

        val botones = pantalla.apariciones(BOTON)
        assertTrue(botones.isNotEmpty(), promesa(246) + " · no hay botón «$BOTON» en MainActivity")
        assertEquals(emptyList(), botones.filterNot(::enRamaDev).map(pantalla::linea), promesa(246) + " · el botón fuera de la rama MODE_DEV")
        assertEquals(
            emptyList(),
            pantalla.apariciones("VozEnVivoDev").filterNot { enRamaDev(it) || pantalla.linea(it) == "import com.zevcorp.graph.voice.live.VozEnVivoDev" }.map(pantalla::linea),
            promesa(246) + " · VozEnVivoDev fuera de la rama MODE_DEV (un alias, un import con `as` o un nombre calificado también cuentan)",
        )
        assertTrue(
            Regex("""\bVozEnVivoDev\s*\(""").findAll(pantalla.soloCodigo).any { enRamaDev(it.range.first) },
            promesa(246) + " · la voz se construye dentro de la rama MODE_DEV",
        )
        for (f in app) {
            if (f.ruta == VOZ || f.ruta == PANTALLA) continue
            assertEquals(emptyList(), f.apariciones("VozEnVivoDev").map(f::linea), promesa(246) + " · ${f.ruta} nombra a VozEnVivoDev")
            assertEquals(emptyList(), f.apariciones("ConversacionViva").map(f::linea), promesa(246) + " · la conversación solo la arma VozEnVivoDev, no ${f.ruta}")
        }
        assertEquals(listOf("class VozEnVivoDev(private val contexto: Context) {"), voz.apariciones("VozEnVivoDev").map(voz::linea), promesa(246) + " · en su archivo, VozEnVivoDev solo se declara")
        val clase = Regex("""\bclass\s+VozEnVivoDev\b""").find(voz.soloCodigo) ?: fail(promesa(246) + " · VozEnVivoDev.kt no declara la clase")
        val cuerpo = voz.bloque(voz.soloCodigo.indexOf('{', clase.range.last))
        assertEquals(
            emptyList(),
            voz.apariciones("ConversacionViva").filterNot { it in cuerpo || voz.linea(it) == "import graph.core.voz.ConversacionViva" }.map(voz::linea),
            promesa(246) + " · la conversación vive dentro de la clase VozEnVivoDev",
        )

        // PARAR NO DEPENDE DE LA PANTALLA: detener() corre en el alcance propio de la voz. Desde el de una Activity que se
        // cierra, `withContext` lanza antes de correr y el micrófono queda abierto.
        assertEquals(
            listOf("private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)"),
            voz.lineas(Regex("""\balcance\s*=""")),
            promesa(246) + " · la voz tiene su propio alcance",
        )
        val detenciones = voz.apariciones("detener")
        assertEquals(1, detenciones.size, promesa(246) + " · detener() se llama en un solo sitio: ${detenciones.map(voz::linea)}")
        assertTrue(
            Regex("""\balcance\s*\.\s*launch\s*\{""").findAll(voz.soloCodigo).any { detenciones.single() in voz.bloque(it.range.last) },
            promesa(246) + " · y ese sitio está dentro de `alcance.launch { … }`",
        )

        // ── LA CREDENCIAL ES EL DEVICE_ID DEL PROXY: nunca una key, nunca la configuración remota ─────────────────────
        val remota = Regex("(?i)remote")
        assertEquals(emptyList(), remota.findAll(voz.sinComentarios).map { voz.linea(it.range.first) }.toList(), promesa(246) + " · VozEnVivoDev no toca la configuración remota (RemoteConfig ni las prefs «remote…»)")
        for (r in ramas) {
            assertTrue(remota.find(pantalla.sinComentarios.substring(r.first, r.last + 1)) == null, promesa(246) + " · ni la rama MODE_DEV le pasa nada remoto")
        }
        assertEquals(1, Regex("""\bcredencial\s*=""").findAll(voz.soloCodigo).count(), promesa(246) + " · una sola credencial")
        assertEquals(1, Regex("""\bcredencial\s*=\s*\{\s*deviceIdDelProxy\s*\(\s*\)\s*\}""").findAll(voz.soloCodigo).count(), promesa(246) + " · la credencial es deviceIdDelProxy()")
        val declaracion = Regex("""\bfun\s+deviceIdDelProxy\s*\(\s*\)\s*:\s*String\?\s*=""").findAll(voz.soloCodigo).toList()
        assertEquals(1, declaracion.size, promesa(246) + " · deviceIdDelProxy() se declara una vez, como expresión")
        assertEquals(2, voz.apariciones("deviceIdDelProxy").size, promesa(246) + " · y solo se usa como credencial")
        // La expresión llega hasta la primera línea en blanco.
        val expresion = voz.sinComentarios.substring(declaracion.single().range.last + 1).split(Regex("\n[ \t]*\n"), limit = 2)[0]
        assertEquals(emptyList(), Regex("\"([^\"]*)\"").findAll(expresion).map { it.groupValues[1] }.toList(), promesa(246) + " · el device_id no cita ninguna pref por nombre: $expresion")
        assertTrue(Regex("""\bGraphApp\s*\.\s*instance\s*\.\s*resolvedDeviceId\s*\(\s*\)""").containsMatchIn(expresion), promesa(246) + " · lee GraphApp.instance.resolvedDeviceId(): $expresion")
        assertFalse(Regex("""\bBuildConfig\b""").containsMatchIn(expresion), promesa(246) + " · ninguna key horneada en la credencial: $expresion")
        val nombres = Regex("""[\p{L}_][\p{L}\p{N}_]*""").findAll(expresion.replace(Regex("\"[^\"]*\""), "\"\"")).map { it.value }.toSet()
        assertEquals(emptySet(), nombres - NOMBRES_DEL_DEVICE_ID, promesa(246) + " · el device_id no pasa por nadie más: $expresion")

        // ── LA URL ES LA DEL PROXY DE GRAPH: nunca api.openai.com directo, nunca una cabecera con una key real ─────────
        assertEquals(
            0,
            Regex("""api\.openai\.com""").findAll(voz.sinComentarios.replace(Regex("\"[^\"]*\""), "\"\"")).count(),
            promesa(246) + " · VozEnVivoDev no puede nombrar api.openai.com: eso es del protocolo, no de esta capa",
        )
        assertTrue(Regex("""\burlDeConexion\s*=\s*urlDelProxy\s*\(\s*\)""").containsMatchIn(voz.soloCodigo), promesa(246) + " · el protocolo conecta a urlDelProxy()")
        assertTrue(Regex("""\bcabecerasDeConexion\s*=\s*\{\s*emptyMap\s*\(\s*\)\s*\}""").containsMatchIn(voz.soloCodigo), promesa(246) + " · sin cabeceras: nunca un Authorization con una key real")
        val declaracionUrl = Regex("""\bfun\s+urlDelProxy\s*\(\s*\)\s*:\s*String\s*\{""").findAll(voz.soloCodigo).toList()
        assertEquals(1, declaracionUrl.size, promesa(246) + " · urlDelProxy() se declara una vez")
        val cuerpoUrl = voz.bloque(declaracionUrl.single().range.last)
        val textoUrl = voz.sinComentarios.substring(cuerpoUrl.first, cuerpoUrl.last + 1)
        assertTrue(Regex("""\bresolvedGraphBaseUrl\s*\(\s*\)""").containsMatchIn(textoUrl), promesa(246) + " · la URL sale de resolvedGraphBaseUrl(), el mismo que usa el cerebro remoto: $textoUrl")
        assertTrue(Regex("""\bresolvedDeviceId\s*\(\s*\)""").containsMatchIn(textoUrl), promesa(246) + " · la URL lleva el device_id: $textoUrl")
        assertTrue(Regex("""device_id=""").containsMatchIn(textoUrl), promesa(246) + " · el query param es device_id: $textoUrl")
        // MEDIDO CONTRA PRODUCCIÓN (2026-09-18): los rewrites de Graph no se aplican a un WebSocket upgrade
        // (solo a HTTP normal) -- la ruta "bonita" /api/android/live/session da 404 sin llegar a la función;
        // hay que conectar directo al path real del archivo (/api/android-live-session).
        assertTrue(Regex("""/api/android-live-session""").containsMatchIn(textoUrl), promesa(246) + " · conecta al path real de la función, no al de un rewrite que no aplica a WebSocket: $textoUrl")
        assertFalse(Regex("""/api/android/live/session""").containsMatchIn(textoUrl), promesa(246) + " · nunca la ruta con rewrite, que da 404 en un WebSocket upgrade real: $textoUrl")

        // ── LOGBUS SOLO ENCOLA LO QUE DEJA PASAR TelemetriaDeVoz ───────────────────────────────────────────────────────
        assertEquals(1, bus.apariciones("Telemetry").size, promesa(246) + " · LogBus nombra a Telemetry una sola vez: ${bus.apariciones("Telemetry").map(bus::linea)}")
        assertEquals(1, bus.apariciones("enqueue").size, promesa(246) + " · y encola en un solo sitio: ${bus.apariciones("enqueue").map(bus::linea)}")
        assertEquals(
            1,
            Regex("""\bTelemetriaDeVoz\s*\.\s*paraRemoto\s*\(\s*tag\s*,\s*message\s*\)\s*\?\.\s*let\s*\{\s*Telemetry\s*\.\s*enqueue\s*\(\s*tag\s*,\s*it\s*\)\s*\}""").findAll(bus.soloCodigo).count(),
            promesa(246) + " · a Telemetry.enqueue solo llega lo que devuelve TelemetriaDeVoz.paraRemoto(tag, message)",
        )
        assertEquals(1, Regex("""\boverride\s+fun\s+log\s*\(\s*tag\s*:\s*String\s*,\s*message\s*:\s*String\s*\)""").findAll(bus.soloCodigo).count(), promesa(246) + " · dentro de log(tag, message)")
        assertEquals(emptyList(), bus.lineas(Regex("""\b(?:val|var)\s+(?:tag|message|it)\b|(?<![\w.])(?:tag|message)\s*=(?!=)""")), promesa(246) + " · ni tag ni message se sombrean antes del filtro")
        assertEquals(
            listOf("import graph.core.voz.TelemetriaDeVoz"),
            bus.apariciones("TelemetriaDeVoz").map(bus::linea).filter { it.startsWith("import") },
            promesa(246) + " · el filtro es el de core, importado sin alias",
        )
        assertEquals(2, bus.apariciones("TelemetriaDeVoz").size, promesa(246) + " · importado y usado una vez")
        for (f in app) {
            assertEquals(
                emptyList(),
                f.lineas(Regex("""\b(?:object|class|interface|typealias|fun|val|var)\s+TelemetriaDeVoz\b|\bas\s+TelemetriaDeVoz\b""")),
                promesa(246) + " · ${f.ruta} declara otro TelemetriaDeVoz",
            )
            if (f.ruta == BUS || f.ruta == TELEMETRIA) continue
            assertEquals(emptyList(), f.apariciones("enqueue").map(f::linea), promesa(246) + " · a la telemetría solo encola LogBus, no ${f.ruta}")
        }
        assertEquals(listOf("fun enqueue(tag: String, message: String) {"), telemetria.apariciones("enqueue").map(telemetria::linea), promesa(246) + " · Telemetry no se encola por otro camino")
    }

    /**
     * LA 256 SE JUZGA IGUAL, LEYENDO LAS FUENTES, porque lo que falló no es un cálculo sino un CABLE: el catálogo de la
     * voz pedía las acciones con `emptyList()` mientras el otro llamador pasaba las aprendidas, y las dos ramas eran
     * correctas por separado. Lo que hay que atrapar es que se separen otra vez, y eso se ve en el código, no en un
     * doble. El comportamiento —que una aprendida llegue hasta lo que lee el delegado— va detrás, con un `Mcp` real.
     */
    @Test
    fun promesa256() {
        val voz = fuente(VOZ)
        val app = fuente(APP)

        // UN SOLO SITIO DECIDE cuáles aprendidas ve una corrida; si hay dos, vuelven a separarse.
        assertEquals(
            1,
            Regex("""\bfun\s+aprendidasDisponibles\s*\(""").findAll(app.soloCodigo).count(),
            promesa(256) + " · aprendidasDisponibles() se declara una sola vez en GraphApp",
        )
        // Y lo usan los DOS: la anticipación de GraphApp y el catálogo de la voz.
        assertTrue(
            app.apariciones("aprendidasDisponibles").size >= 2,
            promesa(256) + " · GraphApp declara el criterio y no lo usa: ${app.apariciones("aprendidasDisponibles").map(app::linea)}",
        )
        assertEquals(
            1,
            voz.apariciones("aprendidasDisponibles").size,
            promesa(256) + " · la voz no pide las aprendidas al único sitio que lo decide: ${voz.apariciones("aprendidasDisponibles").map(voz::linea)}",
        )
        // NADIE escribe la lista vacía a mano en la llamada al catálogo: ese fue el bug.
        for (f in todas()) {
            assertEquals(
                emptyList(),
                f.lineas(Regex("""\bherramientas\s*\([^\n]*\bemptyList\b""")),
                promesa(256) + " · ${f.ruta} pide el catálogo con una lista vacía escrita a mano",
            )
        }

        // ── Y POR COMPORTAMIENTO: una aprendida llega hasta lo que lee el delegado, pasando por el ejecutor ──────────
        corre {
            val mano = Contrato003FrenoYPuerta.Mano()
            val aprendida = LearnedTool("pedir_un_taxi", "Pide un taxi como se lo enseñaron", listOf("a"))
            val mcp = Mcp(mano.gestos, mano.sistema, listOf(aprendida), mano.reproductor)
            val ojos = HerramientasDeVoz(pantalla = { null }, acciones = { mcp.tools }, mirarEn = despachadorDeIo())
            val leido = ojos.ejecutar(Llamada("call_1", CatalogoDeVoz.QUE_PUEDO_HACER, emptyMap()))
            assertTrue(aprendida.name in leido, promesa(256) + " · la aprendida no llega a lo que lee el delegado: «$leido»")
            assertEquals(emptyList(), mano.entradas, promesa(256) + " · enumerar el catálogo tocó el teléfono: ${mano.entradas}")
        }
    }

    /**
     * LA 259 SE JUZGA IGUAL, LEYENDO LAS FUENTES DE `app` (modo reunión, ergonomía del silencio). `VoiceDock` y
     * `FloatingBubble` son puro Android (accesibilidad, overlay, gestos con la mano) y no corren en `jvmTest`, pero lo
     * que prometen está escrito: dónde se consulta `muted`, dónde se llama `toggleMute()` y por dónde NUNCA puede caer
     * un doble toque con el modo reunión anclado.
     */
    @Test
    fun promesa259() {
        val dock = fuente(VOICE_DOCK)
        val bubble = fuente(BUBBLE)

        // ── VoiceDock declara el estado y la acción del gesto ──────────────────────────────────────────────────────
        assertTrue(
            Regex("""@Volatile\s+var\s+muted\s*=\s*false""").containsMatchIn(dock.soloCodigo),
            promesa(259) + " · VoiceDock no declara `muted` como @Volatile var",
        )
        assertTrue(
            Regex("""\bfun\s+toggleMute\s*\(\s*\)""").containsMatchIn(dock.soloCodigo),
            promesa(259) + " · VoiceDock no declara toggleMute()",
        )

        // ── listenLoop consulta `muted` ANTES de abrir el segmento, y ese bloque no abre el micrófono ──────────────
        val firmaLoop = Regex("""private\s+suspend\s+fun\s+listenLoop\s*\(\s*\)""").find(dock.soloCodigo)
            ?: fail(promesa(259) + " · no encuentro listenLoop() en VoiceDock")
        val cuerpoLoop = dock.bloque(dock.soloCodigo.indexOf('{', firmaLoop.range.last))
        fun dentroDelLoop(pos: Int) = pos in cuerpoLoop

        val chequeoMuted = Regex("""\bif\s*\(\s*muted\s*\)\s*\{""").findAll(dock.soloCodigo)
            .firstOrNull { dentroDelLoop(it.range.first) }
            ?: fail(promesa(259) + " · listenLoop no tiene un bloque `if (muted) { … }`")
        val abreSegmento = Regex("""\blistening\s*=\s*true\b""").findAll(dock.soloCodigo)
            .firstOrNull { dentroDelLoop(it.range.first) }
            ?: fail(promesa(259) + " · no encuentro dónde listenLoop abre el segmento (`listening = true`)")
        assertTrue(
            chequeoMuted.range.first < abreSegmento.range.first,
            promesa(259) + " · el chequeo de `muted` no está antes de abrir el segmento",
        )

        val bloqueMuted = dock.bloque(chequeoMuted.range.last)
        val textoBloqueMuted = dock.sinComentarios.substring(bloqueMuted.first, bloqueMuted.last + 1)
        assertFalse(abreSegmento.range.first in bloqueMuted, promesa(259) + " · `listening = true` cae dentro del bloque de `muted`: abre el micrófono igual")
        assertFalse("defaultTranscriber" in textoBloqueMuted, promesa(259) + " · muteada, la escucha abre el micrófono igual")
        assertTrue("continue" in textoBloqueMuted, promesa(259) + " · muteada, el bucle no vuelve a mirar (falta `continue`)")

        // ── Mutear no cancela nada del worker de tareas ────────────────────────────────────────────────────────────
        val firmaToggle = Regex("""\bfun\s+toggleMute\s*\(\s*\)\s*\{""").find(dock.soloCodigo)
            ?: fail(promesa(259) + " · no encuentro el cuerpo de toggleMute()")
        val cuerpoToggle = dock.bloque(firmaToggle.range.last)
        val textoToggle = dock.sinComentarios.substring(cuerpoToggle.first, cuerpoToggle.last + 1)
        for (prohibido in listOf("taskQueue", "taskWorker", "Ejecucion.parar")) {
            assertFalse(prohibido in textoToggle, promesa(259) + " · toggleMute() toca `$prohibido`")
            assertFalse(prohibido in textoBloqueMuted, promesa(259) + " · el camino de silencio en listenLoop toca `$prohibido`")
        }

        // ── FloatingBubble: el doble toque con el modo reunión anclado va a toggleMute(), nunca al micrófono de un
        //    solo comando ─────────────────────────────────────────────────────────────────────────────────────────
        val firmaListener = Regex("""bubble\.setOnClickListener\s*\{""").find(bubble.soloCodigo)
            ?: fail(promesa(259) + " · no encuentro bubble.setOnClickListener en FloatingBubble")
        val cuerpoListener = bubble.bloque(firmaListener.range.last)
        val textoListener = bubble.sinComentarios.substring(cuerpoListener.first, cuerpoListener.last + 1)
        assertTrue(
            Regex("""voiceDock\.docked\s*->\s*onDockedTap\s*\(\s*\)""").containsMatchIn(textoListener),
            promesa(259) + " · con la burbuja anclada, el toque ya no va a onDockedTap()",
        )

        // ── El ORDEN importa: `execLive` se resuelve antes que `voiceDock.docked` — cortar la
        //    narración de la ejecución con un solo toque sigue siendo el gesto esperado aunque el
        //    modo reunión siga anclado. Invertir el orden dejaría esa narración sonando (el toque
        //    caería siempre en onDockedTap() sin pasar por stopExecLive()) ───────────────────────
        val posExecLive = Regex("""(?<![\p{L}\p{N}_])execLive\s*->""").find(textoListener)
            ?: fail(promesa(259) + " · no encuentro la rama `execLive ->` en el listener")
        val posDockedRama = Regex("""voiceDock\s*\.\s*docked\s*->""").find(textoListener)
            ?: fail(promesa(259) + " · no encuentro la rama `voiceDock.docked ->` en el listener")
        assertTrue(
            posExecLive.range.first < posDockedRama.range.first,
            promesa(259) + " · `execLive` tiene que evaluarse antes que `voiceDock.docked` en el listener",
        )

        // ── Cuando `execLive` Y `voiceDock.docked` son ciertos A LA VEZ (una duda de una tarea de
        //    la reunión respondida con "Responder con voz" mientras esa tarea sigue corriendo), el
        //    toque no puede perderse para el gesto de mutear: tiene que seguir contando, o hacen
        //    falta TRES toques en vez de dos para llegar al doble toque que mutea ───────────────────
        val ramaExecLive = Regex("""(?<![\p{L}\p{N}_])execLive\s*->\s*\{""").find(bubble.soloCodigo)
            ?: fail(promesa(259) + " · la rama `execLive` no abre un bloque `{ … }` en el listener")
        val cuerpoRamaExecLive = bubble.bloque(ramaExecLive.range.last)
        val textoRamaExecLive = bubble.sinComentarios.substring(cuerpoRamaExecLive.first, cuerpoRamaExecLive.last + 1)
        assertTrue(
            Regex("""\bstopExecLive\s*\(\s*\)""").containsMatchIn(textoRamaExecLive),
            promesa(259) + " · el toque con `execLive` encendido ya no corta la narración de la ejecución",
        )
        assertTrue(
            Regex("""if\s*\(\s*voiceDock\s*\.\s*docked\s*\)\s*\{?\s*onDockedTap\s*\(\s*\)\s*\}?""").containsMatchIn(textoRamaExecLive),
            promesa(259) + " · con `voiceDock.docked` a la vez que `execLive`, el toque no llama a onDockedTap(): se pierde para el doble toque que mutea",
        )

        val firmaOnDockedTap = Regex("""\bfun\s+onDockedTap\s*\(\s*\)\s*\{""").find(bubble.soloCodigo)
            ?: fail(promesa(259) + " · FloatingBubble no declara onDockedTap()")
        val cuerpoOnDockedTap = bubble.bloque(firmaOnDockedTap.range.last)
        val textoOnDockedTap = bubble.sinComentarios.substring(cuerpoOnDockedTap.first, cuerpoOnDockedTap.last + 1)
        assertTrue(
            Regex("""voiceDock\s*\.\s*toggleMute\s*\(\s*\)""").containsMatchIn(textoOnDockedTap),
            promesa(259) + " · onDockedTap() no llama a voiceDock.toggleMute()",
        )
        assertFalse(
            Regex("""(?<![\p{L}\p{N}_])onBubbleTap(?![\p{L}\p{N}_])""").containsMatchIn(textoOnDockedTap),
            promesa(259) + " · onDockedTap() cae en onBubbleTap(): un doble toque anclado activaría el menú/tema",
        )
        assertFalse(
            Regex("""(?<![\p{L}\p{N}_])activateMic(?![\p{L}\p{N}_])""").containsMatchIn(textoOnDockedTap),
            promesa(259) + " · onDockedTap() cae en activateMic(): chocaría con la escucha permanente ya corriendo",
        )
    }
}
