package com.horsenma.yourtv

import com.horsenma.yourtv.models.ChannelClassifier
import com.horsenma.yourtv.models.ChannelMetadataParser
import org.junit.Test

/** 调研探针：验证可疑分类的真实行为与根因（非正式断言）。 */
class ResearchProbe {

    @Test
    fun probe() {
        // 悬案：Guangdong TV America → 卫视 的匹配路径、Channel U → 希腊 的匹配路径
        val c1 = ChannelClassifier.classify("Guangdong TV America", "General")
        println("Guangdong TV America => ${c1.category}|${c1.region}")
        val c2 = ChannelClassifier.classify("Channel U", "Entertainment")
        println("Channel U => ${c2.category}|${c2.region}")
        val c3 = ChannelClassifier.classify("Zhejiang TV International", null)
        println("Zhejiang TV International => ${c3.category}|${c3.region}")
        val cases = listOf(
            "Zhejiang TV International", "Dragon TV International",
            "Dragon TV", "Shenzhen Satellite TV", "Jiangsu TV International",
            "Beijing TV International", "Shanghai TV International", "Hunan TV International",
            "苏州", "灌阳新闻综合", "赛事最经典", "发现之旅", "中学生",
            "24小时全运会轮播台", "CND Film Middle School Channel", "高台电视台 (1080p)",
            "家庭影院 (1080p)", "纪实人文 (1080p)", "都市剧场", "数码时代",
            "Supreme Master TV (720p)", "Tech Storm (720p) [Geo-blocked]",
            "中国天气", "财富天下", "中华特产", "环球旅游"
        )
        cases.forEach { t ->
            val c = ChannelClassifier.classify(t, null)
            println("$t => ${c.category}|${c.region} group=${ChannelClassifier.displayGroup(t, null)} name=${ChannelClassifier.displayName(t)}")
        }
        println("--- with hints ---")
        val hinted = listOf(
            "苏州" to "卫视", "Dragon TV International" to "General",
            "新闻综合" to "地方频道", "都市剧场" to "数字电视",
            "高台电视台 (1080p)" to "Undefined", "家庭影院 (1080p)" to "Undefined"
        )
        hinted.forEach { (t, g) ->
            val c = ChannelClassifier.classify(t, g)
            println("$t [$g] => ${c.category}|${c.region} group=${ChannelClassifier.displayGroup(t, g)} merge=${ChannelClassifier.mergeKey(t, g)}")
        }
        println("--- noise checks ---")
        listOf(
            "赛事最经典" to "", "发现之旅" to "", "中学生" to "",
            "24小时全运会轮播台" to "", "CND Film Middle School Channel" to ""
        ).forEach { (t, g) ->
            println("$t => noise=${ChannelMetadataParser.isNoise(t, g, "")}")
        }
    }
}
