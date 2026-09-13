# C++ Native 层宿主机验证资产（tests/host）

> 归属：t7（native-dev）。**不参与 CMake / AGP 构建**，不影响 `libwebrtcdemo_native.so`
> 与 APK（`CMakeLists.txt` 未引用本目录）。
> 用途：在没有 Android 真机、且 t5 产物未就绪时，也能在宿主机上验证两个纯逻辑模块：
> 1. `encoder/layer_bitrate_allocator.*` —— 分层码率换算（40%/70%/100% 累计、单调性、钳制）；
> 2. `log/native_log.*` —— 日志行格式、文件+logcat 双写、2 MiB × 3 滚动、CSV 表头/行；
> 3. `nat/*` —— RFC5780 四步判定（用本机假 STUN 服务器驱动 NatDetector）。

## 为什么需要 stub

| stub | 替代 | 原因 |
|---|---|---|
| `stub/android/log.h` | NDK 的 `<android/log.h>` | `__android_log_print` 在宿主机不存在 |
| `stub/jni.h` | NDK/JDK 的 `<jni.h>` | `nat_detector.cpp` 经 `jni/callback_bridge.h` 引用 JNI 类型；宿主机无该头 |

`stub/` 只能通过 `-I tests/host/stub` 显式加入，**绝不可**加入 CMake 的
`target_include_directories`，否则会遮蔽 NDK 真头文件。

## 运行

```bash
# 在宿主机（或任何有 g++ 的 Linux）执行；REPO = 仓库根
CPP=$REPO/app/src/main/cpp
cd $CPP/tests/host
g++ -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter \
    -I$CPP -Istub layer_bitrate_and_native_log_test.cpp \
    $CPP/encoder/layer_bitrate_allocator.cpp $CPP/log/native_log.cpp -o /tmp/t7_log_test
/tmp/t7_log_test          # 期望：RESULT: failures=0

g++ -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter \
    -I$CPP -Istub nat_detector_host_test.cpp \
    $CPP/nat/nat_detector.cpp $CPP/nat/stun_client.cpp $CPP/log/native_log.cpp \
    -o /tmp/t7_nat_test -lpthread
/tmp/t7_nat_test          # 期望：RESULT: failures=0
```

或直接执行 `./run_host_tests.sh "$REPO"`。

## 覆盖的场景

`layer_bitrate_and_native_log_test.cpp`
- 750 kbps → `ts_target 300/525/750`、`rc 750`、`layer_target` 同步、`ss0 == rc`、decimator `{2,1,1}`；
- 极低码率（10 kbps）→ 每层 ≥ 4 kbps 且单调；
- 真实时序分层矩阵（300k/225k/225k）→ 与策略路径等价；
- T=1（对照实验）→ `ts0 == rc`；
- 全 0 矩阵 → 回退 `total_bps`；
- 日志：行格式正则、级别切换、非法级别不输出、2 MiB × 3 滚动（实测生成 `native.log/.1.log/.2.log`）、`encoder_bitrate.csv` 表头与行。

`nat_detector_host_test.cpp`
- 假 STUN 服务器（127.0.0.1 随机端口）解析 CHANGE-REQUEST 并按场景决定是否回包；
- 判定：`Open` / `FullCone` / `RestrictedCone` / `PortRestrictedCone` / `Symmetric`（映射随目标变化 + Test IV 映射变化）/ `Unknown`（STUN 不可达）；
- 校验 CHANGE-REQUEST 的 `change IP`（0x02）与 `change port`（0x04）确实发出。

## 已知限制

- 不覆盖真实 NAT 环境、真实 coturn、JNI 注册与真机 logcat；
- `nat_detector_host_test.cpp` 用本文件内的空实现替代
  `NotifyNatTypeDetected` / `NotifyLogEvent` / `SetCurrentThreadName`
  （真实实现见 `jni/callback_bridge.cpp`、`util/jni_util.cpp`）；
- stub `jni.h` 仅声明 NAT 路径需要的类型，不是 JNI 规范实现。
