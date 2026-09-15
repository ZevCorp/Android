package graph.core.graph

/** La key de Graph resuelta, o la línea que dice qué falta. */
sealed interface Credential {
    class Ok(val key: String) : Credential
    class Falta(val message: String) : Credential
}

/**
 * De dónde sale la key de Graph: lo que el usuario puso en el panel de desarrollador (prefs) gana
 * sobre lo horneado en el APK (`apikey.properties`). Vacío o solo blancos cuenta como ausente.
 */
object GraphCredentials {
    const val FALTA = "no hay key de graph: ponela en el panel de desarrollador o en apikey.properties como graphApiKey"

    fun resolve(userPref: String?, compiled: String?): Credential {
        val key = userPref?.trim()?.ifEmpty { null } ?: compiled?.trim()?.ifEmpty { null }
        return if (key != null) Credential.Ok(key) else Credential.Falta(FALTA)
    }
}
