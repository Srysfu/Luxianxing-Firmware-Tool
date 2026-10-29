package com.boxflasher.tool

import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 从 CPBox 的阿里云 OSS 抓取固件列表并下载。
 *
 * OSS 桶禁止列目录（ListObjects 403），因此全项目抓取依赖已知项目号列表（探测所得）。
 * 国内桶只含国内项目，海外桶为超集；每个项目号记录了其所属桶。
 */
class FirmwareFetcher {

    companion object {
        const val DEFAULT_PROJECT = "2872"
        const val BUCKET_CN = "cpbox.oss-cn-shenzhen.aliyuncs.com"
        const val BUCKET_ABROAD = "cpbox-abroad.oss-us-west-1.aliyuncs.com"

        /** 国内项目号 */
        private val PROJECTS_CN = listOf(
            "2344", "2517", "2601", "2602", "2617", "2651",
            "2701", "2705", "2720", "2723", "2726", "2732", "2734", "2761",
            "2801", "2821", "2850", "2859",
            "2860", "2861", "2862", "2863", "2865", "2866", "2867", "2868", "2869",
            "2870", "2871", "2872", "2873",
            "2882", "2883", "2884", "2885", "2886", "2887", "2888", "2889",
            "2893", "2894", "2901", "2921", "3075", "3076"
        )

        /** 海外项目号 */
        private val PROJECTS_ABROAD = listOf(
            "2345", "2348", "2539", "2561", "2562", "2563", "2661",
            "2702", "2703", "2704", "2710", "2711", "2712", "2713",
            "2724", "2725", "2727", "2730", "2733", "2736", "2737", "2738",
            "2740", "2743", "2744", "2745",
            "2762", "2763", "2767", "2768", "2770", "2771", "2772", "2773", "2774", "2775", "2780",
            "2800", "2802", "2803", "2804", "2806", "2807", "2808", "2809",
            "2810", "2811", "2812", "2813", "2815", "2816", "2817", "2818", "2819", "2820",
            "2822", "2823", "2824", "2825", "2826", "2827", "2828", "2829",
            "2830", "2831", "2832", "2833", "2834", "2835", "2836", "2837", "2838", "2839",
            "2840", "2841", "2842", "2844", "2845", "2846", "2847", "2848", "2849",
            "2851", "2852", "2854", "2855", "2857", "2858", "2864", "2895", "2896",
            "2903", "2904", "2922", "2923", "2924", "2925", "2926", "2927", "2928", "2929",
            "2931", "2932", "2933", "2934", "2935", "2936", "2937", "2938",
            "2940", "2943", "2944", "2945", "2946", "2947", "2948", "2949",
            "2951", "2952", "2953", "2955", "3001", "3002", "3003", "3072"
        )

        /** 项目号 -> 桶 */
        val PROJECT_BUCKET: Map<String, String> =
            PROJECTS_CN.associateWith { BUCKET_CN } + PROJECTS_ABROAD.associateWith { BUCKET_ABROAD }

        val ALL_PROJECTS: List<String> = PROJECT_BUCKET.keys.sorted()
    }

    data class Firmware(
        val project: String,
        val appver: String,
        val url: String,
        val msg: String,
        val sn: String?,
        val isTest: Boolean,
    )

    data class VersionList(
        val project: String,
        val stable: Firmware?,
        val tests: List<Firmware>,
        val rawJson: String,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun versionUrl(bucket: String, project: String): String =
        "https://$bucket/$project/version.json"

    /** 用户手输项目号：先国内桶，404 再试海外桶。 */
    suspend fun fetchVersions(project: String): VersionList = withContext(Dispatchers.IO) {
        val p = project.ifBlank { DEFAULT_PROJECT }
        val bucket = PROJECT_BUCKET[p] ?: BUCKET_CN
        try {
            fetchFromBucket(bucket, p)
        } catch (e: Exception) {
            if (bucket == BUCKET_CN) fetchFromBucket(BUCKET_ABROAD, p) else throw e
        }
    }

    private fun fetchFromBucket(bucket: String, project: String): VersionList {
        val req = Request.Builder().url(versionUrl(bucket, project)).build()
        client.newCall(req).execute().use { resp: Response ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw RuntimeException("empty body")
            return parseVersionJson(project, body)
        }
    }

    /**
     * 并发抓取所有已知项目的固件。
     * @param onProgress 每完成一个项目回调一次（项目号，已完成数，总数）
     */
    suspend fun fetchAllProjects(
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Firmware> = withContext(Dispatchers.IO) {
        val projects = ALL_PROJECTS
        val total = projects.size
        val semaphore = Semaphore(10)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val all = coroutineScope {
            projects.map { project ->
                async {
                    semaphore.withPermit {
                        val list = try {
                            val bucket = PROJECT_BUCKET[project] ?: BUCKET_CN
                            fetchFromBucket(bucket, project)
                        } catch (e: Exception) {
                            null
                        }
                        val n = doneCount.incrementAndGet()
                        onProgress(project, n, total)
                        list
                    }
                }
            }.awaitAll()
        }
        val result = mutableListOf<Firmware>()
        all.forEach { list ->
            list?.stable?.let { result.add(it) }
            list?.tests?.let { result.addAll(it) }
        }
        result.sortedWith(compareBy({ it.project }, { it.isTest }))
    }

    private fun parseVersionJson(project: String, json: String): VersionList {
        val root = JsonParser.parseString(json).asJsonObject
        val stable = if (root.has("url") && !root.get("url").isJsonNull) {
            Firmware(
                project = project,
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
                        project = project,
                        appver = o.get("appver")?.asString ?: "",
                        url = o.get("url")?.asString ?: "",
                        msg = o.get("msg")?.asString ?: "",
                        sn = o.get("sn")?.asString,
                        isTest = true,
                    )
                )
            }
        }
        return VersionList(project, stable, tests, json)
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
