# Cómo trabajamos — el método, traducido de U-Windows-App

Este repo adopta el mismo método que `U-Windows-App`: **desarrollo dirigido por especificación**
(SDD). Ninguna línea de producción entra antes que la promesa que la juzga. Lo que TIENE que pasar
lo hace cumplir el portero; los `.md` explican el porqué.

## El flujo, en cinco pasos

1. **Spec** — `docs/specs/NNN-<que-hace>.md`. Diagnóstico medido (no supuesto), la tabla de
   promesas numeradas, las fases y lo que NO entra. Commit `docs(specs): …`.
2. **Promesas en rojo** — `core/src/commonTest/kotlin/graph/core/contrato/ContratoNNN<Nombre>.kt`,
   un método `promesaNN` por fila de la tabla, con el enunciado **literal** de la spec. Se
   escriben antes que el código y se ven fallar. Commit `test(contrato): …`.
3. **Implementación** — lo mínimo que pone el contrato INTACTO. Commit `feat(...)`/`fix(...)`.
4. **Verificación** — la compuerta de cuatro niveles (abajo), con la evidencia pegada en el PR.
5. **PR a `main`** — con squash. `main` no recibe push directo, nunca.

Una promesa que pasa apenas se escribe no probó nada. Un contrato con pendientes no está intacto.

## Dónde vive cada cosa

| Qué | Dónde |
|---|---|
| Specs | `docs/specs/NNN-*.md` (numeración correlativa desde 001) |
| Contratos (las promesas) | `core/src/commonTest/kotlin/graph/core/contrato/` |
| El juez | `scripts/contrato.sh` → `CONTRATO INTACTO: N promesas.` o `CONTRATO ROTO` |
| El portero | `.githooks/pre-push` |
| Este método | `docs/como-trabajamos.md` |

## Ramas y commits

- Rama por trabajo: `<persona>/<que-hace>` (p. ej. `yokh/cliente-graph`). Las que por definición
  no cambian comportamiento van con prefijo `chore/`, `docs/`, `refactor/` o `hotfix/`: el
  portero no les exige promesa nueva.
- `main` solo por PR con squash. Un commit por funcionalidad en `main`.
- Formato de commit: `tipo(ámbito): lo que el sistema ahora hace`, en español y minúscula.
  Ejemplo: `feat(graph): el cerebro vive en graph; el cliente manda pantalla y recibe acciones`.

## El portero (`.githooks/pre-push`)

Se activa **una vez por clon**:

```
git config core.hooksPath .githooks
```

Antes de cada push comprueba, en este orden: que no se empuje a `main`; que compile
(`:app:compileReleaseKotlin`, lo que se distribuye); que el contrato esté intacto
(`scripts/contrato.sh`); y que una rama que cambia código traiga su propia promesa
(`core/src/commonTest/**`, contra `origin/main`). Si el portero se equivoca, la conversación es
sobre la promesa, no sobre `--no-verify`.

Juzga **lo que se empuja, no el árbol de trabajo**: cada sha que git le pasa por stdin se compila y se juzga en un worktree temporal; se prueba sin empujar con `printf 'refs/heads/<rama> %s refs/heads/<rama> 0000000000000000000000000000000000000000\n' "$(git rev-parse HEAD)" | bash .githooks/pre-push` (sin stdin juzga HEAD y el árbol, y lo avisa).

## La compuerta de cuatro niveles

Un cambio está listo cuando pasa los cuatro, y el PR dice cuáles y con qué evidencia:

1. **Compila** — `./gradlew :app:compileReleaseKotlin`.
2. **Contrato** — `./scripts/contrato.sh` → INTACTO.
3. **Escenarios** — el `ExecutionEngine` real con teléfono y transporte falsos (viven en el mismo
   contrato; ver la promesa 4 de la spec 001).
4. **Corrida a mano** — el APK release en el teléfono, contra el backend real, en **al menos dos
   apps distintas**, nombradas en el PR con lo que pasó (HTTP, ms, acción, resultado visible).

## Los números no se reciclan

Cada spec numera en su propio bloque para que dos ramas paralelas no choquen: la spec NNN usa las
promesas NNN×100+1 en adelante (la 002 empieza en 201), salvo la 001, que usa 1-99.

Una promesa retirada deja su número vacío en la tabla (tachada, con el porqué). Una promesa nueva
toma el siguiente número libre, aunque la spec ya esté "cerrada": el enunciado del test y la fila
de la spec cambian en el mismo commit, o no cambian.

El juez lo hace cumplir: cada fila de la tabla `| # | Promesa |` de cada spec necesita su
`promesaN` corrida, o sale `⧗ SIN JUEZ`; un test con `@Ignore` sale `✘ (silenciada)`; y un test sin
fila en ninguna spec no deja juzgar. Retirar una promesa es tachar su enunciado (`~~…~~`), no
borrar el test.
