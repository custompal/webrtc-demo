#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_jn_binding.py —— jni_zero「hashing / proxy」形态下 Java↔.so 可绑定性的静态证明（t29 核心验收）

背景（源码级依据）
------------------
third_party/jni_zero/jni_registration_generator.py:232-262 有两种模式：
  * jni_mode.is_hashing or jni_mode.is_muxing:
        # org/jni_zero/GEN_JNI.java  ← gen_jni_java.generate_forwarding(...)   （转发层，非 native）
        # J/N.java                   ← gen_jni_java.generate_impl(..., short_gen_jni_class, ...) （native 哈希名）
  * else:
        # org/jni_zero/GEN_JNI.java  ← generate_impl(...)                      （经典注册形态，native 可读名）
本构建的 GN 目标传 --use-proxy-hash ⇒ is_hashing=True ⇒ 走第一支。

JNI 符号规则（jni_zero proxy.py::hashed_name + JNI mangling）
-------------------------------------------------------------
  方法名（Java 侧）= hashed = ('M' + base64(md5(<可读 native 名>), altchars=b'$_')).rstrip('=')[:8]
  导出符号        = 'Java_' + jni_mangle('J/N') + '_' + jni_mangle(hashed)
  jni_mangle: '_' -> '_1' ; '$' -> '_00024' ; '/' -> '_'
  ⇒ 对类 J.N，符号形如 Java_J_N_<mangled hashed>（如 Java_J_N_M_1YMMZf6）

本脚本做三件事（全部静态、无需真机）
------------------------------------
  E1  J/N.class 存在，且其 native 方法名集合 按上式变形后，与 .so 导出的 Java_J_N_* 集合
      **双向相等（差集为空）**；
  E2  GEN_JNI 为转发层（native 数 == 0），且每个方法体 invokestatic 到 J/N.<某个 native 名>；
  E3  jar 内 48 个 *Jni 包装类调用的 GEN_JNI.<可读名> 调用点，是否都能在 GEN_JNI 中找到对应方法
      （缺失项单独列出；AV1 属已知豁免）。

用法
----
  python3 scripts/check_jn_binding.py \
      --jar  third_party/libwebrtc/java/libwebrtc-java.jar \
      --so   third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so
  可选：--javap <path>  --nm <path>  --expect-native <n>
退出码：0 = 全通过（E3 的"已知豁免"白名单项不计失败）；1 = 有失败项。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

DEFAULT_JAVAP = "/opt/dsh-workspaces/webrtc-build/src/third_party/jdk/current/bin/javap"
DEFAULT_NM = ("/opt/dsh-workspaces/android-sdk/ndk/26.1.10909125/toolchains/"
              "llvm/prebuilt/linux-x86_64/bin/llvm-nm")
# 已知豁免：仅存在于库侧 placeholder、未编入 libjingle_peerconnection_so 的 native
KNOWN_EXEMPT = {"org_webrtc_LibaomAv1Encoder_create"}


def jni_mangle(s):
    """JNI 名字转义：'_' -> '_1'，'$' -> '_00024'，'/' -> '_'（顺序敏感：先转义 _ 再转 /）。"""
    out = []
    for ch in s:
        if ch == "_":
            out.append("_1")
        elif ch == "$":
            out.append("_00024")
        elif ch == "/":
            out.append("_")
        else:
            out.append(ch)
    return "".join(out)


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def unzip_list(jar):
    with zipfile.ZipFile(jar) as z:
        return z.namelist()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--so", required=True)
    ap.add_argument("--javap", default=DEFAULT_JAVAP)
    ap.add_argument("--nm", default=DEFAULT_NM)
    ap.add_argument("--expect-native", type=int, default=193,
                    help=".so 应导出的 Java_J_N_* 符号数（默认 193）")
    a = ap.parse_args()

    fails = []
    print("=" * 78)
    print("jni_zero hashing(proxy) 形态 Java<->.so 可绑定性检查")
    print("  jar =", a.jar)
    print("  so  =", a.so)
    print("=" * 78)

    # ---------- E1 ----------
    print("\n[E1] J/N.class 存在 + native 名集合 <-> .so 符号集合 双向相等")
    names = unzip_list(a.jar)
    has_jn = "J/N.class" in names
    print("     jar 内 'J/N.class' 存在:", "YES" if has_jn else "NO")
    if not has_jn:
        fails.append("jar 内缺少 J/N.class —— 即仍为编译期 stub 形态，绑定必失败")

    rc, out, err = run([a.javap, "-p", "-classpath", a.jar, "J.N"])
    jn_natives = []
    if rc == 0:
        for ln in out.splitlines():
            m = re.search(r"^\s+(?:public|private|protected)?\s*static\s+native\s+"
                          r"[\w.<>\[\]$]+\s+([\w$]+)\s*\(", ln)
            if m:
                jn_natives.append(m.group(1))
    jn_natives = sorted(set(jn_natives))
    print("     J.N native 方法数（唯一）:", len(jn_natives))
    if not jn_natives:
        fails.append("javap 未解出 J.N 的 native 方法（类缺失或非 native）")

    rc2, soout, _ = run(["grep", "-ao", r"Java_J_N_M[A-Za-z0-9_$]*", a.so])
    so_syms = sorted(set(x for x in soout.split() if x))
    print("     .so 导出 Java_J_N_* 符号数:", len(so_syms),
          "(期望 %d)" % a.expect_native)
    if len(so_syms) != a.expect_native:
        fails.append(".so 导出符号数 %d != 期望 %d" % (len(so_syms), a.expect_native))

    # 由 Java 侧方法名按官方规则复算期望符号
    cls_mangled = jni_mangle("J/N")            # -> J_N
    expected = {}
    for n in jn_natives:
        expected["Java_" + cls_mangled + "_" + jni_mangle(n)] = n
    exp_set, so_set = set(expected), set(so_syms)
    only_exp = sorted(exp_set - so_set)
    only_so = sorted(so_set - exp_set)
    print("     期望符号数(由 J.N 复算):", len(exp_set))
    print("     **双向差集**: J.N 独有 = %d ; .so 独有 = %d" % (len(only_exp), len(only_so)))
    for s in only_exp[:5]:
        print("        J.N-only :", s, "<-", expected[s])
    for s in only_so[:5]:
        print("        .so-only :", s)
    if only_exp or only_so:
        fails.append("J.N 与 .so 符号集合不相等（双向差集非空）")
    else:
        print("     => PASS：精确集合相等（逐条可绑定）")

    # ---------- E2 ----------
    print("\n[E2] GEN_JNI 为转发层（native == 0）且转发到 J.N.<native>")
    rc3, gj, _ = run([a.javap, "-p", "-classpath", a.jar, "org.jni_zero.GEN_JNI"])
    gj_natives = [l for l in gj.splitlines() if "native" in l]
    gj_methods = re.findall(r"^\s+public\s+static\s+[\w.<>\[\]$]+\s+([\w$]+)\s*\(",
                            gj, re.M)
    print("     GEN_JNI native 方法数:", len(gj_natives), "（期望 0）")
    print("     GEN_JNI 方法数:", len(set(gj_methods)))
    if gj_natives:
        fails.append("GEN_JNI 仍含 native 方法（应为纯转发层）")
    rc4, gjc, _ = run([a.javap, "-c", "-p", "-classpath", a.jar, "org.jni_zero.GEN_JNI"])
    fwd = set(re.findall(r"Method J/N\.([\w$]+):", gjc))
    print("     GEN_JNI 字节码中 invokestatic J/N.<name> 不同目标数:", len(fwd))
    if len(fwd) == 0:
        fails.append("GEN_JNI 方法体未转发到 J/N.<native>（字节码中未发现 J/N. 调用）")
    not_native = sorted(fwd - set(jn_natives))
    if not_native:
        fails.append("GEN_JNI 转发的目标不在 J.N native 集合中: %s" % not_native[:3])

    # ---------- E3 ----------
    print("\n[E3] jar 内 *Jni 包装类调用的 GEN_JNI.<可读名> 是否都有对应方法")
    tmp = tempfile.mkdtemp(prefix="jnbind-")
    try:
        with zipfile.ZipFile(a.jar) as z:
            z.extractall(tmp)
        # 精确取调用点：对每个 *Jni 类反汇编，抓 invokestatic Method org/jni_zero/GEN_JNI.<name>
        called = set()
        for root, _, files in os.walk(tmp):
            for f in files:
                if not f.endswith("Jni.class"):
                    continue
                rel = os.path.relpath(os.path.join(root, f), tmp)[:-6].replace("/", ".")
                rc5, bc, _ = run([a.javap, "-c", "-p", "-classpath", a.jar, rel])
                if rc5 == 0:
                    for m in re.finditer(r"Method org/jni_zero/GEN_JNI\.([\w$]+):", bc):
                        called.add(m.group(1))
        gj_names = set(gj_methods)
        missing = sorted(c for c in called if c not in gj_names)
        exempt = sorted(c for c in missing if c in KNOWN_EXEMPT)
        real_missing = [c for c in missing if c not in KNOWN_EXEMPT]
        print("     *Jni 调用点（distinct 可读名）:", len(called))
        print("     GEN_JNI 提供的方法名:", len(gj_names))
        print("     未被 GEN_JNI 覆盖:", len(missing), "→ 已知豁免", len(exempt), "；真缺失",
              len(real_missing))
        for c in exempt:
            print("        [豁免]", c, "（AV1：libaom 编码器未编入 libjingle_peerconnection_so）")
        for c in real_missing[:5]:
            print("        [缺失]", c)
        if real_missing:
            fails.append("存在未被 GEN_JNI 覆盖的非豁免调用点: %s" % real_missing[:3])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # ---------- 汇总 ----------
    print("\n" + "=" * 78)
    if fails:
        print("RESULT: FAIL")
        for f in fails:
            print("  -", f)
        print("=" * 78)
        return 1
    print("RESULT: PASS （J.N native 名 <-> .so 符号 双向差集为空；GEN_JNI 纯转发；"
          "*Jni 调用点全覆盖，仅 AV1 为已知豁免）")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
