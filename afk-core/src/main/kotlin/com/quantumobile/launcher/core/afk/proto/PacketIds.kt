package com.quantumobile.launcher.core.afk.proto

// 764–775 generated from PrismarineJS/minecraft-data (data/pc/<version>/protocol.json); 776–777 from the vanilla server's
// own report (`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports` → reports/packets.json), which
// also matches every row from 1.21 on. -1 = the packet does not exist in that version.
// To add a version: take the same packets from its packets.json and raise [PacketIds.NEWEST_KNOWN].

/** Packet IDs (configuration and play states) the AFK client needs, for one protocol version. */
@Suppress("LongParameterList")
class PacketIds(
    val protocol: Int,
    val versions: String,
    val configCookieRequest: Int,
    val configDisconnect: Int,
    val configFinish: Int,
    val configKeepAlive: Int,
    val configPing: Int,
    val configResourcePack: Int,
    val configKnownPacks: Int,
    val configCodeOfConduct: Int,
    val configCookieResponseOut: Int,
    val configFinishOut: Int,
    val configKeepAliveOut: Int,
    val configPongOut: Int,
    val configResourcePackOut: Int,
    val configKnownPacksOut: Int,
    val configAcceptCodeOfConductOut: Int,
    val playKeepAlive: Int,
    val playPing: Int,
    val playDisconnect: Int,
    val playResourcePack: Int,
    val playStartConfiguration: Int,
    val playHealth: Int,
    val playPosition: Int,
    val playCookieRequest: Int,
    val playLogin: Int,
    val playTeleportConfirmOut: Int,
    val playClientCommandOut: Int,
    val playKeepAliveOut: Int,
    val playPongOut: Int,
    val playResourcePackOut: Int,
    val playEntityActionOut: Int,
    val playInputOut: Int,
    val playConfigurationAckOut: Int,
    val playLoadedOut: Int,
    val playCookieResponseOut: Int,
    val playSystemChat: Int,
    val playPlayerChat: Int,
    val playDisguisedChat: Int,
) {
    companion object {
        /** Oldest protocol with a configuration state (1.20.2); older servers are not supported. */
        const val OLDEST = 764
        const val NEWEST_KNOWN = 777

        private val table: Map<Int, PacketIds> = listOf(
            PacketIds(764, "1.20.2", -1, 1, 2, 3, 4, 6, -1, -1, -1, 2, 3, 4, 5, -1, -1, 36, 51, 27, 66, 101, 89, 62, -1, 41, 0, 8, 20, 35, 39, 33, -1, 11, -1, -1, 103, 55, 28),
            PacketIds(765, "1.20.3–1.20.4", -1, 1, 2, 3, 4, 7, -1, -1, -1, 2, 3, 4, 5, -1, -1, 36, 51, 27, 68, 103, 91, 62, -1, 41, 0, 8, 21, 36, 40, 34, -1, 11, -1, -1, 105, 55, 28),
            PacketIds(766, "1.20.5–1.20.6", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 38, 53, 29, 70, 105, 93, 64, 22, 43, 0, 9, 24, 39, 43, 37, -1, 12, -1, 17, 108, 57, 30),
            PacketIds(767, "1.21–1.21.1", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 38, 53, 29, 70, 105, 93, 64, 22, 43, 0, 9, 24, 39, 43, 37, -1, 12, -1, 17, 108, 57, 30),
            PacketIds(768, "1.21.2–1.21.3", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 39, 55, 29, 75, 112, 98, 66, 22, 44, 0, 10, 26, 41, 45, 39, 40, 14, -1, 19, 115, 59, 30),
            PacketIds(769, "1.21.4", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 39, 55, 29, 75, 112, 98, 66, 22, 44, 0, 10, 26, 43, 47, 40, 41, 14, 42, 19, 115, 59, 30),
            PacketIds(770, "1.21.5", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 38, 54, 28, 74, 111, 97, 65, 21, 43, 0, 10, 26, 43, 47, 40, 41, 14, 42, 19, 114, 58, 29),
            PacketIds(771, "1.21.6", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 38, 54, 28, 74, 111, 97, 65, 21, 43, 0, 11, 27, 44, 48, 41, 42, 15, 43, 20, 114, 58, 29),
            PacketIds(772, "1.21.7–1.21.8", 0, 2, 3, 4, 5, 9, 14, -1, 1, 3, 4, 5, 6, 7, -1, 38, 54, 28, 74, 111, 97, 65, 21, 43, 0, 11, 27, 44, 48, 41, 42, 15, 43, 20, 114, 58, 29),
            PacketIds(773, "1.21.9–1.21.10", 0, 2, 3, 4, 5, 9, 14, 19, 1, 3, 4, 5, 6, 7, 9, 43, 59, 32, 79, 116, 102, 70, 21, 48, 0, 11, 27, 44, 48, 41, 42, 15, 43, 20, 119, 63, 33),
            PacketIds(774, "1.21.11", 0, 2, 3, 4, 5, 9, 14, 19, 1, 3, 4, 5, 6, 7, 9, 43, 59, 32, 79, 116, 102, 70, 21, 48, 0, 11, 27, 44, 48, 41, 42, 15, 43, 20, 119, 63, 33),
            PacketIds(775, "26.1", 0, 2, 3, 4, 5, 9, 14, 19, 1, 3, 4, 5, 6, 7, 9, 44, 61, 32, 81, 118, 104, 72, 21, 49, 0, 12, 28, 45, 49, 42, 43, 16, 44, 21, 121, 65, 33),
            PacketIds(776, "26.2", 0, 2, 3, 4, 5, 9, 14, 19, 1, 3, 4, 5, 6, 7, 9, 44, 61, 32, 81, 118, 104, 72, 21, 49, 0, 12, 28, 45, 49, 42, 43, 16, 44, 21, 121, 65, 33),
            PacketIds(777, "26.3", 0, 2, 3, 4, 5, 9, 15, 20, 1, 3, 4, 5, 6, 7, 9, 45, 62, 32, 82, 120, 106, 73, 21, 50, 0, 12, 28, 45, 50, 42, 43, 16, 44, 21, 124, 66, 33),
        ).associateBy { it.protocol }

        /** Exact table for [protocol], or null when this version is not known. */
        fun exact(protocol: Int): PacketIds? = table[protocol]

        /** Releases newer than [NEWEST_KNOWN] are tried with the newest table (packet IDs often stay the same). */
        fun forProtocol(protocol: Int): PacketIds? = when {
            protocol < OLDEST -> null
            protocol >= SNAPSHOT_BIT -> null
            protocol > NEWEST_KNOWN -> table.getValue(NEWEST_KNOWN)
            else -> table[protocol]
        }

        /** Snapshot protocol numbers have this bit set. */
        const val SNAPSHOT_BIT = 0x40000000
    }
}
