/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件是 XLMod 的一部分：你可以按 GNU Affero 通用公共许可证第 3 版（或更高版本）条款
 * 使用、修改与再分发；通过网络提供服务时须向使用者提供对应源码。详见仓库根目录 LICENSE。
 */

package net.xuele.xuelets.mod;

import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 轻量密码学工具——只用 Android 自带 JCA，minSdk 19 可用。
 *
 * <p>用途：① 管理员密码校验（PBKDF2-HMAC-SHA256）；② 题库的内容指纹（MD5）。
 * 为什么不用 {@code SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")}：该算法在
 * Android 8.1(API 27) 之前不存在，而本模块 minApi=19。这里用 HmacSHA256 手写 PBKDF2，
 * 与 Python 侧 {@code hashlib.pbkdf2_hmac('sha256', ...)} 结果逐字节一致。</p>
 *
 * <p>本类不参与"业务字符串加密"以外的处理；所有字符串字面量同样会被 obf_strings.py 加密。</p>
 */
public final class XLModCrypto {

    private XLModCrypto() {
    }

    // ================= hex =================

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        String s = hex.trim();
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    // ================= 摘要 / HMAC =================

    public static String md5Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            return bytesToHex(d);
        } catch (Throwable t) {
            return "";
        }
    }

    /** PBKDF2-HMAC-SHA256（手写实现，与 Python hashlib.pbkdf2_hmac('sha256') 一致） */
    public static byte[] pbkdf2(byte[] password, byte[] salt, int iters, int dkLen) {
        try {
            if (iters < 1) iters = 1;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            int hLen = mac.getMacLength();
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] out = new byte[blocks * hLen];
            for (int i = 1; i <= blocks; i++) {
                byte[] ib = new byte[salt.length + 4];
                System.arraycopy(salt, 0, ib, 0, salt.length);
                ib[salt.length] = (byte) (i >>> 24);
                ib[salt.length + 1] = (byte) (i >>> 16);
                ib[salt.length + 2] = (byte) (i >>> 8);
                ib[salt.length + 3] = (byte) i;
                byte[] u = mac.doFinal(ib);
                byte[] t = new byte[u.length];
                System.arraycopy(u, 0, t, 0, u.length);
                for (int j = 1; j < iters; j++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < t.length; k++) t[k] = (byte) (t[k] ^ u[k]);
                }
                System.arraycopy(t, 0, out, (i - 1) * hLen, hLen);
            }
            byte[] dk = new byte[dkLen];
            System.arraycopy(out, 0, dk, 0, dkLen);
            return dk;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** 定长比较（避免时序差异），长度不同直接 false */
    public static boolean sameBytes(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }
}
