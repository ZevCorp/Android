// POST /api/teach/upload-token — firma la subida del mp4 a Gemini y al archivo (Supabase Storage),
// y devuelve las dos URLs. El cliente sube los bytes directo a cada destino: el video nunca pasa por
// esta función (límite de 4.5 MB) y ninguna key sale del servidor.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { handleUploadToken, UploadTokenBody } from '../../src/http/handleTeach';

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'usa POST' });
    return;
  }
  const result = await handleUploadToken((req.body || {}) as UploadTokenBody, req.headers.authorization);
  res.status(result.status).json(result.json);
}
