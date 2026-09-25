/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件是 XLMod 的一部分：你可以按 GNU Affero 通用公共许可证第 3 版（或更高版本）条款
 * 使用、修改与再分发；通过网络提供服务时须向使用者提供对应源码。详见仓库根目录 LICENSE。
 */

package net.xuele.xuelets.mod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 版本更新检测（强制更新）。
 *
 * <p>数据源：GitHub Releases —— {@code https://api.github.com/repos/wg-1337/xlmod/releases}。
 * 取所有非 draft / 非 prerelease 的 tag（作者的标签习惯是 {@code v<版本号>}），
 * 按版本号取最大者与本地 {@link XLModConfig#VERSION} 比较（**忽略大小写**）。</p>
 *
 * <p>发现新版本 → 弹**强制更新**窗口：</p>
 * <ul>
 *   <li>不可取消：无"取消"按钮、返回键无效、点击外部不关闭；</li>
 *   <li><b>始终在最上方</b>：窗口挂在"当前前台 Activity"上，并在**每次 Activity 恢复时重新弹出**
 *       （覆盖后续新开的页面），同时在面板手动检查时即时弹出；</li>
 *   <li>按钮：立即更新（打开 release 的 APK 资源链接/发布页）；没有取消路径。</li>
 * </ul>
 *
 * <p>与云端授权无关：本模块只做版本比较与提示，不参与任何权限判断。</p>
 */
public final class XLModUpdate {

    /** Releases API（列全部 release，自行比较取最大版本） */
    private static final String API = "https://api.github.com/repos/wg-1337/xlmod/releases";
    /** 没有资产链接时的兜底地址 */
    private static final String FALLBACK = "https://github.com/wg-1337/xlmod/releases";

    private static final long CHECK_TTL_MS = 10L * 60L * 1000L;   // 10 分钟内不重复请求

    private static String sLatest = "";          // 远端最新版本号（如 v4.2p）
    private static String sDownloadUrl = "";     // 该 release 的 APK 直链（没有则回落发布页）
    private static String sNotes = "";           // release 说明（截断）
    private static boolean sChecked = false;     // 本次进程是否已检查过
    private static long sLastTry = 0L;
    private static boolean sFetching = false;
    private static AlertDialog sDialog = null;   // 当前挂着的强制更新窗口
    private static Activity sDialogHost = null;

    private XLModUpdate() {
    }

    // ==================== 对外 ====================

    /** 是否有更新（远端版本 > 本地版本） */
    public static boolean updateRequired() {
        return !sLatest.isEmpty() && compareVersions(sLatest, XLModConfig.VERSION) > 0;
    }

    public static String latestTag() {
        return sLatest;
    }

    public static String statusText() {
        if (sLatest.isEmpty()) return "更新检测：未获取（可点「检查更新」重试）";
        return updateRequired()
                ? "更新检测：发现新版本 " + sLatest + "（当前 " + XLModConfig.VERSION + "）→ 需强制更新"
                : "更新检测：已是最新（当前 " + XLModConfig.VERSION + "，远端 " + sLatest + "）";
    }

    /** 启动/手动检查（后台线程；force=true 忽略 10 分钟节流） */
    public static void checkAsync(final android.content.Context ctx, final boolean force) {
        long now = System.currentTimeMillis();
        if (sFetching) return;
        if (!force && sChecked && now - sLastTry < CHECK_TTL_MS) return;
        sFetching = true;
        sLastTry = now;
        sChecked = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = httpGet(API);
                    if (json == null) {
                        XLModConfig.logAppend("[更新] releases 获取失败（网络？）");
                        return;
                    }
                    JSONArray arr = new JSONArray(json);
                    String bestTag = "";
                    String bestUrl = "";
                    String bestNotes = "";
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.optJSONObject(i);
                        if (r == null) continue;
                        if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) continue;
                        String tag = r.optString("tag_name", "").trim();
                        if (tag.isEmpty()) continue;
                        if (bestTag.isEmpty() || compareVersions(tag, bestTag) > 0) {
                            bestTag = tag;
                            bestUrl = pickAssetUrl(r);
                            bestNotes = r.optString("body", "");
                        }
                    }
                    if (bestTag.isEmpty()) {
                        XLModConfig.logAppend("[更新] 没有可用的 release（全部为 draft/prerelease？）");
                        return;
                    }
                    sLatest = bestTag;
                    sDownloadUrl = bestUrl.isEmpty() ? FALLBACK : bestUrl;
                    sNotes = shorten(bestNotes, 300);
                    XLModConfig.logAppend("[更新] 远端最新=" + sLatest + " 本地=" + XLModConfig.VERSION
                            + " → " + (updateRequired() ? "需要强制更新" : "已是最新"));
                    if (updateRequired()) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                showOnTop();
                            }
                        });
                    }
                } catch (Throwable t) {
                    XLModConfig.logAppend("[更新] 检测异常: " + t);
                } finally {
                    sFetching = false;
                }
            }
        }).start();
    }

    /** 每次 Activity 恢复时调用：需要更新就重新把窗口顶上来 */
    public static void onActivityResumed(final Activity act) {
        if (act == null || !updateRequired()) return;
        if (sDialogHost == act && sDialog != null && sDialog.isShowing()) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                show(act);
            }
        });
    }

    /** 手动触发：立刻检查并（若有新版）立刻弹窗 */
    public static void checkNowAndShow(final Activity act) {
        checkAsync(act, true);
        if (updateRequired()) show(act);
        else if (act != null) {
            android.widget.Toast.makeText(act, "正在检查更新…", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 窗口 ====================

    /** 挂在当前前台 Activity 上弹出（没有则挂传入的） */
    private static void showOnTop() {
        Activity host = XLModHelper.currentActivity();
        if (host != null) show(host);
    }

    public static void show(final Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            if (sDialog != null && sDialog.isShowing() && sDialogHost == act) return;
            dismiss();
            String msg = "当前版本：" + XLModConfig.VERSION + "\n最新版本：" + sLatest
                    + "\n\n本版本已停止使用，必须更新后才能继续。"
                    + (sNotes.isEmpty() ? "" : "\n\n更新说明：\n" + sNotes);
            AlertDialog d = new AlertDialog.Builder(act)
                    .setTitle("发现新版本，需强制更新")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openUrl(act, sDownloadUrl);
                            // 点完不关：更新完成（重装）前一直挡着；下次恢复/重开还会再弹
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (updateRequired()) show(act);
                                }
                            }, 1200);
                        }
                    })
                    .create();
            d.setCanceledOnTouchOutside(false);
            d.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                    return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
                            || keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH;
                }
            });
            d.show();
            sDialog = d;
            sDialogHost = act;
            XLModConfig.logAppend("[更新] 已在页面 " + act.getClass().getSimpleName() + " 弹出强制更新窗口");
        } catch (Throwable t) {
            XLModConfig.logAppend("[更新] 弹窗失败: " + t);
        }
    }

    public static void dismiss() {
        try {
            if (sDialog != null && sDialog.isShowing()) sDialog.dismiss();
        } catch (Throwable ignored) {
        }
        sDialog = null;
        sDialogHost = null;
    }

    // ==================== 工具 ====================

    /** 取 release 里第一个 APK 资源直链；没有就返回空（调用方回落发布页） */
    private static String pickAssetUrl(JSONObject release) {
        try {
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) return "";
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "").toLowerCase();
                String url = a.optString("browser_download_url", "");
                if (url.isEmpty()) continue;
                if (name.endsWith(".apk")) return url;
            }
            // 没有 apk 就取第一个资产
            JSONObject a0 = assets.optJSONObject(0);
            if (a0 != null) return a0.optString("browser_download_url", "");
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 版本比较（忽略大小写；形如 v4.2p / V4.10 / 4.2）：
     * 先逐段比数字，再比后缀字母。返回 &gt;0 表示 a 比 b 新。
     */
    public static int compareVersions(String a, String b) {
        try {
            String[] pa = split(a), pb = split(b);
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int va = num(i < pa.length ? pa[i] : "0");
                int vb = num(i < pb.length ? pb[i] : "0");
                if (va != vb) return va > vb ? 1 : -1;
            }
            String sa = suffix(a), sb = suffix(b);
            return sa.compareToIgnoreCase(sb);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 去掉前缀 v/V，去掉后缀字母，按 . 切分 */
    private static String[] split(String v) {
        String s = clean(v);
        return s.isEmpty() ? new String[]{"0"} : s.split("\\.");
    }

    private static String clean(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        return s.substring(0, i);
    }

    /** 版本号尾部的字母后缀（4.2p → "p"；4.2 → ""） */
    private static String suffix(String v) {
        if (v == null) return "";
        String s = v.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) sb.insert(0, c);
            else break;
        }
        return sb.toString();
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace("\r", "");
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    private static void openUrl(Activity act, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    private static String httpGet(String u) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "XLMod");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
