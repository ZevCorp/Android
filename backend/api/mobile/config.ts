// GET /api/mobile/config — reparto de keys para el cliente Android (ver src/http/handleMobileConfig.ts).
// Requiere Authorization: Bearer <ANDROID_CLIENT_TOKEN>. No participa del bucle de ejecución de
// /api/agent/turn (ese sigue siendo exclusivo del cliente Windows) — rutas totalmente independientes.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { handleMobileConfig } from '../../src/http/handleMobileConfig';

export default function handler(req: VercelRequest, res: VercelResponse): void {
  if (req.method !== 'GET') {
    res.status(405).json({ error: 'usa GET' });
    return;
  }
  const result = handleMobileConfig(req.headers.authorization);
  res.status(result.status).json(result.json);
}
