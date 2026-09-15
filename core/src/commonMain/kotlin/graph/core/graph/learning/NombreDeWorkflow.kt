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
    /** El nombre compuesto de lo que se sabe. [cuandoMs] en ms Unix; [desfaseMs] el de la zona local. */
    fun derivar(app: String, ventana: String, cuandoMs: Long?, pasos: Int, desfaseMs: (Long) -> Long): String = TODO()

    /** Lo que NO es un nombre: vacío, «Workflow sin descripción», «No description», o un encabezado del LLM. */
    fun esRelleno(descripcion: String?): Boolean = TODO()

    /** `uia://claude.exe/…` → «Claude» · `sapgui://QAS/NWP1` → «SAP NWP1» · `android://com.x/…` → «com.x». */
    fun appDe(origin: String?): String = TODO()

    /** «2 sep 13:42», en hora local y sin el año. */
    fun fechaCorta(ms: Long, desfaseMs: (Long) -> Long): String = TODO()
}
