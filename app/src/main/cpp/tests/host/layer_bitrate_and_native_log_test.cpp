// 主机侧验证程序（非交付物）：验证分层码率换算与 native 日志设施。
// 编译：clang++ -std=c++17 -I<cpp> -I<stub> test_main.cpp
//         <cpp>/encoder/layer_bitrate_allocator.cpp <cpp>/log/native_log.cpp
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "encoder/layer_bitrate_allocator.h"
#include "log/native_log.h"

using namespace webrtcdemo;

static int g_failures = 0;

static void Check(bool condition, const char* what) {
  if (!condition) {
    ++g_failures;
    printf("FAIL: %s\n", what);
  } else {
    printf("ok  : %s\n", what);
  }
}

static void CheckEq(int value, int expected, const char* what) {
  if (value != expected) {
    ++g_failures;
    printf("FAIL: %s got=%d expected=%d\n", what, value, expected);
  } else {
    printf("ok  : %s = %d\n", what, value);
  }
}

// 复刻契约 §5.6 的 A1 实际输入：SDK 给 3x3 矩阵，只有 [0][0] 非 0。
static LayerBitrate MakeSdkMatrix(int32_t total_bps) {
  LayerBitrate rates;
  rates.num_spatial = 3;
  rates.num_temporal = 3;
  rates.layer_bps[0][0] = total_bps;
  rates.total_bps = total_bps;
  rates.framerate_fps = 30;
  return rates;
}

int main() {
  // ---- 用例 1：750 kbps → 累计 40%/70%/100% ----
  VpxLayerRates r1 =
      LayerBitrateAllocator::Compute(MakeSdkMatrix(750000), 1, 3);
  CheckEq(r1.configured_spatial, 1, "case1 s");
  CheckEq(r1.configured_temporal, 3, "case1 t");
  CheckEq(r1.rc_target_bitrate_kbps, 750, "case1 rc_target");
  CheckEq(r1.ts_target_bitrate_kbps[0], 300, "case1 ts0 (40%)");
  CheckEq(r1.ts_target_bitrate_kbps[1], 525, "case1 ts1 (70%)");
  CheckEq(r1.ts_target_bitrate_kbps[2], 750, "case1 ts2 (100%)");
  Check(r1.ts_target_bitrate_kbps[0] <= r1.ts_target_bitrate_kbps[1] &&
            r1.ts_target_bitrate_kbps[1] <= r1.ts_target_bitrate_kbps[2],
        "case1 monotonic");
  CheckEq(r1.layer_target_bitrate_kbps[0], 300, "case1 layer0");
  CheckEq(r1.layer_target_bitrate_kbps[1], 525, "case1 layer1");
  CheckEq(r1.layer_target_bitrate_kbps[2], 750, "case1 layer2");
  CheckEq(r1.ss_target_bitrate_kbps[0], 750, "case1 ss0 == rc");
  CheckEq((int)r1.ts_rate_decimator[0], 2, "case1 decimator0");
  CheckEq((int)r1.ts_rate_decimator[1], 1, "case1 decimator1");
  CheckEq((int)r1.ts_rate_decimator[2], 1, "case1 decimator2");

  // ---- 用例 2：极低码率 10 kbps → 每层不小于 4 kbps 且单调 ----
  VpxLayerRates r2 = LayerBitrateAllocator::Compute(MakeSdkMatrix(10000), 1, 3);
  CheckEq(r2.rc_target_bitrate_kbps, 10, "case2 rc_target");
  Check(r2.ts_target_bitrate_kbps[0] >= LayerBitrateAllocator::kMinLayerKbps,
        "case2 min clamp");
  Check(r2.ts_target_bitrate_kbps[0] <= r2.ts_target_bitrate_kbps[2],
        "case2 monotonic");

  // ---- 用例 3：矩阵带真实时序分层数据 ----
  LayerBitrate temporal;
  temporal.num_spatial = 1;
  temporal.num_temporal = 3;
  temporal.layer_bps[0][0] = 300000;
  temporal.layer_bps[0][1] = 225000;
  temporal.layer_bps[0][2] = 225000;
  temporal.total_bps = 750000;
  temporal.framerate_fps = 30;
  VpxLayerRates r3 = LayerBitrateAllocator::Compute(temporal, 1, 3);
  CheckEq(r3.ts_target_bitrate_kbps[0], 300, "case3 ts0");
  CheckEq(r3.ts_target_bitrate_kbps[1], 525, "case3 ts1");
  CheckEq(r3.ts_target_bitrate_kbps[2], 750, "case3 ts2");

  // ---- 用例 4：T=1（对照实验）----
  VpxLayerRates r4 =
      LayerBitrateAllocator::Compute(MakeSdkMatrix(500000), 1, 1);
  CheckEq(r4.configured_temporal, 1, "case4 t");
  CheckEq(r4.ts_target_bitrate_kbps[0], 500, "case4 ts0 == rc");
  CheckEq((int)r4.ts_rate_decimator[0], 1, "case4 decimator");

  // ---- 用例 5：全 0 矩阵回退 total_bps ----
  LayerBitrate zero;
  zero.num_spatial = 3;
  zero.num_temporal = 3;
  zero.total_bps = 640000;
  VpxLayerRates r5 = LayerBitrateAllocator::Compute(zero, 1, 3);
  CheckEq(r5.rc_target_bitrate_kbps, 640, "case5 fallback total");

  // ---- 用例 6：日志设施（格式 / 双写 / 滚动 / CSV / 级别）----
  const std::string log_dir = "/tmp/t7test/logs";
  std::string mkdir_cmd = "rm -rf " + log_dir + " && mkdir -p " + log_dir;
  if (system(mkdir_cmd.c_str()) != 0) {
    printf("FAIL: cannot prepare log dir\n");
    return 1;
  }
  NativeLogger& logger = NativeLogger::Instance();
  Check(logger.Init(log_dir, "native", kLogDebug, 64 * 1024, 3),
        "logger init ok");
  logger.LogF(kLogInfo, "encoder", "encoder_init w=%d h=%d s=%d t=%d cpu=%d",
              640, 480, 1, 3, 8);
  logger.LogF(kLogDebug, "bitrate", "setrates total_bps=%d fps=%d", 750000, 30);
  NativeLogger::Instance().Log(kLogOff + 1, "encoder", "should_be_clamped");
  logger.SetLevel(kLogWarn);
  Check(!logger.ShouldLog(kLogInfo), "level switch filters INFO");
  Check(logger.ShouldLog(kLogWarn), "level switch allows WARN");
  logger.SetLevel(kLogDebug);
  logger.LogF(kLogInfo, "nat", "nat_done type=FullCone detail=%s",
              "mapped=1.2.3.4:54321;testI=resp");

  EncoderBitrateSample sample;
  sample.ts_ms = 1773000000000LL;
  sample.total_bps = 750000;
  sample.fps = 30;
  sample.num_spatial = 1;
  sample.num_temporal = 3;
  sample.layer_bps[0][0] = 750000;
  sample.ts_kbps[0] = 300;
  sample.ts_kbps[1] = 525;
  sample.ts_kbps[2] = 750;
  sample.rc_target_kbps = 750;
  sample.encoded_bytes = 12345;
  sample.key_frame = 1;
  sample.qp = 24;
  logger.WriteBitrateSample(sample);

  // 触发滚动：单文件上限 64 KiB，写 1500 行（每行 ~90 字节 ≈ 135 KB）→ 至少滚 2 次
  for (int i = 0; i < 1500; ++i) {
    logger.LogF(kLogInfo, "bitrate", "setrates total_bps=%d fps=30 seq=%d",
                750000 - i * 1000, i);
  }
  logger.Flush();
  logger.Shutdown();

  std::string check_cmd =
      "cd " + log_dir +
      " && wc -l native.log && head -1 native.log && "
      "head -1 encoder_bitrate.csv && sed -n '2p' encoder_bitrate.csv && ls";
  printf("---- 文件检查 ----\n");
  fflush(stdout);
  if (system(check_cmd.c_str()) != 0) {
    ++g_failures;
    printf("FAIL: file inspection command\n");
  }
  // 每条日志一次 write、行格式校验（滚动后首行可能已进 .1/.2，故用 native.log native.1.log native.2.log）
  std::string grep_cmd =
      "cd " + log_dir +
      " && echo -n 'encoder_lines=' && grep -h 'INFO    native encoder   \\[' "
      "native.log native.1.log native.2.log | wc -l"
      " && echo -n 'bitrate_lines=' && grep -h 'DEBUG   native bitrate   \\[' "
      "native.log native.1.log native.2.log | wc -l"
      " && echo -n 'nat_lines=' && grep -h 'INFO    native nat       \\[' "
      "native.log native.1.log native.2.log | wc -l"
      " && echo -n 'bad_lines=' && grep -hvE "
      "'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z "
      "(VERBOSE|DEBUG|INFO|WARN|ERROR) +native +[a-z_]+ +\\[[0-9]+/[0-9]+\\] ' "
      "native.log native.1.log native.2.log | wc -l"
      " && echo -n 'csv_header=' && grep -c "
      "'ts_ms,total_bps,fps,s,t,layer_0_0' encoder_bitrate.csv";
  printf("---- 格式校验 ----\n");
  fflush(stdout);
  if (system(grep_cmd.c_str()) != 0) {
    ++g_failures;
    printf("FAIL: format verification command\n");
  }

  printf("\nRESULT: failures=%d\n", g_failures);
  return g_failures == 0 ? 0 : 1;
}
