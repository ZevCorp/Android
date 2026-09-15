package graph.core.graph.learning

/**
 * EL DISCO DE LA ENSEÑANZA, como puerto (spec 004, fase 4A2): `core` no sabe de archivos. La [Leccion]
 * escribe aquí la lección, los cierres pendientes y los videos por reprocesar; la app lo implementa en 4C
 * (temporal + renombrar dentro de su carpeta privada) y el contrato, con un mapa en memoria que falla a pedido.
 *
 * Las rutas son relativas a la raíz del almacén y usan `/`: `lecciones/ses-1.json`. Quien implementa crea
 * las carpetas que hagan falta.
 */
interface Almacen {
    /**
     * Escribe [contenido] entero en [ruta], o no escribe nada: si falla a mitad, [ruta] queda como estaba
     * (con lo anterior, o sin existir). Nunca un archivo a medias: una lección cortada se lee como una
     * lección, y es peor que ninguna. Si no pudo, lanza.
     */
    suspend fun escribirEntero(ruta: String, contenido: String)

    /** El contenido de [ruta], o `null` si no existe. */
    suspend fun leer(ruta: String): String?

    /** Las rutas de los archivos que hay directamente en [carpeta], listas para [leer] y [borrar]; vacía si no existe. */
    suspend fun listar(carpeta: String): List<String>

    /** Borra [ruta]; si ya no existía, no pasa nada. */
    suspend fun borrar(ruta: String)
}
