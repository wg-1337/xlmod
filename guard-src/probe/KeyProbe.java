/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件由 guard_gen.py 生成，是 XLMod 的一部分；按 GNU AGPL-3.0（或更高版本）发布，详见 LICENSE。
 */

public final class KeyProbe {
    public static void main(String[] args) {
        int k = 0x5A ^ net.xuele.xuelets.mod.guard.Guard8.a() ^ net.xuele.xuelets.mod.guard.Guard9.b();
        System.out.println(k & 0xFF);
    }
}
