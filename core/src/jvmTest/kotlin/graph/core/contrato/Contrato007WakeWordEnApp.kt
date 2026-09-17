package graph.core.contrato

import graph.core.contrato.Contrato007ÜResponde.Companion.promesa
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * LAS PROMESAS 703-705 SE JUZGAN LEYENDO LAS FUENTES DE `app` (docs/specs/007), mismo criterio que
 * 246/256/259 (`Contrato002VozEnVivoDev.kt`) y 602/611/613/615/619/621 (`Contrato006LoQueVe.kt`):
 * `app` es Android (accesibilidad, overlay, `SpeechRecognizer`) y no corre en `jvmTest`, pero lo que
 * promete está escrito — dónde vive el bucle de la palabra, con qué transcriptor, y por dónde nunca
 * puede caer una frase completa en el log.
 */
class Contrato007WakeWordEnApp {

    @Test
    fun promesa703() {
        val p = 703
        val dock = fuenteDeLaApp("WakeWordDock.kt")

        // Usa el reconocedor del sistema pidiendo reconocimiento EN EL DISPOSITIVO.
        assertTrue(
            Regex("""SystemTranscriber\([^)]*preferOffline\s*=\s*true""").containsMatchIn(dock),
            promesa(p) + " · WakeWordDock no pide SystemTranscriber(preferOffline = true)",
        )

        // Nunca llega al Modo Reunión de verdad: no sabe que MeetingBrain ni taskQueue existen.
        for (prohibido in listOf("MeetingBrain", "taskQueue", "taskWorker")) {
            assertFalse(
                Regex("(?<![\\p{L}\\p{N}_])" + prohibido + "(?![\\p{L}\\p{N}_])").containsMatchIn(dock),
                promesa(p) + " · WakeWordDock nombra «$prohibido»",
            )
        }

        // Nunca loguea la frase completa: ninguna llamada a LogBus.log lleva la variable de lo escuchado.
        val llamadasALog = Regex("""LogBus\.log\([^)]*\)""").findAll(dock).map { it.value }.toList()
        for (llamada in llamadasALog) {
            assertFalse(
                Regex("""\bheard\b""").containsMatchIn(llamada),
                promesa(p) + " · una llamada a LogBus.log lleva la frase escuchada: $llamada",
            )
        }

        // Y SystemTranscriber de verdad soporta el parámetro (no es un nombre que no existe).
        val transcribers = fuenteDeLaApp("Transcribers.kt")
        assertTrue(
            Regex("""class\s+SystemTranscriber\s*\([^)]*preferOffline\s*:\s*Boolean\s*=\s*false""").containsMatchIn(transcribers),
            promesa(p) + " · SystemTranscriber no declara preferOffline con default false (no cambia a los llamadores existentes)",
        )
        assertTrue(
            Regex("""EXTRA_PREFER_OFFLINE""").containsMatchIn(transcribers),
            promesa(p) + " · SystemTranscriber no usa EXTRA_PREFER_OFFLINE",
        )

        // Sin reconocedor de voz disponible en el dispositivo, el bucle no reintenta cada rato: espera un backoff
        // bien más largo que el reintento normal (150 ms / 400 ms), no lo gasta en batería.
        val bloque = Regex("""isRecognitionAvailable\([^)]*\)\)\s*\{\s*delay\((\w+)\)""").find(dock)
            ?: fail(promesa(p) + " · no encuentro un delay dentro del if de isRecognitionAvailable")
        val nombreConstante = bloque.groupValues[1]
        val valorMs = Regex(Regex.escape(nombreConstante) + """\s*=\s*([\d_]+)L""").find(dock)
            ?.groupValues?.get(1)?.replace("_", "")?.toLong()
            ?: fail(promesa(p) + " · no encuentro el valor de $nombreConstante")
        assertTrue(
            valorMs >= 5_000,
            promesa(p) + " · sin reconocedor disponible, el backoff ($nombreConstante = $valorMs ms) sigue siendo un reintento corto",
        )
    }

    @Test
    fun promesa704() {
        val p = 704
        val pantalla = fuenteDeLaApp("MainActivity.kt")

        // El interruptor vive en la MISMA tarjeta que «Hacer de Ü tu asistente» (el panel principal
        // de ajustes), nunca dentro de la restricción angosta `if (mode == MODE_DEV) { … }` que usa
        // la voz en vivo de prueba (esa restricción es solo de VozEnVivoDev, no aplica acá).
        val desde = pantalla.indexOf("val setup = card()")
        if (desde < 0) fail(promesa(p) + " · no encuentro la tarjeta de ajustes (`val setup = card()`)")
        val hasta = pantalla.indexOf("root.addView(setup)", desde)
        if (hasta < 0) fail(promesa(p) + " · la tarjeta de ajustes no cierra con `root.addView(setup)`")
        val tarjeta = pantalla.substring(desde, hasta)

        assertTrue("Hacer de Ü tu asistente" in tarjeta, promesa(p) + " · «Hacer de Ü tu asistente» ya no vive en esta tarjeta")
        assertTrue("Hola Ü" in tarjeta, promesa(p) + " · no encuentro el interruptor «Hola Ü» cerca de «Hacer de Ü tu asistente»")
        assertFalse("MODE_DEV" in tarjeta, promesa(p) + " · la tarjeta de ajustes quedó condicionada a MODE_DEV")

        // Se guarda en preferencias y arranca APAGADO por defecto.
        assertTrue(
            Regex("""getBoolean\(\s*"wakeWordEnabled"\s*,\s*false\s*\)""").containsMatchIn(tarjeta),
            promesa(p) + " · el interruptor no lee wakeWordEnabled con default false",
        )
        assertTrue(
            Regex("""putBoolean\(\s*"wakeWordEnabled"\s*,""").containsMatchIn(tarjeta),
            promesa(p) + " · el interruptor no persiste wakeWordEnabled",
        )

        // Y la burbuja arranca la escucha leyendo la MISMA preferencia (mismo default apagado).
        val bubble = fuenteDeLaApp("FloatingBubble.kt")
        assertTrue(
            Regex("""getBoolean\(\s*"wakeWordEnabled"\s*,\s*false\s*\)""").containsMatchIn(bubble),
            promesa(p) + " · FloatingBubble no arranca la escucha de la palabra leyendo la misma preferencia",
        )
    }

    @Test
    fun promesa705() {
        val p = 705
        val bubble = fuenteDeLaApp("FloatingBubble.kt")

        val cuerpo = cuerpo(bubble, Regex("""private\s+fun\s+onWakeWordDetected\s*\(\s*\)"""))
        assertTrue(
            "playListenChime()" in cuerpo || "playTick()" in cuerpo,
            promesa(p) + " · al detectar la palabra no suena ningún aviso ya existente: $cuerpo",
        )
        assertTrue("pulse()" in cuerpo, promesa(p) + " · la burbuja no reacciona con una animación ya existente: $cuerpo")
        assertTrue(Regex("""\bnarrate\s*\(\s*greeting\s*\)""").containsMatchIn(cuerpo), promesa(p) + " · no narra el saludo: $cuerpo")
        assertTrue(Regex("""\bspeak\s*\(\s*greeting\s*\)""").containsMatchIn(cuerpo), promesa(p) + " · no habla el saludo: $cuerpo")
        assertTrue(
            "voiceDock.showListeningBadge()" in cuerpo,
            promesa(p) + " · no reusa el badge de VoiceDock («👂 te escucho…»): $cuerpo",
        )
        assertTrue("voiceDock.dockNow()" in cuerpo, promesa(p) + " · no entra al Modo Reunión de forma programática: $cuerpo")

        // El saludo sale de una lista de al menos 3 variantes elegidas al azar, no de un texto fijo.
        val saludos = Regex("""SALUDOS\s*=\s*listOf\(([\s\S]*?)\)""").find(bubble)?.groupValues?.get(1)
            ?: fail(promesa(p) + " · no encuentro la lista SALUDOS")
        val variantes = Regex("\"([^\"]*)\"").findAll(saludos).count()
        assertTrue(variantes >= 3, promesa(p) + " · SALUDOS tiene menos de 3 variantes ($variantes)")
        assertTrue(Regex("""SALUDOS\s*\.\s*random\s*\(\s*\)""").containsMatchIn(cuerpo), promesa(p) + " · el saludo no se elige al azar: $cuerpo")

        // No rompe cómo el modo reunión expone docked/listening.
        val dock = fuenteDeLaApp("VoiceDock.kt")
        assertTrue(Regex("""@Volatile\s+var\s+docked\s*=\s*false""").containsMatchIn(dock), promesa(p) + " · VoiceDock ya no expone `docked` como antes")
        assertTrue(Regex("""@Volatile\s+var\s+listening\s*=\s*false""").containsMatchIn(dock), promesa(p) + " · VoiceDock ya no expone `listening` como antes")
        assertTrue(Regex("""\bfun\s+dockNow\s*\(\s*\)""").containsMatchIn(dock), promesa(p) + " · VoiceDock no expone dockNow() para entrar sin coordenadas de arrastre")
    }

    /** El cuerpo de la función que abre [firma], hasta su llave de cierre: la primera `}` con su misma sangría. */
    private fun cuerpo(codigo: String, firma: Regex): String {
        val lineas = codigo.lines()
        val i = lineas.indexOfFirst { firma.containsMatchIn(it) }
        if (i < 0) fail("no encuentro «${firma.pattern}»")
        val sangria = lineas[i].takeWhile { it == ' ' }
        val fin = (i + 1 until lineas.size).firstOrNull { lineas[it] == "$sangria}" } ?: fail("«${firma.pattern}» no cierra")
        return lineas.subList(i, fin + 1).joinToString("\n")
    }

    private fun fuenteDeLaApp(nombre: String): String = fuente("app/src/main/kotlin", nombre)

    /**
     * `:core:jvmTest` corre con el directorio de trabajo en `core/`, pero no se supone: se sube desde donde esté hasta
     * encontrar [raizRelativa]. Si el archivo no aparece, la promesa falla: una lectura de cero archivos no da verde.
     */
    private fun fuente(raizRelativa: String, nombre: String): String {
        val desde = File("").absoluteFile
        val raiz = generateSequence(desde) { it.parentFile }
            .map { File(it, raizRelativa) }
            .firstOrNull { it.isDirectory }
            ?: fail("no encuentro $raizRelativa subiendo desde $desde")
        val archivo = raiz.walkTopDown().firstOrNull { it.isFile && it.name == nombre }
            ?: fail("no encuentro $nombre en $raiz")
        return archivo.readText()
    }
}
