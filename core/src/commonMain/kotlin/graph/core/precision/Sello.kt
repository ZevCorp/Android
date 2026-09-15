package graph.core.precision

/**
 * EL SELLO DE UN DESTINO EN EL LOG (spec 003, promesa 317). El log sale del teléfono por la telemetría: lo que nombra un
 * destino tiene que servir para seguirlo de una línea a otra dentro del proceso y para nada más. Por eso no es un hash
 * cualquiera: es un HMAC con una llave al azar que nace con el proceso y no sale de él. Sin la llave no se recorre un
 * diccionario (el FNV-1a sin sal de antes volvía a «ana» en menos de un segundo), y otro proceso, con otra llave, sella
 * distinto el mismo destino: no sirve de identificador entre teléfonos ni entre arranques.
 *
 * Solo se sella lo estructural (ver [TopeDeIntentos.enLog]): sellar el texto visible seguiría siendo una huella de lo que la
 * pantalla muestra. Cada plataforma pone su HMAC y su azar seguro, como el [Candado]: common no los trae sin dependencia.
 * Devuelve 8 hex: sin la llave, 32 bits bastan para seguir un destino dentro de una petición.
 */
internal expect fun sello(texto: String): String
