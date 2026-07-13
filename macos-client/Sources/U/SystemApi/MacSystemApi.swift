import AppKit
import Foundation

/// Acciones de sistema por API/URL-scheme de macOS: el equivalente a los "Common Intents" de Android y
/// a `WindowsSystemApi`. Abren apps, URLs, correo, ajustes… sin navegar la interfaz. Solo se EJECUTAN.
enum MacSystemApi {

    @discardableResult
    static func launchApp(_ name: String) -> Bool {
        guard !name.isEmpty else { return false }
        // Por nombre de app (NSWorkspace) o dejando que `open -a` lo resuelva.
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: name) {
            NSWorkspace.shared.open(url); return true
        }
        return shell("/usr/bin/open", ["-a", name])
    }

    @discardableResult static func openUrl(_ url: String) -> Bool { open(normalize(url)) }
    @discardableResult static func webSearch(_ q: String) -> Bool { open("https://www.google.com/search?q=\(enc(q))") }
    @discardableResult static func openMaps(_ q: String) -> Bool { open("https://maps.apple.com/?q=\(enc(q))") }
    @discardableResult static func directions(_ d: String) -> Bool { open("https://maps.apple.com/?daddr=\(enc(d))") }
    @discardableResult static func sendEmail(_ to: String, _ subject: String, _ body: String) -> Bool {
        open("mailto:\(enc(to))?subject=\(enc(subject))&body=\(enc(body))")
    }
    @discardableResult static func dial(_ number: String) -> Bool { open("tel:\(enc(number))") }
    @discardableResult static func sendSms(_ number: String, _ message: String) -> Bool { open("sms:\(enc(number))&body=\(enc(message))") }
    @discardableResult static func openCamera() -> Bool { launchApp("Photo Booth") }

    @discardableResult
    static func openSettings(_ section: String) -> Bool {
        let pane: String
        switch section {
        case "wifi": pane = "x-apple.systempreferences:com.apple.wifi-settings-extension"
        case "bluetooth": pane = "x-apple.systempreferences:com.apple.BluetoothSettings"
        case "display": pane = "x-apple.systempreferences:com.apple.Displays-Settings.extension"
        case "sound": pane = "x-apple.systempreferences:com.apple.Sound-Settings.extension"
        case "network": pane = "x-apple.systempreferences:com.apple.Network-Settings.extension"
        case "battery": pane = "x-apple.systempreferences:com.apple.Battery-Settings.extension"
        case "privacy": pane = "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension"
        default: pane = "x-apple.systempreferences:"
        }
        return open(pane)
    }

    @discardableResult
    static func setClipboard(_ text: String) -> Bool {
        let pb = NSPasteboard.general
        pb.clearContents()
        return pb.setString(text, forType: .string)
    }

    @discardableResult static func shareText(_ text: String) -> Bool { setClipboard(text) }

    @discardableResult
    static func adjustVolume(_ direction: String) -> Bool {
        let script: String
        switch direction {
        case "raise": script = "set volume output volume ((output volume of (get volume settings)) + 12)"
        case "lower": script = "set volume output volume ((output volume of (get volume settings)) - 12)"
        case "mute": script = "set volume with output muted"
        case "unmute": script = "set volume without output muted"
        default: return false
        }
        return osascript(script)
    }

    @discardableResult
    static func setVolume(_ percent: Int) -> Bool {
        osascript("set volume output volume \(max(0, min(100, percent)))")
    }

    // La app Reloj/Calendario no exponen protocolo directo; abrirlas es el fallback honesto.
    @discardableResult static func setAlarm(_ h: Int, _ m: Int, _ msg: String) -> Bool { launchApp("Clock") }
    @discardableResult static func setTimer(_ s: Int, _ msg: String) -> Bool { launchApp("Clock") }
    @discardableResult static func createEvent(_ t: String, _ start: String, _ loc: String) -> Bool { launchApp("Calendar") }

    // MARK: - helpers

    private static func normalize(_ url: String) -> String {
        url.hasPrefix("http") ? url : "https://\(url)"
    }
    private static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }
    @discardableResult
    private static func open(_ url: String) -> Bool {
        guard let u = URL(string: url) else { return false }
        return NSWorkspace.shared.open(u)
    }
    @discardableResult
    private static func osascript(_ script: String) -> Bool { shell("/usr/bin/osascript", ["-e", script]) }
    @discardableResult
    private static func shell(_ path: String, _ args: [String]) -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        do { try p.run(); return true } catch { return false }
    }
}
