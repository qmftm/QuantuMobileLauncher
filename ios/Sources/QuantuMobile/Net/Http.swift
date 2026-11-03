import Foundation

struct HTTPResult {
    let code: Int
    let body: Data
    var isSuccess: Bool { (200..<300).contains(code) }
    var json: [String: Any] { (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:] }
}

enum Http {
    static func get(_ url: String, headers: [String: String] = [:]) async throws -> HTTPResult {
        var req = URLRequest(url: URL(string: url)!)
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        return try await send(req)
    }

    static func postJSON(_ url: String, body: [String: Any]) async throws -> HTTPResult {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(req)
    }

    static func postForm(_ url: String, fields: [String: String]) async throws -> HTTPResult {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=+")
        let text = fields.map { key, value in
            "\(key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key)=\(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)"
        }.joined(separator: "&")
        req.httpBody = Data(text.utf8)
        return try await send(req)
    }

    private static func send(_ req: URLRequest) async throws -> HTTPResult {
        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        return HTTPResult(code: code, body: data)
    }
}
