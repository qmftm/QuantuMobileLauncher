import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var accounts: AccountStore
    @AppStorage("selectedVersion") private var selectedVersion: String = ""
    @StateObject private var session = PlaySession()

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                GroupBox("Account") {
                    Text(accounts.selected?.username ?? "No account selected")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                GroupBox("Version") {
                    Text(selectedVersion.isEmpty ? "No version selected" : selectedVersion)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button {
                    Task { await play() }
                } label: {
                    Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(accounts.selected == nil || selectedVersion.isEmpty || session.busy)
                if session.busy {
                    VStack(spacing: 6) {
                        ProgressView(value: session.fraction)
                        Text(session.stage).font(.footnote).foregroundColor(.secondary)
                    }
                }
                if !session.message.isEmpty {
                    Text(session.message).font(.footnote).foregroundColor(.secondary)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("QuantuMobile")
        }
    }

    private func play() async {
        guard let account = accounts.selected else { return }
        await session.play(versionId: selectedVersion, account: account)
    }
}
