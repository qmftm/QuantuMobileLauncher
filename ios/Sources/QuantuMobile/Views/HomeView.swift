import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var accounts: AccountStore
    @AppStorage("selectedVersion") private var selectedVersion: String = ""
    @State private var status: String = ""
    private let runtime: JVMRuntime = MissingJVMRuntime()

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
                .disabled(accounts.selected == nil || selectedVersion.isEmpty)
                if !status.isEmpty {
                    Text(status).font(.footnote).foregroundColor(.secondary)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("QuantuMobile")
        }
    }

    private func play() async {
        guard let account = accounts.selected else { return }
        let entry = VersionEntry(id: selectedVersion, type: "", url: "", releaseTime: "")
        do {
            try await runtime.launch(version: entry, account: account)
        } catch {
            status = error.localizedDescription
        }
    }
}
