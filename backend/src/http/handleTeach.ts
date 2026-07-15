// Endpoints de la enseñanza por video, independientes del runtime (Vercel o servidor local), igual
// que handleTurn.ts. Ver geminiVideo.ts para el porqué del reparto de trabajo con el cliente: en
// resumen, el mp4 nunca pasa por Vercel (límite de 4.5 MB) y las keys nunca llegan al cliente.
//
//   POST /api/teach/upload-token  → URLs firmadas (Gemini + archivo en Supabase). Rápido.
//   POST /api/teach/file-state    → ¿el video ya está ACTIVE en Gemini? El cliente consulta en bucle.
//   POST /api/teach/process-video → generateContent con el prompt médico + guarda las notas.

import { activeModel, assertConfigured, config } from '../config';
import { fileState, processVideo, startUpload } from '../teach/geminiVideo';
import { signVideoUpload } from '../teach/videoStorage';
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

/** Auth + configuración del cerebro. Devuelve el error listo para responder, o null si todo bien. */
function guard(authHeader?: string): HttpResult | null {
  const authError = checkAuth(authHeader);
  if (authError) return authError;
  try {
    assertConfigured();
  } catch (e) {
    return { status: 500, json: { error: (e as Error).message } };
  }
  return null;
}

// ── POST /api/teach/upload-token ────────────────────────────────────────────

export interface UploadTokenBody {
  contentLength?: number;
  userId?: string;
}

/**
 * Reserva el archivo en Gemini y firma la subida al archivo de Supabase. Devuelve las dos URLs; el
 * cliente sube el mismo mp4 a ambas. Ninguna key sale de aquí.
 */
export async function handleUploadToken(body: UploadTokenBody, authHeader?: string): Promise<HttpResult> {
  const bad = guard(authHeader);
  if (bad) return bad;

  const contentLength = Number(body.contentLength) || 0;
  if (contentLength <= 0) {
    return { status: 400, json: { error: 'falta `contentLength` (bytes del mp4 a subir)' } };
  }
  const userId = body.userId?.trim() || 'anon';

  try {
    const geminiUploadUrl = await startUpload('graph_teach', contentLength);

    // Archivar es deseable, no imprescindible: si Supabase no está configurado o falla, la enseñanza
    // sigue. Se avisa con `archiveError` en vez de tumbar la petición entera.
    let archive: { uploadUrl: string; path: string } | null = null;
    let archiveError: string | null = null;
    try {
      archive = await signVideoUpload(userId, new Date().toISOString());
    } catch (e) {
      archiveError = (e as Error).message;
    }

    return {
      status: 200,
      json: {
        geminiUploadUrl,
        archiveUploadUrl: archive?.uploadUrl ?? null,
        archivePath: archive?.path ?? null,
        archiveError,
      },
    };
  } catch (e) {
    return { status: 502, json: { error: `Gemini: ${(e as Error).message}` } };
  }
}

// ── POST /api/teach/file-state ──────────────────────────────────────────────

export interface FileStateBody {
  fileUri?: string;
}

/** ¿El video ya salió de PROCESSING? Llamada corta a propósito: el cliente la repite en bucle. */
export async function handleFileState(body: FileStateBody, authHeader?: string): Promise<HttpResult> {
  const bad = guard(authHeader);
  if (bad) return bad;

  const fileUri = body.fileUri?.trim();
  if (!fileUri) return { status: 400, json: { error: 'falta `fileUri`' } };

  try {
    return { status: 200, json: { state: await fileState(fileUri) } };
  } catch (e) {
    return { status: 502, json: { error: `Gemini: ${(e as Error).message}` } };
  }
}

// ── POST /api/teach/process-video ───────────────────────────────────────────

export interface ProcessVideoBody {
  fileUri?: string;
  userId?: string;
}

/**
 * El video ya está ACTIVE: se le pide a Gemini el conocimiento del sistema (prompt médico) y se
 * guardan las notas en el MemoryStore del usuario — el mismo store que el bucle de ejecución ya usa
 * para inyectar contexto en cada turno.
 */
export async function handleProcessVideo(body: ProcessVideoBody, authHeader?: string): Promise<HttpResult> {
  const bad = guard(authHeader);
  if (bad) return bad;

  const fileUri = body.fileUri?.trim();
  if (!fileUri) return { status: 400, json: { error: 'falta `fileUri`' } };
  const userId = body.userId?.trim() || 'anon';

  try {
    const result = await processVideo(fileUri, activeModel());

    const { memory } = deps();
    for (const note of result.notes) await memory.remember(userId, note.app, note.note);

    return {
      status: 200,
      json: { summary: result.summary, notes: result.notes, questions: result.questions },
    };
  } catch (e) {
    return { status: 502, json: { error: `Gemini: ${(e as Error).message}` } };
  }
}
