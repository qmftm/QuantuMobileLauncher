import Foundation
import CryptoKit

enum AccountType: String, Codable {
    case microsoft
    case offline
}

/// Same shape as the Android launcher's Account (accounts.json), so the two apps stay easy to compare.
struct Account: Codable, Identifiable, Equatable {
    var id: String = UUID().uuidString
    var type: AccountType
    var username: String
    /// Undashed Minecraft profile UUID.
    var uuid: String
    var accessToken: String = "0"
    var refreshToken: String?
    /// Seconds since 1970 when `accessToken` expires (Microsoft accounts only).
    var expiresAt: TimeInterval = 0
    var xuid: String = ""

    var userType: String { type == .microsoft ? "msa" : "legacy" }
    var needsRefresh: Bool { type == .microsoft && Date().timeIntervalSince1970 > expiresAt - 300 }

    static func isValidOfflineName(_ name: String) -> Bool {
        name.range(of: "^[A-Za-z0-9_]{3,16}$", options: .regularExpression) != nil
    }

    /// The UUID vanilla servers derive for offline-mode players: MD5("OfflinePlayer:<name>") with version 3 bits set.
    static func offlineUUID(for name: String) -> String {
        var bytes = Array(Insecure.MD5.hash(data: Data("OfflinePlayer:\(name)".utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func offline(name: String) -> Account {
        Account(type: .offline, username: name, uuid: offlineUUID(for: name))
    }
}

/// The saved accounts and the selected one (stored as JSON in UserDefaults; move to the Keychain before shipping tokens).
@MainActor
final class AccountStore: ObservableObject {
    @Published private(set) var accounts: [Account] = []
    @Published var selectedID: String?

    private let key = "accounts.v1"
    private let selectedKey = "accounts.selected"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let list = try? JSONDecoder().decode([Account].self, from: data) {
            accounts = list
        }
        selectedID = UserDefaults.standard.string(forKey: selectedKey) ?? accounts.first?.id
    }

    var selected: Account? { accounts.first { $0.id == selectedID } }

    func add(_ account: Account) {
        if let i = accounts.firstIndex(where: { $0.uuid == account.uuid && $0.type == account.type }) {
            var merged = account
            merged.id = accounts[i].id
            accounts[i] = merged
        } else {
            accounts.append(account)
        }
        if selectedID == nil { selectedID = account.id }
        save()
    }

    func remove(_ account: Account) {
        accounts.removeAll { $0.id == account.id }
        if selectedID == account.id { selectedID = accounts.first?.id }
        save()
    }

    func select(_ account: Account) {
        selectedID = account.id
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(accounts) { UserDefaults.standard.set(data, forKey: key) }
        UserDefaults.standard.set(selectedID, forKey: selectedKey)
    }
}
