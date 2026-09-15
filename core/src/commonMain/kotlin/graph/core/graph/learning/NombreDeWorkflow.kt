package graph.core.graph.learning

/**
 * CÓMO SE LLAMA UN WORKFLOW cuando Graph no lo bautizó. Espejo de `NombreDeWorkflow.cs` (promesa 108 de
 * Windows; aquí, spec 004, promesa 404).
 *
 * Medido contra el Graph vivo el 2026-09-02: de cuatro workflows, tres se llamaban «Workflow sin
 * descripción» y el cuarto «User workflow summary:», la primera línea del resumen del LLM. Graph bautiza
 * con el resumen al cerrar, y si el cierre falla (504) se queda el relleno. Lo que sí se sabe de cada
 * workflow sin pedírselo a nadie: la app, la ventana, cuándo y cuántos pasos. Con eso se compone un
 * nombre que se reconoce: «com.miui.calculator · Calculadora · 2 sep 13:42 · 6 pasos».
 */
object NombreDeWorkflow {
    private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /** Cuánto de la ventana cabe en el nombre antes de estorbar en la lista. */
    private const val MAX_VENTANA = 28

    private const val MS_DIA = 86_400_000L

    /** El nombre compuesto de lo que se sabe. [cuandoMs] en ms Unix; [desfaseMs] el de la zona local. */
    fun derivar(app: String, ventana: String, cuandoMs: Long?, pasos: Int, desfaseMs: (Long) -> Long): String {
        val a = app.trim()
        val v = ventana.trim()
        val partes = mutableListOf<String>()
        if (a.isNotEmpty()) partes += a
        // La ventana solo si añade algo: «Claude · Claude» no dice más que «Claude».
        if (v.isNotEmpty() && !v.equals(a, ignoreCase = true))
            partes += if (v.length > MAX_VENTANA) v.take(MAX_VENTANA - 1).trimEnd() + "…" else v
        if (cuandoMs != null) partes += fechaCorta(cuandoMs, desfaseMs)
        partes += if (pasos == 1) "1 paso" else "$pasos pasos"
        return partes.joinToString(" · ")
    }

    /**
     * Lo que NO es un nombre: vacío, el relleno del grabador («Workflow sin descripción»), el de Graph
     * («No description»), o un encabezado del LLM (termina en dos puntos, o empieza por «User workflow summary»).
     */
    fun esRelleno(descripcion: String?): Boolean {
        val d = descripcion?.trim().orEmpty()
        return d.isEmpty() ||
            d.equals("Workflow sin descripción", ignoreCase = true) ||
            d.equals("Workflow sin descripcion", ignoreCase = true) ||
            d.equals("No description", ignoreCase = true) ||
            d.endsWith(':') ||
            d.startsWith("User workflow summary", ignoreCase = true)
    }

    /** `uia://claude.exe/…` → «Claude» · `sapgui://QAS/NWP1` → «SAP NWP1» · `android://com.x/…` → «com.x». */
    fun appDe(origin: String?): String {
        var s = origin?.trim().orEmpty()
        if (s.isEmpty()) return ""
        var esquema = ""
        val i = s.indexOf("://")
        if (i >= 0) {
            esquema = s.substring(0, i).lowercase()
            s = s.substring(i + 3)
        }
        val segmentos = s.split('/').filter { it.isNotEmpty() }
        if (segmentos.isEmpty()) return ""
        return when (esquema) {
            "sapgui" -> "SAP " + segmentos.last()
            "uia" -> segmentos[0].let { if (it.endsWith(".exe", ignoreCase = true)) it.dropLast(4) else it }.replaceFirstChar { it.uppercaseChar() }
            else -> segmentos[0]
        }
    }

    /** «2 sep 13:42», en hora local y sin el año: un workflow de hace un año ya no es «el de ayer». */
    fun fechaCorta(ms: Long, desfaseMs: (Long) -> Long): String {
        val local = ms + desfaseMs(ms)
        val (mes, dia) = mesYDia(local.floorDiv(MS_DIA))
        val delDia = local.mod(MS_DIA)
        val hora = (delDia / 3_600_000).toString().padStart(2, '0')
        val minuto = (delDia / 60_000 % 60).toString().padStart(2, '0')
        return "$dia ${MESES[mes - 1]} $hora:$minuto"
    }

    /** Días desde 1970-01-01 → (mes, día). `civil_from_days` de Howard Hinnant: sin `kotlinx-datetime`. */
    private fun mesYDia(dias: Long): Pair<Int, Int> {
        val z = dias + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val dia = doy - (153 * mp + 2) / 5 + 1
        val mes = if (mp < 10) mp + 3 else mp - 9
        return mes.toInt() to dia.toInt()
    }
}
