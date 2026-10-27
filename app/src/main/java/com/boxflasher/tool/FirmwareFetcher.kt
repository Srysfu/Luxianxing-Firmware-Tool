package com.boxflasher.tool

import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 从 CPBox 的阿里云 OSS 抓取固件列表并下载。
 */
class FirmwareFetcher {

    companion object {
        const val DEFAULT_PROJECT = "2872"
        const val DEFAULT_BUCKET = "cpbox"
        const val DEFAULT_REGION = "oss-cn-shenzhen"
    }

    data class Firmware(
        val appver: String,
        val url: String,
        val msg: String,
        val sn: String?,
        val isTest: Boolean,
    )

    data class VersionList(
        val stable: Firmware?,
        val tests: List<Firmware>,
        val rawJson: String,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun versionUrl(project: String): String {
        val p = project.ifBlank { DEFAULT_PROJECT }
        return "https://$DEFAULT_BUCKET.$DEFAULT_REGION.aliyuncs.com/$p/version.json"
    }

    suspend fun fetchVersions(project: String): VersionList = withContext(Dispatchers.IO) {
        val url = versionUrl(project)
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp: Response ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw RuntimeException("empty body")
            parseVersionJson(body)
        }
    }

    private fun parseVersionJson(json: String): VersionList {
        val root = JsonParser.parseString(json).asJsonObject
        val stable = if (root.has("url") && !root.get("url").isJsonNull) {
            Firmware(
                appver = root.get("appver")?.asString ?: "",
                url = root.get("url")?.asString ?: "",
                msg = root.get("msg")?.asString ?: "",
                sn = null,
                isTest = false,
            )
        } else null

        val tests = mutableListOf<Firmware>()
        if (root.has("test") && root.get("test").isJsonArray) {
            for (e in root.get("test").asJsonArray) {
                val o = e.asJsonObject
                tests.add(
                    Firmware(
                        appver = o.get("appver")?.asString ?: "",
                        url = o.get("url")?.asString ?: "",
                        msg = o.get("msg")?.asString ?: "",
                        sn = o.get("sn")?.asString,
                        isTest = true,
                    )
                )
            }
        }
        return VersionList(stable, tests, json)
    }

    suspend fun download(fw: Firmware, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(fw.url).build()
            client.newCall(req).execute().use { resp: Response ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw RuntimeException("empty body")
                val total = body.contentLength()
                var done = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1024 * 64)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            if (n > 0) {
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                }
            }
            dest
        }
}
