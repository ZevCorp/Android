import Foundation

/// Canal de voz/narración hacia el usuario (lo implementa la UI).
protocol VoiceChannel: AnyObject {
    func narrate(_ text: String)
    func speak(_ text: String)
}

/// El asistente puede preguntar algo al usuario (respuesta por texto o voz).
protocol UserChannel: AnyObject {
    func ask(_ question: String) async -> String
}

/// El bucle de ejecución del lado cliente. Gemelo de `core/application/Engine.kt` y del `AgentLoop`
/// de Windows: donde aquel llamaba a un `Brain` local, este hace `POST /api/agent/turn` al backend.
/// El cliente CONDUCE el bucle (capturar → pedir decisión → ejecutar → repetir); el cerebro remoto
/// solo decide. Toda la inteligencia está del otro lado del cable.
final class AgentLoop {
    private let backend: BackendClient
    private let ax: AxReader
    private let mcp: LocalMcp
    private weak var voice: VoiceChannel?
    private weak var user: UserChannel?
    private let installedApps: () -> [String]
    private let maxTurns: Int

    private var cancelled = false

    init(backend: BackendClient, ax: AxReader, mcp: LocalMcp, voice: VoiceChannel, user: UserChannel,
         installedApps: @escaping () -> [String], maxTurns: Int = 40) {
        self.backend = backend
        self.ax = ax
        self.mcp = mcp
        self.voice = voice
        self.user = user
        self.installedApps = installedApps
        self.maxTurns = maxTurns
    }

    func cancel() { cancelled = true }

    /// Ejecuta un objetivo hasta que el cerebro devuelve el control con texto. Devuelve ese resumen.
    func run(goal: String) async -> String {
        cancelled = false
        voice?.narrate("¡Vamos! \(goal)")
        var session: String?
        var results: [String] = []
        var inform: String?
        var summary = ""
        var wantShot = false
        var actionCount = 0

        for _ in 0..<maxTurns {
            if cancelled { break }

            // 1) Capturar el estado (texto por AX; screenshot solo si el cerebro lo pidió).
            var state = ax.read()
            state.apps = installedApps()
            if wantShot { state.screenshot = Screenshotter.captureBase64Png() }

            // 2) Pedir la decisión al backend.
            let req = TurnRequest(session: session, goal: session == nil ? goal : nil,
                                  userId: nil, state: state, results: results, inform: inform)
            inform = nil
            let resp: TurnResponse
            do {
                resp = try await backend.turn(req)
            } catch {
                voice?.speak("No pude contactar con el cerebro. Revisa la conexión.")
                return "error de backend: \(error.localizedDescription)"
            }

            session = resp.session
            wantShot = resp.needsScreenshot ?? false
            if let n = resp.narration, !n.isEmpty { voice?.narrate(n) }
            if let s = resp.speech, !s.isEmpty { voice?.speak(s) }
            if let t = resp.text, !t.isEmpty { summary = t }
            if resp.done == true { break }

            // 3) Ejecutar las acciones decididas por el cerebro.
            let actions = resp.actions ?? []
            let intents = resp.intents ?? []
            var out: [String] = []
            for (i, action) in actions.enumerated() {
                if cancelled { break }
                if i < intents.count, !intents[i].isEmpty { voice?.narrate(intents[i]) }
                out.append(execute(action))
                actionCount += 1
                if actions.count > 1 { try? await Task.sleep(nanoseconds: 350_000_000) }
            }
            results = out

            // 4) Si preguntó algo, resolverlo antes del próximo turno (la respuesta viaja en `inform`).
            if let q = resp.question, !q.isEmpty {
                voice?.speak(q)
                let answer = await user?.ask(q) ?? ""
                inform = answer.isEmpty ? "usa tu mejor criterio" : answer
            }

            try? await Task.sleep(nanoseconds: 300_000_000)
        }

        if !summary.isEmpty { voice?.speak(summary) }
        else if actionCount == 0 {
            summary = "Mmm, no estoy seguro de haberte entendido. ¿Me lo dices de otra forma?"
            voice?.speak(summary)
        }
        voice?.narrate("¡Listo! 🎉")
        return summary.isEmpty ? "Hecho" : summary
    }

    private func execute(_ a: AgentAction) -> String {
        func ok(_ b: Bool) -> String { b ? "ok" : "no se pudo ejecutar la acción" }
        switch a.kind {
        case "tap": return ok(InputExecutor.tap(x: a.x ?? 0, y: a.y ?? 0))
        case "type": return ok(InputExecutor.type(x: a.x ?? 0, y: a.y ?? 0, text: a.text ?? ""))
        case "scroll": return ok(InputExecutor.scroll(down: a.down ?? true))
        case "swipe": return ok(InputExecutor.swipe(x1: a.x1 ?? 0, y1: a.y1 ?? 0, x2: a.x2 ?? 0, y2: a.y2 ?? 0, ms: a.ms ?? 400))
        case "key": return ok(InputExecutor.key(a.key ?? ""))
        case "wait":
            usleep(useconds_t(min(max(a.ms ?? 0, 0), 10000) * 1000))
            return "ok"
        case "mcp": return mcp.call(tool: a.tool ?? "", args: a.args ?? [:])
        default: return "acción desconocida: \(a.kind)"
        }
    }
}
