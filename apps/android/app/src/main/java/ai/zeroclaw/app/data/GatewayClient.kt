package ai.zeroclaw.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GatewayException(
    val status: Int,
    message: String,
) : IOException(message)

/**
 * HTTP surface of the ZeroClaw gateway used by the app: pairing, agents,
 * session history and turn cancellation. Chat streaming runs over
 * [ChatSocket]; everything else here is request/response.
 */
class GatewayClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        runCatching {
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun pair(baseUrl: String, code: String, deviceName: String): String =
        withContext(Dispatchers.IO) {
            val payload = zcJson.encodeToString(
                PairRequest.serializer(),
                PairRequest(code = code.trim(), deviceName = deviceName),
            )
            val request = Request.Builder()
                .url("$baseUrl/api/pair")
                .post(payload.toRequestBody(jsonMedia))
                .build()
            val body = execute(request)
            zcJson.decodeFromString(PairResponse.serializer(), body).token
        }

    suspend fun agents(baseUrl: String, token: String): List<AgentEntry> =
        withContext(Dispatchers.IO) {
            val body = execute(get("$baseUrl/api/agents/available", token))
            val root = zcJson.parseToJsonElement(body)
            val agents = root.objOrNull()?.get("agents") as? JsonArray ?: return@withContext emptyList()
            agents.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val alias = obj["alias"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNullSafe()?.toBooleanStrictOrNull() ?: true
                AgentEntry(alias = alias, enabled = enabled)
            }
        }

    suspend fun sessions(baseUrl: String, token: String): List<SessionEntry> =
        withContext(Dispatchers.IO) {
            val body = execute(get("$baseUrl/api/sessions", token))
            parseSessions(body).sortedByDescending { it.lastActivity }
        }

    suspend fun messages(baseUrl: String, token: String, sessionId: String): MessagesResponse =
        withContext(Dispatchers.IO) {
            val body = execute(
                get("$baseUrl/api/sessions/${encodePath(sessionId)}/messages", token),
            )
            zcJson.decodeFromString(MessagesResponse.serializer(), body)
        }

    suspend fun abort(baseUrl: String, token: String, sessionId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                execute(
                    Request.Builder()
                        .url("$baseUrl/api/sessions/${encodePath(sessionId)}/abort")
                        .post(ByteArray(0).toRequestBody(null))
                        .header("Authorization", "Bearer $token")
                        .build(),
                )
            }
        }
    }

    private fun get(url: String, token: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GatewayException(
                    status = response.code,
                    message = gatewayErrorMessage(body, response.code),
                )
            }
            return body
        }
    }

    private fun gatewayErrorMessage(body: String, status: Int): String {
        val parsed = runCatching { zcJson.parseToJsonElement(body) }.getOrNull()
        val obj = parsed as? JsonObject
        val message = obj?.get("message")?.jsonPrimitive?.contentOrNullSafe()
            ?: obj?.get("error")?.jsonPrimitive?.contentOrNullSafe()
        val code = obj?.get("code")?.jsonPrimitive?.contentOrNullSafe()
        return when {
            !message.isNullOrBlank() && !code.isNullOrBlank() -> "$message ($code)"
            !message.isNullOrBlank() -> message
            body.isNotBlank() && body.length < 300 -> body
            else -> "HTTP $status"
        }
    }

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Small helper used by the view model to keep call sites tidy. */
fun List<MessageRow>.toChatMessages(): List<ChatMessage> = mapNotNull { row ->
    val role = when (row.role.lowercase()) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        else -> MessageRole.System
    }
    if (row.content.isBlank()) {
        null
    } else {
        ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = role,
            text = row.content,
        )
    }
}

/** Parse helper shared with tests: accepts a bare array or `{sessions:[...]}`. */
fun parseSessions(body: String): List<SessionEntry> {
    val root = zcJson.parseToJsonElement(body)
    val array = when (root) {
        is JsonArray -> root
        is JsonObject -> root["sessions"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return array.mapNotNull { element ->
        runCatching { zcJson.decodeFromJsonElement(SessionEntry.serializer(), element) }.getOrNull()
    }
}
