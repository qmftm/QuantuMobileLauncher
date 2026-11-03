import Foundation

struct AuthError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Microsoft -> Xbox Live -> XSTS -> Minecraft services login chain, the same one the Android launcher uses
/// (public Live "desktop" OAuth client; the sign-in page runs in a web view and the code is read from the redirect URL).
enum MicrosoftAuth {
    static let clientID = "00000000402b5328"
    static let redirectURI = "https://login.live.com/oauth20_desktop.srf"
    private static let scope = "service::user.auth.xboxlive.com::MBI_SSL"

    static var authorizeURL: URL {
        var c = URLComponents(string: "https://login.live.com/oauth20_authorize.srf")!
        c.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: scope),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "prompt", value: "select_account"),
        ]
        return c.url!
    }

    static func login(code: String) async throws -> Account {
        try await complete(msToken(["code": code, "grant_type": "authorization_code"]))
    }

    static func refresh(_ account: Account) async throws -> Account {
        guard let refresh = account.refreshToken else { throw AuthError(message: "No refresh token; please sign in again") }
        var fresh = try await complete(msToken(["refresh_token": refresh, "grant_type": "refresh_token"]))
        fresh.id = account.id
        return fresh
    }

    private struct MsToken { let accessToken: String; let refreshToken: String }

    private static func msToken(_ params: [String: String]) async throws -> MsToken {
        var fields = params
        fields["client_id"] = clientID
        fields["redirect_uri"] = redirectURI
        fields["scope"] = scope
        let res = try await Http.postForm("https://login.live.com/oauth20_token.srf", fields: fields)
        let json = res.json
        guard res.isSuccess, let access = json["access_token"] as? String, let refresh = json["refresh_token"] as? String else {
            throw AuthError(message: "Microsoft login failed: \((json["error_description"] as? String) ?? String(res.code))")
        }
        return MsToken(accessToken: access, refreshToken: refresh)
    }

    private static func complete(_ ms: MsToken) async throws -> Account {
        // Xbox Live user token
        let xbl = try await Http.postJSON("https://user.auth.xboxlive.com/user/authenticate", body: [
            "Properties": ["AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": ms.accessToken],
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        ])
        guard xbl.isSuccess, let xblToken = xbl.json["Token"] as? String else {
            throw AuthError(message: "Xbox Live authentication failed (HTTP \(xbl.code))")
        }

        // XSTS token for Minecraft services
        let xsts = try await Http.postJSON("https://xsts.auth.xboxlive.com/xsts/authorize", body: [
            "Properties": ["SandboxId": "RETAIL", "UserTokens": [xblToken]],
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        ])
        if xsts.code == 401 { throw AuthError(message: xstsMessage(xsts.json["XErr"])) }
        guard xsts.isSuccess, let xstsToken = xsts.json["Token"] as? String,
              let claims = ((xsts.json["DisplayClaims"] as? [String: Any])?["xui"] as? [[String: Any]])?.first,
              let uhs = claims["uhs"] as? String else {
            throw AuthError(message: "XSTS authorization failed (HTTP \(xsts.code))")
        }

        // Minecraft access token
        let mc = try await Http.postJSON("https://api.minecraftservices.com/authentication/login_with_xbox", body: [
            "identityToken": "XBL3.0 x=\(uhs);\(xstsToken)",
        ])
        guard mc.isSuccess, let mcToken = mc.json["access_token"] as? String else {
            throw AuthError(message: "Minecraft services authentication failed (HTTP \(mc.code))")
        }
        let expiresIn = (mc.json["expires_in"] as? Double) ?? 86_400

        // Profile (also proves game ownership)
        let profileRes = try await Http.get("https://api.minecraftservices.com/minecraft/profile",
                                            headers: ["Authorization": "Bearer \(mcToken)"])
        if profileRes.code == 404 { throw AuthError(message: "This Microsoft account does not own Minecraft: Java Edition") }
        guard profileRes.isSuccess, let name = profileRes.json["name"] as? String, let id = profileRes.json["id"] as? String else {
            throw AuthError(message: "Failed to fetch the Minecraft profile (HTTP \(profileRes.code))")
        }

        return Account(
            type: .microsoft, username: name, uuid: id, accessToken: mcToken, refreshToken: ms.refreshToken,
            expiresAt: Date().timeIntervalSince1970 + expiresIn, xuid: (claims["xid"] as? String) ?? ""
        )
    }

    private static func xstsMessage(_ code: Any?) -> String {
        let value = (code as? NSNumber)?.int64Value ?? 0
        switch value {
        case 2148916233: return "This Microsoft account has no Xbox profile. Sign in at minecraft.net once to create one."
        case 2148916235: return "Xbox Live is not available in your country."
        case 2148916236, 2148916237: return "This account needs adult verification on the Xbox website."
        case 2148916238: return "This is a child account; it must be added to a Microsoft family by an adult."
        default: return "Xbox authorization failed (XErr \(value))"
        }
    }
}
