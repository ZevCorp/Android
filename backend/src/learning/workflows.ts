// Aprendizaje: herramientas aprendidas del árbol de UI y workflows (el puente consciente ↔
// subconsciente). En Android esto vivía repartido entre `core` (tipos, PassiveLearning,
// WorkflowRecorder/Runner) y `app` (GeminiLearning, GeminiWorkflow, KnowledgeGraph...). TODO ello es
// innovación que hoy se compila dentro del APK. Aquí vive SOLO en el backend.
//
// Primera construcción: tipos + stores en memoria + puntos de extensión claramente marcados. El
// cerebro ya sabe declarar estas herramientas al modelo (ver brain/prompt.ts). La captación (post-
// procesamiento LLM de la traza, reconexión MCP↔workflow, proyección a grafo) se enchufa aquí sin
// tocar el cliente ni el contrato de acciones.

import { McpTool, LEARNED_VIA, WORKFLOW_VIA } from '../domain/mcp';

/** Un mapa de UI aprendido de una app: nombre + documentación + catálogo de elementos + app. */
export interface LearnedTool {
  name: string;
  description: string;
  elements: string[];
  app: string;
}

export interface WorkflowStep {
  action: string;
  app: string;
  /** true = clic por árbol de UI (subconsciente); false = computer-use acotado (consciente). */
  subconscious: boolean;
  note?: string;
}

export interface Workflow {
  name: string;
  description: string;
  steps: WorkflowStep[];
}

function sanitize(s: string): string {
  const cleaned = s
    .trim()
    .toLowerCase()
    .split('')
    .map((c) => ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '_'))
    .join('')
    .replace(/^_+|_+$/g, '');
  return cleaned || 'learned_tool';
}

/** Declara una herramienta aprendida como McpTool (el modelo compone la secuencia de `taps`). */
export function learnedToMcp(lt: LearnedTool): McpTool {
  const appNote = lt.app ? `[app: ${lt.app}] ` : '';
  return {
    name: sanitize(lt.name),
    description: `${appNote}${lt.description} Elementos disponibles (etiquetas exactas): ${lt.elements.join(', ')}.`,
    params: [{ name: 'taps', description: 'Etiquetas a tocar EN ORDEN, separadas por comas (usa solo las disponibles)' }],
    via: LEARNED_VIA,
  };
}

/** Declara un workflow como McpTool `workflow_*` (el modelo lo invoca entero con `context`). */
export function workflowToMcp(wf: Workflow): McpTool {
  const sub = wf.steps.filter((s) => s.subconscious).length;
  const apps = [...new Set(wf.steps.map((s) => s.app).filter(Boolean))];
  const appNote = apps.length ? `[app: ${apps.join(', ')}] ` : '';
  return {
    name: `workflow_${sanitize(wf.name)}`,
    description:
      `${appNote}${wf.description} Steps: ${wf.steps.map((s) => s.action).join(' → ')} ` +
      `(${sub} de ${wf.steps.length} subconscientes).`,
    params: [{ name: 'context', description: 'Datos variables de ESTA ejecución (nombres, textos, cantidades); "" si no aplica' }],
    via: WORKFLOW_VIA,
  };
}

/** Store de aprendizajes por app. Primera construcción: en memoria. Enchufar Supabase/Neo4j aquí. */
export interface LearningStore {
  learnedTools(userId: string, apps: string[]): Promise<LearnedTool[]>;
  workflows(userId: string, apps: string[]): Promise<Workflow[]>;
}

export class InMemoryLearningStore implements LearningStore {
  private learned: LearnedTool[] = [];
  private wf: Workflow[] = [];

  async learnedTools(): Promise<LearnedTool[]> {
    return this.learned;
  }
  async workflows(): Promise<Workflow[]> {
    return this.wf;
  }

  // Puntos de extensión para el post-procesamiento (pasivo/activo) — a implementar en fases siguientes.
  addLearned(t: LearnedTool) {
    this.learned.push(t);
  }
  addWorkflow(w: Workflow) {
    this.wf.push(w);
  }
}
