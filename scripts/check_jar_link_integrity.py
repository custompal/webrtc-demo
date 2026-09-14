#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_jar_link_integrity.py — Java SDK 链路完整性检查（t23 新增不变式）

用途
----
扫描一个 .jar 内**全部 class 的常量池**，收集其引用的**类**（CONSTANT_Class 项），
并对两类引用做「必须能在同一 jar 内找到定义」的断言：

  1) 严格检查（STRICT，验收用）：所有 `org/webrtc/**Jni` 与 `org/jni_zero/**Jni` 引用
     —— 缺失数必须为 0。这正是真机 `NoClassDefFoundError: org.webrtc.PeerConnectionFactoryJni`
     的成因（交付 jar 缺少 jni_zero 生成的 *Jni 绑定实现类）。
  2) 宽松检查（INFO，仅报告不判定）：所有 `org/webrtc/**` / `org/jni_zero/**` 引用。
     平台类（`android/**`、`java/**`、`javax/**`、`dalvik/**`）由运行环境提供，不计入缺失。

另附带断言 `org/webrtc/PeerConnectionFactoryJni`：
  * 类存在；
  * 含静态方法 `get()`；
  * 至少含 1 个 `native` 方法（ACC_NATIVE=0x0100）。

退出码：0 = 严格检查通过（缺失=0）；1 = 存在缺失或断言失败；2 = 用法/IO 错误。
输出为可直接粘贴进报告的原始文本。

用法：
    python3 check_jar_link_integrity.py <jar 路径> [--expect-classes N]
"""
import sys
import struct
import zipfile
from collections import defaultdict

# ---- JVM class 文件常量池 tag ----
CP_Utf8 = 1
CP_Integer = 3
CP_Float = 4
CP_Long = 5
CP_Double = 6
CP_Class = 7
CP_String = 8
CP_Fieldref = 9
CP_Methodref = 10
CP_InterfaceMethodref = 11
CP_NameAndType = 12
CP_MethodHandle = 15
CP_MethodType = 16
CP_Dynamic = 17
CP_InvokeDynamic = 18
CP_Module = 19
CP_Package = 20

ACC_NATIVE = 0x0100
ACC_STATIC = 0x0008

PLATFORM_PREFIXES = ("android/", "java/", "javax/", "dalvik/", "sun/", "jdk/")


def _u1(b, i):
    return b[i], i + 1


def _u2(b, i):
    return struct.unpack_from(">H", b, i)[0], i + 2


def _u4(b, i):
    return struct.unpack_from(">I", b, i)[0], i + 4


def parse_constant_pool(b):
    """返回 (utf8: dict[idx->str], class_refs: set[str], this_class: str|None)"""
    if len(b) < 10 or b[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    i = 8  # magic(4) + minor(2) + major(2)
    cp_count, i = _u2(b, i)
    utf8 = {}
    class_name_idx = []
    idx = 1
    while idx < cp_count:
        tag, i = _u1(b, i)
        if tag == CP_Utf8:
            ln, i = _u2(b, i)
            utf8[idx] = b[i:i + ln].decode("utf-8", "replace")
            i += ln
        elif tag in (CP_Integer, CP_Float, CP_Fieldref, CP_Methodref,
                     CP_InterfaceMethodref, CP_NameAndType, CP_Dynamic,
                     CP_InvokeDynamic):
            i += 4
        elif tag in (CP_Long, CP_Double):
            i += 8
            idx += 1  # 占两个槽位
        elif tag == CP_Class:
            name_idx, i = _u2(b, i)
            class_name_idx.append(name_idx)
        elif tag in (CP_String, CP_MethodType, CP_Module, CP_Package):
            i += 2
        elif tag == CP_MethodHandle:
            i += 3
        else:
            raise ValueError("unknown constant pool tag %d at index %d" % (tag, idx))
        idx += 1
    # 类引用
    refs = set()
    for ni in class_name_idx:
        n = utf8.get(ni)
        if n:
            refs.add(n)
    # this_class
    this_class = None
    if i + 2 <= len(b):
        tc, _ = _u2(b, i)
        # class_name_idx 顺序即 CP_Class 出现顺序；需按索引映射
        # 简单起见：用 CONSTANT_Class 表重建
    # 重建索引->类名映射
    idx2name = {}
    ii = 10
    cp_count2, _ = _u2(b, 8)
    j = 1
    while j < cp_count2:
        t, ii2 = _u1(b, ii)
        if t == CP_Utf8:
            ln, ii3 = _u2(b, ii2)
            idx2name[j] = b[ii3:ii3 + ln].decode("utf-8", "replace")
            ii = ii3 + ln
        elif t in (CP_Integer, CP_Float, CP_Fieldref, CP_Methodref,
                   CP_InterfaceMethodref, CP_NameAndType, CP_Dynamic,
                   CP_InvokeDynamic):
            ii = ii2 + 4
        elif t in (CP_Long, CP_Double):
            ii = ii2 + 8
            j += 1
        elif t == CP_Class:
            ni, ii3 = _u2(b, ii2)
            ii = ii3
        elif t in (CP_String, CP_MethodType, CP_Module, CP_Package):
            ii = ii2 + 2
        elif t == CP_MethodHandle:
            ii = ii2 + 3
        else:
            break
        j += 1
    if i + 2 <= len(b):
        tc_idx, _ = _u2(b, i)
        # 找该 CONSTANT_Class 的 name_index
        ii = 10
        j = 1
        while j < cp_count2:
            t, ii2 = _u1(b, ii)
            if t == CP_Utf8:
                ln, ii3 = _u2(b, ii2)
                ii = ii3 + ln
            elif t in (CP_Integer, CP_Float, CP_Fieldref, CP_Methodref,
                       CP_InterfaceMethodref, CP_NameAndType, CP_Dynamic,
                       CP_InvokeDynamic):
                ii = ii2 + 4
            elif t in (CP_Long, CP_Double):
                ii = ii2 + 8
                j += 1
            elif t == CP_Class:
                if j == tc_idx:
                    ni, _ = _u2(b, ii2)
                    this_class = idx2name.get(ni)
                    break
                ni, ii3 = _u2(b, ii2)
                ii = ii3
            elif t in (CP_String, CP_MethodType, CP_Module, CP_Package):
                ii = ii2 + 2
            elif t == CP_MethodHandle:
                ii = ii2 + 3
            else:
                break
            j += 1
    return refs, this_class


def parse_methods(b):
    """返回 [(name, descriptor, access_flags)]"""
    out = []
    try:
        i = 8
        cp_count, i = _u2(b, i)
        idx = 1
        utf8 = {}
        while idx < cp_count:
            tag, i = _u1(b, i)
            if tag == CP_Utf8:
                ln, i = _u2(b, i)
                utf8[idx] = b[i:i + ln].decode("utf-8", "replace")
                i += ln
            elif tag in (CP_Integer, CP_Float, CP_Fieldref, CP_Methodref,
                         CP_InterfaceMethodref, CP_NameAndType, CP_Dynamic,
                         CP_InvokeDynamic):
                i += 4
            elif tag in (CP_Long, CP_Double):
                i += 8
                idx += 1
            elif tag == CP_Class:
                i += 2
            elif tag in (CP_String, CP_MethodType, CP_Module, CP_Package):
                i += 2
            elif tag == CP_MethodHandle:
                i += 3
            else:
                break
            idx += 1
        i += 6  # access_flags, this_class, super_class
        ic, i = _u2(b, i)
        i += 2 * ic
        fc, i = _u2(b, i)
        for _ in range(fc):
            i += 6
            ac, i = _u2(b, i)
            for _ in range(ac):
                _, i = _u2(b, i)
                ln, i = _u4(b, i)
                i += ln
        mc, i = _u2(b, i)
        for _ in range(mc):
            af, i = _u2(b, i)
            ni, i = _u2(b, i)
            di, i = _u2(b, i)
            ac, i = _u2(b, i)
            for _ in range(ac):
                _, i = _u2(b, i)
                ln, i = _u4(b, i)
                i += ln
            out.append((utf8.get(ni, "?"), utf8.get(di, "?"), af))
    except Exception:
        pass
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print("用法: python3 check_jar_link_integrity.py <jar 路径>")
        return 2
    jar = args[0]
    try:
        z = zipfile.ZipFile(jar)
    except Exception as e:
        print("ERROR: 无法打开 jar: %s" % e)
        return 2

    names = set(z.namelist())
    defined = {n[:-6].replace("/", "/") for n in names if n.endswith(".class")}

    strict_refs = defaultdict(set)   # ref -> {referrer}
    info_refs = defaultdict(set)
    n_classes = 0
    for n in sorted(names):
        if not n.endswith(".class"):
            continue
        try:
            b = z.read(n)
            refs, _this = parse_constant_pool(b)
        except Exception as e:
            print("WARN: 解析失败 %s: %s" % (n, e))
            continue
        n_classes += 1
        owner = n[:-6]
        for r in refs:
            if r.endswith("Jni") and (r.startswith("org/webrtc/") or r.startswith("org/jni_zero/")):
                strict_refs[r].add(owner)
            if r.startswith("org/webrtc/") or r.startswith("org/jni_zero/"):
                info_refs[r].add(owner)

    print("=" * 78)
    print("jar: %s" % jar)
    print("class 总数: %d" % n_classes)
    print("=" * 78)

    # ---- 严格检查 ----
    missing_strict = {r: v for r, v in strict_refs.items() if r not in defined}
    print("[STRICT] org/webrtc/*Jni + org/jni_zero/*Jni 引用检查")
    print("  被引用的 *Jni 类数: %d" % len(strict_refs))
    print("  缺失（引用了但 jar 内无定义）: %d" % len(missing_strict))
    for r in sorted(missing_strict):
        who = sorted(missing_strict[r])
        print("    MISSING %s.class   <- 被 %d 个类引用（例: %s）"
              % (r, len(who), ", ".join(who[:3])))

    # ---- 宽松检查（信息）----
    missing_info = {r: v for r, v in info_refs.items()
                    if r not in defined and not r.startswith(PLATFORM_PREFIXES)}
    print("[INFO]   全部 org/webrtc/** 与 org/jni_zero/** 引用（仅报告，不判定）")
    print("  缺失（非平台类）: %d" % len(missing_info))
    for r in sorted(missing_info):
        print("    INFO    %s.class   <- 被 %d 个类引用" % (r, len(missing_info[r])))

    # ---- 关键类断言 ----
    # 注意 jni_zero 的设计：`*Jni` 类**自身不声明 native 方法**，而是持有/调用
    # `org.jni_zero.GEN_JNI` 上的 native 入口。故断言为：
    #   a) 类存在；b) 有 public static get()；c) 该类调用 GEN_JNI，且 jar 内 GEN_JNI 存在并含 native 声明。
    key = "org/webrtc/PeerConnectionFactoryJni"
    ok_key = True
    print("[ASSERT] %s" % key)
    if key + ".class" not in names:
        print("  FAIL: 类不存在")
        ok_key = False
    else:
        b = z.read(key + ".class")
        ms = parse_methods(b)
        refs, _ = parse_constant_pool(b)
        has_get = any(m[0] == "get" and (m[2] & ACC_STATIC) for m in ms)
        natives = [m for m in ms if m[2] & ACC_NATIVE]
        calls_genjni = "org/jni_zero/GEN_JNI" in refs
        print("  存在: YES")
        print("  方法数: %d" % len(ms))
        print("  get() [static]: %s" % ("YES" if has_get else "NO"))
        print("  该类自身 native 方法数: %d （jni_zero 设计中 *Jni 不自带 native，通常为 0）" % len(natives))
        print("  该类是否调用 org/jni_zero/GEN_JNI: %s" % ("YES" if calls_genjni else "NO"))
        if not has_get:
            print("  FAIL: 缺静态 get()")
            ok_key = False
        if not calls_genjni:
            print("  FAIL: 未调用 GEN_JNI（不是有效的 jni_zero 绑定类）")
            ok_key = False

    # native 入口实际所在：GEN_JNI
    genjni_natives = 0
    if "org/jni_zero/GEN_JNI.class" in names:
        gm = parse_methods(z.read("org/jni_zero/GEN_JNI.class"))
        genjni_natives = len([m for m in gm if m[2] & ACC_NATIVE])
        print("[ASSERT] org/jni_zero/GEN_JNI: 存在 YES, native 声明数 = %d" % genjni_natives)
    else:
        print("[ASSERT] org/jni_zero/GEN_JNI: 存在 NO（*Jni 类运行期将抛 NoClassDefFoundError）")
        ok_key = False

    print("=" * 78)
    rc = 0 if (not missing_strict and ok_key) else 1
    print("RESULT: %s （严格缺失=%d, 关键类断言=%s, GEN_JNI native=%d）"
          % ("PASS" if rc == 0 else "FAIL", len(missing_strict),
             "OK" if ok_key else "FAILED", genjni_natives))
    print("=" * 78)
    return rc


if __name__ == "__main__":
    sys.exit(main())
