// Enseñanza activa por video: un médico graba su pantalla usando el sistema del hospital y narra en
// voz alta lo que hace; Gemini mira el video y extrae CONOCIMIENTO REUTILIZABLE sobre cómo se opera
// el sistema — no datos clínicos de un caso concreto (ver la regla de privacidad del prompt).
//
// REPARTO DE TRABAJO CON EL CLIENTE (y por qué):
//   El mp4 NO puede pasar por una función de Vercel: el límite de payload es 4.5 MB. Pero la key de
//   Gemini tampoco debe vivir en el cliente (se distribuye en el .exe y es extraíble). El protocolo
//   de subida "resumable" de Google resuelve justo esto:
//     1. `startUpload` — el backend reserva el archivo CON la key. Request chico, cabe de sobra.
//     2. El cliente sube los bytes directo a Google usando la URL devuelta, que trae su propio token
//        embebido y NO necesita la key.
//     3. `fileState` — el cliente pregunta al backend si el video ya quedó ACTIVE. Llamadas cortas.
//     4. `processVideo` — el backend hace el generateContent CON la key.
//   Resultado: el video nunca toca Vercel y la key nunca toca el cliente.

import { config } from '../config';

const BASE = 'https://generativelanguage.googleapis.com';

/** Paso 1 del resumable upload: reserva el archivo en Gemini y devuelve la URL de subida. */
export async function startUpload(displayName: string, contentLength: number): Promise<string> {
  const res = await fetch(`${BASE}/upload/v1beta/files`, {
    method: 'POST',
    headers: {
      'x-goog-api-key': config.geminiApiKey,
      'X-Goog-Upload-Protocol': 'resumable',
      'X-Goog-Upload-Command': 'start',
      'X-Goog-Upload-Header-Content-Length': String(contentLength),
      'X-Goog-Upload-Header-Content-Type': 'video/mp4',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ file: { display_name: displayName } }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`upload start HTTP ${res.status}: ${body.slice(0, 200)}`);
  }
  const uploadUrl = res.headers.get('x-goog-upload-url');
  if (!uploadUrl) throw new Error('Gemini no devolvió X-Goog-Upload-URL');
  return uploadUrl;
}

/** Estado del archivo subido: PROCESSING | ACTIVE | FAILED. El cliente consulta esto en bucle. */
export async function fileState(fileUri: string): Promise<string> {
  // fileUri viene como https://generativelanguage.googleapis.com/v1beta/files/abc123
  const name = fileUri.replace(/^.*\/(v1beta\/files\/)/, '$1');
  const res = await fetch(`${BASE}/${name}`, {
    headers: { 'x-goog-api-key': config.geminiApiKey },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`files.get HTTP ${res.status}: ${body.slice(0, 200)}`);
  }
  const body = (await res.json()) as { state?: string };
  return body.state ?? 'UNKNOWN';
}

export interface MemoryNote {
  app: string;
  note: string;
}

export interface VideoTeachResult {
  summary: string;
  notes: MemoryNote[];
  questions: string[];
}

const MEDICAL_TEACH_PROMPT = `
Eres Ü, un asistente que ayudará a operar el sistema informático de un hospital (HIS/EHR u otro
software clínico). Un MÉDICO acaba de grabar su pantalla mientras USA ese sistema, narrando en voz
alta lo que hace — te está ENSEÑANDO cómo se opera, para que después tú puedas ayudar a otros
usuarios con las mismas tareas.

Mira TODO el video (imagen + audio) y extrae CONOCIMIENTO SOBRE EL SISTEMA, organizado POR
APLICACIÓN/MÓDULO. Buscamos hechos operativos reutilizables, NO datos de un caso concreto. Ejemplos
del tipo de nota que sí sirve:
- "Para admitir un paciente se usa el botón 'Nuevo ingreso' en la pantalla principal, no el menú
  'Pacientes'."
- "El campo 'Diagnóstico principal' solo acepta códigos CIE-10; hay un buscador si se escribe texto."
- "Las órdenes de laboratorio se firman digitalmente desde la pestaña 'Pendientes', abajo a la
  derecha."

REGLA DE PRIVACIDAD, ABSOLUTA Y SIN EXCEPCIÓN:
NUNCA registres en una nota ningún dato que identifique o describa a una persona concreta: nombres
de pacientes, números de historia clínica o documento, fechas de nacimiento, diagnósticos
específicos de un caso, resultados de laboratorio, medicaciones recetadas, o cualquier dato clínico
ligado a un caso real que aparezca en pantalla durante la demostración. Si un ejemplo en el video
usa datos de un paciente (real o de prueba), IGNORA esos datos por completo y quédate solo con EL
PROCEDIMIENTO — cómo se navega, qué botón se pulsa, qué significa cada campo, en qué orden se hace
algo. Ante cualquier duda de si un dato es identificable, OMÍTELO.

REGLAS ESTRICTAS (calidad sobre cantidad):
- Cada nota: UNA frase, auto-contenida, sobre CÓMO FUNCIONA o CÓMO SE USA el sistema.
- Incluye SOLO lo que entiendas con certeza muy alta y tenga valor real para operar el sistema
  después. Ante la duda, fuera. No inventes procedimientos que no viste.
- "app": el nombre visible del sistema o módulo al que aplica la nota (p.ej. "HIS - Admisiones",
  "Laboratorio"). Si la nota es general y no pertenece a un módulo concreto, usa "".
- Si algo importante quedó ambiguo y conviene confirmarlo con el médico, agrégalo en "questions"
  (pregunta corta y natural). Máximo 3. Si no hace falta preguntar nada, deja la lista vacía.
- Si el video no contiene nada confiable que guardar (o todo lo mostrado es dato de paciente sin
  procedimiento reutilizable), devuelve items y questions vacíos.

Además, escribe un "summary": un resumen CORTO (1-3 frases), en primera persona y en tono
profesional, de lo que ENTENDISTE sobre cómo se usa el sistema — para mostrárselo al médico. Si no
aprendiste nada útil (o todo era dato clínico que debiste descartar), dilo con naturalidad.

Responde SOLO JSON:
{"summary": "...", "items": [{"app": "HIS - Admisiones", "note": "..."}], "questions": ["..."]}
`.trim();

/** El video ya está ACTIVE: pídele a Gemini el conocimiento del sistema. */
export async function processVideo(fileUri: string, model: string): Promise<VideoTeachResult> {
  const req = {
    contents: [
      {
        role: 'user',
        parts: [{ fileData: { mimeType: 'video/mp4', fileUri } }, { text: MEDICAL_TEACH_PROMPT }],
      },
    ],
    generationConfig: { responseMimeType: 'application/json' },
  };

  const res = await fetch(`${BASE}/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': config.geminiApiKey },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`generateContent HTTP ${res.status}: ${body.slice(0, 200)}`);
  }

  const body = (await res.json()) as {
    candidates?: { content?: { parts?: { text?: string }[] } }[];
  };
  const text = body.candidates?.[0]?.content?.parts?.find((p) => typeof p.text === 'string')?.text;
  if (!text) throw new Error('Gemini no devolvió texto en la respuesta');

  const parsed = firstJsonObject(text);
  const notes: MemoryNote[] = Array.isArray(parsed.items)
    ? (parsed.items as { app?: string; note?: string }[])
        .map((it) => ({ app: (it.app ?? '').trim(), note: (it.note ?? '').trim() }))
        .filter((n) => n.note.length > 0)
    : [];
  const questions: string[] = Array.isArray(parsed.questions)
    ? (parsed.questions as unknown[]).map((q) => String(q).trim()).filter((q) => q.length > 0)
    : [];
  const summary = typeof parsed.summary === 'string' ? parsed.summary.trim() : '';

  return { summary, notes, questions };
}

/** Tolera fences de markdown o texto extra alrededor del JSON (igual que la versión Android). */
function firstJsonObject(text: string): Record<string, unknown> {
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start === -1 || end === -1 || end < start) throw new Error('respuesta sin JSON reconocible');
  return JSON.parse(text.slice(start, end + 1));
}
