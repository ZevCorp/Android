// POST /api/teach/file-state — ¿el video que el cliente subió ya salió de PROCESSING en Gemini?
// Existe como endpoint aparte para que el cliente haga el bucle de espera: así cada función es
// corta y no dependemos del límite de duración de Vercel para esperar a Google.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { handleFileState, FileStateBody } from '../../src/http/handleTeach';

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'usa POST' });
    return;
  }
  const result = await handleFileState((req.body || {}) as FileStateBody, req.headers.authorization);
  res.status(result.status).json(result.json);
}
