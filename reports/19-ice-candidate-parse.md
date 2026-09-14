# t49 修复报告：IceCandidateInfo 候选解析字段错位

## 根因（被测代码自身的 bug，非测试夹具问题）
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/IceCandidateInfo.kt` 的 `parse()`：

```kotlin
val tokens = sdp.trim().removePrefix("a=").split(' ').filter { it.isNotEmpty() }
val body = if (tokens.firstOrNull()?.startsWith("candidate:") == true) tokens.drop(1) else tokens
```

候选行的第一个 token 形如 `candidate:2998576043` —— **`candidate:` 前缀与 foundation 在同一个 token 内**。
原实现用 `tokens.drop(1)` 丢掉**整个** token，等于把 foundation 一起删掉，`body` 相对注释声明的
`[foundation, component, protocol, priority, address, port, typ, type, …]` **整体前移一格**：

| 字段 | 期望读到 | 实际读到（错位后） |
|---|---|---|
| foundation | `2998576043` | `1`（component） |
| component | `1` | `udp` → `toIntOrNull()==null` → `0` |
| protocol | `udp` | `2122260223`（priority） |
| address | `192.168.10.7` | `41234`（port） |
| port | `41234` | `typ` → `0` |

与真机单测失败完全一致：`expected:<udp> but was:<2122260223>`、`expected:<120.230.119.5> but was:<7627>`、
`expected:<49160> but was:<0>`、`expected:<5000> but was:<0>`。

## 修复
只剥掉 `candidate:` 前缀、**保留 foundation 作为 body[0]**：

```kotlin
val raw = sdp.trim().removePrefix("a=").split(' ').filter { it.isNotEmpty() }
val body = raw.mapIndexed { index, token -> if (index == 0) token.removePrefix("candidate:") else token }
```

## 验证
1. 等价算法在 4 个测试夹具（host / srflx+raddr+rport / relay / `a=` 前缀+多余空白）上复刻执行：
   `全部 4 个夹具映射正确 ✓ (bad=0)`（脚本 `tmp/t49-fix-report.md` 同目录，node 复刻）。
2. 完整验证 = 宿主机构建的单测（`IceCandidateInfoTest` 4 例 + 全量 67 例），由随后的合并构建承担。

## 影响说明
该解析器的输出会写进 `ice_candidate_local` / `ice_candidate_remote` 诊断事件（t44 新增）。
修复前它会报告错误的 type/address/port，**足以把"relay 是否真的被采集/是否被使用"的判断带偏**；
修复后 `summary()` 输出的 `type/proto/addr/port` 与真实候选串一致。

## 归属
captain 修复（native-dev 在 claim t49 后连续遭遇平台级不可恢复 turn 失败）。
