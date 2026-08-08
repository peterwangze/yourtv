package com.horsenma.yourtv.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelClassifierTest {

    @Test
    fun `央视 分类`() {
        val c = ChannelClassifier.classify("CCTV-1 综合", null)
        assertEquals(ChannelClassifier.CAT_CCTV, c.category)
        assertEquals("", c.region)
        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.classify("CCTV1", null).category)
        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.classify("央视一套", null).category)
        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.classify("CGTN", null).category)
        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.classify("CETV-1", null).category)
        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.classify("中国教育电视台", null).category)
    }

    @Test
    fun `卫视 分类`() {
        val c = ChannelClassifier.classify("湖南卫视", null)
        assertEquals(ChannelClassifier.CAT_WEISHI, c.category)
        assertEquals("", c.region)
        assertEquals(ChannelClassifier.CAT_WEISHI, ChannelClassifier.classify("Hunan TV", null).category)
        assertEquals(ChannelClassifier.CAT_WEISHI, ChannelClassifier.classify("浙江卫视高清", null).category)
        // 凤凰卫视/香港卫视 归地方(港澳台)
        assertEquals(ChannelClassifier.CAT_LOCAL, ChannelClassifier.classify("凤凰卫视中文台", null).category)
        assertEquals("香港", ChannelClassifier.classify("凤凰卫视中文台", null).region)
    }

    @Test
    fun `地方 分类`() {
        assertEquals(ChannelClassifier.CAT_LOCAL, ChannelClassifier.classify("北京新闻", null).category)
        assertEquals("北京", ChannelClassifier.classify("北京新闻", null).region)
        assertEquals("江苏", ChannelClassifier.classify("盐城新闻综合", null).region)
        assertEquals("黑龙江", ChannelClassifier.classify("七台河新闻综合", null).region)
        assertEquals("浙江", ChannelClassifier.classify("余姚新闻综合", null).region)
        assertEquals("浙江", ChannelClassifier.classify("中国蓝新闻", null).region)
        assertEquals("湖南", ChannelClassifier.classify("快乐垂钓", null).region)
        assertEquals("广东", ChannelClassifier.classify("深圳都市", null).region)
        assertEquals("香港", ChannelClassifier.classify("翡翠台", null).region)
        assertEquals("台湾", ChannelClassifier.classify("TVBS新闻", null).region)
        assertEquals("澳门", ChannelClassifier.classify("澳视澳门", null).region)
        // 分组提示为地方频道但标题无省市 → 地方›未分类
        val un = ChannelClassifier.classify("都市频道", "地方频道")
        assertEquals(ChannelClassifier.CAT_LOCAL, un.category)
        assertEquals("未分类", un.region)
        assertEquals(ChannelClassifier.CAT_LOCAL, ChannelClassifier.classify("新闻综合", "Local").category)
    }

    @Test
    fun `海外 分类`() {
        assertEquals(ChannelClassifier.CAT_OVERSEAS, ChannelClassifier.classify("BBC World News", null).category)
        assertEquals("英国", ChannelClassifier.classify("BBC World News", null).region)
        assertEquals("美国", ChannelClassifier.classify("CNN", null).region)
        assertEquals("日本", ChannelClassifier.classify("NHK World", null).region)
        assertEquals("韩国", ChannelClassifier.classify("KBS World", null).region)
    }

    @Test
    fun `其他 分类`() {
        assertEquals(ChannelClassifier.CAT_OTHER, ChannelClassifier.classify("环球购物", null).category)
        assertEquals(ChannelClassifier.CAT_OTHER, ChannelClassifier.classify("某某轮播台", "Movie").category)
        // 分组提示兜底
        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.classify("综合频道", "央视").category)
    }

    @Test
    fun `displayGroup 与一级分类`() {
        assertEquals("央视", ChannelClassifier.displayGroup("CCTV1", "央视"))
        assertEquals("卫视", ChannelClassifier.displayGroup("湖南卫视", null))
        assertEquals("江苏", ChannelClassifier.displayGroup("盐城新闻综合", null))
        assertEquals("英国", ChannelClassifier.displayGroup("BBC", null))
        assertEquals("新闻", ChannelClassifier.displayGroup("某某台", "News"))
        assertEquals("其他", ChannelClassifier.displayGroup("某某轮播", ""))

        assertEquals(ChannelClassifier.CAT_CCTV, ChannelClassifier.topCategoryOfGroup("央视"))
        assertEquals(ChannelClassifier.CAT_WEISHI, ChannelClassifier.topCategoryOfGroup("卫视"))
        assertEquals(ChannelClassifier.CAT_LOCAL, ChannelClassifier.topCategoryOfGroup("江苏"))
        assertEquals(ChannelClassifier.CAT_LOCAL, ChannelClassifier.topCategoryOfGroup("香港"))
        assertEquals(ChannelClassifier.CAT_OVERSEAS, ChannelClassifier.topCategoryOfGroup("美国"))
        assertEquals(ChannelClassifier.CAT_OTHER, ChannelClassifier.topCategoryOfGroup("新闻"))
        assertEquals(ChannelClassifier.CAT_OTHER, ChannelClassifier.topCategoryOfGroup("其他"))
        assertEquals(ChannelClassifier.CAT_LOCAL, ChannelClassifier.topCategoryOfGroup("未分类"))

        assertFalse(ChannelClassifier.isThreeLevelCategory(ChannelClassifier.CAT_CCTV))
        assertFalse(ChannelClassifier.isThreeLevelCategory(ChannelClassifier.CAT_WEISHI))
        assertTrue(ChannelClassifier.isThreeLevelCategory(ChannelClassifier.CAT_LOCAL))
        assertTrue(ChannelClassifier.isThreeLevelCategory(ChannelClassifier.CAT_OVERSEAS))
        assertTrue(ChannelClassifier.isThreeLevelCategory(ChannelClassifier.CAT_OTHER))
    }

    @Test
    fun `跨源合并键`() {
        assertEquals(
            ChannelClassifier.mergeKey("CCTV-1 综合", null),
            ChannelClassifier.mergeKey("CCTV1", null)
        )
        assertEquals(
            ChannelClassifier.mergeKey("CCTV1超清", null),
            ChannelClassifier.mergeKey("CCTV1", null)
        )
        assertEquals(
            ChannelClassifier.mergeKey("湖南卫视", null),
            ChannelClassifier.mergeKey("Hunan TV", null)
        )
        assertEquals(
            ChannelClassifier.mergeKey("北京卫视超清", null),
            ChannelClassifier.mergeKey("北京卫视", null)
        )
        // 地方频道跨省同名不合并
        assertTrue(
            ChannelClassifier.mergeKey("都市频道", "江苏") !=
                ChannelClassifier.mergeKey("都市频道", "浙江")
        )
        // 央视 4K 与高清合并为同一台号
        assertEquals(
            ChannelClassifier.cctvMergeKey("CCTV4K"),
            ChannelClassifier.cctvMergeKey("CCTV-4K超清")
        )
        assertEquals(
            ChannelClassifier.mergeKey("CCTV+ 1", "央视"),
            ChannelClassifier.mergeKey("CCTV1", "央视")
        )
        assertEquals(1, ChannelClassifier.channelSortOrder("CCTV+ 1", "央视"))
        assertEquals(4000, ChannelClassifier.channelSortOrder("CCTV4K", "央视"))
        assertEquals("CCTV1", ChannelClassifier.displayName("CCTV+ 1 (600p) [Not 24/7]"))
        assertEquals("CCTV5+", ChannelClassifier.displayName("CCTV5+体育赛事"))
    }

    @Test
    fun `名称规范化`() {
        assertEquals("cctv1综合", ChannelClassifier.normalizeName("CCTV-1 综合"))
        assertEquals("北京卫视", ChannelClassifier.normalizeName("北京卫视超清"))
        assertEquals("湖南卫视", ChannelClassifier.normalizeName("湖南卫视(高清)"))
    }

    @Test
    fun `分组名本地化增强`() {
        assertEquals("体育", ChannelClassifier.localizeOther("体育-今天05-10"))
        assertEquals("体育", ChannelClassifier.localizeOther("体育-明天05-11"))
        assertEquals("动画", ChannelClassifier.localizeOther("Animation;Kids"))
        assertEquals("少儿", ChannelClassifier.localizeOther("Kids;Religious"))
        assertEquals("其他", ChannelClassifier.localizeOther("undefined"))
        assertEquals("新闻", ChannelClassifier.localizeOther("News"))
    }

    @Test
    fun `新源频道分类`() {
        // ChinaIPTV 上海台分组：标题含省份 → 地方-上海
        val sh = ChannelClassifier.classify("上海纪实人文频道", "上海台")
        assertEquals(ChannelClassifier.CAT_LOCAL, sh.category)
        assertEquals("上海", sh.region)
        // 卡酷少儿（北京广播电视台）→ 地方-北京
        val kaku = ChannelClassifier.classify("卡酷少儿", "")
        assertEquals(ChannelClassifier.CAT_LOCAL, kaku.category)
        assertEquals("北京", kaku.region)
        // 兵团卫视 → 卫视
        assertEquals(ChannelClassifier.CAT_WEISHI, ChannelClassifier.classify("兵团卫视", "").category)
        // 哈哈炫动卫视 → 卫视
        assertEquals(ChannelClassifier.CAT_WEISHI, ChannelClassifier.classify("哈哈炫动卫视", "上海台").category)
        // vbskycn 分组为地方频道但标题无省市 → 地方-未分类
        val un = ChannelClassifier.classify("都市频道", "地方频道")
        assertEquals("未分类", un.region)
    }

    @Test
    fun `新源跨源合并`() {
        // CCTV-16 4K（ChinaIPTV 移动源）与 CCTV16 合并为同一台号
        assertEquals(
            ChannelClassifier.mergeKey("CCTV-16 4K", "央视"),
            ChannelClassifier.mergeKey("CCTV16", "央视频道")
        )
        // CCTV-5+ 体育赛事（vicjl）与 CCTV5+（vbskycn）合并
        assertEquals(
            ChannelClassifier.mergeKey("CCTV-5+ 体育赛事", ""),
            ChannelClassifier.mergeKey("CCTV5+", "央视频道")
        )
        // 东方卫视4K（bestv.cn）与东方卫视合并
        assertEquals(
            ChannelClassifier.mergeKey("东方卫视4K", ""),
            ChannelClassifier.mergeKey("东方卫视", "卫视频道")
        )
        // 湖南卫视4K（芒果TV）与湖南卫视合并
        assertEquals(
            ChannelClassifier.mergeKey("湖南卫视4K", ""),
            ChannelClassifier.mergeKey("湖南卫视", "卫视")
        )
        // 苏州4K（kan0512）与苏州新闻综合合并为江苏
        val suzhou = ChannelClassifier.classify("苏州4K", "")
        assertEquals(ChannelClassifier.CAT_LOCAL, suzhou.category)
        assertEquals("江苏", suzhou.region)
        assertEquals(16, ChannelClassifier.channelSortOrder("CCTV-16 4K", "央视"))
    }

    @Test
    fun `繁体频道名归类与合并`() {
        // iptv-org 繁体写法：北京衛視 → 卫视而非地方-北京
        val bj = ChannelClassifier.classify("北京衛視 (1080p) [Geo-blocked]", "General")
        assertEquals(ChannelClassifier.CAT_WEISHI, bj.category)
        // 繁体与简体写法合并为同一频道
        assertEquals(
            ChannelClassifier.mergeKey("北京衛視 (1080p) [Geo-blocked]", "General"),
            ChannelClassifier.mergeKey("北京卫视", "卫视频道")
        )
        // 展示名清理原始后缀
        assertEquals("北京衛視", ChannelClassifier.displayName("北京衛視 (1080p) [Geo-blocked]"))
        // 规范化：繁体转简体、去括号与方括号内容
        assertEquals("北京卫视", ChannelClassifier.normalizeName("北京衛視 (1080p) [Geo-blocked]"))
        assertEquals("香港有线新闻", ChannelClassifier.normalizeName("香港有線新聞"))
    }
}
