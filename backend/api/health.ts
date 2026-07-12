// GET /api/health — sonda de salud + reporte de configuración (sin filtrar secretos).
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { activeKey, activeModel, config } from '../src/config';

export default function handler(_req: VercelRequest, res: VercelResponse): void {
  res.status(200).json({
    ok: true,
    service: 'u-windows-backend',
    provider: config.provider,
    model: activeModel(),
    configured: Boolean(activeKey()),
    effort: config.effort,
    authRequired: Boolean(config.clientToken),
  });
}
