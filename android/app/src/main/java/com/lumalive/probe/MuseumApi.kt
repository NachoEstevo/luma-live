package com.lumalive.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class MuseumApi {
    @Volatile private var active: HttpURLConnection? = null
    private var token = ""
    private val base = "http://127.0.0.1:4174"
    fun cancel() { active?.disconnect() }

    private suspend fun request(path: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        active = connection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 45000
            connection.instanceFollowRedirects = false
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Luma-Token", token)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(100_000) } ?: "{}"
            val result = try { JSONObject(body) } catch (_: Exception) { throw IllegalStateException("El backend devolvió una respuesta ilegible") }
            if (status !in 200..299) throw IllegalStateException(result.optString("error", "El backend rechazó la consulta").take(600))
            result
        } finally { if (active === connection) active = null; connection.disconnect() }
    }

    suspend fun check(): JSONObject {
        val config = request("/api/config")
        check(config.optBoolean("metaEnabled")) { "Muse no está habilitado en el backend de esta PC" }
        token = config.getString("token")
        return config
    }

    suspend fun ask(image: String, capturedAt: Long, question: String, history: List<MuseumTurn>): JSONObject {
        check()
        val turns = JSONArray()
        history.forEach { turns.put(JSONObject().put("role", it.role).put("text", it.text)) }
        return request("/api/museum/ask", JSONObject().put("allowPaid", true).put("image", image).put("capturedAt", capturedAt).put("question", question).put("history", turns))
    }

    suspend fun transcribe(wavBase64: String): JSONObject {
        check()
        return request("/api/museum/transcribe", JSONObject().put("allowPaid", true).put("audio", wavBase64))
    }
}
