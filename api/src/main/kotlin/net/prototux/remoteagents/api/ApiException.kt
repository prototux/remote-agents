package net.prototux.remoteagents.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/**
 * An error answer from the server. Bodies come in two shapes: `{"error":"text"}` (most routes, observed
 * on 0.10.4) and `{"error":{"code","message"}}` (auth and `/info`). Both are read.
 */
open class ApiException(val httpStatus: Int, val code: String?, message: String) : IOException(message) {
    companion object {
        fun from(httpStatus: Int, body: String?): ApiException {
            val json = body?.let { runCatching { ApiJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            var code: String? = null
            val message = when (val e = json?.get("error")) {
                is JsonPrimitive -> e.content
                is JsonObject -> { code = e.str("code"); e.str("message") ?: code ?: "HTTP $httpStatus" }
                else -> body?.take(200)?.ifBlank { null } ?: "HTTP $httpStatus"
            }
            return if (httpStatus == 401) AuthFailedException(message) else ApiException(httpStatus, code, message)
        }
    }
}

/** 401: the token was rotated or is wrong. The client must stop retrying and ask the owner to re-pair. */
class AuthFailedException(message: String = "Unauthorized") : ApiException(401, "auth_failed", message)
