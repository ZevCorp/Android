package graph.core.voz

import graph.core.graph.TurnScreenState

/**
 * LO QUE LA VOZ VE DE LA PANTALLA (docs/specs/002, fase 2B2a). SOLO LEE: no toca, no abre, no captura.
 *
 * MIRA EL MISMO ESTADO QUE EL TURNO DE GRAPH ([TurnScreenState]: `screen`, `uiContext`, tamaño), el que arma el
 * servicio de accesibilidad. No hay una segunda lectura de la pantalla: dos lecturas distintas acaban diciendo cosas
 * distintas, y la voz contaría una pantalla que el cerebro no está viendo. La captura NO viaja ([ProtocoloGptLive.mira]
 * es `false`): una PNG no cabe en los 32 768 B de la sesión.
 *
 * NO SE INVENTA NADA. Si el `uiContext` no trae lo que se le pregunta —una pantalla protegida, un formato que cambió—
 * vuelve tal cual, porque la persona de la voz prohíbe justo eso: «Nunca inventes lo que hay en pantalla ni lo que no
 * ves».
 */
object OjosDeLaVoz {

    /** Sin servicio de accesibilidad no hay estado que leer, y eso se dice en vez de callar. */
    const val SIN_PANTALLA = "no puedo ver la pantalla ahora mismo: el servicio de accesibilidad de Ü no está activo"

    /** Lo que se cita de un filtro. Más largo no es una búsqueda: es un texto que alguien quiere que se repita. */
    const val TOPE_DEL_FILTRO = 60

    /** Dónde está el teléfono: la app al frente, el tipo de pantalla, el teclado y el tamaño. */
    fun dondeEstoy(estado: TurnScreenState?): String = TODO("2B2a")

    /**
     * Qué hay en la pantalla. Sin [filtro], las cuentas y las etiquetas visibles; con él, si eso está o no, sin mirar
     * tildes ni mayúsculas.
     */
    fun queVeo(estado: TurnScreenState?, filtro: String = ""): String = TODO("2B2a")

    /** La MEDIDA de lo que se leyó, para el log: cuántas etiquetas y cuántos caracteres. Nunca una etiqueta. */
    fun etiquetas(estado: TurnScreenState?): Int = TODO("2B2a")
}
