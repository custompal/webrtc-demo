# reports/24-nat-address-endianness.md —— 原生 STUN 映射地址字节序反转（t54）

- 任务：t54（native-dev）；范围：`app/src/main/cpp/nat/**` + 本报告。
- 依据：契约 `doc/14-interface-contract.md`（sha256 `b3b67438…`，本轮未改）、
  RFC 5389 §15.2（XOR-MAPPED-ADDRESS）、RFC 5780（NAT 行为发现）。
- 真机日志（只读，同一台 Xiaomi 24117RK2CC / Android 16）：
  宿主 `/opt/dsh-workspaces/tmp/dl-f/x/`（蜂窝）与 `/opt/dsh-workspaces/tmp/dl-g/x/`
  （WiFi）↔ 容器 `/data/dsh/home/workspace/tmp/dl-f/x/`、`/data/dsh/home/workspace/tmp/dl-g/x/`。
- 容器内**未跑 gradle**；用 NDK clang++ 做了真实目标语法检查与离线宿主自测（§7）。

---

## 1. 结论摘要

| 项 | 结论 |
| --- | --- |
| 根因 | **整数字节序约定混用**：`IpToString(uint32_t)` 要求「网络序整数」，而 STUN 属性路径传入的是 `ReadUint32()` 得到的「大端数值」；小端设备上 `inet_ntop` 读到的 4 个内存字节被逐字节反转（修复前 `stun_client.cpp:91-99` 定义、`:129`/`:148` 两处误用） |
| 为什么只有 IP 反转、端口却正确 | 端口走的是 `ReadUint16()` 数值路径（`ntohs` 语义正确）；地址是唯一被当成内存字节序列解读的字段（§3.3） |
| 为什么本地地址正确、映射地址错误 | `getsockname()`/`recvfrom()` 返回的 `s_addr` **本来就是网络序**，那三处调用恰好符合 `IpToString()` 的约定；只有 STUN 属性两处不符 ⇒ 真机出现「本地 IP 对、公网 IP 反转」的不对称（§3.3，这是本根因的决定性佐证） |
| 影响面 | ① 诊断显示不可信；② **`nat_detector.cpp:193` 的 Open（无 NAT）判定也被波及**（`mapped1 == client.local_address()` 永远为假）；③ Symmetric/端口受限等**同类比较不受影响**（两侧被同样反转）；真机 `nat_done type=Symmetric` 语义不变（§6） |
| 修复 | 新增 `nat/stun_address.h`（字节序安全的解码：`FormatIpv4` / `DecodePlainAddress` / `DecodeXorAddress`，XOR 严格按 RFC 5389 §15.2 **逐字节**异或），`stun_client.cpp` 的全部地址格式化改走**字节**接口（§4） |
| 同类缺陷复核 | `XOR-MAPPED-ADDRESS`/`MAPPED-ADDRESS`/`RESPONSE-ORIGIN`/`OTHER-ADDRESS` 是**同一处**代码，已一并修复；`XOR-PEER-ADDRESS`/`XOR-RELAYED-ADDRESS` **本工程未解析**（全树 grep 无该属性常量）⇒ 无同类缺陷（§5） |
| 验证 | 离线宿主自测 **22 项断言 failures=0 / exit=0**（含真机两条对照值与「旧路径反向结果」回归断言）；aarch64 `-Wall -Wextra -fsyntax-only` exit=0（§7） |
| 事件/字段名 | `stun_response` / `nat_done` / `stun_local_address` 的事件名与字段名**一字未改**（doc/14 §9 冻结节流） |

---

## 2. 现象与真机对照（修复前）

探针打印的映射地址与真值逐字节反转；真值取自 libwebrtc 自己的 srflx 候选
（`ice_candidate_local … addr=…`，同机同网）：

| # | 网络 | 原生探针（native.log，修复前） | libwebrtc 真值（app.log） | 关系 |
| --- | --- | --- | --- | --- |
| 1 | 蜂窝 | `stun_response … mapped=195.211.55.111:37341`（dl-f `native.log:659`）；`nat_done type=Symmetric detail=mapped=195.211.55.111:37341`（`:664`） | `ice_candidate_local … srflx … addr=111.55.211.195`（dl-f `app.log:2986`、`:3376`） | 4 字节反转 |
| 2 | WiFi | `stun_response … mapped=148.119.230.120:7431`（dl-g `native.log:344`）；`nat_done … mapped=148.119.230.120:7431`（`:349`） | `ice_candidate_local … srflx … addr=120.230.119.148`（dl-g `app.log:2099`、`:2701`） | 4 字节反转 |
| 3 | WiFi（另一 socket） | `mapped=148.119.230.120:7586`（dl-g `native.log:356`） | `addr=120.230.119.148`（dl-g `app.log:2701`） | 同上 |

**端口与本地地址都是正确的**（同一条日志里）：
- `stun_local_address ip=10.46.17.3 port=37341`（dl-f `native.log:657`）——
  本地地址来自 `getsockname()`，打印正确；
- `mapped=…:37341`（dl-f `native.log:659`）端口与本地端口一致（同一 socket），打印正确；
- `stun_response source=47.238.144.66:3478`（来自 `recvfrom()`）也正确。

⇒ 缺陷**只出现在 STUN 属性里的 IP 字段**，这本身就指向「该字段走了与众不同的转换路径」。

---

## 3. 根因（代码级）

### 3.1 缺陷代码（修复前 HEAD 版本）

```cpp
// app/src/main/cpp/nat/stun_client.cpp:91-99（修复前）
std::string IpToString(uint32_t network_order_ip) {
  char buffer[INET_ADDRSTRLEN] = {0};
  struct in_addr address;
  address.s_addr = network_order_ip;              // ← 要求「网络序整数」
  if (inet_ntop(AF_INET, &address, buffer, sizeof(buffer)) == nullptr) { … }
  return std::string(buffer);
}
```
两处**约定不符**的调用：
```cpp
// :129（MAPPED-ADDRESS / RESPONSE-ORIGIN / OTHER-ADDRESS 明文属性）
out->ip = IpToString(ReadUint32(value + 4));
// :148（XOR-MAPPED-ADDRESS）
const uint32_t xor_ip = ReadUint32(value + 4) ^ kMagicCookie;
out->ip = IpToString(xor_ip);
```
而 `ReadUint32()`（`stun_client.cpp:73-77`，修复前）返回的是**大端数值**：
```cpp
uint32_t ReadUint32(const uint8_t* data) {
  return (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3];
}
```
即「人类书写顺序的整数」（对 `111.55.211.195` 就是 `0x6F37D3C3`），
**不是** `s_addr` 所需的网络序整数。

### 3.2 为什么 4 个字节被反转（可复现的计算）

`inet_ntop(AF_INET, &addr, …)` 读的是 `addr.s_addr` 在**内存里的 4 个字节**，
且这 4 个字节必须按「a.b.c.d」排列（网络序）。小端设备上把 `0x6F37D3C3`
赋给 `s_addr` 后，内存里是 `C3 D3 37 6F` ⇒ 打印 `195.211.55.111` —— 与真机
完全一致（另一条：`120.230.119.148 = 0x78E67794` → 内存 `94 77 E6 78` →
`148.119.230.120` ✓）。

同一缺陷对**端口**无影响：`out->port = ReadUint16(value + 2)` 得到的是数值，
端口按数值参与比较与打印（`std::to_string`），不经过「内存字节序列」解读。

### 3.3 为什么本地/对端地址打印是对的（决定性佐证）

`IpToString()` 的另外三处调用传的是 `sockaddr_in::sin_addr.s_addr`
（`getsockname()`/`recvfrom()` 返回值，**本来就是网络序整数**，正是该函数想要的）：
`stun_client.cpp:209`（`getsockname` 主 socket）、`:243`（路由探测 socket）、
`:385`（`recvfrom` 的 source）。于是同一份日志里：
**本地地址/对端地址正确、STUN 属性地址反转** —— 这个不对称只有在
「STUN 属性路径的字节序约定与其余路径不同」时才成立，是本根因的决定性证据。

> 附带确认：XOR 的**数学**没错（`ReadUint32(bytes) ^ kMagicCookie` 与
> 「逐字节异或」等价），错的只是「异或后的 32 位值被当成网络序内存」这一步。

---

## 4. 修复

### 4.1 新增 `app/src/main/cpp/nat/stun_address.h`（字节序安全的纯解析）

设计原则：**只接受「4 个网络序字节」，不接受整数** —— 去掉整数就没有主机序/
网络序的混淆面；XOR 严格按 RFC 5389 §15.2 逐字节异或（不含 32 位整数路径）：

| 接口 | 作用 |
| --- | --- |
| `kStunMagicCookieBytes[4] = {0x21,0x12,0xA4,0x42}`（`:41`） | cookie 的**网络序字节**（与大端 0x2112A442 相等，自测里断言） |
| `ReadUint16Be/ReadUint32Be`（`:48`/`:55`） | 大端读（语义与旧 `ReadUint16/ReadUint32` 相同，但命名显式） |
| `FormatIpv4(bytes, out, capacity)`（`:65`） | 4 个网络序字节 → `a.b.c.d`（手写十进制，不依赖 `inet_ntop`/`snprintf`，故可离线自测） |
| `DecodePlainAddress(value, length, bytes, port)`（`:101`） | MAPPED-ADDRESS/RESPONSE-ORIGIN/OTHER-ADDRESS（明文，仅 IPv4） |
| `DecodeXorAddress(value, length, bytes, port)`（`:126`） | XOR-MAPPED-ADDRESS/XOR-PEER-ADDRESS/XOR-RELAYED-ADDRESS：端口 ^ `0x2112`，地址**逐字节** ^ cookie（`:135-143`） |

### 4.2 `stun_client.cpp` 的改动

| 位置（修复后） | 改动 |
| --- | --- |
| `:38` | `#include "nat/stun_address.h"` |
| `:91-99`（旧 `IpToString`） | **删除**（保留会再次被误用） |
| `:100` `IpFromNetworkOrderBytes()` | 新增：字节 → 字符串（走 `FormatIpv4`） |
| `:109` `IpFromSockaddrInAddr()` | 新增：`sockaddr_in::sin_addr` → 字符串（取地址后按字节传入） |
| `:141` `ParsePlainAddress` | `DecodePlainAddress()` + `IpFromNetworkOrderBytes()` |
| `:161` `ParseXorMappedAddress` | `DecodeXorAddress()` + `IpFromNetworkOrderBytes()` |
| `:226`/`:260`/`:402` | 三处 `sockaddr` 调用点改为 `IpFromSockaddrInAddr(...)`（行为与修复前一致，只是名字显式） |

未改动：`nat/nat_detector.{h,cpp}`、`nat/stun_client.h`、JNI 层、事件名与字段名、
NAT 类型判定逻辑（`Symmetric`/`PortRestricted`/`Open` 的判定顺序与条件一字未动）。

---

## 5. 地址类属性复核（避免只修一处）

| 属性 | 本工程是否解析 | 结论 |
| --- | --- | --- |
| `MAPPED-ADDRESS` (0x0001) | 是（`stun_client.cpp` `kAttrMappedAddress` → `ParsePlainAddress`） | **同源缺陷**，已随 `:141` 修复 |
| `RESPONSE-ORIGIN` (0x802B) | 是（→ `ParsePlainAddress`） | 同源，已修复 |
| `OTHER-ADDRESS` (0x802C) | 是（→ `ParsePlainAddress`） | 同源，已修复（RFC 5780 的 change-request 判定依赖它） |
| `XOR-MAPPED-ADDRESS` (0x0020) | 是（→ `ParseXorMappedAddress`） | **同源缺陷**，已随 `:161` 修复 |
| `XOR-PEER-ADDRESS` (0x0012) | **否** | 全树 grep（`0x0012`/`XOR_PEER`/`XOR-PEER`）无解析代码 ⇒ 无同类缺陷；若将来加入，必须复用 `DecodeXorAddress()` |
| `XOR-RELAYED-ADDRESS` (0x0016) | **否** | 同上 |

## 6. 影响面

1. **诊断显示（已确认）**：`stun_response`/`nat_done` 的 `mapped=`/`origin=`/`other=`
   字段此前不可信，会把排障带偏（且与 libwebrtc 候选自相矛盾）。
2. **`Open`（无 NAT）判定（也被波及，需更正任务描述中的「仅显示」）**：
   `nat_detector.cpp:193` `if (mapped1 == client.local_address())` 用**字符串比较**；
   `local_address()` 打印正确而 `mapped1` 被反转 ⇒ 该分支**永远不可能成立**。
   修复后此分支恢复可用（真机两条日志里映射地址与本端地址本就不同，故本轮
   不会改变任何既有结论）。
3. **Symmetric / 端口受限判定（不受影响）**：`nat_detector.cpp:206/222/236` 比较的是
   **同一个解析器产出的多个映射地址**（test1 vs test2/test3/test4），被一致反转时
   相等/不等关系不变 ⇒ 类型判定与真机 `nat_done type=Symmetric` 语义保持不变。
4. **协议行为（不受影响）**：地址字段只用于日志与 `mapped==local` 判定，不参与
   STUN 消息构造/校验（校验用 cookie 与 message type）。

## 7. 验证（容器内真实执行）

### 7.1 离线字节序自测（新增 `nat/stun_address_host_test.cpp`，freestanding）

```bash
NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
cd code/webrtc-demo
$NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
  -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
  -DSTUN_ADDRESS_HOST_TEST -DSTUN_ADDRESS_FREESTANDING -I app/src/main/cpp \
  app/src/main/cpp/nat/stun_address_host_test.cpp \
  -o <工作区>/t54-work/stun_address_test -Wl,-e,_start -Wl,--build-id=none
<工作区>/t54-work/stun_address_test ; echo "exit=$?"
```
结果（容器内执行，日志 `webrtc-build/t54-work/stun_address_test.log`）：
```
  OK   magic cookie bytes == 0x2112A442 (big endian)
  OK   plain MAPPED-ADDRESS(IPv4) decoded        OK plain port = 37341
  OK   plain ip (NOT reversed) = 111.55.211.195
  OK   XOR-MAPPED-ADDRESS(IPv4) decoded          OK xor port = 37341
  OK   xor ip (NOT reversed) = 111.55.211.195
  OK   old buggy path reproduces reversal = 195.211.55.111
  OK   XOR-MAPPED-ADDRESS #2 decoded             OK xor port #2 = 7431
  OK   xor ip #2 (NOT reversed) = 120.230.119.148
  OK   plain/xor rejected when length < 8 ；family != IPv4 ；capacity < 16 ；nullptr
  OK   format 0.0.0.0 / 255.255.255.255 / 10.0.0.1
== result: failures=0 ==   exit=0（22 项断言）
```
断言锚定真机数据：XOR 值 `{0x00,0x01,0xB0,0xCF,0x4E,0x25,0x77,0x81}` 即
「端口 37341 ^ 0x2112」+「111.55.211.195 逐字节 ^ cookie」，解出 `111.55.211.195:37341`
（真机旧输出为反转的 `195.211.55.111:37341`）；第二条用
`{0x00,0x01,0x3C,0x15,0x59,0xF4,0xD3,0xD6}` → `120.230.119.148:7431`。
「old buggy path reproduces reversal」一项把修复前的错误结果写成断言 ⇒
该自测**能抓到回归**（不是空跑）。

### 7.2 真实目标工具链语法检查（aarch64，`-Wall -Wextra`）
```bash
$NDK/clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti \
  -Wall -Wextra -I app/src/main/cpp -fsyntax-only app/src/main/cpp/nat/stun_client.cpp
```
结果：**exit=0，无任何诊断**。

### 7.3 契约 verify 三条
`grep`（stun_client.cpp 地址相关）与全树 `XOR-MAPPED|XOR_PEER|XOR_RELAYED|MAPPED_ADDRESS`
复核均 **exit=0**；报告 mode 644、`git status --porcelain` 结果见 §9。

## 8. 未验证项（真机复测才可判定）

| # | 未验证项 | 下一轮真机只看这几行即可判定 |
| --- | --- | --- |
| U1 | 修复后 `mapped=` 是否等于真实公网 IP | `native.log` 的 `stun_response … mapped=<ip>:<port>`；与同一导出包 `app.log` 的 `ice_candidate_local … srflx … addr=<ip>` **逐字符相等**（IP 部分）即通过 |
| U2 | `nat_done` 的 `type=` 是否仍为 `Symmetric`（判定语义未变） | `native.log` 的 `nat_done type=… detail=mapped=…;testI=…;testII=…`；蜂窝/WiFi 两网应各自与修复前**同类型** |
| U3 | `Open`（无 NAT）分支是否恢复可用 | 仅当本端就在公网时才应出现 `type=Open`；判据 `mapped1 == local_address()`（`nat_detector.cpp:193`）现在可能为真（此前不可能） |
| U4 | `origin=`/`other=`（RESPONSE-ORIGIN/OTHER-ADDRESS）是否与预期一致 | `stun_response … origin=66.144.238.47:3478 other=-`（coturn 变更地址）；`other` 在 change-request 场景下应为服务器另一地址而非反转值 |

**假设/边界（如实声明）**：
- A-1：真值取自 libwebrtc 的 srflx 候选（同一 socket 的 STUN 映射），与原生探针
  **不是同一个 socket**（端口不同属正常）；IP 部分的一致性足以证明字节序结论。
- A-2：修复只改「地址字节序」路径，未在真机运行过；§7 的离线自测与语法检查是
  本轮可提供的全部证据（无 JDK/SDK，无法在容器内跑 gradle/Instrumentation）。

## 9. 交付清单

| 文件 | 变更 | sha256 |
| --- | --- | --- |
| `app/src/main/cpp/nat/stun_address.h` | 新增（字节序安全解码） | `c7407ec271bcae72047a0572fe3e9ff244785d869d4f17ec9f2d5971d9b32088` |
| `app/src/main/cpp/nat/stun_client.cpp` | 修复两处属性解析 + 三处 sockaddr 调用点改名/改路径；删除旧 `IpToString` | `7a86745793f5902e171cccabf91eaeb2a6d23e70798d83571818d4153b7dfd50`（修复前 `c02a3ca4fe1e04c7cad43c9b1932bca69f9fc24ac28c16e1873158543fa54c93`） |
| `app/src/main/cpp/nat/stun_address_host_test.cpp` | 新增离线自测（22 断言，`failures=0`） | `1aa9fa686523128350ab7516dc2dcc7d1a11b1db63bc231a01698c24964696ee` |
| `reports/24-nat-address-endianness.md` | 本报告（mode 644） | 见提交记录 |

未改：`nat/nat_detector.{h,cpp}`、`nat/stun_client.h`、`jni/**`、`encoder/**`、
`kotlin/**`、`doc/**`、`third_party/**`、`scripts/**`、`CMakeLists.txt`
（`CMakeLists.txt` 逐文件列举源，新自测文件不参与 App 构建）。
离线产物写在仓外 `webrtc-build/t54-work/`。
