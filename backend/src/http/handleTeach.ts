// Lógica de POST /api/teach/save-notes, independiente del runtime (Vercel o servidor local), igual
// que handleTurn.ts.
//
// El cliente Windows procesa el video de enseñanza HABLANDO DIRECTO CON GEMINI (ver el comentario en
// windows-client/src/Config.cs sobre por qué: el mp4 no cabe en el límite de payload de una función
// de Vercel, y esperar el procesamiento excede su límite de duración). Este endpoint es lo único que
// le queda al backend de esa función: recibir las notas YA EXTRAÍDAS (texto pequeño) y persistirlas
// en el MemoryStore del usuario — el mismo store que el bucle de ejecución ya usa para inyectar
// contexto en cada turno.

import { config } from '../config';
import { deps } from '../container';

export interface HttpResult {
  status: number;
  json: unknown;
}

function checkAuth(authHeader?: string): HttpResult | null {
  if (!config.clientToken) return null;
  const auth = (authHeader || '').replace(/^Bearer\s+/i, '');
  if (auth !== config.clientToken) return { status: 401, json: { error: 'no autorizado' } };
  return null;
}

export interface TeachNote {
  app?: string;
  note?: string;
}

export interface SaveNotesBody {
  userId?: string;
  notes?: TeachNote[];
}

export async function handleSaveNotes(body: SaveNotesBody, authHeader?: string): Promise<HttpResult> {
  const authError = checkAuth(authHeader);
  if (authError) return authError;

  const userId = body.userId?.trim() || 'anon';
  const notes = Array.isArray(body.notes) ? body.notes : [];
  if (notes.length === 0) {
    return { status: 400, json: { error: 'falta `notes` (no vacío)' } };
  }

  const { memory } = deps();
  let saved = 0;
  for (const n of notes) {
    const note = n.note?.trim();
    if (!note) continue;
    await memory.remember(userId, n.app?.trim() ?? '', note);
    saved++;
  }

  return { status: 200, json: { ok: true, saved } };
}
