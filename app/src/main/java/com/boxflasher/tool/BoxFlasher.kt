package com.boxflasher.tool

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 盒子刷机执行器。
 */
class BoxFlasher(private val host: String = "192.168.1.101") {

    companion object {
        const val DEFAULT_HOST = "192.168.1.101"
    }

    data class BoxInfo(
        val update: Int,
        val name: String?,
        val appver: String?,
        val raw: String,
    )

    data class FlashState(
        val update: Int,
        val name: String?,
        val appver: String?,
        val done: Boolean,
        val success: Boolean,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val base = "http://$host"

    fun queryBoxInfo(): BoxInfo {
        return queryBoxInfoSync()
    }

    private fun queryBoxInfoSync(): BoxInfo {
        val req = Request.Builder().url("$base/cgi-bin/index.cgi?id=host").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw RuntimeException("empty body")
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            return parseBoxInfo(body)
        }
    }

    suspend fun queryBoxInfoAsync(): BoxInfo = withContext(Dispatchers.IO) {
        queryBoxInfoSync()
    }

    private fun parseBoxInfo(body: String): BoxInfo {
        return try {
            val o = JSONObject(body)
            val update = o.optInt("update", -1)
            val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" }
            val appver = o.optJSONObject("sys")?.optString("appver")
            BoxInfo(update, name, appver, body)
        } catch (e: Exception) {
            BoxInfo(-1, null, null, body)
        }
    }

    suspend fun upload(file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String =
        withContext(Dispatchers.IO) {
            val mime = "application/octet-stream".toMediaType()
            val fileBody = object : okhttp3.RequestBody() {
                override fun contentType() = mime
                override fun contentLength() = file.length()
                override fun writeTo(sink: okio.BufferedSink) {
                    file.inputStream().use { input ->
                        val buf = ByteArray(1024 * 64)
                        var done = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            if (n > 0) {
                                sink.write(buf, 0, n)
                                done += n
                                onProgress(done, file.length())
                            }
                        }
                    }
                }
            }

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "update.img", fileBody)
                .build()

            val req = Request.Builder()
                .url("$base/cgi-bin/index.cgi?id=upload")
                .post(multipart)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                body
            }
        }

    suspend fun rebootAsync(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/cgi-bin/index.cgi?id=set&rst=1")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
            body
        }
    }

    suspend fun pollUntilFinished(
        maxWaitSeconds: Int = 120,
        intervalMillis: Long = 3000,
        onState: (FlashState) -> Unit,
    ): FlashState = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L
        var last = FlashState(-1, null, null, false, false)
        while (System.currentTimeMillis() < deadline) {
            val info = queryBoxInfoSync()
            val state = FlashState(
                update = info.update,
                name = info.name,
                appver = info.appver,
                done = info.update == 3 || info.update == 4 || info.update == 5,
                success = info.update == 3,
            )
            last = state
            onState(state)
            if (state.done) return@withContext state
            kotlinx.coroutines.delay(intervalMillis)
        }
        last
    }
}
