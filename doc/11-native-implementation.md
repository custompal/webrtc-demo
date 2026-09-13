# 11 — C++ Native 层实现指南

> 本文档定义 `app/src/main/cpp/` 下的全部 C++ 代码。
> Agent 按此文档生成 native 层代码，包括 JNI 桥接、PeerConnection 管理、VP9 编码器、NAT 探测。

## 1. 目录结构

```
app/src/main/cpp/
├── CMakeLists.txt                           # 顶层 CMake
├── jni/
│   ├── jni_bridge.h                         # JNI 注册与生命周期
│   ├── jni_bridge.cpp
│   ├── peer_connection_jni.h                # PeerConnection JNI 接口
│   ├── peer_connection_jni.cpp
│   └── native_callbacks.h                   # C++ → Java 回调声明
├── webrtc/
│   ├── peer_connection_manager.h            # PeerConnection 生命周期管理
│   ├── peer_connection_manager.cpp
│   ├── video_sink_adapter.h                 # 视频渲染适配器
│   ├── stats_collector.h                    # getStats 数据收集
│   └── stats_collector.cpp
├── encoder/
│   ├── vp9_encoder_factory.h                # VideoEncoderFactory 实现
│   ├── vp9_encoder_factory.cpp
│   ├── vp9_encoder.h                        # 自研 VP9 编码器（核心）
│   └── vp9_encoder.cpp
├── nat/
│   ├── nat_detector.h                       # RFC5780 NAT 探测
│   └── nat_detector.cpp
└── util/
    ├── jni_util.h                           # JNI 辅助函数
    └── jni_util.cpp
```

## 2. CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(webrtcdemo)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# === NDK 配置 ===
set(ANDROID_STL c++_shared)
set(ANDROID_PLATFORM android-26)

# === 第三方库路径 ===
set(LIBWEBRTC_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libwebrtc)
set(LIBVPX_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libvpx)

# === libwebrtc ===
add_library(webrtc STATIC IMPORTED)
set_target_properties(webrtc PROPERTIES
    IMPORTED_LOCATION ${LIBWEBRTC_PATH}/lib/libwebrtc.a
)

# libwebrtc 头文件
file(GLOB_RECURSE WEBRTC_HEADERS ${LIBWEBRTC_PATH}/include/*.h)
target_include_directories(webrtc INTERFACE ${LIBWEBRTC_PATH}/include)

# === libvpx ===
add_library(vpx STATIC IMPORTED)
set_target_properties(vpx PROPERTIES
    IMPORTED_LOCATION ${LIBVPX_PATH}/lib/libvpx.a
)
target_include_directories(vpx INTERFACE ${LIBVPX_PATH}/include)

# === 主 native 库 ===
add_library(webrtcdemo SHARED
    jni/jni_bridge.cpp
    jni/peer_connection_jni.cpp
    webrtc/peer_connection_manager.cpp
    webrtc/stats_collector.cpp
    encoder/vp9_encoder_factory.cpp
    encoder/vp9_encoder.cpp
    nat/nat_detector.cpp
    util/jni_util.cpp
)

target_include_directories(webrtcdemo PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBWEBRTC_PATH}/include
    ${LIBVPX_PATH}/include
)

# === 链接库 ===
target_link_libraries(webrtcdemo
    webrtc                          # libwebrtc.a
    vpx                             # libvpx.a
    log                             # Android log
    android                         # Android NDK
    OpenSLES                        # 音频
    EGL                             # GL 渲染
    GLESv2                          # GL 渲染
)
```

## 3. JNI 桥接

### 3.1 包名与类名约定

```
Java 包名: com.example.webrtcdemo.webrtc
Java 类名: WebRtcNative
JNI 注册: JNI_OnLoad 中 RegisterNatives
```

### 3.2 jni_bridge.cpp — JNI_OnLoad

```cpp
#include <jni.h>
#include <android/log.h>

#define TAG "WebRtcDemo"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 声明所有 JNI 方法（定义在各自的 .cpp 中）
extern bool registerPeerConnectionMethods(JNIEnv* env);
extern bool registerNativeCallbacks(JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    // 注册 PeerConnection JNI 方法
    if (!registerPeerConnectionMethods(env)) {
        LOGE("registerPeerConnectionMethods failed");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: 所有方法注册成功");
    return JNI_VERSION_1_6;
}
```

### 3.3 JNI 方法表（RegisterNatives）

```cpp
// peer_connection_jni.cpp

#include <jni.h>

// === Java 侧声明的 native 方法 ===
// public class WebRtcNative {
//     static { System.loadLibrary("webrtcdemo"); }
//     static native void init();
//     static native void createPeerConnection(boolean audio, boolean video);
//     static native void setVideoSurface(Object surface);
//     static native void setRemoteVideoSink(Object renderer);
//     static native void setLocalDescription(String sdp);
//     static native void setRemoteDescription(String sdp, String type);
//     static native void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex);
//     static native void toggleMute(boolean audio, boolean video);
//     static native void hangup();
//     static native void startNatTest(String stunHost, int stunPort);
//     static native String getStats();
// }

static const char* kClassName = "com/example/webrtcdemo/webrtc/WebRtcNative";

// 方法实现（简化签名）
static void nativeInit(JNIEnv* env, jclass clazz) { /* 初始化全局状态 */ }
static void nativeCreatePeerConnection(JNIEnv* env, jclass clazz,
    jboolean audio, jboolean video) { /* 创建 PeerConnection */ }
static void nativeSetVideoSurface(JNIEnv* env, jclass clazz, jobject surface) { /* 设置本地视频 Surface */ }
static void nativeSetRemoteVideoSink(JNIEnv* env, jclass clazz, jobject renderer) { /* 设置远端渲染器 */ }
static void nativeSetLocalDescription(JNIEnv* env, jclass clazz, jstring sdp) { /* 设置本地 SDP */ }
static void nativeSetRemoteDescription(JNIEnv* env, jclass clazz, jstring sdp, jstring type) { /* 设置远端 SDP */ }
static void nativeAddIceCandidate(JNIEnv* env, jclass clazz,
    jstring candidate, jstring sdpMid, jint sdpMLineIndex) { /* 添加 ICE candidate */ }
static void nativeToggleMute(JNIEnv* env, jclass clazz, jboolean audio, jboolean video) { /* 静音/摄像头切换 */ }
static void nativeHangup(JNIEnv* env, jclass clazz) { /* 挂断 */ }
static void nativeStartNatTest(JNIEnv* env, jclass clazz, jstring stunHost, jint stunPort) { /* NAT 探测 */ }
static jstring nativeGetStats(JNIEnv* env, jclass clazz) { /* 返回 JSON 统计 */ }

// 方法表
static const JNINativeMethod kMethods[] = {
    {"init",                  "()V",                                      (void*)nativeInit},
    {"createPeerConnection", "(ZZ)V",                                     (void*)nativeCreatePeerConnection},
    {"setVideoSurface",      "(Ljava/lang/Object;)V",                    (void*)nativeSetVideoSurface},
    {"setRemoteVideoSink",   "(Ljava/lang/Object;)V",                    (void*)nativeSetRemoteVideoSink},
    {"setLocalDescription",  "(Ljava/lang/String;)V",                     (void*)nativeSetLocalDescription},
    {"setRemoteDescription", "(Ljava/lang/String;Ljava/lang/String;)V",  (void*)nativeSetRemoteDescription},
    {"addIceCandidate",      "(Ljava/lang/String;Ljava/lang/String;I)V", (void*)nativeAddIceCandidate},
    {"toggleMute",           "(ZZ)V",                                     (void*)nativeToggleMute},
    {"hangup",               "()V",                                       (void*)nativeHangup},
    {"startNatTest",         "(Ljava/lang/String;I)V",                    (void*)nativeStartNatTest},
    {"getStats",             "()Ljava/lang/String;",                      (void*)nativeGetStats},
};

bool registerPeerConnectionMethods(JNIEnv* env) {
    jclass clazz = env->FindClass(kClassName);
    if (!clazz) {
        __android_log_print(ANDROID_LOG_ERROR, "WebRtcDemo",
            "FindClass %s failed", kClassName);
        return false;
    }
    int count = sizeof(kMethods) / sizeof(kMethods[0]);
    if (env->RegisterNatives(clazz, kMethods, count) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "WebRtcDemo",
            "RegisterNatives failed");
        return false;
    }
    return true;
}
```

### 3.4 C++ → Java 回调

```cpp
// native_callbacks.h
#pragma once
#include <jni.h>

// 回调到 Java 的方法签名：
// public class WebRtcNative {
//     static void onIceCandidate(String candidate, String sdpMid, int mLineIdx);
//     static void onIceConnectionChange(String state);
//     static void onCandidatePairChanged(String local, String remote,
//         String state, boolean nominated);
//     static void onStatsReport(String json);
//     static void onBitrateChanged(int bps);
//     static void onNatTypeDetected(String type);
//     static void onRemoteVideoReady();
// }

class NativeCallbacks {
public:
    static void init(JavaVM* vm, jclass clazz);
    static void onIceCandidate(const std::string& candidate,
        const std::string& sdpMid, int mLineIdx);
    static void onIceConnectionChange(const std::string& state);
    static void onStatsReport(const std::string& json);
    static void onBitrateChanged(int bps);
    static void onNatTypeDetected(const std::string& type);
    static void onRemoteVideoReady();

private:
    static JavaVM* vm_;
    static jclass class_;
    static jmethodID on_ice_candidate_;
    static jmethodID on_ice_connection_change_;
    static jmethodID on_stats_report_;
    static jmethodID on_bitrate_changed_;
    static jmethodID on_nat_type_detected_;
    static jmethodID on_remote_video_ready_;
};
```

## 4. PeerConnectionManager

### 4.1 头文件

```cpp
// webrtc/peer_connection_manager.h
#pragma once

#include <memory>
#include <string>
#include <functional>
#include <vector>

#include "api/peer_connection_interface.h"
#include "api/scoped_refptr.h"

class VP9EncoderFactory;
class StatsCollector;
class VideoSinkAdapter;

class PeerConnectionManager :
    public webrtc::PeerConnectionObserver,
    public webrtc::CreateSessionDescriptionObserver {
public:
    PeerConnectionManager();
    ~PeerConnectionManager();

    // 初始化
    bool init(const std::string& stunUrl,
              const std::string& turnUrl,
              const std::string& turnUsername,
              const std::string& turnCredential);

    // 创建 PeerConnection
    bool createPeerConnection(bool enableAudio, bool enableVideo);

    // SDP 操作
    void createOffer();
    void createAnswer();
    void setLocalDescription(const std::string& sdp);
    void setRemoteDescription(const std::string& sdp, const std::string& type);

    // ICE
    void addIceCandidate(const std::string& candidate,
                         const std::string& sdpMid,
                         int sdpMLineIndex);

    // 媒体
    void setVideoSurface(void* surface);      // ANativeWindow*
    void setRemoteVideoSink(void* sink);
    void toggleMute(bool muteAudio, bool muteVideo);

    // 控制
    void hangup();
    std::string getStatsJson();

    // 回调
    std::function<void(const std::string& sdp)> onLocalSdpReady;
    std::function<void(const std::string& candidate,
        const std::string& sdpMid, int mLineIdx)> onIceCandidateReady;
    std::function<void(const std::string& state)> onIceConnectionChange;
    std::function<void(int bps)> onBitrateChanged;
    std::function<void()> onRemoteVideoReady;

private:
    // PeerConnectionObserver
    void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState state) override;
    void OnAddTrack(rtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver,
                    const std::vector<rtc::scoped_refptr<webrtc::MediaStreamInterface>>& streams) override;
    void OnRemoveTrack(rtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver) override;
    void OnDataChannel(rtc::scoped_refptr<webrtc::DataChannelInterface> channel) override {}
    void OnRenegotiationNeeded() override {}
    void OnIceConnectionChange(webrtc::PeerConnectionInterface::IceConnectionState state) override;
    void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState state) override;
    void OnIceCandidate(const webrtc::IceCandidateInterface* candidate) override;
    void OnIceConnectionReceivingChange(bool receiving) override {}

    // CreateSessionDescriptionObserver
    void OnSuccess(webrtc::SessionDescriptionInterface* desc) override;
    void OnFailure(webrtc::RTCError error) override;

private:
    rtc::scoped_refptr<webrtc::PeerConnectionFactoryInterface> factory_;
    rtc::scoped_refptr<webrtc::PeerConnectionInterface> peer_connection_;
    std::unique_ptr<VP9EncoderFactory> vp9_encoder_factory_;
    std::unique_ptr<StatsCollector> stats_collector_;
    std::unique_ptr<VideoSinkAdapter> remote_video_sink_;

    // 本地媒体轨道
    rtc::scoped_refptr<webrtc::VideoTrackSource> video_source_;
    rtc::scoped_refptr<webrtc::VideoTrackInterface> local_video_track_;
    rtc::scoped_refptr<webrtc::AudioTrackInterface> local_audio_track_;
};
```

### 4.2 初始化流程

```cpp
bool PeerConnectionManager::init(
    const std::string& stunUrl,
    const std::string& turnUrl,
    const std::string& turnUsername,
    const std::string& turnCredential) {

    // 1. 创建 PeerConnectionFactory
    webrtc::PeerConnectionFactoryDependencies dependencies;
    dependencies.task_queue_factory = webrtc::CreateDefaultTaskQueueFactory();
    dependencies.signaling_thread = rtc::Thread::CreateWithSocketServer();
    dependencies.worker_thread = rtc::Thread::Create();
    dependencies.worker_thread->Start();
    dependencies.signaling_thread->Start();

    // 2. 注入自研 VP9 编码器工厂
    vp9_encoder_factory_ = std::make_unique<VP9EncoderFactory>();
    dependencies.video_encoder_factory =
        std::unique_ptr<webrtc::VideoEncoderFactory>(vp9_encoder_factory_.get());

    factory_ = webrtc::CreateModifiablePeerConnectionFactory(dependencies);

    // 3. 配置 ICE servers
    webrtc::PeerConnectionInterface::RTCConfiguration config;
    config.sdp_semantics = webrtc::SdpSemantics::kUnifiedPlan;

    webrtc::PeerConnectionInterface::IceServer stun_server;
    stun_server.uri = stunUrl;
    config.servers.push_back(stun_server);

    webrtc::PeerConnectionInterface::IceServer turn_server;
    turn_server.uri = turnUrl;
    turn_server.username = turnUsername;
    turn_server.password = turnCredential;
    config.servers.push_back(turn_server);

    // 4. 创建 PeerConnection
    auto error = webrtc::RTCError();
    peer_connection_ = factory_->CreatePeerConnection(
        config, nullptr, nullptr, this, &error);
    if (!peer_connection_) { return false; }

    // 5. 创建音频轨道
    cricket::AudioOptions audio_options;
    local_audio_track_ = factory_->CreateAudioTrack(
        "audio_track",
        factory_->CreateAudioSource(audio_options).get());

    // 6. 创建视频轨道（AndroidVideoTrackSource）
    video_source_ = rtc::make_ref_counted<webrtc::AndroidVideoTrackSource>(
        factory_->signaling_thread(), false);
    local_video_track_ = factory_->CreateVideoTrack(
        "video_track", video_source_.get());

    // 7. 添加到 PeerConnection
    if (audio_enabled) {
        peer_connection_->AddTrack(local_audio_track_, {"stream_id"});
    }
    if (video_enabled) {
        peer_connection_->AddTrack(local_video_track_, {"stream_id"});
    }

    // 8. 初始化 stats 收集器
    stats_collector_ = std::make_unique<StatsCollector>(peer_connection_);
    stats_collector_->onBitrateChanged = [this](int bps) {
        if (this->onBitrateChanged) this->onBitrateChanged(bps);
    };

    return true;
}
```

### 4.3 Offer/Answer 流程

```cpp
void PeerConnectionManager::createOffer() {
    webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
    options.offer_to_receive_audio = true;
    options.offer_to_receive_video = true;
    auto observer = webrtc::CreatePeerConnectionObserver(this);
    peer_connection_->CreateOffer(observer.get(), options);
}

void PeerConnectionManager::OnSuccess(webrtc::SessionDescriptionInterface* desc) {
    std::string sdp;
    desc->ToString(&sdp);
    peer_connection_->SetLocalDescription(
        webrtc::SetSessionDescriptionObserver(), desc);
    if (onLocalSdpReady) onLocalSdpReady(sdp);
}

void PeerConnectionManager::setRemoteDescription(
    const std::string& sdp, const std::string& type) {
    webrtc::SdpParseError error;
    auto desc = webrtc::CreateSessionDescription(
        webrtc::SdpTypeFromString(type), sdp, &error);
    peer_connection_->SetRemoteDescription(
        webrtc::SetSessionDescriptionObserver(), desc.release());
}
```

### 4.4 ICE 回调

```cpp
void PeerConnectionManager::OnIceCandidate(
    const webrtc::IceCandidateInterface* candidate) {
    std::string sdp;
    candidate->ToString(&sdp);
    if (onIceCandidateReady) {
        onIceCandidateReady(sdp,
            candidate->sdp_mid(),
            candidate->sdp_mline_index());
    }
}

void PeerConnectionManager::OnIceConnectionChange(
    webrtc::PeerConnectionInterface::IceConnectionState state) {
    std::string state_str = webrtc::IceConnectionStateToString(state);
    if (onIceConnectionChange) onIceConnectionChange(state_str);
}
```

## 5. 自研 VP9 编码器

### 5.1 VP9EncoderFactory

```cpp
// encoder/vp9_encoder_factory.h
#pragma once
#include "api/video_codecs/video_encoder_factory.h"

class VP9EncoderFactory : public webrtc::VideoEncoderFactory {
public:
    std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
    std::unique_ptr<webrtc::VideoEncoder> CreateVideoEncoder(
        const webrtc::SdpVideoFormat& format) override;
    std::unique_ptr<webrtc::VideoEncoderFactory::CodecInfo>
    QueryVideoEncoder(const webrtc::SdpVideoFormat& format) override;
};
```

```cpp
// encoder/vp9_encoder_factory.cpp
#include "vp9_encoder_factory.h"
#include "vp9_encoder.h"

std::vector<webrtc::SdpVideoFormat> VP9EncoderFactory::GetSupportedFormats() const {
    std::vector<webrtc::SdpVideoFormat> formats;
    // VP9 基础格式
    formats.push_back(webrtc::SdpVideoFormat("VP9"));
    return formats;
}

std::unique_ptr<webrtc::VideoEncoder> VP9EncoderFactory::CreateVideoEncoder(
    const webrtc::SdpVideoFormat& format) {
    return std::make_unique<VP9Encoder>();
}

auto VP9EncoderFactory::QueryVideoEncoder(
    const webrtc::SdpVideoFormat& format)
    -> std::unique_ptr<CodecInfo> {
    auto info = std::make_unique<CodecInfo>();
    info->is_hardware_accelerated = false;  // 纯软件编码
    info->implementation_name = "SelfVP9";
    return info;
}
```

### 5.2 VP9Encoder 核心实现

```cpp
// encoder/vp9_encoder.h
#pragma once

#include <memory>
#include "api/video_codecs/video_encoder.h"
#include "vpx/vpx_encoder.h"
#include "vpx/vp8cx.h"

class VP9Encoder : public webrtc::VideoEncoder {
public:
    VP9Encoder();
    ~VP9Encoder() override;

    // === VideoEncoder 接口 ===
    int32_t InitEncode(const webrtc::VideoCodec* codec_settings,
                       const Settings& settings) override;
    int32_t Encode(const webrtc::VideoFrame& frame,
                   const std::vector<webrtc::VideoFrameType>* frame_types) override;
    int32_t RegisterEncodeCompleteCallback(
        webrtc::EncodedImageCallback* callback) override;
    void SetRates(const webrtc::RateControlParameters& parameters) override;
    void OnPacketLossRateUpdate(double packet_loss_rate) override;
    void OnRttUpdate(int64_t rtt_ms) override;
    void OnLossNotification(
        const webrtc::LossNotification& loss_notification) override {}
    int32_t Release() override;
    webrtc::VideoEncoder::EncoderInfo GetEncoderInfo() const override;

private:
    // === vpx 编码器状态 ===
    vpx_codec_ctx_t enc_;
    vpx_codec_enc_cfg_t cfg_;
    bool initialized_ = false;

    // === 编码参数 ===
    int width_ = 0;
    int height_ = 0;
    int target_bitrate_ = 0;          // rc_target_bitrate (kbps)
    int max_bitrate_ = 0;
    uint32_t flags_ = 0;

    // === SVC 分层码率（VP9 核心） ===
    int ss_target_bitrate_[3] = {0};  // 每空间层目标码率
    int layer_target_bitrate_[3] = {0}; // 每时序层目标码率

    // === 回调 ===
    webrtc::EncodedImageCallback* callback_ = nullptr;

    // === 帧计数 ===
    int64_t frame_count_ = 0;

    // 将 VideoBitrateAllocation 转为 vpx 码率配置
    void applyBitrateAllocation(
        const webrtc::VideoBitrateAllocation& allocation);
};
```

```cpp
// encoder/vp9_encoder.cpp — 关键方法

int32_t VP9Encoder::InitEncode(
    const webrtc::VideoCodec* codec_settings,
    const Settings& settings) {

    width_ = codec_settings->width;
    height_ = codec_settings->height;
    target_bitrate_ = codec_settings->startBitrate / 1000;  // kbps
    max_bitrate_ = codec_settings->maxBitrate / 1000;

    // === vpx 编码器配置 ===
    vpx_codec_enc_config_default(&vpx_codec_vp9_cx, &cfg_, 0);

    cfg_.rc_target_bitrate = target_bitrate_;
    cfg_.g_w = width_;
    cfg_.g_h = height_;
    cfg_.g_lag_in_frames = 0;          // 零延迟模式
    cfg_.g_threads = 1;                // 单线程（省 CPU）
    cfg_.kf_max_dist = 1999;          // 关键帧间隔
    cfg_.rc_min_quantizer = 4;
    cfg_.rc_max_quantizer = 56;
    cfg_.rc_undershoot_pct = 50;
    cfg_.rc_overshoot_pct = 50;
    cfg_.rc_dropframe_thresh = 0;

    // === VP9 SVC 配置（2 空间层 + 3 时序层） ===
    cfg_.ss_number_layers = 2;         // 2 个空间层
    cfg_.ts_number_layers = 3;         // 3 个时序层
    cfg_.ss_target_bitrate[0] = target_bitrate_ * 30 / 100;  // 基础层 30%
    cfg_.ss_target_bitrate[1] = target_bitrate_ * 70 / 100;  // 增强层 70%
    cfg_.ts_target_bitrate[0] = target_bitrate_ * 40 / 100;
    cfg_.ts_target_bitrate[1] = target_bitrate_ * 70 / 100;
    cfg_.ts_target_bitrate[2] = target_bitrate_;              // 全量层 = 总码率
    cfg_.ts_rate_layer_decimator[0] = 2;
    cfg_.ts_rate_layer_decimator[1] = 1;

    // 初始化 ss_target_bitrate_ 和 layer_target_bitrate_ 用于动态调整
    ss_target_bitrate_[0] = cfg_.ss_target_bitrate[0];
    ss_target_bitrate_[1] = cfg_.ss_target_bitrate[1];
    layer_target_bitrate_[0] = cfg_.ts_target_bitrate[0];
    layer_target_bitrate_[1] = cfg_.ts_target_bitrate[1];
    layer_target_bitrate_[2] = cfg_.ts_target_bitrate[2];

    // === 初始化编码器 ===
    vpx_codec_enc_init(&enc_, &vpx_codec_vp9_cx, &cfg_, 0);
    // 启用 SVC 编码
    vpx_codec_control(&enc_, VP9E_SET_SVC, 1);
    // 关键帧最小间隔
    vpx_codec_control(&enc_, VP9E_SET_SVC_LAYER_ID, &svc_layer_id_);

    initialized_ = true;
    return WEBRTC_VIDEO_CODEC_OK;
}

// === 动态码率设置核心方法 ===
void VP9Encoder::SetRates(const webrtc::RateControlParameters& parameters) {
    if (!initialized_) return;

    // parameters.bitrate 是 VideoBitrateAllocation
    // 包含每空间层/时序层的目标码率
    applyBitrateAllocation(parameters.bitrate);

    // 更新 vpx 配置
    vpx_codec_enc_config_set(&enc_, &cfg_);

    // 回调通知 UI
    if (callback_) {
        // 可选：通过回调报告码率变化
    }
}

void VP9Encoder::applyBitrateAllocation(
    const webrtc::VideoBitrateAllocation& allocation) {

    // 从 VideoBitrateAllocation 提取每层码率
    // allocation.GetSpatialLayer(spatial_idx).GetBitrateBps(temporal_idx)

    for (int si = 0; si < cfg_.ss_number_layers; si++) {
        uint32_t layer_bitrate_bps = 0;
        for (int ti = 0; ti < cfg_.ts_number_layers; ti++) {
            uint32_t bps = allocation.GetSpatialLayer(si).GetBitrateBps(ti);
            if (bps > 0) layer_bitrate_bps += bps;
        }
        // 转换为 kbps
        int kbps = layer_bitrate_bps / 1000;
        if (si < cfg_.ss_number_layers) {
            cfg_.ss_target_bitrate[si] = kbps;
        }
    }

    // 计算总码率
    int total_kbps = 0;
    for (int ti = 0; ti < cfg_.ts_number_layers; ti++) {
        uint32_t layer_bps = 0;
        for (int si = 0; si < cfg_.ss_number_layers; si++) {
            layer_bps += allocation.GetSpatialLayer(si).GetBitrateBps(ti);
        }
        int kbps = layer_bps / 1000;
        if (ti < cfg_.ts_number_layers) {
            cfg_.ts_target_bitrate[ti] = total_kbps + kbps;
            total_kbps += kbps;
        }
    }

    // 更新总目标码率
    cfg_.rc_target_bitrate = total_kbps;
    target_bitrate_ = total_kbps;
}

int32_t VP9Encoder::Encode(
    const webrtc::VideoFrame& frame,
    const std::vector<webrtc::VideoFrameType>* frame_types) {

    if (!initialized_) return WEBRTC_VIDEO_CODEC_UNINITIALIZED;

    // 将 VideoFrame 转为 vpx_image_t
    vpx_image_t img;
    vpx_img_wrap(&img, VPX_IMG_FMT_I420, width_, height_, 1,
                 frame.video_frame_buffer()->DataY());

    // 判断是否为关键帧
    bool is_keyframe = false;
    if (frame_types && !frame_types->empty()) {
        is_keyframe = (*frame_types)[0] == webrtc::VideoFrameType::kVideoFrameKey;
    }

    // 设置编码 flags
    uint32_t flags = 0;
    if (is_keyframe) flags |= VPX_EFLAG_FORCE_KF;

    // 编码
    vpx_codec_err_t res = vpx_codec_encode(&enc_, &img,
        frame.timestamp_us(), 1, flags, VPX_DL_REALTIME);

    if (res != VPX_CODEC_OK) return WEBRTC_VIDEO_CODEC_ERROR;

    // 取出编码后的帧
    const vpx_codec_cx_pkt_t* pkt;
    vpx_codec_iter_t iter = nullptr;
    while ((pkt = vpx_codec_get_cx_data(&enc_, &iter))) {
        if (pkt->kind == VPX_CODEC_CX_FRAME_PKT) {
            // 构造 EncodedImage 回调
            webrtc::EncodedImage image;
            image.SetEncodedData(
                webrtc::EncodedImageBuffer::Create(
                    static_cast<uint8_t*>(pkt->data.frame.buf),
                    pkt->data.frame.sz));
            image._encodedWidth = width_;
            image._encodedHeight = height_;
            image.SetTimestamp(frame.timestamp_us());

            // 设置 SVC 层信息
            if (pkt->data.frame.spatial_layer_id >= 0) {
                image.SetSpatialIndex(
                    pkt->data.frame.spatial_layer_id);
            }
            if (pkt->data.frame.temporal_layer_id >= 0) {
                image.SetTemporalIndex(
                    pkt->data.frame.temporal_layer_id);
            }

            if (callback_) callback_->OnEncodedImage(image, nullptr);
        }
    }

    frame_count_++;
    return WEBRTC_VIDEO_CODEC_OK;
}
```

> **对照阅读**：libwebrtc 官方 VP9 编码器在 `modules/video_coding/codecs/vp9/`，核心类 `LibvpxVp9Encoder`，其 `SetSvcRates()` 方法处理同样的 `VideoBitrateAllocation → ss_target_bitrate` 转换，可直接对照。

### 5.3 EncoderInfo

```cpp
webrtc::VideoEncoder::EncoderInfo VP9Encoder::GetEncoderInfo() const {
    EncoderInfo info;
    info.implementation_name = "SelfVP9";
    info.supports_native_handle = false;
    info.is_trusted_rate_controller = false;
    info.has_trusted_rate_controller = false;
    info.resolution_bitrate_limits = {
        {320 * 180, 0, 0, 500},      // 低分辨率上限 500kbps
        {640 * 360, 0, 0, 1000},     // 标清上限 1000kbps
        {1280 * 720, 0, 0, 2000},    // 高清上限 2000kbps
    };
    return info;
}
```

## 6. NAT 探测模块

### 6.1 头文件

```cpp
// nat/nat_detector.h
#pragma once
#include <string>
#include <functional>
#include <memory>

class NatDetector {
public:
    NatDetector();
    ~NatDetector();

    // 启动 NAT 类型探测（RFC5780）
    void detect(const std::string& stunHost, int stunPort);

    // 回调
    std::function<void(const std::string& natType)> onDetected;

private:
    enum class NatType {
        OPEN,              // 公网 IP，无 NAT
        FULL_CONE,         // Full Cone
        RESTRICTED_CONE,   // Restricted Cone
        PORT_RESTRICTED_CONE, // Port Restricted Cone
        SYMMETRIC,         // Symmetric
        UNKNOWN            // 探测失败
    };

    // RFC5780 测试步骤
    NatType performTests(const std::string& stunHost, int stunPort);

    // STUN 协议
    struct StunBindingResponse {
        uint32_t mappedIp;
        uint16_t mappedPort;
        bool changedIp;
        uint16_t changedPort;
        bool valid;
    };

    StunBindingResponse sendStunBinding(
        const std::string& stunHost, int stunPort,
        bool changeRequest = false,
        const std::string& altHost = "", int altPort = 0);
};
```

### 6.2 实现概述

```
RFC5780 NAT 探测流程：

Test 1: 发 STUN Binding Request 到 STUN:3478
  → 记录 mapped IP:PORT (响应中的 XOR-MAPPED-ADDRESS)
  → 如果 mapped IP == 本机 IP → Open (无 NAT)

Test 2: 发带 CHANGE-REQUEST "change IP and port" 的 STUN 请求
  → 如果收到响应 → Full Cone (从备用 IP:PORT 收到)
  → 如果无响应 → 继续

Test 3: 发带 CHANGE-REQUEST "change port only"
  → 如果收到响应 → Restricted Cone
  → 如果无响应 → 继续

Test 4: 比对 Test 1 的 mapped PORT
  → 如果 mapped PORT 不同 → Symmetric
  → 如果相同 → Port Restricted Cone
```

## 7. StatsCollector

```cpp
// webrtc/stats_collector.h
#pragma once
#include <string>
#include <functional>
#include "api/peer_connection_interface.h"

class StatsCollector : public webrtc::RTCStatsCollectorCallback {
public:
    explicit StatsCollector(webrtc::PeerConnectionInterface* pc);
    ~StatsCollector();

    // 触发 stats 收集
    void collectAsync();

    // 回调
    std::function<void(const std::string& json)> onStatsJson;
    std::function<void(int bps)> onBitrateChanged;

private:
    // RTCStatsCollectorCallback
    void OnStatsDelivered(
        const rtc::scoped_refptr<const webrtc::RTCStatsReport>& report) override;

    webrtc::PeerConnectionInterface* pc_;

    // 从 stats report 提取关键信息
    std::string extractJson(
        const webrtc::RTCStatsReport& report);

    // 判断连接类型 P2P / RELAY
    std::string getConnectionType(
        const webrtc::RTCStatsReport& report);

    // 提取可用带宽
    int getAvailableBitrate(
        const webrtc::RTCStatsReport& report);

    // 提取传输速率
    int getSendBitrate(const webrtc::RTCStatsReport& report);
    int getReceiveBitrate(const webrtc::RTCStatsReport& report);
};
```

**提取的 stats 字段映射**：

| UI 显示 | libwebrtc stats 字段 | 类型 |
|---|---|---|
| 连接类型 P2P/RELAY | `candidate-pair.local-candidate-type` + `candidate-pair.remote-candidate-type` | `local`/`srflx` → P2P; `relay` → RELAY |
| 可用带宽 | `candidate-pair.available-outgoing-bitrate` | bps |
| 上行速率 | `outbound-rtp.bytes-sent` / dt | bps |
| 下行速率 | `inbound-rtp.bytes-received` / dt | bps |
| 编码码率 | `outbound-rtp.bitrate-mean` 或 `encoder-implementation.target-bitrate` | bps |

## 8. 代码风格

详见 `05-code-design.md` 第 11 节。要点：
- 遵循 WebRTC/Chromium C++ 风格
- 所有自研代码**必须包含中文注释**
- 文件头、公共接口、关键学习点逻辑必须有中文注释
- `.clang-format` 配置文件放在 `app/src/main/cpp/.clang-format`

## 9. 对照阅读指引

| 自研文件 | 对照的 libwebrtc 源码 | 路径 |
|---|---|---|
| `vp9_encoder.cpp` | `LibvpxVp9Encoder` | `modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc` |
| `vp9_encoder_factory.cpp` | `VP9EncoderFactory` | `modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc` |
| `peer_connection_manager.cpp` | `PeerConnection` | `pc/peer_connection.cc` |
| `stats_collector.cpp` | `RTCStatsCollector` | `pc/rtc_stats_collector.cc` |
| `nat_detector.cpp` | 无直接对照 | 参考 RFC 5780 |
