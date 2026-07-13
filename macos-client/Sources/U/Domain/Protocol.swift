import Foundation

// Tipos del contrato con el backend. Espejo EXACTO de backend/src/domain/actions.ts y de
// windows-client/src/Domain/Protocol.cs. El cliente solo conoce esto del cerebro; nada más cruza.

/// Estado de pantalla que el cliente captura y envía cada turno.
struct ScreenState: Codable {
    var screen: String
    var uiContext: String
    var width: Int
    var height: Int
    /// PNG en base64 SIN prefijo data-uri. Solo cuando el turno anterior pidió computer-use.
    var screenshot: String?
    /// Apps instaladas conocidas (resuelve list_apps y alimenta el prompt del cerebro).
    var apps: [String]?
}

/// Petición a POST /api/agent/turn.
struct TurnRequest: Codable {
    /// Blob opaco del turno anterior. Nil en el primer turno.
    var session: String?
    /// Objetivo del usuario. Solo en el primer turno.
    var goal: String?
    var userId: String?
    var state: ScreenState
    /// Resultados de las acciones del turno anterior (mismo orden).
    var results: [String]
    /// Respuesta del usuario a un ask_user del turno anterior. Nil si no hubo.
    var inform: String?
}

/// Una acción decidida por el cerebro que el cliente ejecuta localmente. Unión discriminada por `kind`.
struct AgentAction: Codable {
    var kind: String
    var x: Int?
    var y: Int?
    var x1: Int?
    var y1: Int?
    var x2: Int?
    var y2: Int?
    var ms: Int?
    var text: String?
    var key: String?
    var down: Bool?
    var tool: String?
    var args: [String: String]?
}

/// Respuesta de POST /api/agent/turn: BrainTurn + la sesión opaca actualizada.
struct TurnResponse: Codable {
    var session: String?
    var actions: [AgentAction]?
    var question: String?
    var done: Bool?
    var text: String?
    var needsScreenshot: Bool?
    var narration: String?
    var speech: String?
    var intents: [String]?
    var error: String?
}
