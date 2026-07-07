package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.Workflow
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * GRAFO DE CONOCIMIENTO en Neo4j (AuraDB por Cypher/HTTP): los aprendizajes del árbol de UI y los
 * workflows se proyectan como un grafo — el "grado de conocimiento" del asistente — navegable en
 * Neo4j Browser/Bloom:
 *
 *   (:Workflow)-[:HAS_STEP]->(:Step)-[:NEXT]->(:Step)     ← el flujo de la tarea
 *   (:Step)-[:TAPS]->(:Element)-[:IN_APP]->(:App)         ← steps subconscientes tocan elementos
 *   (:McpMap)-[:KNOWS]->(:Element), (:McpMap)-[:MAPS]->(:App) ← el mapa MCP de cada app
 *   (:Workflow)-[:USES_APP]->(:App)
 *
 * Igual que CloudSync: OFFLINE-FIRST. El disco local sigue siendo la fuente en caliente; el grafo se
 * actualiza en background (push al guardar, sync completo al arrancar) y NUNCA lanza. Si no hay
 * credenciales configuradas (URI/usuario/contraseña de Aura en la app), la capa queda en silencio.
 */
object KnowledgeGraph {

    /** Credenciales en caliente (las lee de prefs vía GraphApp): (uri, user, password). */
    @Volatile var credentials: () -> Triple<String, String, String> = { Triple("", "", "") }

    private val json = Json

    val configured: Boolean
        get() = credentials().let { (uri, user, pass) -> uri.isNotBlank() && user.isNotBlank() && pass.isNotBlank() }

    /** Proyecta (upsert) el mapa MCP de una app: McpMap → KNOWS → Element → IN_APP → App. */
    fun pushTool(tool: LearnedTool) {
        if (!configured || tool.app.isBlank()) return
        runCatching {
            cypher(
                """
                MERGE (a:App {pkg: ${'$'}app})
                MERGE (m:McpMap {name: ${'$'}name})
                SET m.description = ${'$'}description
                MERGE (m)-[:MAPS]->(a)
                WITH m, a
                UNWIND ${'$'}elements AS label
                MERGE (e:Element {label: label, app: ${'$'}app})
                MERGE (e)-[:IN_APP]->(a)
                MERGE (m)-[:KNOWS]->(e)
                """.trimIndent(),
                buildJsonObject {
                    put("app", tool.app)
                    put("name", tool.name)
                    put("description", tool.description)
                    putJsonArray("elements") { tool.elements.forEach { add(it) } }
                },
            )
            LogBus.log("neo4j", "🕸 mapa MCP proyectado: ${tool.name} (${tool.elements.size} elementos)")
        }.onFailure { LogBus.log("neo4j", "🕸 no pude proyectar ${tool.name}: ${it.message}") }
    }

    /** Proyecta (upsert) un workflow: Workflow → HAS_STEP → Step → NEXT/TAPS, reemplazando sus steps. */
    fun pushWorkflow(wf: Workflow) {
        if (!configured) return
        runCatching {
            cypher(
                """
                MERGE (w:Workflow {name: ${'$'}name})
                SET w.description = ${'$'}description, w.source = ${'$'}source
                WITH w
                OPTIONAL MATCH (w)-[:HAS_STEP]->(old:Step)
                DETACH DELETE old
                WITH DISTINCT w
                UNWIND ${'$'}steps AS st
                MERGE (a:App {pkg: st.app})
                CREATE (s:Step {order: st.order, action: st.action, subconscious: st.subconscious, note: st.note})
                MERGE (w)-[:HAS_STEP]->(s)
                MERGE (s)-[:IN_APP]->(a)
                MERGE (w)-[:USES_APP]->(a)
                FOREACH (_ IN CASE WHEN st.target <> '' THEN [1] ELSE [] END |
                  MERGE (e:Element {label: st.target, app: st.app})
                  MERGE (e)-[:IN_APP]->(a)
                  MERGE (s)-[:TAPS]->(e))
                """.trimIndent(),
                buildJsonObject {
                    put("name", wf.name)
                    put("description", wf.description)
                    put("source", wf.source)
                    putJsonArray("steps") {
                        wf.steps.forEachIndexed { i, s ->
                            addJsonObject {
                                put("order", i + 1)
                                put("app", s.app.ifBlank { "desconocida" })
                                put("action", s.action)
                                put("target", s.target)
                                put("subconscious", s.subconscious)
                                put("note", s.note)
                            }
                        }
                    }
                },
            )
            // La cadena temporal del flujo: step 1 → step 2 → … (primero a, después b, después c).
            cypher(
                """
                MATCH (w:Workflow {name: ${'$'}name})-[:HAS_STEP]->(s:Step)
                WITH s ORDER BY s.order
                WITH collect(s) AS ss
                UNWIND range(0, size(ss) - 2) AS i
                WITH ss[i] AS a, ss[i + 1] AS b
                MERGE (a)-[:NEXT]->(b)
                """.trimIndent(),
                buildJsonObject { put("name", wf.name) },
            )
            val sub = wf.steps.count { it.subconscious }
            LogBus.log("neo4j", "🕸 workflow proyectado: ${wf.name} (${wf.steps.size} steps, $sub subconscientes)")
        }.onFailure { LogBus.log("neo4j", "🕸 no pude proyectar el workflow ${wf.name}: ${it.message}") }
    }

    /** Quita un workflow del grafo (cuando el usuario lo borra en la app). */
    fun deleteWorkflow(name: String) {
        if (!configured) return
        runCatching {
            cypher(
                """
                MATCH (w:Workflow {name: ${'$'}name})
                OPTIONAL MATCH (w)-[:HAS_STEP]->(s:Step)
                DETACH DELETE w, s
                """.trimIndent(),
                buildJsonObject { put("name", name) },
            )
        }.onFailure { LogBus.log("neo4j", "🕸 no pude borrar el workflow $name: ${it.message}") }
    }

    /** Sync de arranque: proyecta TODO el conocimiento actual (llamar desde IO). */
    fun syncAll(tools: List<LearnedTool>, workflows: List<Workflow>) {
        if (!configured) return
        tools.forEach { pushTool(it) }
        workflows.forEach { pushWorkflow(it) }
        if (tools.isNotEmpty() || workflows.isNotEmpty())
            LogBus.log("neo4j", "🕸 grafo sincronizado: ${tools.size} mapas MCP + ${workflows.size} workflows")
    }

    /* ---------- Cypher sobre HTTP (Query API v2 de Neo4j/Aura) ---------- */

    /** Normaliza la URI de Aura (neo4j+s://host o https://host) al endpoint de la Query API. */
    private fun endpoint(uri: String): String {
        val host = uri.trim()
            .removePrefix("neo4j+s://").removePrefix("neo4j+ssc://").removePrefix("neo4j://")
            .removePrefix("https://").removePrefix("http://")
            .trimEnd('/')
        return "https://$host/db/neo4j/query/v2"
    }

    private fun cypher(statement: String, parameters: JsonObject) {
        val (uri, user, pass) = credentials()
        val body = buildJsonObject {
            put("statement", statement)
            putJsonObject("parameters") { parameters.forEach { (k, v) -> put(k, v) } }
        }
        val bytes = json.encodeToString(JsonObject.serializer(), body).toByteArray()
        val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
        val (code, resp) = GeminiHttp.withRetry("neo4j") {
            val c = URL(endpoint(uri)).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Authorization", "Basic $auth")
            c.doOutput = true
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            val respBody = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            status to respBody
        }
        if (code >= 300) error("Neo4j HTTP $code: ${resp.take(160)}")
        // La Query API devuelve 200 aunque el Cypher falle: los errores vienen en el cuerpo.
        if (resp.contains("\"errors\"") && !resp.contains("\"errors\":[]") && !resp.contains("\"errors\": []"))
            error("Cypher: ${resp.take(200)}")
    }
}
