/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件是 XLMod 的一部分：你可以按 GNU Affero 通用公共许可证第 3 版（或更高版本）条款
 * 使用、修改与再分发；通过网络提供服务时须向使用者提供对应源码。详见仓库根目录 LICENSE。
 */

package net.xuele.xuelets.mod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * 远程授权（限制 + 公告 + 签名校验）——XLMod 开源部分。
 *
 * <p><b>代码公开后"保密"没有意义；真正能限制用户的是"只有作者能签发配置"。</b>
 * 因此配置必须带 <b>ECDSA P-256（SHA256withECDSA）</b> 签名：App 只内置公钥，
 * 验签失败一律按锁定处理，且地址写死、不允许用户改。</p>
 *
 * <ul>
 *   <li><b>拉不到 / 验签失败 / 已过期</b> → 锁死：除 {@link #FAIL_CLOSED_ALLOW} 外全部禁用，不读任何本地缓存；</li>
 *   <li><b>公告</b>：{@code notice} 每次打开面板都弹；</li>
 *   <li><b>实时生效</b>：前台每 60 秒校验一次，内容变化立即应用并通知面板重建；</li>
 *   <li><b>熔断</b>：{@code "kill": true} 全停；<b>有效期</b>：{@code "expires"}（epoch 秒，0/缺省=不过期）。</li>
 * </ul>
 *
 * <p>仓库根目录需放 <code>license.json</code>（V4.3p 起只有密文；<b>不再放明文 features.json</b>）。
 * 签发：<code>python license_tool.py seal</code>（AES-256-CBC 加密 + ECDSA 签名；私钥 <code>keys/…_private.pem</code>，不提交）。</p>
 *
 * <p><b>V4.3p 加密授权</b>：{@code {"v":2,"salt":"…","enc":"…","sig":"…"}}，
 * {@code enc}=AES-256-CBC(salt‖iv‖密文)，密钥由本地主密钥派生（{@link XLModSecrets}，公开仓库里是占位密钥 →
 * 别人拿到密文也解不开）；明文里除功能开关外还有<b>管理员密码校验块</b>（PBKDF2 盐/迭代数/哈希），
 * 面板输入正确密码即"管理员解锁"（全部功能放行，优先级高于 features/kill/过期）。</p>
 */
public final class XLModFeatures {

    /** 配置地址（写死，禁止用户自建配置自解锁） */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/wg-1337/xlmod/main/features.json";

    /** 内置公钥（ECDSA P-256，X.509 SubjectPublicKeyInfo DER 的 Base64） */
    private static final String PUB_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOqImyoOv1mNhFKWRGfWg0cTFoaGc"
                    + "jN340j4bn/jHT92UanJCcJHQ30gMvpK/YPmhSOF4IjOD2cJd8eFQCKjDwQ==";

    /** 拉不到/验签不过时仍可用的"隐藏类功能"（其余全部锁死） */
    public static final String[] FAIL_CLOSED_ALLOW = {"privacy", "logs"};

    /** 加密授权的签名前缀（签名覆盖 前缀+salt+enc，避免换 salt 重放） */
    private static final String SIG_PREFIX = "XLModLic-v2|";
    // 注：privacy 同时属于 ALWAYS_ON —— 即"配置拿不到"和"配置明确关掉它"都不影响隐私功能

    /** 全部功能区 ID（与面板分组一一对应） */
    public static final String[] IDS = {
            "identity", "teacher_tools", "cloud_keep", "notify_recall", "homework",
            "auto_sign", "auto_challenge", "cloud_flower", "rank", "answer",
            "logs"
    };

    /** 不受云端配置影响的本地功能区（隐私隐藏：属于用户自身权益，任何情况都必须可用） */
    public static final String[] ALWAYS_ON = {"privacy"};

    private static final long REFRESH_MS = 60L * 1000L;

    /** 配置变更回调（面板据此重建 UI） */
    public interface Listener {
        void onConfigChanged();
    }

    private static JSONObject sCfg = null;      // 仅内存保存（不落盘：拿不到就是锁死）
    private static String sRaw = "";
    private static long sLoadedAt = 0L;
    private static long sLastTryAt = 0L;
    private static long sExpires = 0L;
    private static String sErr = "";
    private static boolean sFetching = false;
    private static int sLicenseFormat = 0;      // 0=未取到 1=明文(payload) 2=加密(enc)
    private static Listener sListener = null;
    private static Handler sHandler = null;
    private static boolean sWatching = false;

    private XLModFeatures() {
    }

    /** 地址固定（不允许修改） */
    public static String url() {
        return DEFAULT_URL;
    }

    /** 单文件授权地址：把 DEFAULT_URL 末尾的 features.json 换成 license.json */
    public static String bundleUrl() {
        final String tail = "features.json";
        return DEFAULT_URL.endsWith(tail)
                ? DEFAULT_URL.substring(0, DEFAULT_URL.length() - tail.length()) + "license.json"
                : DEFAULT_URL + ".bundle";
    }

    public static void setListener(Listener l) {
        sListener = l;
    }

    public static boolean loaded() {
        return sCfg != null;
    }

    public static boolean killed() {
        JSONObject c = sCfg;
        return c != null && c.optBoolean("kill", false);
    }

    /** 授权形态：0=未取到 1=明文签名 2=加密签名（V4.3p） */
    public static int licenseFormat() {
        return sLicenseFormat;
    }

    /** 公告内容（每次打开面板都显示） */
    public static String notice() {
        JSONObject c = sCfg;
        return c == null ? "" : c.optString("notice", "");
    }

    /**
     * 功能区是否启用（限制语义）：
     * 没配置 / 验签失败 / 已过期 → 只放行 {@link #FAIL_CLOSED_ALLOW}；
     * 其它情况按 features 表，未列出的用 default（缺省 false）。
     */
    public static boolean enabled(String id) {
        try {
            for (String a : ALWAYS_ON) {
                if (a.equals(id)) return true;      // 隐私隐藏不看云端：缺失/熔断/过期都照常可用
            }
            if (XLModConfig.isAdminUnlocked()) return true;   // 管理员密码解锁：全部功能放行
            JSONObject c = sCfg;
            if (c == null) return isFailClosedAllowed(id);
            if (expired()) return isFailClosedAllowed(id);
            if (c.optBoolean("kill", false)) return false;
            JSONObject f = c.optJSONObject("features");
            if (f != null && f.has(id)) return f.optBoolean(id, false);
            return c.optBoolean("default", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean expired() {
        return sExpires > 0 && System.currentTimeMillis() / 1000L > sExpires;
    }

    private static boolean isFailClosedAllowed(String id) {
        for (String s : FAIL_CLOSED_ALLOW) {
            if (s.equals(id)) return true;
        }
        return false;
    }

    /** 面板/日志状态文本 */
    public static String statusText() {
        String admin = XLModConfig.isAdminUnlocked()
                ? "管理员已解锁（全部功能放行，本地覆盖云端开关）\n" : "";
        JSONObject c = sCfg;
        if (c == null) {
            return admin + "授权状态：未获取或未通过校验 → 已锁定（仅保留隐藏类功能）"
                    + (sErr.isEmpty() ? "" : "\n原因：" + sErr);
        }
        JSONObject f = c.optJSONObject("features");
        int on = 0;
        if (f != null) {
            java.util.Iterator<String> it = f.keys();
            while (it.hasNext()) {
                if (f.optBoolean(it.next(), false)) on++;
            }
        }
        long sec = (System.currentTimeMillis() - sLoadedAt) / 1000L;
        String exp = sExpires > 0 ? (" · 有效期至 " + sExpires + "（epoch 秒）") : " · 长期有效";
        String fmt = sLicenseFormat == 2 ? "加密授权（AES-256-CBC + ECDSA P-256，仓库里只有密文）"
                : "明文授权（payload + ECDSA P-256，建议改用加密授权）";
        return admin + "授权状态：" + (c.optBoolean("kill", false) ? "已熔断（全部停用）"
                : (expired() ? "已过期（锁定）" : "正常"))
                + " · 已开启 " + on + " 项 · " + sec + " 秒前校验通过" + exp
                + "\n" + fmt
                + (XLModConfig.hasAdminVerifier() ? " · 管理员密码校验块已就绪" : " · 授权里没有管理员密码块");
    }

    /** 拉取 + 验签（+ 解密）（后台线程）；force=false 时 60 秒内不重复请求 */
    public static void refreshAsync(final Context ctx, final boolean force) {
        if (sFetching) return;
        if (!force && System.currentTimeMillis() - sLastTryAt < REFRESH_MS) return;
        sFetching = true;
        sLastTryAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 优先用"单文件" license.json（配置+签名在同一文件里）：
                    // 两个文件各自走 CDN 缓存时会出现"新签名配旧配置"的不同步，导致误锁；
                    // 单文件只有一份缓存，从根上避免该问题。
                    // V4.3p：单文件里是**密文**（enc）；旧版明文（payload）仍兼容；
                    // 再不行才回退到 features.json + features.json.sig（仓库已不再放，留作兼容）。
                    byte[] cfg = null;
                    int format = 0;
                    byte[] bundle = httpGet(bundleUrl());
                    if (bundle != null) {
                        JSONObject b = new JSONObject(new String(bundle, "UTF-8").trim());
                        String enc = b.optString("enc", "");
                        if (!enc.isEmpty() && b.has("salt")) {
                            String salt = b.optString("salt", "");
                            byte[] sig = android.util.Base64.decode(b.getString("sig"),
                                    android.util.Base64.DEFAULT);
                            // 签名覆盖 前缀+salt+enc（换 salt/换密文都会验签失败）
                            byte[] signed = (SIG_PREFIX + salt + "|" + enc).getBytes("UTF-8");
                            if (!verify(signed, sig)) {
                                sErr = "加密授权签名校验失败";
                                XLModConfig.logAppend("[功能开关] " + sErr + "（保持锁定）");
                                return;
                            }
                            cfg = decryptLicense(salt, enc);
                            if (cfg == null) {
                                sErr = "加密授权解密失败（主密钥不匹配）";
                                XLModConfig.logAppend("[功能开关] " + sErr + "（保持锁定）");
                                return;
                            }
                            format = 2;
                        } else if (b.has("payload")) {
                            cfg = android.util.Base64.decode(b.getString("payload"),
                                    android.util.Base64.DEFAULT);
                            byte[] sig = android.util.Base64.decode(b.getString("sig"),
                                    android.util.Base64.DEFAULT);
                            if (!verify(cfg, sig)) {
                                sErr = "签名校验失败";
                                XLModConfig.logAppend("[功能开关] 配置签名校验失败（保持锁定）");
                                return;
                            }
                            format = 1;
                        }
                    }
                    if (cfg == null) {
                        byte[] c = httpGet(url());
                        byte[] sigB64 = httpGet(url() + ".sig");
                        if (c != null && sigB64 != null) {
                            byte[] sig = android.util.Base64.decode(new String(sigB64, "UTF-8").trim(),
                                    android.util.Base64.DEFAULT);
                            if (!verify(c, sig)) {
                                sErr = "签名校验失败（回退 features.json）";
                                XLModConfig.logAppend("[功能开关] " + sErr + "（保持锁定）");
                                return;
                            }
                            cfg = c;
                            format = 1;
                        }
                    }
                    if (cfg == null) {
                        sErr = "授权或签名下载失败";
                        XLModConfig.logAppend("[功能开关] " + sErr + "（保持锁定）");
                        return;
                    }
                    String json = new String(cfg, "UTF-8").trim();
                    JSONObject o = new JSONObject(json);
                    boolean changed = !json.equals(sRaw) || format != sLicenseFormat;
                    sCfg = o;
                    sRaw = json;
                    sLicenseFormat = format;
                    sExpires = o.optLong("expires", 0L);
                    sErr = expired() ? "配置已过期" : "";
                    if (changed) sLoadedAt = System.currentTimeMillis();
                    // 管理员密码校验块（随授权一起加密下发）→ 本地缓存，断网也能验证管理员密码
                    try {
                        JSONObject adm = o.optJSONObject("admin");
                        if (adm != null && adm.optString("hash", "").length() > 0) {
                            XLModConfig.setAdminVerifier(adm.optString("salt", ""),
                                    adm.optInt("iters", 20000), adm.optString("hash", ""));
                        }
                    } catch (Throwable ignored) {
                    }
                    XLModConfig.logAppend("[功能开关] 授权校验通过" + (changed ? "（有变化）" : "（无变化）")
                            + "：" + statusText().replace('\n', ' '));
                    if (changed) notifyChanged();
                } catch (Throwable t) {
                    sErr = String.valueOf(t);
                    XLModConfig.logAppend("[功能开关] 配置处理异常: " + t + "（保持锁定）");
                } finally {
                    sFetching = false;
                }
            }
        }).start();
    }

    // ================= 加密授权（V4.3p） =================

    /**
     * 解密 license 的 enc 段：base64(salt 单独在 JSON 里, enc = iv‖密文)。
     * 密钥 = HMAC-SHA256(本地主密钥, salt+标签)（见 {@link XLModCrypto#licenseKey(byte[])}）。
     */
    private static byte[] decryptLicense(String saltB64, String encB64) {
        try {
            byte[] salt = android.util.Base64.decode(saltB64, android.util.Base64.DEFAULT);
            byte[] blob = android.util.Base64.decode(encB64, android.util.Base64.DEFAULT);
            if (salt.length < 8 || blob.length <= 16) return null;
            byte[] iv = new byte[16];
            byte[] ct = new byte[blob.length - 16];
            System.arraycopy(blob, 0, iv, 0, 16);
            System.arraycopy(blob, 16, ct, 0, ct.length);
            byte[] key = XLModCrypto.licenseKey(salt);
            if (key.length == 0) return null;
            return XLModCrypto.aesCbcDecrypt(key, iv, ct);
        } catch (Throwable t) {
            XLModConfig.logAppend("[功能开关] 授权解密异常: " + t);
            return null;
        }
    }

    // ================= 管理员密码 =================

    /** 授权里是否带有管理员密码校验块（没拿到授权时无法验证管理员密码） */
    public static boolean adminVerifierReady() {
        return XLModConfig.hasAdminVerifier();
    }

    /**
     * 校验管理员密码：对（授权里加密下发的）PBKDF2 校验块做比对。
     *
     * @return 空字符串 = 通过；否则为失败原因（直接显示给用户）
     */
    public static String verifyAdminPassword(String pw) {
        try {
            if (pw == null || pw.trim().isEmpty()) return "请输入管理员密码";
            String saltB64 = XLModConfig.getAdminSalt();
            String hashB64 = XLModConfig.getAdminHash();
            int iters = XLModConfig.getAdminIters();
            if (saltB64.isEmpty() || hashB64.isEmpty()) {
                return "本机还没有拿到带管理员密码块的授权：请先联网点「立即校验授权（拉取签名配置）」，再输入密码";
            }
            byte[] salt = android.util.Base64.decode(saltB64, android.util.Base64.DEFAULT);
            byte[] want = android.util.Base64.decode(hashB64, android.util.Base64.DEFAULT);
            byte[] got = XLModCrypto.pbkdf2(pw.trim().getBytes("UTF-8"), salt, iters, want.length);
            if (got.length == 0 || !XLModCrypto.sameBytes(want, got)) {
                XLModConfig.logAppend("[管理员] 管理员密码校验失败（输入不匹配）");
                return "管理员密码不正确";
            }
            XLModConfig.setAdminUnlocked(true);
            return "";
        } catch (Throwable t) {
            return "校验异常：" + t;
        }
    }

    /** 前台每分钟校验一次；有变化 → 通知面板重建（实时生效） */
    public static void startWatcher() {
        if (sWatching) return;
        sWatching = true;
        sHandler = new Handler(Looper.getMainLooper());
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (XLModHelper.isAppForeground()) refreshAsync(null, false);
                } catch (Throwable ignored) {
                }
                if (sHandler != null) sHandler.postDelayed(this, REFRESH_MS);
            }
        }, REFRESH_MS);
        XLModConfig.logAppend("[功能开关] 已启动前台每分钟校验（签名配置，变更实时生效）");
    }

    private static void notifyChanged() {
        final Listener l = sListener;
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    l.onConfigChanged();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ==================== 工具 ====================

    private static byte[] httpGet(String u) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "XLMod");
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** SHA256withECDSA 验签（公钥内置在代码里） */
    private static boolean verify(byte[] data, byte[] sig) {
        try {
            byte[] der = android.util.Base64.decode(PUB_B64, android.util.Base64.DEFAULT);
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(data);
            return s.verify(sig);
        } catch (Throwable t) {
            XLModConfig.logAppend("[功能开关] 验签异常: " + t);
            return false;
        }
    }
}
