package graph.core.contrato

import graph.core.domain.GraphLog
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.CuentaDePeticion
import graph.core.precision.Freno
import graph.core.precision.TopeDeIntentos
import graph.core.telemetria.LineaDeLog
import graph.core.telemetria.PuertaDeTelemetria
import graph.core.voz.TelemetriaDeVoz
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * DEL TELÉFONO SOLO SALEN MEDIDAS (docs/specs/005, promesas 501-506). Se juzga la puerta pura con las líneas reales de cada
 * fuga del diagnóstico, sembradas con secretos que no se parecen a nada del vocabulario (`Zorbax`, `Qwyk`, `7731`): si un trozo
 * de tres caracteres de un secreto aparece en lo que sale, salió.
 */
class Contrato005SoloSalenMedidas {

    companion object {
        val PROMESAS = mapOf(
            501 to "De una línea del log a la telemetría remota solo sale lo que tiene forma de medida permitida: un nombre de la lista cerrada, una medida o un id opaco; todo texto libre sale como su largo ‹N› y un tag fuera de la lista sale como «otro».",
            502 to "Un número sale del teléfono solo como medida: pegado a su unidad, detrás de HTTP, intento, turno o una clave=, o delante de caracteres, bytes, turnos o acciones. Una clave, un teléfono o una cédula dentro de un texto no salen.",
            503 to "Un id sale solo si es opaco: un UUID, un sello # de 8 hex con alguna letra, una celda:x,y, una ruta de la API hecha de segmentos conocidos e ids, o un prefijo cerrado (call_, item_, wf_…) con la forma de quien lo produce. Una palabra tras el prefijo, un paquete de app o un número largo no son ids.",
            504 to "Lo que se sube de un pedido y de un usuario lo arma la puerta: el pedido, el resumen, el nombre y el modelo del teléfono viajan como su largo; el estado y la vía, de una lista cerrada; los ids, solo si son UUID; y ninguna clave fuera de las de la RPC.",
            505 to "Cada fila de log que sale la arma la puerta, con solo device_id, prompt_id, tag y message. Detrás de TelemetriaDeVoz la medida de la voz sobrevive y ni una palabra de lo dicho sale; pasar dos veces por la puerta da lo mismo que una, y un mensaje enorme sale acotado.",
            506 to "Las líneas de precisión que ya son medidas pasan sin tocar precisión: la de peticion entera con su destino sellado, en celda o por su largo; las del motor con sus largos, turnos, acciones y tiempos; y del freno, el tope y el workflow, su medida, su sello y el tipo de la excepción.",
            508 to "Un sello sale solo si tiene al menos una letra: # con ocho cifras decimales es un pedido, una factura o una cédula y sale como su largo; y el sello de verdad de precisión lleva siempre una letra, así que ninguna línea real de peticion pierde su destino sellado.",
            509 to "Un id con prefijo sale solo con la forma y el largo de quien lo produce: wf_ con 13 cifras de Graph, call_ de 24 e item_ de 21 en base62, resp_ y msg_ de 50 hex, files/ de 12; una palabra con una cifra, un número u otro largo no es id, y una ruta de la API con un segmento de texto o de más de 6 cifras sale como su largo.",
            510 to "Una unidad sostiene un número solo como la escriben los logs: ms, s, KB, MB y % pegados, ms, min, KB y MB con espacio, y s con espacio hasta 3 cifras; B y h no son unidad, números seguidos cuyas cifras juntas llegan a 7 se tapan, y paso y turno sostienen a lo más 3 cifras.",
            511 to "Una coordenada, una celda y una marca ‹N› sostienen a lo más 5 cifras por componente: con más, también una marca escrita a mano, salen como su largo; un tramo de más de 99999 caracteres sale como ‹99999›, así que pasar dos veces por la puerta sigue dando lo mismo.",
        )

        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    private val puerta = PuertaDeTelemetria

    /** Largo en caracteres de verdad (un emoji es uno), como lo cuenta la puerta. */
    private fun n(s: String): Int {
        var k = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            k++
        }
        return k
    }

    private fun tramo(s: String) = "‹${n(s)}›"

    /** Ningún trozo de 3 caracteres (sin espacios) de ningún secreto sale. Los secretos no comparten trigramas con la puerta. */
    private fun sinFuga(p: Int, salida: String, vararg secretos: String) {
        for (secreto in secretos) {
            for (i in 0..(secreto.length - 3)) {
                val trozo = secreto.substring(i, i + 3)
                if (trozo.any { it.isWhitespace() || it.isSurrogate() }) continue
                assertTrue(trozo !in salida, promesa(p) + " · «$trozo» (de «$secreto») salió: $salida")
            }
        }
    }

    private val SECRETOS = arrayOf("Zorbax", "Qwyk", "7731", "3009876542", "1037246")

    @Test
    fun promesa501() {
        val p = 501
        // Cada fuga del diagnóstico, con la forma exacta con la que la escribe la app.
        val fugas = listOf(
            "app" to "Pídeme: escríbele a Zorbax que la clave es Qwyk",
            "run" to "▶ \"escríbele a Zorbax que la clave es Qwyk\"",
            "run" to "■ 3 turnos · 5 acciones · 12s · Le escribí a Zorbax: nos vemos a las 8",
            "run" to "🗣 Listo, le escribí a Zorbax",
            "run" to "❓ ¿Le digo a Zorbax que sí?",
            "run" to "＋ audio durante ejecución: \"Zorbax, apúntalo\"",
            "voice" to "en vivo: \"dile a Zorbax que Qwyk\"",
            "meeting" to "🎧 \"Zorbax dijo que la clave es 7731\"",
            "run" to "🔗 contexto pendiente (offer): \"¿le escribo a Zorbax?\"",
            "run" to "🙋 propongo tras ejecutar: ¿Le mando la foto a Zorbax?",
            "run" to "🤝 acción anticipada: llamar a Zorbax",
            "run" to "🔮 anticipo: Zorbax suele pedir esto · send_sms · worth=7",
            "memory" to "🧠 recordado [WhatsApp]: Zorbax es mi hermana",
            "memory" to "🧠 aprendido de tu respuesta [com.whatsapp]: Qwyk es la clave",
            "run" to "turno 4 · 1234ms · graph · \"Chat con Zorbax · escribiendo\" · decide: send_sms",
            "mcp" to "tap \"Enviar a Zorbax\": no está en la pantalla actual (com.whatsapp)",
            "learn" to "señal clic \"Zorbax Pérez\" en com.whatsapp · 4 señales",
            "bug-ui" to "❌ \"Zorbax\": tocaste 540,1200 pero el nodo es Qwyk",
            "workflow" to "  3/7 🧩 subconsciente \"Qwyk Zorbax\"",
            "api" to "portapapeles ← \"Qwyk 7731\"",
            "assist" to "▶ acción: \"llama a Zorbax al 3009876542\"",
        )
        for ((tag, linea) in fugas) {
            val sale = puerta.mensaje(linea)
            sinFuga(p, sale, *SECRETOS, "whatsapp", "WhatsApp", "hermana", "clave")
            assertEquals(tag, puerta.tag(tag), promesa(p) + " · el tag «$tag» es de la lista")
        }

        // El formato: lo libre es su largo, y los tramos se juntan con sus espacios y su puntuación.
        assertEquals("${tramo("Pídeme")}: ${tramo("escríbele a Zorbax que la clave es Qwyk")}", puerta.mensaje(fugas[0].second), promesa(p))
        assertEquals(
            "■ 3 turnos · 5 acciones · 12s · ${tramo("Le escribí a Zorbax")}: ${tramo("nos vemos a las 8")}",
            puerta.mensaje(fugas[2].second), promesa(p),
        )
        assertEquals("＋ ${tramo("audio durante ejecución")}: \"${tramo("Zorbax, apúntalo")}\"", puerta.mensaje(fugas[5].second), promesa(p))
        assertEquals(
            "tap \"${tramo("Enviar a Zorbax")}\": ${tramo("no está en la pantalla actual")} (${tramo("com.whatsapp")})",
            puerta.mensaje(fugas[15].second), promesa(p),
        )
        assertEquals("  3/7 🧩 subconsciente \"${tramo("Qwyk Zorbax")}\"", puerta.mensaje(fugas[18].second), promesa(p))
        // Lo que ya es de la lista cerrada sale igual.
        for (igual in listOf("  ▪ MCP send_sms → ok", "sesión cerrada", "HTTP 503 transitorio", "▶ ok · done")) {
            assertEquals(igual, puerta.mensaje(igual), promesa(p) + " · «$igual»")
        }
        // Un emoji que no es marcador cae como texto.
        assertEquals("‹1›", puerta.mensaje("👋"), promesa(p) + " · un emoji suelto")

        // Tags: la lista cerrada y los de la voz quedan; el resto es «otro».
        for (t in listOf("run", "app", "graph", "workflow", "memory", "voz-viva", "voz-canal", "aprendizaje", "leccion", "peticion", "tope", "freno", "puerta")) {
            assertEquals(t, puerta.tag(t), promesa(p) + " · el tag «$t»")
        }
        for (t in listOf("Zorbax", "run Zorbax", "voz-Zorbax Qwyk", "", "a".repeat(80))) {
            assertEquals(PuertaDeTelemetria.OTRO, puerta.tag(t), promesa(p) + " · el tag «$t»")
        }
    }

    @Test
    fun promesa502() {
        val p = 502
        assertEquals(tramo("mi clave es 7731"), puerta.mensaje("mi clave es 7731"), promesa(p) + " · una clave")
        assertEquals(tramo("llama al 3009876542"), puerta.mensaje("llama al 3009876542"), promesa(p) + " · un teléfono")
        assertEquals(tramo("CC 1037246 de Zorbax"), puerta.mensaje("CC 1037246 de Zorbax"), promesa(p) + " · una cédula")
        assertEquals(tramo("compra 3 panes"), puerta.mensaje("compra 3 panes"), promesa(p) + " · un número sin medida")
        assertEquals("‹4›", puerta.mensaje("7731"), promesa(p) + " · un número suelto")
        // Un prefijo de medida no sostiene un número largo: «intento 3009876542» no es un intento.
        sinFuga(p, puerta.mensaje("intento 3009876542"), "3009876542")
        sinFuga(p, puerta.mensaje("HTTP: 1037246"), "1037246")
        sinFuga(p, puerta.mensaje("turno 7731 de 3009876542 caracteres"), "3009876542")

        for (medida in listOf(
            "HTTP 503", "HTTP -1", "HTTP 0 · sin reintento", "reintento 2/3", "intento 1/3", "1600ms", "12s", "3.5s", "40%", "120 KB",
            "42 caracteres", "9 car.", "120 bytes", "3 turnos · 5 acciones", "turno 4 · 1234ms", "llamadas=5", "primera=120 ms",
            "respuesta de 120 bytes", "pedido de 42 caracteres", "status: 503",
        )) {
            assertEquals(medida, puerta.mensaje(medida), promesa(p) + " · «$medida» es medida")
        }
        assertEquals("HTTP 503 transitorio · reintento 2/3 ‹2› 1600ms", puerta.mensaje("HTTP 503 transitorio · reintento 2/3 en 1600ms"), promesa(p))
    }

    @Test
    fun promesa503() {
        val p = 503
        for (id in listOf(
            "3f2c9a1e-7b4d-4e8a-9c21-0d5e6f7a8b9c", "#a1b2c3d4", "celda:3,4", "celda:-1,12", "tap(120,300)", "swipe(1,2→3,40)",
            // Los ids con prefijo, con la forma real de quien los produce (509): Graph, GPT-Live, OpenAI y Gemini.
            "wf_1789054200000", "call_ydaLTWADFkH6AtEXUxsfdltF", "item_ENQCUyla2TB9mFPFUmDHi", "files/a8k2x9m4q7zt",
            "resp_07dae34b6353cdd7006aa897181ecc87d2bf67e8e76fc2eb17",
            "/api/v1/workflows/wf_1789054200000", "/api/v1/learning/sessions/wf_1789054200000/steps", "/api/v1/workflows/17/plan", "/api/v1/agent/turn",
        )) {
            assertEquals(id, puerta.mensaje(id), promesa(p) + " · «$id» es opaco")
        }
        for (noId in listOf("wf-zorbax", "#Zorbax12", "#a1b2c3", "com.whatsapp", "3009876542", "ses-Qwyk", "files/Zorbax", "/api/v1/workflows/registrar-a-zorbax")) {
            assertEquals(tramo(noId), puerta.mensaje(noId), promesa(p) + " · «$noId» no es un id")
        }
        assertEquals("${tramo("la sesión")} wf_1789054200000 ${tramo("para")} wf_1789054140000", puerta.mensaje("la sesión wf_1789054200000 para wf_1789054140000"), promesa(p) + " · los ids entre texto quedan")
    }

    @Test
    fun promesa504() {
        val p = 504
        val id = "3f2c9a1e-7b4d-4e8a-9c21-0d5e6f7a8b9c"
        val dispositivo = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val pedido = "escríbele a Zorbax al 3009876542 👋"

        val abre = Json.parseToJsonElement(puerta.cuerpoDePedido(id, dispositivo, "Zorbax Pérez", pedido, "burbuja", "running")).jsonObject
        assertEquals(setOf("p_id", "p_device_id", "p_user_name", "p_prompt", "p_source", "p_status"), abre.keys, promesa(p) + " · claves al abrir")
        assertEquals(id, abre.getValue("p_id").jsonPrimitive.content, promesa(p))
        assertEquals(dispositivo, abre.getValue("p_device_id").jsonPrimitive.content, promesa(p))
        assertEquals("${n(pedido)} caracteres", abre.getValue("p_prompt").jsonPrimitive.content, promesa(p) + " · el pedido es su largo")
        assertEquals("12 caracteres", abre.getValue("p_user_name").jsonPrimitive.content, promesa(p) + " · el nombre es su largo")
        assertEquals("burbuja", abre.getValue("p_source").jsonPrimitive.content, promesa(p))
        assertEquals("running", abre.getValue("p_status").jsonPrimitive.content, promesa(p))
        sinFuga(p, abre.toString(), *SECRETOS)

        val resumen = "Le escribí a Zorbax: la clave es Qwyk"
        for (estado in listOf("ok", "error", "cancelled")) {
            val cierra = Json.parseToJsonElement(puerta.cuerpoDePedido(id, dispositivo, "Zorbax", pedido, "app", estado, resumen)).jsonObject
            assertEquals(setOf("p_id", "p_device_id", "p_user_name", "p_prompt", "p_source", "p_status", "p_summary", "p_finished"), cierra.keys, promesa(p) + " · claves al cerrar")
            assertEquals("${n(resumen)} caracteres", cierra.getValue("p_summary").jsonPrimitive.content, promesa(p) + " · el resumen es su largo")
            assertEquals(estado, cierra.getValue("p_status").jsonPrimitive.content, promesa(p))
            assertEquals(JsonPrimitive(true), cierra.getValue("p_finished"), promesa(p))
            sinFuga(p, cierra.toString(), *SECRETOS)
        }
        val raro = Json.parseToJsonElement(puerta.cuerpoDePedido("Zorbax", "Qwyk 7731", "", "", "Zorbax", "borrado por Qwyk", "")).jsonObject
        assertEquals(JsonNull, raro.getValue("p_id"), promesa(p) + " · un id que no es UUID no viaja")
        assertEquals(JsonNull, raro.getValue("p_device_id"), promesa(p))
        assertEquals(PuertaDeTelemetria.OTRO, raro.getValue("p_source").jsonPrimitive.content, promesa(p) + " · vía fuera de la lista")
        assertEquals(PuertaDeTelemetria.OTRO, raro.getValue("p_status").jsonPrimitive.content, promesa(p) + " · estado fuera de la lista")
        sinFuga(p, raro.toString(), *SECRETOS)

        val usuario = Json.parseToJsonElement(puerta.cuerpoDeUsuario(dispositivo, "Zorbax", "Xiaomi M2101K7BL", "0.42")).jsonObject
        assertEquals(setOf("p_device_id", "p_display_name", "p_device_model", "p_app_version"), usuario.keys, promesa(p) + " · claves del usuario")
        assertEquals("6 caracteres", usuario.getValue("p_display_name").jsonPrimitive.content, promesa(p) + " · el nombre es su largo")
        assertEquals("16 caracteres", usuario.getValue("p_device_model").jsonPrimitive.content, promesa(p) + " · el modelo es su largo")
        assertEquals("0.42", usuario.getValue("p_app_version").jsonPrimitive.content, promesa(p) + " · la versión es un número")
        val versionRara = Json.parseToJsonElement(puerta.cuerpoDeUsuario(dispositivo, "Zorbax", "x", "0.42 Zorbax")).jsonObject
        assertEquals("11 caracteres", versionRara.getValue("p_app_version").jsonPrimitive.content, promesa(p) + " · una versión con texto es su largo")
        assertEquals("7 caracteres", puerta.largo("Zorbax👋"), promesa(p) + " · el largo cuenta caracteres")
    }

    @Test
    fun promesa505() {
        val p = 505
        val dispositivo = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val pedido = "3f2c9a1e-7b4d-4e8a-9c21-0d5e6f7a8b9c"
        val dichoLocal = "usuario dijo: mi clave es Qwyk 7731 y Zorbax 👋"
        val vozRemota = TelemetriaDeVoz.paraRemoto("voz-viva", dichoLocal)!!
        val eventoRemoto = TelemetriaDeVoz.paraRemoto("voz-canal", "← {\"type\":\"session.algo\",\"text\":\"Zorbax Qwyk\"}")!!
        val lote = listOf(
            LineaDeLog(pedido, "app", "Pídeme: escríbele a Zorbax"),
            LineaDeLog(null, "tag de Zorbax", "HTTP 503 · reintento 2/3"),
            LineaDeLog(pedido, "voz-viva", vozRemota),
            LineaDeLog(pedido, "voz-canal", eventoRemoto),
            LineaDeLog("Qwyk", "run", "▶ objetivo de 42 caracteres"),
        )
        val filas = Json.parseToJsonElement(puerta.filasDeLog(dispositivo, lote)).jsonArray
        assertEquals(lote.size, filas.size, promesa(p) + " · una fila por línea")
        for ((i, fila) in filas.withIndex()) {
            val o = fila.jsonObject
            assertEquals(setOf("device_id", "prompt_id", "tag", "message"), o.keys, promesa(p) + " · claves de la fila $i")
            assertEquals(dispositivo, o.getValue("device_id").jsonPrimitive.content, promesa(p))
            assertEquals(puerta.mensaje(lote[i].mensaje), o.getValue("message").jsonPrimitive.content, promesa(p) + " · el mensaje es el de la puerta")
            assertEquals(puerta.tag(lote[i].tag), o.getValue("tag").jsonPrimitive.content, promesa(p) + " · el tag es el de la puerta")
        }
        assertEquals(pedido, filas[0].jsonObject.getValue("prompt_id").jsonPrimitive.content, promesa(p))
        assertEquals(JsonNull, filas[1].jsonObject.getValue("prompt_id"), promesa(p) + " · sin pedido, null")
        assertEquals(JsonNull, filas[4].jsonObject.getValue("prompt_id"), promesa(p) + " · un pedido que no es UUID, null")
        assertEquals(PuertaDeTelemetria.OTRO, filas[1].jsonObject.getValue("tag").jsonPrimitive.content, promesa(p))
        sinFuga(p, filas.toString(), *SECRETOS)
        assertEquals(1, filas.count { (it as JsonObject).getValue("message").jsonPrimitive.content == "usuario dijo: ${n("mi clave es Qwyk 7731 y Zorbax 👋")} caracteres" }, promesa(p) + " · la medida de la voz sobrevive: $filas")
        assertTrue(filas[3].jsonObject.getValue("message").jsonPrimitive.content.endsWith(" car.)"), promesa(p) + " · el largo del evento de la voz sobrevive: ${filas[3]}")
        sinFuga(p, filas.toString(), dichoLocal.substringAfter(": "))

        // Dos veces por la puerta es lo mismo que una.
        val muestras = listOf(
            "Pídeme: escríbele a Zorbax que la clave es Qwyk", "■ 3 turnos · 5 acciones · 12s · Le escribí a Zorbax: nos vemos a las 8",
            "tap \"Enviar a Zorbax\": no está (com.whatsapp)", "llamadas=5 distintas=3 intentos_max=2 «#a1b2c3d4» primera=— ultima=900 ms",
            "¿le escribo a Zorbax? 👋 · HTTP 503", vozRemota, eventoRemoto, "‹12› y ‹3›", "  3/7 👁 consciente",
        )
        for (m in muestras) {
            val una = puerta.mensaje(m)
            assertEquals(una, puerta.mensaje(una), promesa(p) + " · idempotente con «$m»")
        }

        // Un mensaje enorme sale acotado, sin un ‹ abierto y sin lo que traía.
        val enorme = "Zorbax Qwyk 7731 · HTTP 503 · ".repeat(2_000)
        val sale = puerta.mensaje(enorme)
        assertTrue(n(sale) <= PuertaDeTelemetria.TOPE_DE_MENSAJE + 1, promesa(p) + " · acotado: ${n(sale)} caracteres")
        assertEquals(sale.count { it == '‹' }, sale.count { it == '›' }, promesa(p) + " · ningún ‹ queda abierto")
        sinFuga(p, sale, *SECRETOS)
        assertTrue(n(puerta.filasDeLog(dispositivo, listOf(LineaDeLog(null, "run", enorme)))) < PuertaDeTelemetria.TOPE_DE_MENSAJE + 200, promesa(p) + " · y la fila también")
    }

    @Test
    fun promesa506() {
        val p = 506
        // Formatos literales de yokh/precision: CuentaDePeticion.cerrar, TopeDeIntentos.enLog, Engine y ArmadoDeEjecucion.
        for (linea in listOf(
            "llamadas=5 distintas=3 intentos_max=2 «#a1b2c3d4» primera=120 ms ultima=900 ms desde_peticion=1500 ms rechazadas=0 retiradas=1",
            "llamadas=1 distintas=1 intentos_max=0 «—» primera=— ultima=— desde_peticion=— rechazadas=0 retiradas=0",
            "llamadas=4 distintas=2 intentos_max=3 «celda:12,-3» primera=80 ms ultima=2400 ms desde_peticion=— rechazadas=1 retiradas=0",
            "llamadas=2 distintas=2 intentos_max=2 «campo de 12 caracteres» primera=10 ms ultima=20 ms desde_peticion=30 ms rechazadas=0 retiradas=0",
            "llamadas=2 distintas=1 intentos_max=2 «nombre de 7 caracteres» primera=10 ms ultima=20 ms desde_peticion=30 ms rechazadas=0 retiradas=0",
            "llamadas=2 distintas=1 intentos_max=2 «destino de 30 caracteres» primera=10 ms ultima=20 ms desde_peticion=30 ms rechazadas=0 retiradas=0",
            "llamadas=2 distintas=1 intentos_max=2 «nodo sin id estructural» primera=10 ms ultima=20 ms desde_peticion=30 ms rechazadas=2 retiradas=0",
            "▶ objetivo de 42 caracteres",
            "🗣 18 caracteres",
            "❓ pregunta de 12 caracteres",
            "■ 3 turnos · 5 acciones · 12s · resumen de 40 caracteres",
            "turno 4 · 1234ms · graph · \"—\" · decide: send_sms",
            "  ▪ computer-use type(120,300) · 9 caracteres",
            "  ▪ computer-use open_app · 8 caracteres",
            "  ▪ MCP launch_app → ok",
            "  ▪ wait 500ms → ok",
            "  3/7 👁 consciente",
        )) {
            assertEquals(linea, puerta.mensaje(linea), promesa(p) + " · pasa entera: «$linea»")
        }
        val freno = puerta.mensaje("ya hay una tarea en curso: no abro otra corrida encima (pedido de 42 caracteres)")
        assertTrue(freno.startsWith("ya hay una tarea en curso: ") && freno.endsWith(" (pedido de 42 caracteres)"), promesa(p) + " · freno: $freno")
        val tope = puerta.mensaje("no paso «send_sms»: tercera entrada a «#0f1e2d3c», que ya falló dos veces en esta petición")
        assertTrue("«send_sms»" in tope && "«#0f1e2d3c»" in tope, promesa(p) + " · tope: $tope")
        val consciente = puerta.mensaje("step consciente falló (IllegalStateException)")
        assertTrue(consciente.endsWith("(IllegalStateException)") && "consciente" in consciente, promesa(p) + " · workflow: $consciente")
        val transitorio = puerta.mensaje("step consciente falló (SocketTimeoutException)")
        assertTrue(transitorio.endsWith("(SocketTimeoutException)") && "consciente" in transitorio, promesa(p) + " · workflow: $transitorio")
        // Las otras clases que la app emite de verdad y el panel tiene que poder distinguir: una URL mal escrita no es un corte
        // de red (`GraphTransport.causa`), un servidor que no habla HTTP tampoco (`CanalOkHttp.tipo`), y un aviso que revienta
        // al inicializarse llega como `Error` (`Freno.dile`).
        for (clase in listOf("MalformedURLException", "ProtocolException", "ExceptionInInitializerError")) {
            val linea = puerta.mensaje("step consciente falló ($clase)")
            assertTrue(linea.endsWith("($clase)") && "consciente" in linea, promesa(p) + " · workflow: $linea")
        }
        val desconocida = puerta.mensaje("step consciente falló (AnaMariaError)")
        assertTrue(desconocida.endsWith("(‹${n("AnaMariaError")}›)") && "consciente" in desconocida, promesa(p) + " · workflow: $desconocida")
        val ajena = puerta.mensaje("step consciente falló (JuanPerezException)")
        assertTrue(ajena.endsWith("(‹${n("JuanPerezException")}›)") && "consciente" in ajena, promesa(p) + " · workflow: $ajena")
        val etiquetaSellada = puerta.mensaje("✋ tercera entrada a «#9e8d7c6b» · 3 turnos · 4 acciones · 9s · no sigo")
        assertTrue(etiquetaSellada.endsWith("«#9e8d7c6b» · 3 turnos · 4 acciones · 9s · no sigo"), promesa(p) + " · motor: $etiquetaSellada")

        // Y con el código de verdad de precisión: si su formato cambia, esto lo ve antes que el panel. El sello lleva la llave
        // del proceso (8 hex al azar): de los secretos solo se buscan trozos que no pueden salir en hex.
        val personas = arrayOf("Zorbax", "zorbax", "Qwyk", "qwyk")
        val reloj = TestTimeSource()
        val lineas = mutableListOf<String>()
        val log = GraphLog { _, mensaje -> lineas += mensaje }
        val topeReal = TopeDeIntentos(48)
        val cuenta = CuentaDePeticion(reloj, log)
        for (destino in listOf(
            TopeDeIntentos.Destino.Nodo("a11y:id=com.whatsapp:id/send;cls=android.widget.Button;text=Enviar a Zorbax", "Enviar a Zorbax"),
            TopeDeIntentos.Destino.Celda(3, 4),
            topeReal.alEscribir("Teléfono de Zorbax"),
            TopeDeIntentos.Destino.Nombre("Zorbax Qwyk"),
            TopeDeIntentos.Destino.Nodo("a11y:text=Zorbax Qwyk", "Zorbax Qwyk"),
        )) {
            repeat(2) { cuenta.llamada("tap", topeReal.clave(destino), topeReal.enLog(destino)) }
            reloj += 120.milliseconds
            cuenta.resultado("tap", actuo = true)
            val linea = assertNotNull(cuenta.cerrar(), promesa(p) + " · la cuenta no dejó línea")
            assertEquals(linea, puerta.mensaje(linea), promesa(p) + " · la línea real de peticion pasa entera")
            sinFuga(p, linea, *personas)
        }
        corre {
            val armado = ArmadoDeEjecucion(Freno(log = log), log)
            val pedido = "escríbele a Zorbax al 3009876542"
            runCatching {
                armado.correr(pedido) {
                    runCatching { armado.correr("y a Qwyk también") { } }
                    armado.parar("píldora de Zorbax")
                }
            }
            val abierta = puerta.mensaje(assertNotNull(lineas.firstOrNull { it.startsWith("tarea abierta") }, promesa(p) + " · $lineas"))
            assertTrue(abierta.endsWith("(pedido de ${n(pedido)} caracteres)"), promesa(p) + " · la medida del pedido al abrir: $abierta")
            val rechazo = puerta.mensaje(assertNotNull(lineas.firstOrNull { it.startsWith("ya hay una tarea en curso") }, promesa(p) + " · $lineas"))
            assertTrue(rechazo.startsWith("ya hay una tarea en curso: ") && rechazo.endsWith("(pedido de ${n("y a Qwyk también")} caracteres)"), promesa(p) + " · el rechazo: $rechazo")
            for (l in lineas) sinFuga(p, puerta.mensaje(l), *personas, "3009876542")
        }
    }

    @Test
    fun promesa508() {
        val p = 508
        // Ocho cifras con # delante: un pedido, una factura, una cédula. No son un sello.
        for (numero in listOf("#12345678", "#40123456", "#10203040", "#00000000")) {
            assertEquals(tramo(numero), puerta.mensaje(numero), promesa(p) + " · «$numero» es un número")
        }
        assertEquals("${tramo("portapapeles")} ← \"${tramo("pedido #12345678")}\"", puerta.mensaje("portapapeles ← \"pedido #12345678\""), promesa(p))
        assertEquals(
            "${tramo("en vivo")}: \"${tramo("paga la factura #40123456 de Claro")}\"",
            puerta.mensaje("en vivo: \"paga la factura #40123456 de Claro\""), promesa(p),
        )
        // Un sello con alguna letra sigue siendo sello, esté donde esté la letra.
        for (sello in listOf("#a1b2c3d4", "#1234567f", "#0f1e2d3c")) {
            assertEquals(sello, puerta.mensaje(sello), promesa(p) + " · «$sello» es un sello")
        }

        // Y el de verdad. Ocho hex de un HMAC salen todo cifras una vez de cada ~43 ((10/16)^8): dos mil destinos distintos por el
        // TopeDeIntentos y la CuentaDePeticion reales no dejan escapar a un productor que no ponga su letra.
        val sello = Regex("#[0-9a-f]{8}")
        val tope = TopeDeIntentos(48)
        val cuenta = CuentaDePeticion(TestTimeSource(), GraphLog { _, _ -> })
        repeat(2_000) { i ->
            val destino = TopeDeIntentos.Destino.Nodo("a11y:id=com.app:id/fila_$i;cls=android.widget.TextView;text=Zorbax", "Zorbax")
            val sellado = tope.enLog(destino)
            assertTrue(sello.matches(sellado), promesa(p) + " · el destino no salió sellado: «$sellado»")
            assertTrue(sellado.any { it in 'a'..'f' }, promesa(p) + " · el sello de precisión salió todo cifras: «$sellado»")
            cuenta.llamada("tap", tope.clave(destino), sellado)
            cuenta.resultado("tap", actuo = true)
            val linea = assertNotNull(cuenta.cerrar(), promesa(p) + " · la cuenta no dejó línea")
            assertEquals(linea, puerta.mensaje(linea), promesa(p) + " · la línea real de peticion perdió su sello")
        }
    }

    @Test
    fun promesa509() {
        val p = 509
        // Lo que producen de verdad Graph (`wf_` y `Date.now()`, sesión y workflow), GPT-Live (llamada y delegación), OpenAI
        // Responses (medido en el teléfono) y Gemini (el video subido): suelto, en su ruta y en la línea que lo lleva.
        for (id in listOf(
            "wf_1789054200000", "call_ydaLTWADFkH6AtEXUxsfdltF", "item_ENQCUyla2TB9mFPFUmDHi",
            "resp_07dae34b6353cdd7006aa897181ecc87d2bf67e8e76fc2eb17", "msg_07dae34b6353cdd7006aa8971e8b6487d2a94a31ae5992d797",
            "files/a8k2x9m4q7zt",
            "/api/v1/workflows/wf_1789054200000", "/api/v1/learning/sessions/wf_1789054200000/steps",
            "/api/v1/workflows/3f2c9a1e-7b4d-4e8a-9c21-0d5e6f7a8b9c/plan", "/api/v1/workflows/123456/prepend-alignment",
        )) {
            assertEquals(id, puerta.mensaje(id), promesa(p) + " · «$id» es un id real")
        }
        assertEquals(
            "▶ ${tramo("enseñando")} (${tramo("sesión")} wf_1789054200000, ${tramo("workflow")} wf_1789054140000)",
            puerta.mensaje("▶ enseñando (sesión wf_1789054200000, workflow wf_1789054140000)"), promesa(p),
        )
        // Otro largo, otra forma, o una palabra con una cifra: no es id. Tampoco una ruta con un segmento así.
        for (noId in listOf(
            "ses-3001234567", "wf-anapaula1", "call_mama2", "files/3001234567", "wf_3001234567", "wf_17890542000001",
            "call_juanitaperezgomezdelrio1", "call_ydaLTWADFkH6AtEXUxsfdlt", "item_1", "item_anapaulagomezperez123", "resp_07dae34b",
            "msg_mamapaulina", "files/abc123", "files/mariapaulina", "sess_3001234567", "evt_1234567",
            "/api/v1/workflows/3001234567", "/api/v1/workflows/wf-anapaula1", "/api/v1/learning/sessions/ses-juanperez7/steps",
        )) {
            assertEquals(tramo(noId), puerta.mensaje(noId), promesa(p) + " · «$noId» no es un id")
        }
    }

    @Test
    fun promesa510() {
        val p = 510
        // Direcciones, teléfonos partidos y claves que se disfrazaban de medida.
        for (fuga in listOf(
            "el apartamento 301B de la calle 45 B", "marca 300 s 123 s 4567 s", "300 ms 123 4567", "B 12345678 B",
            "mi código de verificación es 482913 s", "pin 1234h", "12.345.678 caracteres", "nombre de 123456789 caracteres",
            "300 caracteres 123 caracteres 4567 caracteres", "7731 GB", "el código del paso 4521",
        )) {
            assertEquals(tramo(fuga), puerta.mensaje(fuga), promesa(p) + " · «$fuga» no es una medida")
        }
        assertEquals("${tramo("el código del paso 4521 y turno")}: ${tramo("9876")}", puerta.mensaje("el código del paso 4521 y turno: 9876"), promesa(p))

        // Las medidas como las escriben los logs de verdad: Engine, GraphBrain, Reintentos.corto, el freno, la voz y GeminiBrain.
        for (igual in listOf(
            "■ 3 turnos · 5 acciones · 12s · resumen de 40 caracteres", "turno 999 · 1234ms · graph", "paso 12", "reintento 2/3",
            "45KB", "3MB", "80%", "5 min", "10000 ms", "120 KB",
            "llamadas=5 distintas=3 intentos_max=2 «—» primera=120 ms ultima=900 ms desde_peticion=1500 ms rechazadas=0 retiradas=1",
        )) {
            assertEquals(igual, puerta.mensaje(igual), promesa(p) + " · «$igual» es medida")
        }
        for ((linea, sale) in listOf(
            "el tope de 90 s del arranque se agotó" to "${tramo("el tope de")} 90 s ${tramo("del arranque se agotó")}",
            "altavoz abierto: 24000 Hz mono PCM16, cola de 30 s" to "${tramo("altavoz abierto")}: ${tramo("24000 Hz mono PCM16, cola de")} 30 s",
            "la corrida no soltó en 5000 ms tras el alto" to "${tramo("la corrida no soltó en")} 5000 ms ${tramo("tras el alto")}",
            "pasaron 10000 ms sin apretón de manos" to "${tramo("pasaron")} 10000 ms ${tramo("sin apretón de manos")}",
            "interacción → HTTP 200 · 1234ms · envié 45KB de pantalla" to "${tramo("interacción")} → HTTP 200 · 1234ms · ${tramo("envié")} 45KB ${tramo("de pantalla")}",
        )) {
            assertEquals(sale, puerta.mensaje(linea), promesa(p) + " · «$linea»")
        }
    }

    @Test
    fun promesa511() {
        val p = 511
        for (igual in listOf("(4,60)", "tap(120,300)", "swipe(1,2→3,40)", "(99999,-99999)", "celda:12,-3", "celda:99999,99999", "‹12345›")) {
            assertEquals(igual, puerta.mensaje(igual), promesa(p) + " · «$igual» cabe")
        }
        for ((fuga, sale) in listOf(
            "(3001234,567)" to "(${tramo("3001234,567")})",
            "tap(123456,1)" to "tap(${tramo("123456,1")})",
            "swipe(1,2→3,400000)" to "swipe(${tramo("1,2")}→${tramo("3,400000")})",
            "celda:3001234567,12" to "${tramo("celda")}:${tramo("3001234567,12")}",
            "celda:1,123456" to "${tramo("celda")}:${tramo("1,123456")}",
            "‹3001234567›" to tramo("‹3001234567›"),
            "‹123456›" to tramo("‹123456›"),
        )) {
            assertEquals(sale, puerta.mensaje(fuga), promesa(p) + " · «$fuga»")
            assertEquals(sale, puerta.mensaje(sale), promesa(p) + " · idempotente con «$fuga»")
        }
        // Un tramo gigante se marca con el tope, y la marca vuelve a pasar igual.
        val gigante = puerta.mensaje("Zorbax".repeat(20_000))
        assertEquals("‹99999›", gigante, promesa(p) + " · un tramo de 120000 caracteres")
        assertEquals(gigante, puerta.mensaje(gigante), promesa(p) + " · idempotente con un tramo gigante")
    }
}
