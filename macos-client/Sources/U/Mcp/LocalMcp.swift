import Foundation

/// Registro `nombre → ejecutor local`. El cerebro (backend) DECLARA el catálogo MCP y decide QUÉ
/// herramienta llamar; este registro solo sabe CÓMO ejecutarla en macOS. No conoce descripciones,
/// esquemas ni prompts — solo el mapeo. Ese es el corte de la separación. Gemelo de `LocalMcp` (C#).
final class LocalMcp {
    private let ax: AxReader
    init(ax: AxReader) { self.ax = ax }

    private static let known: Set<String> = [
        "go_home", "open_app_drawer", "open_notifications", "switch_window", "scroll_menu",
        "launch_app", "set_alarm", "set_timer", "create_event", "dial", "send_sms", "send_email",
        "web_search", "open_url", "open_maps", "directions", "open_camera", "open_settings",
        "share_text", "set_clipboard", "set_volume", "adjust_volume",
    ]

    /// Ejecuta una llamada MCP y devuelve un resultado legible para el modelo ("ok" / detalle).
    func call(tool: String, args: [String: String]) -> String {
        func A(_ k: String) -> String { (args[k] ?? "").trimmingCharacters(in: .whitespaces) }
        func I(_ k: String, _ def: Int) -> Int { Int(A(k)) ?? def }

        // Herramienta aprendida: llega con `taps` y no es del catálogo base. Devuelve su propio detalle.
        if !Self.known.contains(tool), args["taps"] != nil {
            return runLearnedTaps(taps: A("taps"))
        }

        let ok: Bool
        switch tool {
        // Gestos de macOS
        case "go_home": ok = Gestures.showDesktop()
        case "open_app_drawer": ok = Gestures.spotlight()
        case "open_notifications": ok = Gestures.missionControl()
        case "switch_window": ok = Gestures.switchWindow(next: A("direction") != "previous")
        case "scroll_menu": ok = InputExecutor.scroll(down: A("direction") != "up")
        // Acciones de sistema
        case "launch_app": ok = MacSystemApi.launchApp(A("app"))
        case "set_alarm": ok = MacSystemApi.setAlarm(I("hour", 8), I("minute", 0), A("message"))
        case "set_timer": ok = MacSystemApi.setTimer(I("seconds", 60), A("message"))
        case "create_event": ok = MacSystemApi.createEvent(A("title"), A("start"), A("location"))
        case "dial": ok = MacSystemApi.dial(A("number"))
        case "send_sms": ok = MacSystemApi.sendSms(A("number"), A("message"))
        case "send_email": ok = MacSystemApi.sendEmail(A("to"), A("subject"), A("body"))
        case "web_search": ok = MacSystemApi.webSearch(A("query"))
        case "open_url": ok = MacSystemApi.openUrl(A("url"))
        case "open_maps": ok = MacSystemApi.openMaps(A("query"))
        case "directions": ok = MacSystemApi.directions(A("destination"))
        case "open_camera": ok = MacSystemApi.openCamera()
        case "open_settings": ok = MacSystemApi.openSettings(A("section"))
        case "share_text": ok = MacSystemApi.shareText(A("text"))
        case "set_clipboard": ok = MacSystemApi.setClipboard(A("text"))
        case "set_volume": ok = MacSystemApi.setVolume(I("percent", 100))
        case "adjust_volume": ok = MacSystemApi.adjustVolume(A("direction"))
        default:
            return "herramienta no soportada por este cliente: \(tool)"
        }
        return ok ? "ok" : "la herramienta no se pudo ejecutar"
    }

    private func runLearnedTaps(taps: String) -> String {
        let labels = taps.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var failed: [String] = []
        for label in labels {
            var done = ax.press(label: label)
            if !done, let pt = ax.tapTarget(forLabel: label) {
                done = InputExecutor.tap(x: Int(pt.x), y: Int(pt.y))
            }
            if !done { failed.append(label) }
            usleep(350_000)
        }
        if !failed.isEmpty { return "la herramienta no se pudo ejecutar — pasos fallidos: \(failed.joined(separator: ", ")) (de \(labels.count))" }
        return labels.isEmpty ? "la herramienta no se pudo ejecutar — llamada sin taps" : "ok"
    }
}
