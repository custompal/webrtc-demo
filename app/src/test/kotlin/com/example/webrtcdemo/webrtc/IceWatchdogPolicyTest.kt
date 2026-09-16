package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.ui.call.CallSurvivability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t80：看门狗"档位被误传为 epoch 毫秒"的误报回归 + ICE 横幅恢复清除
// ----------------------------------------------------------------------------
// 真机缺陷（webrtc-demo-logs-20260916-033453Z）：通话完全正常（P2P/RELAY 双形态
// down≈2.0 Mbps / up≈1.5–2.4 Mbps、画面双向流畅），房主端却**持续**显示红色横幅
// 「ICE 未连通（本端候选 host=6,srflx=1,relay=2；对端候选 -）…」。
// 日志铁证（同一秒内自证误报）：
//   03:34:24.872 ice_watchdog_rearmed … elapsed_ms=1789529664872   ← epoch 毫秒被当成"已用时长"
//   03:34:24.873 ice_timeout after_ms=1789529664872 ice_state=NEW   ← 立刻判失败 + onError
//   03:34:25.130 ice_watchdog_started evt=22                        ← 看门狗**这时**才启动
//   03:34:25.436 ice_watchdog_ok elapsed_ms=306 → 25.438 state=CONNECTED
// 本文件只钉**纯判定**（不构造 PeerConnection）：CallSession 的 IceWatchdogPolicy 与
// CallSurvivability.shouldClearIceError 就是生产代码调用的同一实现。
//
// 运行方式（宿主机）：离线 harness（kotlinc-embeddable + JUnit4）或 gradle :app:testDebugUnitTest
// ============================================================================

class IceWatchdogPolicyTest {

    // ============ ① 基数守卫：看门狗未启动/基数无效时，rearm 不得触发失败检查 ============

    @Test
    fun baseZeroRearmMustNotTriggerFailureCheck() {
        // 看门狗还没启动（relay 候选早到，真机 evt=16/18 < evt=22）⇒ 必须跳过
        assertFalse(
            "看门狗未启动时 rearm 不得进入检查（否则 elapsed 会是 epoch 量级）",
            IceWatchdogPolicy.shouldRearmCheck(watchdogActive = false, startMs = 0L),
        )
        // 看门狗对象在但基数被复位（stop 之后）⇒ 同样跳过
        assertFalse(IceWatchdogPolicy.shouldRearmCheck(watchdogActive = true, startMs = 0L))
        assertFalse(IceWatchdogPolicy.shouldRearmCheck(watchdogActive = true, startMs = -1L))
        // 正常启动后（基数有效）⇒ 允许
        assertTrue(IceWatchdogPolicy.shouldRearmCheck(watchdogActive = true, startMs = 1_789_529_664_000L))
        // 复现旧行为：基数 0 时"时长"就是 epoch 毫秒，远超 30 s 档 ⇒ 旧实现必然触发检查（这正是缺陷）
        val epochLikeElapsed = 1_789_529_664_872L
        assertTrue("旧口径：epoch 量级 'elapsed' >= ICE_WARN_MS", epochLikeElapsed >= CallSession.ICE_WARN_MS)
    }

    @Test
    fun realElapsedIsUnknownWhenBaseInvalid() {
        assertEquals(-1L, IceWatchdogPolicy.realElapsedMs(startMs = 0L, nowMs = 1_789_529_664_872L))
        assertEquals(306L, IceWatchdogPolicy.realElapsedMs(startMs = 1_000L, nowMs = 1_306L))
    }

    // ============ ② 档位/时长一致性守卫：陈旧档位必须跳过（不上报失败/不回调 onError） ============

    @Test
    fun epochSizedTierIsStaleWhenRealElapsedIsShort() {
        val now = 1_789_529_664_872L
        // 真机形态：档位 = epoch 毫秒（1789529664872），基数无效 ⇒ 陈旧 ⇒ 必须跳过
        assertTrue(
            "epoch 量级档位 + 基数无效 ⇒ stale（不得上报失败）",
            IceWatchdogPolicy.isStaleTier(
                tierMs = 1_789_529_664_872L,
                startMs = 0L,
                nowMs = now,
                failMs = CallSession.ICE_FAIL_MS,
            ),
        )
        // 基数有效但真实耗时不足（本世代刚启动 306 ms）而档位已到失败档 ⇒ 陈旧
        assertTrue(
            IceWatchdogPolicy.isStaleTier(
                tierMs = CallSession.ICE_FAIL_MS,
                startMs = now - 306L,
                nowMs = now,
                failMs = CallSession.ICE_FAIL_MS,
            ),
        )
        // 未到失败档的档位（30 s）不受该守卫影响
        assertFalse(
            IceWatchdogPolicy.isStaleTier(
                tierMs = CallSession.ICE_WARN_MS,
                startMs = 0L,
                nowMs = now,
                failMs = CallSession.ICE_FAIL_MS,
            ),
        )
    }

    @Test
    fun legitFailTierStillPassesWhenRealElapsedReached() {
        val now = 1_789_529_664_872L
        // 真实等到 45 s 以上：档位与耗时一致 ⇒ **不得**跳过（真正连不上时仍要如实上报）
        assertFalse(
            IceWatchdogPolicy.isStaleTier(
                tierMs = CallSession.ICE_FAIL_MS,
                startMs = now - 45_001L,
                nowMs = now,
                failMs = CallSession.ICE_FAIL_MS,
            ),
        )
        // 顺延后的档位（45 s + 步长）同样按真实耗时判定
        assertFalse(
            IceWatchdogPolicy.isStaleTier(
                tierMs = CallSession.ICE_FAIL_MS + 5_000L,
                startMs = now - 60_000L,
                nowMs = now,
                failMs = CallSession.ICE_FAIL_MS,
            ),
        )
    }

    // ============ ③ 恢复清除路径：连通/媒体判活 ⇒ 清横幅；非 ICE 文案不清 ============

    @Test
    fun iceBannerClearedWhenConnectedOrMediaAlive() {
        val banner = "ICE 未连通（本端候选 host=6,srflx=1,relay=2；对端候选 -）。可在诊断页打开「强制中继」后重试"
        // 已 CONNECTED（真机 03:34:25.438）⇒ 清除
        assertTrue(CallSurvivability.shouldClearIceError(banner, phaseConnected = true, mediaAlive = false))
        // 媒体判活（帧新鲜 / down_bps>0，真机 down≈2.0 Mbps）⇒ 清除
        assertTrue(CallSurvivability.shouldClearIceError(banner, phaseConnected = false, mediaAlive = true))
        // 中继不可用类文案同属 ICE 类 ⇒ 同样可清
        assertTrue(
            CallSurvivability.shouldClearIceError(
                "未获取到中继候选（TURN 无响应）——本端候选 host=6",
                phaseConnected = true,
                mediaAlive = false,
            ),
        )
        // 没有横幅 / 还没恢复 ⇒ 不清
        assertFalse(CallSurvivability.shouldClearIceError(null, phaseConnected = true, mediaAlive = true))
        assertFalse(CallSurvivability.shouldClearIceError("", phaseConnected = true, mediaAlive = true))
        assertFalse(CallSurvivability.shouldClearIceError(banner, phaseConnected = false, mediaAlive = false))
    }

    @Test
    fun nonIceBannerIsNotClearedByRecoveryPath() {
        // 服务端错误等非 ICE 文案必须保留（不能被"恢复"路径吞掉）
        assertFalse(
            CallSurvivability.shouldClearIceError("服务端错误: ROOM_FULL", phaseConnected = true, mediaAlive = true),
        )
        assertFalse(
            CallSurvivability.shouldClearIceError("对端无响应：可能未加入", phaseConnected = true, mediaAlive = true),
        )
        // 与 t68 同口径：媒体判活时 ICE 类文案不得再弹（onError 抑制）
        assertFalse(
            CallSurvivability.shouldSurfaceError("ICE 未连通（本端候选 host=6）", mediaAlive = true),
        )
        assertTrue(CallSurvivability.shouldSurfaceError("ICE 未连通（本端候选 host=6）", mediaAlive = false))
    }
}
