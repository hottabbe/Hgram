package tw.nekomimi.nekogram.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.telegram.messenger.FileLog
import org.telegram.tgnet.ConnectionsManager
import tw.nekomimi.nekogram.NekoConfig
import xyz.nextalone.nagram.network.NetworkRequestBuilder
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

object DnsFactory {
    private const val STATUS_NOERROR = 0
    private const val STATUS_NXDOMAIN = 3

    fun providers() = if (NekoConfig.customDoH.String().isNotBlank()) arrayOf(
        NekoConfig.customDoH.String())
    else arrayOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://[2606:4700:4700::1111]/dns-query",
        "https://[2606:4700:4700::1001]/dns-query",
        "https://8.8.8.8/resolve",
    )

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val txtCache = ConcurrentHashMap<String, List<String>>()

    class CustomException(message: String) : Exception(message)

    @JvmStatic
    @JvmOverloads
    fun lookup(domain: String, fallback: Boolean = false): List<InetAddress> {

        if (!NekoConfig.useSystemDNS.Bool()) {

            FileLog.d("Lookup $domain")

            ConnectionsManager.getIpStrategy()

            val cached = cache[domain]
            if (cached != null) {
                FileLog.d("DNS cache hit for $domain: $cached")
                return cached
            }

            val noFallback = ConnectionsManager.hasIpv4 || ConnectionsManager.hasIpv6
            val recordType = if (noFallback) {
                if (ConnectionsManager.hasIpv4) "A" else "AAAA"
            } else if (!fallback) "A" else "AAAA"

            val providerList = providers()
            val counterAll = AtomicInteger(0)
            val resolved = AtomicBoolean(false)

            val ret = runBlocking {
                val jobs = CopyOnWriteArrayList<Job>()
                val ret: List<InetAddress>? = suspendCancellableCoroutine { cont ->
                    for (provider in providerList) {
                        val job = launch(Dispatchers.IO) {
                            try {
                                val response = makeDohJsonRequest(provider, domain, recordType)

                                if (response.statusCode !in 200..299) {
                                    throw CustomException("$provider not successful: ${response.statusCode}")
                                }

                                val jsonResponse = JSONObject(response.body)
                                val status = jsonResponse.optInt("Status", -1)

                                if (status != STATUS_NOERROR && status != STATUS_NXDOMAIN) {
                                    throw CustomException("$provider DNS error: status=$status")
                                }

                                if (status == STATUS_NXDOMAIN) {
                                    throw CustomException("$provider NXDOMAIN")
                                }

                                val answers = jsonResponse.optJSONArray("Answer")
                                if (answers == null || answers.length() == 0) {
                                    if (!noFallback && !fallback) {
                                        val aaaaResult = lookup(domain, true)
                                        if (aaaaResult.isNotEmpty() && resolved.compareAndSet(false, true)) {
                                            cache[domain] = aaaaResult
                                            jobs.forEach { it.cancel() }
                                            cont.resume(aaaaResult)
                                        }
                                    }
                                    throw CustomException("$provider no answer")
                                }

                                val addresses = mutableListOf<InetAddress>()
                                for (i in 0 until answers.length()) {
                                    val answer = answers.getJSONObject(i)
                                    val data = answer.optString("data", "")
                                    if (data.isNotBlank()) {
                                        try {
                                            addresses.add(InetAddress.getByName(data))
                                        } catch (e: Exception) {
                                            FileLog.w("Failed to parse address: $data")
                                        }
                                    }
                                }

                                if (addresses.isNotEmpty() && resolved.compareAndSet(false, true)) {
                                    cache[domain] = addresses
                                    FileLog.d("DNS Result $domain: $addresses")
                                    jobs.forEach { it.cancel() }
                                    cont.resume(addresses)
                                }
                            } catch (e: Exception) {
                                if (e is CustomException) {
                                    FileLog.e(e)
                                } else {
                                    FileLog.w(e.stackTraceToString())
                                }
                            }
                            if (counterAll.incrementAndGet() == providerList.size && resolved.compareAndSet(false, true)) {
                                jobs.forEach { it.cancel() }
                                cont.resume(null)
                            }
                        }
                        jobs.add(job)
                    }

                    val timeoutJob = launch {
                        delay(5000L)
                        if (resolved.compareAndSet(false, true)) {
                            jobs.forEach { it.cancel() }
                            cont.resume(null)
                    }
                }
                    jobs.add(timeoutJob)
                }

                ret
            }

            if (ret != null) return ret
        }

        FileLog.d("Try system dns to resolve $domain")

        try {
            return InetAddress.getAllByName(domain).toList()
        } catch (e: Exception) {
            FileLog.d("System dns fail: ${e.message ?: e.javaClass.simpleName}")
        }

        return listOf()
    }

    @JvmStatic
    fun getTxts(domain: String): List<String> {

        FileLog.d("Lookup $domain for txts")

        val cached = txtCache[domain]
        if (cached != null) {
            FileLog.d("TXT cache hit for $domain: $cached")
            return cached
        }

        val providerList = providers()
        val counterAll = AtomicInteger(0)
        val resolved = AtomicBoolean(false)

        return runBlocking {
            val jobs = CopyOnWriteArrayList<Job>()
            val ret: List<String> = suspendCancellableCoroutine { cont ->
                for (provider in providerList) {
                    val job = launch(Dispatchers.IO) {
                        try {
                            val response = makeDohJsonRequest(provider, domain, "TXT")

                            if (response.statusCode !in 200..299) {
                                throw CustomException("$provider not successful: ${response.statusCode}")
                            }

                            val jsonResponse = JSONObject(response.body)
                            val status = jsonResponse.optInt("Status", -1)

                            if (status != STATUS_NOERROR && status != STATUS_NXDOMAIN) {
                                throw CustomException("$provider DNS error: status=$status")
                            }

                            if (status == STATUS_NXDOMAIN) {
                                throw CustomException("$provider NXDOMAIN")
                            }

                            val answers = jsonResponse.optJSONArray("Answer")
                            if (answers == null || answers.length() == 0) {
                                throw CustomException("$provider no TXT answer")
                            }

                            val txts = mutableListOf<String>()
                            for (i in 0 until answers.length()) {
                                val answer = answers.getJSONObject(i)
                                val data = answer.optString("data", "")
                                if (data.isNotBlank()) {
                                    val cleanData = cleanTxtData(data)
                                    if (cleanData.isNotBlank()) {
                                        txts.add(cleanData)
                                    }
                                }
                            }

                            if (txts.isNotEmpty() && resolved.compareAndSet(false, true)) {
                                txtCache[domain] = txts
                                FileLog.d("TXT Result $domain: $txts")
                                jobs.forEach { it.cancel() }
                                cont.resume(txts)
                            }
                        } catch (e: Exception) {
                            if (e is CustomException) {
                                FileLog.e(e)
                            } else {
                                FileLog.w(e.stackTraceToString())
                            }
                        }
                        if (counterAll.incrementAndGet() == providerList.size && resolved.compareAndSet(false, true)) {
                            jobs.forEach { it.cancel() }
                            cont.resume(listOf())
                        }
                    }
                    jobs.add(job)
                }

                val timeoutJob = launch {
                    delay(5000L)
                    if (resolved.compareAndSet(false, true)) {
                        jobs.forEach { it.cancel() }
                        cont.resume(listOf())
                    }
                }
                jobs.add(timeoutJob)
            }

            ret
        }
    }

    private fun makeDohJsonRequest(provider: String, name: String, type: String): xyz.nextalone.nagram.network.NetworkResponse {
        return NetworkRequestBuilder.get(provider) {
            header("accept", "application/dns-json")
            parameter("name", name)
            parameter("type", type)
        }.execute()
    }

    @JvmStatic
    fun clearCache() {
        cache.clear()
        txtCache.clear()
    }

    @JvmStatic
    fun probe(endpoint: String): Boolean {
        return try {
            val separator = if (endpoint.contains("?")) "&" else "?"
            val url = java.net.URL(endpoint + separator + "name=example.com&type=A")
            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 2200
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/dns-json")
            }
            try {
                if (connection.responseCode !in 200..299) return false
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                org.json.JSONObject(body).optInt("Status", -1) == STATUS_NOERROR
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            FileLog.w("DoH probe failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun cleanTxtData(data: String): String {
        var result = data.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        result = result.replace("\\\"", "\"")
        result = result.replace("\\\\", "\\")
        return result
    }
}
