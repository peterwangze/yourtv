package com.horsenma.yourtv

import org.junit.Assert.*
import org.junit.Test

class SourcePreferenceTest {
    @Test fun `compatibility fallback prefers 720 over tiny video`() {
        assertEquals("720", SourcePreference.rank(listOf(
            SourcePreference.Candidate("tiny", "426x240", compatibility = 40),
            SourcePreference.Candidate("720", "1280x720", compatibility = 15),
        )).first())
    }
    private fun line(url: String, resolution: String? = null, health: Int = 1,
                     stable: Boolean = false, startup: Long? = null, compatibility: Int = 25) =
        SourcePreference.Candidate(url, resolution, health, stable, startup, compatibility)

    @Test fun `remembered fast SD cannot pin playback ahead of measured full HD`() {
        assertEquals(listOf("hd", "sd"), SourcePreference.rank(listOf(
            line("sd", "720x576", 4, true, 100), line("hd", "1920x1080"))))
    }
    @Test fun `cooling full HD permits healthy lower resolution`() {
        assertEquals("sd", SourcePreference.rank(listOf(line("hd", "1920x1080", 0),
            line("sd", "1280x720", 4))).first())
    }
    @Test fun `domain and channel branding do not prove full HD`() {
        assertEquals(1, SourceQuality.preferenceTier("https://migu.example/live/hd.m3u8", null))
        assertEquals(0, SourceQuality.preferenceTier("https://example/4k.m3u8", "1920x540"))
        assertEquals(0, SourceQuality.preferenceTier("https://example/1080p.m3u8", "1280x720"))
    }
    @Test fun `both dimensions must meet the full HD floor`() {
        assertFalse(SourceQuality.resolution("1920x540")!!.fullHd)
        assertFalse(SourceQuality.resolution("1440x1080")!!.fullHd)
        assertTrue(SourceQuality.resolution("1920x1080")!!.fullHd)
        assertNull(SourceQuality.resolution("1920xgarbage"))
    }
    @Test fun `confirmed full HD beats unmeasured 4K advertising`() {
        assertEquals("hd", SourcePreference.rank(listOf(line("https://x/4k.m3u8"),
            line("hd", "1920x1080"))).first())
    }
    @Test fun `faster actual startup wins within equally healthy full HD lines`() {
        assertEquals("fast", SourcePreference.rank(listOf(line("slow", "3840x2160", 4, false, 7000),
            line("fast", "1920x1080", 4, false, 900))).first())
    }
    @Test fun `old stable marker without playback evidence does not override current route`() {
        assertEquals("new", SourcePreference.rank(listOf(line("old", "1920x1080", 1, true),
            line("new", "1920x1080", 4, false, 1500))).first())
    }
    @Test fun `carrier label does not override measured dimensions`() {
        assertEquals("carrier", SourcePreference.rank(listOf(line("public", "720x576", 4, false, 20, 40),
            line("carrier", "1920x1080", 1, false, null, 15))).first())
    }
}
