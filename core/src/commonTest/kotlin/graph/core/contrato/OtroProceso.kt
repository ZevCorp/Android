package graph.core.contrato

/**
 * El rechazo del tercer toque a un nodo con [selector], tal como lo escribe el log de OTRO proceso: uno nuevo, con su propia
 * sal (promesa 317). Solo jvm lanza procesos: por eso aquí se declara y en jvmTest se lanza.
 */
expect fun rechazoEnOtroProceso(selector: String): String
