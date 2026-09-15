package graph.core.precision

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** La llave del proceso: 32 bytes de `SecureRandom`, en memoria y en ningún otro sitio. */
private val LLAVE = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256")

/** En jvm, HMAC-SHA256 con la llave del proceso, sus 4 primeros bytes. Un `Mac` por llamada: no es seguro entre hilos, y el log sella poco. */
internal actual fun hmacDelProceso(texto: String): String =
    Mac.getInstance("HmacSHA256").apply { init(LLAVE) }.doFinal(texto.encodeToByteArray())
        .take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
