// POST /api/teach/process-video — el video ya está ACTIVE en Gemini: se procesa con el prompt
// médico (un médico enseñando a usar el sistema del hospital) y el conocimiento extraído se guarda
// en el MemoryStore del usuario. Es la única llamada larga del flujo (generateContent sobre un
// video): ver `maxDuration` en vercel.json.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { handleProcessVideo, ProcessVideoBody } from '../../src/http/handleTeach';

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'usa POST' });
    return;
  }
  const result = await handleProcessVideo((req.body || {}) as ProcessVideoBody, req.headers.authorization);
  res.status(result.status).json(result.json);
}
