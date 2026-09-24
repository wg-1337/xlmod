/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件是 XLMod 的一部分：你可以按 GNU Affero 通用公共许可证第 3 版（或更高版本）条款
 * 使用、修改与再分发；通过网络提供服务时须向使用者提供对应源码。详见仓库根目录 LICENSE。
 */

package net.xuele.xuelets.mod;

/**
 * B 级混淆辅助：字符串解密（XOR + Base64）。
 *
 * <p>密钥不再是固定 0x5A，而是由两个"守卫"类在运行期共同推导：
 * {@code key = 0x5A ^ Guard8.a() ^ Guard9.b()}。Guard8 / Guard9 分别编译进
 * classes8.dex / classes9.dex，且互相用反射校验对方的 {@code tag()}。
 * 因此<b>删掉任意一个 dex 都会让密钥算错</b>，本 Mod 内所有加密字符串随即变成乱码，
 * 功能整体失效 —— 这就是"删哪个都不行"的防删设计。</p>
 *
 * <p>本类不参与字符串加密（obf_strings.py 不处理它），R8 会把它与所有调用点一起重命名。</p>
 */
public class Obf {

    private static int sKey = -1;

    /** 运行期密钥链（结果缓存；守卫缺失时刻意返回错误值 0） */
    private static int key() {
        if (sKey >= 0) return sKey;
        int k;
        try {
            Class<?> g8 = Class.forName("net.xuele.xuelets.mod.guard.Guard8");
            Class<?> g9 = Class.forName("net.xuele.xuelets.mod.guard.Guard9");
            int a = ((Integer) g8.getMethod("a").invoke(null)).intValue();
            int b = ((Integer) g9.getMethod("b").invoke(null)).intValue();
            int t8 = ((Integer) g8.getMethod("tag").invoke(null)).intValue();
            int t9 = ((Integer) g9.getMethod("tag").invoke(null)).intValue();
            k = 0x5A ^ a ^ b;
            if ((t8 ^ t9) == 0) k = 0;      // 指纹异常 → 视为被篡改
        } catch (Throwable t) {
            k = 0;                          // 守卫 dex 缺失 → 密钥错误（防删）
        }
        sKey = k & 0xFF;
        try {
            XLModConfig.logAppend("[守卫] 字符串密钥链已建立 key=0x" + Integer.toHexString(sKey));
        } catch (Throwable ignored) {
        }
        return sKey;
    }

    /** 运行时解密：obf_strings.py 生成的 b64 常量在这里还原为原文 */
    public static String de(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            byte[] d = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            int k = key();
            for (int i = 0; i < d.length; i++) {
                d[i] = (byte) (d[i] ^ k);
            }
            return new String(d, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
