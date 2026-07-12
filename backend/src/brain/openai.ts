// El cerebro: OpenAI con computer-use nativo sobre la Responses API + las herramientas MCP.
//
// Port stateless de app/platform/OpenAiBrain.kt. En Android era una clase con estado en campos
// (previousId, pending, …) que persistía en el proceso entre turnos. Aquí, como el backend es
// serverless, reconstruimos ese estado desde `SessionState` (el blob opaco) al entrar y lo
// devolvemos actualizado al salir. Misma lógica de protocolo, sin instancia viva.
//
// Protocolo (developers.openai.com/api/docs/guides/tools-computer-use):
//  - POST /v1/responses con Authorization: Bearer <key> y tool {type:"computer"}.
//  - La conversación la mantiene el servidor de OpenAI vía previous_response_id; cada turno reenvía
//    computer_call_output (screenshot) y/o function_call_output (resultado MCP).
//  - Las acciones vienen en PÍXELES ABSOLUTOS del screenshot enviado; el cliente ya manda su tamaño
//    real y aquí reescalamos screenshot→pantalla.

import { Action, BrainTurn, ScreenState } from '../domain/actions';
import { McpTool } from '../domain/mcp';
import { PendingCall, SessionState } from '../domain/session';
import { goalPrompt } from './prompt';

const OA_BASE = 'https://api.openai.com';

interface TurnInput {
  session: SessionState;
  tools: McpTool[];
  mcpNames: Set<string>;
  memory: string;
  apps: string[];
  state: ScreenState;
  results: string[];
  apiKey: string;
}

export interface TurnOutput {
  session: SessionState;
  turn: BrainTurn;
}

/** PNG (base64 sin prefijo) → data-uri para la Responses API. */
function dataUri(b64: string): string {
  return `data:image/png;base64,${b64}`;
}

/** Declaración de función (Responses API) desde una McpTool, con enum en las opciones. */
function mcpFn(t: McpTool): unknown {
  const properties: Record<string, unknown> = {};
  for (const p of t.params) {
    properties[p.name] = {
      type: 'string',
      description: p.description,
      ...(p.options && p.options.length ? { enum: p.options } : {}),
    };
  }
  return {
    type: 'function',
    name: t.name,
    description: t.description,
    parameters: { type: 'object', properties, required: t.params.map((p) => p.name) },
  };
}

function customFn(name: string, description: string, arg: string): unknown {
  return {
    type: 'function',
    name,
    description,
    parameters: { type: 'object', properties: { [arg]: { type: 'string' } }, required: [arg] },
  };
}

function transient(code: number): boolean {
  return code === 429 || (code >= 500 && code <= 599);
}

async function oaHttp(url: string, apiKey: string, body: unknown): Promise<{ code: number; body: string }> {
  let wait = 800;
  for (let attempt = 1; ; attempt++) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify(body),
    });
    const text = await res.text();
    if (!transient(res.status) || attempt >= 4) return { code: res.status, body: text };
    await new Promise((r) => setTimeout(r, wait));
    wait = Math.min(wait * 2, 8000);
  }
}

const asStr = (v: unknown): string => (typeof v === 'string' ? v : v == null ? '' : String(v));
const asObj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' ? (v as Record<string, unknown>) : {});
const asArr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Ejecuta un turno del cerebro. Devuelve la sesión actualizada + el BrainTurn a mandar al cliente. */
export async function runBrainTurn(inp: TurnInput): Promise<TurnOutput> {
  const s: SessionState = JSON.parse(JSON.stringify(inp.session)); // copia mutable
  const { tools, mcpNames, memory, apps, state, results, apiKey } = inp;

  const stateBlock = `Pantalla actual: ${state.screen}\nDónde estás (árbol de UI de Windows):\n${state.uiContext}`;
  const input: unknown[] = [];

  const userMessage = (text: string) => {
    const content: unknown[] = [{ type: 'input_text', text }];
    if (state.screenshot) content.push({ type: 'input_image', image_url: dataUri(state.screenshot), detail: 'original' });
    input.push({ type: 'message', role: 'user', content });
  };

  if (!s.previousId) {
    userMessage(goalPrompt({ goal: s.goal, tools, memory, stateBlock }));
  } else if (s.pending.length === 0) {
    userMessage((s.continuationMessage || s.informText || 'Continúa.') + `\n${stateBlock}`);
    s.continuationMessage = '';
    s.informText = '';
  } else {
    s.pending.forEach((call, i) => {
      if (call.isComputer) {
        const out = {
          type: 'computer_screenshot',
          image_url: state.screenshot ? dataUri(state.screenshot) : '',
          detail: 'original',
        };
        const fields: Record<string, unknown> = { type: 'computer_call_output', call_id: call.id, output: out };
        if (call.safety && call.safety.length) fields.acknowledged_safety_checks = call.safety;
        input.push(fields);
      } else if (call.name === 'ask_user') {
        input.push(functionOutput(call.id, s.informText || '(sin respuesta)'));
      } else if (call.internalOutput != null) {
        input.push(functionOutput(call.id, call.internalOutput));
      } else if (call.name === 'speak') {
        input.push(functionOutput(call.id, 'ok'));
      } else {
        input.push(functionOutput(call.id, results[i] ?? 'ok'));
      }
    });
    s.informText = '';
  }

  const toolDecls: unknown[] = [{ type: 'computer' }];
  for (const t of tools) toolDecls.push(mcpFn(t));
  toolDecls.push(customFn('ask_user', 'Pregunta al usuario cuando tengas una duda real e importante. Responde con texto o voz.', 'question'));
  toolDecls.push(customFn('speak', 'Di algo en voz alta con tu personalidad. Solo para lo importante; no narres cada paso.', 'text'));
  toolDecls.push(customFn('list_apps', 'Lista las aplicaciones instaladas para elegir cuál abrir.', 'reason'));

  const reqBody: Record<string, unknown> = {
    model: s.model,
    input,
    tools: toolDecls,
    truncation: 'auto',
    reasoning: { effort: s.effort },
  };
  if (s.previousId) reqBody.previous_response_id = s.previousId;

  const res = await oaHttp(`${OA_BASE}/v1/responses`, apiKey, reqBody);
  if (res.code >= 300) {
    // Si el hilo previo expiró y aún no hicimos nada este turno, abre ventana nueva y reintenta una vez.
    if (s.startId && s.previousId === s.startId) {
      s.previousId = '';
      s.startId = '';
      s.continuationMessage = '';
      return runBrainTurn({ ...inp, session: s });
    }
    throw new Error(`OpenAI HTTP ${res.code}: ${res.body.slice(0, 200)}`);
  }

  return parseTurn(JSON.parse(res.body), s, state, mcpNames, apps);
}

function functionOutput(callId: string, output: string): unknown {
  return { type: 'function_call_output', call_id: callId, output };
}

/** Traduce la respuesta de la Responses API a un BrainTurn + la sesión actualizada. */
function parseTurn(
  body: Record<string, unknown>,
  s: SessionState,
  state: ScreenState,
  mcpNames: Set<string>,
  apps: string[],
): TurnOutput {
  s.previousId = asStr(body.id) || s.previousId;
  const items = asArr(body.output ?? body.outputs).map(asObj);

  // Reescalado screenshot→pantalla: OpenAI da píxeles del screenshot enviado.
  // El cliente Windows captura el screenshot a la resolución real de pantalla, así que el screenshot
  // y la pantalla coinciden (escala 1) salvo que el cliente reduzca; en ese caso mandaría su tamaño.
  const sx = 1;
  const sy = 1;
  const px = (a: Record<string, unknown>, key: string, scale: number): number => {
    const raw = a[key];
    const n = typeof raw === 'number' ? raw : typeof raw === 'string' ? parseFloat(raw) : NaN;
    return Number.isFinite(n) ? Math.round(n * scale) : -1;
  };

  const actions: Action[] = [];
  const pending: PendingCall[] = [];
  const intents: string[] = [];
  let question: string | null = null;
  let speech: string | null = null;
  let text = '';

  const addAction = (a: Record<string, unknown>) => {
    switch (asStr(a.type)) {
      case 'click':
      case 'double_click':
      case 'left_click':
        actions.push({ kind: 'tap', x: px(a, 'x', sx), y: px(a, 'y', sy) });
        break;
      case 'type':
        actions.push({ kind: 'type', x: px(a, 'x', sx), y: px(a, 'y', sy), text: asStr(a.text) });
        break;
      case 'keypress':
      case 'key': {
        const keys = asArr(a.keys).map(asStr).filter(Boolean);
        const single = asStr(a.key);
        actions.push({ kind: 'key', key: mapKey(keys.length ? keys : single ? [single] : []) });
        break;
      }
      case 'scroll': {
        const dy = px(a, 'scroll_y', 1);
        const dyAlt = px(a, 'delta_y', 1);
        const v = dy !== -1 ? dy : dyAlt !== -1 ? dyAlt : 1;
        actions.push({ kind: 'scroll', down: v >= 0 });
        break;
      }
      case 'drag':
      case 'swipe': {
        const path = asArr(a.path).map(asObj);
        const p0 = path[0] ?? {};
        const p1 = path[path.length - 1] ?? p0;
        actions.push({ kind: 'swipe', x1: px(p0, 'x', sx), y1: px(p0, 'y', sy), x2: px(p1, 'x', sx), y2: px(p1, 'y', sy), ms: 400 });
        break;
      }
      case 'wait':
        actions.push({ kind: 'wait', ms: Number(px(a, 'ms', 1) > 0 ? px(a, 'ms', 1) : 1000) });
        break;
      default:
        break; // move / screenshot: no aplican (el screenshot ya viaja en cada output)
    }
  };

  for (const item of items) {
    switch (asStr(item.type)) {
      case 'message':
        text += extractMessage(item);
        break;
      case 'output_text':
        text += asStr(item.text);
        break;
      case 'computer_call': {
        const id = asStr(item.call_id) || asStr(item.id) || `call_${pending.length}`;
        const safety = asArr(item.pending_safety_checks);
        pending.push({ id, name: 'computer', isComputer: true, safety });
        const acts = asArr(item.actions).map(asObj);
        if (acts.length) acts.forEach(addAction);
        else if (item.action) addAction(asObj(item.action));
        break;
      }
      case 'function_call': {
        const name = asStr(item.name);
        const id = asStr(item.call_id) || asStr(item.id) || `call_${pending.length}`;
        const safety = asArr(item.pending_safety_checks);
        let args: Record<string, unknown> = {};
        try {
          args = asObj(JSON.parse(asStr(item.arguments)));
        } catch {
          args = asObj(item.arguments);
        }
        const call: PendingCall = { id, name, isComputer: false, safety };
        if (name !== 'ask_user' && name !== 'speak') intents.push(asStr(args.intent));
        if (mcpNames.has(name)) {
          const cleanArgs: Record<string, string> = {};
          for (const [k, v] of Object.entries(args)) if (k !== 'intent') cleanArgs[k] = asStr(v);
          actions.push({ kind: 'mcp', tool: name, args: cleanArgs });
        } else if (name === 'list_apps') {
          call.internalOutput = JSON.stringify({ apps });
        } else if (name === 'ask_user') {
          question = asStr(args.question);
        } else if (name === 'speak') {
          speech = asStr(args.text);
        }
        pending.push(call);
        break;
      }
      default:
        break;
    }
  }

  s.pending = pending;
  const needsScreenshot =
    pending.some((c) => c.isComputer) || actions.some((a) => a.kind === 'tap' || a.kind === 'type');

  const turn: BrainTurn = {
    actions,
    question,
    done: pending.length === 0,
    text,
    needsScreenshot,
    narration: intents.find((i) => i) ?? '',
    speech,
    intents,
  };
  return { session: s, turn };
}

/** Une los keys de un keypress a lo que espera el ejecutor del cliente (enter/back/…), o el primero. */
function mapKey(keys: string[]): string {
  const up = keys.map((k) => k.toUpperCase());
  if (up.includes('ENTER') || up.includes('RETURN')) return 'enter';
  if (up.includes('ESC') || up.includes('ESCAPE')) return 'back';
  return (keys[0] ?? '').toLowerCase();
}

function extractMessage(item: Record<string, unknown>): string {
  const content = item.content;
  if (Array.isArray(content)) {
    return content.map((part) => {
      const o = asObj(part);
      return asStr(o.text) || asStr(o.output_text);
    }).join('');
  }
  if (typeof content === 'string') return content;
  return asStr(item.text);
}
