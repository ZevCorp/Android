// POST /api/agent/turn — el ÚNICO endpoint del bucle de ejecución (Vercel Function).
//
// El cliente Windows lo llama una vez por turno:
//  - Primer turn: manda { goal, state }  (sin `session`).
//  - Siguientes:  manda { session, state, results, inform? }  (echa el blob opaco del turno anterior).
// Devuelve { session, ...BrainTurn }. La lógica vive en src/http/handleTurn.ts (compartida con el
// servidor local de desarrollo). El cliente nunca ve prompt, catálogo MCP, memoria ni la key.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { handleTurn, TurnBody } from '../../src/http/handleTurn';

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'usa POST' });
    return;
  }
  const result = await handleTurn((req.body || {}) as TurnBody, req.headers.authorization);
  res.status(result.status).json(result.json);
}
