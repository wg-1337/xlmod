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
import android.content.SharedPreferences;

/**
 * Mod 配置中心：所有功能的开关与参数。
 * 基于 SharedPreferences，与 App 的 MMKV 隔离，零依赖。
 */
public class XLModConfig {
    private static SharedPreferences sPrefs;

    /**
     * 隐私模式静态字段：0=关 1=空值 2=自定义（smali 直接 sget 读取，用于 classes.dex 内零方法引用注入）。
     * 同步策略：<clinit> 从 MMKV 读取（启动早期加 deviceId 头前）；若 MMKV 无对应键/不可用，
     * 由 init(context)（首个 Activity 初始化）从 SharedPreferences 补读——修复"重启后恢复默认"。
     */
    public static int sPrivacyMode;
    public static String sPrivacyDeviceId = "";

    /** 身份等级静态字段：0=全部(最高级,旧版行为) 1=教师 2=班主任 3=校级 4=教研 5=教育局（smali 用 sget 零方法引用读取） */
    public static int sIdentityLevel = 0;

    /** 自动打榜引擎是否激活（供 smali 无引用读取，判定弹窗自动确认/自动退出） */
    public static int sAutoEngineActive = 0;

    private static boolean sPrivacySynced = false;
    private static boolean sIdentitySynced = false;

    static {
        syncServiceStatics();
    }

    private static void syncServiceStatics() {
        try {
            com.tencent.mmkv.MMKV m = net.xuele.android.core.data.MMKVHelper.get();
            if (m != null) {
                if (m.containsKey("privacy_mode")) {
                    sPrivacyMode = m.getInt("privacy_mode", 0);
                    sPrivacyDeviceId = m.getString("privacy_device_id", "");
                    sPrivacySynced = true;
                }
                if (m.containsKey("identity_level")) {
                    sIdentityLevel = m.getInt("identity_level", 0);
                    sIdentitySynced = true;
                }
                // MMKV 实例可用即视为已尝试；缺失的键留给 init() 用 SharedPreferences 补齐
                return;
            }
        } catch (Throwable t) {
        }
        // MMKV 不可用：等 init(context) 后用 SharedPreferences 回退
    }

    /**
     * 早期自加载：类被首次访问时（哪怕是在别的 dex 的 smali 里读 {@code sPrivacyMode}）就把隐私配置读进来。
     *
     * <p>为什么要这么早：{@code DeviceUtil.getDeviceId()/getInstallId()} 的 smali 钩子是**直接读静态字段**的，
     * 如果字段还没初始化（=0），隐私就等于没开。这里通过反射拿 Application（不新增 classes.dex 的方法引用，
     * 因为那个 dex 的方法数已满），拿不到就留给 {@link #ensurePrivacySynced()} 在后续请求时补上。</p>
     *
     * <p>与云端配置无关：这里只读本地 SharedPreferences，不做任何网络/授权判断。</p>
     */
    static {
        try {
            earlyLoadPrivacy();
        } catch (Throwable ignored) {
        }
    }

    private static boolean sPrivacyRetryNeeded = true;

    private static void earlyLoadPrivacy() {
        try {
            Class<?> xlApp = Class.forName("net.xuele.android.core.common.XLApp");
            Object app = xlApp.getMethod("get").invoke(null);
            if (app instanceof android.content.Context) {
                init((android.content.Context) app);
                sPrivacyRetryNeeded = !sPrivacySynced;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 隐私配置兜底同步：第一次没拿到 Application 时，后续任何一次请求都会重试一次 */
    public static void ensurePrivacySynced() {
        if (sPrivacySynced && !sPrivacyRetryNeeded) return;
        if (!sPrivacySynced) {
            syncFromPrefs();
        }
        if (!sPrivacySynced) {
            earlyLoadPrivacy();
        }
        sPrivacyRetryNeeded = !sPrivacySynced;
    }

    private static void syncFromPrefs() {
        SharedPreferences p = p();
        if (p == null) return;
        sPrivacyMode = p.getInt("privacy_mode", 0);
        sPrivacyDeviceId = p.getString("privacy_device_id", "");
        sIdentityLevel = p.getInt("identity_level", 0);
        sPrivacySynced = true;
        sIdentitySynced = true;
    }

    /** 本地文件合并节流：init() 会被 5 秒计时器频繁调用，不能每次都读文件+落盘 */
    private static long sLastInitSyncAt = 0;

    public static void init(Context context) {
        // 一次性迁移：老版本可能把"发作业修复"存成了 false，这里强制开启一次（之后仍可由面板关闭）
        try {
            if (!b("hw_fix_v2", false)) {
                wb("hw_fix_v2", true);
                wb("hw_fix_enabled", true);
                logAppend("[注入] 一次性迁移：已把「发作业修复」开关置为开（如需关闭请在面板手动关闭）");
            }
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        if (now - sLastInitSyncAt > 30000) {
            sLastInitSyncAt = now;
            syncHwTargetsWithFile();
        }
        if (sPrefs == null && context != null) {
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences("xlmod_cfg", Context.MODE_PRIVATE);
            if (!sPrivacySynced || !sIdentitySynced) {
                syncFromPrefs();
            }
        }
    }

    private static SharedPreferences p() {
        return sPrefs;
    }

    private static boolean b(String k, boolean d) {
        SharedPreferences p = p();
        return p == null ? d : p.getBoolean(k, d);
    }

    private static int i(String k, int d) {
        SharedPreferences p = p();
        return p == null ? d : p.getInt(k, d);
    }

    private static String s(String k, String d) {
        SharedPreferences p = p();
        return p == null ? d : p.getString(k, d);
    }

    private static void wb(String k, boolean v) {
        SharedPreferences p = p();
        if (p != null) p.edit().putBoolean(k, v).apply();
    }

    private static void wi(String k, int v) {
        SharedPreferences p = p();
        if (p != null) p.edit().putInt(k, v).apply();
    }

    private static void ws(String k, String v) {
        SharedPreferences p = p();
        if (p != null) p.edit().putString(k, v).apply();
    }

    // ============ 教师身份 ============
    public static boolean isTeacherMod() {
        return b("teacher_mod", false);
    }

    public static void setTeacherMod(boolean v) {
        wb("teacher_mod", v);
    }

    // 职务名称（如 校长/班主任/普通教师）
    public static String getFixPositionName() {
        return s("fix_position_name", "");
    }

    public static void setFixPositionName(String v) {
        ws("fix_position_name", v == null ? "" : v.trim());
    }

    // 身份名称（dutyname，如 教师/教研员）
    public static String getFixDutyName() {
        return s("fix_duty_name", "");
    }

    public static void setFixDutyName(String v) {
        ws("fix_duty_name", v == null ? "" : v.trim());
    }

    public static String getFixPositionId() {
        return s("fix_position_id", "");
    }

    public static void setFixPositionId(String v) {
        ws("fix_position_id", v == null ? "" : v.trim());
    }

    // 身份等级：0=全部(最高级) 1=教师 2=班主任 3=校级 4=教研 5=教育局（前端下拉与 smali 阈值一致）
    public static int getIdentityLevel() {
        if (!sIdentitySynced) syncFromPrefs();
        return sIdentityLevel;
    }

    public static void setIdentityLevel(int v) {
        if (v < 0) v = 0;
        if (v > 5) v = 5;
        sIdentityLevel = v;
        sIdentitySynced = true;
        wi("identity_level", v);
        try {
            if (net.xuele.android.core.data.MMKVHelper.get() != null) {
                net.xuele.android.core.data.MMKVHelper.get().putInt("identity_level", v);
            }
        } catch (Throwable t) {
        }
    }

    // ============ 视频压缩 ============
    // 0=原版行为 1=不压缩 2=固定档位 3=自定义码率
    public static int getCompressMode() {
        return i("compress_mode", 0);
    }

    public static void setCompressMode(int v) {
        wi("compress_mode", v);
    }

    public static int getCompressLevel() {
        return i("compress_level", 3);
    }

    public static void setCompressLevel(int v) {
        wi("compress_level", v);
    }

    // ============ 本地压缩流程 ============
    // 说明：档位/码率**不做自动接管**（与 09-06 老版完全一致）——
    //   档位：「压缩模式=固定档位」时用 compress_level，否则用服务端下发的 compressResolution；
    //   码率：「压缩模式=自定义码率」时用 compress_kbps，否则用 App 默认码率表。
    // 本地压缩流程本身照走（唯一相关改动在 smali：开启「云端保持原片」时不走"不压缩直传"分支）。

    public static int getCompressKbps() {
        return i("compress_kbps", 0);
    }

    public static void setCompressKbps(int v) {
        wi("compress_kbps", v);
    }

    // ============ 云端保持原片（实验：上传扩展名伪装，绕过服务端转码） ============
    public static boolean isCloudKeepOriginal() {
        return b("cloud_keep_original", false);
    }

    public static void setCloudKeepOriginal(boolean v) {
        wb("cloud_keep_original", v);
    }

    // ============ 上传扩展名模式（面板可选）============
    // 0 = bin（伪装扩展名：服务端不按视频转码，**默认**——"发通知/发作业"不压缩就靠它）
    // 1 = 原始媒体扩展名（mp4/mov 等，兼容性优先，但服务端会照常把 1080p 源转码）
    //     非视频文件（文档 ppt/pdf/doc/zip 等）不走这条媒体伪装分支，始终保留自身扩展名。
    // 键名带 _v2：避免旧版本存下的值（曾一度默认成"媒体文件"）继续生效
    /**
     * 上传扩展名：0 = bin（伪装扩展名，云原片用），1 = 媒体文件（原扩展名，默认）。
     *
     * <p>一次性迁移（V4.1c）：早期版本默认是 bin，导致普通上传也丢掉媒体扩展名；
     * 现在默认改为"媒体文件"，对老用户也强制迁移一次（之后仍可在面板手动选回 bin）。</p>
     */
    public static int getHwUploadExtMode() {
        try {
            if (!b("hw_ext_default_v3", false)) {
                wb("hw_ext_default_v3", true);
                wi("hw_upload_ext_mode_v2", 1);
                logAppend("[压缩] 上传扩展名默认值迁移为「媒体文件（原扩展名）」");
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return i("hw_upload_ext_mode_v2", 1);
    }

    public static void setHwUploadExtMode(int v) {
        wi("hw_upload_ext_mode_v2", v <= 0 ? 0 : 1);
    }

    // ============ 通知删除常态化（已发通知：撤回并删除+删除 一直同时显示） ============
    public static boolean isRecallAlways() {
        return b("recall_always", false);
    }

    public static void setRecallAlways(boolean v) {
        wb("recall_always", v);
    }

    // ============ 发作业参数（诊断/后续功能用） ============
    public static String getHwClassId() {
        return s("hw_class_id", "");
    }

    public static void setHwClassId(String v) {
        ws("hw_class_id", v == null ? "" : v.trim());
    }

    public static String getHwBookId() {
        return s("hw_book_id", "");
    }

    public static void setHwBookId(String v) {
        ws("hw_book_id", v == null ? "" : v.trim());
    }

    public static String getHwLessonId() {
        return s("hw_lesson_id", "");
    }

    public static void setHwLessonId(String v) {
        ws("hw_lesson_id", v == null ? "" : v.trim());
    }

    public static String getHwSubjectId() {
        return s("hw_subject_id", "");
    }

    public static void setHwSubjectId(String v) {
        ws("hw_subject_id", v == null ? "" : v.trim());
    }

    public static String getHwGrade() {
        return s("hw_grade", "");
    }

    public static void setHwGrade(String v) {
        ws("hw_grade", v == null ? "" : v.trim());
    }

    // 发作业修复：开关 + 抓取到的目标数据（JSON）
    public static boolean isHwFixEnabled() {
        return b("hw_fix_enabled", true);
    }

    public static void setHwFixEnabled(boolean v) {
        wb("hw_fix_enabled", v);
    }

    // ==================== 通用配置读写（供 XLModFeatures 等模块使用） ====================
    /** 读字符串配置（不存在返回 def） */
    public static String cfgGet(String k, String def) {
        try {
            return s(k, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 写字符串配置 */
    public static void cfgSet(String k, String v) {
        try {
            ws(k, v == null ? "" : v);
        } catch (Throwable ignored) {
        }
    }

    /** 读 long 配置（内部用字符串存，避免 SharedPreferences 类型不一致） */
    public static long cfgGetLong(String k, long def) {
        try {
            String v = s(k, "");
            if (v == null || v.isEmpty()) return def;
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static void cfgSetLong(String k, long v) {
        try {
            ws(k, String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    public static String getHwTargetsJson() {
        return s("hw_targets_json", "");
    }

    private static String sLastSavedJson = "";

    public static void setHwTargetsJson(String v) {
        String json = v == null ? "" : v;
        ws("hw_targets_json", json);
        if (!json.equals(sLastSavedJson)) {
            sLastSavedJson = json;
            saveHwTargetsToFile(json);
        }
    }

    /** 读取本地文件内容（无文件/解析失败返回 null） */
    public static String readHwTargetsFile() {
        try {
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File f = new java.io.File(dir, "xlmod_hw_data.json");
            if (!f.exists() || f.length() <= 0) return null;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            fis.close();
            String json = new String(buf, 0, Math.max(n, 0), "UTF-8");
            new org.json.JSONObject(json);
            return json;
        } catch (Throwable t) {
            logAppend("[抓取] 读本地文件失败: " + t);
            return null;
        }
    }

    /** 以本地文件为准：把文件数据合并回内存（启动、打开面板时调用），并回写文件保持一致 */
    public static void syncHwTargetsWithFile() {
        try {
            String fileJson = readHwTargetsFile();
            if (fileJson == null) {
                String mem = getHwTargetsJson();
                if (!mem.isEmpty()) saveHwTargetsToFile(mem);
                return;
            }
            String memStr = getHwTargetsJson();
            org.json.JSONObject memRoot = memStr.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(memStr);
            org.json.JSONObject fileRoot = new org.json.JSONObject(fileJson);
            String[][] specs = {{"grades", "id"}, {"classes", "classId"}, {"students", "objectId"},
                    {"teachers", "objectId"}, {"books", "bookId"}};
            for (String[] sp : specs) {
                net.xuele.xuelets.mod.XLModHelper.mergeExternal(memRoot, fileRoot, sp[0], sp[1]);
            }
            if (fileRoot.has("materials")) {
                memRoot.put("materials", fileRoot.get("materials"));
            }
            String merged = memRoot.toString();
            if (merged.equals(memStr)) return; // 无变化：不写盘（5 秒计时器会频繁走到这里）
            ws("hw_targets_json", merged);
            sLastSavedJson = merged;
            saveHwTargetsToFile(merged);
            logAppend("[抓取] 已与本地文件合并: 年级=" + len(memRoot, "grades") + " 班级=" + len(memRoot, "classes")
                    + " 学生=" + len(memRoot, "students") + " 课本=" + len(memRoot, "books"));
        } catch (Throwable t) {
            logAppend("[抓取] syncHwTargetsWithFile 异常: " + t);
        }
    }

    private static int len(org.json.JSONObject o, String k) {
        org.json.JSONArray a = o.optJSONArray(k);
        return a == null ? 0 : a.length();
    }

    /** 抓取数据同时落盘到 /sdcard/Download/xlmod_hw_data.json（卸载重装/清数据后仍可用） */
    private static long sLastBackupAt = 0;

    public static void saveHwTargetsToFile(String v) {
        try {
            if (v == null || v.isEmpty()) return;
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "xlmod_hw_data.json");
            // 写前备份（最多每分钟一次）
            try {
                if (f.exists() && f.length() > 0 && System.currentTimeMillis() - sLastBackupAt > 60000) {
                    sLastBackupAt = System.currentTimeMillis();
                    java.io.File bak = new java.io.File(dir, "xlmod_hw_data.bak.json");
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(bak);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(v.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            logAppend("[抓取] 写本地文件失败: " + t);
        }
    }

    /** 保存结果自检：SP 长度 + 文件路径/大小（失败可见） */
    public static void logSaveResult() {
        try {
            String mem = getHwTargetsJson();
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File f = new java.io.File(dir, "xlmod_hw_data.json");
            String fileInfo = f.exists() ? (f.length() + " 字节") : "文件不存在(写入失败?)";
            logAppend("[抓取] 保存自检: SP=" + mem.length() + " 字符, 文件=" + f.getAbsolutePath() + " " + fileInfo);
        } catch (Throwable t) {
            logAppend("[抓取] 保存自检异常: " + t);
        }
    }

    /** 启动时若本地为空则从文件回读（保证"抓过一次就一直有"） */
    public static void loadHwTargetsFromFileIfEmpty() {
        syncHwTargetsWithFile();
    }

    private static void loadHwTargetsFromFileIfEmptyOld() {
        try {
            if (!getHwTargetsJson().isEmpty()) {
                // 已有内存数据 → 反向补写文件，保证两边一致
                saveHwTargetsToFile(getHwTargetsJson());
                return;
            }
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File f = new java.io.File(dir, "xlmod_hw_data.json");
            if (!f.exists() || f.length() <= 0) return;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            fis.close();
            String json = new String(buf, 0, Math.max(n, 0), "UTF-8");
            new org.json.JSONObject(json);
            ws("hw_targets_json", json);
            logAppend("[抓取] 已从 /sdcard/Download/xlmod_hw_data.json 回读本地数据");
        } catch (Throwable ignored) {
        }
    }

    /** 抓取目标类型：3=学生(默认) 4=班级 0=教师 2=学校 1=教育管理 */
    public static int getHwTargetType() {
        return i("hw_target_type", 3);
    }

    public static void setHwTargetType(int v) {
        wi("hw_target_type", v);
    }

    /** 注入时优先使用的课时/课本（由面板「发布课时/发布班级」选择写入） */
    public static String getHwUseLessonId() {
        return s("hw_use_lesson_id", "");
    }

    public static void setHwUseLessonId(String v) {
        ws("hw_use_lesson_id", v == null ? "" : v.trim());
    }

    public static String getHwUseBookId() {
        return s("hw_use_book_id", "");
    }

    public static void setHwUseBookId(String v) {
        ws("hw_use_book_id", v == null ? "" : v.trim());
    }

    /**
     * 不注入课本/课时：开启后布置作业页保持"课外作业"形态（不改 lessonId/bookId、不自动拉题）。
     * 面板「课时」下拉的第一项「不注入（课外作业）」写入此开关；班级注入不受影响。
     */
    public static boolean isHwNoLesson() {
        return b("hw_no_lesson", false);
    }

    public static void setHwNoLesson(boolean v) {
        wb("hw_no_lesson", v);
    }

    /** 一键发作业的题目数量 */
    public static int getHwQCount() {
        return i("hw_q_count", 5);
    }

    public static void setHwQCount(int v) {
        wi("hw_q_count", v <= 0 ? 1 : v);
    }

    // ============ 自动签到 ============
    public static boolean isAutoSign() {
        return b("auto_sign", false);
    }

    public static void setAutoSign(boolean v) {
        wb("auto_sign", v);
    }

    // ============ 循环计时器（每 5 秒检查一次时间；到点自动执行 签到/打榜/云朵） ============
    // 旧版只在 onResume 时复查一次：若到点时 App 一直停在某个页面（没有 resume 事件），就会漏执行。
    public static boolean isTimerEnabled() {
        return b("timer_enabled", true);
    }

    public static void setTimerEnabled(boolean v) {
        wb("timer_enabled", v);
    }

    // ============ 榜单显示用户ID ============
    public static boolean isShowRankUserId() {
        return b("rank_uid", false);
    }

    public static void setShowRankUserId(boolean v) {
        wb("rank_uid", v);
    }

    // ============ 金榜题名自动作答 ============
    public static boolean isAutoAnswer() {
        return b("auto_answer", false);
    }

    public static void setAutoAnswer(boolean v) {
        wb("auto_answer", v);
    }

    // ============ 答题答案悬浮窗 ============
    public static boolean isShowAnswerFloat() {
        return b("answer_float", false);
    }

    public static void setShowAnswerFloat(boolean v) {
        wb("answer_float", v);
    }

    // ============ 隐私隐藏（设备ID） ============
    // 0=关闭(原样) 1=空值 2=自定义；写入 MMKV + prefs 双份；读取：MMKV(<clinit>) 优先，缺失时 init/get 用 prefs 补读
    public static int getPrivacyMode() {
        if (!sPrivacySynced) syncFromPrefs();
        return sPrivacyMode;
    }

    public static void setPrivacyMode(int v) {
        sPrivacyMode = v;
        sPrivacySynced = true;
        wi("privacy_mode", v);
        try {
            if (net.xuele.android.core.data.MMKVHelper.get() != null) {
                net.xuele.android.core.data.MMKVHelper.get().putInt("privacy_mode", v);
            }
        } catch (Throwable t) {
        }
    }

    /** 当前 Mod 版本号（唯一来源：面板显示、更新检测都用它）。作者的标签习惯是 v<版本号> */
    public static final String VERSION = "v4.2p";

    /** 隐私隐藏：是否同时清空请求头里的机型/系统版本（phoneModel / systemVersion） */
    public static boolean isPrivacyHideModel() {
        return b("privacy_hide_model", true);
    }

    public static void setPrivacyHideModel(boolean v) {
        wb("privacy_hide_model", v);
    }

    public static String getPrivacyDeviceId() {
        if (!sPrivacySynced) syncFromPrefs();
        return sPrivacyDeviceId;
    }

    public static void setPrivacyDeviceId(String v) {
        String val = v == null ? "" : v.trim();
        sPrivacyDeviceId = val;
        sPrivacySynced = true;
        ws("privacy_device_id", val);
        try {
            if (net.xuele.android.core.data.MMKVHelper.get() != null) {
                net.xuele.android.core.data.MMKVHelper.get().putString("privacy_device_id", val);
            }
        } catch (Throwable t) {
        }
    }

    // ============ 答题答案获取方式 ============
    // 0=本地数据 1=判题接口（题目ID->exercise/correct） 2=详情接口（挑战ID->queryCompetitionDetail）
    public static int getAnswerFetchMode() {
        return i("answer_fetch_mode", 0);
    }

    public static void setAnswerFetchMode(int v) {
        wi("answer_fetch_mode", v);
    }

    // ============ 调试：三窗对比 ============
    public static boolean isDebugFloat() {
        return b("debug_float", false);
    }

    public static void setDebugFloat(boolean v) {
        wb("debug_float", v);
    }

    // ============ 云朵助手 ============
    public static boolean isAutoCloud() {
        return b("auto_cloud", false);
    }

    public static void setAutoCloud(boolean v) {
        wb("auto_cloud", v);
    }

    public static boolean isCloudClaimedToday(String uid) {
        if (uid == null || uid.isEmpty()) return true;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        String v = s("cloud_claimed_" + uid, "");
        return today.equals(v);
    }

    public static void markCloudClaimed(String uid) {
        if (uid == null || uid.isEmpty()) return;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        ws("cloud_claimed_" + uid, today);
    }

    // ============ Mod 运行日志 / 崩溃栈 ============
    public static void logAppend(String line) {
        try {
            SharedPreferences p = p();
            if (p == null) return;
            String old = p.getString("xllog", "");
            String t = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            String next = (old + "\n" + t + " " + line);
            if (next.length() > 6000) {
                next = next.substring(next.length() - 6000);
            }
            p.edit().putString("xllog", next).apply();
        } catch (Throwable t) {
        }
    }

    public static String logGet() {
        return s("xllog", "");
    }

    public static void logClear() {
        ws("xllog", "");
    }

    public static void crashPut(String stack) {
        ws("xlcrash", stack);
    }

    public static String crashGet() {
        return s("xlcrash", "");
    }

    // ============ 定时/自动 ============
    // HH:mm 比较（当前时间是否已到 t）
    public static boolean isAfterTime(String hhmm) {
        if (hhmm == null || hhmm.isEmpty()) return true;
        try {
            String now = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
            return now.compareTo(hhmm) >= 0;
        } catch (Throwable t) {
            return true;
        }
    }

    // 指定时间后自动签到（默认 00:00 = 任意时间）
    public static String getSignTime() {
        return s("sign_time", "00:00");
    }

    public static void setSignTime(String v) {
        ws("sign_time", v == null ? "00:00" : v.trim());
    }

    // ============ 自动打榜 ============
    public static boolean isAutoChallenge() {
        return b("auto_challenge", false);
    }

    public static void setAutoChallenge(boolean v) {
        wb("auto_challenge", v);
    }

    public static String getChallengeStartTime() {
        return s("challenge_start_time", "00:00");
    }

    public static void setChallengeStartTime(String v) {
        ws("challenge_start_time", v == null ? "00:00" : v.trim());
    }

    // 操作延迟(毫秒)
    public static int getChallengeEntryDelay() {
        return i("challenge_entry_delay", 3000);
    }

    public static void setChallengeEntryDelay(int v) {
        wi("challenge_entry_delay", v);
    }

    public static int getChallengeAnswerDelay() {
        return i("challenge_answer_delay", 2500);
    }

    public static void setChallengeAnswerDelay(int v) {
        wi("challenge_answer_delay", v);
    }

    public static int getChallengeExitDelay() {
        return i("challenge_exit_delay", 3000);
    }

    public static void setChallengeExitDelay(int v) {
        wi("challenge_exit_delay", v);
    }

    // 每天每学科最多挑战次数（与服务器一致默认3）
    public static int getBattlesPerSubject() {
        return i("battles_per_subject", 3);
    }

    public static void setBattlesPerSubject(int v) {
        wi("battles_per_subject", v);
    }

    // 学科列表（id:名称），逗号分隔
    public static String getChallengeSubjects() {
        return s("challenge_subjects", "2:\u6570\u5b66,1:\u8bed\u6587,3:\u82f1\u8bed,4:\u7269\u7406,5:\u5316\u5b66,6:\u751f\u7269,7:\u5386\u53f2,8:\u5730\u7406,9:\u653f\u6cbb");
    }

    public static void setChallengeSubjects(String v) {
        ws("challenge_subjects", v == null ? "" : v.trim());
    }

    public static boolean isChallengeDoneToday(String uid) {
        if (uid == null || uid.isEmpty()) return true;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        return today.equals(s("challenge_done_" + uid, ""));
    }

    public static void markChallengeDone(String uid) {
        if (uid == null || uid.isEmpty()) return;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        ws("challenge_done_" + uid, today);
    }

    public static void clearChallengeDone(String uid) {
        if (uid == null || uid.isEmpty()) return;
        ws("challenge_done_" + uid, "");
    }

    // ============ 教师工具：学生编辑 userId ============
    public static String getLaunchUserId() {
        return s("launch_user_id", "");
    }

    public static void setLaunchUserId(String v) {
        ws("launch_user_id", v == null ? "" : v.trim());
    }

    // ============ 答案知识库（赛后详情采集，供下一局命中） ============
    public static void kbPut(String key, String value) {
        if (key == null || key.isEmpty() || value == null) return;
        ws("kb_" + key, value);
    }

    public static String kbGet(String key) {
        if (key == null || key.isEmpty()) return "";
        return s("kb_" + key, "");
    }

    // ============ 签到去重 ============
    public static boolean isSignedToday(String uid) {
        if (uid == null || uid.isEmpty()) return true;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        String key = "signed_" + uid;
        String v = s(key, "");
        return today.equals(v);
    }

    public static void markSigned(String uid) {
        if (uid == null || uid.isEmpty()) return;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        ws("signed_" + uid, today);
    }

    // ============ 签到去重（拆分：用户空间 / 无尽大陆 各自独立标记） ============
    // 旧版共用 signed_<uid> 一个标记：两个签到接口中任意一个成功即把两者都"视为已签"，
    // 导致第二个接口（或次日仍要签的接口）被永久跳过——这正是"只能签第一天"的根因之一。
    public static boolean isSignedSpaceToday(String uid) {
        return isDayMarked("sign_space_", uid);
    }

    public static void markSpaceSigned(String uid) {
        markDay("sign_space_", uid);
    }

    public static boolean isSignedEndlessToday(String uid) {
        return isDayMarked("sign_endless_", uid);
    }

    public static void markEndlessSigned(String uid) {
        markDay("sign_endless_", uid);
    }

    private static boolean isDayMarked(String prefix, String uid) {
        if (uid == null || uid.isEmpty()) return true;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        return today.equals(s(prefix + uid, ""));
    }

    private static void markDay(String prefix, String uid) {
        if (uid == null || uid.isEmpty()) return;
        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        ws(prefix + uid, today);
    }
}
