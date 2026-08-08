package com.horsenma.yourtv

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceQualityTest {

    @Test
    fun `URL 关键词评分`() {
        assertEquals(100, SourceQuality.score("http://x.com/live/8k.m3u8"))
        assertEquals(95, SourceQuality.score("http://x.com/live/4k.m3u8"))
        assertEquals(95, SourceQuality.score("http://x.com/live/channel4k2160p.m3u8"))
        // 咪咕运营商级源
        assertEquals(90, SourceQuality.score("http://gslbmgsplive.miguvideo.com/migu/kailu/cctv1hd265/51/index.m3u8?msisdn=x"))
        assertEquals(90, SourceQuality.score("http://hlsztemgsplive.miguvideo.com:8080/wd_r2/cctv/jiangsuhd/1200/index.m3u8"))
        // 百视通/APTV 8M
        assertEquals(88, SourceQuality.score("http://live.aptvapp.com/bestv.php?id=hunanwshd8m/8000000"))
        // 芒果TV 官方直播（湖南卫视4K 等）
        assertEquals(88, SourceQuality.score("http://hlsal-ldvt.qing.mgtv.com/nn_live/nn_x64/Y2RuZXhfaWQ9YWxfaGxzX2xkdnQmZT02OTE0NjA0JnY9MSZpZD1ITldTWkdTVCZzPTcwN2RiY"))
        // 浙江广电官方流（cztv 蓝天下/云平台）
        assertEquals(87, SourceQuality.score("http://hw-m-l.cztv.com/channels/lantian/channel001/1080p.m3u8"))
        assertEquals(87, SourceQuality.score("http://l.cztvcloud.com/channels/lantian/channel001/1080p.m3u8"))
        // 运营商 OTT：移动/电信/广东移动
        assertEquals(85, SourceQuality.score("http://ottrrs.hl.chinamobile.com/PLTV/88888888/224/3221226307/index.m3u8"))
        assertEquals(85, SourceQuality.score("http://live.dxhmt.cn:9081/live/ts001.m3u8"))
        assertEquals(85, SourceQuality.score("http://120.196.232.43:8088/rrs03.hw.gmcc.net/PLTV/651/224/3221226698/1.m3u8"))
        // 苏州4K（kan0512）
        assertEquals(95, SourceQuality.score("https://tylive.kan0512.com/norecord/csztv4k_4k.m3u8"))
        assertEquals(85, SourceQuality.score("http://x.com/live/1080p.m3u8"))
        assertEquals(70, SourceQuality.score("http://x.com/live/hd.m3u8"))
        assertEquals(55, SourceQuality.score("http://x.com/live/stream.m3u8"))
    }

    @Test
    fun `实测分辨率优先`() {
        assertEquals(100, SourceQuality.scoreWithResolution("http://x/unknown.m3u8", "3840x2160"))
        assertEquals(90, SourceQuality.scoreWithResolution("http://x/unknown.m3u8", "1920x1080"))
        assertEquals(75, SourceQuality.scoreWithResolution("http://x/unknown.m3u8", "1280x720"))
        assertEquals(45, SourceQuality.scoreWithResolution("http://x/unknown.m3u8", "640x360"))
        // 有实测时忽略 URL 关键词
        assertEquals(90, SourceQuality.scoreWithResolution("http://x/4k.m3u8", "1920x1080"))
    }

    @Test
    fun `标题关键词辅助评分`() {
        assertEquals(95, SourceQuality.scoreWithResolution("http://x/stream.m3u8", null, "CCTV4K"))
        assertEquals(88, SourceQuality.scoreWithResolution("http://x/stream.m3u8", null, "北京卫视超清"))
        assertEquals(80, SourceQuality.scoreWithResolution("http://x/stream.m3u8", null, "湖南卫视高清"))
        assertEquals(55, SourceQuality.scoreWithResolution("http://x/stream.m3u8", null, "CCTV1"))
    }
}
