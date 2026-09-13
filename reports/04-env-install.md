# 04 宿主机安装 Android SDK+NDK、JDK 17、cmake/ninja（t4）

- 任务：t4（work，依赖 t1）— 宿主机安装 Android SDK+NDK、JDK 17、cmake/ninja 到工作区根目录
- 执行人：env-installer（attempt 1，attempt_id `fb53a46f-5269-4c23-8454-8e4e049a4374`）
- 执行位置：**宿主机 root**（`ssh … root@172.21.0.219 -p 5766`，每次用完 `exit`）；工作区根 `/opt/dsh-workspaces`（≙ 容器 `/data/dsh/home/workspace`）
- 执行时间：2026-09-13 15:33–15:38（CST）
- 结果：**成功**。JDK 17、Android SDK（cmdline-tools/platform-tools/platforms;android-34/build-tools;34.0.0/cmake;3.22.1/NDK 26.1.10909125）与系统 cmake/ninja 全部就位并逐项验证通过；`env.sh` 可在新 shell source 生效（含无条件引入 `env-go.sh`）。

---

## 1. 目标

在宿主机把 **JDK 17 + Android SDK + NDK + cmake + ninja** 安装到工作区根目录，供 t5（libwebrtc/libvpx 编译）、t7/t8（native/上层开发）与 t10（宿主机构建 APK）使用；产出可 source 的 `env.sh`；统一共享区属主为 1000:1000。
**范围外**：Go 工具链（t13，go-dev 已在容器内完成，本任务未安装、未修改其文件）。

## 2. 执行步骤与关键命令

### 2.0 预检（读 t1 报告）

```bash
cat reports/01-host-recon.md     # 结论：apt 可用；dl.google.com 20–24 MB/s；属性陷阱需 chown
ls /usr/lib/jvm ; ls /opt/dsh-workspaces/android-sdk   # 均不存在/为空 → 需全新安装
df -h /                          # 5.2G 已用 / 61G 可用
```

### 2.1 宿主机 apt 批量安装（JDK 17 + 系统 cmake/ninja + 构建依赖）

```bash
cat > /tmp/t4-apt.sh <<'EOF'
#!/bin/bash
set -x
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
  openjdk-17-jdk cmake ninja-build pkg-config zip unzip \
  autoconf automake libtool bison flex gperf \
  libglib2.0-dev libpulse-dev libx11-dev libxext-dev libxfixes-dev libxcomposite-dev \
  libxdamage-dev libxrandr-dev libxrender-dev libxtst-dev libxi-dev
EOF
nohup bash /tmp/t4-apt.sh > /tmp/t4-apt.log 2>&1 &   # 后台跑，避免 SSH 会话超时
```
结果：`APT_EXIT=0`。安装的版本（`dpkg -l` 实测）：

```
openjdk-17-jdk:amd64  17.0.20+8-1~24.04
cmake                 3.28.3-1build7
ninja-build           1.11.1-2
```
> 附带装上 autoconf/automake/libtool/bison/flex/gperf 与构建依赖（libglib2.0-dev、libpulse-dev、X11 系列），t1 报告已记录这些缺失项，属 t4 安装范围，避免 t5/install-build-deps 阶段再卡一次。

### 2.2 Android cmdline-tools（sdkmanager）安装到 `<WS>/android-sdk/`

```bash
URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
curl -sSL -o /tmp/cmdline-tools.zip "$URL"
sha256sum /tmp/cmdline-tools.zip      # 见 §5.1
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
mkdir -p $SDK/cmdline-tools
mv /tmp/cmdline-tools-extract/cmdline-tools $SDK/cmdline-tools/latest
```
> sdkmanager 要求目录形如 `<sdk>/cmdline-tools/latest/bin/…`，故把 zip 内的 `cmdline-tools/` 重命名放入 `latest/`。

### 2.3 接受 licenses + 安装 SDK 组件（NDK 版本决策见 §3）

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/dsh-workspaces/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses      # 全部 accept
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "cmake;3.22.1" "ndk;26.1.10909125"
```
> 首次尝试失败（见 §6 问题 1：JAVA_HOME 未就绪），修正后一次成功。

### 2.4 写 `env.sh`（宿主机）与 `env-container.sh`（容器路径映射）

- `<WS>/env.sh`（宿主机用）：导出 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`、`ANDROID_HOME/ANDROID_SDK_ROOT=<WS>/android-sdk`、`ANDROID_NDK_HOME/ANDROID_NDK_ROOT=<WS>/android-sdk/ndk/26.1.10909125`、`ANDROID_CMAKE=<WS>/android-sdk/cmake/3.22.1/bin/cmake`，并把 `java/JDK、cmdline-tools、platform-tools、emulator` 加入 PATH；**无条件**包含：
  ```sh
  [ -f "$WORKSPACE_ROOT/env-go.sh" ] && . "$WORKSPACE_ROOT/env-go.sh"
  ```
  （已实测并入 t13 的 Go 环境：`GOROOT=/opt/dsh-workspaces/go`、`GOPROXY=https://goproxy.cn,https://proxy.golang.org,direct`、`go1.22.12`）
- `<WS>/env-container.sh`（附赠）：容器内挂载点为 `/data/dsh/home/workspace`，无法直接复用同一个 `env.sh`；该脚本同时导出**容器可达路径**与**宿主机绝对路径**（`HOST_*` 变量），供 native-dev/android-dev 生成 `local.properties`/脚本时路径对齐，并明确提示"真正的 APK 构建在宿主机执行、容器内没有 JDK"。两个文件均由 t4 创建，互不覆盖。

### 2.5 属主修正 + 逐项验证

```bash
chown -R 1000:1000 /opt/dsh-workspaces/android-sdk /opt/dsh-workspaces/env.sh /opt/dsh-workspaces/env-container.sh
chmod 755 /opt/dsh-workspaces/env.sh /opt/dsh-workspaces/env-container.sh
find /opt/dsh-workspaces/android-sdk … ! -uid 1000    # → 空（全部 1000:1000）
```
验证分三路：① 新 shell `source env.sh` 后逐项打版本；② 直接跑 `sdkmanager --list`；③ **以 uid 1000（admin）身份**再跑一遍，证明后续非 root 构建可用。

## 3. 关键决策

1. **JDK 17 采用 apt 版**（`openjdk-17-jdk 17.0.20`）而非下载 tarball 到 `<WS>/jdk/`。理由：Ubuntu 24.04 自带 17.0.20，依赖（ca-certificates、fontconfig 等）由 apt 一并解决，避免手工解压后缺依赖；`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` 稳定可写进 `env.sh`，满足"给出稳定的 JAVA_HOME"。**偏差需记录**：任务写的是"装到 `<WS>/jdk/`"，本实现落在系统路径 `/usr/lib/jvm/…`（未创建 `<WS>/jdk/`）；若 verifier/captain 坚持要求 JDK 落在工作区内，可追加软链或重装 tarball（属可快速补做的变更）。
2. **NDK 版本选择 r26 系列 → `26.1.10909125`（r26b）**。`doc/13` 要求 NDK 26+，`doc/08` 说用 libwebrtc 自带 `android_toolchain`，两说冲突、契约未定；按任务指示"契约未定则选 r26 系列并记录"。r26b 是 r26 系列成熟版本，配 AGP 8.x/CMake 3.22 稳定。**t5 若用 webrtc 自带 toolchain，本 NDK 仍可与 AGP/CMake 并存互不影响**（AGP 通过 `ndkVersion` 指定）。
3. **cmake 双份并存**：系统 `cmake 3.28.3`（PATH 优先，已验证 ≥3.22）与 SDK 内 `cmake;3.22.1`（AGP `externalNativeBuild` 可指定）。两者都装，AGP 走 SDK 版、手工编译脚本走系统版。
4. **build-tools 选 34.0.0**：与 `platforms;android-34`、AGP 8.x 默认组合匹配（doc/05 提到 AGP 8.5/CMake 3.22+/NDK r25c+）。未强装 35.x，避免与上层契约不确定的 compileSdk 相冲突。
5. **批量安装构建依赖**：把 autoconf/automake/libtool/bison/flex/gperf 与 libglib2.0-dev/libpulse-dev/X11 头文件一并装好，减少 t5 阶段因缺依赖返工。
6. **后台执行 + 日志**：apt 与 sdkmanager 下载量大（cmdline-tools 153 MB、NDK ~2.1 GB），全部 `nohup … &` 后台跑并落日志 `/tmp/t4-*.log`，避免 SSH 长会话断开导致半途而废。

## 4. 交付物与绝对路径

| 交付物 | 宿主机绝对路径 | 容器侧等价路径 |
|---|---|---|
| JDK 17 | `/usr/lib/jvm/java-17-openjdk-amd64`（`JAVA_HOME`） | 不可见（JDK 只在宿主机） |
| Android SDK 根 | `/opt/dsh-workspaces/android-sdk` | `/data/dsh/home/workspace/android-sdk` |
| cmdline-tools（sdkmanager） | `…/android-sdk/cmdline-tools/latest/bin/sdkmanager` | 同左（换根） |
| platform-tools（adb 37.0.1） | `…/android-sdk/platform-tools` | 同左 |
| platforms;android-34 | `…/android-sdk/platforms/android-34` | 同左 |
| build-tools;34.0.0 | `…/android-sdk/build-tools/34.0.0` | 同左 |
| NDK 26.1.10909125 | `…/android-sdk/ndk/26.1.10909125`（`ANDROID_NDK_HOME`） | 同左 |
| SDK cmake 3.22.1 | `…/android-sdk/cmake/3.22.1` | 同左 |
| licenses | `…/android-sdk/licenses/`（7 个 license 文件） | 同左 |
| 环境脚本（宿主机） | `/opt/dsh-workspaces/env.sh` | `/data/dsh/home/workspace/env.sh` |
| 环境脚本（容器路径映射） | `/opt/dsh-workspaces/env-container.sh` | `/data/dsh/home/workspace/env-container.sh` |
| 系统 cmake / ninja | `/usr/bin/cmake`（3.28.3）/ `/usr/bin/ninja`（1.11.1） | 不可见 |
| 本报告 | `…/code/webrtc-demo/reports/04-env-install.md` | 同左 |

**磁盘占用**：`android-sdk` 合计 **2.6 GB**（ndk 2.1 G、build-tools 151 M、cmdline-tools 148 M、platforms 138 M、cmake 61 M、platform-tools 22 M、licenses 32 K）；根分区已用 8.9 G / 可用 57 G。本任务未创建 `<WS>/jdk/`（见 §3 决策 1）。

## 5. 验收证据（原始输出）

### 5.1 安装来源与校验

```
zip size: 153607504
2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258  cmdline-tools.zip
   ← https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip (sdkmanager 12.0)
licenses 已写入: android-sdk-license, android-sdk-preview-license, android-googletv-license,
                 android-googlexr-license, android-sdk-arm-dbt-license, google-gdk-license,
                 mips-android-sysimage-license
```

### 5.2 `sdkmanager --list`（摘录 installed 段，退出码 0）

```
Installed packages:
  Path                 | Version       | Description                      | Location
  build-tools;34.0.0   | 34.0.0        | Android SDK Build-Tools 34       | build-tools/34.0.0
  cmake;3.22.1         | 3.22.1        | CMake 3.22.1                     | cmake/3.22.1
  ndk;26.1.10909125    | 26.1.10909125 | NDK (Side by side) 26.1.10909125 | ndk/26.1.10909125
  platform-tools       | 37.0.1        | Android SDK Platform-Tools       | platform-tools
  platforms;android-34 | 3             | Android SDK Platform 34          | platforms/android-34
SDKMANAGER_LIST_EXIT=0
```

### 5.3 逐项版本验证（`source env.sh` 后，宿主机 root）

```
[1] java -version
openjdk version "17.0.20" 2026-07-21
OpenJDK Runtime Environment (build 17.0.20+8-1-24.04-Ubuntu)
[2] javac -version            → javac 17.0.20
[3] cmake --version           → cmake version 3.28.3        (≥3.22 ✅)
[4] ninja --version           → 1.11.1
[5] sdkmanager --version      → 12.0
[6] platforms;android-34      → /opt/dsh-workspaces/android-sdk/platforms/android-34
                                Pkg.Desc=Android SDK Platform 14 / Platform.Version=14
[7] NDK                       → /opt/dsh-workspaces/android-sdk/ndk/26.1.10909125
                                Pkg.Revision = 26.1.10909125
[8] build-tools               → 34.0.0
[9] SDK cmake                 → cmake version 3.22.1-g37088a8
[10] platform-tools           → Android Debug Bridge version 1.0.41 / 37.0.1-15733141
```

### 5.4 `env.sh` 在新 shell source 生效（关键：Go 环境已被无条件并入）

```
$ ssh … bash -lc ". /opt/dsh-workspaces/env.sh --print"
WORKSPACE_ROOT   = /opt/dsh-workspaces
JAVA_HOME        = /usr/lib/jvm/java-17-openjdk-amd64
ANDROID_HOME     = /opt/dsh-workspaces/android-sdk
ANDROID_NDK_HOME = /opt/dsh-workspaces/android-sdk/ndk/26.1.10909125
GOROOT           = /opt/dsh-workspaces/go          ← 来自 env-go.sh
GOPROXY          = https://goproxy.cn,https://proxy.golang.org,direct
--- 版本 ---
openjdk version "17.0.20" …  / javac 17.0.20 / cmake version 3.28.3 / 1.11.1
sdkmanager = present ；NDK = present (/opt/dsh-workspaces/android-sdk/ndk/26.1.10909125)
go version go1.22.12 linux/amd64
```

### 5.5 以 **uid 1000（admin）** 复验（证明非 root 用户/后续构建可直接用）

```
$ sudo -u admin -H bash -lc '. /opt/dsh-workspaces/env.sh >/dev/null 2>&1; …'
java=openjdk version "17.0.20" 2026-07-21
javac=javac 17.0.20
cmake=cmake version 3.28.3
ninja=1.11.1
sdkmanager=12.0
ANDROID_HOME=/opt/dsh-workspaces/android-sdk
NDK clang 可执行: …/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang
exit=0
```

### 5.6 容器侧路径映射复验（`env-container.sh`）

```
WORKSPACE_ROOT(container) = /data/dsh/home/workspace
ANDROID_HOME(container)   = /data/dsh/home/workspace/android-sdk
ANDROID_NDK_HOME(cont.)   = /data/dsh/home/workspace/android-sdk/ndk/26.1.10909125
  OK      /data/dsh/home/workspace/android-sdk
  OK      /data/dsh/home/workspace/android-sdk/ndk/26.1.10909125
  java: 未安装（符合预期：JDK 在宿主机）
  go:   go version go1.22.12 linux/amd64
```

### 5.7 属主与磁盘

```
$ find /opt/dsh-workspaces/android-sdk /opt/dsh-workspaces/env.sh /opt/dsh-workspaces/env-container.sh ! -uid 1000
（无输出 → 全部 1000:1000）
$ ls -ld /opt/dsh-workspaces        → drwxr-x--- admin admin
$ du -sh /opt/dsh-workspaces/android-sdk → 2.6G
$ df -h /  → /dev/vda3  69G  8.9G  57G  14% /
```

### 5.8 与 t2 契约（doc/14-interface-contract.md §3.5）一致性复核

```
契约要求                                            本任务实装                                    结论
JDK 17 / JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  openjdk 17.0.20 @ /usr/lib/jvm/java-17-openjdk-amd64  ✅
ANDROID_HOME=/opt/dsh-workspaces/android-sdk           /opt/dsh-workspaces/android-sdk                       ✅
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125       …/ndk/26.1.10909125                                   ✅
compileSdk/targetSdk/minSdk = 34/34/26 → build-tools 34 build-tools;34.0.0 + platforms;android-34             ✅
Gradle 8.7（wrapper）                                  由 t8 提供 gradle wrapper；本机不用系统 gradle        —
```

## 6. 遇到的问题及自行解决的尝试

| # | 问题 | 尝试与结果 | 状态 |
|---|---|---|---|
| 1 | 第一次跑 `sdkmanager --install` 报 `ERROR: JAVA_HOME is not set and no 'java' command could be found`，仅 `cmdline-tools/` 就位 | 定位为**并发时序**：apt 装 JDK 尚未完成时脚本已执行 `JAVA_HOME=$(ls -d …)` 得到空值。改为等 JDK 落地后显式写死 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` 重跑 → licenses 全部接受、5 个包一次装齐 | ✅ 已解决（非镜像/网络问题，未消耗重试次数） |
| 2 | sdkmanager 要求 `<sdk>/cmdline-tools/latest/bin/…` 结构，zip 解压出来是 `cmdline-tools/` | `mv cmdline-tools-extract/cmdline-tools $SDK/cmdline-tools/latest` | ✅ 已解决 |
| 3 | NDK 版本二义（doc/13 要 26+，doc/08 说用 webrtc 自带 toolchain） | 按任务指示选 r26 系列 `26.1.10909125` 并记录；t2 契约 `doc/14 §3.5` 冻结后复核 **逐字一致**（AGP 用 `ndkVersion` 指定，与 webrtc 自带 toolchain 可并存） | ✅ 已解决（契约确认） |
| 4 | 属主：root 解压出的 `android-sdk/*` 与 `env*.sh` 在容器内属 root，会阻塞 node | 宿主机 `chown -R 1000:1000` + `chmod 755`，并用 `find ! -uid 1000` 复核为空；另以 `sudo -u admin` 实跑验证 | ✅ 已解决 |
| 5 | 宿主机 `/opt/dsh-workspaces` 根目录为 `drwxr-x---`（750） | 持有者即为 uid 1000，读写无碍；未改动根目录权限（避免影响其它组件），仅保证内部我新增路径属主正确 | ✅ 已确认无需变更 |
| 6 | 下载量大（NDK 2.1 GB），SSH 长会话有中断风险 | 全部 `nohup … &` 后台化 + 日志落盘，逐段轮询确认退出码 | ✅ 已规避 |

**重试次数**：1 次失败（问题 1）→ 1 次修正即成功，未达 3 次上限。

## 7. 未解决 / 需人工介入

1. **JDK 安装位置与任务字面要求有偏差**：任务写"装到 `<WS>/jdk/`"，实测装在系统 `/usr/lib/jvm/java-17-openjdk-amd64`（未创建 `<WS>/jdk/`）。理由见 §3 决策 1。若验收方要求必须落在工作区内，可追加：(a) `ln -s /usr/lib/jvm/java-17-openjdk-amd64 /opt/dsh-workspaces/jdk`；或 (b) 下载 Adoptium/OpenJDK 17 tarball 解压到 `<WS>/jdk`。**请 captain/verifier 明确取舍**（我倾向 (a)，零下载、JAVA_HOME 仍稳定）。
2. **NDK 版本已与 t2 契约复核一致（无需变更）**：t2 已产出 `doc/14-interface-contract.md`，其 §3.5 明确要求 `JDK 17`、`ANDROID_HOME=/opt/dsh-workspaces/android-sdk`、`ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125`，并定 `compileSdk/targetSdk/minSdk = 34/34/26`——与本任务实装**逐条一致**。原 doc/13「NDK 26+」与 doc/08「用 webrtc 自带 android_toolchain」的冲突，按契约取 r26b（26.1.10909125）解决。
3. **build-tools 版本已对齐契约**：现装 `build-tools;34.0.0`，与 `compileSdk=34/targetSdk=34` 一致。仅当后续改 `compileSdk` 为 35 时，才需补 `build-tools;35.0.0` + `platforms;android-35`。
   → **契约符合性小结**：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` ✅、`ANDROID_HOME=/opt/dsh-workspaces/android-sdk` ✅、`ANDROID_NDK_HOME=…/ndk/26.1.10909125` ✅、`build-tools 34` ✅，全部与 doc/14 §3.5 逐字一致。
4. **t5 若走 libwebrtc 自带 `android_toolchain`**：该路径由 `gclient sync` 拉取（`src/third_party/android_toolchain`），与本任务装的 NDK 互不冲突，但会额外占用数 GB 磁盘——t1 的 61 GB 余量需持续关注（现余 57 GB）。
5. **容器内无 JDK/SDK 可执行环境**：容器 rootfs 只读、不能 apt，因此**容器内无法运行 Gradle/APK 构建**；t10 必须在宿主机执行。已通过 `env-container.sh --print` 明确提示，避免上层成员误在容器内构建。

---

# 附录 A：captain 决策后的追加执行（2026-09-13 15:38–15:40 CST）

> t4 主体验收在 §5 已完成。本附录记录 captain 五项决策中与宿主环境相关的两项落实（A1 swap、A2 缓存目录约定），以及 t1 报告的追加修订（A3）。

## A1. 4 GB swapfile 创建并启用（用户已授权）

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
```

```
$ swapon --show
NAME      TYPE SIZE USED PRIO
/swapfile file   4G   0B   -2
$ free -h
               total   used   free  shared  buff/cache  available
Mem:           7.1Gi  1.4Gi  236Mi   7.6Mi       5.7Gi      5.7Gi
Swap:          4.0Gi     0B   4.0Gi
$ cat /proc/swaps
/swapfile  file  4194300  0  -2
$ grep swapfile /etc/fstab
/swapfile none swap sw 0 0
$ df -h /  →  69G  11G  56G  16% /
```

- **`vm.swappiness` 已按 captain 指令设为 10**（原值 0）：见 A1.1。`swappiness=0` 并不禁用 swap，但会让内核在内存尖峰时**优先触发 OOM killer 而不是换出匿名页**——恰好削弱了 4 GB swap 作为"libwebrtc 链接阶段 OOM 保险"的意义，故改为 10（先换出、别杀进程）。
- fstab 已持久化，重启后自动生效。磁盘由 61 G → **56 G 可用**（符合 captain 总账：out 10–16G + src 20–24G + SDK/NDK 8–10G + submodule 150M + swap 4G）。
- `/swapfile` 为 `root:root 600`（root 专属，不参与共享区属主约定）。

### A1.1 swappiness=10 落地与一个"覆写陷阱"

```
$ cat /etc/sysctl.d/99-webrtc-demo.conf
# webrtc-demo: 4GB swapfile 为 libwebrtc 链接阶段内存尖峰做 OOM 保险
# swappiness=10 → 内存压力下优先换出匿名页，而不是让 OOM killer 杀进程
vm.swappiness = 10

$ sysctl vm.swappiness            →  vm.swappiness = 10
$ cat /proc/sys/vm/swappiness     →  10
$ free -h
               total   used   free  shared  buff/cache  available
Mem:           7.1Gi  1.6Gi  199Mi   7.4Mi       5.6Gi      5.5Gi
Swap:          4.0Gi  524Ki   4.0Gi      ← swap 已实际被使用（非 0），证明保险生效
$ swapon --show
NAME      TYPE SIZE USED PRIO
/swapfile file   4G 524K   -2
```

**陷阱**：首次 `sysctl -w vm.swappiness=10` 看似成功，但 `sysctl --system` 之后又被**打回 0**。根因——阿里云默认 `/etc/sysctl.conf` 第 1 行硬编码 `vm.swappiness = 0`，而 `sysctl --system` 的读取顺序把 `/etc/sysctl.conf` 排在**最后**，它反而**覆盖**了 `/etc/sysctl.d/99-webrtc-demo.conf` 的 10；只改 drop-in 文件**无法持久生效**。

处理（最小改动 + 可回滚）：

```bash
cp -a /etc/sysctl.conf /etc/sysctl.conf.bak-webrtc-demo-20260913-154136   # 先备份
sed -i 's|^[[:space:]]*vm\.swappiness[[:space:]]*=.*|# [webrtc-demo] 原为 vm.swappiness = 0，已由 /etc/sysctl.d/99-webrtc-demo.conf 覆写为 10（2026-09-13）|' /etc/sysctl.conf
sysctl --system
```

结果：`/etc/sysctl.conf` 第 1 行已注释（**其余内核参数一字未动**），`sysctl --system` 后 `vm.swappiness = 10` 稳定；备份 `/etc/sysctl.conf.bak-webrtc-demo-20260913-154136`（566 B，原文件）。
**回滚**：`cp -a /etc/sysctl.conf.bak-webrtc-demo-* /etc/sysctl.conf && rm -f /etc/sysctl.d/99-webrtc-demo.conf && sysctl --system`。

## A2. 缓存/临时目录一律落到工作区（写入 env.sh 与 env-container.sh）

针对"容器 `$HOME=/data/dsh/home` 在 workspace-write 沙箱下不可写、`/tmp` 可执行产物被拒"的实测约束：

| 变量 | 值 | 谁导出 |
|---|---|---|
| `GRADLE_USER_HOME` | `<WS>/.gradle-home` | 本任务（env.sh / env-container.sh） |
| `TMPDIR` | `<WS>/tmp` | 本任务（env.sh / env-container.sh） |
| `GOCACHE` | `<WS>/go/cache` | env-go.sh（t13），本任务不覆盖 |
| `GOTMPDIR` | `<WS>/go/tmp` | env-go.sh（t13），本任务不覆盖 |

目录 `<WS>/.gradle-home`、`<WS>/tmp` 已创建并 `chown 1000:1000`。两个脚本均用 `${VAR:-默认值}` 语法，允许后续成员用同名环境变量覆盖。

实测（宿主机，uid 1000 身份）：

```
$ sudo -u admin -H bash -lc ". /opt/dsh-workspaces/env.sh >/dev/null; \
    mkdir -p \$GRADLE_USER_HOME/caches && test -w \$GRADLE_USER_HOME/caches && echo GRADLE_CACHE_WRITABLE=yes; \
    touch \$TMPDIR/probe && echo TMPDIR_WRITABLE=yes"
GRADLE_CACHE_WRITABLE=yes
TMPDIR_WRITABLE=yes

$ bash -lc '. /opt/dsh-workspaces/env.sh --print' | grep -E 'GRADLE|GOCACHE|TMPDIR'
GRADLE_USER_HOME = /opt/dsh-workspaces/.gradle-home
GOCACHE        = /opt/dsh-workspaces/go/cache  GOTMPDIR = /opt/dsh-workspaces/go/tmp
TMPDIR         = /opt/dsh-workspaces/tmp
```
容器内（`env-container.sh --print`）：`GRADLE_USER_HOME=/data/dsh/home/workspace/.gradle-home`、`TMPDIR=/data/dsh/home/workspace/tmp`。

> **t10 构建约定（重要）**：在宿主机执行 `./gradlew` 前必须 `. /opt/dsh-workspaces/env.sh`，这样 Gradle 缓存落到 `<WS>/.gradle-home` 而不是 `/root/.gradle` 或容器内的不可写 `$HOME`。

## A3. 其它决策的落实情况（供 verifier 对照）

| captain 决策 | 落实 |
|---|---|
| 1) 创建 4 GB swap + swappiness=10 | ✅ A1 + A1.1（swap 4 GB 已启用并被实际使用 524K；swappiness **已设为 10** 并解决 `/etc/sysctl.conf` 的 0 覆写陷阱，已持久化） |
| 2) 云安全组由用户放行 | ⏭ 非本任务（无需操作）；t6/t12 外部实测为准 |
| 3) t1 报告追加修订段 | ✅ 已在 `reports/01-host-recon.md` **末尾追加**「修订（15:39）」R1 段（`--depth 1` 实测：libvpx 2.2 s、libwebrtc 120 MB/4.8 s ≈25 MB/s；原 0.32 MB/s 系 `--filter=blob:none` 部分克隆的测量误差），**未改动原 §1–§9 任何文字** |
| 4) submodule 不切 tag | ✅ 未执行任何 submodule 版本变更（仍 `d2413e2c` / `be0e9008`） |
| 5) t10 前统一提交一次 | ⏭ 待 t10；会先 `git status --short` + `git diff --cached --stat` 复核，并确保第三方产物/`.gradle`/apk/signaling 不入库、仓库增长 <50 MB |

附录执行后 t4 主验收项复测仍全部通过（java 17.0.20 / cmake 3.28.3 / ninja 1.11.1 / sdkmanager 12.0 / NDK 存在 / env.sh 可 source）。

---

# 附录 B：`.gitignore` 缺陷修复（C23/R7）与 t10 构建脚本草稿（2026-09-13 15:40–15:45 CST）

> captain 第 1 项指令的直接落实。属 t3 交付物（`.gitignore`）的缺陷修复，由 env-installer（t3/t4 同一人）执行；**未改动任何已交付报告的历史段落**。

## B1. 缺陷：裸模式 `signaling` 会吞掉整个 Go 源码目录

修复前 `.gitignore` 第 46–48 行：

```
signaling                      # ← 裸模式，匹配任意层级的 signaling 文件或目录
signaling-linux-amd64
code/webrtc-demo/signaling
```

修复前 `git check-ignore -v` 原始输出（**5/5 全部命中**，证明 Go 源码确实不会入库）：

```
signaling/main.go                .gitignore:46:signaling	signaling/main.go
signaling/signaling              .gitignore:46:signaling	signaling/signaling
signaling/signaling-linux-amd64  .gitignore:46:signaling	signaling/signaling-linux-amd64
signaling                        .gitignore:46:signaling	signaling
signaling/internal/room.go       .gitignore:46:signaling	signaling/internal/room.go
```
> `/signaling/` 下实测已有 `main.go`、`go.mod`、`go.sum`、`config/`、`protocol/`、`room/`、`server/`、`util/` 等 Go 源码，以及编译产物 `signaling`(5.4 MB) 与 `dist/`。

## B2. 修复后的规则（.gitignore 第 45–51 行）

```
# ===== Go 信令服务编译产物（只忽略二进制，绝不忽略 signaling/ 源码目录）=====
# 修复 C23/R7：原裸模式 `signaling` 会命中整个 signaling/ 目录 → Go 全部源码静默不入库。
# 必须使用带前导斜杠的精确路径（相对仓库根）。
/signaling/signaling
/signaling/signaling-linux-amd64
/signaling/dist/
/signaling/logs/
```

**captain 要求的验证命令与原始输出**：

```
$ git check-ignore -v signaling/main.go
（无输出）
$ echo $?
1                      ← 非 0 = 未被忽略 ✅

$ git status --short --untracked-files=all signaling/ | head -12
?? signaling/config/config.go
?? signaling/go.mod
?? signaling/go.sum
?? signaling/main.go
?? signaling/protocol/errors.go
?? signaling/protocol/message.go
?? signaling/room/manager.go
?? signaling/room/manager_test.go
?? signaling/room/peer.go
?? signaling/room/room.go
?? signaling/server/e2e_test.go
?? signaling/server/server.go      ← Go 源码已全部回到未跟踪（可入库）状态 ✅
```

二进制仍被正确忽略（逐条 `git check-ignore -q`，exit 0 = IGNORED）：

```
signaling/signaling                     IGNORED   [.gitignore:48:/signaling/signaling]
signaling/signaling-linux-amd64         IGNORED   [.gitignore:49:/signaling/signaling-linux-amd64]
signaling/dist/signaling-linux-amd64    IGNORED   [.gitignore:50:/signaling/dist/]
signaling/logs/signaling-live.log       IGNORED   [.gitignore:79:/signaling/logs/*]
reports/04-env-install.md               TRACKED
scripts/build_app.sh                    TRACKED
```

## B3. 连带修复：构建/运行期日志不入库

发现 `!reports/**`（"明确保留"块）会把 `reports/logs/*.log` 重新纳入跟踪（t10 的构建日志会被误提交）。注意 git 的优先级陷阱：
- 目录级 `/reports/logs/` **无效**——`!reports/**` 会把它重新包含（每次构建都生成新日志，若入库会污染历史与体积）；
- 必须使用**内容级**模式并放在 `!reports/**` **之后**（同层级后写者优先）：

```
/reports/logs/*
/signaling/logs/*
```

实测：`reports/logs/build_app-20260913-154016.log` → **IGNORED**；`reports/04-env-install.md` → **TRACKED**（报告正文仍入库）。

## B4. t10 构建脚本草稿：`scripts/build_app.sh`

按 captain 第 3 项要求**只脚本化、未提前跑构建**。脚本包含 9 个阶段：

| 阶段 | 内容 |
|---|---|
| 0 | 强制 `. <WS>/env.sh`；校验 `GRADLE_USER_HOME=<WS>/.gradle-home`、`TMPDIR=<WS>/tmp` 已生效 |
| 1 | 工具链版本核对：JDK 17、cmake ≥3.22、ninja、gradle wrapper = 8.7（契约 §3.3/§3.5） |
| 2 | **t5 产物就位检查**：`libwebrtc-arm64.aar`、`libwebrtc-java.jar`、`jni/arm64-v8a/libjingle_peerconnection_so.so`、`libvpx/lib/libvpx.a`、`libvpx/include/vpx/`，并用 `file` 核验 aarch64 |
| 3 | t7/t8 源码就位检查（CMakeLists、kotlin、app/build.gradle.kts、settings.gradle.kts） |
| 4 | **契约硬约束自检 §4.2**：`add_library(webrtcdemo_native SHARED` 必须命中；CMakeLists 中**禁止**出现 `libwebrtc.a`/`libjingle_peerconnection_so`；Kotlin 侧 `System.loadLibrary("webrtcdemo_native")` |
| 5 | **契约 §4.3**：把 `libjingle_peerconnection_so.so` 从 `third_party/libwebrtc/java/jni/arm64-v8a/`（缺则从 AAR 解包）复制到 `app/src/main/jniLibs/arm64-v8a/`，并 `file` 核验 aarch64 |
| 6 | 生成 `local.properties`（`sdk.dir` / `ndk.dir` 指向宿主机绝对路径） |
| 7 | `./gradlew --no-daemon assembleDebug`（**草稿：尚未执行**） |
| 8 | Go：`GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w"` → `signaling/dist/signaling-linux-amd64` |
| 9 | APK 解包核验：`lib/arm64-v8a/{libjingle_peerconnection_so.so, libwebrtcdemo_native.so}` 必须存在；dex 含 `org.webrtc` 类；`res/xml/file_paths.xml` + manifest 的 FileProvider（用 build-tools 的 `aapt2 dump xmltree`） |

用法：`bash scripts/build_app.sh --check-only`（只校验，不构建）/ `bash scripts/build_app.sh`（完整）。

**草稿在宿主机的 `--check-only` 实测输出（节选，证明前置校验逻辑可用、且不会误跑构建）**：

```
===== 0. 环境 =====   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  ANDROID_HOME=/opt/dsh-workspaces/android-sdk
                      GRADLE_USER_HOME=/opt/dsh-workspaces/.gradle-home  TMPDIR=/opt/dsh-workspaces/tmp   java 17.0.20
===== 1. 工具链 =====  javac 17.0.20 [OK JDK 17]  cmake 3.28.3 [OK ≥3.22]  ninja 1.11.1  wrapper=gradle-8.7-bin.zip
===== 2. t5 产物 ===== FAIL 缺失: third_party/libwebrtc/java/libwebrtc-arm64.aar
                       FAIL 缺失: …/libwebrtc-java.jar
                       FAIL 缺失: …/jni/arm64-v8a/libjingle_peerconnection_so.so
                       FAIL 缺失: third_party/libvpx/lib/libvpx.a
                       FAIL 缺失: third_party/libvpx/include/vpx        ← t5 未完成，符合预期
===== 3. 源码 =====     OK CMakeLists.txt  OK kotlin(8 个 .kt)  OK app/build.gradle.kts  OK settings.gradle.kts
===== 4. 契约约束 ====  OK 库名 = webrtcdemo_native (SHARED)   OK 未链接 libwebrtc.a / libjingle_peerconnection_so
                       WARN 未找到 System.loadLibrary("webrtcdemo_native")   ← t8 NativeLoader 尚未产出，符合预期
===== 6. local.properties ==== OK（已写；校验后已删除，避免容器侧误用宿主机路径）
== --check-only：不执行构建 ==   前置校验存在失败项，请先修复（EXIT=1）
```

结论：脚本前置校验已可用，**未触发任何 gradle 下载或编译**（未污染报告，未产生 APK 相关副作用）。
执行脚本过程中宿主机的编译无关副作用：生成 `reports/logs/build_app-<ts>.log`（已加入忽略）与临时 `local.properties`（已删除）；`reports/`、`scripts/` 属主已复核为 1000:1000（SSH root 执行产生的 root 属主已修正，`find ! -uid 1000` = 0）。

## B5. 待 t10 前完成的收尾项

1. t5 产物落地后重跑 `bash scripts/build_app.sh --check-only`，确认第 2 阶段全部 OK；
2. t8 补上 `System.loadLibrary("webrtcdemo_native")` 后，第 4 阶段 WARN 应转为 OK；
3. 首次真实构建前，先预热 Gradle wrapper 发行包（`services.gradle.org` 的 `gradle-8.7-bin.zip`，避免构建时才发现网络问题）；
4. 统一提交（captain 决策 5）前执行 `git status --short` + `git diff --cached --stat`，确认产物目录与日志未入库、仓库增长 <50 MB。

---

# 附录 C：为 t10 脚本增加"Kotlin 编译级早失败"阶段（2026-09-13 15:47 CST）

> 依据 captain 转达的 android-dev 建议（U4）。目的：t14 骨架与 t8 业务代码**在容器内永远无法编译验证**（容器无 JDK/SDK、rootfs 只读），先在宿主机跑 Kotlin 编译，**让 Kotlin 级错误在耗时更长的 native 编译与打包之前暴露**，同时为 verifier 提供"骨架 + t8 代码至少通过 Kotlin 编译"的编译级证据。

## C1. 新增阶段 6.5（位于 local.properties 之后、assembleDebug 之前）

```bash
# 脚本内实际执行的命令（属性名已按 t8 真实定义修正，见 C2）
. /opt/dsh-workspaces/env.sh && cd /opt/dsh-workspaces/code/webrtc-demo \
  && ./gradlew --no-daemon :app:compileDebugKotlin -PwebrtcDemo.skipNative=true
```

- 完整 stdout/stderr 落盘 `reports/logs/kotlin-compile-<ts>.log`（该目录已 gitignore），终端只 tail 25 行；
- **失败即停**：`FAILED!=0` 时跳过阶段 7/8/9，保留最早错误，绝不"跳过 Kotlin 检查继续打包"；
- 阶段 2 已保证 `third_party/libwebrtc/java/libwebrtc-java.jar`（`org.webrtc.*`）存在，**jar 未就绪时阶段 2 直接 FAIL 并停止**，不会静默跳过；
- 阶段 2 同时核验 AAR / `jni/arm64-v8a/libjingle_peerconnection_so.so` / `libvpx.a` / `include/vpx` 与 aarch64 架构——因此 6.5 的 Kotlin 编译是在"t5 产物齐全"的前提下运行的。

## C2. ⚠️ 属性名更正（captain 给的写法与实际不符）

captain 指令写的是 `-PwebrtcDemo.skipNative=true`（大写 W），**实际 t8 定义的是小写 w**：

```
$ grep -n "skipNative" app/build.gradle.kts
12://   -PwebrtcDemo.skipNative=true   跳过 externalNativeBuild。当 t7 的
18:    (findProperty("webrtcDemo.skipNative") as String?)?.toBoolean() ?: false
```

Gradle 属性名**大小写敏感**，`-PwebrtcDemo.skipNative` 不会被识别。脚本已改为：
- 优先静态探测 `app/build.gradle.kts` 中是否存在 `webrtcDemo.skipNative` → 用 **`-PwebrtcDemo.skipNative=true`**（当前命中此分支）；
- 探测不到时回退 captain 写法并打 `WARN`；失败时命令行属性不识别会保留原始错误，**不吞错误**。

## C3. 顺带更新的静态检查结果（t8 已补 Kotlin NativeLoader）

新增阶段后重跑 `--check-only`，阶段 4 由原来的 WARN 变为 OK：

```
===== 4. 契约硬约束自检（§4.2）=====
  OK   库名 = webrtcdemo_native (SHARED)
  OK   未链接 libwebrtc.a / libjingle_peerconnection_so（符合 §4.2）
  OK   Kotlin NativeLoader: System.loadLibrary("webrtcdemo_native")     ← 之前是 WARN
===== 5. 抽出 libjingle so =====
  FAIL 既无 third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so 也无 libwebrtc-arm64.aar
        （t5 产物仍未落地，符合预期）
===== --check-only：不执行构建 ==    EXIT=1（未执行任何 gradle）
```

补充：t8 正在推进（`app/src/main/kotlin` 已从 8 个 .kt 增至 **10 个**）。

## C4. 目录约定的统一确认（与 android-dev 对齐）

- `GRADLE_USER_HOME=<WS>/.gradle-home`、`TMPDIR=<WS>/tmp`，`GOCACHE`/`GOTMPDIR` 由 `env-go.sh` 提供 —— **不新造变量**；
- android-dev 误建的 `<WS>/.tmp` 已由其删除，统一使用 `<WS>/tmp`（本报告 A2 与 env 脚本即该约定）；
- 每次 `--check-only` 产生的临时文件/日志分别落在 `<WS>/tmp` 与 `reports/logs/`，均已被 gitignore 覆盖；校验后生成的 `local.properties` 已删除（避免容器侧误用宿主机路径）。

## C5. 首轮真实构建的执行顺序（t10 时）

`--check-only` 通过（t5 产物齐全）→ 脚本自动进入 6.5 Kotlin 编译 → 通过后 7 `assembleDebug` → 8 Go 二进制 → 9 APK 解包核验。
若 6.5 失败：脚本停在 6.5，`reports/logs/kotlin-compile-<ts>.log` 保留**完整原始错误**，报告按"骨架/t8 代码未通过 Kotlin 编译"如实记录（禁止宣称构建成功）。

---

# 附录 D：Go 二进制构建对齐与 t9 产物可复现验证（2026-09-13 15:53 CST，据 go-dev 来函）

## D1. t10 的 Go 构建命令已对齐 go-dev 的口径

go-dev 给出的命令（并与 t9 产出一致）：

```bash
. /opt/dsh-workspaces/env-go.sh && cd /opt/dsh-workspaces/code/webrtc-demo/signaling \
  && go build -trimpath -ldflags "-s -w" -o signaling .
```

- 脚本阶段 0 已 `. <WS>/env.sh`，而 `env.sh` **无条件 source `env-go.sh`**，因此 `GOCACHE/GOTMPDIR/GOENV/GOPATH` 全部指向 `<WS>/go` 内——**满足 go-dev 强调的"必须先 source，否则 `mkdir /data/dsh/home/.cache/go-build: permission denied`"**。
- 已核实 `env-go.sh` **不会覆盖** 我在 `env.sh` 中导出的 `TMPDIR`/`GRADLE_USER_HOME`（它只设 `GOTMPDIR`），实测宿主机 source 顺序后：`gradle_home=/opt/dsh-workspaces/.gradle-home`、`tmpdir=/opt/dsh-workspaces/tmp`、`GOCACHE=/opt/dsh-workspaces/go/cache`、`GOTMPDIR=/opt/dsh-workspaces/go/tmp` —— 全部落在工作区内 ✅。
- 脚本阶段 8 产物仍写 `signaling/dist/signaling-linux-amd64`（契约 §8 的部署名为 `signaling`），构建后自动 `sha256sum` + `file`，并与 t9 既有的 `signaling/signaling` **`cmp` 逐字节比对**，不一致则 WARN 并提示记录两者哈希。

## D2. 独立复现 t9 二进制（证明可复现、非伪造）

在**宿主机**用同一命令重建到临时路径（未覆盖交付物）：

```
$ . /opt/dsh-workspaces/env.sh && cd /opt/dsh-workspaces/code/webrtc-demo/signaling
$ time go build -trimpath -ldflags "-s -w" -o /opt/dsh-workspaces/tmp/t10-go-rebuild-signaling .
real 0m1.291s
$ sha256sum /opt/dsh-workspaces/tmp/t10-go-rebuild-signaling
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068   ← 与 go-dev 交付值【完全一致】
$ file …  →  ELF 64-bit LSB executable, x86-64, statically linked, stripped
$ du / 交付物：signaling/signaling = 5,496,984 B；dist/signaling-linux-amd64 = 5,496,984 B
```

结论：t9 的 `signaling/signaling`（5,496,984 B / sha256 `c298235a…c068`）**在宿主机可 1.3 秒复现且哈希完全一致** → 属可复现构建，非伪造产物。临时文件已删除。

## D3. `.gitignore` 与 Go 源码入库状态（回应 go-dev 第 2 点）

- go-dev 确认 C23/R7 已解决 ✅。补充实测：`git add --dry-run signaling/` 输出 **23 个源码文件**（**20 个 `.go`** + `go.mod`/`go.sum`/`README.md`，含 go-dev 新增的 `logging/logkeys_test.go`），**不含** `signaling/signaling`、`dist/`、`logs/`（0 个二进制/日志混入）。该计数经双方独立复测一致（go-dev 16:00 复测同为 23；其先前通报的"24"系把 `logkeys_test.go` 重复计数，已在 `reports/09-go-signaling.md` §8 更正为以本报告实跑为准）：

```
add 'signaling/README.md'   add 'signaling/config/config.go'   add 'signaling/go.mod'   add 'signaling/go.sum'
add 'signaling/logging/formatter.go' … add 'signaling/main.go' … add 'signaling/protocol/…'
add 'signaling/room/…'      add 'signaling/server/…'           add 'signaling/util/…'
$ git check-ignore -v signaling/main.go   → （无输出，exit=1：未忽略 ✅）
$ git check-ignore -v signaling/signaling signaling/dist/signaling-linux-amd64 signaling/logs/t9-live-evidence.log
  .gitignore:48:/signaling/signaling	signaling/signaling
  .gitignore:50:/signaling/dist/	signaling/dist/signaling-linux-amd64
  .gitignore:51:/signaling/logs/	signaling/logs/t9-live-evidence.log
```

- go-dev 指出的"`git ls-files signaling` 仍为空"属实：**尚未 add**。按 captain 决策 5（t10 前由我统一提交），将在 t10 阶段用显式路径提交 `signaling/**` 的 23 个源码文件，届时先 `git status --short` + `git diff --cached --stat` 复核。（文件数会随 go-dev 继续补测试而增长，以提交时的 dry-run 实测为准。）

## D4. 竞态检测的归属裁决（captain 2026-09-13 15:57 → **判给 verifier / t11，不在 t10 执行**）

命令：`. /opt/dsh-workspaces/env-go.sh && cd .../signaling && CGO_ENABLED=1 go test -race ./... -count=1`（容器无 gcc，须在宿主机跑）。

**captain 裁决（最终）**：判给 **verifier（t11）**执行，原始输出写入 `reports/99-final-report.md`。

- **理由**：竞态检测属**独立验证**范畴；由实现者（go-dev）或构建设施（t10）自验会削弱独立性。verifier 已有宿主机执行权限（其已在此宿主机完成 V60/V61 的 coturn 与 STUN 实测）。契约 **V39** 已将其写为「质量项（可选，须在宿主机执行）… 由 t10/t11 在宿主机补跑」。
- **据此执行**：t10 **不跑** race，**不加** `--with-race` 开关（避免 t10 范围蔓延——t10 是构建任务）。
- 本命令与理由**保留在本报告**（不删除），供 verifier 直接取用。
- 我此前的处置（未执行、不抢跑他人任务）与 captain 裁决一致。

## D5. 磁盘水位（截至 15:54；告警线口径已按 captain 15:57 更正）

```
/dev/vda3  69G  29G  38G  43% /
du -sh: webrtc-build 15G | android-sdk 2.6G | go 561M | code 205M | .gradle-home 8K
```

- **告警线口径（captain 更正）**：以 **`已用 ≥ 50G`** 为红线（而非"88% 使用率"）。当前已用 **29 G**，距红线 **21 G**。
- t5 的 `gclient` 拉取仍在进行（`webrtc-build` 已 15 G，预计编译阶段 10–25 h），剩余 38 G；Gradle 缓存目前几乎为空（8 K，尚未跑过构建），首轮构建需预留 ~1–2 G。
- 待命要求：t5 在编期间**不提前跑 gradle**，t10 保持待命。

---

# 附录 E：属主回归发现与修复 / t5 产物独立核验（2026-09-13 16:10–16:16 CST）

> 起因：webrtc-builder 来函同步 t5 现状（含"我在共享区以 root 建的东西都紧跟 chown"）。按惯例外来声明一律**实测复核**，而不是直接采信——复核中发现了一处**真实的属主回归**。

## E1. 属主回归（已被我修复）

复核命令与结果（宿主机）：

```
$ find /opt/dsh-workspaces/code/webrtc-demo/third_party ! -uid 1000 | wc -l
8234                     ← 全部集中在 third_party/libwebrtc-src（源码 submodule 的工作树 + 部分 .git）
$ find .../third_party/libwebrtc ! -uid 1000 | wc -l   → 0   （t5 产物属主正常）
$ find .../third_party/libvpx    ! -uid 1000 | wc -l   → 0
$ find /opt/dsh-workspaces/code/webrtc-demo ! -uid 1000 -printf "%u:%g %p\n"（修复前，共 5 项）
root:root …/app/src/main/jniLibs                 ← 我的 build_app.sh --check-only 以 root 执行时建的
root:root …/app/src/main/jniLibs/arm64-v8a
root:root …/reports/logs/build_app-20260913-154634.log   ← 同上（我的脚本产物）
root:root …/.git/modules/third_party/libwebrtc-src/config
root:root …/.git/modules/third_party/libwebrtc-src/HEAD  ← 其他成员 root 侧 git 操作留下
```

修复（宿主机 root）：

```bash
chown -R 1000:1000 …/third_party/libwebrtc-src
chown -R 1000:1000 …/app/src/main/jniLibs …/reports/logs …/.git/modules/third_party/libwebrtc-src
```

修复后：`find /opt/dsh-workspaces/code/webrtc-demo ! -uid 1000 | wc -l` → **0** ✅

> 说明：8234 个 root 属主文件属于"其他成员在宿主机以 root 操作 submodule"的副作用，并非 webrtc-builder 声明中的 `webrtc-build/`（那部分它确实处理过）。**t3 验收项"仓库内文件属主为 node/1000"因此一度回归，现已恢复**。我把这次回归写在此处，供 verifier 在 V 表中复核时以"修复后 0 项"为结论、并知悉回归曾发生。

## E2. 防复发：build_app.sh 增加阶段 10「属主归一」

我自己的脚本（以 root 在宿主机跑）此前会留下 root 属主的 `jniLibs/`、`reports/logs/*.log`、`local.properties`、`app/build/`。已在脚本末尾新增**阶段 10**：

```bash
if [ "$(id -u)" = "0" ]; then
  chown -R 1000:1000 "$JNILIBS" "$LOG_DIR"          # 本脚本自有产出
  chown 1000:1000 "$PROJ/local.properties" 2>/dev/null
  chown -R 1000:1000 "$APP/build" 2>/dev/null
  # 判据：受版控树（排除 gitignore）
  while IFS= read -r f; do
    [ -e "$PROJ/$f" ] && [ "$(stat -c %u "$PROJ/$f")" != "1000" ] && echo "非1000: $f"
  done < <(cd "$PROJ" && git ls-files --cached --others --exclude-standard)
  # third_party 产物：仅记录
  find "$TP/libwebrtc" "$TP/libvpx" ! -uid 1000 | wc -l
fi
```
（**注意：初版曾以"项目内非 1000 项 = 0"为判据，已被 E5 的新口径取代**——见下。）

→ 这样 t10 真实构建跑完后，**受版控树**属主自动回到 1000:1000，且把"非 1000 项数"与 `third_party` 记录项作为脚本自检输出留档。

## E3. t5 产物独立核验（不采信声明，实测）

| 声明（webrtc-builder） | 我的独立实测 | 结论 |
|---|---|---|
| `third_party/libvpx/lib/libvpx.a` ≈1.9 MB，已核 AArch64 | `ls -l` → **1,929,142 B**；用 NDK 的 `llvm-readelf -h` 抽首个成员 `aarch64_cpudetect.c.o`：`Class: ELF64`、`Type: REL`、**`Machine: AArch64`** | ✅ 一致 |
| `include/vpx/*.h` 存在 | `vpx_codec.h`、`vp8cx.h`、`vp9*.h` 等齐全 | ✅ |
| t5 产物属主已 chown | 实测 0 项非 1000 | ✅ |
| 当前进度 238/4262 步 | 16:10 复测 `.ninja_log` **277 行**（在推进） | ✅ 进行中 |
| 当前 libwebrtc 交付物 | `third_party/libwebrtc/{include,java,lib}` 已建目录；`java/jni/` 存在，但 **`.aar` / `libwebrtc-java.jar` 尚未出现**（t5 未到 SOLINK 阶段，符合进度） | ⏳ 待 t5 完成 |

## E4. t1 §R1 的因果归因已按 webrtc-builder 复测更新

webrtc-builder 指出"0.32 MB/s 并非 `--filter=blob:none` 特有"（它用纯 `--depth 1` 也测到过 460 KB/30 s，重测 37 MB/<60 s，归因于吞吐波动 + 其并发测试占带宽）。已在 `reports/01-host-recon.md` 的修订段加入 **R1 补充**：保留我的原始测量事实，**因果归因不定论**；操作结论不变（不做网络优化、shallow 只为省磁盘、时间预估以 t5 实时进度为准）。

## E5. 阶段 10 的**执行时点与判据口径**（captain 2026-09-13 16:20 确认后已改）

captain 指出原设计有时序缺陷，我据此重写了阶段 10：

**问题**：t5 正在宿主机**以 root 编译**，会持续往 `third_party/libwebrtc/**`、`third_party/libvpx/**` 写 root 属主的新文件。若把"项目内非 1000 项 = 0"作为判据，则**每次 t5 重编都会让这条变成假失败**。

**新口径（已写入脚本注释与实现）**：

1. **执行时点**：固定为脚本**最后一环**（stage 9 之后）——即所有构建产物落盘之后、git 提交之前。中途不执行。
2. **判据 = 受版本控制的树**：以 `git ls-files --cached --others --exclude-standard` 为全集（受版控文件 + 将被提交的未跟踪文件，**排除 gitignore**），要求其中非 1000 属主项 = 0；不为 0 时脚本**自动 chown 归零**并复测。
3. **`third_party` 下的 gitignored 产物只记录、不作判据**：脚本单独打印
   `[记录项·非判据] third_party 下（含 gitignored 产物与 submodule 工作树）非 1000 属主文件数`，
   并注明"t5 以 root 编译期间会持续产生，属正常"。
4. **归零范围**：受版控树 + 本脚本自有产出（`jniLibs/`、`reports/logs/`、`local.properties`、`app/build/`）。

实测确认（宿主机，只读模拟）：

```
受版控树非1000项：0
third_party 记录项：0        （t5 当前写入尚在 webrtc-build/ 内，未落到 third_party 产物目录）
```

→ 交付给 verifier 的口径：**验收看"受版控树非 1000 项 = 0"**（这是 t3 验收项的正解），`third_party` 记录项仅供观察、不构成失败。

## E6. 待命：磁盘清理预案（captain 16:20 预决策）

captain 已预决策"让 webrtc-builder 直接砍掉可选的 `webrtc` `.a` 备料"（契约中它可选；硬交付只有 `libvpx.a` + `libwebrtc-java.jar` + `libjingle_peerconnection_so.so`）。我把清理预案固定如下，**收到 captain 指令才执行**：

| 优先级 | 对象 | 说明 |
|---|---|---|
| 1 | `webrtc-build/src/out/**` 中间产物 | 仅清 `.o`/`.d` 等中间物；**不删最终 `libjingle_peerconnection_so.so`/jar 所在产物** |
| 2 | 各子仓 `.git` 冗余 pack | `git gc --prune=now` / 删除已合并 pack（先在容器侧备查） |
| 3 | 其它非必需依赖 | 逐个与 captain 确认后再动 |

**硬约束**：① 执行前**记录释放量**（`df` before/after + `du` 明细）；② **绝不触碰** `third_party/libvpx/**`、`third_party/libwebrtc/java/**`（硬交付）；③ 不碰 `env.sh`、`android-sdk/`、`go/`、`.gradle-home/`、`tmp/`。

---

# 附录 F：阶段 8 的"提交后必然假失败"隐患与修复（go-dev 主动提醒，2026-09-13 16:25 CST）

## F1. 问题：Go 会把 VCS 状态编进二进制

go-dev 在飞行前检查中发现：**t10 提交 `signaling/` 之后，`cmp` 阶段 8 必然报差异，但源码一字未改**。我按惯例独立复现了它的实验（宿主机实测）：

```
=== A) 仓库内默认构建（-trimpath -ldflags "-s -w"）===
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068   ← 与 t9 交付物相同（当前 VCS 状态未变）
  build vcs=git
  build vcs.revision=1a9d3ff69667f4198cb7952d8ecd015fc9965052
  build vcs.time=2026-09-13T07:31:55Z
  build vcs.modified=true

=== B) 仓库内 -buildvcs=false → 1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa
=== C) 再跑一次 -buildvcs=false → 1b333208…43aa（与 B 逐字节相同 ⇒ 确定性成立）

=== D) 非 git 目录（同样 23 个受版控文件）默认构建 → 1b333208…43aa，且 go version -m **无任何 vcs 行**
完整 diff（非 git 构建 vs 交付物）只有两类差异：
  1) 首行二进制路径
  2) 4 行 vcs.* 字段
其余元数据逐行相同。
```

**结论（与 go-dev 一致）**：`c298235a…c068` **只在"同一 git 工作区 + 同一 VCS 状态"下可复现**；t10 提交后 HEAD 前进、`vcs.modified` 翻转 ⇒ 哈希必然变化。若不改规则，阶段 8 会在**源码未变**的情况下报 WARN，误导后人以为 t9 代码被改动。

## F2. 修复后的阶段 8 比较规则（已实现并双侧实测）

```text
if cmp 相同                       → OK「t9 产物可复现」，并打印 vcs.revision
else
  novcs := 归一去首行(二进制路径)与所有 vcs 行后，两侧 go version -m 逐行相同？
  det   := 在 signaling/ 内用 -buildvcs=false 重建两次，两次逐字节相同？
  if novcs=yes && det=yes         → OK「差异仅为 Go 的 VCS 戳，源码未变，避免假失败」
  else                            → WARN「差异无法用 VCS 戳解释，请人工核对源码」
```

**踩坑记录（我自己的 bug，已修）**：初版归一化写成 `grep -v 'build\tvcs\.'`，GNU grep 的 BRE **不把 `\t` 当制表符**，导致 vcs 行没被剔除、误判 `novcs=no`（即"无法归因"）。改为 `sed '1d' | grep -v -e 'vcs=' -e 'vcs\.'` 后正确。脚本内已留注释警示。

**双侧实测（宿主机）**：

```
① 同一 VCS 状态（cmp 相等路径）：            "cmp 相同 → 可复现 ✅"
② 不同 VCS 状态（非 git 目录构建 vs 交付物）：非vcs元数据相同=yes ; -buildvcs=false 确定性=yes
                                            → 归因=『仅 VCS 戳差异』，不作失败 ✅
```

## F3. 附带确认：go-dev 的"提交自洽性"结论

go-dev 另测"仅用 23 个受版控文件即可完整构建 + 四包 `go test` 全绿"，说明**提交不含未跟踪依赖**。我认可该结论（其方法为把受跟踪文件复制到非 git 目录构建，与我 F1-D 的复现方式一致，双方结果互相印证：两侧同为 `1b333208…43aa`）。

**不引入额外解释成本**：按 captain 与 go-dev 的共同判断，**不重编交付物**（`c298235a…c068` 已被 t12 部署并完成端到端联调）；本修复只作用于 t10 的自检脚本。

## F4. ⚠️ F2 规则的**假 OK 缺陷**（go-dev 对抗测试发现，我已独立复现并再次修订）

**问题**：F2 的判据 1（"非 vcs 元数据逐行相同 ⇒ 源码未变"）**根本不成立**——`go version -m` 的元数据里**不含被编译的代码**。

**我的独立复现**（宿主机，把 `signaling/` 复制到临时目录后改 `server/server.go:22` 的 `const Version = "0.1.0"` → `"0.1.1"`，两侧均 `-buildvcs=false`）：

```
基线         -buildvcs=false → 1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa
改 1 处源码  -buildvcs=false → 74a37d0e5040bb5d1c244b7e14f3414c4d2320521a35d7e3f4332b46b6365ff2  ← 哈希确实变了
两次重建（改后）→ 74a37d0e…5ff2 = 74a37d0e…5ff2（确定性成立）
元数据差异行数（去首行/vcs 行；交付物 vs 改动版）→ 0          ← 元数据完全看不出改动
```

⇒ 按 F2 规则，**一次真实的源码改动会被判成 OK**。**假 OK 比假 WARN 危险得多**（假的 WARN 会招来人工复核，假的 OK 会直接放行）。go-dev 的结论与我实测一致。

**最终规则（2026-09-13 16:55，已写入脚本；以 VCS-free 基线哈希为决定性判据）**：

```text
① cmp 相同                              → OK「t9 产物可复现」
② cmp 不同 → 在 signaling/ 内 -buildvcs=false 重建两次，取 sha256 h2/h3：
     h2 == h3 且 h2 == NOVCS_BASELINE   → OK  「哈希变化已确证仅由 Go VCS 戳引起」（避免假失败）
     h2 != h3                           → FAIL「构建不可确定性，环境不稳定」
     h2 != NOVCS_BASELINE               → FAIL「源码/工具链/环境确实变了，不得放行」（拦住假 OK）
③ 元数据比对降级为**辅助信号**（只说明 module/构建参数一致），**不得单独下"源码未变"结论**
```

其中 `NOVCS_BASELINE` 默认写死为 go-dev §4.4 记录、并经我 F1-D 独立复现的值：
`1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa`（可用同名环境变量覆盖，便于 go-dev 未来重交付时更新）。

**三场景实测（宿主机）**：

```
场景 A｜源码未变（模拟 t10 提交后）：VCS-free 重建 == 基线 → OK「差异仅 VCS 戳」✅
场景 B｜源码真改（Version 0.1.0→0.1.1）：VCS-free 重建 = 74a37d0e… ≠ 基线 → FAIL（假 OK 被拦住）✅
场景 C｜辅助元数据在源码改动后差异行数 = 0 → 证明它只能作辅助，不能作判据 ✅
```

**方法论备注**：这条修正是"我的判据被对抗测试推翻后立刻改掉"的过程记录——保留 F2（旧规则）与 F4（修正）两段，读者可看到判据是如何被证伪与替换的，而不是被悄悄抹掉。

---

# 附录 G：t10 开工前的产物路径对齐与阶段 2 加固（2026-09-13 16:55 CST）

## G1. 与契约 §4.3 / t5 收尾链的路径对齐（只读核对）

**契约 §4.3 冻结的产物路径**（`doc/14-interface-contract.md`）：

| 产物 | 路径 |
|---|---|
| Java SDK AAR | `third_party/libwebrtc/java/libwebrtc-arm64.aar` |
| Java 类 jar | `third_party/libwebrtc/java/libwebrtc-java.jar`（= AAR 内 `classes.jar`） |
| JNI 共享库 | `third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so` |
| 静态库（不链接） | `third_party/libwebrtc/lib/*.a` |
| libvpx | `third_party/libvpx/{lib/libvpx.a, include/vpx/*.h}` |

**t5 收尾链实际落点**（只读阅读 `scripts/t5-tail.sh` + `webrtc-build/t5-build.sh` 的 `phase_extract()`，行 187–199）——**与契约路径逐条一致**：
`aar → $TP/libwebrtc/java/libwebrtc-arm64.aar`、`classes.jar → libwebrtc-java.jar`、`libjingle_peerconnection_so.so → java/jni/arm64-v8a/`、`libvpx.a + include/vpx → third_party/libvpx/`。
→ 结论：我的阶段 2 期望路径**无需改动**。

## G2. 阶段 2 两处加固（已实现并单测）

1. **jar 自愈**：若 `libwebrtc-java.jar` 缺失但 AAR 存在，**先**从 AAR 抽 `classes.jar` 补齐（契约定义 jar == AAR 内 classes.jar），再进入判定——避免"明明能自愈却先记 FAIL"。注意顺序：自愈放在 `fail()` 判定**之前**（否则 FAILED 粘滞会让 `--check-only` 误退出 1）。
2. **jar 内容核验**：确认 jar 内 `org/webrtc/` 类条目 > 0（D1 要求官方 Java SDK），否则 FAIL"Java SDK 不完整"。

**单测（宿主机，构造含 `org/webrtc/*.class` + `AndroidManifest.xml` 的假 AAR）**：

```
=== 模拟：jar 缺失 + AAR 存在 → 自愈逻辑 ===
→ 已自愈补齐 jar ✅
jar 存在: yes
jar 内 org/webrtc 条目 = 2
→ org/webrtc 校验通过 ✅（阶段 2 新判据生效）
```

## G3. ninja 进度口径澄清（captain 16:50 查清，记录备查）

我此前上报"`.ninja_log` 5209 行 > 4262 步估计，疑重规划/重启"——captain 查清**不是异常**：5528 行是**整轮累计已完成边数**，而 4262 是**早期某时点 `ninja -n` 的估算**（偏小约 1/3），两者不是同一量。决定性数据是当前 `ninja -n` 只剩 **[126/126] 步后置校验**，`error:`/`FAILED:` 匹配 = 0 → 构建已在收尾。**教训**：进度判断用"当前 dry-run 剩余步数"，不要用累计 `.ninja_log` 行数对比早期估算。

## G4. t10 开工判据（captain 16:50 口径，我照此执行）

**判据 = 产物真正落地**（`libjingle_peerconnection_so.so` 与 `libwebrtc-java.jar`/AAR 出现），**不是剩余步数**。产物一出现即按序执行：
`--check-only` → 阶段 6.5（`:app:compileDebugKotlin -PwebrtcDemo.skipNative=true`）→ 阶段 7 `assembleDebug` → 8 Go → 9 APK 核验 → 10 属主归一。
**在产物落地前不跑 gradle**（当前 `.aar`/jar 均未出现）。
