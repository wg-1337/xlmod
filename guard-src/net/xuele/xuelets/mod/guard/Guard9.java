/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件由 guard_gen.py 生成，是 XLMod 的一部分；按 GNU AGPL-3.0（或更高版本）发布，详见 LICENSE。
 */

package net.xuele.xuelets.mod.guard;

/**
 * 多 dex 互锁守卫（B 片）——编译进 classes9.dex。
 *
 * <p>与 {@link Guard8} 互相引用（都走反射），两者同时存在时密钥才正确。</p>
 */
public final class Guard9 {

    private static int sCache = -1;

    /** 存在性指纹（固定常量，JVM 与 ART 一致） */
    public static int tag() { return 16825; }

    /** 对外唯一入口：本片分片值 */
    public static int b() {
        if (sCache >= 0) return sCache;
        int self = chain();
        int peer;
        try {
            Class<?> c = Class.forName("net.xuele.xuelets.mod.guard.Guard8");
            // 只取同伴的"存在性指纹"，避免递归
            peer = ((Integer) c.getMethod("tag").invoke(null)).intValue();
        } catch (Throwable t) {
            peer = 0;
        }
        sCache = (self ^ (peer & 0x7FFF)) & 0x7FFF;
        return sCache;
    }

    /** 内部校验片段 #0（结果参与 key 链，本身无副作用） */
    private static int f0() {
        int h = 60139;
long acc = 569662L;
h = (h * 31 + 7123) ^ (h >>> 3);
acc = (acc * 6281L + h) & 0x7FFFFFFFL;
h = (h * 31 + 4205) ^ (h >>> 4);
acc = (acc * 7123L + h) & 0x7FFFFFFFL;
h = (h * 31 + 60139) ^ (h >>> 5);
acc = (acc * 4205L + h) & 0x7FFFFFFFL;
h = (h * 31 + 6280) ^ (h >>> 6);
acc = (acc * 60139L + h) & 0x7FFFFFFFL;
h = (h * 31 + 7123) ^ (h >>> 7);
acc = (acc * 6281L + h) & 0x7FFFFFFFL;
h = (h * 31 + 4205) ^ (h >>> 3);
acc = (acc * 7123L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 4205;
    }

    /** 内部校验片段 #1（结果参与 key 链，本身无副作用） */
    private static int f1() {
        int h = 41756;
long acc = 356950L;
h = (h * 31 + 33131) ^ (h >>> 3);
acc = (acc * 34621L + h) & 0x7FFFFFFFL;
h = (h * 31 + 754) ^ (h >>> 4);
acc = (acc * 33131L + h) & 0x7FFFFFFFL;
h = (h * 31 + 41756) ^ (h >>> 5);
acc = (acc * 755L + h) & 0x7FFFFFFFL;
h = (h * 31 + 34620) ^ (h >>> 6);
acc = (acc * 41757L + h) & 0x7FFFFFFFL;
h = (h * 31 + 33131) ^ (h >>> 7);
acc = (acc * 34621L + h) & 0x7FFFFFFFL;
h = (h * 31 + 754) ^ (h >>> 3);
acc = (acc * 33131L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 754;
    }

    /** 内部校验片段 #2（结果参与 key 链，本身无副作用） */
    private static int f2() {
        int h = 17247;
long acc = 935668L;
h = (h * 31 + 49953) ^ (h >>> 3);
acc = (acc * 9869L + h) & 0x7FFFFFFFL;
h = (h * 31 + 46016) ^ (h >>> 4);
acc = (acc * 49953L + h) & 0x7FFFFFFFL;
h = (h * 31 + 17247) ^ (h >>> 5);
acc = (acc * 46017L + h) & 0x7FFFFFFFL;
h = (h * 31 + 9869) ^ (h >>> 6);
acc = (acc * 17247L + h) & 0x7FFFFFFFL;
h = (h * 31 + 49953) ^ (h >>> 7);
acc = (acc * 9869L + h) & 0x7FFFFFFFL;
h = (h * 31 + 46016) ^ (h >>> 3);
acc = (acc * 49953L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 46016;
    }

    /** 内部校验片段 #3（结果参与 key 链，本身无副作用） */
    private static int f3() {
        int h = 5097;
long acc = 550612L;
h = (h * 31 + 41802) ^ (h >>> 3);
acc = (acc * 14799L + h) & 0x7FFFFFFFL;
h = (h * 31 + 20707) ^ (h >>> 4);
acc = (acc * 41803L + h) & 0x7FFFFFFFL;
h = (h * 31 + 5097) ^ (h >>> 5);
acc = (acc * 20707L + h) & 0x7FFFFFFFL;
h = (h * 31 + 14798) ^ (h >>> 6);
acc = (acc * 5097L + h) & 0x7FFFFFFFL;
h = (h * 31 + 41802) ^ (h >>> 7);
acc = (acc * 14799L + h) & 0x7FFFFFFFL;
h = (h * 31 + 20707) ^ (h >>> 3);
acc = (acc * 41803L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 20707;
    }

    /** 内部校验片段 #4（结果参与 key 链，本身无副作用） */
    private static int f4() {
        int h = 14774;
long acc = 285249L;
h = (h * 31 + 11403) ^ (h >>> 3);
acc = (acc * 61117L + h) & 0x7FFFFFFFL;
h = (h * 31 + 42799) ^ (h >>> 4);
acc = (acc * 11403L + h) & 0x7FFFFFFFL;
h = (h * 31 + 14774) ^ (h >>> 5);
acc = (acc * 42799L + h) & 0x7FFFFFFFL;
h = (h * 31 + 61116) ^ (h >>> 6);
acc = (acc * 14775L + h) & 0x7FFFFFFFL;
h = (h * 31 + 11403) ^ (h >>> 7);
acc = (acc * 61117L + h) & 0x7FFFFFFFL;
h = (h * 31 + 42799) ^ (h >>> 3);
acc = (acc * 11403L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 42799;
    }

    /** 内部校验片段 #5（结果参与 key 链，本身无副作用） */
    private static int f5() {
        int h = 53720;
long acc = 3011L;
h = (h * 31 + 33971) ^ (h >>> 3);
acc = (acc * 20491L + h) & 0x7FFFFFFFL;
h = (h * 31 + 25958) ^ (h >>> 4);
acc = (acc * 33971L + h) & 0x7FFFFFFFL;
h = (h * 31 + 53720) ^ (h >>> 5);
acc = (acc * 25959L + h) & 0x7FFFFFFFL;
h = (h * 31 + 20491) ^ (h >>> 6);
acc = (acc * 53721L + h) & 0x7FFFFFFFL;
h = (h * 31 + 33971) ^ (h >>> 7);
acc = (acc * 20491L + h) & 0x7FFFFFFFL;
h = (h * 31 + 25958) ^ (h >>> 3);
acc = (acc * 33971L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 25958;
    }

    /** 内部校验片段 #6（结果参与 key 链，本身无副作用） */
    private static int f6() {
        int h = 32459;
long acc = 236686L;
h = (h * 31 + 25331) ^ (h >>> 3);
acc = (acc * 28867L + h) & 0x7FFFFFFFL;
h = (h * 31 + 49478) ^ (h >>> 4);
acc = (acc * 25331L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32459) ^ (h >>> 5);
acc = (acc * 49479L + h) & 0x7FFFFFFFL;
h = (h * 31 + 28867) ^ (h >>> 6);
acc = (acc * 32459L + h) & 0x7FFFFFFFL;
h = (h * 31 + 25331) ^ (h >>> 7);
acc = (acc * 28867L + h) & 0x7FFFFFFFL;
h = (h * 31 + 49478) ^ (h >>> 3);
acc = (acc * 25331L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 49478;
    }

    /** 内部校验片段 #7（结果参与 key 链，本身无副作用） */
    private static int f7() {
        int h = 25540;
long acc = 186245L;
h = (h * 31 + 47211) ^ (h >>> 3);
acc = (acc * 28009L + h) & 0x7FFFFFFFL;
h = (h * 31 + 4406) ^ (h >>> 4);
acc = (acc * 47211L + h) & 0x7FFFFFFFL;
h = (h * 31 + 25540) ^ (h >>> 5);
acc = (acc * 4407L + h) & 0x7FFFFFFFL;
h = (h * 31 + 28009) ^ (h >>> 6);
acc = (acc * 25541L + h) & 0x7FFFFFFFL;
h = (h * 31 + 47211) ^ (h >>> 7);
acc = (acc * 28009L + h) & 0x7FFFFFFFL;
h = (h * 31 + 4406) ^ (h >>> 3);
acc = (acc * 47211L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 4406;
    }

    /** 内部校验片段 #8（结果参与 key 链，本身无副作用） */
    private static int f8() {
        int h = 35208;
long acc = 803878L;
h = (h * 31 + 58098) ^ (h >>> 3);
acc = (acc * 56199L + h) & 0x7FFFFFFFL;
h = (h * 31 + 40094) ^ (h >>> 4);
acc = (acc * 58099L + h) & 0x7FFFFFFFL;
h = (h * 31 + 35208) ^ (h >>> 5);
acc = (acc * 40095L + h) & 0x7FFFFFFFL;
h = (h * 31 + 56199) ^ (h >>> 6);
acc = (acc * 35209L + h) & 0x7FFFFFFFL;
h = (h * 31 + 58098) ^ (h >>> 7);
acc = (acc * 56199L + h) & 0x7FFFFFFFL;
h = (h * 31 + 40094) ^ (h >>> 3);
acc = (acc * 58099L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 40094;
    }

    /** 内部校验片段 #9（结果参与 key 链，本身无副作用） */
    private static int f9() {
        int h = 52873;
long acc = 476174L;
h = (h * 31 + 37103) ^ (h >>> 3);
acc = (acc * 49237L + h) & 0x7FFFFFFFL;
h = (h * 31 + 60100) ^ (h >>> 4);
acc = (acc * 37103L + h) & 0x7FFFFFFFL;
h = (h * 31 + 52873) ^ (h >>> 5);
acc = (acc * 60101L + h) & 0x7FFFFFFFL;
h = (h * 31 + 49237) ^ (h >>> 6);
acc = (acc * 52873L + h) & 0x7FFFFFFFL;
h = (h * 31 + 37103) ^ (h >>> 7);
acc = (acc * 49237L + h) & 0x7FFFFFFFL;
h = (h * 31 + 60100) ^ (h >>> 3);
acc = (acc * 37103L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 60100;
    }

    /** 内部校验片段 #10（结果参与 key 链，本身无副作用） */
    private static int f10() {
        int h = 7528;
long acc = 246290L;
h = (h * 31 + 3813) ^ (h >>> 3);
acc = (acc * 54705L + h) & 0x7FFFFFFFL;
h = (h * 31 + 26657) ^ (h >>> 4);
acc = (acc * 3813L + h) & 0x7FFFFFFFL;
h = (h * 31 + 7528) ^ (h >>> 5);
acc = (acc * 26657L + h) & 0x7FFFFFFFL;
h = (h * 31 + 54704) ^ (h >>> 6);
acc = (acc * 7529L + h) & 0x7FFFFFFFL;
h = (h * 31 + 3813) ^ (h >>> 7);
acc = (acc * 54705L + h) & 0x7FFFFFFFL;
h = (h * 31 + 26657) ^ (h >>> 3);
acc = (acc * 3813L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 26657;
    }

    /** 内部校验片段 #11（结果参与 key 链，本身无副作用） */
    private static int f11() {
        int h = 31032;
long acc = 551529L;
h = (h * 31 + 11340) ^ (h >>> 3);
acc = (acc * 22367L + h) & 0x7FFFFFFFL;
h = (h * 31 + 27138) ^ (h >>> 4);
acc = (acc * 11341L + h) & 0x7FFFFFFFL;
h = (h * 31 + 31032) ^ (h >>> 5);
acc = (acc * 27139L + h) & 0x7FFFFFFFL;
h = (h * 31 + 22367) ^ (h >>> 6);
acc = (acc * 31033L + h) & 0x7FFFFFFFL;
h = (h * 31 + 11340) ^ (h >>> 7);
acc = (acc * 22367L + h) & 0x7FFFFFFFL;
h = (h * 31 + 27138) ^ (h >>> 3);
acc = (acc * 11341L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 27138;
    }

    /** 内部校验片段 #12（结果参与 key 链，本身无副作用） */
    private static int f12() {
        int h = 56759;
long acc = 853799L;
h = (h * 31 + 32323) ^ (h >>> 3);
acc = (acc * 61489L + h) & 0x7FFFFFFFL;
h = (h * 31 + 42096) ^ (h >>> 4);
acc = (acc * 32323L + h) & 0x7FFFFFFFL;
h = (h * 31 + 56759) ^ (h >>> 5);
acc = (acc * 42097L + h) & 0x7FFFFFFFL;
h = (h * 31 + 61489) ^ (h >>> 6);
acc = (acc * 56759L + h) & 0x7FFFFFFFL;
h = (h * 31 + 32323) ^ (h >>> 7);
acc = (acc * 61489L + h) & 0x7FFFFFFFL;
h = (h * 31 + 42096) ^ (h >>> 3);
acc = (acc * 32323L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 42096;
    }

    /** 内部校验片段 #13（结果参与 key 链，本身无副作用） */
    private static int f13() {
        int h = 693;
long acc = 941676L;
h = (h * 31 + 8766) ^ (h >>> 3);
acc = (acc * 20201L + h) & 0x7FFFFFFFL;
h = (h * 31 + 16591) ^ (h >>> 4);
acc = (acc * 8767L + h) & 0x7FFFFFFFL;
h = (h * 31 + 693) ^ (h >>> 5);
acc = (acc * 16591L + h) & 0x7FFFFFFFL;
h = (h * 31 + 20201) ^ (h >>> 6);
acc = (acc * 693L + h) & 0x7FFFFFFFL;
h = (h * 31 + 8766) ^ (h >>> 7);
acc = (acc * 20201L + h) & 0x7FFFFFFFL;
h = (h * 31 + 16591) ^ (h >>> 3);
acc = (acc * 8767L + h) & 0x7FFFFFFFL;
String s = Integer.toHexString(h) + Long.toHexString(acc);
int crc = 0;
for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;
return crc ^ 16591;
    }

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
        acc = (acc * 7 + f12()) & 0x7FFFFFFF;
        acc = (acc * 7 + f13()) & 0x7FFFFFFF;
        return (acc ^ 21297) & 0x7FFFFFFF;
    }
}
