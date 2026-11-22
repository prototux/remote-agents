package net.prototux.remoteagents.api

import kotlinx.serialization.json.JsonObject

object Fixtures {
    fun lines(name: String): List<String> =
        javaClass.classLoader.getResource(name)!!.readText().lines().filter { it.isNotBlank() }

    fun messages(name: String): List<AgentMessage> =
        lines(name).map { AgentMessage.parse(ApiJson.parseToJsonElement(it) as JsonObject) }
}
