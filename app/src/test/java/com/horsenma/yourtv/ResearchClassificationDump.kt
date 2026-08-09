package com.horsenma.yourtv

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.horsenma.yourtv.models.ChannelClassifier
import com.horsenma.yourtv.models.ChannelMetadataParser
import org.junit.Test
import java.io.File

/**
 * 调研工具（非正式断言测试）：用 App 真实的 ChannelClassifier / ChannelMetadataParser
 * 对已下载的真实源数据跑分类，输出带分类结果的 JSON，供数据驱动分析使用。
 */
class ResearchClassificationDump {

    data class Entry(val source: String, val group: String, val title: String, val url: String)

    @Test
    fun dumpClassification() {
        val gson = Gson()
        val base = File("D:/AI/agent/codex/android/tv/tools/research")
        val input = File(base, "channels.json")
        val entries: List<Entry> = gson.fromJson(input.readText(), object : TypeToken<List<Entry>>() {}.type)
        val out = mutableListOf<Map<String, Any?>>()
        entries.forEach { e ->
            val c = ChannelClassifier.classify(e.title, e.group)
            out.add(
                linkedMapOf(
                    "source" to e.source,
                    "group" to e.group,
                    "title" to e.title,
                    "url" to e.url,
                    "category" to c.category,
                    "region" to c.region,
                    "displayGroup" to ChannelClassifier.displayGroup(e.title, e.group),
                    "displayName" to ChannelClassifier.displayName(e.title),
                    "mergeKey" to ChannelClassifier.mergeKey(e.title, e.group),
                    "noise" to ChannelMetadataParser.isNoise(e.title, e.group, e.url),
                    "wrongUri" to ChannelMetadataParser.isLikelyWrongChannelUri(e.title, e.url),
                    "quality" to SourceQuality.scoreWithResolution(e.url, null, e.title)
                )
            )
        }
        File(base, "classified.json").writeText(gson.toJson(out))
        val cats = out.groupingBy { it["category"] }.eachCount()
        println("classified ${out.size} entries: $cats")
        val noise = out.count { it["noise"] == true }
        val wrong = out.count { it["wrongUri"] == true }
        println("noise=$noise wrongUri=$wrong")
    }
}
