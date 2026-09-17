# JNI contract

> **GENERATED — do not edit.** Regenerate with `bash scripts/gen-doc-tables.sh`.
> Generator: `scripts/gen-doc-tables.sh` (sha256 `e7951ef4e950858588103cb1e5714d390612fd8db625f570759c20e5c8c156c5`)
> Deterministic: no timestamp is embedded, so repeated runs are byte-identical.
> Sources (sha256 of the exact revision this table was built from):
> - `app/src/main/cpp/jni/jni_bridge.h` — sha256 `8aa0b3388ca89cf8c2bd4f29370e8a578af5ddeb591361d7bce34741cb0fc2e5`
> - `app/src/main/cpp/jni/nat_detector_jni.cpp` — sha256 `647ccbea6f400d39d359164c7fdd4c09d0aaf8ea4a9dd052be24c1e491490af2`
> - `app/src/main/cpp/jni/native_log_jni.cpp` — sha256 `70f92b3b43bd9beb902feff86d7328119a9ca5374c9346df246023fc00d7ab00`
> - `app/src/main/cpp/jni/vp9_encoder_jni.cpp` — sha256 `8b8e83c31fa5778cd80aff1455a29ee3ffab5f92048e132b4150098827e38d1b`

> **Errata:** an earlier captain ruling assumed `Java_*` exports; this repo uses `JNI_OnLoad` + `RegisterNatives` (see `app/src/main/cpp/jni/jni_bridge.h:9`) — table is built from Kotlin declarations + `JNINativeMethod` tables + C prototypes.
>
> **Generated headers:** `:generated headers not present in repo; table built from Kotlin declarations + .so exports + cpp/jni sources`. If a generated `*_jni.h` is ever added to the repository this table picks it up first (priority: generated header > Kotlin declarations + `JNINativeMethod` tables).
>
> **`.so` is a build artifact, not in VCS:** `.gitignore:56 *.so` and `app/.gitignore:2 /build/`; `git ls-files app/src/main/jniLibs` is empty and the only tracked-artifact candidates in `app/src/main/jniLibs/arm64-v8a` are `libjingle_peerconnection_so.so` and `libc++_shared.so`. `libwebrtcdemo_native.so` exists only under `app/build/intermediates/**`.
>
> **Registration tables:** the `JNINativeMethod` arrays this table is built from live in `app/src/main/cpp/jni/nat_detector_jni.cpp:49`, `app/src/main/cpp/jni/native_log_jni.cpp:71` and `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347`.
>
> **Upstream libwebrtc bindings** (`J.N` / `GEN_JNI`) are documented descriptively — they live in `third_party/`, are upstream-owned and are not machine-generated here. Evidence: `reports/05-libwebrtc-build.md:496` (and the t29/t30/t36 signature comparisons; t36 produced the 193/193 comparison).

## 1. Registered Kotlin classes (`app/src/main/cpp/jni/jni_bridge.h`)

| Class constant | Kotlin class path | Declared at |
|---|---|---|
| `kNativeLogClass` | `com/example/webrtcdemo/nativebridge/NativeLog` | `app/src/main/cpp/jni/jni_bridge.h:20` |
| `kNativeVp9EncoderClass` | `com/example/webrtcdemo/nativebridge/NativeVp9Encoder` | `app/src/main/cpp/jni/jni_bridge.h:22` |
| `kNativeNatDetectorClass` | `com/example/webrtcdemo/nativebridge/NativeNatDetector` | `app/src/main/cpp/jni/jni_bridge.h:24` |
| `kNativeCallbacksClass` | `com/example/webrtcdemo/nativebridge/NativeCallbacks` | `app/src/main/cpp/jni/jni_bridge.h:26` |

## 2. Kotlin `external fun` declarations (`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/**`)

| Object | Function | Parameters | Derived JNI descriptor | KDoc descriptor | Source |
|---|---|---|---|---|---|
| `NativeLog` | `nativeInit` | logDir:String, fileNameBase:String, level:Int, maxBytesPerFile:Long, maxFiles:Int | `(Ljava/lang/String;Ljava/lang/String;IJI)V` | `(Ljava/lang/String;Ljava/lang/String;IJI)V` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:64` |
| `NativeLog` | `nativeSetLevel` | level:Int | `(I)V` | `(I)V` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:78` |
| `NativeLog` | `nativeFlush` |  | `()V` | `()V` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:82` |
| `NativeLog` | `nativeShutdown` |  | `()V` | `()V` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:86` |
| `NativeNatDetector` | `nativeDetect` | stunHost:String, stunPort:Int, timeoutMs:Long | `(Ljava/lang/String;IJ)V` | `(Ljava/lang/String;IJ)V` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeNatDetector.kt:24` |
| `NativeNatDetector` | `nativeCancel` |  | `()V` | `()V` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeNatDetector.kt:28` |
| `NativeVp9Encoder` | `nativeCreate` |  | `()J` | `()J` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:40` |
| `NativeVp9Encoder` | `nativeInit` | handle:Long, width:Int, height:Int, startBitrateBps:Int, maxBitrateBps:Int, maxFramerate:Int, numSpatialLayers:Int, numTemporalLayers:Int | `(JIIIIIII)I` | `(JIIIIIII)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:49` |
| `NativeVp9Encoder` | `nativeEncode` | handle:Long, y:ByteBuffer, u:ByteBuffer, v:ByteBuffer, width:Int, height:Int, strideY:Int, strideU:Int, strideV:Int, captureTimeNs:Long, rotationDegrees:Int, requestKeyFrame:Boolean | `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I` | `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:69` |
| `NativeVp9Encoder` | `nativeCopyEncodedFrame` | handle:Long, dst:ByteBuffer, outMeta:IntArray | `(JLjava/nio/ByteBuffer;[I)I` | `(JLjava/nio/ByteBuffer;[I)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:93` |
| `NativeVp9Encoder` | `nativeGetEncodedFrameSize` | handle:Long | `(J)I` | `(J)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:97` |
| `NativeVp9Encoder` | `nativeSetRates` | handle:Long, layerBitratesBps:IntArray, numSpatialLayers:Int, numTemporalLayers:Int, totalBitrateBps:Int, framerateFps:Int | `(J[IIIII)I` | `(J[IIIII)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:105` |
| `NativeVp9Encoder` | `nativeRequestKeyFrame` | handle:Long | `(J)I` | `(J)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:116` |
| `NativeVp9Encoder` | `nativeRelease` | handle:Long | `(J)I` | `(J)I` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:120` |
| `NativeVp9Encoder` | `nativeGetImplName` |  | `()Ljava/lang/String;` | `()Ljava/lang/String;` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:124` |

## 3. `JNINativeMethod` registration tables (native side)

| Array | Method name | JNI descriptor | C function | Source |
|---|---|---|---|---|
| `kNatDetectorMethods` | `nativeDetect` | `(Ljava/lang/String;IJ)V` | `(NativeDetect` | `app/src/main/cpp/jni/nat_detector_jni.cpp:50` |
| `kNatDetectorMethods` | `nativeCancel` | `()V` | `(NativeCancel` | `app/src/main/cpp/jni/nat_detector_jni.cpp:52` |
| `kNativeLogMethods` | `nativeInit` | `(Ljava/lang/String;Ljava/lang/String;IJI)V` | `(NativeLogInit` | `app/src/main/cpp/jni/native_log_jni.cpp:72` |
| `kNativeLogMethods` | `nativeSetLevel` | `(I)V` | `(NativeLogSetLevel` | `app/src/main/cpp/jni/native_log_jni.cpp:74` |
| `kNativeLogMethods` | `nativeFlush` | `()V` | `(NativeLogFlush` | `app/src/main/cpp/jni/native_log_jni.cpp:75` |
| `kNativeLogMethods` | `nativeShutdown` | `()V` | `(NativeLogShutdown` | `app/src/main/cpp/jni/native_log_jni.cpp:76` |
| `kVp9EncoderMethods` | `nativeCreate` | `()J` | `(NativeCreate` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:348` |
| `kVp9EncoderMethods` | `nativeInit` | `(JIIIIIII)I` | `(NativeInit` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:349` |
| `kVp9EncoderMethods` | `nativeEncode` | `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I` | `(NativeEncode` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:350` |
| `kVp9EncoderMethods` | `nativeCopyEncodedFrame` | `(JLjava/nio/ByteBuffer;[I)I` | `(NativeCopyEncodedFrame` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:353` |
| `kVp9EncoderMethods` | `nativeGetEncodedFrameSize` | `(J)I` | `(NativeGetEncodedFrameSize` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:355` |
| `kVp9EncoderMethods` | `nativeSetRates` | `(J[IIIII)I` | `(NativeSetRates` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:357` |
| `kVp9EncoderMethods` | `nativeRequestKeyFrame` | `(J)I` | `(NativeRequestKeyFrame` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:358` |
| `kVp9EncoderMethods` | `nativeRelease` | `(J)I` | `(NativeRelease` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:360` |
| `kVp9EncoderMethods` | `nativeGetImplName` | `()Ljava/lang/String;` | `(NativeGetImplName` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:361` |

## 4. C function definitions (`app/src/main/cpp/jni/*.h|*.cpp`)

| C function | Definition |
|---|---|
| `(NativeCancel` | **not found** |
| `(NativeCopyEncodedFrame` | **not found** |
| `(NativeCreate` | **not found** |
| `(NativeDetect` | **not found** |
| `(NativeEncode` | **not found** |
| `(NativeGetEncodedFrameSize` | **not found** |
| `(NativeGetImplName` | **not found** |
| `(NativeInit` | **not found** |
| `(NativeLogFlush` | **not found** |
| `(NativeLogInit` | **not found** |
| `(NativeLogSetLevel` | **not found** |
| `(NativeLogShutdown` | **not found** |
| `(NativeRelease` | **not found** |
| `(NativeRequestKeyFrame` | **not found** |
| `(NativeSetRates` | **not found** |

## 5. Kotlin -> native callbacks (C++ calls Java)

Registered through `GetStaticMethodID` in `app/src/main/cpp/jni/callback_bridge.cpp`.

| Kotlin method | Kotlin declaration | C++ name constant | C++ signature constant |
|---|---|---|---|
| `onNatTypeDetected` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:36` | **missing** | **missing** |
| `onLogEvent` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:52` | **missing** | **missing** |

## 6. `.so` exported symbols

| Check | Result |
|---|---|
| `.so` present in repo tree | UNVERIFIED (.so is a build artifact, not in repo) — binding is `JNI_OnLoad` + `RegisterNatives`, so no `Java_*` exports are expected |
| binutils for symbol extraction | UNVERIFIED (no binutils in container: `nm`/`readelf`/`objdump` unavailable) |

## 7. Divergence: Kotlin declarations vs `JNINativeMethod` tables

| Direction | Methods |
|---|---|
| Declared in Kotlin, not registered natively | _(none)_ |
| Registered natively, not declared in Kotlin | _(none)_ |

| Method (`Object#method`) | Kotlin descriptor (derived) | Native descriptor (registered) | Match |
|---|---|---|---|
| `NativeLog#nativeFlush` | `()V` | `()V` | yes |
| `NativeLog#nativeInit` | `(Ljava/lang/String;Ljava/lang/String;IJI)V` | `(Ljava/lang/String;Ljava/lang/String;IJI)V` | yes |
| `NativeLog#nativeSetLevel` | `(I)V` | `(I)V` | yes |
| `NativeLog#nativeShutdown` | `()V` | `()V` | yes |
| `NativeNatDetector#nativeCancel` | `()V` | `()V` | yes |
| `NativeNatDetector#nativeDetect` | `(Ljava/lang/String;IJ)V` | `(Ljava/lang/String;IJ)V` | yes |
| `NativeVp9Encoder#nativeCopyEncodedFrame` | `(JLjava/nio/ByteBuffer;[I)I` | `(JLjava/nio/ByteBuffer;[I)I` | yes |
| `NativeVp9Encoder#nativeCreate` | `()J` | `()J` | yes |
| `NativeVp9Encoder#nativeEncode` | `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I` | `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I` | yes |
| `NativeVp9Encoder#nativeGetEncodedFrameSize` | `(J)I` | `(J)I` | yes |
| `NativeVp9Encoder#nativeGetImplName` | `()Ljava/lang/String;` | `()Ljava/lang/String;` | yes |
| `NativeVp9Encoder#nativeInit` | `(JIIIIIII)I` | `(JIIIIIII)I` | yes |
| `NativeVp9Encoder#nativeRelease` | `(J)I` | `(J)I` | yes |
| `NativeVp9Encoder#nativeRequestKeyFrame` | `(J)I` | `(J)I` | yes |
| `NativeVp9Encoder#nativeSetRates` | `(J[IIIII)I` | `(J[IIIII)I` | yes |

**Result: `15` Kotlin declarations, `15` registered natives, `15` descriptor matches; missing: Kotlin-side none, native-side none.**
