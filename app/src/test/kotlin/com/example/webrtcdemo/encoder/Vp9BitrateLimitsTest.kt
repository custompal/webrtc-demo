package com.example.webrtcdemo.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// Vp9BitrateLimitsTest —— 码率上下限的纯 JVM 回归（t89）
// ----------------------------------------------------------------------------
// 防的是「码率分配崩塌」回归：`minBitrateBps` 一旦被写成 0，libwebrtc 的
// `GetEncoderBitrateLimitsForResolution()`（消费点 video/video_stream_encoder.cc:469/:540）
// 就会把视频流地板抽掉，分配可掉到几十 kbps（真机实测 37–40 kbps）。
// 因此本测试**必须**断言每个档位的 minBitrateBps > 0，且启动码率/上限自洽。
// ============================================================================
class Vp9BitrateLimitsTest {

    private val limits = Vp9BitrateLimits.limits().toList()

    @Test
    fun `表结构与官方 VP9 单播表一致（6 档，像素递增）`() {
        assertEquals(6, limits.size)
        for (i in 1 until limits.size) {
            assertTrue(
                "第 $i 档像素数必须大于前一档",
                limits[i].frameSizePixels > limits[i - 1].frameSizePixels
            )
        }
        assertEquals(320 * 180, limits[0].frameSizePixels)
        assertEquals(1920 * 1080, limits[5].frameSizePixels)
    }

    @Test
    fun `每档都有非零码率地板（旧实现 min=0 是崩塌根因）`() {
        for (limit in limits) {
            assertTrue(
                "frameSizePixels=${limit.frameSizePixels} 的 minBitrateBps 必须 > 0",
                limit.minBitrateBps > 0
            )
            assertEquals(30_000, limit.minBitrateBps)
        }
    }

    @Test
    fun `每档的启动码率与上限自洽`() {
        for (limit in limits) {
            assertTrue(
                "frameSizePixels=${limit.frameSizePixels}：maxBitrateBps 必须 >= minBitrateBps",
                limit.maxBitrateBps >= limit.minBitrateBps
            )
            assertTrue(
                "frameSizePixels=${limit.frameSizePixels}：minStartBitrateBps 不得为负",
                limit.minStartBitrateBps >= 0
            )
            if (limit.frameSizePixels >= 480 * 270) {
                assertTrue(
                    "480*270 起必须有非零启动码率（H4：让估计器有可探测流量）",
                    limit.minStartBitrateBps > 0
                )
            }
        }
    }

    @Test
    fun `640x360 档与官方数值逐字一致`() {
        val l = Vp9BitrateLimits.matchForPixels(640 * 360)
        assertEquals(640 * 360, l.frameSizePixels)
        assertEquals(190_000, l.minStartBitrateBps)
        assertEquals(30_000, l.minBitrateBps)
        assertEquals(420_000, l.maxBitrateBps)
    }

    @Test
    fun `真机 480x360 与 640x480 命中档位的地板不为零（崩塌场景回归）`() {
        // 480*360 = 172800 px ⇒ 命中 480*270 档（120k/30k/300k）
        val p480 = Vp9BitrateLimits.matchForPixels(480 * 360)
        assertEquals(480 * 270, p480.frameSizePixels)
        assertTrue("480x360 的分配不得低于 30 kbps", p480.minBitrateBps >= 30_000)
        assertTrue("480x360 的启动码率应 >= 120 kbps", p480.minStartBitrateBps >= 120_000)
        // 640*480 = 307200 px ⇒ 命中 640*360 档
        val p640 = Vp9BitrateLimits.matchForPixels(640 * 480)
        assertEquals(640 * 360, p640.frameSizePixels)
        assertEquals(30_000, p640.minBitrateBps)
    }

    @Test
    fun `极小分辨率退回最小档而不是无限制`() {
        val l = Vp9BitrateLimits.matchForPixels(1)
        assertEquals(320 * 180, l.frameSizePixels)
        assertEquals(30_000, l.minBitrateBps)
    }

    @Test
    fun `limits() 每次返回新数组（调用方改写不影响内部表）`() {
        val a = Vp9BitrateLimits.limits()
        val b = Vp9BitrateLimits.limits()
        assertTrue("必须是不同数组实例", a !== b)
        assertEquals(a.size, b.size)
    }
}
