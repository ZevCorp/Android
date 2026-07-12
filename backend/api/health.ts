// GET /api/health — sonda de salud + reporte de configuración (sin filtrar secretos).
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { config } from '../src/config';

export default function handler(_req: VercelRequest, res: VercelResponse): void {
  res.status(200).json({
    ok: true,
    service: 'u-windows-backend',
    configured: Boolean(config.openAiApiKey),
    model: config.model,
    effort: config.effort,
    authRequired: Boolean(config.clientToken),
  });
}
