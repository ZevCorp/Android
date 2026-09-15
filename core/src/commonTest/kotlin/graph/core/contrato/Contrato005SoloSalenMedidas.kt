package graph.core.contrato

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
import kotlin.test.assertTrue

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
            503 to "Un id sale solo si es opaco: un UUID, un sello # de 8 hex, una celda:x,y, una ruta de la API hecha de segmentos conocidos e ids, o un prefijo cerrado (call_, ses-, wf-…) con dígitos. Una palabra tras el prefijo, un paquete de app o un número largo no son ids.",
            504 to "Lo que se sube de un pedido y de un usuario lo arma la puerta: el pedido, el resumen, el nombre y el modelo del teléfono viajan como su largo; el estado y la vía, de una lista cerrada; los ids, solo si son UUID; y ninguna clave fuera de las de la RPC.",
            505 to "Cada fila de log que sale la arma la puerta, con solo device_id, prompt_id, tag y message. Detrás de TelemetriaDeVoz la medida de la voz sobrevive y ni una palabra de lo dicho sale; pasar dos veces por la puerta da lo mismo que una, y un mensaje enorme sale acotado.",
            506 to "Las líneas de precisión que ya son medidas pasan sin tocar precisión: la de peticion entera con su destino sellado, en celda o por su largo; las del motor con sus largos, turnos, acciones y tiempos; y del freno, el tope y el workflow, su medida, su sello y el tipo de la excepción.",
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

    private val SECRETOS = arrayOf("Zorbax", "Qwyk", "7731", "3001234567", "1037246")

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
            "assist" to "▶ acción: \"llama a Zorbax al 3001234567\"",
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
        assertEquals(tramo("llama al 3001234567"), puerta.mensaje("llama al 3001234567"), promesa(p) + " · un teléfono")
        assertEquals(tramo("CC 1037246 de Zorbax"), puerta.mensaje("CC 1037246 de Zorbax"), promesa(p) + " · una cédula")
        assertEquals(tramo("compra 3 panes"), puerta.mensaje("compra 3 panes"), promesa(p) + " · un número sin medida")
        assertEquals("‹4›", puerta.mensaje("7731"), promesa(p) + " · un número suelto")
        // Un prefijo de medida no sostiene un número largo: «intento 3001234567» no es un intento.
        sinFuga(p, puerta.mensaje("intento 3001234567"), "3001234567")
        sinFuga(p, puerta.mensaje("HTTP: 1037246"), "1037246")
        sinFuga(p, puerta.mensaje("turno 7731 de 3001234567 caracteres"), "3001234567")

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
            "ses-1", "wf-9f8e7d", "call_ydaLTWADFkH6AtEXUxsfdltF", "item_1", "files/abc123",
            "/api/v1/workflows/wf-1", "/api/v1/learning/sessions/ses-1/steps", "/api/v1/workflows/17/plan", "/api/v1/agent/turn",
        )) {
            assertEquals(id, puerta.mensaje(id), promesa(p) + " · «$id» es opaco")
        }
        for (noId in listOf("wf-zorbax", "#Zorbax12", "#a1b2c3", "com.whatsapp", "3001234567", "ses-Qwyk", "files/Zorbax", "/api/v1/workflows/registrar-a-zorbax")) {
            assertEquals(tramo(noId), puerta.mensaje(noId), promesa(p) + " · «$noId» no es un id")
        }
        assertEquals("${tramo("la sesión")} ses-1 ${tramo("para")} wf-2", puerta.mensaje("la sesión ses-1 para wf-2"), promesa(p) + " · los ids entre texto quedan")
    }

    @Test
    fun promesa504() {
        val p = 504
        val id = "3f2c9a1e-7b4d-4e8a-9c21-0d5e6f7a8b9c"
        val dispositivo = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        val pedido = "escríbele a Zorbax al 3001234567 👋"

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
        val etiquetaSellada = puerta.mensaje("✋ tercera entrada a «#9e8d7c6b» · 3 turnos · 4 acciones · 9s · no sigo")
        assertTrue(etiquetaSellada.endsWith("«#9e8d7c6b» · 3 turnos · 4 acciones · 9s · no sigo"), promesa(p) + " · motor: $etiquetaSellada")
    }
}
