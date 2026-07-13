import AppKit

// Entrypoint del cliente macOS. App de agente (sin icono en el Dock, LSUIElement) que muestra la
// carita flotante como overlay permanente. Pide el permiso de Accesibilidad al arrancar: sin él, la
// Accessibility API no devuelve el árbol de UI.

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let face = FaceWindow()

    func applicationDidFinishLaunching(_ notification: Notification) {
        if !AxReader.isTrusted {
            AxReader.requestTrust()
            let alert = NSAlert()
            alert.messageText = "Ü necesita permiso de Accesibilidad"
            alert.informativeText = "Actívalo en Ajustes → Privacidad y seguridad → Accesibilidad, y también " +
                "Grabación de pantalla (para computer-use). Luego reinicia Ü."
            alert.runModal()
        }
        face.showFloatingFace()
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory) // sin icono en el Dock; solo la carita flotante
let delegate = AppDelegate()
app.delegate = delegate
app.run()
