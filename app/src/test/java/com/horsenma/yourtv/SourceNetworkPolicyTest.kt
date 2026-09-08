package com.horsenma.yourtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNetworkPolicyTest {
    @Test
    fun `识别三网与公网线路`() {
        assertEquals(
            SourceNetworkPolicy.Carrier.CHINA_MOBILE,
            SourceNetworkPolicy.carrier("http://ottrrs.hl.chinamobile.com/live/cctv1.m3u8"),
        )
        assertEquals(
            SourceNetworkPolicy.Carrier.CHINA_UNICOM,
            SourceNetworkPolicy.carrier("http://221.7.175.154:8445/live/cctv1.m3u8"),
        )
        assertEquals(
            SourceNetworkPolicy.Carrier.CHINA_TELECOM,
            SourceNetworkPolicy.carrier("http://live.dxhmt.cn:9081/live/ts001.m3u8"),
        )
        assertEquals(
            SourceNetworkPolicy.Carrier.PUBLIC,
            SourceNetworkPolicy.carrier("http://ali-m-l.cztv.com/channels/lantian/channel001/1080p.m3u8"),
        )
    }

    @Test
    fun `过滤私网组播和非播放器地址`() {
        assertFalse(SourceNetworkPolicy.isUsablePublicCandidate("http://192.168.1.8/live.m3u8"))
        assertFalse(SourceNetworkPolicy.isUsablePublicCandidate("http://169.254.2.8/live.m3u8"))
        assertFalse(SourceNetworkPolicy.isUsablePublicCandidate("http://[fd12::8]/live.m3u8"))
        assertFalse(SourceNetworkPolicy.isUsablePublicCandidate("rtp://239.1.1.1:1234"))
        assertFalse(SourceNetworkPolicy.isUsablePublicCandidate("file:///sdcard/live.ts"))
        assertFalse(SourceNetworkPolicy.isUsablePublicCandidate("https://cdn.example.cn/archive/2023.mp4"))
        assertTrue(SourceNetworkPolicy.isUsablePublicCandidate("https://example.cn/live.m3u8"))
    }

    @Test
    fun `候选线路限制主机并保留三网兜底`() {
        val ranked = listOf(
            "https://ali-m-l.cztv.com/live/1.m3u8",
            "https://ali-m-l.cztv.com/live/2.m3u8",
            "https://ali-m-l.cztv.com/live/3.m3u8",
            "https://another.example.cn/live.m3u8",
            "http://ottrrs.hl.chinamobile.com/live/1.m3u8",
            "http://221.7.175.154:8445/live/1.m3u8",
            "http://live.dxhmt.cn:9081/live/1.m3u8",
            "https://third.example.cn/live.m3u8",
            "https://fourth.example.cn/live.m3u8",
        )

        val result = SourceNetworkPolicy.diversify(ranked, 8)

        assertEquals(ranked.first(), result.first())
        assertTrue(result.size <= 8)
        assertTrue(result.any { SourceNetworkPolicy.carrier(it) == SourceNetworkPolicy.Carrier.CHINA_MOBILE })
        assertTrue(result.any { SourceNetworkPolicy.carrier(it) == SourceNetworkPolicy.Carrier.CHINA_UNICOM })
        assertTrue(result.any { SourceNetworkPolicy.carrier(it) == SourceNetworkPolicy.Carrier.CHINA_TELECOM })
        assertTrue(result.groupingBy(SourceNetworkPolicy::hostOf).eachCount().values.all { it <= 2 })
    }
}
