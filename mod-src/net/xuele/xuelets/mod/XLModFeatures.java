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
 * <p>仓库根目录需放 <code>features.json</code> 与 <code>features.json.sig</code>（Base64 签名）。
 * 签发：<code>python sign_config.py sign features.json</code>（私钥 <code>keys/…_private.pem</code>，不提交）。</p>
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

    /** 全部功能区 ID（与面板分组一一对应） */
    public static final String[] IDS = {
            "identity", "teacher_tools", "cloud_keep", "notify_recall", "homework",
            "auto_sign", "auto_challenge", "cloud_flower", "rank", "answer",
            "privacy", "logs"
    };

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
    private static Listener sListener = null;
    private static Handler sHandler = null;
    private static boolean sWatching = false;

    private XLModFeatures() {
    }

    /** 地址固定（不允许修改） */
    public static String url() {
        return DEFAULT_URL;
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
        JSONObject c = sCfg;
        if (c == null) {
            return "授权状态：未获取或未通过签名校验 → 已锁定（仅保留隐藏类功能）"
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
        return "授权状态：" + (c.optBoolean("kill", false) ? "已熔断（全部停用）"
                : (expired() ? "已过期（锁定）" : "正常"))
                + " · 已开启 " + on + " 项 · " + sec + " 秒前校验通过" + exp
                + "\n已验签（ECDSA P-256，作者私钥签发）";
    }

    /** 拉取 + 验签（后台线程）；force=false 时 60 秒内不重复请求 */
    public static void refreshAsync(final Context ctx, final boolean force) {
        if (sFetching) return;
        if (!force && System.currentTimeMillis() - sLastTryAt < REFRESH_MS) return;
        sFetching = true;
        sLastTryAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] cfg = httpGet(url());
                    byte[] sigB64 = httpGet(url() + ".sig");
                    if (cfg == null || sigB64 == null) {
                        sErr = "配置或签名下载失败";
                        XLModConfig.logAppend("[功能开关] " + sErr + "（保持锁定）");
                        return;
                    }
                    byte[] sig = android.util.Base64.decode(new String(sigB64, "UTF-8").trim(),
                            android.util.Base64.DEFAULT);
                    if (!verify(cfg, sig)) {
                        sErr = "签名校验失败";
                        XLModConfig.logAppend("[功能开关] 配置签名校验失败（保持锁定）");
                        return;
                    }
                    String json = new String(cfg, "UTF-8").trim();
                    JSONObject o = new JSONObject(json);
                    boolean changed = !json.equals(sRaw);
                    sCfg = o;
                    sRaw = json;
                    sExpires = o.optLong("expires", 0L);
                    sErr = expired() ? "配置已过期" : "";
                    if (changed) sLoadedAt = System.currentTimeMillis();
                    XLModConfig.logAppend("[功能开关] 配置验签通过" + (changed ? "（有变化）" : "（无变化）")
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
