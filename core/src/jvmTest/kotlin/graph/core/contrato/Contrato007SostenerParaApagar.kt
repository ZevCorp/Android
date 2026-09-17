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
            710 to "Sostener la burbuja quieta, sin moverla y sin soltarla, arma un temporizador de 5 segundos hacia el apagado; con el dedo quieto ese tiempo entero, se dispara.",
            711 to "Soltar el dedo (ACTION_UP o ACTION_CANCEL) antes de que se cumplan los 5 segundos cancela el apagado sin efecto: el toque simple y el arrastre normal siguen funcionando igual que siempre.",
            712 to "Un movimiento FRANCO de la burbuja cancela el apagado pendiente, con un umbral propio y bastante más tolerante que el de tap/arrastre (para que el temblor normal de una mano sostenida 5 s no lo corte solo), sin tocar el resto del arrastre ni el aviso al modo reunión.",
            713 to "Cumplidos los 5 segundos, la burbuja anima una explosión (escala hacia arriba y opacidad hacia 0 con ValueAnimator, sin traer ninguna librería nueva) y solo al terminar la animación dispara el apagado.",
            714 to "El apagado corta el modo reunión si estaba activo (persistiendo sus notas) y detiene cualquier voz sonando, la del sistema y la de OpenAI.",
            715 to "El apagado quita la vista de la burbuja de la pantalla y deja registrado que Ü se apagó por este gesto, con la misma medida que cualquier otra línea del log (nunca texto libre fuera de la puerta de telemetría).",
            716 to "Despertar a Ü vuelve a mostrar la misma burbuja sin recrear el motor de voz ni los sonidos, y no hace nada si ya estaba despierta.",
            717 to "Abrir la app de nuevo (dockToApp/setHiddenForApp) o el asistente del botón de encendido despiertan a Ü si estaba dormido por el gesto.",
            718 to "Si el apagado ya disparó DENTRO del mismo toque (la burbuja explotó sin que hubiera arrastre), soltar el dedo justo después no cuenta como un click normal: no cae en performClick() ni reabre el panel. El próximo toque, con Ü ya despierta, se comporta como siempre.",
            719 to "Mientras Ü está dormido no se puede seguir escuchando la palabra de activación: canListenForWakeWord() excluye el estado dormido y sleep() detiene ese bucle; si el interruptor seguía prendido, despertar lo retoma.",
        )

        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    @Test
    fun promesa710() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 710

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
    fun promesa711() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 711
        val up = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_UP,\s*MotionEvent\.ACTION_CANCEL\s*->\s*\{"""))
        // Sin comentarios: un comentario que solo MENCIONE la llamada no puede hacer pasar un juez
        // que en verdad la borró (control 007 · límite del juez por texto).
        val upSinComentarios = sinComentarios(up)
        assertTrue(
            Regex("""shutdownJob\s*\?\.\s*cancel\s*\(\s*\)""").containsMatchIn(upSinComentarios),
            promesa(p) + " · soltar el dedo antes de los 5 s no cancela el apagado: $up",
        )
        // El gesto de siempre sigue intacto: un toque simple y el vuelo a la esquina no se tocaron.
        assertTrue("v.performClick()" in upSinComentarios, promesa(p) + " · el toque normal (sin arrastre) dejó de funcionar")
        assertTrue("flingToEdge" in upSinComentarios, promesa(p) + " · soltar tras arrastrar dejó de lanzarla a la esquina")
    }

    @Test
    fun promesa712() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 712
        val move = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_MOVE\s*->\s*\{"""))

        // El umbral chico (120 ≈ 11 px) sigue sirviendo SOLO para tap-vs-arrastre: ya no cancela el
        // apagado ahí mismo (si lo hiciera, el temblor normal de una mano en 5 s lo cortaría solo).
        val dragIf = cuerpo(move, Regex("""if\s*\(\s*moved\s*\|\|.*>\s*120\s*\)\s*\{"""))
        assertTrue("voiceDock.track(" in dragIf, promesa(p) + " · el arrastre dejó de avisarle al modo reunión")
        assertFalse(
            Regex("""shutdownJob\s*\?\.\s*cancel\s*\(\s*\)""").containsMatchIn(dragIf),
            promesa(p) + " · el apagado se sigue cancelando con el mismo umbral ajustado del tap/arrastre (120): un temblor normal de mano lo superaría en 5 s y cortaría el apagado sin que la persona sienta que hizo algo mal: $dragIf",
        )

        // El apagado se cancela con un umbral PROPIO, bastante más tolerante (5×-8× el de arriba,
        // en distancia al cuadrado: 120×25 a 120×64).
        val declaracion = Regex("""SHUTDOWN_CANCEL_DISTANCE_SQ\s*=\s*(\d+)\s*\*\s*(\d+)""").find(bubble)
        val valor = declaracion?.groupValues?.drop(1)?.filter { it.isNotBlank() }?.map { it.toInt() }?.fold(1) { a, b -> a * b }
            ?: Regex("""SHUTDOWN_CANCEL_DISTANCE_SQ\s*=\s*(\d+)\D""").find(bubble)?.groupValues?.get(1)?.toInt()
            ?: fail(promesa(p) + " · no declara un umbral propio para cancelar el apagado")
        assertTrue(
            valor in 3000..7700,
            promesa(p) + " · el umbral de cancelación del apagado ($valor) no está entre 5 y 8 veces el de tap/arrastre (120)",
        )
        assertTrue(
            Regex("""shutdownJob\s*!=\s*null\s*&&\s*\w+\s*>\s*SHUTDOWN_CANCEL_DISTANCE_SQ\s*\)\s*\{\s*shutdownJob\s*\?\.\s*cancel\s*\(\s*\)""").containsMatchIn(move),
            promesa(p) + " · un movimiento franco (umbral propio) no cancela el apagado pendiente: $move",
        )
    }

    @Test
    fun promesa713() {
        val explode = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""private fun explodeAndSleep\s*\("""))
        val p = 713
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
    fun promesa714() {
        // Sin comentarios: un comentario que solo MENCIONE voiceDock.destroy()/undock() no puede
        // hacer pasar un juez que en verdad la borró (control 007 · límite del juez por texto).
        val sleep = sinComentarios(cuerpo(fuenteDeLaApp(BUBBLE), Regex("""private fun sleep\s*\(""")))
        val p = 714
        assertTrue(
            Regex("""voiceDock\s*\.\s*(destroy|undock)\s*\(\s*\)""").containsMatchIn(sleep),
            promesa(p) + " · el apagado no corta el modo reunión si estaba activo: $sleep",
        )
        assertTrue(Regex("""tts\s*\?\.\s*stop\s*\(\s*\)""").containsMatchIn(sleep), promesa(p) + " · no detiene el TTS del sistema")
        assertTrue(Regex("""openAiTts\s*\.\s*stop\s*\(\s*\)""").containsMatchIn(sleep), promesa(p) + " · no detiene la voz de OpenAI")
    }

    @Test
    fun promesa718() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 718

        assertTrue(
            Regex("""private\s+var\s+shutdownFired\s*=\s*false""").containsMatchIn(bubble),
            promesa(p) + " · no declara la bandera que marca que el apagado ya disparó en este toque",
        )

        val explode = cuerpo(bubble, Regex("""private fun explodeAndSleep\s*\("""))
        assertTrue(
            Regex("""shutdownFired\s*=\s*true""").containsMatchIn(explode),
            promesa(p) + " · explodeAndSleep() no marca que el apagado arrancó en este toque: $explode",
        )

        val down = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_DOWN\s*->\s*\{"""))
        assertTrue(
            Regex("""shutdownFired\s*=\s*false""").containsMatchIn(down),
            promesa(p) + " · ACTION_DOWN no resetea la bandera para el siguiente toque: $down",
        )

        val up = cuerpo(attachDrag(bubble), Regex("""MotionEvent\.ACTION_UP,\s*MotionEvent\.ACTION_CANCEL\s*->\s*\{"""))
        assertTrue(
            Regex("""if\s*\(\s*shutdownFired\s*\)\s*\{[\s\S]*?}\s*else\s+if\s*\(\s*!\s*moved\s*\)\s*v\.performClick\(\)""").containsMatchIn(up),
            promesa(p) + " · soltar el dedo justo después de que la explosión disparó puede seguir cayendo en v.performClick(): $up",
        )
    }

    @Test
    fun promesa719() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 719

        // canListenForWakeWord() es un cuerpo de expresión (`= …`), sin llaves: se recorta hasta la
        // línea en blanco que la separa de la siguiente declaración, no con cuerpo().
        val desde = bubble.indexOf("private fun canListenForWakeWord")
        if (desde < 0) fail(promesa(p) + " · no encuentro canListenForWakeWord()")
        val hasta = bubble.indexOf("\n\n", desde).let { if (it < 0) bubble.length else it }
        val canListen = bubble.substring(desde, hasta)
        assertTrue(
            Regex("""!\s*asleep""").containsMatchIn(canListen),
            promesa(p) + " · canListenForWakeWord() no excluye el estado dormido: $canListen",
        )

        // Sin comentarios: un comentario que solo MENCIONE wakeWordDock.stop() no puede hacer pasar
        // un juez que en verdad la borró (mismo límite que 711/712/714).
        val sleep = sinComentarios(cuerpo(bubble, Regex("""private fun sleep\s*\(""")))
        assertTrue(
            Regex("""wakeWordDock\s*\.\s*stop\s*\(\s*\)""").containsMatchIn(sleep),
            promesa(p) + " · sleep() no detiene la escucha de la palabra de activación: $sleep",
        )

        val wake = sinComentarios(cuerpo(bubble, Regex("""fun wakeIfAsleep\s*\(""")))
        assertTrue(
            Regex("""getBoolean\(\s*"wakeWordEnabled"\s*,\s*false\s*\)\s*\)\s*wakeWordDock\s*\.\s*start\s*\(""").containsMatchIn(wake),
            promesa(p) + " · wakeIfAsleep() no retoma la escucha de la palabra si el interruptor seguía prendido: $wake",
        )
    }

    @Test
    fun promesa715() {
        val sleep = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""private fun sleep\s*\("""))
        val p = 715
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
    fun promesa716() {
        val wake = cuerpo(fuenteDeLaApp(BUBBLE), Regex("""fun wakeIfAsleep\s*\("""))
        val p = 716
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
    fun promesa717() {
        val bubble = fuenteDeLaApp(BUBBLE)
        val p = 717
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

    /** Descarta líneas comentadas (`//`) antes de buscar un patrón: un comentario que solo MENCIONA
     *  una llamada no debe hacer pasar un juez que en verdad la borró (control 007). */
    private fun sinComentarios(codigo: String): String =
        codigo.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

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
