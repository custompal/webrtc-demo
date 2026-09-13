#!/usr/bin/env bash
# 宿主机验证脚本（t7 native-dev）：编译并运行两个宿主侧测试。
# 用法：./run_host_tests.sh [仓库根]（默认 = 本文件上溯 5 级目录）
# 注意：本脚本与 tests/host 不参与 CMake/AGP 构建。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${1:-$(cd "$HERE/../../../../.." && pwd)}"
CPP="$REPO/app/src/main/cpp"
OUT="${TMPDIR:-/tmp}/webrtcdemo-native-tests"

echo "== repo = $REPO"
mkdir -p "$OUT"

echo "== 1/2 分层码率 + 日志设施"
g++ -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter \
  -I"$CPP" -I"$HERE/stub" \
  "$HERE/layer_bitrate_and_native_log_test.cpp" \
  "$CPP/encoder/layer_bitrate_allocator.cpp" \
  "$CPP/log/native_log.cpp" \
  -o "$OUT/layer_bitrate_and_native_log_test"
"$OUT/layer_bitrate_and_native_log_test"

echo "== 2/2 NAT 四步判定（假 STUN 服务器）"
g++ -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter \
  -I"$CPP" -I"$HERE/stub" \
  "$HERE/nat_detector_host_test.cpp" \
  "$CPP/nat/nat_detector.cpp" \
  "$CPP/nat/stun_client.cpp" \
  "$CPP/log/native_log.cpp" \
  -o "$OUT/nat_detector_host_test" -lpthread
"$OUT/nat_detector_host_test"

echo "== 全部通过"
