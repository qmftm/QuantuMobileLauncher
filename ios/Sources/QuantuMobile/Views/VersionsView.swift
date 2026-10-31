import SwiftUI

struct VersionsView: View {
    @AppStorage("selectedVersion") private var selectedVersion: String = ""
    @State private var manifest: VersionManifest?
    @State private var error: String?
    @State private var showSnapshots = false

    private var shown: [VersionEntry] {
        (manifest?.versions ?? []).filter { showSnapshots || $0.type == "release" }
    }

    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error).foregroundColor(.red) }
                ForEach(shown) { v in
                    Button {
                        selectedVersion = v.id
                    } label: {
                        HStack {
                            Text(v.id)
                            Spacer()
                            if selectedVersion == v.id { Image(systemName: "checkmark") }
                        }
                    }
                    .foregroundColor(.primary)
                }
            }
            .navigationTitle("Versions")
            .toolbar {
                Toggle("Snapshots", isOn: $showSnapshots)
            }
            .task { await load() }
            .refreshable { await load() }
        }
    }

    private func load() async {
        do {
            manifest = try await MojangAPI.versionManifest()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }
}
