// Estado del cerebro entre turnos, serializado como blob OPACO que el cliente reenvía sin leer.
//
// El backend es stateless (Vercel serverless): no guarda memoria entre requests. Todo el estado del
// hilo del cerebro (el `previous_response_id` de OpenAI, las llamadas pendientes, el objetivo) viaja
// firmado con HMAC dentro de `session`. El cliente lo trata como una caja negra: lo recibe y lo
// vuelve a mandar el siguiente turno. La firma impide que se manipule; no contiene prompts ni la
// key del modelo, así que la innovación sigue viviendo solo en el servidor.

import crypto from 'crypto';

/** Una llamada pendiente del modelo a la que hay que responder el próximo turno. */
export interface PendingCall {
  id: string;
  name: string; // "computer" para computer_call; el nombre de la función si es function_call
  isComputer: boolean;
  /** pending_safety_checks a acusar (JSON crudo de OpenAI), vacío si no hay. */
  safety: unknown[];
  /** Para calls resueltas server-side (p.ej. list_apps): el output a enviar. */
  internalOutput?: string;
}

/** Todo lo que hay que recordar del hilo del cerebro para continuarlo el siguiente turno. */
export interface SessionState {
  goal: string;
  model: string;
  effort: string;
  /** `previous_response_id` de la Responses API (el servidor de OpenAI guarda la conversación). */
  previousId: string;
  /** Id con el que se reanudó un hilo previo (para reabrir ventana si expiró). */
  startId: string;
  continuationMessage: string;
  informText: string;
  pending: PendingCall[];
}

export function freshSession(goal: string, model: string, effort: string): SessionState {
  return {
    goal,
    model,
    effort,
    previousId: '',
    startId: '',
    continuationMessage: '',
    informText: '',
    pending: [],
  };
}

function secret(): string {
  return process.env.SESSION_SECRET || 'dev-insecure-session-secret-change-me';
}

/** Firma y codifica el estado en un token opaco `<base64url payload>.<base64url hmac>`. */
export function encodeSession(s: SessionState): string {
  const payload = Buffer.from(JSON.stringify(s), 'utf8').toString('base64url');
  const mac = crypto.createHmac('sha256', secret()).update(payload).digest('base64url');
  return `${payload}.${mac}`;
}

/** Decodifica y verifica el token. Lanza si la firma no cuadra (manipulación). */
export function decodeSession(token: string): SessionState {
  const [payload, mac] = token.split('.');
  if (!payload || !mac) throw new Error('session token malformado');
  const expected = crypto.createHmac('sha256', secret()).update(payload).digest('base64url');
  const a = new Uint8Array(Buffer.from(mac));
  const b = new Uint8Array(Buffer.from(expected));
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) throw new Error('firma de sesión inválida');
  return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as SessionState;
}
