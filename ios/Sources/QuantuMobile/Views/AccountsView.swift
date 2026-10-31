import SwiftUI

struct AccountsView: View {
    @EnvironmentObject private var accounts: AccountStore
    @State private var showMicrosoft = false
    @State private var showOffline = false
    @State private var offlineName = ""
    @State private var message: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(accounts.accounts) { account in
                    Button {
                        accounts.select(account)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(account.username)
                                Text(account.type == .microsoft ? "Microsoft" : "Offline")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            if accounts.selectedID == account.id { Image(systemName: "checkmark") }
                        }
                    }
                    .foregroundColor(.primary)
                    .swipeActions {
                        Button("Delete", role: .destructive) { accounts.remove(account) }
                    }
                }
                if let message { Text(message).foregroundColor(.red) }
            }
            .navigationTitle("Accounts")
            .toolbar {
                Menu {
                    Button("Microsoft account") { showMicrosoft = true }
                    Button("Offline account") { showOffline = true }
                } label: {
                    Image(systemName: "plus")
                }
            }
            .sheet(isPresented: $showMicrosoft) {
                MicrosoftLoginView { code in
                    showMicrosoft = false
                    Task {
                        do {
                            accounts.add(try await MicrosoftAuth.login(code: code))
                            message = nil
                        } catch {
                            message = error.localizedDescription
                        }
                    }
                }
            }
            .alert("Offline account", isPresented: $showOffline) {
                TextField("Player name", text: $offlineName)
                Button("Add") {
                    let name = offlineName.trimmingCharacters(in: .whitespaces)
                    if Account.isValidOfflineName(name) {
                        accounts.add(.offline(name: name))
                        message = nil
                    } else {
                        message = "Use 3-16 letters, digits or underscores."
                    }
                    offlineName = ""
                }
                Button("Cancel", role: .cancel) { offlineName = "" }
            }
        }
    }
}
