/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件由 guard_gen.py 生成，是 XLMod 的一部分；按 GNU AGPL-3.0（或更高版本）发布，详见 LICENSE。
 */

package net.xuele.xuelets.mod.guard;

/**
 * 多 dex 互锁守卫（A 片）——编译进 classes8.dex。
 *
 * <p>本类的取值参与 Mod 字符串解密密钥的计算：{@code key = 0x5A ^ Guard8.a() ^ Guard9.b()}。
 * 因此删除 classes8.dex 或 classes9.dex 都会导致密钥错误、Mod 内字符串全部乱码（功能整体失效）。</p>
 *
 * <p>类名与方法名由构建脚本固定，并在 obf-rules.pro 中 keep。</p>
 */
public final class Guard8 {

    private static int sCache = -1;

    /** 存在性指纹（固定常量，JVM 与 ART 一致） */
    public static int tag() { return 14151; }

    /** 对外唯一入口：跑完自校验链后返回本片分片值 */
    public static int a() {
        if (sCache >= 0) return sCache;
        int self = chain();
        // 交叉校验：必须能在运行时看到 Guard9（否则返回一个错误值）
        int peer;
        try {
            Class<?> c = Class.forName("net.xuele.xuelets.mod.guard.Guard9");
            peer = ((Integer) c.getMethod("tag").invoke(null)).intValue();
        } catch (Throwable t) {
            peer = 0;                       // 缺失同伴 → 密钥必错
        }
        sCache = (self ^ (peer & 0xFF)) & 0x7FFF;
        return sCache;
    }

    /** 内部校验片段 #0（结果参与 key 链，本身无副作用） */
    private static int f0() {
        int h = 64836;
long acc = 114674L;
h = (h * 31 + 32583) ^ (h >>> 3);
acc = (acc * 53397L + h) & 0x7FFFFFFFL;
h = (h * 31 + 14586) ^ (h >>> 4);
acc = (acc * 32583L + h) & 0x7FFFFFFFL;
h = (h * 31 + 64836) ^ (h >>> 5);
acc = (acc * 14587L + h) & 0x7FFFFFFFL;
h = (h * 31 + 53397) ^ (h >>> 6);
acc = (acc * 64837L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32583) ^ (h >>> 7);
acc = (acc * 53397L + h) & 0x7FFFFFFFL;
h = (h * 31 + 14586) ^ (h >>> 3);
acc = (acc * 32583L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 14586;
    }

    /** 内部校验片段 #1（结果参与 key 链，本身无副作用） */
    private static int f1() {
        int h = 59659;
long acc = 521647L;
h = (h * 31 + 16880) ^ (h >>> 3);
acc = (acc * 30899L + h) & 0x7FFFFFFFL;
h = (h * 31 + 24857) ^ (h >>> 4);
acc = (acc * 16881L + h) & 0x7FFFFFFFL;
h = (h * 31 + 59659) ^ (h >>> 5);
acc = (acc * 24857L + h) & 0x7FFFFFFFL;
h = (h * 31 + 30899) ^ (h >>> 6);
acc = (acc * 59659L + h) & 0x7FFFFFFFL;
h = (h * 31 + 16880) ^ (h >>> 7);
acc = (acc * 30899L + h) & 0x7FFFFFFFL;
h = (h * 31 + 24857) ^ (h >>> 3);
acc = (acc * 16881L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 24857;
    }

    /** 内部校验片段 #2（结果参与 key 链，本身无副作用） */
    private static int f2() {
        int h = 59850;
long acc = 856926L;
h = (h * 31 + 34828) ^ (h >>> 3);
acc = (acc * 50223L + h) & 0x7FFFFFFFL;
h = (h * 31 + 34453) ^ (h >>> 4);
acc = (acc * 34829L + h) & 0x7FFFFFFFL;
h = (h * 31 + 59850) ^ (h >>> 5);
acc = (acc * 34453L + h) & 0x7FFFFFFFL;
h = (h * 31 + 50222) ^ (h >>> 6);
acc = (acc * 59851L + h) & 0x7FFFFFFFL;
h = (h * 31 + 34828) ^ (h >>> 7);
acc = (acc * 50223L + h) & 0x7FFFFFFFL;
h = (h * 31 + 34453) ^ (h >>> 3);
acc = (acc * 34829L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 34453;
    }

    /** 内部校验片段 #3（结果参与 key 链，本身无副作用） */
    private static int f3() {
        int h = 59339;
long acc = 895293L;
h = (h * 31 + 32610) ^ (h >>> 3);
acc = (acc * 46295L + h) & 0x7FFFFFFFL;
h = (h * 31 + 61163) ^ (h >>> 4);
acc = (acc * 32611L + h) & 0x7FFFFFFFL;
h = (h * 31 + 59339) ^ (h >>> 5);
acc = (acc * 61163L + h) & 0x7FFFFFFFL;
h = (h * 31 + 46295) ^ (h >>> 6);
acc = (acc * 59339L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32610) ^ (h >>> 7);
acc = (acc * 46295L + h) & 0x7FFFFFFFL;
h = (h * 31 + 61163) ^ (h >>> 3);
acc = (acc * 32611L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 61163;
    }

    /** 内部校验片段 #4（结果参与 key 链，本身无副作用） */
    private static int f4() {
        int h = 15981;
long acc = 712774L;
h = (h * 31 + 58855) ^ (h >>> 3);
acc = (acc * 32683L + h) & 0x7FFFFFFFL;
h = (h * 31 + 44253) ^ (h >>> 4);
acc = (acc * 58855L + h) & 0x7FFFFFFFL;
h = (h * 31 + 15981) ^ (h >>> 5);
acc = (acc * 44253L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32683) ^ (h >>> 6);
acc = (acc * 15981L + h) & 0x7FFFFFFFL;
h = (h * 31 + 58855) ^ (h >>> 7);
acc = (acc * 32683L + h) & 0x7FFFFFFFL;
h = (h * 31 + 44253) ^ (h >>> 3);
acc = (acc * 58855L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 44253;
    }

    /** 内部校验片段 #5（结果参与 key 链，本身无副作用） */
    private static int f5() {
        int h = 59567;
long acc = 399960L;
h = (h * 31 + 11154) ^ (h >>> 3);
acc = (acc * 52659L + h) & 0x7FFFFFFFL;
h = (h * 31 + 7691) ^ (h >>> 4);
acc = (acc * 11155L + h) & 0x7FFFFFFFL;
h = (h * 31 + 59567) ^ (h >>> 5);
acc = (acc * 7691L + h) & 0x7FFFFFFFL;
h = (h * 31 + 52659) ^ (h >>> 6);
acc = (acc * 59567L + h) & 0x7FFFFFFFL;
h = (h * 31 + 11154) ^ (h >>> 7);
acc = (acc * 52659L + h) & 0x7FFFFFFFL;
h = (h * 31 + 7691) ^ (h >>> 3);
acc = (acc * 11155L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 7691;
    }

    /** 内部校验片段 #6（结果参与 key 链，本身无副作用） */
    private static int f6() {
        int h = 16260;
long acc = 215535L;
h = (h * 31 + 18538) ^ (h >>> 3);
acc = (acc * 9139L + h) & 0x7FFFFFFFL;
h = (h * 31 + 22857) ^ (h >>> 4);
acc = (acc * 18539L + h) & 0x7FFFFFFFL;
h = (h * 31 + 16260) ^ (h >>> 5);
acc = (acc * 22857L + h) & 0x7FFFFFFFL;
h = (h * 31 + 9138) ^ (h >>> 6);
acc = (acc * 16261L + h) & 0x7FFFFFFFL;
h = (h * 31 + 18538) ^ (h >>> 7);
acc = (acc * 9139L + h) & 0x7FFFFFFFL;
h = (h * 31 + 22857) ^ (h >>> 3);
acc = (acc * 18539L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 22857;
    }

    /** 内部校验片段 #7（结果参与 key 链，本身无副作用） */
    private static int f7() {
        int h = 33876;
long acc = 941279L;
h = (h * 31 + 31686) ^ (h >>> 3);
acc = (acc * 8201L + h) & 0x7FFFFFFFL;
h = (h * 31 + 27936) ^ (h >>> 4);
acc = (acc * 31687L + h) & 0x7FFFFFFFL;
h = (h * 31 + 33876) ^ (h >>> 5);
acc = (acc * 27937L + h) & 0x7FFFFFFFL;
h = (h * 31 + 8200) ^ (h >>> 6);
acc = (acc * 33877L + h) & 0x7FFFFFFFL;
h = (h * 31 + 31686) ^ (h >>> 7);
acc = (acc * 8201L + h) & 0x7FFFFFFFL;
h = (h * 31 + 27936) ^ (h >>> 3);
acc = (acc * 31687L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 27936;
    }

    /** 内部校验片段 #8（结果参与 key 链，本身无副作用） */
    private static int f8() {
        int h = 30401;
long acc = 987611L;
h = (h * 31 + 59382) ^ (h >>> 3);
acc = (acc * 13311L + h) & 0x7FFFFFFFL;
h = (h * 31 + 24271) ^ (h >>> 4);
acc = (acc * 59383L + h) & 0x7FFFFFFFL;
h = (h * 31 + 30401) ^ (h >>> 5);
acc = (acc * 24271L + h) & 0x7FFFFFFFL;
h = (h * 31 + 13310) ^ (h >>> 6);
acc = (acc * 30401L + h) & 0x7FFFFFFFL;
h = (h * 31 + 59382) ^ (h >>> 7);
acc = (acc * 13311L + h) & 0x7FFFFFFFL;
h = (h * 31 + 24271) ^ (h >>> 3);
acc = (acc * 59383L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 24271;
    }

    /** 内部校验片段 #9（结果参与 key 链，本身无副作用） */
    private static int f9() {
        int h = 136;
long acc = 329278L;
h = (h * 31 + 61045) ^ (h >>> 3);
acc = (acc * 24317L + h) & 0x7FFFFFFFL;
h = (h * 31 + 65453) ^ (h >>> 4);
acc = (acc * 61045L + h) & 0x7FFFFFFFL;
h = (h * 31 + 136) ^ (h >>> 5);
acc = (acc * 65453L + h) & 0x7FFFFFFFL;
h = (h * 31 + 24316) ^ (h >>> 6);
acc = (acc * 137L + h) & 0x7FFFFFFFL;
h = (h * 31 + 61045) ^ (h >>> 7);
acc = (acc * 24317L + h) & 0x7FFFFFFFL;
h = (h * 31 + 65453) ^ (h >>> 3);
acc = (acc * 61045L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 65453;
    }

    /** 内部校验片段 #10（结果参与 key 链，本身无副作用） */
    private static int f10() {
        int h = 50063;
long acc = 487143L;
h = (h * 31 + 38754) ^ (h >>> 3);
acc = (acc * 63441L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32841) ^ (h >>> 4);
acc = (acc * 38755L + h) & 0x7FFFFFFFL;
h = (h * 31 + 50063) ^ (h >>> 5);
acc = (acc * 32841L + h) & 0x7FFFFFFFL;
h = (h * 31 + 63440) ^ (h >>> 6);
acc = (acc * 50063L + h) & 0x7FFFFFFFL;
h = (h * 31 + 38754) ^ (h >>> 7);
acc = (acc * 63441L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32841) ^ (h >>> 3);
acc = (acc * 38755L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 32841;
    }

    /** 内部校验片段 #11（结果参与 key 链，本身无副作用） */
    private static int f11() {
        int h = 13065;
long acc = 581425L;
h = (h * 31 + 38842) ^ (h >>> 3);
acc = (acc * 44485L + h) & 0x7FFFFFFFL;
h = (h * 31 + 2026) ^ (h >>> 4);
acc = (acc * 38843L + h) & 0x7FFFFFFFL;
h = (h * 31 + 13065) ^ (h >>> 5);
acc = (acc * 2027L + h) & 0x7FFFFFFFL;
h = (h * 31 + 44485) ^ (h >>> 6);
acc = (acc * 13065L + h) & 0x7FFFFFFFL;
h = (h * 31 + 38842) ^ (h >>> 7);
acc = (acc * 44485L + h) & 0x7FFFFFFFL;
h = (h * 31 + 2026) ^ (h >>> 3);
acc = (acc * 38843L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 2026;
    }

    /** 把 filler 串成链（互相依赖，删除任一方法都会让分片改变） */
    private static int chain() {
        int acc = 0;
        acc = (acc * 7 + f0()) & 0x7FFFFFFF;
        acc = (acc * 7 + f1()) & 0x7FFFFFFF;
        acc = (acc * 7 + f2()) & 0x7FFFFFFF;
        acc = (acc * 7 + f3()) & 0x7FFFFFFF;
        acc = (acc * 7 + f4()) & 0x7FFFFFFF;
        acc = (acc * 7 + f5()) & 0x7FFFFFFF;
        acc = (acc * 7 + f6()) & 0x7FFFFFFF;
        acc = (acc * 7 + f7()) & 0x7FFFFFFF;
        acc = (acc * 7 + f8()) & 0x7FFFFFFF;
        acc = (acc * 7 + f9()) & 0x7FFFFFFF;
        acc = (acc * 7 + f10()) & 0x7FFFFFFF;
        acc = (acc * 7 + f11()) & 0x7FFFFFFF;
        return (acc ^ 16127) & 0x7FFFFFFF;
    }
}
