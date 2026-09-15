package graph.core.contrato

import kotlinx.coroutines.runBlocking

actual fun corre(block: suspend () -> Unit) = runBlocking { block() }
