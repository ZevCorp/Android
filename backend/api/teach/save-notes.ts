// POST /api/teach/save-notes — el cliente ya proceso el video de enseñanza hablando directo con
// Gemini (ver comentario en src/http/handleTeach.ts); aquí solo llegan las notas ya extraídas
// (texto pequeño), que se guardan en el MemoryStore del usuario.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { handleSaveNotes, SaveNotesBody } from '../../src/http/handleTeach';

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'usa POST' });
    return;
  }
  const result = await handleSaveNotes((req.body || {}) as SaveNotesBody, req.headers.authorization);
  res.status(result.status).json(result.json);
}
