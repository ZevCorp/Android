// POST /api/agent/turn — el ÚNICO endpoint del bucle de ejecución.
//
// El cliente Windows lo llama una vez por turno:
//  - Primer turn: manda { goal, state }  (sin `session`).
//  - Siguientes:  manda { session, state, results }  (echa el blob opaco del turno anterior).
// Devuelve { session, ...BrainTurn }: la sesión actualizada a reenviar + las acciones a ejecutar.
//
// El cliente nunca ve el prompt, el catálogo MCP, la memoria ni la key de OpenAI: todo eso se
// resuelve aquí dentro y solo sale el `Action[]`.

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { assertConfigured, config } from '../../src/config';
import { decodeSession, encodeSession, freshSession } from '../../src/domain/session';
import { ScreenState } from '../../src/domain/actions';
import { resolveTurn } from '../../src/application/engine';
import { deps } from '../../src/container';

interface TurnBody {
  session?: string;
  goal?: string;
  userId?: string;
  state?: ScreenState;
  results?: string[];
  /** Respuesta del usuario a un ask_user pendiente; se enruta a session.informText. */
  inform?: string;
}

function unauthorized(res: VercelResponse) {
  res.status(401).json({ error: 'no autorizado' });
}

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'usa POST' });
    return;
  }

  // Autenticación opcional del cliente (Bearer). En dev, si CLIENT_TOKEN está vacío, se omite.
  if (config.clientToken) {
    const auth = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
    if (auth !== config.clientToken) return unauthorized(res);
  }

  try {
    assertConfigured();
  } catch (e) {
    res.status(500).json({ error: (e as Error).message });
    return;
  }

  const body = (req.body || {}) as TurnBody;
  if (!body.state || typeof body.state.screen !== 'string') {
    res.status(400).json({ error: 'falta `state` (screen, uiContext, width, height)' });
    return;
  }

  const userId = body.userId?.trim() || 'anon';

  // Reconstruye o inicia la sesión del cerebro.
  let session;
  try {
    session = body.session
      ? decodeSession(body.session)
      : freshSession(body.goal?.trim() || '', config.model, config.effort);
  } catch (e) {
    res.status(400).json({ error: `sesión inválida: ${(e as Error).message}` });
    return;
  }
  if (!body.session && !session.goal) {
    res.status(400).json({ error: 'el primer turno requiere `goal`' });
    return;
  }

  // La respuesta del usuario a una pregunta pendiente entra al hilo como informText.
  if (typeof body.inform === 'string') session.informText = body.inform;

  try {
    const { session: next, turn } = await resolveTurn(
      { userId, session, state: body.state, results: body.results ?? [] },
      deps(),
    );
    res.status(200).json({ session: encodeSession(next), ...turn });
  } catch (e) {
    res.status(502).json({ error: `cerebro: ${(e as Error).message}` });
  }
}
