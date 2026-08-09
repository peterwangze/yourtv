package com.horsenma.yourtv.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMetadataParserTest {

    @Test
    fun prefersTvgNameWhenLabelContainsAttributes() {
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV1\" group-title=\"央视\",tvg-id=\"\" tvg-name=\"\" tvg-logo=\"\""
        )

        assertEquals("CCTV1", parsed?.title)
        assertEquals("央视", parsed?.group)
    }

    @Test
    fun rejectsMetadataOnlyAndAdvertisementLabels() {
        assertNull(ChannelMetadataParser.parse("#EXTINF:-1 tvg-id=\"\" tvg-name=\"\",tvg-id=\"\" tvg-name=\"\""))
        assertNull(ChannelMetadataParser.parse("#EXTINF:-1 group-title=\"广告\",广告购物频道"))
    }

    @Test
    fun readsUnquotedAndQuotedAttributes() {
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1 tvg-name=\"湖南卫视\" tvg-chno=35 group-title=\"卫视\",湖南卫视"
        )
        assertEquals("湖南卫视", parsed?.name)
        assertEquals(35, parsed?.number)
    }

    @Test
    fun acceptsAttributesAfterDurationComma() {
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1,tvg-id=\"\" tvg-name=\"CCTV1\" tvg-logo=\"logo\" group-title=\"央视\",CCTV1"
        )
        assertEquals("CCTV1", parsed?.title)
        assertEquals("央视", parsed?.group)
    }

    @Test
    fun detectsObviousCrossWiredCctvLine() {
        assertEquals(true, ChannelMetadataParser.isLikelyWrongChannelUri("CCTV1", "https://example/cctv2hd/index.m3u8"))
        assertEquals(false, ChannelMetadataParser.isLikelyWrongChannelUri("CCTV1", "https://example/cctv1hd/index.m3u8"))
    }

    @Test
    fun mergesAttributesSplitAcrossBothSidesOfComma() {
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1 tvg-id=\"\",tvg-name=\"CCTV1\" group-title=\"央视\",CCTV1"
        )
        assertEquals("CCTV1", parsed?.title)
        assertEquals("央视", parsed?.group)
    }

    @Test
    fun parsesChinaIptvAttributesBeforeCommaFormat() {
        // hujingguang/ChinaIPTV 真实行：属性在前，频道名在逗号后
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1 group-title=\"上海台\" tvg-id=\"上海纪实人文频道\" tvg-logo=\"http://tv.haoqu99.com/d/file/2020/0503/small.jpg\",上海纪实人文频道"
        )
        assertEquals("上海纪实人文频道", parsed?.title)
        assertEquals("上海台", parsed?.group)
        assertEquals("http://tv.haoqu99.com/d/file/2020/0503/small.jpg", parsed?.logo)
    }

    @Test
    fun parsesVbskycnStandardFormat() {
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1 tvg-name=\"CCTV5+\" tvg-id=\"114\" tvg-logo=\"https://tb.zbds.top/logo/CCTV5+.png\" group-title=\"央视频道\", CCTV5+"
        )
        assertEquals("CCTV5+", parsed?.title)
        assertEquals("央视频道", parsed?.group)
    }

    @Test
    fun parsesVicjlTvgIdOnlyFormat() {
        val parsed = ChannelMetadataParser.parse(
            "#EXTINF:-1 tvg-id=\"cctv1\" tvg-logo=\"cctv1.png\",CCTV-1 综合"
        )
        assertEquals("CCTV-1 综合", parsed?.title)
        assertEquals("", parsed?.group)
    }

    @Test
    fun rejectsVodAndLoopStreamUris() {
        assertTrue(ChannelMetadataParser.isNoiseUri("https://vip.ffzy-play.com/20221018/1953_2d6ba500/index.m3u8"))
        assertTrue(ChannelMetadataParser.isNoiseUri("https://live.metshop.top/huya/11601966"))
        assertTrue(ChannelMetadataParser.isNoiseUri("http://newcntv.qcloudcdn.com/asp/hls/4000/0303000a/3/default/93fc12dbf14241c18c23da104fbade23/4000.m3u8"))
        assertTrue(ChannelMetadataParser.isNoiseUri("https://hls.cntv.lxdns.com/asp/hls/2000/0303000a/3/default/f0a47e155d32450daa1d36a04fd7d262/2000.m3u8"))
        assertTrue(ChannelMetadataParser.isNoiseUri("https://txmov2.a.kwimgs.com/bs3/video-hls/5195746663405928031_hlsb.m3u8"))
        // 常规直播流不受影响
        assertFalse(ChannelMetadataParser.isNoiseUri("http://gslbmgsplive.miguvideo.com/migu/kailu/cctv1hd265/51/index.m3u8"))
        assertFalse(ChannelMetadataParser.isNoiseUri("http://ali-m-l.cztv.com/channels/lantian/channel001/1080p.m3u8"))
    }

    @Test
    fun rejectsAdNetworkRadioAndVirtualLiveUris() {
        // 广告网络投放（韩国电影1/2 实为广告流）
        assertTrue(ChannelMetadataParser.isNoiseUri("https://stream.ads.ottera.tv/playlist.m3u8?network_id=595"))
        // 蜻蜓FM 广播电台冒充电视（黑屏）
        assertTrue(ChannelMetadataParser.isNoiseUri("https://ls.qingting.fm/live/21303/64k.m3u8"))
        // 咪咕点播虚拟直播
        assertTrue(ChannelMetadataParser.isNoiseUri("http://gslbmgsplive.miguvideo.com/wd-virtuallive5100002089-150/index.m3u8"))
        assertTrue(ChannelMetadataParser.isNoiseUri("http://gslbmgsplive.miguvideo.com/migu/virtuallive2/5900002409/51/20210706/index.m3u8"))
        // 快手视频文件
        assertTrue(ChannelMetadataParser.isNoiseUri("http://jsmov2.a.yximgs.com/bs3/video-hls/5250634317044855747_hlsb.m3u8"))
    }

    @Test
    fun rejectsStaleSportsAndUndefinedTitles() {
        // 昨天的体育赛事（过期回放）
        assertTrue(ChannelMetadataParser.isNoise("德甲 汉堡VS弗赖堡 王子睿 21:15", "体育-昨天05-09"))
        // 图文直播（比分牌，无视频）
        assertTrue(ChannelMetadataParser.isNoise("CBA 山西汾酒VS浙江浙商证券 图文直播 19:35", "体育-今天05-10"))
        // 重映/点播栏目
        assertTrue(ChannelMetadataParser.isNoise("BWF 汤杯经典重映-中国 vs 印度 经典重映 12:00", "体育-今天05-10"))
        assertTrue(ChannelMetadataParser.isNoise("经典动画大集合", "儿童频道"))
        // 正常直播不受影响
        assertFalse(ChannelMetadataParser.isNoise("德甲 汉堡VS弗赖堡 王子睿 21:15", "体育-今天05-10"))
        // "Undefined" 分组是 iptv-org 的合法兜底归类，不能当作噪音（VOA 等）
        assertFalse(ChannelMetadataParser.isNoise("VOA美国之音 (1080p)", "Undefined"))
        assertFalse(ChannelMetadataParser.isNoise("咪咕体育日报 undefined 咪咕体育日报 第105期 08:00", "体育-今天05-10"))
        assertFalse(ChannelMetadataParser.isNoise("CCTV1", "央视频道"))
    }

    @Test
    fun rejectsYearPrefixedChunwanTitles() {
        assertTrue(ChannelMetadataParser.isNoise("1987年春晚", "春晚频道"))
        assertTrue(ChannelMetadataParser.isNoise("2025年春晚", ""))
        assertFalse(ChannelMetadataParser.isNoise("CCTV1", "央视频道"))
    }
}
