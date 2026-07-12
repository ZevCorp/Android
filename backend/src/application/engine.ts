// Orquestación de UN turno del lado servidor. El bucle completo (capturar pantalla → decidir →
// ejecutar → repetir) lo conduce el CLIENTE; el servidor resuelve cada turno de forma stateless.
//
// Compárese con core/application/Engine.kt: allí `ExecutionEngine.run` corría el bucle entero en el
// dispositivo llamando a `brain.next` + `phone`/`mcp`. Aquí partimos ese bucle por la mitad: la
// decisión (brain.next) vive en el servidor; la ejecución (phone/mcp) vive en el cliente. El
// contrato `Action[]`/`BrainTurn` es la costura.

import { BrainTurn, ScreenState } from '../domain/actions';
import { baseCatalog, catalogNames, McpTool } from '../domain/mcp';
import { SessionState } from '../domain/session';
import { runProviderTurn } from '../brain/provider';
import { MemoryStore } from '../memory/store';
import { LearningStore, learnedToMcp, workflowToMcp } from '../learning/workflows';
import { activeKey } from '../config';

export interface TurnRequest {
  userId: string;
  session: SessionState;
  state: ScreenState;
  results: string[];
}

export interface TurnResult {
  session: SessionState;
  turn: BrainTurn;
}

/**
 * Ensambla el catálogo MCP que el cerebro declara al modelo este turno: base (gestos + sistema) +
 * herramientas aprendidas + workflows, según lo que el store sepa de las apps visibles. Todo esto es
 * innovación server-side; el cliente solo recibe el `Action[]` resultante.
 */
async function assembleTools(userId: string, apps: string[], learning: LearningStore): Promise<McpTool[]> {
  const learned = await learning.learnedTools(userId, apps);
  const workflows = await learning.workflows(userId, apps);
  return [...baseCatalog(), ...learned.map(learnedToMcp), ...workflows.map(workflowToMcp)];
}

export async function resolveTurn(
  req: TurnRequest,
  deps: { memory: MemoryStore; learning: LearningStore },
): Promise<TurnResult> {
  const apps = req.state.apps ?? [];
  const tools = await assembleTools(req.userId, apps, deps.learning);
  const memory = await deps.memory.forPrompt(req.userId);

  const { session, turn } = await runProviderTurn({
    session: req.session,
    tools,
    mcpNames: catalogNames(tools),
    memory,
    apps,
    state: req.state,
    results: req.results,
    apiKey: activeKey(),
  });

  return { session, turn };
}
