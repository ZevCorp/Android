import Foundation

/// Configuración del cliente. Lo ÚNICO que necesita saber: dónde está el backend y (opcional) el token.
/// Ninguna key de modelo, ningún prompt. Se persiste en ~/Library/Application Support/U/config.json.
struct Config: Codable {
    var backendUrl: String = "http://localhost:3000"
    var clientToken: String?
    var userId: String = "anon"

    private static var path: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("U", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("config.json")
    }

    static func load() -> Config {
        guard let data = try? Data(contentsOf: path),
              let cfg = try? JSONDecoder().decode(Config.self, from: data) else { return Config() }
        return cfg
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) { try? data.write(to: Self.path) }
    }
}
