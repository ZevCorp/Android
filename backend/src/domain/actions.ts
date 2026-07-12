// Contrato de acciones y estado entre el cliente (frontend Windows) y el cerebro (backend).
//
// Este archivo es el ÚNICO punto de acoplamiento entre las dos mitades del sistema. El cliente
// no conoce nada del cerebro salvo estos tipos; el cerebro no conoce nada de Windows salvo estos
// tipos. Si mañana llega un cliente macOS o un frontend web, hablan este mismo idioma.

/**
 * Estado de la pantalla que el CLIENTE captura y envía al cerebro en cada turno.
 * Por defecto es TEXTO (georreferenciación por árbol de UI, leído con UIA en Windows):
 * `screen` (proceso · título de ventana) y `uiContext` (tipo de pantalla + etiquetas visibles)
 * bastan para que el modelo sepa dónde está y actúe con MCP. El `screenshot` (PNG en base64) solo
 * se adjunta cuando el turno anterior pidió computer-use (`needsScreenshot`).
 */
export interface ScreenState {
  screen: string;
  uiContext: string;
  width: number;
  height: number;
  /** PNG en base64 SIN el prefijo data-uri. Solo presente cuando el cerebro pidió mirar la pantalla. */
  screenshot?: string | null;
  /** Apps instaladas que el cliente conoce (resuelve `list_apps` y alimenta el prompt). */
  apps?: string[];
}

/**
 * Una acción que el cerebro decide y el CLIENTE ejecuta localmente. Es la unión discriminada que
 * viaja por el cable: computer-use (coordenadas en píxeles de la pantalla real) o una llamada MCP
 * (el cliente la despacha por nombre a su ejecutor local — gesto de Windows o acción de sistema).
 */
export type Action =
  | { kind: 'tap'; x: number; y: number }
  | { kind: 'type'; x: number; y: number; text: string }
  | { kind: 'scroll'; down: boolean }
  | { kind: 'swipe'; x1: number; y1: number; x2: number; y2: number; ms: number }
  | { kind: 'key'; key: string }
  | { kind: 'wait'; ms: number }
  | { kind: 'mcp'; tool: string; args: Record<string, string> };

/**
 * Lo que el cerebro devuelve en un turno: acciones a ejecutar + narración/voz/pregunta, o fin con
 * texto. Espejo de `BrainTurn` del core Kotlin, serializado a JSON.
 */
export interface BrainTurn {
  actions: Action[];
  question?: string | null;
  done: boolean;
  text: string;
  /** El cerebro usará computer-use el próximo turno: el cliente debe adjuntar un screenshot. */
  needsScreenshot: boolean;
  /** Globo de diálogo con personalidad (narración silenciosa). */
  narration: string;
  /** Frase para decir en voz alta (solo cosas importantes). */
  speech?: string | null;
  /** Intención por acción, para narrar en tiempo real cada paso. */
  intents: string[];
}
