import CoreGraphics

/// Gestos de navegación de macOS por atajos del sistema — el equivalente a los gestos de Windows
/// (mostrar escritorio, Spotlight, cambiar de app). Puro I/O de teclado.
enum Gestures {
    private static let src = CGEventSource(stateID: .hidSystemState)

    // Códigos de tecla virtuales macOS
    private static let kD: CGKeyCode = 2, kTab: CGKeyCode = 48, kSpace: CGKeyCode = 49, kF11: CGKeyCode = 103

    /// Mostrar el escritorio (F11 / Mission Control "show desktop").
    @discardableResult static func showDesktop() -> Bool { combo(key: kF11, flags: []) }

    /// Spotlight (⌘Space) para buscar/lanzar apps — el "cajón de aplicaciones".
    @discardableResult static func spotlight() -> Bool { combo(key: kSpace, flags: .maskCommand) }

    /// Centro de notificaciones no tiene atajo fijo; abrimos Mission Control como "notificaciones".
    @discardableResult static func missionControl() -> Bool { combo(key: kF11, flags: .maskControl) }

    /// Cambiar de app (⌘Tab / ⌘⇧Tab).
    @discardableResult
    static func switchWindow(next: Bool) -> Bool {
        combo(key: kTab, flags: next ? .maskCommand : [.maskCommand, .maskShift])
    }

    private static func combo(key: CGKeyCode, flags: CGEventFlags) -> Bool {
        guard let down = CGEvent(keyboardEventSource: src, virtualKey: key, keyDown: true),
              let up = CGEvent(keyboardEventSource: src, virtualKey: key, keyDown: false) else { return false }
        down.flags = flags
        up.flags = flags
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
        return true
    }
}
