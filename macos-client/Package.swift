// swift-tools-version:5.9
import PackageDescription

// Cliente macOS del asistente Ü. FRONTEND TONTO, igual que el de Windows: lee el árbol de UI con la
// Accessibility API (AXUIElement), captura pantalla, ejecuta ratón/teclado y muestra la carita
// flotante. NADA de inteligencia: cada turno consulta al backend (Vercel). El mismo contrato que el
// cliente Windows — el backend no cambia ni una línea.
let package = Package(
    name: "U",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "U",
            path: "Sources/U"
        )
    ]
)
