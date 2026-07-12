// Servidor local de desarrollo — corre el backend SIN la CLI de Vercel, para probar en local.
//   npx tsx local-server.mts     (lee .env.local)
// Enruta las mismas funciones que las Vercel Functions (src/http/handleTurn.ts).

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// --- Carga .env.local (parser mínimo; sin dependencias) ---
const dir = path.dirname(fileURLToPath(import.meta.url));
for (const file of ['.env.local', '.env']) {
  const p = path.join(dir, file);
  if (!fs.existsSync(p)) continue;
  for (const line of fs.readFileSync(p, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/i);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}

const { handleTurn } = await import('./src/http/handleTurn');
const { activeKey, activeModel, config } = await import('./src/config');

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => resolve(data));
  });
}

const server = http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json');
  const url = req.url || '';

  if (url.startsWith('/api/health')) {
    res.end(JSON.stringify({
      ok: true, service: 'u-windows-backend', provider: config.provider,
      model: activeModel(), configured: Boolean(activeKey()), authRequired: Boolean(config.clientToken),
    }));
    return;
  }

  if (url.startsWith('/api/agent/turn') && req.method === 'POST') {
    try {
      const body = JSON.parse((await readBody(req)) || '{}');
      const result = await handleTurn(body, req.headers.authorization as string | undefined);
      res.statusCode = result.status;
      res.end(JSON.stringify(result.json));
    } catch (e) {
      res.statusCode = 500;
      res.end(JSON.stringify({ error: (e as Error).message }));
    }
    return;
  }

  res.statusCode = 404;
  res.end(JSON.stringify({ error: 'not found' }));
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => {
  console.log(`Ü backend local → http://localhost:${port}  (provider=${config.provider}, model=${activeModel()}, key=${activeKey() ? 'ok' : 'FALTA'})`);
});
