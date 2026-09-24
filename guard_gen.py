# XLMod — 学乐云客户端增强模块
# Copyright (C) 2026 wg-1337
# SPDX-License-Identifier: AGPL-3.0-or-later
# 本文件按 GNU Affero 通用公共许可证第 3 版（或更高版本）发布，详见仓库根目录 LICENSE。

# -*- coding: utf-8 -*-
"""多 dex 互锁（防删）代码生成器。

思路：
  1) 真实代码（Mod 主逻辑）留在 classes7.dex；
  2) 生成两个"守卫"类，分别编译进 classes8.dex / classes9.dex；
  3) 字符串解密密钥 = 0x5A ^ G8.a() ^ G9.b()，其中：
       · G8.a() 先跑一条自校验链，再用反射读取 G9 的存在与取值，最后返回自己的分片；
       · G9.b() 同样用反射读取 G8，返回值依赖于 G8 的分片；
     → 两者互相引用、缺一不可：删掉 classes8 或 classes9 都会让密钥算错，
       于是 Mod 内所有加密字符串变成乱码（功能整体失效）。
  4) 每个守卫类里插入大量"环环相扣但结果无用"的计算（CRC/哈希/状态机），
     拖慢静态分析，同时保证运行结果确定（构建期与运行期一致）。

本脚本输出：
  guard-src/net/xuele/xuelets/mod/guard/Guard8.java
  guard-src/net/xuele/xuelets/mod/guard/Guard9.java
  guard-consts.json   （供 obf_strings.py 计算最终密钥）
"""
import io, json, os, random, textwrap

random.seed(0x584C4D)  # 固定种子 → 可复现构建

C8 = random.randint(0x1000, 0x7FFF)
C9 = random.randint(0x1000, 0x7FFF)
T8 = random.randint(0x1000, 0x7FFF)
T9 = random.randint(0x1000, 0x7FFF)
BASE = 0x5A
FINAL_KEY = BASE ^ C8 ^ C9

os.makedirs('guard-src/net/xuele/xuelets/mod/guard', exist_ok=True)


def filler_methods(cls, seed, n=12):
    """生成 n 个"有意义却无用"的方法：CRC32/位运算/状态机，结果互相串联但不影响外部语义"""
    out = []
    rnd = random.Random(seed)
    for i in range(n):
        consts = [rnd.randint(1, 0xFFFF) for _ in range(4)]
        body = []
        body.append('        int h = %d;' % consts[0])
        body.append('        long acc = %dL;' % (consts[1] * 2654435761 % 1000003))
        for j in range(6):
            body.append('        h = (h * 31 + %d) ^ (h >>> %d);' % (consts[(j + 2) % 4], 3 + (j % 5)))
            body.append('        acc = (acc * %dL + h) & 0x7FFFFFFFL;' % (consts[(j + 1) % 4] | 1))
        body.append('        String s = Integer.toHexString(h) + Long.toHexString(acc);')
        body.append('        int crc = 0;')
        body.append('        for (int k = 0; k < s.length(); k++) crc = (crc * 131 + s.charAt(k)) & 0xFFFF;')
        body.append('        return crc ^ %d;' % consts[3])
        out.append(textwrap.dedent('''
            /** 内部校验片段 #%d（结果参与 key 链，本身无副作用） */
            private static int f%d() {
        %s
            }
        ''' % (i, i, '\n'.join(body))).strip('\n'))
    return '\n\n'.join(out)


def chain_calls(n=12):
    """把所有 filler 串成一条链：后者依赖前者的返回值（环环相扣）"""
    lines = []
    lines.append('        int acc = 0;')
    for i in range(n):
        lines.append('        acc = (acc * 7 + f%d()) & 0x7FFFFFFF;' % i)
    return '\n'.join(lines)


GUARD8 = u'''/*
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
    public static int tag() { return __T8__; }

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

__FILLERS__

    /** 把 filler 串成链（互相依赖，删除任一方法都会让分片改变） */
    private static int chain() {
__CHAIN__
        return (acc ^ __C8__) & 0x7FFFFFFF;
    }
}
'''
GUARD8 = (GUARD8.replace('__FILLERS__', filler_methods('G8', 0x1111))
                .replace('__CHAIN__', chain_calls(12))
                .replace('__C8__', str(C8))
                .replace('__T8__', str(T8)))

GUARD9 = u'''/*
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
    public static int tag() { return __T9__; }

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

__FILLERS__

    private static int chain() {
__CHAIN__
        return (acc ^ __C9__) & 0x7FFFFFFF;
    }
}
'''
GUARD9 = (GUARD9.replace('__FILLERS__', filler_methods('G9', 0x2222, 14))
                .replace('__CHAIN__', chain_calls(14))
                .replace('__C9__', str(C9))
                .replace('__T9__', str(T9)))

io.open('guard-src/net/xuele/xuelets/mod/guard/Guard8.java', 'w', encoding='utf-8').write(GUARD8)
io.open('guard-src/net/xuele/xuelets/mod/guard/Guard9.java', 'w', encoding='utf-8').write(GUARD9)
# KeyProbe：构建期实跑一次守卫代码，取真实密钥（保证与端上完全一致）
os.makedirs('guard-src/probe', exist_ok=True)
io.open('guard-src/probe/KeyProbe.java', 'w', encoding='utf-8').write(
'''/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件由 guard_gen.py 生成，是 XLMod 的一部分；按 GNU AGPL-3.0（或更高版本）发布，详见 LICENSE。
 */

public final class KeyProbe {
    public static void main(String[] args) {
        int k = 0x5A ^ Guard8Shim.a() ^ Guard9Shim.b();
        System.out.println(k & 0xFF);
    }
}
'''.replace('Guard8Shim', 'net.xuele.xuelets.mod.guard.Guard8')
   .replace('Guard9Shim', 'net.xuele.xuelets.mod.guard.Guard9'))

io.open('guard-consts.json', 'w', encoding='utf-8').write(json.dumps(
    {'c8': C8, 'c9': C9, 't8': T8, 't9': T9, 'base': BASE,
     'final_key_hint': FINAL_KEY}, indent=2))
print('guard generated: C8=0x%X C9=0x%X T8=0x%X T9=0x%X (hint key=0x%X)' % (C8, C9, T8, T9, FINAL_KEY))
