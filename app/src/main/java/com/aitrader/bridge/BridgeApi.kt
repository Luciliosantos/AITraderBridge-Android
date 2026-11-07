package com.aitrader.bridge

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP mínimo da ponte (sem dependências externas). */
object BridgeApi {

    data class Response(val status: Int, val body: String) {
        fun json(): JSONObject = try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (e: Exception) {
            JSONObject()
        }

        fun errorMessage(): String {
            val fromBody = json().optString("error", "")
            if (fromBody.isNotBlank()) return fromBody
            return when (status) {
                401 -> "Token do dispositivo inválido. Pareie novamente."
                404 -> "Código inválido ou já utilizado."
                409 -> "Sequência repetida — reenviando com número novo."
                410 -> "Código expirado. Gere outro no painel."
                422 -> "Dados rejeitados pela validação."
                else -> "Falha HTTP $status"
            }
        }
    }

    private fun normalizeBase(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    fun post(baseUrl: String, path: String, token: String?, payload: JSONObject): Response {
        val url = URL(normalizeBase(baseUrl) + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")

            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            return Response(code, body)
        } finally {
            conn.disconnect()
        }
    }
}