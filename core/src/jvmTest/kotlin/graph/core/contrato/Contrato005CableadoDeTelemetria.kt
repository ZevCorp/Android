package graph.core.contrato

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * LA 507 SE JUZGA LEYENDO LAS FUENTES DE `app` (docs/specs/005), como la 246: `app` es Android y no corre en jvmTest. Lo que
 * promete está escrito: por dónde sale cada cuerpo hacia la telemetría y quién llama a `Telemetry`. Se cuenta CADA aparición
 * del nombre —con paquete delante, en un alias o en un import con `as`— y cada una tiene que estar donde debe. Los comentarios
 * no cuentan; los textos sí, porque dentro de una plantilla cabe código.
 */
class Contrato005CableadoDeTelemetria {

    companion object {
        val PROMESAS = mapOf(
            507 to "En las fuentes de app todo lo que Telemetry sube lo arma PuertaDeTelemetria: cada http de Telemetry manda un cuerpo de la puerta, Telemetry no arma JSON ni abre otra conexión, en app solo se usan sus miembros init, userName, deviceId, ensureUser, promptStarted, promptFinished y enqueue, nadie más nombra las tablas de telemetría y LogBus sigue guardando la línea entera.",
        )

        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        const val TELEMETRIA = "app/src/main/kotlin/com/zevcorp/graph/platform/Telemetry.kt"
        const val BUS = "app/src/main/kotlin/com/zevcorp/graph/platform/LogBus.kt"
        /** Lo único de `Telemetry` que `app` puede usar. Ninguno sube nada que no arme la puerta. */
        val MIEMBROS = setOf("init", "userName", "deviceId", "ensureUser", "promptStarted", "promptFinished", "enqueue")
        val CUERPOS = setOf("filasDeLog", "cuerpoDePedido", "cuerpoDeUsuario")
        val TABLAS = Regex("""graph_exec_logs|graph_upsert_prompt|graph_upsert_app_user|graph_prompts|graph_app_users""")
        /** Las claves que viajan viven en core, en la puerta. En Telemetry.kt no pueden aparecer ni en un texto. */
        val CLAVES = Regex("""p_prompt|p_summary|p_user_name|p_display_name|p_device_model|p_app_version|p_status|p_source|prompt_id|device_id""")
        const val D = "$"
    }

    private val raiz: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "app/src/main").isDirectory }
        ?: fail("no encuentro app/src/main subiendo desde ${System.getProperty("user.dir")}")

    /** Una fuente en dos vistas del mismo largo: sin comentarios, y además sin el contenido de los textos. */
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

        fun apariciones(nombre: String): List<Int> =
            Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(nombre) + "(?![\\p{L}\\p{N}_])").findAll(sinComentarios).map { it.range.first }.toList()

        fun linea(pos: Int): String {
            val desde = sinComentarios.lastIndexOf('\n', pos) + 1
            val hasta = sinComentarios.indexOf('\n', pos).let { if (it < 0) sinComentarios.length else it }
            return sinComentarios.substring(desde, hasta).trim()
        }

        /** Profundidad de llaves en [pos], contada en el código sin textos. */
        fun profundidad(pos: Int): Int {
            var nivel = 0
            for (k in 0 until pos) when (soloCodigo[k]) { '{' -> nivel++; '}' -> nivel-- }
            return nivel
        }
    }

    private fun todas(): List<Fuente> = File(raiz, "app/src").walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java", "kts") }
        .map { Fuente(it.relativeTo(raiz).invariantSeparatorsPath, it.readText()) }
        .toList()

    @Test
    fun promesa507() {
        val p = promesa(507)
        val app = todas()
        val tel = app.singleOrNull { it.ruta == TELEMETRIA } ?: fail("$p · falta $TELEMETRIA")
        val bus = app.singleOrNull { it.ruta == BUS } ?: fail("$p · falta $BUS")

        // ── CADA http( DE TELEMETRY LLEVA UN CUERPO DE LA PUERTA ─────────────────────────────────────────────────────────
        val llamadasHttp = Regex("""(?<![\w.])http\s*\(""").findAll(tel.soloCodigo).map { it.range.first }
            .filterNot { Regex("""\bfun\s+$""").containsMatchIn(tel.soloCodigo.substring(maxOf(0, it - 12), it)) }.toList()
        assertTrue(llamadasHttp.size >= 4, "$p · Telemetry sube por http( al menos usuario, abrir, cerrar y filas: ${llamadasHttp.map(tel::linea)}")
        val cuerpoDeLaPuerta = Regex("""\Ahttp\s*\(\s*"POST"\s*,\s*"\${D}PROJECT/[a-z_/]+"\s*,\s*PuertaDeTelemetria\s*\.\s*(\w+)\s*\(""")
        val usados = llamadasHttp.map { pos ->
            val m = cuerpoDeLaPuerta.find(tel.sinComentarios.substring(pos)) ?: fail("$p · un http( sin cuerpo de la puerta: ${tel.linea(pos)}")
            m.groupValues[1]
        }
        assertEquals(CUERPOS, usados.toSet(), "$p · los tres cuerpos de la puerta, y ninguno más: $usados")
        assertEquals(
            1,
            Regex("""\bprivate\s+fun\s+http\s*\(\s*method\s*:\s*String\s*,\s*url\s*:\s*String\s*,\s*body\s*:\s*String\?\s*,\s*vararg\s+headers\s*:\s*Pair<String,\s*String>\s*\)""").findAll(tel.soloCodigo).count(),
            "$p · un solo http, privado, con esa firma",
        )

        // ── TELEMETRY NO ARMA JSON NI ABRE OTRA CONEXIÓN ──────────────────────────────────────────────────────────────────
        for (nombre in listOf("buildJsonObject", "buildJsonArray", "JsonObject", "JsonArray", "JsonPrimitive", "JsonNull", "Json", "encodeToString", "put", "StringBuilder", "buildString", "joinToString")) {
            assertEquals(emptyList(), tel.apariciones(nombre).map(tel::linea), "$p · Telemetry.kt nombra «$nombre»")
        }
        assertEquals(emptyList(), CLAVES.findAll(tel.sinComentarios).map { tel.linea(it.range.first) }.toList(), "$p · las claves de la RPC viven en la puerta, no en Telemetry.kt")
        for (nombre in listOf("openConnection", "outputStream", "toByteArray", "URL")) {
            val usos = tel.apariciones(nombre).map(tel::linea).filterNot { it.startsWith("import ") }
            assertEquals(1, usos.size, "$p · Telemetry.kt usa «$nombre» ${usos.size} veces: $usos")
        }
        assertEquals(1, Regex("""\boutputStream\s*\.\s*use\s*\{\s*it\s*\.\s*write\s*\(\s*body\s*\.\s*toByteArray\s*\(\s*\)\s*\)\s*\}""").findAll(tel.soloCodigo).count(), "$p · lo único que se escribe es el body de http")
        for (nombre in listOf("Socket", "OkHttpClient", "HttpsURLConnection", "WebSocket")) {
            assertEquals(emptyList(), tel.apariciones(nombre).map(tel::linea), "$p · Telemetry.kt abre otra conexión: «$nombre»")
        }

        // ── LOS MIEMBROS PÚBLICOS DE TELEMETRY SON LOS SIETE ─────────────────────────────────────────────────────────────
        val objeto = Regex("""\bobject\s+Telemetry\s*\{""").find(tel.soloCodigo) ?: fail("$p · Telemetry.kt no declara object Telemetry")
        val nivelDelObjeto = tel.profundidad(objeto.range.last) + 1
        val publicos = Regex("""(?m)^[ \t]*((?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public|protected|override|inline|suspend|const|lateinit)\s+)*)(fun|val|var)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)""")
            .findAll(tel.soloCodigo)
            .filter { tel.profundidad(it.range.first) == nivelDelObjeto && "private" !in it.groupValues[1] }
            .map { it.groupValues[3] }.toSet()
        assertEquals(MIEMBROS, publicos, "$p · lo público de Telemetry")

        // ── QUIÉN LLAMA A TELEMETRY: SOLO ESOS MIEMBROS, SIN ALIAS ───────────────────────────────────────────────────────
        val llamadores = mutableListOf<String>()
        for (f in app) {
            if (f.ruta == TELEMETRIA) continue
            for (pos in f.apariciones("Telemetry")) {
                val linea = f.linea(pos)
                if (linea == "import com.zevcorp.graph.platform.Telemetry") continue
                val miembro = Regex("""\A\s*\.\s*([A-Za-z_]\w*)""").find(f.sinComentarios.substring(pos + "Telemetry".length))?.groupValues?.get(1)
                assertTrue(miembro != null && miembro in MIEMBROS, "$p · ${f.ruta} usa Telemetry fuera de sus miembros: $linea")
                llamadores += "${f.ruta.substringAfterLast('/')}:$miembro"
            }
            assertEquals(emptyList(), TABLAS.findAll(f.sinComentarios).map { f.linea(it.range.first) }.toList(), "$p · ${f.ruta} nombra una tabla de telemetría")
            for (nombre in listOf("PuertaDeTelemetria", "LineaDeLog")) {
                assertEquals(emptyList(), f.apariciones(nombre).map(f::linea), "$p · ${f.ruta} nombra $nombre: la puerta se usa solo desde Telemetry")
            }
        }
        assertTrue(llamadores.any { it.endsWith(":promptStarted") } && llamadores.any { it.endsWith(":enqueue") }, "$p · la lista de llamadores no ve a GraphApp ni a LogBus: $llamadores")
        assertEquals(
            listOf("import graph.core.telemetria.LineaDeLog", "import graph.core.telemetria.PuertaDeTelemetria"),
            (tel.apariciones("PuertaDeTelemetria") + tel.apariciones("LineaDeLog")).map(tel::linea).filter { it.startsWith("import") }.sorted(),
            "$p · la puerta es la de core, importada sin alias",
        )
        for (nombre in listOf("PuertaDeTelemetria", "LineaDeLog")) {
            assertEquals(emptyList(), tel.apariciones(nombre).map(tel::linea).filter { Regex("""\bas\s+\w+|typealias""").containsMatchIn(it) }, "$p · sin alias de $nombre")
        }

        // ── EL LOG LOCAL SIGUE ENTERO ────────────────────────────────────────────────────────────────────────────────────
        assertEquals(1, Regex("""\bval\s+line\s*=\s*"\${D}\{fmt\.format\(Date\(\)\)\} \[\${D}tag\] \${D}message"""").findAll(bus.sinComentarios).count(), "$p · LogBus arma la línea con el mensaje entero")
        assertEquals(1, Regex("""\bbuffer\s*\.\s*addLast\s*\(\s*line\s*\)""").findAll(bus.soloCodigo).count(), "$p · y la guarda en el panel local")
        assertEquals(1, Regex("""\bLog\s*\.\s*d\s*\(\s*"Graph"\s*,\s*"\[\${D}tag\] \${D}message"\s*\)""").findAll(bus.sinComentarios).count(), "$p · y en logcat")
    }
}
