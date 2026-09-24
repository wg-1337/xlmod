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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 远程配置（授权 / 限制 + 公告）——XLMod 开源部分。
 *
 * <p><b>语义：配置是"限制手段"，不是"锦上添花"。</b></p>
 * <ul>
 *   <li><b>拉不到配置就锁死</b>：从未成功获取配置时，除"隐藏类功能"（{@link #FAIL_CLOSED_ALLOW}）外
 *       全部功能区禁用；<b>不读取任何本地配置文件</b>（没有离线缓存兜底，这是刻意的）。</li>
 *   <li><b>公告</b>：配置里的 {@code notice} 每次打开 Mod 面板都会弹一次。</li>
 *   <li><b>实时生效</b>：App 在前台时每 60 秒拉一次；配置内容有变化 → 立刻应用并通知面板重建 UI。</li>
 *   <li><b>熔断</b>：{@code "kill": true} → 全部功能禁用（含隐藏类）。</li>
 * </ul>
 *
 * <p>配置格式（仓库：<code>https://github.com/wg-1337/xlmod</code> 的 <code>features.json</code>）：</p>
 * <pre>
 * {
 *   "version": 1,
 *   "default": false,          // features 里没列出的功能区默认值（限制语义 → 建议 false）
 *   "kill": false,
 *   "notice": "公告内容：每次打开面板都会显示",
 *   "features": { "identity": true, "homework": true, ... }
 * }
 * </pre>
 */
public final class XLModFeatures {

    /** 配置地址（默认指向授权仓库；面板里可临时改） */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/wg-1337/xlmod/main/features.json";

    /** 拉不到配置时仍然可用的"隐藏类功能"（其余全部锁死） */
    public static final String[] FAIL_CLOSED_ALLOW = {"privacy", "logs"};

    private static final String KEY_URL = "feature_url";
    private static final long REFRESH_MS = 60L * 1000L;      // 前台每分钟刷新

    /** 全部功能区 ID（与面板分组一一对应） */
    public static final String[] IDS = {
            "identity", "teacher_tools", "cloud_keep", "notify_recall", "homework",
            "auto_sign", "auto_challenge", "cloud_flower", "rank", "answer",
            "privacy", "logs"
    };

    /** 配置变更回调（面板用它重建 UI，实现"实时更改"） */
    public interface Listener {
        void onConfigChanged();
    }

    private static JSONObject sCfg = null;      // 仅在内存中保存（不落盘：拿不到就是锁死）
    private static String sRaw = "";
    private static long sLoadedAt = 0L;
    private static long sLastTryAt = 0L;
    private static String sErr = "";
    private static boolean sFetching = false;
    private static Listener sListener = null;
    private static Handler sHandler = null;
    private static boolean sWatching = false;

    private XLModFeatures() {
    }

    public static String url() {
        String u = XLModConfig.cfgGet(KEY_URL, "");
        return (u == null || u.isEmpty()) ? DEFAULT_URL : u;
    }

    public static void setUrl(String u) {
        XLModConfig.cfgSet(KEY_URL, u == null ? "" : u.trim());
    }

    public static void setListener(Listener l) {
        sListener = l;
    }

    /** 是否已经拿到过配置（没拿到 → 面板显示"已锁定"） */
    public static boolean loaded() {
        return sCfg != null;
    }

    public static boolean killed() {
        JSONObject c = sCfg;
        return c != null && c.optBoolean("kill", false);
    }

    /** 公告内容（每次打开面板都要显示） */
    public static String notice() {
        JSONObject c = sCfg;
        return c == null ? "" : c.optString("notice", "");
    }

    /**
     * 功能区是否启用。
     * <p><b>限制语义</b>：没有配置 → 只放行 {@link #FAIL_CLOSED_ALLOW}；有配置 → 按 features 表，
     * 未列出的用 default（缺省 false）。</p>
     */
    public static boolean enabled(String id) {
        try {
            JSONObject c = sCfg;
            if (c == null) return isFailClosedAllowed(id);       // 没配置 → 锁死（隐藏类除外）
            if (c.optBoolean("kill", false)) return false;        // 熔断
            JSONObject f = c.optJSONObject("features");
            if (f != null && f.has(id)) return f.optBoolean(id, false);
            return c.optBoolean("default", false);                // 未列出 → default（缺省 false）
        } catch (Throwable t) {
            return false;                                        // 任何异常 → 锁死
        }
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
            return "授权状态：未获取配置 → 已锁定（仅保留隐藏类功能）"
                    + (sErr.isEmpty() ? "" : "\n失败原因：" + sErr)
                    + "\n地址：" + url();
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
        return "授权状态：" + (c.optBoolean("kill", false) ? "已熔断（全部功能停用）" : "正常")
                + " · 已开启 " + on + " 项 · " + sec + " 秒前拉取"
                + "\n地址：" + url();
    }

    /** 立即（后台）拉取配置；force=false 时 60 秒内不重复请求 */
    public static void refreshAsync(final Context ctx, final boolean force) {
        if (sFetching) return;
        if (!force && System.currentTimeMillis() - sLastTryAt < REFRESH_MS) return;
        sFetching = true;
        sLastTryAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(url());
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "XLMod");
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        sErr = "HTTP " + code;
                        XLModConfig.logAppend("[功能开关] 配置获取失败: " + sErr + "（继续锁定）");
                        return;
                    }
                    InputStream in = conn.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    br.close();
                    String json = sb.toString().trim();
                    JSONObject o = new JSONObject(json);          // 解析失败 → 保持锁定
                    boolean changed = !json.equals(sRaw);
                    sCfg = o;
                    sRaw = json;
                    sErr = "";
                    if (changed) sLoadedAt = System.currentTimeMillis();
                    XLModConfig.logAppend("[功能开关] 配置" + (changed ? "已更新" : "无变化")
                            + "：" + statusText().replace('\n', ' '));
                    if (changed) notifyChanged();
                } catch (Throwable t) {
                    sErr = String.valueOf(t);
                    XLModConfig.logAppend("[功能开关] 配置获取异常: " + t + "（继续锁定）");
                } finally {
                    sFetching = false;
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /** 前台每分钟检查一次；有变化 → 通知面板重建（实时生效） */
    public static void startWatcher() {
        if (sWatching) return;
        sWatching = true;
        sHandler = new Handler(Looper.getMainLooper());
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (XLModHelper.isAppForeground()) {
                        refreshAsync(null, false);
                    }
                } catch (Throwable ignored) {
                }
                if (sHandler != null) sHandler.postDelayed(this, REFRESH_MS);
            }
        }, REFRESH_MS);
        XLModConfig.logAppend("[功能开关] 已启动前台每分钟检查（实时生效）");
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
}
