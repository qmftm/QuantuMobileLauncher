package com.quantumobile.launcher.core.auth

import com.quantumobile.launcher.core.LauncherJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class AccountFile(val selected: String? = null, val accounts: List<Account> = emptyList())

/** Persists accounts in app-private storage (not readable by other apps). */
class AccountStore(private val file: File) {
    private val state = MutableStateFlow(load())

    val accounts: StateFlow<List<Account>> get() = _accounts
    private val _accounts = MutableStateFlow(state.value.accounts)

    val selected: Account?
        get() = state.value.let { s -> s.accounts.firstOrNull { it.id == s.selected } ?: s.accounts.firstOrNull() }

    @Synchronized
    fun upsert(account: Account, select: Boolean = true) {
        val s = state.value
        // Re-adding the same Minecraft profile replaces the old entry instead of duplicating it.
        val existing = s.accounts.firstOrNull { it.uuid == account.uuid && it.type == account.type }
        val toSave = if (existing != null) account.copy(id = existing.id) else account
        val list = s.accounts.filterNot { it.id == toSave.id } + toSave
        save(AccountFile(if (select) toSave.id else s.selected, list))
    }

    @Synchronized
    fun select(id: String) = save(state.value.copy(selected = id))

    @Synchronized
    fun remove(id: String) {
        val s = state.value
        save(AccountFile(s.selected.takeUnless { it == id }, s.accounts.filterNot { it.id == id }))
    }

    private fun load(): AccountFile =
        runCatching { LauncherJson.decodeFromString<AccountFile>(file.readText()) }.getOrDefault(AccountFile())

    private fun save(data: AccountFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(LauncherJson.encodeToString(AccountFile.serializer(), data))
        tmp.renameTo(file)
        state.value = data
        _accounts.value = data.accounts
    }
}
