package com.aitrader.bridge

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Cliente autenticado da API LS Broker. */
object BridgeApi {
    data class Response(val status: Int, val body: String) {
        fun json(): JSONObject = try { if (body.isBlank()) JSONObject() else JSONObject(body) } catch (_: Exception) { JSONObject() }
        fun errorMessage(): String {
            val j = json()
            return j.optString("message").takeIf { it.isNotBlank() }
                ?: j.optString("error").takeIf { it.isNotBlank() }
                ?: "Falha HTTP $status"
        }
    }

    fun request(baseUrl: String, method: String, path: String, token: String? = null, payload: JSONObject? = null): Response {
        val base = baseUrl.trim().trimEnd('/')
        val url = URL((if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base") + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 35000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                conn.doOutput = true
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } } ?: ""
            return Response(code, body)
        } finally { conn.disconnect() }
    }

    fun get(baseUrl: String, path: String, token: String) = request(baseUrl, "GET", path, token)
    fun post(baseUrl: String, path: String, token: String?, payload: JSONObject) = request(baseUrl, "POST", path, token, payload)
    fun put(baseUrl: String, path: String, token: String, payload: JSONObject) = request(baseUrl, "PUT", path, token, payload)
}
