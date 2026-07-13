import AppKit

/// La carita flotante: el frontend completo. Recoge lo que el usuario pide (texto), lanza el
/// `AgentLoop` (que consulta al cerebro remoto) y muestra narración/estado. Implementa
/// `VoiceChannel` y `UserChannel` para que el bucle hable y pregunte por esta UI. Cero decisiones.
/// Gemelo de `FaceWindow.xaml.cs` (Windows).
final class FaceWindow: NSObject, VoiceChannel, UserChannel, NSTextFieldDelegate {
    private var config = Config.load()
    private let ax = AxReader()
    private let voiceIO = VoiceIO()
    private var loop: AgentLoop!
    private var currentTask: Task<Void, Never>?

    private var panel: NSPanel!
    private let statusLabel = NSTextField(labelWithString: "Ü listo")
    private let bubble = NSTextField(labelWithString: "")
    private let input = NSTextField()
    private let backendField = NSTextField()
    private let stopButton = NSButton()

    // Para resolver preguntas del asistente desde la caja de texto.
    private var pendingAnswer: CheckedContinuation<String, Never>?

    func showFloatingFace() {
        let rect = NSRect(x: 0, y: 0, width: 320, height: 200)
        panel = NSPanel(contentRect: rect,
                        styleMask: [.nonactivatingPanel, .titled, .fullSizeContentView],
                        backing: .buffered, defer: false)
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.backgroundColor = NSColor(calibratedWhite: 0.06, alpha: 0.95)
        panel.hasShadow = true
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true

        buildViews()
        // Esquina inferior derecha.
        if let screen = NSScreen.main {
            let vf = screen.visibleFrame
            panel.setFrameOrigin(NSPoint(x: vf.maxX - rect.width - 24, y: vf.minY + 24))
        }
        panel.makeKeyAndOrderFront(nil)

        let mcp = LocalMcp(ax: ax)
        loop = AgentLoop(backend: BackendClient(config: config), ax: ax, mcp: mcp,
                         voice: self, user: self, installedApps: InstalledApps.list)
    }

    private func buildViews() {
        guard let content = panel.contentView else { return }
        let face = NSView(frame: NSRect(x: 16, y: 148, width: 40, height: 40))
        face.wantsLayer = true
        face.layer?.cornerRadius = 20
        face.layer?.backgroundColor = NSColor(calibratedRed: 0.72, green: 0.78, blue: 1.0, alpha: 1).cgColor

        statusLabel.frame = NSRect(x: 66, y: 150, width: 238, height: 36)
        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 13)
        statusLabel.maximumNumberOfLines = 2

        bubble.frame = NSRect(x: 16, y: 96, width: 288, height: 44)
        bubble.textColor = NSColor(white: 1, alpha: 0.8)
        bubble.font = .systemFont(ofSize: 12)
        bubble.maximumNumberOfLines = 3

        input.frame = NSRect(x: 16, y: 56, width: 230, height: 28)
        input.placeholderString = "Pídeme algo…"
        input.target = self
        input.action = #selector(onSubmit)
        input.delegate = self

        stopButton.frame = NSRect(x: 254, y: 56, width: 50, height: 28)
        stopButton.title = "⏹"
        stopButton.bezelStyle = .rounded
        stopButton.target = self
        stopButton.action = #selector(onStop)
        stopButton.isHidden = true

        backendField.frame = NSRect(x: 16, y: 16, width: 200, height: 26)
        backendField.stringValue = config.backendUrl
        backendField.font = .systemFont(ofSize: 11)
        let saveBtn = NSButton(frame: NSRect(x: 224, y: 16, width: 80, height: 26))
        saveBtn.title = "Backend"
        saveBtn.bezelStyle = .rounded
        saveBtn.target = self
        saveBtn.action = #selector(onSaveConfig)

        [face, statusLabel, bubble, input, stopButton, backendField, saveBtn].forEach { content.addSubview($0) }
    }

    // MARK: - acciones de UI

    @objc private func onSubmit() {
        let text = input.stringValue.trimmingCharacters(in: .whitespaces)
        input.stringValue = ""
        guard !text.isEmpty else { return }
        if let cont = pendingAnswer { pendingAnswer = nil; cont.resume(returning: text); return }
        startGoal(text)
    }

    @objc private func onStop() {
        currentTask?.cancel()
        loop.cancel()
        if let cont = pendingAnswer { pendingAnswer = nil; cont.resume(returning: "") }
        setStatus("Detenido")
        stopButton.isHidden = true
    }

    @objc private func onSaveConfig() {
        config.backendUrl = backendField.stringValue.trimmingCharacters(in: .whitespaces)
        config.save()
        let mcp = LocalMcp(ax: ax)
        loop = AgentLoop(backend: BackendClient(config: config), ax: ax, mcp: mcp,
                         voice: self, user: self, installedApps: InstalledApps.list)
        setStatus("Backend guardado")
    }

    private func startGoal(_ goal: String) {
        stopButton.isHidden = false
        setStatus("Pensando…")
        currentTask = Task { [weak self] in
            guard let self else { return }
            let summary = await self.loop.run(goal: goal)
            await MainActor.run {
                self.setStatus(summary)
                self.stopButton.isHidden = true
            }
        }
    }

    // MARK: - VoiceChannel
    func narrate(_ text: String) { DispatchQueue.main.async { self.bubble.stringValue = text } }
    func speak(_ text: String) {
        DispatchQueue.main.async { self.bubble.stringValue = text; self.setStatus(text) }
        voiceIO.speak(text)
    }

    // MARK: - UserChannel
    func ask(_ question: String) async -> String {
        await MainActor.run { self.setStatus(question); self.panel.makeFirstResponder(self.input) }
        return await withCheckedContinuation { cont in self.pendingAnswer = cont }
    }

    private func setStatus(_ s: String) {
        let t = s.count > 120 ? String(s.prefix(120)) + "…" : s
        DispatchQueue.main.async { self.statusLabel.stringValue = t }
    }
}
