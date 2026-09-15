package graph.core.contrato

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/** Una JVM nueva con el classpath de las pruebas: corre [OtroProceso] y devuelve lo que escribió. */
actual fun rechazoEnOtroProceso(selector: String): String {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val salida = File.createTempFile("otro-proceso", ".txt")
    try {
        val proceso = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), OtroProceso::class.java.name, selector)
            .redirectErrorStream(true)
            .redirectOutput(salida)
            .start()
        if (!proceso.waitFor(60, TimeUnit.SECONDS)) {
            proceso.destroyForcibly()
            error("el otro proceso no terminó en 60 s: ${salida.readText()}")
        }
        check(proceso.exitValue() == 0) { "el otro proceso salió con ${proceso.exitValue()}: ${salida.readText()}" }
        return salida.readText().trim()
    } finally {
        salida.delete()
    }
}

/** El otro proceso: la misma puerta de verdad que la 317 usa en este, y su línea de rechazo por la salida. */
object OtroProceso {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println(Contrato003LoQueSaleYUnaCorrida.rechazoDelNodo(args.single()))
    }
}
