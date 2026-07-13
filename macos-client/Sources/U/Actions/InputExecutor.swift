import CoreGraphics
import Foundation

/// Ejecuta las primitivas de computer-use con CGEvent (Quartz): el equivalente macOS de las primitivas
/// `Phone` (tap/type/scroll/swipe/key). Puro I/O; sin ninguna decisión propia. Coordenadas en PUNTOS
/// globales de pantalla (origen arriba-izquierda), que es lo que usan CGEvent y la Accessibility API.
enum InputExecutor {

    private static let src = CGEventSource(stateID: .hidSystemState)

    @discardableResult
    static func tap(x: Int, y: Int) -> Bool {
        let p = CGPoint(x: x, y: y)
        move(to: p)
        usleep(30_000)
        post(.leftMouseDown, at: p, button: .left)
        post(.leftMouseUp, at: p, button: .left)
        return true
    }

    @discardableResult
    static func type(x: Int, y: Int, text: String) -> Bool {
        tap(x: x, y: y)
        usleep(60_000)
        return typeText(text)
    }

    @discardableResult
    static func typeText(_ text: String) -> Bool {
        for scalar in text.unicodeScalars {
            var utf16 = Array(String(scalar).utf16)
            for down in [true, false] {
                guard let e = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: down) else { continue }
                e.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
                e.post(tap: .cghidEventTap)
            }
        }
        return true
    }

    @discardableResult
    static func scroll(down: Bool) -> Bool {
        let delta: Int32 = down ? -3 : 3
        guard let e = CGEvent(scrollWheelEvent2Source: src, units: .line, wheelCount: 1, wheel1: delta, wheel2: 0, wheel3: 0) else { return false }
        e.post(tap: .cghidEventTap)
        return true
    }

    @discardableResult
    static func swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) -> Bool {
        let start = CGPoint(x: x1, y: y1)
        let end = CGPoint(x: x2, y: y2)
        move(to: start)
        post(.leftMouseDown, at: start, button: .left)
        let steps = max(10, ms / 15)
        for i in 1...steps {
            let t = Double(i) / Double(steps)
            let p = CGPoint(x: Double(x1) + (Double(x2 - x1) * t), y: Double(y1) + (Double(y2 - y1) * t))
            post(.leftMouseDragged, at: p, button: .left)
            usleep(useconds_t(max(1, ms / steps) * 1000))
        }
        post(.leftMouseUp, at: end, button: .left)
        return true
    }

    /// Teclas semánticas que el cerebro envía normalizadas (enter/back/tab/…).
    @discardableResult
    static func key(_ key: String) -> Bool {
        let vk: CGKeyCode? = {
            switch key.lowercased() {
            case "enter": return 36
            case "back", "esc", "escape": return 53
            case "tab": return 48
            case "backspace": return 51
            case "delete": return 117
            case "up": return 126
            case "down": return 125
            case "left": return 123
            case "right": return 124
            case "home": return 115
            case "end": return 119
            case "space": return 49
            default: return nil
            }
        }()
        guard let code = vk else { return false }
        for down in [true, false] {
            CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down)?.post(tap: .cghidEventTap)
        }
        return true
    }

    // MARK: - helpers

    private static func move(to p: CGPoint) {
        CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left)?
            .post(tap: .cghidEventTap)
    }

    private static func post(_ type: CGEventType, at p: CGPoint, button: CGMouseButton) {
        CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: button)?
            .post(tap: .cghidEventTap)
    }
}
