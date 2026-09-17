package graph.core.contrato

import graph.core.telemetria.PuertaDeTelemetria
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CONTRATO 007 — SOSTENER LA BURBUJA 5 S LA APAGA (docs/specs/007-sostener-para-apagar.md).
 *
 * Todo esto es Android puro (gestos con la mano, `ValueAnimator`, `WindowManager`) y no corre en `jvmTest`, pero lo
 * que promete está escrito: dónde se arma el temporizador, cuándo se cancela, y qué corta la explosión antes de
 * quitar la burbuja de la pantalla. Se juzga igual que la 259 y las 602/611/613/615/619/621 de la 006: leyendo las
 * fuentes de `app`, porque solo `jvmTest` lee disco.
 *
 * Android no deja que la app apague su propio permiso de accesibilidad: por eso "apagar" acá es dormir la burbuja
 * y lo que escucha, no el servicio.
 */
class Contrato007SostenerParaApagar {

    private companion object {
        const val BUBBLE = "FloatingBubble.kt"
        const val ASSIST = "AssistActivity.kt"

        val PROMESAS = mapOf(
            701 to "Sostener la burbuja quieta, sin moverla y sin soltarla, arma un temporizador de 5 segundos hacia el apagado; con el dedo quieto ese tiempo entero, se dispara.",
            702 to "Soltar el dedo (ACTION_UP o ACTION_CANCEL) antes de que se cumplan los 5 segundos cancela el apagado sin efecto: el toque simple y el arrastre normal siguen funcionando igual que siempre.",
            703 to "Un movimiento significativo de la burbuja (el mismo umbral que ya usa el arrastre) cancela el apagado pendiente, sin tocar el resto del arrastre ni el aviso al modo reunión.",
            704 to "Cumplidos los 5 segundos, la burbuja anima una explosión (escala hacia arriba y opacidad hacia 0 con ValueAnimator, sin traer ninguna librería nueva) y solo al terminar la animación dispara el apagado.",
            705 to "El apagado corta el modo reunión si estaba activo (persistiendo sus notas) y detiene cualquier voz sonando, la del sistema y la de OpenAI.",
            706 to "El apagado quita la vista de la burbuja de la pantalla y deja registrado que Ü se apagó por este gesto, con la misma medida que cualquier otra línea del log (nunca texto libre fuera de la puerta de telemetría).",
            707 to "Despertar a Ü vuelve a mostrar la misma burbuja sin recrear el motor de voz ni los sonidos, y no hace nada si ya estaba despierta.",
            708 to "Abrir la app de nuevo (dockToApp/setHiddenForApp) o el asistente del botón de encendido despiertan a Ü si estaba dormido por el gesto.",
        )

        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    @Test
    fun promesa701() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 701

        assertTrue(
            Regex("""SHUTDOWN_HOLD_MS\s*=\s*5_?000L""").containsMatchIn(bubble),
            promesa(p) + " · no declara el apagado a los 5000 ms",
        )
        val down = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_DOWN\s*->\s*\{"""))
        assertTrue(
            Regex("""scope\s*\.\s*launch\s*\{\s*delay\s*\(\s*SHUTDOWN_HOLD_MS\s*\)\s*;?\s*explodeAndSleep\s*\(\s*\)""").containsMatchIn(down),
            promesa(p) + " · ACTION_DOWN no arma el temporizador de 5 s hacia explodeAndSleep(): $down",
        )
    }

    @Test
    fun promesa702() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 702
        val up = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_UP,\s*MotionEvent\.ACTION_CANCEL\s*->\s*\{"""))
        assertTrue(
            Regex("""shutdownJob\s*\?\.\s*cancel\s*\(\s*\)""").containsMatchIn(up),
            promesa(p) + " · soltar el dedo antes de los 5 s no cancela el apagado: $up",
        )
        // El gesto de siempre sigue intacto: un toque simple y el vuelo a la esquina no se tocaron.
        assertTrue("v.performClick()" in up, promesa(p) + " · el toque normal (sin arrastre) dejó de funcionar")
        assertTrue("flingToEdge" in up, promesa(p) + " · soltar tras arrastrar dejó de lanzarla a la esquina")
    }

    @Test
    fun promesa703() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 703
        val move = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_MOVE\s*->\s*\{"""))
        val dentro = cuerpo(move, Regex("""if\s*\(\s*moved\s*\|\|\s*dx\s*\*\s*dx\s*\+\s*dy\s*\*\s*dy\s*>\s*120\s*\)\s*\{"""))
        assertTrue(
            Regex("""shutdownJob\s*\?\.\s*cancel\s*\(\s*\)""").containsMatchIn(dentro),
            promesa(p) + " · moverla de verdad no cancela el apagado pendiente: $dentro",
        )
        assertTrue("voiceDock.track(" in dentro, promesa(p) + " · el arrastre dejó de avisarle al modo reunión")
    }

    @Test
    fun promesa704() {
        val explode = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""private fun explodeAndSleep\s*\("""))
        val p = 704
        assertTrue("ValueAnimator" in explode, promesa(p) + " · la explosión no usa ValueAnimator (no se trae una librería nueva)")
        assertTrue(Regex("""bubble\.scaleX\s*=""").containsMatchIn(explode) && Regex("""bubble\.scaleY\s*=""").containsMatchIn(explode),
            promesa(p) + " · la explosión no escala la burbuja: $explode")
        assertTrue(Regex("""bubble\.alpha\s*=""").containsMatchIn(explode), promesa(p) + " · la explosión no baja la opacidad")
        assertTrue(
            Regex("""onAnimationEnd\s*\([^)]*\)\s*=\s*sleep\s*\(\s*\)""").containsMatchIn(explode),
            promesa(p) + " · la explosión no espera a que termine la animación para apagar (sleep() no está en onAnimationEnd): $explode",
        )
    }

    @Test
    fun promesa705() {
        val sleep = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""private fun sleep\s*\("""))
        val p = 705
        assertTrue(
            Regex("""voiceDock\s*\.\s*(destroy|undock)\s*\(\s*\)""").containsMatchIn(sleep),
            promesa(p) + " · el apagado no corta el modo reunión si estaba activo: $sleep",
        )
        assertTrue(Regex("""tts\s*\?\.\s*stop\s*\(\s*\)""").containsMatchIn(sleep), promesa(p) + " · no detiene el TTS del sistema")
        assertTrue(Regex("""openAiTts\s*\.\s*stop\s*\(\s*\)""").containsMatchIn(sleep), promesa(p) + " · no detiene la voz de OpenAI")
    }

    @Test
    fun promesa706() {
        val sleep = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""private fun sleep\s*\("""))
        val p = 706
        assertTrue(
            Regex("""wm\s*\.\s*removeView\s*\(\s*bubble\s*\)""").containsMatchIn(sleep),
            promesa(p) + " · el apagado no quita la burbuja de la pantalla: $sleep",
        )
        assertTrue(Regex("""asleep\s*=\s*true""").containsMatchIn(sleep), promesa(p) + " · el apagado no queda registrado en el estado")
        val tags = Regex("""LogBus\.log\(\s*"([a-z]+)"""").findAll(sleep).map { it.groupValues[1] }.toList()
        assertTrue(tags.isNotEmpty(), promesa(p) + " · el apagado no deja ninguna línea en el log")
        for (tag in tags) {
            assertTrue(tag in PuertaDeTelemetria.TAGS, promesa(p) + " · el tag «$tag» no está en la lista cerrada de la puerta de telemetría")
        }
    }

    @Test
    fun promesa707() {
        val wake = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""fun wakeIfAsleep\s*\("""))
        val p = 707
        assertTrue(
            Regex("""if\s*\(\s*!\s*asleep\s*\)\s*return""").containsMatchIn(wake),
            promesa(p) + " · despertar sin estar dormida hace algo de más: $wake",
        )
        assertTrue(
            Regex("""wm\s*\.\s*addView\s*\(\s*bubble\s*,\s*bubbleParams\s*\)""").containsMatchIn(wake),
            promesa(p) + " · wakeIfAsleep no vuelve a mostrar la burbuja: $wake",
        )
        assertFalse("TextToSpeech(" in wake, promesa(p) + " · wakeIfAsleep recrea el TTS")
        assertFalse("SoundPool.Builder" in wake, promesa(p) + " · wakeIfAsleep recrea el SoundPool")
    }

    @Test
    fun promesa708() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 708
        val dockToApp = cuerpo(bubble, Regex("""fun dockToApp\s*\("""))
        assertTrue("wakeIfAsleep()" in dockToApp, promesa(p) + " · dockToApp no despierta a Ü al volver a la app")
        val setHiddenForApp = cuerpo(bubble, Regex("""fun setHiddenForApp\s*\("""))
        assertTrue("wakeIfAsleep()" in setHiddenForApp, promesa(p) + " · setHiddenForApp no despierta a Ü al volver a la app")

        val assist = fuenteDeLaApp(ASSIST)
        assertTrue(
            Regex("""bubble\s*\?\.\s*wakeIfAsleep\s*\(\s*\)""").containsMatchIn(assist),
            promesa(p) + " · el asistente del botón de encendido no despierta a Ü",
        )
    }

    /* ---------- Ayudas para leer las fuentes de `app` (mismo patrón que Contrato006LoQueVe) ---------- */

    /** El texto de `attachDrag`, la única función que arma y cancela el temporizador de apagado. */
    private fun attachDrag(fuente: String) = cuerpo(fuente, Regex("""private fun attachDrag\s*\("""))

    private fun fuenteDeLaApp(nombre: String): String = fuente("app/src/main/kotlin", nombre)

    /**
     * `:core:jvmTest` corre con el directorio de trabajo en `core/`, pero no se supone: se sube desde donde esté
     * hasta encontrar [raizRelativa]. Si el archivo no aparece, la promesa falla: una lectura de cero archivos no
     * da verde.
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

    /** El cuerpo de la función/bloque que abre con [firma], hasta la línea que cierra con su misma sangría. */
    private fun cuerpo(codigo: String, firma: Regex): String {
        val lineas = codigo.lines()
        val i = lineas.indexOfFirst { firma.containsMatchIn(it) }
        if (i < 0) fail("no encuentro «${firma.pattern}»")
        val sangria = lineas[i].takeWhile { it == ' ' }
        val fin = (i + 1 until lineas.size).firstOrNull { lineas[it] == "$sangria}" } ?: fail("«${firma.pattern}» no cierra")
        return lineas.subList(i, fin + 1).joinToString("\n")
    }
}
