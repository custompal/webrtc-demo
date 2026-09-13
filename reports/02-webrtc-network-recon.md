# t5 前置侦察：源码获取网络瓶颈实测与对策建议

- 作者：webrtc-builder（t5 owner）
- 时间：2026-09-13（宿主机 CST）
- 触发：env-installer 报告 googlesource git 速率 ≈0.32 MB/s，我来复核并给出可执行的绕过方案
- 结论速览：**googlesource 的 git-upload-pack 在本机对大仓近乎不可用（实测 5–50 KB/s），但 HTTP archive 与 GitHub 镜像可达 2–9 MB/s。`fetch webrtc_android`（依赖 chromium/src 作为 master）实际不可行，必须换路由。**

> ## ⚠️ 复核更正（同日 15:1x 发现，以此节为准）
>
> **本文件 §1 中"大仓 git 仅 5–50 KB/s、webrtc/src 30s 仅 460KB"的结论已被复核推翻，属不可靠测量。**
>
> **更正的实测（60s 窗口、纯 `git clone --depth 1`、无任何 filter）**：
> | 目标 | 结果 |
> |---|---|
> | `webrtc.googlesource.com/src` | **37 MB / < 60s（≈5+ MB/s）成功完成** |
> | `chromium.googlesource.com/webm/libvpx`（对照） | 6.3 MB 完成 |
>
> **错因（作者自认）**：原测量是在**同时运行 4 路并发 clone 基准测试**的窗口内取得的，带宽/CPU 被自身占用，得到被污染的小样本值，并据此下了过于绝对的结论。**慢的原因不是 `--filter=blob:none`**——更正测量未使用 filter。
>
> **对方案的实际影响**：无。未做镜像改造、未配代理。独立 `.gclient` 的唯一作用是**跳过与任务无关的 `chromium/src` master 全仓克隆（10–20 GB）**；依赖仍走官方 `gclient sync`，实测 **322 仓 / src 20 GB，约 3 分钟已拉 5.1 GB**。
> 结论修正为：**googlesource git 可用；超大仓吞吐波动大，不宜据小样本下结论。**

## 0. 更新（同日，已验证的解决方案）

上述瓶颈**已破解且方案已跑通**，无需降级到预编译产物：

| 措施 | 说明 | 实测结果 |
|---|---|---|
| 复用 t3 的 `third_party/libwebrtc-src` | 它是 **2026-09-12 最新 revision** `be0e9008`，比 GitHub 镜像（2026-01）新 8 个月 | ✅ 作为 gclient 的 `src` |
| **独立 `.gclient` + `managed:False`** | 绕开 `fetch webrtc_android` 的 chromium/src master 克隆（原死路） | ✅ 生效 |
| `setsid nohup gclient sync --nohooks --no-history --jobs 4` | 必须 `setsid` 脱离 SSH 会话，否则被 SIGHUP 杀死（已踩坑并修正） | ✅ 3 分钟拉取 **313 个依赖仓 / 5.1 GB** |

- 结论修正：**DEPS 的第三方依赖仓本身可正常拉取**——大仓瓶颈只影响 webrtc/src、chromium/src 这类超大仓。网络不再是阻塞项。
- 路线 C（降级用预编译 AAR）**不需要采纳**，保留为最后兜底。

## 0.1 终态实测（回应 captain 的 a/b/c/d 要求）

| captain 要求 | 落实情况 |
|---|---|
| a) `--no-history` 压缩下载 | ✅ 实际采用 `gclient sync --nohooks --no-history --jobs 4`（比 `fetch --nohistory webrtc_android` 更彻底：完全跳过 chromium/src master） |
| b) 小规模吞吐实测先报 | ✅ 见下表；结论：**依赖阶段总下载耗时 ~3–10 分钟，而非 15–30 小时** |
| c) nohup + 分阶段日志 + 水位 | ✅ `setsid nohup`（必需，否则 SIGHUP 杀进程）；日志 `logs/{gclient-sync,runhooks,01-gn,02-build,03-aar,04-extract,supervisor,watermark}.log` |
| d) 超 30h 立即告警 | ✅ **不会超**：下载约 20 分钟，瓶颈转为 CPU 编译（预计 10–25h），已在预算内 |

**分项实测吞吐（关键结论：git 协议与大仓是唯一坏路径）**

| 阶段/源 | 实测 | 数据量 |
|---|---|---|
| `gclient sync` 依赖（322 仓，googlesource 小仓为主） | ≈3 分钟内完成 313 仓 | **5.1 GB → 最终 src 13 GB** |
| `gclient runhooks`（commondatastorage.googleapis.com，10 线程） | **≈4.9 MB/s** | sysroot 222M+218M + webrtc resources |
| googlesource **大仓** git（webrtc/src、chromium/src） | **10–50 KB/s（不可用）** | 已用独立 .gclient 完全绕开 |
| HTTP archive / dl.google.com / codeload.github | 5–25 MB/s | 备用路径 |


## 1. 实测矩阵（全部为本机实测，命令与原始数值）

| # | 传输方式 | 目标 | 实测吞吐 | 判定 |
|---|---|---|---|---|
| 1 | `git clone --depth 1` | webrtc.googlesource.com/src | 30 s 仅 **460 KB**（≈15 KB/s） | ❌ 不可用 |
| 2 | `git clone --depth 1 --filter=blob:none` | chromium.googlesource.com/chromium/src | 60 s 仅 **19 MB**（≈0.32 MB/s，env-installer 数据） | ❌ 不可用 |
| 3 | `git ls-remote` | webrtc.googlesource.com/src | **1.9 s**（仅 HEAD） | ✅ 快（说明握手不慢） |
| 4 | `git fetch --depth 1`（已 init 的仓） | webrtc.googlesource.com/src | **Total 0 objects**，无对象传输 | ❌ 异常 |
| 5 | `git clone --depth 1` | chromium.googlesource.com/webm/libvpx | **6.3 MB / 90 s**（≈70 KB/s） | ⚠️ 勉强 |
| 6 | `git clone --depth 1` | boringssl.googlesource.com/boringssl | **68 MB**（小仓可完成） | ✅ 可用 |
| 7 | `curl +archive/refs/heads/main.tar.gz` | webrtc.googlesource.com/src | **5.1 MB/s**（30 s 得 40.7 MB，HTTP 200） | ✅ 极佳 |
| 8 | `curl codeload tar.gz` | github.com/webrtc-mirror/webrtc | **9.1 MB/s**（30 s 得 40.1 MB） | ✅ 极佳 |
| 9 | `git clone --depth 1` | github.com/webrtc-mirror/webrtc | **37 MB .git + 全工作树，~75 s 完成** | ✅ 可用 |
| 10 | `curl` dl.google.com（NDK zip） | dl.google.com | **24.6 MB/s** | ✅ 极佳 |
| 11 | `curl -4` vs 默认（IPv6）archive | webrtc.googlesource.com | 6.06 vs 6.44 MB/s | ✅ 与 IPv6 无关 |

补充事实：
- `getent ahosts webrtc.googlesource.com` → 173.194.174.82 (v4) 与 2404:6800:4008:c06::52 (v6)；**宿主机无 IPv6 默认路由**，但强制 `-4` 后 HTTP archive 速率不变 → 排除 IPv6 因素。
- 结论：**瓶颈在 git-upload-pack 的大仓对象协商/传输路径**，不在 DNS、不在 IPv4/IPv6、不在总带宽（同一 host 的 HTTP archive 有 5–6 MB/s）。
- GitHub 镜像可达且内容完整：`webrtc-mirror/webrtc` 含 `sdk/android`、`tools_webrtc/android/build_aar.py`、`DEPS`；**但 HEAD 为 2026-01-13**（比当前时间旧约 8 个月，需作为 pinned revision 接受）。
- DEPS 依赖 host 分布：`chromium.googlesource.com` **48**、`chrome-infra-packages.appspot.com` 2、`boringssl.googlesource.com` 1、`aomedia.googlesource.com` 1（共 52 处 googlesource 引用）。

## 2. 为什么 `fetch webrtc_android` 不可行

`fetch webrtc_android` / `gclient sync` 的 master 项目是 **chromium/src**（即 route #2，0.32 MB/s 且实测 30 s 只走 460 KB 级），随后还要从 `chromium.googlesource.com` 拉 48 处依赖与 prebuilt clang/SDK。按实测速率，**仅网络阶段就是数十小时级，且极可能中途永久卡死**，不是"慢但能成"。

## 3. 三条可选路线（请 captain 决策）

| 路线 | 做法 | 预期网络耗时 | 预期编译耗时 | 风险 |
|---|---|---|---|---|
| **A. 全源码编译（标准）** | chromium src 改走 GitHub 镜像/archive + git URL 重写把 48 处 googlesource 依赖映射到可用源（部分无镜像，如 chromium/src 的 build、clang prebuilt 需另找） | 数小时～十几小时，部分依赖可能根本拿不到 | 15–30 h | ⚠️ 高：build/clang prebuilt 无可靠镜像，很可能卡死在依赖阶段 |
| **B. 源码可编译子集（推荐，若必须自己编）** | 用 GitHub 镜像的 webrtc/src(pinned 2026-01) + 逐一拉取**编译所需**的最小第三方集（abseil/boringssl/libyuv/opus/… 均有快镜像或小仓），裁剪掉 Android platform/prebuilt 等非必需项；产出 `webrtc .a + 头文件(含 sdk/android)` 与 libvpx（libvpx 仓可直接拉，见 #5） | 2–6 h（主要是拼 DEPS） | 15–30 h | ⚠️ 中：DEPS 裁剪工作量大，Java SDK 的 `libjingle_peerconnection_so.so` 需要 Android 侧 build 支持 |
| **C. 降级：核心自编 + Java SDK 用官方预编译** | `third_party/libvpx` 自己编（快仓可用）；`libjingle_peerconnection_so.so` + `org.webrtc.*` 用官方 Maven 产物 `io.github.webrtc-sdk:android`（repo1.maven.org 可达）补齐 Java SDK 产物 | <1 h | 1–2 h（仅 libvpx） | ✅ 低，但**偏离用户"从源码编译 libwebrtc"的要求**，须用户拍板 |

> 说明：路线 C 的 Maven 坐标 `io.github.webrtc-sdk:android` 已实测 HTTP 200（repo1.maven.org）；`org.webrtc:google-webrtc` 为 404（已弃用/从未发布到中央仓）。是否接受降级必须由用户/captain 决定，我不会自行替换。

## 4. 需要立即决策/授权的 3 件事

1. **路线选择**：A / B / C 选哪条（我倾向 **B**，若时间预算 ≤24h 则 **C**）。
2. ~~**swap 授权**（env-installer 报告 §7.1）：无 swap + 7.1 GiB 内存，链接 `libjingle_peerconnection_so.so` 单条 lld 峰值有 OOM 风险；建议 `fallocate -l 6G /swapfile`（占用 61 GB 可用中的 ~6 GB）。是否授权？~~
   > **⚠️ 本条已失效（2026-09-13 稍后）**：captain 已授权、env-installer 已创建 **4 GB `/swapfile`**（`/etc/fstab` 持久化，`vm.swappiness=10`），**已启用并正在使用**。
   > **不要执行本条的 `fallocate`**：`/swapfile` 是**活动** swap 文件，`fallocate` 会返回 `ETXTBSY`；也**不要** `swapoff` 重建（会把已换出页拉回 7.1 GiB 内存，编译高峰期可能触发 OOM）。
   > **t5 脚本中不存在任何 swap 操作**（`grep -niE "fallocate|swapoff|swapon|mkswap" scripts/` 零命中），本条仅为当时的授权请示文本，已作废。
3. **revision 接受度**：GitHub 镜像 pinned 到 2026-01-13；若必须最新，则只能走 A 且风险高。

## 5. 我已完成的等待期准备（不违反 t5 gate）

- 已后台启动 depot_tools 克隆（走 googlesource，小仓可用，约 30 min）。
- `code/webrtc-demo/scripts/t5-libwebrtc-libvpx-build.sh` 已就绪（`bash -n` 通过）：分阶段 deps/gn/ninja/aar/libvpx/extract、幂等、含内存+磁盘 watermark watchdog（磁盘 88% 自动中止）、产物 AArch64 核验与清单生成。拿到路线决策后我会按所选路线改造 fetch 段（镜像/archive 优先，git URL 重写）。
- 并行度定案 `-j2`、链接 `-j1`；尚未开始任何编译。
