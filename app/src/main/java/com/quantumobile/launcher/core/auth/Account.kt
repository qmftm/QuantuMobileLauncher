package com.quantumobile.launcher.core.auth

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AccountType { MICROSOFT, OFFLINE }

@Serializable
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val type: AccountType,
    val username: String,
    /** Undashed Minecraft profile UUID. */
    val uuid: String,
    val accessToken: String = "0",
    val refreshToken: String? = null,
    /** Epoch millis when [accessToken] expires (Microsoft accounts only). */
    val expiresAt: Long = 0,
    val xuid: String = "",
) {
    val userType get() = if (type == AccountType.MICROSOFT) "msa" else "legacy"
    val needsRefresh get() = type == AccountType.MICROSOFT && System.currentTimeMillis() > expiresAt - 5 * 60_000

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_]{3,16}$")

        fun isValidOfflineName(name: String) = NAME.matches(name)

        /** Same UUID derivation vanilla servers use for offline-mode players. */
        fun offlineUuid(name: String): String =
            UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString().replace("-", "")

        fun offline(name: String): Account {
            require(isValidOfflineName(name)) { "Invalid player name: $name" }
            return Account(type = AccountType.OFFLINE, username = name, uuid = offlineUuid(name))
        }
    }
}
