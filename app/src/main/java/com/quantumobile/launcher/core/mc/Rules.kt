package com.quantumobile.launcher.core.mc

import com.quantumobile.launcher.core.obj
import com.quantumobile.launcher.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Environment used to evaluate the `rules` blocks in version JSON files. Android is treated as Linux. */
data class RuleEnv(
    val osName: String = "linux",
    val arch: String = "arm64",
    val features: Map<String, Boolean> = emptyMap(),
)

object Rules {
    /** Mojang semantics: no rules → allowed; otherwise the last matching rule wins, default deny. */
    fun isAllowed(rules: JsonArray?, env: RuleEnv): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        for (element in rules) {
            val rule = element as? JsonObject ?: continue
            if (matches(rule, env)) allowed = rule.str("action") == "allow"
        }
        return allowed
    }

    private fun matches(rule: JsonObject, env: RuleEnv): Boolean {
        rule.obj("os")?.let { os ->
            os.str("name")?.let { if (it != env.osName) return false }
            os.str("arch")?.let { if (it != env.arch) return false }
        }
        rule.obj("features")?.let { features ->
            for ((key, value) in features) {
                val expected = (value as? JsonPrimitive)?.booleanOrNull ?: return false
                if ((env.features[key] ?: false) != expected) return false
            }
        }
        return true
    }
}
