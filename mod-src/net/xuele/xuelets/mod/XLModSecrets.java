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
 * 本地密钥（**仓库里是占位值**）。
 *
 * <p>V4.3p 起，仓库里的 <code>license.json</code> 只有密文：AES-256-CBC 的密钥由这里的
 * {@link #LICENSE_KEY_HEX} 派生（HMAC-SHA256(主密钥, salt+标签)）。作者的仓库放的是
 * <b>占位密钥</b>，所以拿到密文也解不开（看不到功能开关，也看不到管理员密码）。</p>
 *
 * <p>自己部署时：<code>python sign_config.py init-key</code> 会生成一份随机主密钥写进本文件
 * （并写入 <code>keys/license_key.txt</code>，该文件不提交），然后
 * <code>python sign_config.py set-pw 你的管理员密码</code> +
 * <code>python sign_config.py seal features.json</code> 签发你自己的加密授权。</p>
 */
public final class XLModSecrets {

    /** 32 字节主密钥（hex，64 字符）。**占位值**：请用 init-key 生成你自己的。 */
    public static final String LICENSE_KEY_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private XLModSecrets() {
    }
}