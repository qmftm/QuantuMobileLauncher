package com.quantumobile.launcher.core.afk

import com.quantumobile.launcher.core.auth.Account

/** Callbacks from a running connection to the bot that owns it. */
interface AfkEvents {
    /** The player is in the world (play state reached). [version] is the server's version name. */
    fun connected(version: String)

    /** Something worth showing next to the status (e.g. "respawned"). */
    fun note(message: String)

    /** The server's list icon (64×64 PNG) from its status ping, when it has one. */
    fun serverIcon(png: ByteArray) {}

    /** A line for the server's log (chat, joins, deaths…). */
    fun log(kind: AfkLogKind, text: String) {}

    /** The bot's surroundings changed (position, health, players…). */
    fun world(info: AfkWorldInfo) {}

    /** While in the world, a way to send chat or "/commands" (returns false if it could not); null once disconnected. */
    fun chatSender(send: ((String) -> Boolean)?) {}
}

/** How a connection ended. [retry] is false when reconnecting cannot help (unsupported version, wrong account type). */
data class AfkDisconnect(val reason: String, val retry: Boolean = true)

/** Runs one connection to a server until it ends. Cancelling the coroutine closes the connection. */
fun interface AfkConnector {
    suspend fun run(host: String, port: Int, account: Account, events: AfkEvents): AfkDisconnect
}

/** An error that reconnecting will not fix (missing account, expired login, wrong account type). */
open class AfkFatalException(message: String) : java.io.IOException(message)
