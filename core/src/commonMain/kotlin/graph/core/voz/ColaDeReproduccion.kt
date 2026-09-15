package graph.core.voz

import kotlin.math.min

/**
 * LA COLA DEL ALTAVOZ (docs/specs/002, fase B1b, promesa 244). GPT-Live manda la voz mucho más rápido de lo que suena: una
 * frase de cinco segundos entra en menos de uno y espera aquí. Espejo del `BufferedWaveProvider` de
 * `U-Windows-App/windows-client/src/Voice/LiveAudio.cs:498-501`, reescrito puro para juzgarlo sin altavoz.
 *
 * AL LLENARSE SE PIERDE LO MÁS VIEJO, NUNCA LO NUEVO (`DiscardOnBufferOverflow` en U): si el audio se acumula porque el
 * teléfono va justo, se prefiere saltar un trozo ya viejo a que Ü se quede atrás para siempre o se caiga la sesión.
 *
 * [sonando] ES «TIENE BYTES», NUNCA EL VOLUMEN (`LiveAudio.cs:154`): es la llave de [CompuertaDeEco], y el silencio que
 * espera en cola también es Ü hablando, entre dos palabras de la misma frase.
 *
 * SIEMPRE MUESTRAS ENTERAS: se descarta, se guarda y se saca de a 2 bytes. Media muestra desalinea todo lo que sigue y la
 * voz sale como ruido.
 *
 * SIN CANDADO, como [TurnosSinMarca]: commonMain no tiene `synchronized`. Quien la comparte entre el hilo de la conversación y
 * el del altavoz la guarda con el suyo.
 */
class ColaDeReproduccion(ritmoHz: Int = ProtocoloGptLive.RITMO, segundos: Int = SEGUNDOS) {

    companion object {
        const val SEGUNDOS = 30
    }

    /** PCM16 mono: 2 bytes por muestra. */
    val capacidad: Int = ritmoHz * 2 * segundos

    init {
        require(capacidad > 0) { "la cola del altavoz necesita sitio: $ritmoHz Hz × $segundos s" }
    }

    /** Un anillo: meter y sacar no mueven lo que ya está. Se reserva entero al nacer, 1,4 MB con 30 s a 24 kHz. */
    private val anillo = ByteArray(capacidad)
    private var inicio = 0

    var pendientes: Int = 0
        private set

    /** Lo que se perdió por cola llena, desde que nació. Callar no es perder: no cuenta. */
    var bytesDescartados: Long = 0
        private set

    fun meter(pcm: ByteArray) {
        // Un byte suelto al final no es una muestra: no suena, y guardarlo desalinearía lo siguiente.
        var largo = pcm.size - (pcm.size and 1)
        if (largo == 0) return
        var desde = 0
        if (largo > capacidad) {
            // NI CABE ENTERO: lo más nuevo es su final. Se va todo lo que había y el principio del trozo.
            val sobra = largo - capacidad
            bytesDescartados += pendientes + sobra
            inicio = 0
            pendientes = 0
            desde = sobra
            largo = capacidad
        }
        val falta = largo - (capacidad - pendientes)
        if (falta > 0) {
            inicio = (inicio + falta) % capacidad
            pendientes -= falta
            bytesDescartados += falta
        }
        val fin = (inicio + pendientes) % capacidad
        val primero = min(largo, capacidad - fin)
        pcm.copyInto(anillo, fin, desde, desde + primero)
        if (primero < largo) pcm.copyInto(anillo, 0, desde + primero, desde + largo)
        pendientes += largo
    }

    /** Hasta [n] bytes, en muestras enteras y en orden, para el hilo del altavoz. Sin nada que dar, un array vacío. */
    fun sacar(n: Int): ByteArray {
        var k = min(n, pendientes)
        k -= k and 1
        if (k <= 0) return ByteArray(0)
        val salida = ByteArray(k)
        val primero = min(k, capacidad - inicio)
        anillo.copyInto(salida, 0, inicio, inicio + primero)
        if (primero < k) anillo.copyInto(salida, primero, 0, k - primero)
        inicio = (inicio + k) % capacidad
        pendientes -= k
        return salida
    }

    fun sonando(): Boolean = pendientes > 0

    /** En el acto: lo que esperaba ya no va a sonar. */
    fun callar() {
        inicio = 0
        pendientes = 0
    }
}
