import AppKit
import CoreGraphics

/// Captura la pantalla a PNG (base64) para computer-use. Se llama SOLO cuando el turno anterior pidió
/// `needsScreenshot`. Escala la imagen a la resolución en PUNTOS de la pantalla, para que las
/// coordenadas que devuelve el cerebro coincidan 1:1 con los puntos que usa CGEvent al tocar (en
/// pantallas Retina, píxeles = 2× puntos; capturar en puntos evita el reescalado).
///
/// Requiere permiso de **Grabación de pantalla** en macOS 14+ (Ajustes → Privacidad → Grabación).
enum Screenshotter {
    static func captureBase64Png() -> String? {
        guard let screen = NSScreen.main else { return nil }
        let pointSize = screen.frame.size
        guard let cgImage = CGWindowListCreateImage(
            .infinite, .optionOnScreenOnly, kCGNullWindowID, [.bestResolution]
        ) else { return nil }

        // Reescala a puntos.
        let rep = NSBitmapImageRep(cgImage: cgImage)
        rep.size = pointSize
        let target = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: Int(pointSize.width),
            pixelsHigh: Int(pointSize.height),
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        )
        guard let target else { return rep.representation(using: .png, properties: [:])?.base64EncodedString() }
        target.size = pointSize
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: target)
        rep.draw(in: CGRect(origin: .zero, size: pointSize))
        NSGraphicsContext.restoreGraphicsState()

        return target.representation(using: .png, properties: [:])?.base64EncodedString()
    }
}
