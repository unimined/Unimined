package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.gson.JsonObject

data class McpConfigData(
    val mappingsPath: String,
    val official: Boolean,
    val steps: Map<String, List<McpConfigStep>>,
    val functions: Map<String, McpConfigFunction>
) {
    companion object {
        fun fromJson(json: JsonObject): McpConfigData {
            val specVersion = if (json.has("spec")) json.getAsJsonPrimitive("spec").asInt else 0
            val isSpec6 = specVersion >= 6

            val dataObj = json.getAsJsonObject("data")
            val mappingsValue = dataObj?.get("mappings")
            val mappingsPath = when {
                mappingsValue == null -> ""
                mappingsValue.isJsonObject ->
                    mappingsValue.asJsonObject["joined"]?.asString
                        ?: mappingsValue.asJsonObject.entrySet().firstOrNull()?.value?.asString ?: ""
                else -> mappingsValue.asString
            }

            val official = json.has("official") && json.getAsJsonPrimitive("official").asBoolean
            val stepsJson = json.getAsJsonObject("steps")
            val stepsBuilder = ImmutableMap.builder<String, List<McpConfigStep>>()
            for (key in stepsJson.keySet()) {
                val stepListBuilder = ImmutableList.builder<McpConfigStep>()
                for (child in stepsJson.getAsJsonArray(key)) {
                    stepListBuilder.add(McpConfigStep.fromJson(child.asJsonObject))
                }
                stepsBuilder.put(key, stepListBuilder.build())
            }
            val functionsJson = json.getAsJsonObject("functions")
            val functionsBuilder = ImmutableMap.builder<String, McpConfigFunction>()
            for (key in functionsJson.keySet()) {
                functionsBuilder.put(key, McpConfigFunction.fromJson(functionsJson.getAsJsonObject(key), isSpec6))
            }
            return McpConfigData(mappingsPath, official, stepsBuilder.build(), functionsBuilder.build())
        }
    }
}