package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// FrameDropperFieldTrialTest —— FrameDropper 关闭开关的纯 JVM 回归（t92）
// ----------------------------------------------------------------------------
// field trial 的**名称与取值必须逐字精确**，否则 libwebrtc 静默忽略（不报错），
// 我们会以为「已关闭丢帧」而实际仍在丢帧。本测试把这两个字符串钉死。
// ============================================================================
class FrameDropperFieldTrialTest {

    @Test
    fun `试验名与 libwebrtc 源码逐字一致`() {
        // video/video_stream_encoder.cc:108 kFrameDropperFieldTrial
        assertEquals("WebRTC-FrameDropper", FrameDropperFieldTrial.NAME)
    }

    @Test
    fun `取值必须是 Disabled 而不是 Enabled`() {
        // video_stream_encoder.cc:1459-1463 用的是 IsDisabled(...) ⇒ 只有 Disabled 才关闭丢帧
        assertEquals("Disabled", FrameDropperFieldTrial.DISABLED_VALUE)
        assertFalse(
            "取值写成 Enabled 会让 dropper 继续工作（old-red 判据）",
            FrameDropperFieldTrial.DISABLED_TRIAL.contains("/Enabled/")
        )
    }

    @Test
    fun `完整试验串符合 field-trial 语法 名-值-斜杠`() {
        val trial = FrameDropperFieldTrial.DISABLED_TRIAL
        assertEquals("WebRTC-FrameDropper/Disabled/", trial)
        assertTrue("必须以 / 结尾", trial.endsWith("/"))
        assertEquals("只允许一个试验（单个 name/value 对）", 2, trial.count { it == '/' })
        assertFalse("不得含空格（会解析失败）", trial.contains(' '))
    }
}
