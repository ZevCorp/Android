// Tipos compartidos por todos los providers del cerebro (evita ciclos de import).
import { BrainTurn, ScreenState } from '../domain/actions';
import { McpTool } from '../domain/mcp';
import { SessionState } from '../domain/session';

export interface TurnInput {
  session: SessionState;
  tools: McpTool[];
  mcpNames: Set<string>;
  memory: string;
  apps: string[];
  state: ScreenState;
  results: string[];
  apiKey: string;
}

export interface TurnOutput {
  session: SessionState;
  turn: BrainTurn;
}
