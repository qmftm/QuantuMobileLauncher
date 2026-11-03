package com.quantumobile.launcher.core.afk.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns a chat component (kick messages and the like) into plain text. Servers send these as JSON text
 * (login state, and everything before 1.20.3) or as network NBT (configuration/play from 1.20.3).
 */
object TextComponent {

    fun fromJson(text: String): String {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return text
        return plain(element).trim()
    }

    /** Reads one network-NBT text component from [reader] (no root name, 1.20.2+ format). */
    fun fromNbt(reader: PacketReader): String {
        val type = reader.byte()
        return plain(Nbt.toJson(Nbt.readPayload(reader, type))).trim()
    }

    fun plain(element: JsonElement): String = buildString { appendText(element) }.trimEnd()

    private fun StringBuilder.appendText(element: JsonElement) {
        when (element) {
            is JsonNull -> Unit
            is JsonPrimitive -> append(element.contentOrNull.orEmpty())
            is JsonArray -> element.forEach { appendText(it) }
            is JsonObject -> {
                val text = (element["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (element[""] as? JsonPrimitive)?.contentOrNull
                val key = (element["translate"] as? JsonPrimitive)?.contentOrNull
                when {
                    text != null -> append(text)
                    key != null -> {
                        val args = (element["with"] as? JsonArray)?.map { plain(it) }.orEmpty()
                        append(translate(key, args, (element["fallback"] as? JsonPrimitive)?.contentOrNull))
                    }
                }
                (element["extra"] as? JsonArray)?.forEach { appendText(it) }
            }
        }
    }

    /** Korean text for the vanilla messages a player is most likely to see; other keys are shown as is. */
    private fun translate(key: String, args: List<String>, fallback: String?): String {
        fun a(i: Int) = args.getOrElse(i) { "?" }
        val known = when (key) {
            "chat.type.text" -> "<${a(0)}> ${a(1)}"
            "chat.square_brackets" -> "[${a(0)}]"
            "commands.list.players" -> "접속 중 ${a(0)}/${a(1)}명: ${a(2)}"
            "commands.message.display.outgoing" -> "나 → ${a(0)}: ${a(1)}"
            "chat.disabled.missingProfileKey", "chat.disabled.profile", "chat.disabled.options" ->
                "이 서버는 서명된 채팅만 받아요 (명령어는 보낼 수 있어요)"
            "multiplayer.disconnect.unsigned_chat" -> "서명이 없는 채팅이라 추방됐어요"
            "gameMode.changed" -> "게임 모드가 ${a(0)}(으)로 바뀌었어요"
            "gameMode.survival" -> "서바이벌"
            "gameMode.creative" -> "크리에이티브"
            "gameMode.adventure" -> "모험"
            "gameMode.spectator" -> "관전"
            "chat.type.announcement" -> "[${a(0)}] ${a(1)}"
            "chat.type.emote" -> "* ${a(0)} ${a(1)}"
            "chat.type.admin" -> "[${a(0)}: ${a(1)}]"
            "commands.message.display.incoming" -> "${a(0)} → 나: ${a(1)}"
            "multiplayer.player.joined" -> "${a(0)} 님이 들어왔어요"
            "multiplayer.player.joined.renamed" -> "${a(0)} (${a(1)}) 님이 들어왔어요"
            "multiplayer.player.left" -> "${a(0)} 님이 나갔어요"
            "chat.type.advancement.task", "chat.type.advancement.goal", "chat.type.advancement.challenge" ->
                "${a(0)} 님이 발전 과제 달성: ${a(1)}"
            "death.attack.mob", "death.attack.player", "death.attack.arrow", "death.attack.mob.item", "death.attack.player.item" ->
                "☠ ${a(0)} — ${a(1)} 에게 죽었어요"
            "death.fell.accident.generic", "death.attack.fall" -> "☠ ${a(0)} — 떨어져 죽었어요"
            "death.attack.lava" -> "☠ ${a(0)} — 용암에 빠졌어요"
            "death.attack.drown" -> "☠ ${a(0)} — 익사했어요"
            "death.attack.starve" -> "☠ ${a(0)} — 굶어 죽었어요"
            "death.attack.genericKill", "death.attack.outOfWorld" -> "☠ ${a(0)} — 죽었어요"
            "multiplayer.disconnect.idling" -> "너무 오래 가만히 있어서 추방됐어요"
            "multiplayer.disconnect.duplicate_login" -> "같은 계정이 다른 곳에서 접속했어요"
            "multiplayer.disconnect.server_shutdown" -> "서버가 종료됐어요"
            "multiplayer.disconnect.not_whitelisted" -> "화이트리스트에 없는 계정이에요"
            "multiplayer.disconnect.banned" -> "이 서버에서 차단된 계정이에요"
            "multiplayer.disconnect.banned.reason" -> "차단됨: ${args.firstOrNull().orEmpty()}"
            "multiplayer.disconnect.server_full" -> "서버가 가득 찼어요"
            "multiplayer.disconnect.kicked" -> "관리자가 추방했어요"
            "multiplayer.disconnect.unverified_username" -> "계정 인증에 실패했어요 (정품 인증 서버)"
            "multiplayer.disconnect.outdated_client" -> "클라이언트 버전이 맞지 않아요: ${args.joinToString()}"
            "multiplayer.disconnect.incompatible" -> "호환되지 않는 버전이에요: ${args.joinToString()}"
            "multiplayer.disconnect.name_taken" -> "이미 사용 중인 이름이에요"
            "multiplayer.disconnect.flying" -> "비행이 허용되지 않아 추방됐어요"
            "disconnect.timeout" -> "시간 초과"
            "disconnect.spam" -> "스팸으로 추방됐어요"
            else -> null
        }
        return known
            ?: fallback
            // Advancement names: "advancements.story.enter_the_nether.title" → "enter the nether"
            ?: if (key.startsWith("advancements.") && key.endsWith(".title")) key.split('.').dropLast(1).last().replace('_', ' ') else null
            ?: if (key.startsWith("death.") && args.isNotEmpty()) "☠ ${args.joinToString(" / ")} ($key)"
            else if (args.isEmpty()) key else "$key (${args.joinToString()})"
    }
}

/** Minimal NBT reader: enough to decode text components and to skip anything else. */
internal object Nbt {
    fun readPayload(r: PacketReader, type: Int): Any? = when (type) {
        0 -> null
        1 -> r.byte().toByte()
        2 -> r.short()
        3 -> r.int()
        4 -> r.long()
        5 -> r.float()
        6 -> r.double()
        7 -> r.bytes(r.int())
        8 -> modifiedUtf8(r)
        9 -> {
            val itemType = r.byte()
            val n = r.int()
            List(n.coerceAtLeast(0)) { readPayload(r, itemType) }
        }
        10 -> buildMap {
            while (true) {
                val t = r.byte()
                if (t == 0) break
                val name = modifiedUtf8(r)
                put(name, readPayload(r, t))
            }
        }
        11 -> IntArray(r.int()) { r.int() }
        12 -> LongArray(r.int()) { r.long() }
        else -> throw ProtocolException("Unknown NBT tag $type")
    }

    private fun modifiedUtf8(r: PacketReader): String {
        val len = (r.byte() shl 8) or r.byte()
        // Modified UTF-8 only differs for NUL and supplementary characters; plain UTF-8 decoding is fine for display.
        return String(r.bytes(len), Charsets.UTF_8)
    }

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        else -> JsonNull
    }
}
