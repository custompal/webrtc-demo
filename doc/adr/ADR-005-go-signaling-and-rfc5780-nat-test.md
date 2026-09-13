# ADR-005：信令用 Go 单二进制 + 自研 RFC5780 NAT 探测

- 状态：Accepted（2026-09-09）

## 背景
- 信令非学习重点，需省事；部署要干净。
- NAT 类型展示需“真类型”，而 libwebrtc 只暴露 candidate 类型（host/srflx/relay）。

## 决策
- 信令 = Go 单二进制 WebSocket 服务（按 room id 中继 SDP/ICE，下发 coturn 配置）。
- NAT 类型 = 自研 RFC 5780 mini STUN 测试客户端，判定 Full-Cone / Symmetric 等。

## 后果
- 优点：Go 单二进制部署最干净（丢机器即跑、systemd 托管）；自研 NAT 探测学到 RFC5780 行为与 NAT 类型判定。
- 缺点：信令语言与 C++ 栈不一致（但信令非重点，可接受）；NAT 探测额外工作量适中。
- 边界：采集 / 解码用 libwebrtc 内置（Camera2Capturer + MediaCodec），不自研。

## 相关
- `01-cloud-infra.md`（信令部署）、`02-architecture.md`（JNI 界面含 NAT 探测回调）。
