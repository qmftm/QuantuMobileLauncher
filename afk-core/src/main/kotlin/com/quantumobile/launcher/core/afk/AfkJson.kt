package com.quantumobile.launcher.core.afk

import kotlinx.serialization.json.Json

/** JSON settings for AFK files and the remote protocol: tolerant of fields added by newer versions. */
val AfkJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}
