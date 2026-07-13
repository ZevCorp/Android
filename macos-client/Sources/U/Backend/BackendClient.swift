import Foundation

/// El único puente con el cerebro: `POST {backendUrl}/api/agent/turn`. El cliente manda el estado de
/// pantalla y recibe las acciones a ejecutar. Sin este endpoint, el cliente no sabe pensar — a propósito.
final class BackendClient {
    private let baseUrl: String
    private let userId: String
    private let token: String?

    init(config: Config) {
        baseUrl = config.backendUrl.hasSuffix("/") ? String(config.backendUrl.dropLast()) : config.backendUrl
        userId = config.userId
        token = (config.clientToken?.isEmpty == false) ? config.clientToken : nil
    }

    func turn(_ req: TurnRequest) async throws -> TurnResponse {
        var r = req
        r.userId = userId
        var request = URLRequest(url: URL(string: "\(baseUrl)/api/agent/turn")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONEncoder().encode(r)
        request.timeoutInterval = 300

        let (data, response) = try await URLSession.shared.data(for: request)
        let parsed = try JSONDecoder().decode(TurnResponse.self, from: data)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code >= 300 {
            throw NSError(domain: "U", code: code, userInfo: [NSLocalizedDescriptionKey: parsed.error ?? "backend HTTP \(code)"])
        }
        return parsed
    }
}
