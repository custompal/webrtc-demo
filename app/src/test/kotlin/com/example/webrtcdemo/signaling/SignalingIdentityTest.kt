package com.example.webrtcdemo.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// ============================================================================
// SignalingIdentity 单元测试（doc/14 §8.2 + 架构裁定 D-3）
// ----------------------------------------------------------------------------
// 为什么能在纯 JVM 跑：`SignalingIdentity.invert` 是纯字符串逻辑；`update` 只依赖
// 信令消息数据类与 MutableStateFlow，**不触碰任何 Android API**（日志在 SignalingClient 侧打）。
// 运行方式（宿主机）：./gradlew :app:testDebugUnitTest
//
// 覆盖点（对应 verifier 指出的缺口）：
//   · invert 双向性与"识别不了就返回 null"（禁止槽位顺序推断）
//   · host 路径：由 peerJoined.peerId 取反推导本端（含槽位互换的场景）
//   · joiner 路径：joined.peerId 作为权威本端值
//   · 本端已知时不被 peerJoined 反向覆盖
//   · peerLeft 只清对端；reset 清两侧
// ============================================================================

class SignalingIdentityTest {

    @Before
    fun setUp() {
        // SignalingIdentity 是进程内单例，逐个用例复位，避免相互污染
        SignalingIdentity.reset()
    }

    // ============================ invert ============================

    @Test
    fun invertMapsPeer001ToPeer002() {
        assertEquals("peer-002", SignalingIdentity.invert("peer-001"))
    }

    @Test
    fun invertMapsPeer002ToPeer001() {
        assertEquals("peer-001", SignalingIdentity.invert("peer-002"))
    }

    @Test
    fun invertIsInvolutive() {
        assertEquals("peer-001", SignalingIdentity.invert(SignalingIdentity.invert("peer-001")!!))
        assertEquals("peer-002", SignalingIdentity.invert(SignalingIdentity.invert("peer-002")!!))
    }

    @Test
    fun invertRejectsAnythingElse() {
        // 不猜顺序：只认两个合法槽位，其余一律 null（UI 会显示占位符 —）
        assertNull(SignalingIdentity.invert("peer-003"))
        assertNull(SignalingIdentity.invert(""))
        assertNull(SignalingIdentity.invert("PEER-001"))
        assertNull(SignalingIdentity.invert("peer-1"))
        assertNull(SignalingIdentity.invert("peer-0001"))
    }

    // ============================ update（host / joiner）============================

    @Test
    fun hostDerivesSelfFromPeerJoined() {
        SignalingIdentity.update(peerJoined("peer-002"))
        assertEquals("peer-001", SignalingIdentity.selfPeerId.value)
        assertEquals("peer-002", SignalingIdentity.remotePeerId.value)
    }

    @Test
    fun hostDerivationHandlesSwappedSlots() {
        // 服务端按**空槽位**分配：对端也可能是 peer-001（例如重连占用空槽），
        // 因此必须由对端 ID 取反，而不是假设"host 恒为 peer-001"
        SignalingIdentity.update(peerJoined("peer-001"))
        assertEquals("peer-002", SignalingIdentity.selfPeerId.value)
        assertEquals("peer-001", SignalingIdentity.remotePeerId.value)
    }

    @Test
    fun joinerTakesSelfFromJoined() {
        SignalingIdentity.update(joined("peer-002"))
        assertEquals("peer-002", SignalingIdentity.selfPeerId.value)
    }

    @Test
    fun peerJoinedDoesNotOverrideKnownSelf() {
        SignalingIdentity.update(joined("peer-002"))
        SignalingIdentity.update(peerJoined("peer-001"))
        assertEquals("peer-002", SignalingIdentity.selfPeerId.value)
        assertEquals("peer-001", SignalingIdentity.remotePeerId.value)
    }

    @Test
    fun unknownPeerIdLeavesSelfEmptyForUiPlaceholder() {
        SignalingIdentity.update(peerJoined("peer-999"))
        assertEquals("", SignalingIdentity.selfPeerId.value)
        assertEquals("peer-999", SignalingIdentity.remotePeerId.value)
    }

    @Test
    fun peerLeftClearsRemoteButKeepsSelf() {
        SignalingIdentity.update(joined("peer-002"))
        SignalingIdentity.update(peerJoined("peer-001"))
        SignalingIdentity.clearRemote()
        assertEquals("peer-002", SignalingIdentity.selfPeerId.value)
        assertEquals("", SignalingIdentity.remotePeerId.value)
    }

    @Test
    fun resetClearsBoth() {
        SignalingIdentity.update(joined("peer-002"))
        SignalingIdentity.reset()
        assertEquals("", SignalingIdentity.selfPeerId.value)
        assertEquals("", SignalingIdentity.remotePeerId.value)
    }

    // ============================ 构造辅助 ============================

    private fun joined(peerId: String) = SignalingMessage.Joined(
        roomId = "ABC234",
        stunUrl = "stun:127.0.0.1:3478",
        turnUrl = "turn:127.0.0.1:3478?transport=udp",
        turnUsername = "demo",
        turnCredential = "demopass",
        peerId = peerId,
    )

    private fun peerJoined(peerId: String) = SignalingMessage.PeerJoined(peerId)
}
