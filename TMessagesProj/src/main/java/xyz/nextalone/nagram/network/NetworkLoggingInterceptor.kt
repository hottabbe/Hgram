package xyz.nextalone.nagram.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.telegram.messenger.BuildVars
import tw.nekomimi.nekogram.database.NetworkLogItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NetworkLoggingInterceptor {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sensitiveHeaders = setOf(
        "authorization",
        "x-api-key",
        "x-goog-api-key",
        "x-authorization",
        "x-hgram-redact-body"
    )

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = false
                })
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }

            engine {
                config {
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    suspend fun executeWithLogging(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        requestParams: Map<String, String> = emptyMap(),
        block: suspend HttpClient.() -> HttpResponse
    ): HttpResponse {
        if (!BuildVars.LOGS_ENABLED) {
            return client.block()
        }

        val timestamp = System.currentTimeMillis()
        val redactBody = headers.keys.any { it.equals("X-Hgram-Redact-Body", true) }
        var statusCode = 0
        var responseTime = 0L
        var responseHeaders = ""
        var responseBody = ""
        var errorMessage: String? = null

        val startTime = System.currentTimeMillis()

        try {
            val response = client.block()

            responseTime = System.currentTimeMillis() - startTime
            statusCode = response.status.value

            responseHeaders = response.headers.entries().joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }

            if (redactBody) {
                responseBody = "[REDACTED]"
            } else {
            try {
                responseBody = response.bodyAsText()
                if (responseBody.length > 5000) {
                    responseBody = responseBody.substring(0, 5000) + "\n... (truncated)"
                }
            } catch (e: Exception) {
                responseBody = "Unable to read response body: ${e.message}"
            }
            }

            return response

        } catch (e: Exception) {
            errorMessage = if (redactBody) "[REDACTED]" else e.message ?: "Unknown error"
            responseTime = System.currentTimeMillis() - startTime
            throw e

        } finally {
            val headersString = headers.entries.joinToString("\n") { (k, v) ->
                if (k.lowercase(Locale.ROOT) in sensitiveHeaders) "$k: [REDACTED]" else "$k: $v"
            }
            val paramsString = requestParams.entries.joinToString("&") { "${it.key}=${it.value}" }

            val logItem = NetworkLogItem(
                timestamp,
                method,
                url,
                statusCode,
                responseTime,
                headersString,
                if (redactBody) "[REDACTED]" else requestBody ?: "",
                paramsString,
                responseHeaders,
                responseBody,
                errorMessage
            )

            NetworkLogDb.save(logItem)
        }
    }

    @JvmStatic
    fun getStatusCodeColor(statusCode: Int): Int {
        return when {
            statusCode in 200..299 -> 0xFF4CAF50.toInt()
            statusCode in 300..399 -> 0xFFFFC107.toInt()
            statusCode in 400..499 -> 0xFFFF9800.toInt()
            statusCode in 500..599 -> 0xFFF44336.toInt()
            statusCode != 0 -> 0xFF9E9E9E.toInt()
            else -> 0xFF757575.toInt()
        }
    }

    @JvmStatic
    fun getMethodColor(method: String): Int {
        return when (method.uppercase()) {
            "GET" -> 0xFF2196F3.toInt()
            "POST" -> 0xFF4CAF50.toInt()
            "PUT" -> 0xFFFF9800.toInt()
            "DELETE" -> 0xFFF44336.toInt()
            "PATCH" -> 0xFF9C27B0.toInt()
            "HEAD" -> 0xFF00BCD4.toInt()
            "OPTIONS" -> 0xFF607D8B.toInt()
            else -> 0xFF9E9E9E.toInt()
        }
    }

    @JvmStatic
    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    @JvmStatic
    fun formatResponseTime(timeMs: Long): String {
        return when {
            timeMs < 1000 -> "${timeMs}ms"
            timeMs < 60000 -> String.format(Locale.getDefault(), "%.1fs", timeMs / 1000.0)
            else -> String.format(Locale.getDefault(), "%.1fm", timeMs / 60000.0)
        }
    }
}
