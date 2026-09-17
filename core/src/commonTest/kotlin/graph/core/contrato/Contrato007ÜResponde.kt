package graph.core.contrato

import graph.core.voz.PalabraDeActivacion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CONTRATO 007 — Ü RESPONDE A SU NOMBRE (docs/specs/007-ü-responde-a-su-nombre.md).
 *
 * `PalabraDeActivacion.activa` es una función PURA: sin Android, sin coroutines, sin fuente ni mock.
 * Se juzga con casos reales de las dos listas cerradas de la spec — las frases que SÍ llaman a Ü y
 * las que NO, elegidas para que una regla demasiado floja (substring en vez de palabra completa) o
 * demasiado estricta (sin normalizar tildes/mayúsculas/puntuación) se vea en el acto.
 */
class Contrato007ÜResponde {

    companion object {
        val PROMESAS = mapOf(
            701 to "PalabraDeActivacion.activa(texto) reconoce que la persona llamó a Ü por su nombre con «hola ü», " +
                "«hola u», «ey ü», «ey u», «oye ü», «oye u», «hey ü»/«hey u» (variante razonable, se pronuncia igual " +
                "que «ey»), y con «ü»/«u» sola cuando es TODA la frase — sin importar mayúsculas, tildes/diéresis ni " +
                "signos de puntuación alrededor.",
            702 to "PalabraDeActivacion.activa(texto) NO activa con «una silla», «hola» sola (sin nombrar a Ü), " +
                "«hola tú», «tuve», ni con ninguna palabra que solo contenga la letra u en medio de otra palabra " +
                "(p. ej. «cuchara», «auto»): el nombre tiene que ser una palabra completa de la frase, no una " +
                "coincidencia parcial.",
            // Juzgadas por fuente en Contrato007WakeWordEnApp.kt (app no corre en jvmTest, igual que 246/256/259).
            703 to "La escucha de la palabra vive en `app/`, en su propio archivo, y usa `SystemTranscriber` " +
                "pidiendo reconocimiento en el dispositivo (`EXTRA_PREFER_OFFLINE`); su bucle nunca nombra " +
                "`MeetingBrain` ni `taskQueue`, y nunca pasa la frase escuchada completa a `LogBus.log` (ni loguea " +
                "nada que no sea un aviso fijo, sin la variable del texto).",
            704 to "El interruptor «Activar «Hola Ü»» vive en el panel principal de MainActivity (no en el panel " +
                "de desarrollador), se guarda en las preferencias y arranca APAGADO hasta que el usuario lo " +
                "prende una vez.",
            705 to "Al detectar la palabra: suena un aviso ya existente, la burbuja reacciona con una animación " +
                "ya existente, se narra y se habla un saludo elegido al azar entre variantes, el badge de " +
                "`VoiceDock` avisa «te escucho» reusando su mecanismo, y se entra al Modo Reunión llamando a " +
                "`dock()` de forma programática (sin coordenadas de arrastre) — sin romper cómo se exponen " +
                "`docked`/`listening`.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    @Test
    fun promesa701() {
        val p = 701
        // Las frases de la spec, tal cual las diría alguien: con «ü» real, con «u» llana, con mayúsculas y puntuación.
        val activan = listOf(
            "hola ü", "hola u", "ey ü", "ey u", "oye ü", "oye u", "hey ü", "hey u",
            "ü", "u",
            "Hola Ü", "HOLA U", "¡Hola ü!", "¿ey u?", "  hola   u  ", "Oye, Ü",
        )
        for (frase in activan) {
            assertTrue(PalabraDeActivacion.activa(frase), promesa(p) + " · «$frase» tenía que activar y no activó")
        }
    }

    @Test
    fun promesa702() {
        val p = 702
        val noActivan = listOf(
            "una silla", "hola", "hola tú", "tuve", "cuchara", "auto", "su", "tu",
            "hola ü ayúdame con algo", "ey u dime", "no hola u",
        )
        for (frase in noActivan) {
            assertFalse(PalabraDeActivacion.activa(frase), promesa(p) + " · «$frase» NO tenía que activar y activó")
        }
    }
}
