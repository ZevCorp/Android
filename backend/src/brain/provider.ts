// Sistema de PROVIDERS del cerebro. El backend habla con distintos modelos con computer-use
// (OpenAI, Gemini) tras una interfaz común. El cliente Windows no cambia ni se entera: sigue
// recibiendo el mismo `Action[]`. Cambiar de proveedor es una variable de entorno (PROVIDER).
//
// Cada provider recibe el mismo input y devuelve {session, turn}. La forma del hilo (previous_id de
// OpenAI vs historial acarreado de Gemini) vive dentro de la SessionState y la maneja cada adaptador.

import { runBrainTurn } from './openai';
import { runGeminiTurn } from './gemini';
import { TurnInput, TurnOutput } from './types';

export type { TurnInput, TurnOutput } from './types';

/** Despacha al adaptador del proveedor indicado en la sesión. */
export function runProviderTurn(inp: TurnInput): Promise<TurnOutput> {
  switch (inp.session.provider) {
    case 'gemini':
      return runGeminiTurn(inp);
    case 'openai':
    default:
      return runBrainTurn(inp);
  }
}
