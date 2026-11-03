import SwiftUI

@main
struct QuantuMobileApp: App {
    @StateObject private var accounts = AccountStore()

    var body: some Scene {
        WindowGroup {
            TabView {
                HomeView()
                    .tabItem { Label("Play", systemImage: "play.fill") }
                VersionsView()
                    .tabItem { Label("Versions", systemImage: "square.stack.3d.up") }
                AccountsView()
                    .tabItem { Label("Accounts", systemImage: "person.crop.circle") }
            }
            .environmentObject(accounts)
        }
    }
}
