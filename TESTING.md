# Plan de testing — workflows + MCPs (aprendizaje y ejecución)

**Objetivo:** validar el ciclo completo hasta que funcione perfecto:
enseñanza → post-procesamiento → conocimiento (MCP + workflow + grafo) → ejecución mixta
consciente ↔ subconsciente.

**Cómo trabajamos:** yo verifico de forma autónoma todo lo que se puede sin teléfono; tú eres el
tester en el dispositivo real. Por cada prueba me reportas: *qué hiciste, qué esperabas, qué pasó*, y
**pegas los logs** de la app principal (los tags `[workflow]`, `[learn]`, `[mcp]`, `[neo4j]`,
`[gemini]` cuentan toda la historia). Con eso yo diagnostico, corrijo en la rama, subo y te digo qué
reprobar. Iteramos hasta el criterio de éxito.

**Criterio de "funciona perfecto":** las pruebas 1–5 pasan 3 veces seguidas sin fallos, incluyendo al
menos un workflow que se ejecute 100 % subconsciente de principio a fin.

---

## Verificación autónoma (la corro yo en cada cambio)

- Compilación de `:core` y `:app` (SDK 35).
- Harness JVM del núcleo real: grabadora de workflows (cierre por app / por grabación, clics sin
  etiqueta), runner (switch de vías, fallback en caliente, reporte de fallos) y la secuencia
  consolidar-MCP → reconectar → estructurar-workflow.
- Revisión de los logs que me pegues: cada fallo delata su capa por el tag.

## Pruebas en el teléfono (tú como tester)

### Prueba 1 — Nace un workflow (enseñanza pasiva)

1. En la app principal activa **Aprendizaje pasivo**.
2. Abre la **Calculadora** y haz una operación completa (p.ej. `5 + 7 + 9 =`).
3. Sal de la app (home) y espera ~10–20 s (post-procesamiento LLM).

**Esperado:**
- Voz/narración: *"🧩 Ahora el uso de Calculadora es mejor y más rápido."* y *"🧭 Aprendí el flujo …"*.
- Logs: `[workflow] traza lista: N steps (passive)` → `🧭 workflow "…": N steps (X subconscientes)`.
- En la card **Workflows** de la app principal aparece el workflow; al tocarlo se ve el paso a paso
  con la vía de cada step (🧩 subconsciente / 👁 consciente) y sus notas 📝.

### Prueba 2 — Ejecutar el workflow (el switch de vías, visible)

1. Pide (texto o voz): *"haz una suma en la calculadora: 8 + 4"*.

**Esperado:**
- La **statusbar negra** de arriba muestra en vivo `🧩 subconsciente` en los clics por árbol de UI y
  `👁 consciente` cuando mira la pantalla; tocarla detiene.
- Logs: `decide: workflow_…` → `[workflow] ▶ "…"` → steps con 🧩/👁 → detalle final
  (`X subconscientes + Y conscientes`).
- El resultado es correcto y NO re-improvisó los pasos (no hubo screenshots innecesarios).

### Prueba 3 — Reconexión inversa (el MCP nace después que el workflow)

1. Con aprendizaje pasivo activo, usa una app **poco** (2–3 clics con sentido) y sal: el workflow
   debería salir con steps 👁 conscientes (mapa MCP aún pobre).
2. Verifícalo en la card Workflows.
3. Activa el aprendizaje pasivo otra vez y usa la misma app **a fondo**; sal.

**Esperado:**
- Logs: `[workflow] 🔗 "…" reconectado al MCP de <app>: X/N steps ya subconscientes`.
- En la card Workflows, el MISMO workflow ahora tiene más 🧩 que antes (sin duplicarse).

### Prueba 4 — Nace un workflow (enseñanza activa)

1. Toca el 🎓 de la burbuja, acepta compartir pantalla.
2. Haz una tarea completa explicándola con tu voz (p.ej. enviar un mensaje en WhatsApp).
3. Vuelve a tocar el 🎓.

**Esperado:**
- Además del conocimiento textual de siempre, logs `[workflow] traza lista: N steps (active)` y el
  workflow en la card (una traza activa puede cruzar varias apps).

### Prueba 5 — El grafo de conocimiento (Neo4j)

1. Crea una instancia gratis en **Neo4j AuraDB** (console.neo4j.io) y copia URI, usuario y contraseña.
2. Pégalas en la card de configuración de la app y toca **Guardar keys**.

**Esperado:**
- Logs: `[neo4j] 🕸 grafo sincronizado: X mapas MCP + Y workflows` (y `🕸 … proyectado` en cada
  aprendizaje nuevo).
- En Neo4j Browser, este Cypher dibuja el conocimiento:
  ```cypher
  MATCH p = (w:Workflow)-[:HAS_STEP]->(:Step)-[r]->()
  RETURN p LIMIT 100
  ```
  Modelo: `(:Workflow)-[:HAS_STEP]->(:Step)-[:NEXT]->(:Step)`, `(:Step)-[:TAPS]->(:Element)`,
  `(:McpMap)-[:KNOWS]->(:Element)-[:IN_APP]->(:App)`, `(:Workflow)-[:USES_APP]->(:App)`.

### Prueba 6 — Resiliencia ante la sobrecarga de Google

Si vuelven los `500/503` "high demand": el log debe mostrar
`HTTP 500 transitorio (sobrecarga de Google) · reintento n/3 en Xms` y recuperarse solo en baches
cortos. Solo debe fallar si Google sigue caído tras los 4 intentos (~13 s).

---

## Registro de iteraciones

| # | Fecha | Prueba | Resultado | Acción |
|---|-------|--------|-----------|--------|
| 1 | — | — | — | — |
