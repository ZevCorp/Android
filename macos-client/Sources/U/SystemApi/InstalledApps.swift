import Foundation

/// Enumera las apps instaladas leyendo /Applications (y la del usuario). El cliente incluye esta lista
/// en cada estado para que el cerebro resuelva `list_apps` y elija qué abrir. Info del dispositivo, no
/// inteligencia.
enum InstalledApps {
    private static var cache: [String]?

    static func list() -> [String] {
        if let c = cache { return c }
        var names = Set<String>()
        let dirs = ["/Applications", "/System/Applications",
                    NSString(string: "~/Applications").expandingTildeInPath]
        for dir in dirs {
            guard let items = try? FileManager.default.contentsOfDirectory(atPath: dir) else { continue }
            for item in items where item.hasSuffix(".app") {
                names.insert(String(item.dropLast(4)))
            }
        }
        let sorted = Array(names).sorted().prefix(300)
        cache = Array(sorted)
        return cache!
    }
}
