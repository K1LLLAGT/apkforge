package com.gt.apkforge

import org.json.JSONArray
import org.json.JSONObject

internal data class ForgeJobOptions(
    val apktool: Boolean = true,
    val jadx: Boolean = true,
    val deobf: Boolean = false,
    val merge: Boolean = true,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val force: Boolean = false,
)

internal class ForgeJob(
    val id: String,
    val kind: String,
    val label: String,
    val source: String = "",
    val splits: List<String> = emptyList(),
    var projectDir: String = "",
    val outputRoot: String = "",
    val slug: String = "",
    val temp: Boolean = false,
    val options: ForgeJobOptions = ForgeJobOptions(),
    val batchId: String = "",
    val batchSize: Int = 1,
) {
    var status: String = QUEUED
    var error: String = ""
    var apk: String = ""
    var startedAt: Long = 0
    var endedAt: Long = 0
    var stage: String = ""
    var fraction: Float? = null
    var summaryLine: String = ""
    val logLines = mutableListOf<String>()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("label", label)
        .put("status", status)
        .put("error", error)
        .put("projectDir", projectDir)
        .put("apk", apk)
        .put("startedAt", startedAt)
        .put("endedAt", endedAt)
        .put("source", source)
        .put("splits", JSONArray(splits))
        .put("outputRoot", outputRoot)
        .put("slug", slug)
        .put("temp", temp)
        .put("options", JSONObject()
            .put("apktool", options.apktool)
            .put("jadx", options.jadx)
            .put("deobf", options.deobf)
            .put("merge", options.merge)
            .put("threads", options.threads)
            .put("force", options.force))
        .put("batchId", batchId)
        .put("batchSize", batchSize)
        .put("stage", stage)
        .put("fraction", fraction ?: JSONObject.NULL)
        .put("summaryLine", summaryLine)
        .put("logLines", JSONArray(logLines))

    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
        const val DECOMPILE = "DECOMPILE"
        const val REBUILD = "REBUILD"

        fun fromJson(json: JSONObject): ForgeJob {
            val optionJson = json.optJSONObject("options") ?: JSONObject()
            val splitsJson = json.optJSONArray("splits") ?: JSONArray()
            val job = ForgeJob(
                id = json.getString("id"),
                kind = json.getString("kind"),
                label = json.optString("label"),
                source = json.optString("source"),
                splits = (0 until splitsJson.length()).map { splitsJson.getString(it) },
                projectDir = json.optString("projectDir"),
                outputRoot = json.optString("outputRoot"),
                slug = json.optString("slug"),
                temp = json.optBoolean("temp"),
                options = ForgeJobOptions(
                    apktool = optionJson.optBoolean("apktool", true),
                    jadx = optionJson.optBoolean("jadx", true),
                    deobf = optionJson.optBoolean("deobf"),
                    merge = optionJson.optBoolean("merge", true),
                    threads = optionJson.optInt("threads", Runtime.getRuntime().availableProcessors()).coerceIn(1, 8),
                    force = optionJson.optBoolean("force"),
                ),
                batchId = json.optString("batchId"),
                batchSize = json.optInt("batchSize", 1).coerceAtLeast(1),
            )
            job.status = json.optString("status", QUEUED)
            job.error = json.optString("error")
            job.apk = json.optString("apk")
            job.startedAt = json.optLong("startedAt")
            job.endedAt = json.optLong("endedAt")
            job.stage = json.optString("stage")
            job.fraction = if (json.isNull("fraction")) null else json.optDouble("fraction").toFloat()
            job.summaryLine = json.optString("summaryLine")
            val logs = json.optJSONArray("logLines") ?: JSONArray()
            for (i in 0 until logs.length()) job.logLines.add(logs.getString(i))
            return job
        }
    }
}
