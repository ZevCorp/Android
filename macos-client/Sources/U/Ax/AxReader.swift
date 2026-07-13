import AppKit
import ApplicationServices

/// Lee el árbol de UI de macOS con la Accessibility API (AXUIElement) y lo resume como TEXTO para el
/// cerebro — el gemelo de `UiaReader` (Windows/UIA) y de `GraphAccessibilityService` (Android).
///
/// Produce el `ScreenState` por defecto (sin imagen): `screen` (app · título de ventana) y `uiContext`
/// (elementos accionables visibles). Con eso al cerebro le basta para ubicarse y actuar por MCP; el
/// screenshot solo se adjunta cuando pide computer-use.
///
/// Requiere permiso de **Accesibilidad** (Ajustes → Privacidad y seguridad → Accesibilidad).
final class AxReader {

    struct UiElement {
        let label: String
        let role: String
        let frame: CGRect       // coordenadas globales de pantalla, en PUNTOS (origen arriba-izquierda)
        let element: AXUIElement
    }

    private(set) var elements: [UiElement] = []

    /// ¿Está concedido el permiso de accesibilidad? Sin él, AX no devuelve nada.
    static var isTrusted: Bool { AXIsProcessTrusted() }

    /// Pide el permiso de accesibilidad (muestra el diálogo del sistema la primera vez).
    static func requestTrust() {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
    }

    private static let actionableRoles: Set<String> = [
        kAXButtonRole, kAXMenuItemRole, kAXMenuBarItemRole, kAXTextFieldRole, kAXTextAreaRole,
        kAXCheckBoxRole, kAXRadioButtonRole, kAXPopUpButtonRole, kAXLinkRole, kAXCellRole,
        kAXRowRole, kAXStaticTextRole, kAXComboBoxRole, kAXTabGroupRole, kAXListRole,
    ]

    /// Captura el estado actual (solo texto; el screenshot lo llena `Screenshotter`).
    func read() -> ScreenState {
        let screenFrame = NSScreen.main?.frame ?? .zero
        var state = ScreenState(
            screen: "escritorio",
            uiContext: "",
            width: Int(screenFrame.width),
            height: Int(screenFrame.height),
            screenshot: nil,
            apps: nil
        )

        guard let app = NSWorkspace.shared.frontmostApplication else {
            state.uiContext = "Escritorio de macOS (sin app en primer plano)."
            elements = []
            return state
        }
        let appName = app.localizedName ?? "app"
        let axApp = AXUIElementCreateApplication(app.processIdentifier)

        let window = copyElement(axApp, kAXFocusedWindowAttribute as CFString)
            ?? copyElement(axApp, kAXMainWindowAttribute as CFString)
        let title = window.flatMap { copyString($0, kAXTitleAttribute as CFString) } ?? ""
        state.screen = title.isEmpty ? appName : "\(appName) · \(title)"

        var found: [UiElement] = []
        if let root = window ?? Optional(axApp) {
            collect(root, into: &found, depth: 0)
        }
        elements = found
        state.uiContext = buildContext(app: appName, title: title, elements: found)
        return state
    }

    /// Punto (centro, en puntos) donde tocar el primer elemento cuya etiqueta coincida.
    func tapTarget(forLabel label: String) -> CGPoint? {
        guard let el = elements.first(where: { matches($0.label, label) }) else { return nil }
        guard !el.frame.isEmpty else { return nil }
        return CGPoint(x: el.frame.midX, y: el.frame.midY)
    }

    /// Invoca por acción AX (kAXPressAction, sin ratón) el primer elemento con esa etiqueta.
    func press(label: String) -> Bool {
        guard let el = elements.first(where: { matches($0.label, label) }) else { return false }
        return AXUIElementPerformAction(el.element, kAXPressAction as CFString) == .success
    }

    // MARK: - recorrido del árbol

    private func collect(_ node: AXUIElement, into acc: inout [UiElement], depth: Int) {
        if depth > 40 || acc.count > 400 { return }
        guard let children = copyChildren(node) else { return }
        for child in children {
            let role = copyString(child, kAXRoleAttribute as CFString) ?? ""
            if Self.actionableRoles.contains(role), !isOffscreen(child) {
                let label = labelOf(child)
                if !label.isEmpty, let frame = frameOf(child) {
                    acc.append(UiElement(label: label, role: shortRole(role), frame: frame, element: child))
                }
            }
            collect(child, into: &acc, depth: depth + 1)
            if acc.count > 400 { return }
        }
    }

    /// La etiqueta con la que el agente identifica un elemento: title → description → value → identifier.
    private func labelOf(_ el: AXUIElement) -> String {
        for attr in [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute, kAXIdentifierAttribute] {
            if let s = copyString(el, attr as CFString), !s.trimmingCharacters(in: .whitespaces).isEmpty {
                return s.trimmingCharacters(in: .whitespaces)
            }
        }
        return ""
    }

    private func buildContext(app: String, title: String, elements: [UiElement]) -> String {
        var sb = "App en primer plano: \(app)\n"
        if !title.isEmpty { sb += "Ventana: \(title)\n" }
        sb += "Elementos accionables visibles (\(elements.count)):\n"
        let grouped = Dictionary(grouping: elements, by: { $0.role })
        for (role, items) in grouped.sorted(by: { $0.key < $1.key }) {
            let labels = Array(Set(items.map { $0.label }.filter { $0.count <= 60 })).prefix(40)
            sb += "- \(role): \(labels.joined(separator: " | "))\n"
        }
        return sb.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - helpers AX

    private func matches(_ a: String, _ b: String) -> Bool {
        a.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(b.trimmingCharacters(in: .whitespaces)) == .orderedSame
    }

    private func copyElement(_ el: AXUIElement, _ attr: CFString) -> AXUIElement? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, attr, &value) == .success, let v = value else { return nil }
        // swiftlint:disable:next force_cast
        if CFGetTypeID(v) == AXUIElementGetTypeID() { return (v as! AXUIElement) }
        return nil
    }

    private func copyChildren(_ el: AXUIElement) -> [AXUIElement]? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &value) == .success,
              let arr = value as? [AXUIElement] else { return nil }
        return arr
    }

    private func copyString(_ el: AXUIElement, _ attr: CFString) -> String? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, attr, &value) == .success, let v = value else { return nil }
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }

    private func isOffscreen(_ el: AXUIElement) -> Bool {
        guard let frame = frameOf(el) else { return true }
        return frame.width < 1 || frame.height < 1
    }

    private func frameOf(_ el: AXUIElement) -> CGRect? {
        var posVal: CFTypeRef?
        var sizeVal: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, kAXPositionAttribute as CFString, &posVal) == .success,
              AXUIElementCopyAttributeValue(el, kAXSizeAttribute as CFString, &sizeVal) == .success
        else { return nil }
        var point = CGPoint.zero
        var size = CGSize.zero
        if let p = posVal { AXValueGetValue(p as! AXValue, .cgPoint, &point) }
        if let s = sizeVal { AXValueGetValue(s as! AXValue, .cgSize, &size) }
        return CGRect(origin: point, size: size)
    }

    private func shortRole(_ role: String) -> String {
        role.replacingOccurrences(of: "AX", with: "")
    }
}
