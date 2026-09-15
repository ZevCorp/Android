package graph.core.telemetria

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Una línea del LogBus tal como se encoló: local y entera. Lo que de ella sale del teléfono lo decide [PuertaDeTelemetria]. */
class LineaDeLog(val pedido: String?, val tag: String, val mensaje: String)

/**
 * DEL TELÉFONO A LA TELEMETRÍA REMOTA SOLO SALEN MEDIDAS (docs/specs/005, decisión D). Es el único sitio que decide qué sube
 * a Supabase: `Telemetry` no arma ningún cuerpo, sube lo que arma esta puerta (promesa 507).
 *
 * NIEGA POR DEFECTO. Un mensaje se parte en trozos y cada trozo queda solo si tiene forma de medida permitida: un nombre o una
 * frase de la lista cerrada, un signo de estructura, una medida (un número con su unidad, su sustantivo o su prefijo) o un id
 * opaco. Todo tramo que no, con los espacios y la puntuación de adentro, sale como `‹N›`: su largo en caracteres. Una palabra
 * nueva en un log no sale hasta que alguien la agregue aquí con su promesa.
 *
 * La voz ya viene filtrada por `TelemetriaDeVoz.paraRemoto` en `LogBus` (245, 246): la puerta va detrás y deja pasar sus
 * medidas («usuario dijo: N caracteres»). Pasar dos veces por la puerta da lo mismo que una.
 */
object PuertaDeTelemetria {

    const val OTRO = "otro"
    const val TOPE_DE_TAG = 60
    const val TOPE_DE_MENSAJE = 4000

    /** Un número que solo sostiene su prefijo (`HTTP 503`, `intento 2`) es chico; con unidad o sustantivo, una medida larga. */
    private const val CIFRAS_CON_PREFIJO = 4
    private const val CIFRAS_CON_UNIDAD = 6

    /** `paso` y `turno` cuentan de a poco: cuatro cifras detrás de ellos ya son un pin (510). */
    private const val CIFRAS_DE_PASO = 3
    private val PREFIJOS_DE_PASO = setOf("paso", "turno")

    /** Una `s` suelta es una letra: solo sostiene lo que cabe en un tope o en una cola (`tope de 90 s`, `Reintentos.corto`). */
    private const val CIFRAS_CON_S_SUELTA = 3

    /** Números seguidos cuyas cifras juntas llegan a esto son un teléfono o una cédula partidos, no medidas (510). */
    private const val CIFRAS_EN_SERIE = 7

    /** Un segmento numérico de una ruta de la API es un id de Graph; con más cifras, un teléfono (509). */
    private const val CIFRAS_EN_RUTA = 6

    /** La marca más larga: un tramo de más caracteres se marca con ella, y así la marca vuelve a pasar como marca (511). */
    private const val MARCA_MAXIMA = 99_999

    /* ---------- Las listas cerradas ---------- */

    val TAGS: Set<String> = setOf(
        "app", "api", "assist", "auth", "bug-ui", "cloud", "config", "gemini", "graph", "learn", "mcp", "meeting", "memory",
        "neo4j", "openai", "run", "teach", "telemetry", "ui", "update", "voice", "workflow",
        "aprendizaje", "leccion",
        // precisión (yokh/precision): ArmadoDeEjecucion, Freno, Puerta, TopeDeIntentos, CuentaDePeticion
        "freno", "puerta", "tope", "peticion",
    )

    /** Nombres que salen solos: acciones del cerebro, herramientas del MCP, estados y proveedores. */
    val EVENTOS: Set<String> = setOf(
        "tap", "type", "scroll", "swipe", "key", "wait", "mcp", "MCP", "down", "up",
        "go_home", "go_back", "open_app_drawer", "open_notifications", "pan_home", "scroll_menu", "launch_app", "open_app",
        "set_alarm", "set_timer", "show_alarms", "create_event", "send_sms", "send_email", "web_search", "open_url",
        "check_simit_fines", "open_maps", "open_camera", "open_settings", "share_text", "set_clipboard", "set_volume",
        "adjust_volume", "long_press", "drag_and_drop", "press_key", "take_screenshot", "list_apps", "dial", "directions",
        "ask_user", "left_click", "learned_tool",
        "ok", "error", "cancelled", "running", "done", "fin", "decide", "speak", "ask", "transitorio", "true", "false", "null",
        "subconsciente", "consciente",
        "graph", "openai", "gemini", "deepgram", "GRAPH", "OPENAI", "GEMINI", "session",
    )

    /** Frases fijas que escribe el código, nunca la persona: la voz, precisión y el motor. */
    val FRASES: List<String> = listOf(
        "usuario dijo", "Ü dijo", "sesión cerrada", "nodo sin id estructural", "ya hay una tarea en curso", "no sigo",
        "sin reintento", "paraste tú",
    )

    /** Lo que va detrás de un número y lo vuelve medida: `42 caracteres`, `120 bytes`, `3 turnos`. */
    val SUSTANTIVOS: Set<String> = setOf(
        "caracteres", "carácter", "car.", "bytes", "KB", "MB", "ms", "segundos", "min", "minutos", "turnos", "acciones",
        "llamadas", "intentos", "reintentos", "pasos", "steps", "prompts", "tokens", "niveles", "elementos", "clics", "señales",
        "notas", "subconscientes", "conscientes", "cierres", "veces", "px", "dp", "candidatos", "herramientas", "filas",
        "workflows", "apps", "errores",
    )

    /** Lo que va delante de un número y lo vuelve medida: `HTTP 503`, `intento 2/3`, `llamadas=5`. */
    val PREFIJOS: Set<String> = setOf(
        "HTTP", "status", "intento", "reintento", "turno", "paso", "step", "ronda", "llamadas", "distintas", "intentos_max",
        "primera", "ultima", "desde_peticion", "rechazadas", "retiradas", "worth", "tope",
    )

    /** «X de N caracteres»: de qué es la medida. Sin la medida detrás, la palabra cae. */
    val DESCRIPTORES: Set<String> = setOf(
        "objetivo", "pregunta", "resumen", "pedido", "campo", "nombre", "destino", "respuesta", "error", "mensaje", "frase", "texto",
        "archivo", "cuerpo", "argumento", "interpretación", "nota", "prompt",
    )

    /** Segmentos de las rutas de Graph que pueden ir en un log; el resto de un segmento tiene que ser un id. */
    val RUTAS: Set<String> = setOf(
        "api", "v1", "v2", "agent", "turn", "learning", "sessions", "steps", "finish", "context-notes", "workflows", "plan",
        "prepend-alignment", "teach", "file-state", "interpret-steps", "process-video", "upload-token",
    )

    private val SIGNOS = setOf(
        "·", ":", "=", "(", ")", "[", "]", "«", "»", "\"", "→", "←", "|", "—",
        "▶", "■", "✋", "↻", "▪", "⏹", "⏭", "🧩", "👁", "🗣", "❓", "🧠", "🧵", "🔗", "✓", "✔", "✘", "❌", "🩺", "🧭", "🔨", "🎧",
        "📝", "🎬", "⏳", "🔮", "🙋", "🤝", "＋", "🔎",
    )

    /** Puntuación que no dice nada sola: queda junto a lo que queda y se funde en el tramo que cae. */
    private val SUELTOS = setOf(
        ",", ".", ";", "'", "!", "¡", "?", "¿", "…", "-", "/", "*", "+", "%", "&", "@", "$", "~", "^", "{", "}", "<", ">", "\\",
        "`", "_", "️", "‍",
    )

    /** Lo que puede partir un número sin cortar la serie (510): las letras de unidad que no sostienen nada y la puntuación. */
    private val UNIDADES_SUELTAS = setOf("s", "B", "h", "GB")
    private val PARTEN_UN_NUMERO = setOf(".", ",", "-", "/", "+")

    private val ESTADOS = setOf("running", "ok", "error", "cancelled")
    private val VIAS = setOf("app", "burbuja")

    /* ---------- Las formas ---------- */

    private val UUID = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
    /** Coordenadas, celdas y marcas: a lo más 5 cifras por componente; con más, un teléfono escrito entre signos (511). */
    private val MARCA = Regex("‹\\d{1,5}›")
    /** El sello HMAC de precisión (`TopeDeIntentos.enLog`): `#` y 8 hex con al menos una letra. Ocho cifras son un número (508). */
    private val SELLO = Regex("#(?=[0-9]*[a-f])[0-9a-f]{8}")
    private val CELDA = Regex("celda:-?\\d{1,5},-?\\d{1,5}(?!\\d)")
    private val COORDENADAS = Regex("\\(-?\\d{1,5},-?\\d{1,5}(?:→-?\\d{1,5},-?\\d{1,5})?\\)")
    /** Un id con prefijo se parte entero: lo que no tiene la forma de su productor cae entero, no deja su número suelto. */
    private val ID_CON_PREFIJO = Regex("(?:call|item|msg|resp|sess|ses|wf|evt|req|files?)[-_/][A-Za-z0-9]+")

    // Los ids con prefijo, con la forma y el largo de quien los produce (509). Un prefijo sin productor (`ses-`, `evt_`…) no da id.
    /** Graph: `wf_` y `Date.now()` (`WorkflowLearner.js`), el id de la sesión de aprendizaje y del workflow. */
    private val ID_DE_GRAPH = Regex("wf_1\\d{12}")
    /** GPT-Live: la llamada a una herramienta (spec 002) y la delegación, en base62. */
    private val ID_DE_GPT_LIVE = Regex("call_[A-Za-z0-9]{24}|item_[A-Za-z0-9]{21}")
    /** OpenAI Responses: la respuesta y el mensaje, medidos en el teléfono. */
    private val ID_DE_OPENAI = Regex("(?:resp|msg)_[0-9a-f]{50}")
    /** Gemini Files API: el nombre del video subido (`GeminiVideo.kt`). */
    private val ID_DE_GEMINI = Regex("files/[a-z0-9]{12}")

    private val NUMERO = Regex("-?\\d+(?:[.,]\\d+)?(?:/\\d+)?(?:ms|s|KB|MB|%)?")
    /** Las unidades pegadas que escriben los logs de verdad. `B` y `h` no: `301B` es un portal y `1234h` un pin (510). */
    private val CON_UNIDAD = Regex("-?\\d+(?:[.,]\\d+)?(?:ms|s|KB|MB|%)")
    private val EXCEPCION = Regex("[A-Z][A-Za-z0-9]*(?:Exception|Error)")
    private val VERSION = Regex("\\d{1,4}(?:\\.\\d{1,4}){0,3}(?:-(?:debug|release|beta\\d*|rc\\d*|alpha\\d*))?")
    private val TAG_DE_VOZ = Regex("voz-[a-z]{1,16}")
    private const val FIN_DE_PALABRA = "(?![\\p{L}\\p{N}_])"
    private val FRASES_JUNTAS = FRASES.toSet()

    /** Parte un mensaje en trozos, de izquierda a derecha y sin huecos: cada carácter cae en exactamente un trozo. */
    private val TROZO = Regex(
        listOf(
            "(?:" + FRASES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" + FIN_DE_PALABRA,
            UUID.pattern,
            MARCA.pattern,
            "#[0-9A-Za-z]+",
            CELDA.pattern,
            COORDENADAS.pattern,
            "/api/v\\d+(?:/[A-Za-z0-9_%.\\-]+)*",
            ID_CON_PREFIJO.pattern + FIN_DE_PALABRA,
            "computer-use",
            "car\\.",
            NUMERO.pattern + FIN_DE_PALABRA,
            "[\\p{L}\\p{M}\\p{N}_]+",
            "\\s+",
            "[\\s\\S]",
        ).joinToString("|"),
    )

    /* ---------- Lo que sale ---------- */

    /** El tag, si es de la lista cerrada o de la voz; si no, [OTRO]. */
    fun tag(tag: String): String = if (tag.length <= TOPE_DE_TAG && (tag in TAGS || TAG_DE_VOZ.matches(tag))) tag else OTRO

    /** Lo que de [mensaje] puede salir del teléfono. Nunca lanza y nunca pasa de [TOPE_DE_MENSAJE] caracteres. */
    fun mensaje(mensaje: String): String {
        val trozos = TROZO.findAll(mensaje).map { Trozo(it.value, clase(it.value)) }.toList()
        medidas(trozos)
        return acotado(unir(trozos))
    }

    /** Un texto libre como medida: su largo en caracteres. */
    fun largo(texto: String): String = "${caracteres(texto)} caracteres"

    /** Las filas de `graph_exec_logs`: cuatro claves, y el tag y el mensaje ya pasados por la puerta. */
    fun filasDeLog(dispositivo: String, lote: List<LineaDeLog>): String = JsonArray(
        lote.map { linea ->
            buildJsonObject {
                put("device_id", opaco(dispositivo))
                put("prompt_id", opaco(linea.pedido))
                put("tag", JsonPrimitive(tag(linea.tag)))
                put("message", JsonPrimitive(mensaje(linea.mensaje)))
            }
        },
    ).toString()

    /** El cuerpo de `graph_upsert_prompt`: al abrir sin [resumen]; al cerrar con él y `p_finished`. */
    fun cuerpoDePedido(id: String, dispositivo: String, usuario: String, pedido: String, via: String, estado: String, resumen: String? = null): String =
        buildJsonObject {
            put("p_id", opaco(id))
            put("p_device_id", opaco(dispositivo))
            put("p_user_name", JsonPrimitive(largo(usuario)))
            put("p_prompt", JsonPrimitive(largo(pedido)))
            put("p_source", JsonPrimitive(if (via in VIAS) via else OTRO))
            put("p_status", JsonPrimitive(if (estado in ESTADOS) estado else OTRO))
            if (resumen != null) {
                put("p_summary", JsonPrimitive(largo(resumen)))
                put("p_finished", JsonPrimitive(true))
            }
        }.toString()

    /** El cuerpo de `graph_upsert_app_user`: del nombre y del modelo, su largo; la versión, si tiene forma de versión. */
    fun cuerpoDeUsuario(dispositivo: String, nombre: String, modelo: String, version: String): String =
        buildJsonObject {
            put("p_device_id", opaco(dispositivo))
            put("p_display_name", JsonPrimitive(largo(nombre)))
            put("p_device_model", JsonPrimitive(largo(modelo)))
            put("p_app_version", JsonPrimitive(if (VERSION.matches(version)) version else largo(version)))
        }.toString()

    /* ---------- Cómo decide ---------- */

    private enum class Clase { QUEDA, SUELTO, CAE }

    private class Trozo(val texto: String, var clase: Clase)

    private fun opaco(id: String?): JsonElement = if (id != null && UUID.matches(id)) JsonPrimitive(id) else JsonNull

    private fun esEspacio(texto: String) = texto.all { it.isWhitespace() }

    /** Lo que un trozo es por sí solo. Números, sustantivos, prefijos y descriptores caen hasta que [medidas] los junte. */
    private fun clase(t: String): Clase = when {
        esEspacio(t) || t in SUELTOS -> Clase.SUELTO
        t in FRASES_JUNTAS || t in SIGNOS || t in EVENTOS || t == "computer-use" -> Clase.QUEDA
        UUID.matches(t) || MARCA.matches(t) || SELLO.matches(t) || CELDA.matches(t) || COORDENADAS.matches(t) || EXCEPCION.matches(t) -> Clase.QUEDA
        t.startsWith("/api/") -> if (esRutaConocida(t)) Clase.QUEDA else Clase.CAE
        else -> if (esIdConPrefijo(t)) Clase.QUEDA else Clase.CAE
    }

    /**
     * Un id con prefijo, solo con la forma de su productor: una palabra con una cifra (`wf-anapaula1`), un número
     * (`ses-3001234567`) u otro largo no lo son. Base62 de verdad trae mayúsculas y minúsculas; un nombre de Gemini, letras y cifras.
     */
    private fun esIdConPrefijo(t: String) = when {
        ID_DE_GRAPH.matches(t) || ID_DE_OPENAI.matches(t) -> true
        ID_DE_GPT_LIVE.matches(t) -> t.substringAfter('_').let { id -> id.any { it.isUpperCase() } && id.any { it.isLowerCase() } }
        ID_DE_GEMINI.matches(t) -> t.substringAfter('/').let { id -> id.any { it.isDigit() } && id.any { it.isLetter() } }
        else -> false
    }

    private fun esRutaConocida(ruta: String) = ruta.split('/').drop(1).all { s ->
        s in RUTAS || UUID.matches(s) || (s.length in 1..CIFRAS_EN_RUTA && s.all { it.isDigit() }) || esIdConPrefijo(s)
    }

    /** Un número queda solo junto a lo que lo vuelve medida, y eso que lo sostiene queda con él. */
    private fun medidas(trozos: List<Trozo>) {
        val visibles = trozos.filterNot { esEspacio(it.texto) }
        fun en(k: Int) = visibles.getOrNull(k)
        val tapados = enSerie(visibles)
        for ((k, numero) in visibles.withIndex()) {
            if (!NUMERO.matches(numero.texto) || k in tapados) continue
            val cifras = cifras(numero.texto)
            val fraccion = '/' in numero.texto
            val anterior = en(k - 1)
            val prefijo = if (anterior?.texto == "=" || anterior?.texto == ":") en(k - 2) else anterior
            var queda = false
            val siguiente = en(k + 1)
            if (!fraccion && cifras <= CIFRAS_CON_UNIDAD && siguiente != null && sostiene(siguiente.texto, cifras)) {
                queda = true
                siguiente.clase = Clase.QUEDA
                val descriptor = en(k - 2)
                if (anterior?.texto == "de" && descriptor != null && descriptor.texto in DESCRIPTORES) {
                    anterior.clase = Clase.QUEDA
                    descriptor.clase = Clase.QUEDA
                }
            }
            if (!fraccion && cifras <= CIFRAS_CON_UNIDAD && CON_UNIDAD.matches(numero.texto)) queda = true
            val topeDelPrefijo = if (prefijo?.texto in PREFIJOS_DE_PASO) CIFRAS_DE_PASO else CIFRAS_CON_UNIDAD
            if (prefijo != null && prefijo.texto in PREFIJOS && cifras <= topeDelPrefijo && (queda || cifras <= CIFRAS_CON_PREFIJO)) {
                queda = true
                prefijo.clase = Clase.QUEDA
            }
            // Un contador `N/M` que abre la línea («  3/7 👁 consciente»): lo escribe la plantilla, no la persona.
            if (fraccion && k == 0 && cifras <= 6) queda = true
            if (queda) numero.clase = Clase.QUEDA
        }
        // `primera=—`: la clave de una medida que no hubo.
        for ((k, clave) in visibles.withIndex()) {
            if (clave.texto in PREFIJOS && en(k + 1)?.texto.let { it == "=" || it == ":" } && en(k + 2)?.texto == "—") clave.clase = Clase.QUEDA
        }
    }

    /** Lo que va detrás de un número y lo vuelve medida; la `s` suelta, solo detrás de un número chico. */
    private fun sostiene(t: String, cifras: Int) = t in SUSTANTIVOS || (t == "s" && cifras <= CIFRAS_CON_S_SUELTA)

    private fun cifras(t: String) = t.count { it.isDigit() }

    /**
     * Los números de cada serie —números seguidos, con sus unidades, sus sustantivos y la puntuación que los parte— cuyas cifras
     * juntas llegan a [CIFRAS_EN_SERIE]: `300 s 123 s 4567 s` es un teléfono, no tres medidas, y `12.345.678 caracteres` una
     * cédula. Un signo o un prefijo cortan la serie, porque así se escriben las medidas de verdad (`llamadas=5 distintas=3`,
     * `3 turnos · 12s`).
     */
    private fun enSerie(visibles: List<Trozo>): Set<Int> {
        val tapados = mutableSetOf<Int>()
        var k = 0
        while (k < visibles.size) {
            val numeros = mutableListOf<Int>()
            while (k < visibles.size) {
                val t = visibles[k].texto
                if (NUMERO.matches(t)) numeros += k
                else if (numeros.isEmpty() || !(t in SUSTANTIVOS || t in UNIDADES_SUELTAS || t in PARTEN_UN_NUMERO)) break
                k++
            }
            if (numeros.sumOf { cifras(visibles[it].texto) } >= CIFRAS_EN_SERIE) tapados += numeros
            if (numeros.isEmpty()) k++
        }
        return tapados
    }

    /** Lo que queda, tal cual; cada tramo que cae, con lo suelto de adentro, como su largo. */
    private fun unir(trozos: List<Trozo>): String {
        val sale = StringBuilder()
        val suelto = StringBuilder()
        var tramo = -1
        for (t in trozos) {
            when (t.clase) {
                Clase.SUELTO -> suelto.append(t.texto)
                Clase.CAE -> {
                    tramo = if (tramo < 0) { sale.append(suelto); caracteres(t.texto) } else tramo + caracteres(suelto) + caracteres(t.texto)
                    suelto.clear()
                }
                Clase.QUEDA -> {
                    if (tramo >= 0) marca(sale, tramo)
                    tramo = -1
                    sale.append(suelto).append(t.texto)
                    suelto.clear()
                }
            }
        }
        if (tramo >= 0) marca(sale, tramo)
        return sale.append(suelto).toString()
    }

    /** `‹N›`, con N hasta [MARCA_MAXIMA]: una marca más larga no volvería a pasar como marca. */
    private fun marca(sale: StringBuilder, tramo: Int) {
        sale.append('‹').append(minOf(tramo, MARCA_MAXIMA)).append('›')
    }

    /** A lo más [TOPE_DE_MENSAJE] caracteres, sin partir un carácter ni una marca `‹N›`. */
    private fun acotado(s: String): String {
        if (caracteres(s) <= TOPE_DE_MENSAJE) return s
        var corte = 0
        var vistos = 0
        while (corte < s.length && vistos < TOPE_DE_MENSAJE - 1) {
            corte += if (s[corte].isHighSurrogate() && corte + 1 < s.length && s[corte + 1].isLowSurrogate()) 2 else 1
            vistos++
        }
        val abre = s.lastIndexOf('‹', corte - 1)
        if (abre > s.lastIndexOf('›', corte - 1)) corte = abre
        return s.substring(0, corte) + "…"
    }

    /** Caracteres de verdad: un emoji fuera del plano básico es uno, no sus dos mitades UTF-16. */
    private fun caracteres(s: CharSequence): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}
