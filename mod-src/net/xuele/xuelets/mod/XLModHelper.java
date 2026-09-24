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
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.xuele.xuelets.challenge.model.M_ChallengeQuestion;
import net.xuele.android.ui.question.AnswersBean;
import net.xuele.android.ui.question.ChallengeUserAnswer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mod 运行时辅助：登录态签到、榜单ID、自动作答预填、答题答案悬浮窗、设置页入口。
 * 所有方法均要求：被调用时以真实 App 类运行（stub 仅用于编译期签名对齐）。
 */
public class XLModHelper {

    // ================= 自动签到 =================
    private static boolean sSignTriedThisRun = false;

    // ================= 云端保持原片（深度伪装：首尾扰码） =================
    private static final java.util.concurrent.ConcurrentHashMap<String, File> sCloudDisguise = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> sCloudDisguiseMd5 = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已生成的伪装副本绝对路径：再次传入副本本身时直接返回，避免"自己拷自己"把文件截断 */
    private static final java.util.Set<String> sCloudDisguisedPaths = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    // ============ 伪装参数（与 09-06 那版"实测有效"的实现保持一致）============
    //  从老包 classes7.dex 反编译逐行核对，当年的伪装**只做两件事**：
    //    ① 视频轨 tkhd 的宽高 → 1280×720（16.16 定点）
    //    ② avc1 / hvc1 / hev1 采样条目的宽高 → 1280×720
    //  不做 avcC.level / SPS.level_idc / btrt 之类的"精修"——那些会让文件自相矛盾
    private static final int SPOOF_W = 1280;
    private static final int SPOOF_H = 720;

    /** 生成"头部伪装"副本：内容零改动（保持原始画质），仅把容器分辨率元数据改写成 1280x720（假装已压缩） */
    public static File disguiseFileForCloud(File src) {
        try {
            if (src == null || !src.exists()) return src;
            String key = src.getAbsolutePath();
            File cached = sCloudDisguise.get(key);
            if (cached != null && cached.exists() && cached.length() == src.length()) return cached;
            // 已经是伪装副本（业务层提前适配过）→ 原样返回，幂等
            if (sCloudDisguisedPaths.contains(key)) return src;
            File dir = src.getParentFile();
            File tmpBase = new File(System.getProperty("java.io.tmpdir"));
            if (tmpBase == null || !tmpBase.isDirectory()) {
                tmpBase = new File(dir == null ? "." : dir.getAbsolutePath(), "xlmod_tmp");
            }
            if (!tmpBase.exists()) tmpBase.mkdirs();
            File dst = new File(tmpBase, src.getName());
            if (dst.getAbsolutePath().equals(key)) return src; // 同路径：绝不能自拷贝（会截断文件）
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            sCloudDisguisedPaths.add(dst.getAbsolutePath());
            sCloudDisguise.put(key, dst);
            sCloudDisguiseMd5.remove(key);
            int patched = spoofVideoHeader(dst);
            if (patched > 0) {
                XLModConfig.logAppend("[云原片] 容器伪装已应用: " + src.getName() + " 大小=" + dst.length()
                        + " 改动字段=" + patched + " 个（tkhd/avc1·hvc1 宽高 → " + SPOOF_W + "x" + SPOOF_H
                        + "，编码参数与码流未动）");
            } else {
                XLModConfig.logAppend("[云原片] ⚠ 未找到可伪装字段（改动 0 处）: " + src.getName()
                        + " —— 可能不是 MP4/fMP4 容器（如已是 bin/无 moov），此时仅扩展名伪装生效");
            }
            return dst;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 把 MP4 容器里的视频分辨率元数据（avc1/hvc1 宽高 + tkhd 宽高）改写成 1280x720 */
    private static int sSpoofCount = 0;

    /** 返回本次改动的字段个数（0 = 没找到可伪装字段，需告警） */
    private static int spoofVideoHeader(File f) {
        sSpoofCount = 0;
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
            long len = raf.length();
            long off = 0;
            while (off + 8 <= len) {
                long next = boxNext(raf, off);
                if (next <= 0) break;
                byte[] t = new byte[4];
                raf.seek(off + 4);
                raf.readFully(t);
                if (new String(t, "UTF-8").equals("moov")) {
                    walkMoov(raf, off + 8, next);
                }
                off = next;
            }
            raf.close();
        } catch (Throwable t) {
            XLModConfig.logAppend("[云原片] 伪装异常: " + t);
        }
        return sSpoofCount;
    }

    private static long boxNext(java.io.RandomAccessFile raf, long off) throws Exception {
        raf.seek(off);
        byte[] h = new byte[8];
        if (raf.read(h, 0, 8) < 8) return -1;
        long size = ((long) (h[0] & 0xFF) << 24) | ((long) (h[1] & 0xFF) << 16) | ((long) (h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        long typeOff = off + 4;
        if (size == 1) {
            raf.seek(off + 8);
            byte[] w = new byte[8];
            if (raf.read(w, 0, 8) < 8) return -1;
            size = 0;
            for (int i = 0; i < 8; i++) size = (size << 8) | (w[i] & 0xFF);
            return off + size;
        }
        if (size == 0) return -1;
        return off + size;
    }

    private static String boxType(java.io.RandomAccessFile raf, long off) throws Exception {
        raf.seek(off + 4);
        byte[] t = new byte[4];
        if (raf.read(t, 0, 4) < 4) return "";
        return new String(t, "UTF-8");
    }

    private static void walkMoov(java.io.RandomAccessFile raf, long start, long end) throws Exception {
        long off = start;
        while (off + 8 <= end) {
            long next = boxNext(raf, off);
            if (next <= 0 || next > end) break;
            String t = boxType(raf, off);
            if (t.equals("trak")) {
                walkTrak(raf, off + 8, next);
            }
            off = next;
        }
    }

    private static void walkTrak(java.io.RandomAccessFile raf, long start, long end) throws Exception {
        // 与 09-06 老版一致：**每条轨道的 tkhd 都写** 1280×720（不做视频轨过滤）
        long off = start;
        while (off + 8 <= end) {
            long next = boxNext(raf, off);
            if (next <= 0 || next > end) break;
            String t = boxType(raf, off);
            if (t.equals("tkhd")) {
                // tkhd：ver0 宽@off+84/高@off+88；ver1 宽@off+96/高@off+100（16.16 定点）
                raf.seek(off + 8);
                int ver = raf.read() & 0xFF;
                if (ver == 0) {
                    writeU32(raf, off + 84, SPOOF_W << 16);
                    writeU32(raf, off + 88, SPOOF_H << 16);
                } else {
                    writeU32(raf, off + 96, SPOOF_W << 16);
                    writeU32(raf, off + 100, SPOOF_H << 16);
                }
                sSpoofCount += 2;
            } else if (t.equals("mdia")) {
                walkMdia(raf, off + 8, next);
            }
            off = next;
        }
    }


    private static void walkMdia(java.io.RandomAccessFile raf, long start, long end) throws Exception {
        long off = start;
        while (off + 8 <= end) {
            long next = boxNext(raf, off);
            if (next <= 0 || next > end) break;
            String t = boxType(raf, off);
            if (t.equals("minf")) {
                walkMinf(raf, off + 8, next);
            }
            off = next;
        }
    }

    private static void walkMinf(java.io.RandomAccessFile raf, long start, long end) throws Exception {
        long off = start;
        while (off + 8 <= end) {
            long next = boxNext(raf, off);
            if (next <= 0 || next > end) break;
            String t = boxType(raf, off);
            if (t.equals("stbl")) {
                walkStbl(raf, off + 8, next);
            }
            off = next;
        }
    }

    private static void walkStbl(java.io.RandomAccessFile raf, long start, long end) throws Exception {
        long off = start;
        while (off + 8 <= end) {
            long next = boxNext(raf, off);
            if (next <= 0 || next > end) break;
            String t = boxType(raf, off);
            if (t.equals("stsd")) {
                // stsd：4 ver/flags + 4 entry_count → 首条目 at off+8+8
                long entry = off + 16;
                if (entry + 8 <= next) {
                    raf.seek(entry + 4);
                    byte[] et = new byte[4];
                    if (raf.read(et, 0, 4) < 4) break;
                    String etype = new String(et, "UTF-8");
                    if (etype.equals("avc1") || etype.equals("hvc1") || etype.equals("hev1")) {
                        // 老版（09-06 实测有效）就只改这一处 + tkhd；avcC/SPS/btrt 一律不动
                        writeU16(raf, entry + 32, SPOOF_W);
                        writeU16(raf, entry + 34, SPOOF_H);
                        sSpoofCount += 2;
                    }
                }
            }
            off = next;
        }
    }

    private static void writeU32(java.io.RandomAccessFile raf, long pos, int v) throws Exception {
        raf.seek(pos);
        raf.write((v >>> 24) & 0xFF);
        raf.write((v >>> 16) & 0xFF);
        raf.write((v >>> 8) & 0xFF);
        raf.write(v & 0xFF);
    }

    private static void writeU16(java.io.RandomAccessFile raf, long pos, int v) throws Exception {
        raf.seek(pos);
        raf.write((v >>> 8) & 0xFF);
        raf.write(v & 0xFF);
    }

    /** 伪装副本的 MD5（上传接口需按实际上传内容计算，缓存于本进程） */
    public static String disguisedMd5(File f) {
        try {
            if (f == null || !f.exists()) return "";
            String key = f.getAbsolutePath();
            String cached = sCloudDisguiseMd5.get(key);
            if (cached != null) return cached;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            String hex = sb.toString();
            sCloudDisguiseMd5.put(key, hex);
            return hex;
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= 发作业（业务层上传）适配：视频云原片 =================
    /** 视频扩展名判定（纯字符串判断，不引用 App 类，零新增方法引用压力） */
    private static boolean isVideoExtName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String e = n.substring(dot + 1);
        return e.equals("mp4") || e.equals("mov") || e.equals("m4v") || e.equals("3gp") || e.equals("3gpp")
                || e.equals("avi") || e.equals("mkv") || e.equals("flv") || e.equals("wmv") || e.equals("webm")
                || e.equals("rm") || e.equals("rmvb") || e.equals("ts") || e.equals("mpg") || e.equals("mpeg");
    }

    /** 取原始扩展名（小写，不含点；无扩展名返回空串） */
    private static String originalExt(String name) {
        if (name == null) return "";
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= n.length()) return "";
        return n.substring(dot + 1);
    }

    /**
     * 上传扩展名（面板可选：bin / 原始媒体扩展名）。
     * smali 注入点调用：返回 "bin" = 伪装扩展名（默认，绕过服务端转码）；返回 null = 保持原扩展名（媒体文件）。
     */
    public static String uploadExtFor(String fileName) {
        try {
            // 云端保持原片开启 → 强制 bin（"成功配方"：不重编码 + 容器伪装 + bin 扩展名）
            // 只有云原片关闭时，"上传扩展名"下拉才有意义
            if (XLModConfig.isCloudKeepOriginal()) {
                logCloudOnce("[云原片] 上传扩展名=bin（云原片开启 → 强制伪装扩展名，绕过服务端转码）: " + fileName);
                return "bin";
            }
            if (XLModConfig.getHwUploadExtMode() == 1) return null; // 原始媒体扩展名
            return "bin";
        } catch (Throwable t) {
            return "bin";
        }
    }

    private static String sLastCloudLog = "";

    /** 云原片相关日志去重（同一句只写一次，避免刷屏） */
    public static void logCloudOnce(String msg) {
        try {
            if (msg == null || msg.equals(sLastCloudLog)) return;
            sLastCloudLog = msg;
            XLModConfig.logAppend(msg);
        } catch (Throwable ignored) {
        }
    }

    // ==================== V3.1e 压缩判定仪表 ====================
    /**
     * 复刻 VideoUtils.needCompress(path) 的判定过程并写日志。
     * 判定公式：needCompress = 视频实际码率 > getResolutionBitRate(getClosedResolutionType(w,h)) × 1.1
     * 注入点：VideoUtils.needCompress 入口（每个上传的视频都会走这里）。
     */
    public static void logNeedCompress(String path) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(path);
            int w = parseIntSafe(r.extractMetadata(18));   // METADATA_KEY_VIDEO_WIDTH
            int h = parseIntSafe(r.extractMetadata(19));   // METADATA_KEY_VIDEO_HEIGHT
            int br = parseIntSafe(r.extractMetadata(20));  // METADATA_KEY_BITRATE
            r.release();
            int lvl = closestLevel(w, h);
            int target = targetBitrate(lvl);
            boolean need = br > (long) (target * 1.1f);
            java.io.File f = new java.io.File(path);
            XLModConfig.logAppend("[压缩判定] " + f.getName() + " " + w + "x" + h + " 码率=" + br
                    + " 档位=" + lvl + " 目标=" + target + " → needCompress=" + need
                    + " ｜ 云原片=" + XLModConfig.isCloudKeepOriginal()
                    + " 压缩模式=" + XLModConfig.getCompressMode()
                    + " 自定义码率=" + XLModConfig.getCompressKbps());
        } catch (Throwable t) {
            XLModConfig.logAppend("[压缩判定] " + path + " 读取失败: " + t);
        }
    }

    /** 复刻 VideoFormatHelper.getClosedResolutionType(w,h)：1=450P 2=720P 3=1080P 4=2K（取像素数最接近的档） */
    private static int closestLevel(int w, int h) {
        long px = (long) w * h;
        long[] sizes = {800L * 450, 1280L * 720, 1920L * 1080, 2560L * 1440};
        int best = 3;
        long bestDiff = -1;
        for (int i = 0; i < sizes.length; i++) {
            long d = Math.abs(sizes[i] - px);
            if (bestDiff < 0 || d < bestDiff) {
                bestDiff = d;
                best = i + 1;
            }
        }
        return best;
    }

    /** 复刻 VideoFormatHelper.getResolutionBitRate(level)（含本 Mod 的挂钩与 V3.1e 云原片兜底） */
    private static int targetBitrate(int level) {
        if (XLModConfig.isCloudKeepOriginal()) return 100000000;
        if (XLModConfig.getCompressMode() == 3) {
            int k = XLModConfig.getCompressKbps();
            if (k > 0) return k * 1000;
        }
        if (level == 2 || level == 3) return 2000000;
        if (level == 4) return 3000000;
        return 1000000;
    }

    private static Object callOn(Object o, String name, Class[] types, Object[] args) {
        if (o == null) return null;
        try {
            java.lang.reflect.Method m = o.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(o, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 发作业等"业务层上传"链路的视频适配（UploadTask → FileUploadManager → SingleFileTask）。
     * 必须在 UploadDataHelper.refreshFileKey()/服务端去重之前执行：否则 fileKey/sourceMd5 按"原始文件"算，
     * 与随后被换成伪装副本的上传内容对不上，云原片开关在这条链路上形同失效。
     * 处理方式与 SingleFileTask 内注入一致：路径换伪装副本 + md5 按副本修正 + 扩展名按 bin。
     */
    public static void prepareCloudKeepOriginal(java.util.List resources) {
        try {
            if (!XLModConfig.isCloudKeepOriginal()) return;
            if (resources == null || resources.isEmpty()) return;
            int n = 0;
            for (int i = 0; i < resources.size(); i++) {
                Object res = resources.get(i);
                if (res == null) continue;
                try {
                    String path = (String) callOn(res, "getAvailablePathOrUrl", new Class[]{}, new Object[]{});
                    if (path == null || path.isEmpty()) path = (String) callOn(res, "getPath", new Class[]{}, new Object[]{});
                    if (path == null || path.isEmpty()) continue;
                    File f = new File(path);
                    if (!f.exists() || f.length() <= 0) continue;
                    if (!isVideoExtName(f.getName())) continue;
                    File d = disguiseFileForCloud(f);
                    if (d == null || !d.exists() || d.getAbsolutePath().equals(f.getAbsolutePath())) continue;
                    String md5 = disguisedMd5(d);
                    // 扩展名：面板可选 bin（伪装，绕过转码）/ 原始媒体扩展名（mp4 等）
                    boolean keepExt = XLModConfig.getHwUploadExtMode() == 1;
                    String ext = keepExt ? originalExt(f.getName()) : "bin";
                    callOn(res, "setPath", new Class[]{File.class}, new Object[]{d});
                    callOn(res, "setFileName", new Class[]{String.class}, new Object[]{f.getName()});
                    callOn(res, "setFileSize", new Class[]{String.class}, new Object[]{String.valueOf(d.length())});
                    callOn(res, "setFileMd5", new Class[]{String.class}, new Object[]{md5});
                    callOn(res, "setFileExtension", new Class[]{String.class}, new Object[]{ext});
                    callOn(res, "setSourceMd5", new Class[]{String.class}, new Object[]{""});
                    callOn(res, "setFileKey", new Class[]{String.class}, new Object[]{""});
                    n++;
                    XLModConfig.logAppend("[云原片] 发作业上传适配: " + f.getName() + " → 扩展名=" + ext
                            + (keepExt ? "(原始媒体扩展名)" : "(bin 伪装)") + " md5=" + md5);
                } catch (Throwable t) {
                }
            }
            if (n > 0) XLModConfig.logAppend("[云原片] 本次共适配 " + n + " 个视频资源（云原片开启 → 扩展名强制 bin，绕过服务端转码）");
        } catch (Throwable t) {
            XLModConfig.logAppend("[云原片] prepareCloudKeepOriginal 异常: " + t);
        }
    }

    // ================= 发作业接口诊断（反射调用 teacherWork/v3/*，结果写 Mod 日志） =================
    public static void diagHomework(final Activity act) {
        if (act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    XLModConfig.logAppend("===== 发作业接口诊断 " + System.currentTimeMillis() + " =====");
                    try {
                        net.xuele.android.common.login.LoginManager lm = net.xuele.android.common.login.LoginManager.getInstance();
                        XLModConfig.logAppend("[诊断] 身份: userId=" + lm.getUserId()
                                + " classId=" + callStr(lm, "getClassId")
                                + " gradeId=" + callStr(lm, "getGradeId")
                                + " schoolId=" + callStr(lm, "getSchoolId")
                                + " dutyId=" + callStr(lm, "getDutyId")
                                + " isTeacher=" + callStr(lm, "isTeacher")
                                + " isStudent=" + callStr(lm, "isStudent"));
                    } catch (Throwable t) {
                        XLModConfig.logAppend("[诊断] 身份读取失败: " + t);
                    }
                    XLModConfig.logAppend("[诊断] 配置: classId=" + XLModConfig.getHwClassId()
                            + " bookId=" + XLModConfig.getHwBookId()
                            + " lessonId=" + XLModConfig.getHwLessonId()
                            + " subjectId=" + XLModConfig.getHwSubjectId()
                            + " grade=" + XLModConfig.getHwGrade());
                    probeApi("getClassAndStudent", "getClassAndStudent", new Class[]{}, new Object[]{});
                    if (!XLModConfig.getHwBookId().isEmpty()) {
                        probeApi("getUnits(bookId=" + XLModConfig.getHwBookId() + ")", "getUnits",
                                new Class[]{String.class}, new Object[]{XLModConfig.getHwBookId()});
                    } else {
                        XLModConfig.logAppend("[诊断] getUnits 跳过（未填教材ID）");
                    }
                    if (!XLModConfig.getHwLessonId().isEmpty()) {
                        probeApi("getQuestions(lessonId=" + XLModConfig.getHwLessonId() + ")", "getQuestions",
                                new Class[]{int.class, String.class, int.class, String.class, String.class, String.class, String.class, int.class, int.class},
                                new Object[]{0, XLModConfig.getHwLessonId(), 1, "", "", "", "0", 1, 20});
                    } else {
                        XLModConfig.logAppend("[诊断] getQuestions 跳过（未填课时ID）");
                    }
                    android.widget.Toast.makeText(act, "诊断已写入日志", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    XLModConfig.logAppend("[诊断] 异常: " + t);
                }
            }
        });
    }

    /** 反射读取无参方法（stub 未声明的接口用） */
    private static String callStr(Object target, String method) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            Object r = m.invoke(target);
            return String.valueOf(r);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    /** 反射调用 net.xuele.xuelets.homework.util.Api.ready 上的接口方法，回调用动态代理接住并写日志 */
    private static void probeApi(final String tag, String method, Class[] types, Object[] args) {
        try {
            Class<?> apiCls = Class.forName("net.xuele.xuelets.homework.util.Api");
            Object ready = apiCls.getField("ready").get(null);
            java.lang.reflect.Method m = apiCls.getMethod(method, types);
            Object call = m.invoke(ready, args);
            if (call == null) {
                XLModConfig.logAppend("[诊断] " + tag + " → 返回调用对象为空");
                return;
            }
            Class<?> cbCls = Class.forName("net.xuele.android.core.http.callback.ReqCallBackV2");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method mm, Object[] a) {
                            String n = mm.getName();
                            if (n.equals("onReqSuccess")) {
                                XLModConfig.logAppend("[诊断] " + tag + " → 成功: "
                                        + briefJson(a == null || a.length == 0 ? null : a[0]));
                            } else if (n.equals("onReqFailed")) {
                                String code = a != null && a.length >= 1 ? String.valueOf(a[0]) : "";
                                String msg = a != null && a.length >= 2 ? String.valueOf(a[1]) : "";
                                XLModConfig.logAppend("[诊断] " + tag + " → 失败: code=" + code + " msg=" + msg);
                            }
                            return null;
                        }
                    });
            java.lang.reflect.Method req = call.getClass().getMethod("requestV2", cbCls);
            req.invoke(call, cb);
            XLModConfig.logAppend("[诊断] " + tag + " → 请求已发起");
        } catch (Throwable t) {
            XLModConfig.logAppend("[诊断] " + tag + " → 异常: " + t);
        }
    }

    /** 模型转 JSON（用 App 自带 JsonUtil，失败则退化为 toString），并截断 */
    private static String briefJson(Object o) {
        if (o == null) return "null（空响应）";
        String s = null;
        try {
            Class<?> ju = Class.forName("net.xuele.android.common.tools.JsonUtil");
            for (java.lang.reflect.Method mm : ju.getMethods()) {
                if (mm.getName().equals("objectToJson") && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == Object.class) {
                    Object r = mm.invoke(null, o);
                    if (r != null) {
                        s = String.valueOf(r);
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (s == null) {
            try {
                s = String.valueOf(o);
            } catch (Throwable t) {
                s = "<toString异常>";
            }
        }
        return s.length() > 1500 ? s.substring(0, 1500) + "...(截断)" : s;
    }

    // ================= 发作业修复：目标数据抓取（班级/学生/教师） =================
    private static Activity sTopActivity;
    private static Activity sBookActivity = null;
    /** 当前前台 Activity（onActivityStopped 时清空）：循环计时器只在前台执行任务 */
    private static Activity sForeground = null;
    /** 循环计时器：每 5 秒检查一次"是否到点"，到点则调用对应功能 */
    private static android.os.Handler sTimerHandler = null;
    private static Runnable sTimerTask = null;
    private static boolean sTimerRunning = false;
    private static long sTimerStartedAt = 0;
    private static long sTimerTicks = 0;
    private static final java.util.ArrayList<Activity> sRecent = new java.util.ArrayList<>();
    private static boolean sLifecycleInstalled = false;
    private static android.os.Handler sAutoHandler = null;
    private static Runnable sAutoTask = null;
    private static boolean sAutoRunning = false;
    private static String sLastCounts = "";
    private static long sLastFilterTry = 0;
    private static String sLastClassSummary = "";
    private static String sLastUnitsLog = "";
    private static String sLastShortLog = "";

    private static void rememberActivity(Activity a) {
        try {
            if (a == null) return;
            synchronized (sRecent) {
                sRecent.remove(a);
                sRecent.add(a);
                while (sRecent.size() > 8) sRecent.remove(0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forgetActivity(Activity a) {
        try {
            synchronized (sRecent) {
                sRecent.remove(a);
            }
        } catch (Throwable ignored) {
        }
    }

    private static java.util.List<Activity> recentActivities() {
        synchronized (sRecent) {
            return new java.util.ArrayList<>(sRecent);
        }
    }

    /** App 是否在前台（远程配置 1 分钟轮询用） */
    public static boolean isAppForeground() {
        return sTopActivity != null;
    }

    public static boolean isAutoCaptureRunning() {
        return sAutoRunning;
    }

    /** 开始/停止自动抓取（每 2 秒一次，增量合并；换班级/年级无需再点） */
    public static void toggleAutoCapture(Activity act) {
        toast(act, "自动抓取已移除：抓取时会自动补全学生名单");
    }

    /** 自动抓取已移除：抓取时即补全学生名单 */
    public static void startAutoCapture(final Activity act) {
        XLModConfig.logAppend("[抓取] 自动抓取已移除（抓取时会自动补全学生名单）");
    }

    public static void stopAutoCapture(Activity act) {
        sAutoRunning = false;
        try {
            if (sAutoHandler != null && sAutoTask != null) sAutoHandler.removeCallbacks(sAutoTask);
        } catch (Throwable ignored) {
        }
        XLModConfig.logAppend("[抓取] 已停止自动抓取");
        toast(act, "已停止自动抓取");
    }

    private static boolean sFetchingStudents = false;

    /** 批量补学生名单：对 classes[] 里还没有名单的班，串行调 studentListOfGroup(年级ID, 班级ID, null) */
    public static void captureStudentsForClasses(final Activity act, final int maxClasses) {
        if (sFetchingStudents) return;
        sFetchingStudents = true;
        final JSONObject root = currentJson();
        final JSONArray cs = root.optJSONArray("classes");
        if (cs == null || cs.length() == 0) {
            sFetchingStudents = false;
            XLModConfig.logAppend("[抓取] 补名单跳过：本地无班级");
            return;
        }
        final java.util.HashSet<String> have = new java.util.HashSet<>();
        JSONArray ss = root.optJSONArray("students");
        if (ss != null) {
            for (int i = 0; i < ss.length(); i++) {
                JSONObject st = ss.optJSONObject(i);
                if (st != null) have.add(st.optString("classId"));
            }
        }
        final java.util.List<int[]> todo = new java.util.ArrayList<>();
        final java.util.List<String> gids = new java.util.ArrayList<>();
        final java.util.List<String> cids = new java.util.ArrayList<>();
        int scanned = 0;
        for (int i = 0; i < cs.length() && (maxClasses <= 0 || todo.size() < maxClasses); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            String cid = c.optString("classId");
            String gid = c.optString("gradeId");
            if (cid.isEmpty() || gid.isEmpty()) continue;
            scanned++;
            if (have.contains(gid + cid)) continue;
            todo.add(new int[]{i});
            gids.add(gid);
            cids.add(cid);
        }
        if (todo.isEmpty()) {
            sFetchingStudents = false;
            XLModConfig.logAppend("[抓取] 补名单完成：所有班级已有名单（扫描 " + scanned + " 个班）");
            return;
        }
        XLModConfig.logAppend("[抓取] 补名单开始：待补 " + todo.size() + " 个班"
                + (maxClasses <= 0 ? "（全部）" : "（上限 " + maxClasses + "）"));
        fetchStudentsSeq(root, act, gids, cids, 0, 0, 0);
    }

    private static void fetchStudentsSeq(final JSONObject root, final Activity act, final java.util.List<String> gids,
                                         final java.util.List<String> cids, final int index, final int ok, final int empty) {
        if (index >= gids.size()) {
            sFetchingStudents = false;
            saveTargetsThrottled(root, true);
            sClassCache = null;
            updateFloatText();
            int total = len(root.optJSONArray("students"));
            XLModConfig.logAppend("[抓取] 补名单完成：成功 " + ok + " 班 / 空 " + empty + " 班，学生累计 " + total);
            if (act != null) toast(act, "学生名单补全：" + ok + " 班，累计 " + total + " 人");
            return;
        }
        final String gid = gids.get(index);
        final String cid = cids.get(index);
        callApi("net.xuele.im.util.Api", "studentListOfGroup", new Class[]{String.class, String.class, String.class},
                new Object[]{gid, cid, null}, "学生名单",
                new Sink() {
                    @Override
                    public void onSuccess(Object resp) {
                        int n = 0;
                        try {
                            Object wrapper = firstFieldOfType(resp == null ? Object.class : resp.getClass(), resp, java.util.List.class);
                            if (wrapper instanceof java.util.List) {
                                JSONObject info = new JSONObject();
                                JSONArray arr = new JSONArray();
                                for (Object item : (java.util.List) wrapper) {
                                    JSONObject st = new JSONObject();
                                    st.put("objectId", fieldStr(item, "objectId"));
                                    st.put("name", fieldStr(item, "name"));
                                    st.put("remark", fieldStr(item, "remark"));
                                    st.put("classId", gid + cid);
                                    arr.put(st);
                                    n++;
                                }
                                info.put("students", arr);
                                mergeArray(root, info, "students", "objectId");
                            }
                        } catch (Throwable t) {
                            XLModConfig.logAppend("[抓取] 补名单解析异常(" + cid + "): " + t);
                        }
                        XLModConfig.logAppend("[抓取] 补名单: " + cid + " → " + n + " 人");
                        fetchStudentsSeq(root, act, gids, cids, index + 1, ok + (n > 0 ? 1 : 0), empty + (n > 0 ? 0 : 1));
                    }

                    @Override
                    public void onFail(String code, String msg) {
                        XLModConfig.logAppend("[抓取] 补名单失败(" + cid + "): code=" + code + " msg=" + msg);
                        fetchStudentsSeq(root, act, gids, cids, index + 1, ok, empty + 1);
                    }
                });
    }

    /** 备用班级/科目/教材版本来源：workPageFilter（学生自己的作业筛选数据，学生账号可用） */
    public static void captureClassesViaWorkFilter() {
        try {
            Class<?> apiCls = Class.forName("net.xuele.xuelets.homework.util.Api");
            Object ready = apiCls.getField("ready").get(null);
            Class<?> cbCls = Class.forName("net.xuele.android.core.http.callback.ReqCallBackV2");
            java.lang.reflect.Method m = apiCls.getMethod("workPageFilter", String.class, String.class);
            Object call = m.invoke(ready, null, null);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final JSONArray cls = new JSONArray();
            final JSONArray subs = new JSONArray();
            final JSONArray eds = new JSONArray();
            final String[] err = new String[]{"", ""};
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method mm, Object[] a) {
                            String n = mm.getName();
                            if (n.equals("onReqSuccess")) {
                                try {
                                    Object resp = a == null || a.length == 0 ? null : a[0];
                                    fillFilterList(callObj(resp, "getClassList"), cls);
                                    fillFilterList(callObj(resp, "getSubjectList"), subs);
                                    fillFilterList(callObj(resp, "getDbVersionList"), eds);
                                } catch (Throwable ignored) {
                                }
                                latch.countDown();
                            } else if (n.equals("onReqFailed")) {
                                err[0] = a != null && a.length >= 1 ? String.valueOf(a[0]) : "";
                                err[1] = a != null && a.length >= 2 ? String.valueOf(a[1]) : "";
                                latch.countDown();
                            }
                            return null;
                        }
                    });
            call.getClass().getMethod("requestV2", cbCls).invoke(call, cb);
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
            if (cls.length() == 0 && subs.length() == 0) {
                XLModConfig.logAppend("[抓取] workPageFilter 兜底无数据: code=" + err[0] + " msg=" + err[1]);
                return;
            }
            JSONObject root = currentJson();
            JSONObject info = new JSONObject();
            info.put("classes", cls);
            mergeArray(root, info, "classes", "classId");
            JSONObject mats = root.optJSONObject("materials");
            if (mats == null) mats = new JSONObject();
            if (subs.length() > 0) mats.put("subjects", subs);
            if (eds.length() > 0) mats.put("editions", eds);
            root.put("materials", mats);
            XLModConfig.setHwTargetsJson(root.toString());
            updateFloatText();
            XLModConfig.logAppend("[抓取] workPageFilter 兜底: 班级=" + cls.length() + " 科目=" + subs.length()
                    + " 教材版本=" + eds.length());
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] captureClassesViaWorkFilter 异常: " + t);
        }
    }

    private static void fillFilterList(Object list, JSONArray out) {
        try {
            if (!(list instanceof java.util.List)) return;
            for (Object o : (java.util.List) list) {
                JSONObject j = new JSONObject();
                String id = fieldStr(o, "id");
                if (id.isEmpty()) id = String.valueOf(callStr(o, "getId"));
                String name = fieldStr(o, "name");
                if (name.isEmpty()) name = String.valueOf(callStr(o, "getName"));
                if (name.isEmpty() || "null".equals(name)) name = fieldStr(o, "title");
                if (id.isEmpty() && (name.isEmpty() || "null".equals(name))) continue;
                j.put("classId", id);
                j.put("className", name);
                j.put("gradeId", "");
                j.put("source", "workPageFilter");
                out.put(j);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一次全量抓取（目标班级/学生/教师 + 课本），增量合并进本地 JSON；silent=true 时仅在数量变化时写日志 */
    public static void captureAll(boolean silent) {
        try {
            bc("captureAll 开始");
            JSONObject root = currentJson();
            migrateGradesOutOfClasses(root);
            JSONObject targets = new JSONObject(captureTargetsJson());
            mergeArray(root, targets, "grades", "id");
            mergeArray(root, targets, "classes", "classId");
            mergeArray(root, targets, "students", "objectId");
            mergeArray(root, targets, "teachers", "objectId");
            // 通知选择页没抓到班级时，用学生可用的作业筛选接口兜底（每 30 秒最多一次）
            if (len(root.optJSONArray("classes")) == 0
                    && System.currentTimeMillis() - sLastFilterTry > 30000) {
                sLastFilterTry = System.currentTimeMillis();
                captureClassesViaWorkFilter();
                root = currentJson();
            }
            JSONObject booksInfo = collectBooksNow();
            mergeBooksKeepUnits(root, booksInfo);
            JSONObject mats = booksInfo.optJSONObject("materials");
            if (mats != null && mats.length() > 0) root.put("materials", mats);
            root.put("capturedAt", System.currentTimeMillis());
            saveTargetsThrottled(root, false);
            bc("captureAll 保存完成");
            updateFloatText();
            String counts = "年级=" + len(root.optJSONArray("grades")) + " 班级=" + len(root.optJSONArray("classes"))
                    + " 学生=" + len(root.optJSONArray("students")) + " 教师=" + len(root.optJSONArray("teachers"))
                    + " 课本=" + len(root.optJSONArray("books")) + " 课时=" + countLessons(root.optJSONArray("books"));
            if (!counts.equals(sLastCounts)) {
                sLastCounts = counts;
                XLModConfig.logAppend("[抓取] 抓取更新: " + counts);
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] captureAll 异常: " + t);
        }
    }

    /** 课本合并：同 bookId 时，若新数据没有 units/lessons 则保留旧的（避免"课时被空数据覆盖"） */
    private static void mergeBooksKeepUnits(JSONObject root, JSONObject info) {
        try {
            JSONArray neu = info.optJSONArray("books");
            if (neu == null || neu.length() == 0) return;
            JSONArray old = root.optJSONArray("books");
            java.util.LinkedHashMap<String, JSONObject> merged = new java.util.LinkedHashMap<>();
            if (old != null) {
                for (int i = 0; i < old.length(); i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o != null) merged.put(o.optString("bookId"), o);
                }
            }
            int add = 0, upd = 0, kept = 0;
            for (int i = 0; i < neu.length(); i++) {
                JSONObject n = neu.optJSONObject(i);
                if (n == null) continue;
                String id = n.optString("bookId");
                if (id.isEmpty()) continue;
                JSONObject prev = merged.get(id);
                int newLessons = countLessonsIn(n.optJSONArray("units"));
                if (prev != null) {
                    int oldLessons = countLessonsIn(prev.optJSONArray("units"));
                    if (newLessons == 0 && oldLessons > 0) {
                        // 新数据没课时 → 保留旧 units，仅更新其他字段
                        for (String k : new String[]{"bookName", "subjectId", "subjectName", "gradeNum", "gradeName", "lastLessonId"}) {
                            if (!n.optString(k).isEmpty()) prev.put(k, n.optString(k));
                        }
                        kept++;
                        upd++;
                        continue;
                    }
                    upd++;
                } else {
                    add++;
                }
                merged.put(id, n);
            }
            JSONArray out = new JSONArray();
            for (JSONObject o : merged.values()) out.put(o);
            root.put("books", out);
            XLModConfig.logAppend("[抓取] 合并 books：新增 " + add + " 更新 " + upd + "（其中保留旧课时 " + kept
                    + "）合计 " + out.length() + " 课时总数 " + countLessons(out));
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] mergeBooksKeepUnits 异常: " + t);
        }
    }

    private static final java.util.HashMap<String, String> sLastMergeSummary = new java.util.HashMap<>();

    /** 富合并单条：新值优先，但新值为空时保留旧值；books 的 units/lessons 取并集 */
    private static JSONObject enrich(JSONObject oldO, JSONObject newO) {
        try {
            JSONObject out = new JSONObject();
            java.util.Iterator<String> it = oldO.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, oldO.get(k));
            }
            java.util.Iterator<String> it2 = newO.keys();
            while (it2.hasNext()) {
                String k = it2.next();
                Object v = newO.get(k);
                if (isEmptyValue(v)) continue;      // 新值为空 → 保留旧值
                out.put(k, v);
            }
            // 课本：units/lessons 取并集（避免不同来源互相覆盖导致课时丢失）
            if (newO.has("units")) {
                JSONArray u = unionUnits(oldO.optJSONArray("units"), newO.optJSONArray("units"));
                if (u.length() > 0) out.put("units", u);
            }
            return out;
        } catch (Throwable t) {
            return newO;
        }
    }

    private static boolean isEmptyValue(Object v) {
        if (v == null) return true;
        if (v instanceof String) return ((String) v).isEmpty();
        if (v instanceof JSONArray) return ((JSONArray) v).length() == 0;
        return false;
    }

    /** 单元/课时并集：按 unitId、lessonId 去重，旧有新无的保留 */
    private static JSONArray unionUnits(JSONArray oldU, JSONArray newU) {
        JSONArray out = new JSONArray();
        try {
            java.util.LinkedHashMap<String, JSONObject> units = new java.util.LinkedHashMap<>();
            if (oldU != null) {
                for (int i = 0; i < oldU.length(); i++) {
                    JSONObject u = oldU.optJSONObject(i);
                    if (u != null) units.put(u.optString("unitId"), u);
                }
            }
            if (newU != null) {
                for (int i = 0; i < newU.length(); i++) {
                    JSONObject n = newU.optJSONObject(i);
                    if (n == null) continue;
                    String id = n.optString("unitId");
                    JSONObject prev = units.get(id);
                    if (prev == null) {
                        units.put(id, n);
                    } else {
                        JSONObject m = new JSONObject();
                        java.util.Iterator<String> it = prev.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            m.put(k, prev.get(k));
                        }
                        java.util.Iterator<String> it2 = n.keys();
                        while (it2.hasNext()) {
                            String k = it2.next();
                            Object v = n.get(k);
                            if (isEmptyValue(v)) continue;
                            if ("lessons".equals(k)) {
                                m.put("lessons", unionLessons(prev.optJSONArray("lessons"), n.optJSONArray("lessons")));
                            } else {
                                m.put(k, v);
                            }
                        }
                        units.put(id, m);
                    }
                }
            }
            for (JSONObject u : units.values()) out.put(u);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONArray unionLessons(JSONArray oldL, JSONArray newL) {
        JSONArray out = new JSONArray();
        try {
            java.util.LinkedHashMap<String, JSONObject> m = new java.util.LinkedHashMap<>();
            if (oldL != null) {
                for (int i = 0; i < oldL.length(); i++) {
                    JSONObject l = oldL.optJSONObject(i);
                    if (l != null) m.put(l.optString("lessonId"), l);
                }
            }
            if (newL != null) {
                for (int i = 0; i < newL.length(); i++) {
                    JSONObject l = newL.optJSONObject(i);
                    if (l != null && !l.optString("lessonId").isEmpty()) m.put(l.optString("lessonId"), l);
                }
            }
            for (JSONObject l : m.values()) out.put(l);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 旧数据迁移：把早期误存进 classes[] 的"年级"（source=mGradeGroupOfSchool）挪到 grades[] */
    private static void migrateGradesOutOfClasses(JSONObject root) {
        try {
            JSONArray cs = root.optJSONArray("classes");
            if (cs == null || cs.length() == 0) return;
            JSONArray keep = new JSONArray();
            JSONArray moved = new JSONArray();
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) continue;
                String src = c.optString("source", "");
                if (src.contains("mGradeGroupOfSchool")) {
                    JSONObject g = new JSONObject();
                    g.put("id", c.optString("classId"));
                    g.put("name", c.optString("className"));
                    g.put("count", c.optString("count"));
                    g.put("source", src);
                    moved.put(g);
                } else {
                    keep.put(c);
                }
            }
            if (moved.length() == 0) return;
            root.put("classes", keep);
            JSONObject info = new JSONObject();
            info.put("grades", moved);
            mergeArray(root, info, "grades", "id");
            XLModConfig.logAppend("[抓取] 迁移：classes → grades 共 " + moved.length() + " 条（原为年级）");
        } catch (Throwable ignored) {
        }
    }

    // ================= 面包屑（崩溃时随日志输出，便于定位最后一步） =================
    private static final java.util.ArrayDeque<String> sBreadcrumbs = new java.util.ArrayDeque<>();

    public static void bc(String step) {
        try {
            synchronized (sBreadcrumbs) {
                sBreadcrumbs.addLast(System.currentTimeMillis() % 100000 + " " + step);
                while (sBreadcrumbs.size() > 30) sBreadcrumbs.removeFirst();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String breadcrumbText() {
        try {
            synchronized (sBreadcrumbs) {
                StringBuilder sb = new StringBuilder();
                for (String x : sBreadcrumbs) sb.append("  · ").append(x).append('\n');
                return sb.length() == 0 ? "  (无)" : sb.toString();
            }
        } catch (Throwable t) {
            return "  (读取失败)";
        }
    }

    /** 检测外部注入的布局检查/Xposed 类模块（已知在 Android 14 上崩溃） */
    public static void checkExternalHooks() {
        try {
            String[] bad = {"com.flass.layoutinspect.hook.window.entry.e", "com.flass.layoutinspect.LayoutInspect"};
            for (String n : bad) {
                try {
                    Class.forName(n);
                    XLModConfig.logAppend("[警告] 检测到外部类模块 " + n
                            + "（会 hook View.onAttachedToWindow 并在部分系统上崩溃）→ 请在 Xposed/LSPosed 中关闭它，否则会持续闪退");
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static long sLastSaveAt = 0;

    /** 保存节流：普通情况 15 秒最多写一次；force=true 立即写并做保存自检 */
    public static void saveTargetsThrottled(JSONObject root, boolean force) {
        try {
            long now = System.currentTimeMillis();
            if (!force && now - sLastSaveAt < 15000) return;
            sLastSaveAt = now;
            XLModConfig.setHwTargetsJson(root == null ? currentJson().toString() : root.toString());
            if (force) XLModConfig.logSaveResult();
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 保存异常: " + t);
        }
    }

    /** 对外：把 src 的某个数组按 key 富合并进 target（供 XLModConfig 从文件回读时使用） */
    public static void mergeExternal(JSONObject target, JSONObject src, String field, String key) {
        mergeArray(target, src, field, key);
    }

    /** 富合并：同 key 时以新值为主，但旧值里"新值为空"的字段会被保留；books 的 units/lessons 取并集 */
    private static void mergeArray(JSONObject root, JSONObject src, String field, String key) {
        try {
            JSONArray neu = src.optJSONArray(field);
            if (neu == null || neu.length() == 0) return;
            JSONArray old = root.optJSONArray(field);
            java.util.LinkedHashMap<String, JSONObject> merged = new java.util.LinkedHashMap<>();
            if (old != null) {
                for (int i = 0; i < old.length(); i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o == null) continue;
                    merged.put(o.optString(key), o);
                }
            }
            int add = 0, upd = 0;
            for (int i = 0; i < neu.length(); i++) {
                JSONObject n = neu.optJSONObject(i);
                if (n == null) continue;
                String k = n.optString(key);
                if (k.isEmpty()) continue;
                JSONObject prev = merged.get(k);
                if (prev == null) {
                    merged.put(k, n);
                    add++;
                } else {
                    merged.put(k, enrich(prev, n));
                    upd++;
                }
            }
            JSONArray out = new JSONArray();
            for (JSONObject o : merged.values()) out.put(o);
            root.put(field, out);
            String summary = field + "：新增 " + add + " 更新 " + upd + " 合计 " + out.length();
            if (!summary.equals(sLastMergeSummary.get(field))) {
                sLastMergeSummary.put(field, summary);
                XLModConfig.logAppend("[抓取] 合并 " + summary);
            }
        } catch (Throwable ignored) {
        }
    }
    private static View sTargetFloatView = null;
    private static android.widget.TextView sTargetFloatText = null;
    private static Activity sFloatHost = null;

    /** 注册 Activity 生命周期跟踪（用于把应用内悬浮窗贴到当前页面，含通知选择页） */
    public static void installLifecycle(Activity act) {
        if (sLifecycleInstalled || act == null) return;
        try {
            android.app.Application app = act.getApplication();
            if (app == null) return;
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) {
                    rememberActivity(a);
                    if (isBookPage(a)) sBookActivity = a;
                }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityResumed(Activity a) {
                    sTopActivity = a;
                    sForeground = a;
                    rememberActivity(a);
                    if (isBookPage(a)) sBookActivity = a;
                    if (sTargetFloatView != null) attachFloatTo(a);
                }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) {
                    if (sForeground == a) sForeground = null;
                }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) {
                    forgetActivity(a);
                    if (sFloatHost == a) {
                        sTargetFloatView = null;
                        sTargetFloatText = null;
                        sFloatHost = null;
                    }
                }
            });
            sLifecycleInstalled = true;
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 生命周期注册失败: " + t);
        }
    }

    // ================= 循环计时器（每 5 秒检查一次时间） =================
    private static final long TIMER_INTERVAL_MS = 5000;
    /** 自动签到的重试节流：接口失败时不要每跳（5 秒）重打一次 */
    private static final long SIGN_THROTTLE_MS = 30000;
    private static long sTimerLastSignTry = 0;

    /** 面板/日志用：计时器是否在跑 */
    public static boolean isTimerRunning() {
        return sTimerRunning;
    }

    /** 计时器已运行时长（毫秒） */
    public static long timerUptimeMs() {
        if (!sTimerRunning || sTimerStartedAt <= 0) return 0;
        return System.currentTimeMillis() - sTimerStartedAt;
    }

    /** 启动 5 秒循环计时器（幂等；主线程 Handler，App 进程存活期间一直转） */
    public static void startTimer(final Activity act) {
        if (sTimerRunning) return;
        try {
            XLModConfig.init(act);
            sTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            sTimerTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        timerTick();
                    } catch (Throwable t) {
                    }
                    try {
                        if (sTimerRunning && sTimerHandler != null) {
                            sTimerHandler.postDelayed(this, TIMER_INTERVAL_MS);
                        }
                    } catch (Throwable t) {
                    }
                }
            };
            sTimerRunning = true;
            sTimerStartedAt = System.currentTimeMillis();
            sTimerTicks = 0;
            sTimerHandler.postDelayed(sTimerTask, TIMER_INTERVAL_MS);
            XLModConfig.logAppend("[计时器] 已启动：每 " + (TIMER_INTERVAL_MS / 1000) + " 秒检查一次（到点自动执行 签到/打榜/云朵）");
        } catch (Throwable t) {
            XLModConfig.logAppend("[计时器] 启动失败: " + t);
        }
    }

    /** 停止计时器（面板上关闭开关后可调用；重启 App 会重新启动） */
    public static void stopTimer() {
        try {
            sTimerRunning = false;
            if (sTimerHandler != null && sTimerTask != null) sTimerHandler.removeCallbacks(sTimerTask);
        } catch (Throwable t) {
        }
        XLModConfig.logAppend("[计时器] 已停止");
    }

    /** 面板状态行文字 */
    public static String timerStatusText() {
        if (!sTimerRunning) return "未启动（打开任一页面会自动启动）";
        long s = timerUptimeMs() / 1000;
        return "运行中：每 5 秒检查一次（已运行 " + (s / 60) + " 分 " + (s % 60) + " 秒，第 " + sTimerTicks + " 跳）";
    }

    /** 每一跳：只有"开关打开 + 前台有页面 + 确实到点"才做事；否则静默，不刷日志 */
    private static void timerTick() {
        sTimerTicks++;
        try {
            if (!XLModConfig.isTimerEnabled()) return;
            Activity act = sForeground != null ? sForeground : sTopActivity;
            if (act == null) return;
            long now = System.currentTimeMillis();
            // 云朵助手 / 自动打榜：内部各自判断"开关 + 到点 + 当日已完成"，不满足直接 return（无网络请求）
            try {
                autoCloudIfNeeded(act);
            } catch (Throwable t) {
            }
            try {
                autoChallengeTick(act);
            } catch (Throwable t) {
            }
            // 自动签到：30 秒节流 —— 签到接口失败时不能 5 秒重打一次；成功后当天不再触发
            if (XLModConfig.isAutoSign() && now - sTimerLastSignTry >= SIGN_THROTTLE_MS) {
                sTimerLastSignTry = now;
                try {
                    autoSignIfNeeded(act);
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
        }
    }

    /** 打开通知"选择发送对象"页；type: 3=学生(默认) 4=班级 0=教师 2=学校 1=教育管理 */
    public static void launchTargetPicker(Activity act, int type) {
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(act, "net.xuele.im.activity.NotificationSendTargetActivity");
            i.putExtra("PARAM_TARGET_TYPE", type);
            act.startActivity(i);
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 打开选择页失败: " + t);
            toast(act, "打开选择页失败: " + t);
        }
    }

    public static void launchTargetPicker(Activity act) {
        launchTargetPicker(act, XLModConfig.getHwTargetType());
    }

    private static void toast(Activity act, String s) {
        try {
            android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 显示/隐藏抓取悬浮窗（贴在当前页面） */
    public static boolean isTargetFloatVisible() {
        return sTargetFloatView != null;
    }

    public static void toggleTargetFloat(Activity act) {
        if (sTargetFloatView != null) {
            detachFloat();
            return;
        }
        buildFloat(act);
    }

    public static void showTargetFloat(Activity act) {
        if (sTargetFloatView == null) buildFloat(act);
        else attachFloatTo(sTopActivity != null ? sTopActivity : act);
    }

    private static void buildFloat(final Activity act) {
        try {
            final LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xE6202124);
            bg.setCornerRadius(dp(act, 10));
            bg.setStroke(dp(act, 1), 0x66FFFFFF);
            box.setBackground(bg);
            box.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));

            android.widget.TextView title = new android.widget.TextView(act);
            title.setText("发作业抓取");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(13);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            box.addView(title);

            sTargetFloatText = new android.widget.TextView(act);
            sTargetFloatText.setTextColor(0xFFE6E6E6);
            sTargetFloatText.setTextSize(11);
            sTargetFloatText.setMaxLines(8);
            box.addView(sTargetFloatText);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(floatButton(act, "抓取", new View.OnClickListener() {
                @Override public void onClick(View v) { captureTargets(sTopActivity != null ? sTopActivity : act); }
            }));
            row.addView(floatButton(act, "注入", new View.OnClickListener() {
                @Override public void onClick(View v) { forceInjectIntoTopActivity(); }
            }));
            row.addView(floatButton(act, "导出", new View.OnClickListener() {
                @Override public void onClick(View v) { exportTargets(sTopActivity != null ? sTopActivity : act); }
            }));
            row.addView(floatButton(act, "收起", new View.OnClickListener() {
                @Override public void onClick(View v) { detachFloat(); }
            }));
            box.addView(row);

            // 拖动
            box.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            dx = e.getRawX() - v.getX();
                            dy = e.getRawY() - v.getY();
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE:
                            v.setX(e.getRawX() - dx);
                            v.setY(e.getRawY() - dy);
                            return true;
                    }
                    return false;
                }
            });

            sTargetFloatView = box;
            updateFloatText();
            attachFloatTo(sTopActivity != null ? sTopActivity : act);
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 悬浮窗创建失败: " + t);
        }
    }

    private static android.widget.TextView floatButton(Activity act, String text, View.OnClickListener l) {
        android.widget.TextView tv = new android.widget.TextView(act);
        tv.setText(text);
        tv.setTextColor(0xFF7EC8FF);
        tv.setTextSize(11);
        tv.setPadding(dp(act, 8), dp(act, 5), dp(act, 8), dp(act, 5));
        tv.setOnClickListener(l);
        return tv;
    }

    private static void attachFloatTo(Activity act) {
        try {
            if (act == null || sTargetFloatView == null) return;
            if (sFloatHost == act) return;
            detachFloat();
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    dp(act, 210), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            lp.topMargin = dp(act, 90);
            lp.rightMargin = dp(act, 8);
            decor.addView(sTargetFloatView, lp);
            sFloatHost = act;
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 悬浮窗挂载失败: " + t);
        }
    }

    private static void detachFloat() {
        try {
            if (sTargetFloatView != null && sTargetFloatView.getParent() instanceof ViewGroup) {
                ((ViewGroup) sTargetFloatView.getParent()).removeView(sTargetFloatView);
            }
        } catch (Throwable ignored) {
        }
        sFloatHost = null;
    }

    private static void updateFloatText() {
        try {
            if (sTargetFloatText == null) return;
            String json = XLModConfig.getHwTargetsJson();
            if (json == null || json.isEmpty()) {
                sTargetFloatText.setText("尚未抓到数据。\n先点面板「抓捕学生和教师信息」打开选择页，点年级 → 点班级，再回来点「抓取」。");
                return;
            }
            JSONObject o = new JSONObject(json);
            sTargetFloatText.setText("修复开关：" + (XLModConfig.isHwFixEnabled() ? "开" : "关（注入不会生效）")
                    + "\n年级 " + len(o.optJSONArray("grades"))
                    + " / 班级 " + len(o.optJSONArray("classes"))
                    + " / 学生 " + len(o.optJSONArray("students"))
                    + " / 教师 " + len(o.optJSONArray("teachers"))
                    + "\n课本 " + len(o.optJSONArray("books"))
                    + " / 课时 " + countLessons(o.optJSONArray("books"))
                    + "\n" + preview(o.optJSONArray("classes"), "班级")
                    + "\n" + preview(o.optJSONArray("students"), "学生")
                    + "\n" + preview(o.optJSONArray("books"), "课本"));
        } catch (Throwable t) {
            sTargetFloatText.setText("数据解析失败: " + t);
        }
    }

    private static int len(JSONArray a) {
        return a == null ? 0 : a.length();
    }

    private static String preview(JSONArray arr, String label) {
        if (arr == null || arr.length() == 0) return label + "：无";
        StringBuilder sb = new StringBuilder(label).append("：");
        for (int i = 0; i < arr.length() && i < 4; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (i > 0) sb.append("、");
            String nm = o.optString("name", "");
            if (nm.isEmpty()) nm = o.optString("className", "");
            if (nm.isEmpty()) nm = o.optString("bookName", "?");
            sb.append(nm);
        }
        if (arr.length() > 4) sb.append("…(共").append(arr.length()).append(")");
        return sb.toString();
    }

    /** 抓取：从通知选择页的仓库缓存中读年级/班级/学生/教师，落盘 JSON 并刷新悬浮窗 */
    /** 抓取（合并语义）：只把本次抓到的数据并入本地，绝不覆盖课本/课时等已有内容 */
    public static void captureTargets(final Activity act) {
        try {
            JSONObject targets = new JSONObject(captureTargetsJson());
            JSONObject root = currentJson();
            mergeArray(root, targets, "grades", "id");
            mergeArray(root, targets, "classes", "classId");
            mergeArray(root, targets, "students", "objectId");
            mergeArray(root, targets, "teachers", "objectId");
            if (targets.has("targetDiag")) root.put("targetDiag", targets.optString("targetDiag"));
            saveTargetsThrottled(root, true);
            sClassCache = null;
            updateFloatText();
            String msg = "已合并抓取：年级 " + len(root.optJSONArray("grades")) + " / 班级 " + len(root.optJSONArray("classes"))
                    + " / 学生 " + len(root.optJSONArray("students")) + " / 教师 " + len(root.optJSONArray("teachers"))
                    + " / 课本 " + len(root.optJSONArray("books")) + " / 课时 " + countLessons(root.optJSONArray("books"));
            XLModConfig.logAppend("[抓取] " + msg);
            toast(act, msg);
            captureStudentsForClasses(act, 0);
            try {
                Activity top = sTopActivity;
                if (top != null && top.getClass().getName().contains("AssignHomeworkActivity")) {
                    forceInjectIntoTopActivity();
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 失败: " + t);
            toast(act, "抓取失败: " + t);
        }
    }

    /** 反射读取（类型匹配，抗字段改名）：年级=GradeRepo.mDataList；班级/学生/教师=各 RepoCallCache.mResultCache */
    public static String captureTargetsJson() {
        JSONObject root = new JSONObject();
        JSONArray grades = new JSONArray();
        JSONArray classes = new JSONArray();
        JSONArray students = new JSONArray();
        JSONArray teachers = new JSONArray();
        java.util.IdentityHashMap<Object, Object> seen = new java.util.IdentityHashMap<>();
        try {
            root.put("capturedAt", System.currentTimeMillis());
            // 年级：BasePresenter$Filter$GradeRepo.getInstance().<List 字段>
            try {
                Class<?> gradeRepo = Class.forName("net.xuele.im.util.notification.BasePresenter$Filter$GradeRepo");
                Object inst = gradeRepo.getMethod("getInstance").invoke(null);
                Object list = firstFieldOfType(gradeRepo, inst, java.util.List.class);
                if (list instanceof java.util.List) {
                    for (Object kv : (java.util.List) list) {
                        JSONObject g = new JSONObject();
                        g.put("id", String.valueOf(callStr(kv, "getKey")));
                        g.put("name", String.valueOf(callStr(kv, "getValue")));
                        grades.put(g);
                    }
                }
            } catch (Throwable t) {
                root.put("gradeError", String.valueOf(t));
            }
            // 班级：BasePresenter$Filter$ClassRepo.getInstance().<Map 字段> → ClassItem(<String gradeId>, <List 字段>)
            try {
                Class<?> classRepo = Class.forName("net.xuele.im.util.notification.BasePresenter$Filter$ClassRepo");
                Object inst = classRepo.getMethod("getInstance").invoke(null);
                Object map = firstFieldOfType(classRepo, inst, java.util.Map.class);
                if (map instanceof java.util.Map) {
                    for (Object e : ((java.util.Map) map).entrySet()) {
                        Object key = ((java.util.Map.Entry) e).getKey();
                        Object item = ((java.util.Map.Entry) e).getValue();
                        if (item == null) continue;
                        Class<?> ic = item.getClass();
                        String gradeId = String.valueOf(firstFieldOfType(ic, item, String.class));
                        Object list = firstFieldOfType(ic, item, java.util.List.class);
                        if (list instanceof java.util.List) {
                            for (Object kv : (java.util.List) list) {
                                JSONObject c = new JSONObject();
                                c.put("gradeId", gradeId);
                                c.put("classId", String.valueOf(callStr(kv, "getKey")));
                                c.put("className", String.valueOf(callStr(kv, "getValue")));
                                classes.put(c);
                            }
                        }
                        if (classes.length() == 0 && key != null) {
                            JSONObject c = new JSONObject();
                            c.put("gradeId", String.valueOf(key));
                            c.put("classId", "");
                            c.put("className", "");
                            classes.put(c);
                        }
                    }
                }
            } catch (Throwable t) {
                root.put("classError", String.valueOf(t));
            }
            // 学生/教师：TargetRepository → 各 Repo 的 Map/RepoCallCache → mResultCache → RE_NotificationTargetGroup/Item
            try {
                Class<?> repoCls = Class.forName("net.xuele.im.util.notification.target.repo.TargetRepository");
                Object repo = repoCls.getMethod("getInstance").invoke(null);
                walkCaches(repo, "TargetRepository", 0, grades, students, teachers, classes, seen);
            } catch (Throwable t) {
                root.put("studentError", String.valueOf(t));
                XLModConfig.logAppend("[抓取] TargetRepository 读取失败: " + t);
            }
            root.put("grades", grades);
            root.put("classes", classes);
            root.put("students", students);
            root.put("teachers", teachers);
            try {
                Object gradeRepoInst = null, classRepoInst = null;
                int classItemKeys = 0, classItemWithList = 0;
                try {
                    Class<?> gr = Class.forName("net.xuele.im.util.notification.BasePresenter$Filter$GradeRepo");
                    gradeRepoInst = gr.getMethod("getInstance").invoke(null);
                    Class<?> cr = Class.forName("net.xuele.im.util.notification.BasePresenter$Filter$ClassRepo");
                    classRepoInst = cr.getMethod("getInstance").invoke(null);
                    Object map = firstFieldOfType(cr, classRepoInst, java.util.Map.class);
                    if (map instanceof java.util.Map) {
                        classItemKeys = ((java.util.Map) map).size();
                        for (Object v : ((java.util.Map) map).values()) {
                            if (v != null && firstFieldOfType(v.getClass(), v, java.util.List.class) != null) classItemWithList++;
                        }
                    }
                } catch (Throwable ignored) {
                }
                String diag = "gradeRepo=" + (gradeRepoInst != null)
                        + " classRepo=" + (classRepoInst != null)
                        + " classItemKeys=" + classItemKeys + " classItemWithList=" + classItemWithList
                        + " → grades=" + grades.length() + " classes=" + classes.length()
                        + " students=" + students.length() + " teachers=" + teachers.length();
                root.put("targetDiag", diag);
                XLModConfig.logAppend("[抓取] 目标来源诊断: " + diag);
            } catch (Throwable ignored) {
            }
            XLModConfig.logAppend("[抓取] 来源统计: 年级=" + grades.length() + " 班级/分组=" + classes.length()
                    + " 学生=" + students.length() + " 教师=" + teachers.length()
                    + "（选择页里需先点年级、再点班级，App 才会把名单载入内存）");
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] JSON 组装异常: " + t);
        }
        return root.toString();
    }

    private static void walkCaches(Object o, String path, int depth, JSONArray grades, JSONArray students,
                                   JSONArray teachers, JSONArray classes, java.util.IdentityHashMap<Object, Object> seen) {
        if (o == null || depth > 4 || seen.containsKey(o)) return;
        seen.put(o, o);
        try {
            String cn = o.getClass().getName();
            if (cn.contains("RepoCallCache")) {
                Object cached = cachedResult(o);
                if (cached != null) {
                    XLModConfig.logAppend("[抓取] 缓存命中: " + path + " → " + cached.getClass().getSimpleName());
                    walkCaches(cached, path, depth + 1, grades, students, teachers, classes, seen);
                }
                return;
            }
            if (cn.contains("RE_NotificationTargetGroup")) {
                Object wrapper = firstFieldOfType(o.getClass(), o, java.util.List.class);
                if (wrapper instanceof java.util.List) {
                    boolean isGradeGroup = path.contains("mGradeGroupOfSchool");
                    int n = ((java.util.List) wrapper).size();
                    int logged = 0;
                    for (Object dto : (java.util.List) wrapper) {
                        String gid = fieldStr(dto, "groupId");
                        String gname = fieldStr(dto, "groupName");
                        if (isGradeGroup) {
                            // mGradeGroupOfSchool 里的是"年级"（groupId 就是年级ID，如 2018）
                            JSONObject g = new JSONObject();
                            g.put("id", gid);
                            g.put("name", gname);
                            g.put("count", fieldStr(dto, "count"));
                            g.put("source", path);
                            grades.put(g);
                        } else {
                            JSONObject c = new JSONObject();
                            c.put("gradeId", lastPathKey(path));
                            c.put("classId", gid);
                            c.put("className", gname);
                            c.put("count", fieldStr(dto, "count"));
                            c.put("source", path);
                            classes.put(c);
                        }
                        if (logged++ < 3) {
                            XLModConfig.logAppend("[抓取] " + (isGradeGroup ? "年级" : "班级") + ": " + gname + " id=" + gid
                                    + " count=" + fieldStr(dto, "count") + " path=" + path);
                        }
                    }
                    if (n > 3) {
                        XLModConfig.logAppend("[抓取] " + (isGradeGroup ? "年级" : "班级") + " 共 " + n + " 条（" + path + "，其余略）");
                    }
                }
                return;
            }
            if (cn.contains("RE_NotificationTargetItem")) {
                Object wrapper = firstFieldOfType(o.getClass(), o, java.util.List.class);
                if (wrapper instanceof java.util.List) {
                    boolean isTeacher = path.toLowerCase().contains("teacher") || path.toLowerCase().contains("staff");
                    for (Object dto : (java.util.List) wrapper) {
                        JSONObject it = new JSONObject();
                        it.put("classId", lastPathKey(path));
                        it.put("objectId", fieldStr(dto, "objectId"));
                        it.put("name", fieldStr(dto, "name"));
                        it.put("remark", fieldStr(dto, "remark"));
                        it.put("path", path);
                        if (isTeacher) teachers.put(it);
                        else students.put(it);
                    }
                    XLModConfig.logAppend("[抓取] 名单命中: " + path + " → " + ((java.util.List) wrapper).size()
                            + (isTeacher ? " 教师" : " 学生"));
                }
                return;
            }
            if (o instanceof java.util.Map) {
                for (Object e : ((java.util.Map) o).entrySet()) {
                    Object k = ((java.util.Map.Entry) e).getKey();
                    walkCaches(((java.util.Map.Entry) e).getValue(), path + "/" + String.valueOf(k), depth + 1, grades, students, teachers, classes, seen);
                }
                return;
            }
            if (o instanceof java.util.List) {
                for (Object v : (java.util.List) o) walkCaches(v, path, depth + 1, grades, students, teachers, classes, seen);
                return;
            }
            // 普通对象：继续下钻其实例字段（含各种 Repo / 容器）
            for (java.lang.reflect.Field f : allFields(o.getClass())) {
                try {
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (!(java.util.Map.class.isAssignableFrom(t) || java.util.Collection.class.isAssignableFrom(t)
                            || t.getName().contains("Repo") || t.getName().contains("Cache")
                            || t.getName().startsWith("net.xuele.im.model"))) {
                        continue;
                    }
                    Object v = f.get(o);
                    if (v == null || v == o) continue;
                    walkCaches(v, path + "." + f.getName(), depth + 1, grades, students, teachers, classes, seen);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 取 RepoCallCache 的缓存结果：优先按字段名 mResultCache，其次按 net.xuele.im.model.* 类型（泛型被擦除为 Object） */
    private static Object cachedResult(Object cache) {
        try {
            for (java.lang.reflect.Field f : allFields(cache.getClass())) {
                if (f.getName().equals("mResultCache")) {
                    f.setAccessible(true);
                    Object v = f.get(cache);
                    if (v != null) return v;
                }
            }
            for (java.lang.reflect.Field f : allFields(cache.getClass())) {
                f.setAccessible(true);
                Object v = f.get(cache);
                if (v != null && v.getClass().getName().startsWith("net.xuele.im.model")) return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 路径最后一段（Map 的 key，例如 gradeId） */
    private static String lastPathKey(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : "";
    }

    private static void logGroup(Object dto, String path) {
        try {
            XLModConfig.logAppend("[抓取] 分组: " + fieldStr(dto, "groupName") + " id=" + fieldStr(dto, "groupId")
                    + " count=" + fieldStr(dto, "count") + " path=" + path);
        } catch (Throwable ignored) {
        }
    }

    private static java.util.List<java.lang.reflect.Field> allFields(Class<?> cls) {
        java.util.List<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) out.add(f);
        }
        return out;
    }

    private static Object firstFieldOfType(Class<?> cls, Object obj, Class<?> type) {
        for (java.lang.reflect.Field f : allFields(cls)) {
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null && type.isAssignableFrom(v.getClass()) && !(v == obj)) return v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String fieldStr(Object o, String name) {
        if (o == null) return "";
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (f == null) return "";
            f.setAccessible(true);
            Object v = f.get(o);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 导出抓取数据到 /sdcard/Download/xlmod_hw_targets.json */
    public static void exportTargets(Activity act) {
        try {
            String json = XLModConfig.getHwTargetsJson();
            if (json == null || json.isEmpty()) json = captureTargetsJson();
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "xlmod_hw_targets.json");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
            XLModConfig.logAppend("[抓取] 已导出: " + f.getAbsolutePath());
            toast(act, "已导出: " + f.getAbsolutePath());
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 导出失败: " + t);
            toast(act, "导出失败: " + t);
        }
    }

    /** 从 /sdcard/Download/xlmod_hw_targets.json 导入（覆盖本地缓存） */
    public static void importTargets(Activity act) {
        try {
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File f = new java.io.File(dir, "xlmod_hw_targets.json");
            if (!f.exists()) {
                toast(act, "未找到 " + f.getAbsolutePath());
                return;
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            fis.close();
            String json = new String(buf, 0, Math.max(n, 0), "UTF-8");
            new JSONObject(json); // 校验
            XLModConfig.setHwTargetsJson(json);
            updateFloatText();
            XLModConfig.logAppend("[抓取] 已导入: " + f.getAbsolutePath());
            toast(act, "已导入");
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 导入失败: " + t);
            toast(act, "导入失败: " + t);
        }
    }

    // ================= 发作业修复：课本/课时抓取 =================
    private interface Sink {
        void onSuccess(Object resp);

        void onFail(String code, String msg);
    }

    private static void callApi(String apiClass, String method, Class[] types, Object[] args, final String tag, final Sink sink) {
        try {
            Class<?> apiCls = Class.forName(apiClass);
            Object ready = apiCls.getField("ready").get(null);
            java.lang.reflect.Method m = apiCls.getMethod(method, types);
            Object call = m.invoke(ready, args);
            Class<?> cbCls = Class.forName("net.xuele.android.core.http.callback.ReqCallBackV2");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method mm, Object[] a) {
                            String n = mm.getName();
                            if (n.equals("onReqSuccess")) {
                                sink.onSuccess(a == null || a.length == 0 ? null : a[0]);
                            } else if (n.equals("onReqFailed")) {
                                sink.onFail(a != null && a.length >= 1 ? String.valueOf(a[0]) : "",
                                        a != null && a.length >= 2 ? String.valueOf(a[1]) : "");
                            }
                            return null;
                        }
                    });
            call.getClass().getMethod("requestV2", cbCls).invoke(call, cb);
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] " + tag + " 调用异常: " + t);
        }
    }

    private static JSONObject currentJson() {
        try {
            String s = XLModConfig.getHwTargetsJson();
            if (s != null && !s.isEmpty()) return new JSONObject(s);
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    /** 抓取课本：member/GetMaterialsByUserid（我的教材，含单元/课时，若有） */
    public static void captureBooks(final Activity act) {
        callApi("net.xuele.xuelets.app.user.util.Api", "getMaterialsByUseridSync", new Class[]{}, new Object[]{}, "课本",
                new Sink() {
                    @Override
                    public void onSuccess(Object resp) {
                        try {
                            JSONArray books = booksFromResponse(resp);
                            JSONObject root = currentJson();
                            root.put("books", books);
                            root.put("booksAt", System.currentTimeMillis());
                            XLModConfig.setHwTargetsJson(root.toString());
                            updateFloatText();
                            int lessons = countLessons(books);
                            String msg = "课本 " + books.length() + " 本，内置课时 " + lessons + " 个";
                            XLModConfig.logAppend("[抓取] " + msg + "（若课时为 0，点「抓取课时」逐本拉取）");
                            toast(act, "课本抓取完成：" + msg);
                        } catch (Throwable t) {
                            XLModConfig.logAppend("[抓取] 课本解析失败: " + t);
                            toast(act, "课本解析失败: " + t);
                        }
                    }

                    @Override
                    public void onFail(String code, String msg) {
                        XLModConfig.logAppend("[抓取] 课本抓取失败: code=" + code + " msg=" + msg);
                        toast(act, "课本抓取失败: " + code + " " + msg);
                    }
                });
    }

    /** 抓取课时：对已抓到的每本课本依次调 MediaApi.getUnits(bookId)，补全 units/lessons */
    public static void captureLessons(final Activity act) {
        final JSONObject root = currentJson();
        final JSONArray books = root.optJSONArray("books");
        if (books == null || books.length() == 0) {
            toast(act, "请先抓取课本");
            return;
        }
        String prefer = XLModConfig.getHwUseBookId();
        JSONArray targets = books;
        if (prefer != null && !prefer.isEmpty()) {
            JSONArray one = new JSONArray();
            for (int i = 0; i < books.length(); i++) {
                JSONObject b = books.optJSONObject(i);
                if (b != null && prefer.equals(b.optString("bookId"))) {
                    one.put(b);
                    break;
                }
            }
            if (one.length() > 0) {
                targets = one;
                XLModConfig.logAppend("[抓取] 只抓所选课本的课时: " + prefer);
            }
        }
        fetchUnitsSequential(act, root, targets, 0, 0, 0);
    }

    private static void fetchUnitsSequential(final Activity act, final JSONObject root, final JSONArray books,
                                            final int index, final int ok, final int fail) {
        if (index >= books.length()) {
            try {
                root.put("lessonsAt", System.currentTimeMillis());
                JSONObject latest = currentJson();
                mergeArray(latest, root, "books", "bookId");
                saveTargetsThrottled(latest, true);
                sClassCache = null;
                updateFloatText();
            } catch (Throwable ignored) {
            }
            String msg = "课时抓取完成：成功 " + ok + " 本 / 失败 " + fail + " 本，合计 " + countLessons(books) + " 个课时";
            XLModConfig.logAppend("[抓取] " + msg);
            toast(act, msg);
            return;
        }
        JSONObject b = books.optJSONObject(index);
        final String bookId = b == null ? "" : b.optString("bookId", "");
        if (bookId.isEmpty()) {
            fetchUnitsSequential(act, root, books, index + 1, ok, fail);
            return;
        }
        callApi("net.xuele.android.media.MediaApi", "getUnits", new Class[]{String.class}, new Object[]{bookId}, "课时",
                new Sink() {
                    @Override
                    public void onSuccess(Object resp) {
                        int n = 0;
                        try {
                            Object book = callObj(resp, "getBook");
                            if (book == null && resp != null) {
                                book = firstFieldOfType(resp.getClass(), resp, Object.class);
                            }
                            if (book != null) {
                                JSONObject nb = bookToJson(book);
                                JSONObject cur = books.optJSONObject(index);
                                if (cur != null && nb != null) {
                                    if (nb.has("units")) cur.put("units", nb.getJSONArray("units"));
                                    cur.put("lastLessonId", nb.optString("lastLessonId", cur.optString("lastLessonId", "")));
                                }
                                n = nb == null ? 0 : countLessonsIn(nb.optJSONArray("units"));
                            }
                            XLModConfig.setHwTargetsJson(root.toString());
                            XLModConfig.logAppend("[抓取] 课时: " + bookId + " → " + n + " 个");
                            toast(act, "课时 " + (index + 1) + "/" + books.length() + "：" + n + " 个");
                        } catch (Throwable t) {
                            XLModConfig.logAppend("[抓取] 课时解析失败(" + bookId + "): " + t);
                        }
                        fetchUnitsSequential(act, root, books, index + 1, ok + 1, fail);
                    }

                    @Override
                    public void onFail(String code, String msg) {
                        XLModConfig.logAppend("[抓取] 课时失败(" + bookId + "): code=" + code + " msg=" + msg);
                        fetchUnitsSequential(act, root, books, index + 1, ok, fail + 1);
                    }
                });
    }

    /** 从 RE_GetMaterialsByUserid / RE_GetUnits 等响应里取 books（或单个 book）转 JSON */
    private static JSONArray booksFromResponse(Object resp) throws Exception {
        JSONArray out = new JSONArray();
        if (resp == null) return out;
        Object books = null;
        Class<?> rc = resp.getClass();
        for (Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(resp);
                    if (v instanceof java.util.List) {
                        books = v;
                        break;
                    }
                    if (v != null && v.getClass().getName().contains("M_Book")) {
                        books = v;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (books != null) break;
        }
        if (books == null) {
            Object single = callObj(resp, "getBook");
            if (single != null) books = single;
        }
        if (books instanceof java.util.List) {
            for (Object b : (java.util.List) books) {
                JSONObject o = bookToJson(b);
                if (o != null) out.put(o);
            }
        } else if (books != null) {
            JSONObject o = bookToJson(books);
            if (o != null) out.put(o);
        }
        return out;
    }

    private static Object callObj(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject bookToJson(Object book) {
        try {
            if (book == null) return null;
            JSONObject o = new JSONObject();
            o.put("bookId", fieldStr(book, "bookid"));
            o.put("bookName", fieldStr(book, "bookname"));
            o.put("subjectId", fieldStr(book, "subjectid"));
            o.put("subjectName", fieldStr(book, "subjectname"));
            o.put("gradeNum", fieldStr(book, "gradeNum"));
            o.put("gradeName", fieldStr(book, "gradename"));
            o.put("lastLessonId", fieldStr(book, "lastLessonId"));
            Object units = null;
            for (Class<?> c = book.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getName().equals("units")) {
                        f.setAccessible(true);
                        units = f.get(book);
                        break;
                    }
                }
                if (units != null) break;
            }
            JSONArray ua = new JSONArray();
            if (units instanceof java.util.List) {
                for (Object u : (java.util.List) units) {
                    JSONObject uo = new JSONObject();
                    uo.put("unitId", fieldStr(u, "unitid"));
                    uo.put("unitName", fieldStr(u, "unitname"));
                    JSONArray la = new JSONArray();
                    Object lessons = null;
                    for (java.lang.reflect.Field f : allFields(u.getClass())) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(u);
                            if (v instanceof java.util.List) {
                                lessons = v;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (lessons instanceof java.util.List) {
                        for (Object l : (java.util.List) lessons) {
                            JSONObject lo = new JSONObject();
                            lo.put("lessonId", fieldStr(l, "lessonid"));
                            lo.put("lessonName", fieldStr(l, "lessonname"));
                            la.put(lo);
                        }
                    }
                    uo.put("lessons", la);
                    ua.put(uo);
                }
            }
            o.put("units", ua);
            return o;
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] book 解析异常: " + t);
            return null;
        }
    }

    private static int countLessons(JSONArray books) {
        int n = 0;
        if (books == null) return 0;
        for (int i = 0; i < books.length(); i++) {
            JSONObject b = books.optJSONObject(i);
            if (b != null) n += countLessonsIn(b.optJSONArray("units"));
        }
        return n;
    }

    private static int countLessonsIn(JSONArray units) {
        int n = 0;
        if (units == null) return 0;
        for (int i = 0; i < units.length(); i++) {
            JSONObject u = units.optJSONObject(i);
            JSONArray l = u == null ? null : u.optJSONArray("lessons");
            if (l != null) n += l.length();
        }
        return n;
    }

    /** 打开教材选择页 UserInitAddSubjectActivity（参数与 App 自身调用一致：带一个空的 PARAM_SUBJECT 列表） */
    public static void launchBookPicker(Activity act) {
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(act, "net.xuele.xuelets.app.user.userinit.activity.UserInitAddSubjectActivity");
            i.putExtra("PARAM_SUBJECT", new java.util.ArrayList());
            act.startActivity(i);
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 打开教材页失败: " + t);
            toast(act, "打开教材页失败: " + t);
        }
    }

    private static Object fieldObj(Object o, String name) {
        if (o == null) return null;
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isBookPage(Activity a) {
        return a != null && a.getClass().getName().contains("UserInitAddSubjectActivity");
    }

    /** 扫描最近 Activity（教材页/学科设置页等）收集课本；返回 {books, materials, source} */
    public static JSONObject collectBooksNow() {
        JSONObject out = new JSONObject();
        JSONArray books = new JSONArray();
        JSONObject materials = new JSONObject();
        String bestPage = "";
        Object bestHelper = null;
        Activity bestAct = null;
        java.util.List<Activity> cands = new java.util.ArrayList<>();
        try {
            if (sBookActivity != null) cands.add(sBookActivity);
            if (sTopActivity != null && !cands.contains(sTopActivity)) cands.add(sTopActivity);
            for (Activity a : recentActivities()) {
                if (a != null && !cands.contains(a)) cands.add(a);
            }
            for (Activity a : cands) {
                String cn = a.getClass().getName();
                Object helper = fieldObj(a, "mUserInitAddHelper");
                if (helper == null) {
                    try {
                        Class<?> hc = Class.forName("net.xuele.xuelets.app.user.userinit.helper.UserInitAddHelper");
                        helper = firstFieldOfType(a.getClass(), a, hc);
                    } catch (Throwable ignored) {
                    }
                }
                JSONArray bs = new JSONArray();
                if (helper != null) {
                    bs = listToBooks(fieldObj(helper, "mBooks"));
                    if (bs.length() == 0) {
                        JSONArray bp = listToBooksFromPair(callObj(helper, "getBookPair"));
                        if (bp.length() > 0) {
                            bs = bp;
                            XLModConfig.logAppend("[抓取] getBookPair 兜底命中: " + bp.length() + " 本（" + cn + "）");
                        }
                    }
                }
                if (bs.length() == 0) {
                    JSONArray found = new JSONArray();
                    collectBooks(a, "page", 0, found, new java.util.IdentityHashMap<Object, Object>());
                    if (found.length() > 0) {
                        bs = found;
                        XLModConfig.logAppend("[抓取] 深度扫描命中: " + found.length() + " 本（" + cn + "）");
                    }
                }
                if (bs.length() > 0) {
                    books = bs;
                    bestPage = cn;
                    bestHelper = helper;
                    bestAct = a;
                    break;
                }
                if (helper != null && bestHelper == null) {
                    bestHelper = helper;
                    bestPage = cn;
                    bestAct = a;
                }
            }
            if (bestHelper != null) {
                materials.put("subjects", listToKV(fieldObj(bestHelper, "mSubjects"), "subjectId", "subjectName"));
                materials.put("grades", listToKV(fieldObj(bestHelper, "mGrades"), "gradeName", "level"));
                materials.put("editions", listToKV(fieldObj(bestHelper, "mEditions"), "editionId", "editionName"));
                JSONObject sel = new JSONObject();
                Object sub = fieldObj(bestHelper, "mSelectSubject");
                Object grd = fieldObj(bestHelper, "mSelectGrade");
                Object edi = fieldObj(bestHelper, "mSelectEdition");
                Object bk = fieldObj(bestHelper, "mSelectBook");
                sel.put("subjectId", fieldStr(sub, "subjectId"));
                sel.put("subjectName", fieldStr(sub, "subjectName"));
                sel.put("gradeName", fieldStr(grd, "gradeName"));
                sel.put("gradeLevel", fieldInt(grd, "level"));
                sel.put("gradeId", fieldInt(grd, "id"));
                sel.put("editionId", firstNonEmpty(fieldStr(edi, "editionId"), safeStr(callObj(edi, "getEditionId"))));
                sel.put("editionName", firstNonEmpty(fieldStr(edi, "editionName"), fieldStr(edi, "name")));
                sel.put("bookId", fieldStr(bk, "bookid"));
                sel.put("bookName", fieldStr(bk, "bookname"));
                materials.put("selected", sel);
            }
            if (books.length() == 0) {
                JSONObject sel = materials.optJSONObject("selected");
                if (sel == null || sel.optString("subjectId").isEmpty()) {
                    JSONObject saved = currentJson().optJSONObject("materials");
                    JSONObject s2 = saved == null ? null : saved.optJSONObject("selected");
                    if (s2 != null) sel = s2;
                }
                if (sel != null && !sel.optString("subjectId").isEmpty() && !sel.optString("editionId").isEmpty()) {
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final JSONArray holder = new JSONArray();
                    final int gradeLevel = sel.optInt("gradeLevel", 0);
                    XLModConfig.logAppend("[抓取] 尝试课本接口反查: subject=" + sel.optString("subjectId")
                            + " grade=" + gradeLevel + " edition=" + sel.optString("editionId"));
                    callApi("net.xuele.xuelets.app.user.util.Api", "getBooksBySubjectGradeEdition",
                            new Class[]{String.class, int.class, String.class},
                            new Object[]{sel.optString("subjectId"), Integer.valueOf(gradeLevel), sel.optString("editionId")},
                            "课本反查",
                            new Sink() {
                                @Override
                                public void onSuccess(Object resp) {
                                    try {
                                        JSONArray bs = booksFromResponse(resp);
                                        for (int i = 0; i < bs.length(); i++) holder.put(bs.getJSONObject(i));
                                    } catch (Throwable ignored) {
                                    }
                                    latch.countDown();
                                }

                                @Override
                                public void onFail(String code, String msg) {
                                    XLModConfig.logAppend("[抓取] 课本反查失败: code=" + code + " msg=" + msg);
                                    latch.countDown();
                                }
                            });
                    try {
                        latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable ignored) {
                    }
                    if (holder.length() > 0) {
                        books = holder;
                        XLModConfig.logAppend("[抓取] 课本接口反查命中: " + books.length() + " 本");
                    }
                }
            }
            if (books.length() == 0) {
                XLModConfig.logAppend("[抓取] 课本为空。诊断: 最佳页=" + bestPage + " helper=" + (bestHelper != null)
                        + " 候选页数=" + cands.size());
                XLModConfig.logAppend("[抓取] 诊断-Activity: " + (bestAct == null ? "(无候选)" : dumpFields(bestAct)));
                if (bestHelper != null) {
                    XLModConfig.logAppend("[抓取] 诊断-Helper: " + dumpFields(bestHelper));
                }
            }
            out.put("books", books);
            out.put("materials", materials);
            out.put("source", bestPage);
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] collectBooksNow 异常: " + t);
        }
        return out;
    }

    private static String safeStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static int fieldInt(Object o, String name) {
        try {
            Object v = fieldObj(o, name);
            if (v instanceof Integer) return ((Integer) v).intValue();
            if (v instanceof Number) return ((Number) v).intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v));
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 诊断：列出对象非静态字段（名 → 类型/大小） */
    private static String dumpFields(Object o) {
        StringBuilder sb = new StringBuilder();
        try {
            int n = 0;
            for (java.lang.reflect.Field f : allFields(o.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                String desc;
                if (v == null) desc = "null";
                else if (v instanceof java.util.List) desc = "List(" + ((java.util.List) v).size() + ")";
                else if (v instanceof java.util.Map) desc = "Map(" + ((java.util.Map) v).size() + ")";
                else desc = v.getClass().getSimpleName();
                if (sb.length() > 0) sb.append(", ");
                sb.append(f.getName()).append("=").append(desc);
                if (++n >= 30) break;
            }
        } catch (Throwable t) {
            sb.append("<dump异常>");
        }
        return sb.toString();
    }

    /** 从教材页（或最近页面）抓取课本：合并进本地 JSON 并刷新悬浮窗 */
    public static void captureBooksFromPage(final Activity act) {
        try {
            JSONObject info = collectBooksNow();
            JSONArray books = info.optJSONArray("books");
            JSONObject root = currentJson();
            mergeArray(root, info, "books", "bookId");
            JSONObject mats = info.optJSONObject("materials");
            if (mats != null && mats.length() > 0) {
                JSONObject oldMats = root.optJSONObject("materials");
                if (oldMats == null) oldMats = new JSONObject();
                java.util.Iterator<String> kit = mats.keys();
                while (kit.hasNext()) {
                    String k = kit.next();
                    Object v = mats.get(k);
                    if (v instanceof JSONArray && ((JSONArray) v).length() == 0) continue;
                    oldMats.put(k, v);
                }
                root.put("materials", oldMats);
            }
            root.put("booksAt", System.currentTimeMillis());
            saveTargetsThrottled(root, true);
            updateFloatText();
            int total = len(root.optJSONArray("books"));
            if (books == null || books.length() == 0) {
                XLModConfig.logAppend("[抓取] 课本仍为空：请在教材页选好 学科→年级→教材版本（可在教材页直接用悬浮窗「抓取」或开启自动抓取）");
                toast(act, "课本为空：请先在教材页选 学科→年级→教材版本");
            } else {
                String msg = "课本 +" + books.length() + " 本（累计 " + total + "），来源 " + info.optString("source");
                XLModConfig.logAppend("[抓取] 教材页抓取完成: " + msg);
                toast(act, "教材页抓取完成：" + msg);
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[抓取] 教材页抓取失败: " + t);
            toast(act, "教材页抓取失败: " + t);
        }
    }

    private static int listLen(Object list) {
        return list instanceof java.util.List ? ((java.util.List) list).size() : 0;
    }

    /** getBookPair() → List<KeyValuePair>(bookid, bookname) */
    private static JSONArray listToBooksFromPair(Object pair) {
        JSONArray out = new JSONArray();
        if (!(pair instanceof java.util.List)) return out;
        for (Object kv : (java.util.List) pair) {
            try {
                JSONObject j = new JSONObject();
                j.put("bookId", String.valueOf(callStr(kv, "getKey")));
                j.put("bookName", String.valueOf(callStr(kv, "getValue")));
                j.put("units", new JSONArray());
                out.put(j);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 通用兜底：扫描任意对象图，收集 M_Book（含 InitSubjectModel 里的书信息） */
    private static void collectBooks(Object o, String path, int depth, JSONArray out,
                                     java.util.IdentityHashMap<Object, Object> seen) {
        if (o == null || depth > 6 || seen.containsKey(o) || out.length() > 300) return;
        seen.put(o, o);
        try {
            String cn = o.getClass().getName();
            // 跳过界面/上下文类（保留 androidx.fragment.* 等数据容器，便于扫到 Fragment/Adapter）
            if (o instanceof android.view.View || o instanceof android.view.Window
                    || o instanceof android.content.Context || o instanceof android.content.res.Resources
                    || o instanceof android.graphics.drawable.Drawable
                    || o instanceof String || o instanceof Number || o instanceof Boolean) return;
            if (cn.endsWith("M_Book")) {
                JSONObject b = bookToJson(o);
                if (b != null && !b.optString("bookId").isEmpty()) out.put(b);
                return;
            }
            if (cn.endsWith("InitSubjectModel")) {
                JSONObject b = new JSONObject();
                b.put("bookId", fieldStr(o, "bookId"));
                b.put("bookName", fieldStr(o, "bookName"));
                b.put("subjectId", fieldStr(o, "subjectId"));
                b.put("subjectName", fieldStr(o, "subjectName"));
                b.put("gradeName", fieldStr(o, "gradeName"));
                b.put("gradeNum", fieldStr(o, "gradeNum"));
                b.put("units", new JSONArray());
                if (!b.optString("bookId").isEmpty()) out.put(b);
                return;
            }
            if (o instanceof java.util.Map) {
                for (Object e : ((java.util.Map) o).entrySet()) {
                    collectBooks(((java.util.Map.Entry) e).getValue(), path + "/" + ((java.util.Map.Entry) e).getKey(), depth + 1, out, seen);
                }
                return;
            }
            if (o instanceof java.util.List) {
                for (Object v : (java.util.List) o) collectBooks(v, path, depth + 1, out, seen);
                return;
            }
            for (java.lang.reflect.Field f : allFields(o.getClass())) {
                try {
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (android.view.View.class.isAssignableFrom(t)) continue;
                    Object v = f.get(o);
                    if (v == null || v == o) continue;
                    collectBooks(v, path + "." + f.getName(), depth + 1, out, seen);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static JSONArray listToBooks(Object list) {
        JSONArray out = new JSONArray();
        if (!(list instanceof java.util.List)) return out;
        for (Object b : (java.util.List) list) {
            JSONObject o = bookToJson(b);
            if (o != null) out.put(o);
        }
        return out;
    }

    private static JSONArray listToKV(Object list, String idKey, String nameKey) {
        JSONArray out = new JSONArray();
        if (!(list instanceof java.util.List)) return out;
        for (Object o : (java.util.List) list) {
            try {
                JSONObject j = new JSONObject();
                String id = fieldStr(o, idKey);
                if (id.isEmpty()) id = fieldStr(o, "id");
                String name = fieldStr(o, nameKey);
                if (name.isEmpty()) name = fieldStr(o, "name");
                if (name.isEmpty()) name = String.valueOf(o);
                j.put("id", id);
                j.put("name", name);
                out.put(j);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String describe(Object o) {
        if (o == null) return "";
        try {
            String s = String.valueOf(o);
            return s.length() > 120 ? s.substring(0, 120) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= 一键发作业 =================
    /** 发作业：拉题 → 组装 classess/questions → teacherWork/v3/submitHomework */
    public static void publishHomework(final Activity act, final String classId, final String className,
                                       final String gradeNum, final String lessonId, final String lessonName,
                                       final String subjectId, final String subjectName, final int questionCount) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> apiCls = Class.forName("net.xuele.xuelets.homework.util.Api");
                    Object ready = apiCls.getField("ready").get(null);
                    Class<?> cbCls = Class.forName("net.xuele.android.core.http.callback.ReqCallBackV2");
                    // 1) 拉题
                    java.lang.reflect.Method mGet = apiCls.getMethod("getQuestions", int.class, String.class, int.class,
                            String.class, String.class, String.class, String.class, int.class, int.class);
                    Object call = mGet.invoke(ready, Integer.valueOf(0), lessonId, Integer.valueOf(2), "", "", "", "1",
                            Integer.valueOf(1), Integer.valueOf(Math.max(questionCount, 1)));
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final ArrayList<HashMap<String, Object>> questions = new ArrayList<>();
                    final String[] err = new String[]{"", ""};
                    Object cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                            new java.lang.reflect.InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, java.lang.reflect.Method mm, Object[] a) {
                                    String n = mm.getName();
                                    if (n.equals("onReqSuccess")) {
                                        try {
                                            Object resp = a == null || a.length == 0 ? null : a[0];
                                            Object list = callObj(resp, "getQuestions");
                                            if (list instanceof java.util.List) {
                                                int i = 1;
                                                for (Object q : (java.util.List) list) {
                                                    if (questions.size() >= questionCount) break;
                                                    String queType = fieldStr(q, "queType");
                                                    int qt = parseIntSafe(queType);
                                                    HashMap<String, Object> map = new HashMap<>();
                                                    map.put("queId", fieldStr(q, "queId"));
                                                    map.put("wrappedQueId", fieldStr(q, "wrappedQueId"));
                                                    map.put("queType", queType);
                                                    map.put("bankType", fieldStr(q, "bankType"));
                                                    map.put("sort", String.valueOf(i));
                                                    map.put("targetQueType", new ArrayList<String>());
                                                    map.put("itemClass", (qt == 4 || qt == 6 || qt == 51) ? "1" : "2");
                                                    questions.add(map);
                                                    i++;
                                                }
                                            }
                                        } catch (Throwable t) {
                                            err[0] = "parse";
                                            err[1] = String.valueOf(t);
                                        }
                                        latch.countDown();
                                    } else if (n.equals("onReqFailed")) {
                                        err[0] = a != null && a.length >= 1 ? String.valueOf(a[0]) : "";
                                        err[1] = a != null && a.length >= 2 ? String.valueOf(a[1]) : "";
                                        latch.countDown();
                                    }
                                    return null;
                                }
                            });
                    call.getClass().getMethod("requestV2", cbCls).invoke(call, cb);
                    latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                    if (questions.isEmpty()) {
                        final String m = "拉题失败或该课时无题：code=" + err[0] + " msg=" + err[1];
                        XLModConfig.logAppend("[发作业] " + m);
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(act, m);
                            }
                        });
                        return;
                    }
                    int obj = 0, subj = 0;
                    for (HashMap<String, Object> q : questions) {
                        if ("1".equals(q.get("itemClass"))) subj++;
                        else obj++;
                    }
                    // 2) classess: SubmitClassDTO
                    Class<?> dtoCls = Class.forName("net.xuele.xuelets.homework.model.SubmitClassDTO");
                    Object dto = dtoCls.newInstance();
                    setField(dto, "classId", classId);
                    setField(dto, "className", className);
                    setField(dto, "gradeNum", gradeNum);
                    ArrayList<Object> classess = new ArrayList<>();
                    classess.add(dto);
                    ArrayList<Object> students = new ArrayList<>();
                    // 3) 发布
                    java.lang.reflect.Method mSub = apiCls.getMethod("submitHomework", int.class, String.class, String.class,
                            String.class, String.class, String.class, String.class, String.class, String.class,
                            HashMap.class, ArrayList.class, String.class, long.class, ArrayList.class, ArrayList.class,
                            ArrayList.class, int.class);
                    Object call2 = mSub.invoke(ready, Integer.valueOf(2), lessonId, lessonName, subjectId, subjectName,
                            gradeNum, String.valueOf(subj), String.valueOf(obj), "", null, null, "0",
                            Long.valueOf(0L), questions, classess, students, Integer.valueOf(0));
                    final String[] err2 = new String[]{"", ""};
                    Object cb2 = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                            new java.lang.reflect.InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, java.lang.reflect.Method mm, Object[] a) {
                                    String n = mm.getName();
                                    if (n.equals("onReqSuccess")) {
                                        XLModConfig.logAppend("[发作业] 发布成功：班级=" + classId + " 课时=" + lessonId
                                                + " 题数=" + questions.size());
                                        act.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                toast(act, "发布成功：共 " + questions.size() + " 题");
                                            }
                                        });
                                    } else if (n.equals("onReqFailed")) {
                                        err2[0] = a != null && a.length >= 1 ? String.valueOf(a[0]) : "";
                                        err2[1] = a != null && a.length >= 2 ? String.valueOf(a[1]) : "";
                                        XLModConfig.logAppend("[发作业] 发布失败：code=" + err2[0] + " msg=" + err2[1]);
                                        act.runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                toast(act, "发布失败：" + err2[0] + " " + err2[1]);
                                            }
                                        });
                                    }
                                    return null;
                                }
                            });
                    call2.getClass().getMethod("requestV2", cbCls).invoke(call2, cb2);
                } catch (Throwable t) {
                    XLModConfig.logAppend("[发作业] 异常: " + t);
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(act, "发布异常，详见日志");
                        }
                    });
                }
            }
        }).start();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void setField(Object o, String name, String v) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(o, v);
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 原版发作业页面：数据注入（学生账号服务端返回空的班级/教材） =================
    /** 注入班级/学生：原列表为空（学生账号）时用抓取数据构造 ClassModel 列表，原列表非空则合并 */
    public static java.util.List hwInjectClasses(java.util.List original) {
        try {
            if (!XLModConfig.isHwFixEnabled()) {
                injectSkip("发作业修复开关未开启");
                return original;
            }
            java.util.List injected = buildClassModels();
            if (injected == null || injected.isEmpty()) return original;
            if (original == null || original.isEmpty()) {
                XLModConfig.logAppend("[注入] 班级列表为空 → 注入 " + injected.size() + " 个（抓取数据）");
                return injected;
            }
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (Object o : original) {
                ids.add(fieldStr(o, "classId"));
            }
            int add = 0;
            for (Object o : injected) {
                String id = fieldStr(o, "classId");
                if (!id.isEmpty() && !ids.contains(id)) {
                    original.add(o);
                    add++;
                }
            }
            if (add > 0) XLModConfig.logAppend("[注入] 合并班级 +" + add);
            return original;
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwInjectClasses 异常: " + t);
            return original;
        }
    }

    private static long sLastInjectLog = 0;

    /** 注入跳过原因（节流 5 秒，避免自动抓取刷屏） */
    private static void injectSkip(String why) {
        long now = System.currentTimeMillis();
        if (now - sLastInjectLog < 5000) return;
        sLastInjectLog = now;
        XLModConfig.logAppend("[注入] 跳过：" + why);
    }

    /** 启动/关键路径打印一次"注入状态"（开关 + 本地数据量） */
    public static void logInjectStatus() {
        try {
            JSONObject root = currentJson();
            XLModConfig.logAppend("[注入] 状态: 开关=" + (XLModConfig.isHwFixEnabled() ? "开" : "关")
                    + " 年级=" + len(root.optJSONArray("grades"))
                    + " 班级=" + len(root.optJSONArray("classes"))
                    + " 学生=" + len(root.optJSONArray("students"))
                    + " 课本=" + len(root.optJSONArray("books"))
                    + " 课时=" + countLessons(root.optJSONArray("books")));
        } catch (Throwable ignored) {
        }
    }

    /** 有抓取数据且开关开启时返回注入用班级列表，否则返回 null（供 AssignHomeworkActivity.getClasses 直接短路） */
    public static java.util.List hwInjectedClassesOrNull() {
        try {
            if (!XLModConfig.isHwFixEnabled()) {
                injectSkip("发作业修复开关未开启（面板「发作业修复」打开后再进本页）");
                return null;
            }
            java.util.List injected = buildClassModels();
            if (injected == null || injected.isEmpty()) {
                // 自愈：内存里没有班级，但本地文件可能有 → 同步一次再试
                XLModConfig.syncHwTargetsWithFile();
                sClassCache = null;
                injected = buildClassModels();
            }
            if (injected == null || injected.isEmpty()) {
                JSONObject r = currentJson();
                injectSkip("本地无班级数据：年级=" + len(r.optJSONArray("grades")) + " 班级=" + len(r.optJSONArray("classes"))
                        + " 学生=" + len(r.optJSONArray("students")) + "（先抓班级/学生，或点「重新加载本地数据」）");
                return null;
            }
            bc("getClasses 短路注入");
            XLModConfig.logAppend("[注入] getClasses 短路生效：注入 " + injected.size() + " 个班级（本地数据）");
            String tag = "短路注入 " + injected.size() + " 个班级";
            if (!tag.equals(sLastShortLog)) {
                sLastShortLog = tag;
                XLModConfig.logAppend("[注入] getClasses " + tag);
            }
            return injected;
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwInjectedClassesOrNull 异常: " + t);
            return null;
        }
    }

    /** 用抓取的 classes[] + students[] 构造原版 ClassModel 列表（含每班学生明细） */
    private static java.util.List sClassCache = null;
    private static String sClassCacheVer = "";

    private static String dataVersion(JSONObject root) {
        return len(root.optJSONArray("classes")) + "/" + len(root.optJSONArray("students")) + "/"
                + len(root.optJSONArray("books")) + "/" + countLessons(root.optJSONArray("books"));
    }

    private static java.util.List buildClassModels() {
        java.util.List out = new ArrayList();
        int withStudents = 0, totalStudents = 0;
        try {
            JSONObject root = currentJson();
            String ver = dataVersion(root);
            if (sClassCache != null && ver.equals(sClassCacheVer)) {
                return sClassCache;
            }
            JSONArray cs = root.optJSONArray("classes");
            JSONArray ss = root.optJSONArray("students");
            if (cs == null || cs.length() == 0) return out;
            Class<?> cmCls = Class.forName("net.xuele.xuelets.homework.model.ClassModel");
            Class<?> siCls = Class.forName("net.xuele.xuelets.homework.model.StudentInfoModel");
            java.lang.reflect.Constructor<?> siCtor = siCls.getConstructor(String.class, String.class, String.class);
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null || c.optString("classId").isEmpty()) continue;
                if (c.optString("source", "").contains("mGradeGroupOfSchool")) continue;
                String cid = c.optString("classId");
                String gid = c.optString("gradeId", "");
                String composite = gid + cid;
                Object cm = cmCls.newInstance();
                setFieldObj(cm, "classId", cid);
                setFieldObj(cm, "className", c.optString("className", cid));
                setFieldObj(cm, "grade", gid);
                // classType=1 会被当作"走班制班级"并被 getRealClass 过滤，普通班级用 2
                setFieldObj(cm, "classType", Integer.valueOf(2));
                setFieldObj(cm, "classNum", Integer.valueOf(0));
                ArrayList<Object> stuList = new ArrayList<>();
                if (ss != null) {
                    for (int s = 0; s < ss.length(); s++) {
                        JSONObject st = ss.optJSONObject(s);
                        if (st == null) continue;
                        String scid = st.optString("classId");
                        // 抓捕时学生的 classId 是 "年级ID+分组ID" 拼接键，两种都要匹配
                        boolean match = scid.isEmpty() || scid.equals(cid) || scid.equals(composite)
                                || (!gid.isEmpty() && scid.endsWith(cid));
                        if (!match) continue;
                        Object stu = siCtor.newInstance("", st.optString("objectId"), st.optString("name"));
                        setFieldObj(stu, "isSelect", Boolean.FALSE);
                        stuList.add(stu);
                    }
                }
                setFieldObj(cm, "studentList", stuList);
                withStudents += stuList.size() > 0 ? 1 : 0;
                totalStudents += stuList.size();
                out.add(cm);
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] buildClassModels 异常: " + t);
        }
        String summary = "班级=" + out.size() + " 有名单=" + withStudents + " 学生合计=" + totalStudents;
        if (!summary.equals(sLastClassSummary)) {
            sLastClassSummary = summary;
            XLModConfig.logAppend("[注入] 构建注入班级: " + summary);
        }
        if (!out.isEmpty()) {
            sClassCache = out;
            sClassCacheVer = dataVersion(currentJson());
        }
        return out;
    }

    /**
     * 原版发布前的兜底注入（挂在 AssignWorkHelper.getQuestions 入口）：
     * 课时/教材为空则用抓取数据补上；一个题都没选则用该课时拉 N 道题填进 mSelectQuestions。
     */
    public static void hwEnsureSelectQuestions(Object param) {
        try {
            if (param == null) return;
            if (!XLModConfig.isHwFixEnabled()) {
                injectSkip("发作业修复开关未开启（发布前补课时/拉题）");
                return;
            }
            JSONObject lesson = firstCapturedLesson(currentJson());
            if (lesson == null) {
                XLModConfig.logAppend("[注入] 无可用课时（先抓课本/课时）");
                return;
            }
            setFieldIfEmpty(param, "lessonId", lesson.optString("lessonId"));
            setFieldIfEmpty(param, "lessonName", lesson.optString("lessonName"));
            setFieldIfEmpty(param, "subjectId", lesson.optString("subjectId"));
            setFieldIfEmpty(param, "subjectName", lesson.optString("subjectName"));
            setFieldIfEmpty(param, "gradeNum", lesson.optString("gradeNum"));
            setFieldIfEmpty(param, "bookId", lesson.optString("bookId"));
            setFieldIfEmpty(param, "bookName", lesson.optString("bookName"));
            Object sel = fieldObj(param, "mSelectQuestions");
            if (sel instanceof java.util.List && !((java.util.List) sel).isEmpty()) {
                return;
            }
            String lessonId = lesson.optString("lessonId");
            java.util.List qs = null;
            if (lessonId.equals(sPrefetchedLesson) && sPrefetchedQs != null
                    && System.currentTimeMillis() - sPrefetchedAt < 120000) {
                qs = sPrefetchedQs;
                XLModConfig.logAppend("[注入] 使用预取题目 " + qs.size() + " 道（课时=" + lessonId + "）");
            }
            if (qs == null) {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    injectSkip("题目尚未预取完成（主线程阻塞已移除）→ 已触发后台预取，请再点一次发布");
                    prefetchQuestions(lessonId, XLModConfig.getHwQCount());
                    return;
                }
                qs = fetchQuestionObjects(lessonId, XLModConfig.getHwQCount());
            }
            if (qs.isEmpty()) {
                XLModConfig.logAppend("[注入] 该课时拉题为空，未注入题目（lessonId=" + lessonId + "）");
                prefetchQuestions(lessonId, XLModConfig.getHwQCount());
                return;
            }
            setFieldObj(param, "mSelectQuestions", new ArrayList(qs));
            XLModConfig.logAppend("[注入] 已注入 " + qs.size() + " 道题（课时=" + lesson.optString("lessonName")
                    + "，lessonId=" + lesson.optString("lessonId") + "）");
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwEnsureSelectQuestions 异常: " + t);
        }
    }

    /** 挂在 AssignHomeworkFragment.updateViews：把抓取到的课本/课时信息写进原版参数，并预填课时本地缓存 */
    public static void hwInjectAssignParamFromFragment(Object fragment) {
        try {
            if (fragment == null) return;
            if (!XLModConfig.isHwFixEnabled()) {
                injectSkip("发作业修复开关未开启（课本/课时注入）");
                return;
            }
            Object param = fieldObj(fragment, "mAssignWorkParam");
            if (param == null) {
                XLModConfig.logAppend("[注入] fragment 未找到 mAssignWorkParam");
                return;
            }
            hwInjectAssignParam(param);
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwInjectAssignParamFromFragment 异常: " + t);
        }
    }

    /** 发作业页每次启动都注入：立即 + 延迟重试（等 Fragment/UI 就绪） */
    public static void hwOnAssignActivityLaunch(final Activity act) {
        try {
            bc("AssignHomeworkActivity.onCreate 钩子");
            XLModConfig.logAppend("[注入] AssignHomeworkActivity 启动 → 准备注入（渲染统一由 getClasses 短路完成）");
            logInjectStatus();
            checkExternalHooks();
            JSONObject lesson0 = firstCapturedLesson(currentJson());
            if (lesson0 != null) prefetchQuestions(lesson0.optString("lessonId"), XLModConfig.getHwQCount());
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            // 只做"数据准备 + 课时缓存"，班级渲染交给 getClasses() 短路（单路径，避免重复 loadSuccess 抖动导致闪退）
            h.post(new Runnable() {
                @Override
                public void run() {
                    bc("启动注入-数据准备");
                    injectNow(act, 1, false);
                }
            });
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwOnAssignActivityLaunch 异常: " + t);
        }
    }

    /** 把本地班级/课时强制注入到"当前前台"的发作业页（供悬浮窗/面板手动触发，也用于抓取后立即注入） */
    public static void forceInjectIntoTopActivity() {
        try {
            Activity act = sTopActivity;
            if (act == null) {
                XLModConfig.logAppend("[注入] 手动注入失败：没有前台页面");
                return;
            }
            String cn = act.getClass().getName();
            XLModConfig.logAppend("[注入] 手动注入目标页面: " + cn);
            if (!cn.contains("AssignHomeworkActivity")) {
                toast(act, "当前不是发作业页（请先进入「布置作业」）");
                return;
            }
            if (!XLModConfig.isHwFixEnabled()) {
                XLModConfig.setHwFixEnabled(true);
                XLModConfig.logAppend("[注入] 手动注入时发现开关为关 → 已自动开启");
            }
            sAssignInjected = false;
            sRenderedFor = null;
            injectNow(act, 1, true);
            toast(act, "已执行注入（详见日志 [注入] 行）");
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] forceInjectIntoTopActivity 异常: " + t);
        }
    }

    private static boolean sAssignInjected = false;
    private static Activity sRenderedFor = null;

    /** 延迟重试渲染：adapter 未就绪时最多重试 5 次（每次 400ms），成功后一次性渲染 */
    private static void scheduleRender(final Activity act, final int attempt) {
        if (act == null || attempt > 5) return;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        bc("渲染重试#" + attempt);
                        injectNow(act, attempt + 1, true);
                    } catch (Throwable t) {
                        XLModConfig.logAppend("[注入] 渲染重试异常: " + t);
                    }
                }
            }, 400);
        } catch (Throwable ignored) {
        }
    }


    /** 单次注入尝试：班级 → mClasses + loadSuccess；课本/课时 → 参数 + 缓存 */
    private static void injectNow(Activity act, int attempt, boolean allowRender) {
        try {
            if (act == null || !XLModConfig.isHwFixEnabled()) {
                if (attempt == 1) injectSkip("发作业修复开关未开启");
                return;
            }
            java.util.List injected = hwInjectedClassesOrNull();
            if (injected != null && !injected.isEmpty()) {
                if (allowRender && sRenderedFor == act) {
                    return;
                }
                Object cur = fieldObj(act, "mClasses");
                boolean needSet = !(cur instanceof java.util.List) || ((java.util.List) cur).size() != injected.size();
                if (!needSet && sAssignInjected && !allowRender) {
                    return;
                }
                if (needSet || !sAssignInjected) {
                    Object adapter = fieldObj(act, "mPagerAdapter");
                    setFieldObj(act, "mClasses", injected);
                    try {
                        Object ind = fieldObj(act, "mLoadingIndicatorView");
                        if (ind != null) callObj(ind, "success");
                    } catch (Throwable ignored) {
                    }
                    if (!allowRender) {
                        XLModConfig.logAppend("[注入] 数据准备完成：已写入 " + injected.size() + " 个班级（渲染交由 getClasses 短路）");
                        return;
                    }
                    if (adapter == null) {
                        XLModConfig.logAppend("[注入] PagerAdapter 未就绪 → 延迟重试渲染（第 " + attempt + " 次）");
                        scheduleRender(act, attempt);
                        return;
                    }
                    boolean ok = false;
                    try {
                        java.lang.reflect.Method ls = act.getClass().getDeclaredMethod("loadSuccess");
                        ls.setAccessible(true);
                        ls.invoke(act);
                        ok = true;
                    } catch (Throwable t) {
                        XLModConfig.logAppend("[注入] loadSuccess 反射失败: " + t);
                        try {
                            java.lang.reflect.Method acc = act.getClass().getDeclaredMethod("access$600", act.getClass());
                            acc.setAccessible(true);
                            acc.invoke(null, act);
                            ok = true;
                        } catch (Throwable t2) {
                            XLModConfig.logAppend("[注入] access$600 兜底也失败: " + t2);
                        }
                    }
                    sAssignInjected = true;
                    sRenderedFor = act;
                    XLModConfig.logAppend("[注入] 第 " + attempt + " 次尝试：已注入 " + injected.size() + " 个班级到 mClasses"
                            + (ok ? " + loadSuccess()" : "（渲染交由原流程）"));
                }
            } else if (attempt == 1) {
                injectSkip("本地无班级数据（先抓班级/学生）");
            }
            // 课本/课时：参数 + 课时缓存
            JSONObject lesson = firstCapturedLesson(currentJson());
            if (lesson != null) {
                hwPrefillUnitsCache(lesson.optString("bookId"));
            } else if (attempt == 1) {
                injectSkip("本地无课时数据（先抓课本/课时）");
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] injectNow 异常: " + t);
        }
    }

    /** 把抓取到的课本/课时信息补进原版 AssignWorkParam（空值才填），并预填"课时选择"用的本地缓存 */
    public static void hwInjectAssignParam(Object param) {
        try {
            if (param == null || !XLModConfig.isHwFixEnabled()) return;
            JSONObject lesson = firstCapturedLesson(currentJson());
            if (lesson == null) {
                XLModConfig.logAppend("[注入] 无可用课时（先抓课本/课时）");
                return;
            }
            setFieldIfEmpty(param, "bookId", lesson.optString("bookId"));
            setFieldIfEmpty(param, "bookName", lesson.optString("bookName"));
            setFieldIfEmpty(param, "unitId", lesson.optString("unitId"));
            setFieldIfEmpty(param, "unitName", lesson.optString("unitName"));
            setFieldIfEmpty(param, "lessonId", lesson.optString("lessonId"));
            setFieldIfEmpty(param, "lessonName", lesson.optString("lessonName"));
            setFieldIfEmpty(param, "subjectId", lesson.optString("subjectId"));
            setFieldIfEmpty(param, "subjectName", lesson.optString("subjectName"));
            setFieldIfEmpty(param, "gradeNum", lesson.optString("gradeNum"));
            XLModConfig.logAppend("[注入] AssignWorkParam: book=" + lesson.optString("bookId")
                    + " unit=" + lesson.optString("unitId") + " lesson=" + lesson.optString("lessonId"));
            hwPrefillUnitsCache(lesson.optString("bookId"));
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwInjectAssignParam 异常: " + t);
        }
    }

    /** 预填题库"选择课时"页读取的本地缓存（XLDataManager Temp / IndexQuesLibFragment_Units_KEY） */
    public static void hwPrefillUnitsCache(String bookId) {
        try {
            if (bookId == null || bookId.isEmpty()) return;
            JSONObject root = currentJson();
            JSONArray bs = root.optJSONArray("books");
            if (bs == null) return;
            JSONObject bookJson = null;
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b != null && bookId.equals(b.optString("bookId"))) {
                    bookJson = b;
                    break;
                }
            }
            if (bookJson == null) return;
            JSONArray us = bookJson.optJSONArray("units");
            if (us == null || us.length() == 0) return;

            Class<?> reCls = Class.forName("net.xuele.android.media.resourceselect.model.RE_GetUnits");
            Class<?> bookCls = Class.forName("net.xuele.android.media.resourceselect.model.M_Book");
            Class<?> unitCls = Class.forName("net.xuele.android.media.resourceselect.model.M_Unit");
            Class<?> lessonCls = Class.forName("net.xuele.android.media.resourceselect.model.M_Lesson");
            Object re = reCls.newInstance();
            Object book = bookCls.newInstance();
            setFieldObj(book, "bookid", bookJson.optString("bookId"));
            setFieldObj(book, "bookname", bookJson.optString("bookName"));
            setFieldObj(book, "subjectid", bookJson.optString("subjectId"));
            setFieldObj(book, "subjectname", bookJson.optString("subjectName"));
            setFieldObj(book, "gradeNum", bookJson.optString("gradeNum"));
            setFieldObj(book, "gradename", bookJson.optString("gradeName"));
            setFieldObj(book, "lastLessonId", bookJson.optString("lastLessonId"));
            ArrayList<Object> unitList = new ArrayList<>();
            int lessonCount = 0;
            for (int u = 0; u < us.length(); u++) {
                JSONObject uj = us.optJSONObject(u);
                if (uj == null) continue;
                Object unit = unitCls.newInstance();
                setFieldObj(unit, "unitid", uj.optString("unitId"));
                setFieldObj(unit, "unitname", uj.optString("unitName"));
                ArrayList<Object> lessonList = new ArrayList<>();
                JSONArray ls = uj.optJSONArray("lessons");
                if (ls != null) {
                    for (int l = 0; l < ls.length(); l++) {
                        JSONObject lj = ls.optJSONObject(l);
                        if (lj == null) continue;
                        Object lesson = lessonCls.newInstance();
                        setFieldObj(lesson, "lessonid", lj.optString("lessonId"));
                        setFieldObj(lesson, "lessonname", lj.optString("lessonName"));
                        setFieldObj(lesson, "unitName", uj.optString("unitName"));
                        setFieldObj(lesson, "unitNameFromServer", uj.optString("unitName"));
                        lessonList.add(lesson);
                        lessonCount++;
                    }
                }
                setFieldObj(unit, "lessons", lessonList);
                unitList.add(unit);
            }
            setFieldObj(book, "units", unitList);
            setFieldObj(re, "book", book);

            Class<?> dmCls = Class.forName("net.xuele.android.core.data.XLDataManager");
            Class<?> dtCls = Class.forName("net.xuele.android.core.file.XLDataType");
            Object temp = null;
            for (Object c : dtCls.getEnumConstants()) {
                if ("Temp".equals(String.valueOf(c))) {
                    temp = c;
                    break;
                }
            }
            if (temp == null) return;
            java.lang.reflect.Method put = dmCls.getMethod("putObjectByJson", dtCls, String.class, Object.class);
            put.invoke(null, temp, "IndexQuesLibFragment_Units_KEY", re);
            String tag2 = "课本=" + bookJson.optString("bookName") + " 单元=" + unitList.size() + " 课时=" + lessonCount;
            if (!tag2.equals(sLastUnitsLog)) {
                sLastUnitsLog = tag2;
                XLModConfig.logAppend("[注入] 已预填课时缓存: " + tag2);
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] hwPrefillUnitsCache 异常: " + t);
        }
    }

    /** 取抓取数据里第一个可用课时（带所属课本的 subject/grade） */
    private static JSONObject firstCapturedLesson(JSONObject root) {
        try {
            JSONArray bs = root.optJSONArray("books");
            if (bs == null) return null;
            String prefer = XLModConfig.getHwUseLessonId();
            String preferBook = XLModConfig.getHwUseBookId();
            // 两轮：第一轮优先匹配面板选定的课时/课本，第二轮兜底取第一课时
            for (int round = 0; round < 2; round++) {
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b == null) continue;
                if (round == 0 && !preferBook.isEmpty() && !preferBook.equals(b.optString("bookId"))) continue;
                JSONArray us = b.optJSONArray("units");
                if (us == null) continue;
                for (int u = 0; u < us.length(); u++) {
                    JSONObject un = us.optJSONObject(u);
                    JSONArray ls = un == null ? null : un.optJSONArray("lessons");
                    if (ls == null) continue;
                    for (int l = 0; l < ls.length(); l++) {
                        JSONObject ln = ls.optJSONObject(l);
                        if (ln == null || ln.optString("lessonId").isEmpty()) continue;
                        if (round == 0 && !prefer.isEmpty() && !prefer.equals(ln.optString("lessonId"))) continue;
                        JSONObject out = new JSONObject();
                        out.put("lessonId", ln.optString("lessonId"));
                        out.put("lessonName", ln.optString("lessonName"));
                        out.put("unitId", un.optString("unitId"));
                        out.put("unitName", un.optString("unitName"));
                        out.put("bookId", b.optString("bookId"));
                        out.put("bookName", b.optString("bookName"));
                        out.put("subjectId", b.optString("subjectId"));
                        out.put("subjectName", b.optString("subjectName"));
                        out.put("gradeNum", b.optString("gradeNum"));
                        return out;
                    }
                }
            }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static java.util.List sPrefetchedQs = null;
    private static String sPrefetchedLesson = "";
    private static long sPrefetchedAt = 0;

    /** 后台预取题目（不阻塞主线程）；同一课时 60 秒内不重复 */
    public static void prefetchQuestions(final String lessonId, final int count) {
        try {
            if (lessonId == null || lessonId.isEmpty()) return;
            if (lessonId.equals(sPrefetchedLesson) && sPrefetchedQs != null
                    && System.currentTimeMillis() - sPrefetchedAt < 60000) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.List qs = fetchQuestionObjects(lessonId, count);
                    if (qs != null && !qs.isEmpty()) {
                        sPrefetchedQs = qs;
                        sPrefetchedLesson = lessonId;
                        sPrefetchedAt = System.currentTimeMillis();
                        XLModConfig.logAppend("[注入] 预拉题完成: " + qs.size() + " 道（课时=" + lessonId + "）");
                    } else {
                        XLModConfig.logAppend("[注入] 预拉题为空（课时=" + lessonId + "）");
                    }
                }
            }).start();
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] prefetchQuestions 异常: " + t);
        }
    }

    /** 同步拉题：返回原版 M_HomeWorkSimplifyQuestion 对象列表（反射构造，供原版流程使用） */
    private static java.util.List fetchQuestionObjects(String lessonId, final int count) {
        java.util.List out = new ArrayList();
        try {
            Class<?> apiCls = Class.forName("net.xuele.xuelets.homework.util.Api");
            Object ready = apiCls.getField("ready").get(null);
            Class<?> cbCls = Class.forName("net.xuele.android.core.http.callback.ReqCallBackV2");
            Class<?> qCls = Class.forName("net.xuele.xuelets.homework.model.M_HomeWorkSimplifyQuestion");
            java.lang.reflect.Method mGet = apiCls.getMethod("getQuestions", int.class, String.class, int.class,
                    String.class, String.class, String.class, String.class, int.class, int.class);
            Object call = mGet.invoke(ready, Integer.valueOf(0), lessonId, Integer.valueOf(2), "", "", "", "1",
                    Integer.valueOf(1), Integer.valueOf(Math.max(count, 1)));
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final JSONArray holder = new JSONArray();
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method mm, Object[] a) {
                            String n = mm.getName();
                            if (n.equals("onReqSuccess")) {
                                try {
                                    Object resp = a == null || a.length == 0 ? null : a[0];
                                    Object list = callObj(resp, "getQuestions");
                                    if (list instanceof java.util.List) {
                                        for (Object q : (java.util.List) list) {
                                            if (holder.length() >= count) break;
                                            JSONObject o = new JSONObject();
                                            o.put("queId", fieldStr(q, "queId"));
                                            o.put("wrappedQueId", fieldStr(q, "wrappedQueId"));
                                            o.put("queType", fieldStr(q, "queType"));
                                            o.put("bankType", fieldStr(q, "bankType"));
                                            holder.put(o);
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                latch.countDown();
                            } else if (n.equals("onReqFailed")) {
                                XLModConfig.logAppend("[注入] 拉题失败: code="
                                        + (a != null && a.length >= 1 ? String.valueOf(a[0]) : "")
                                        + " msg=" + (a != null && a.length >= 2 ? String.valueOf(a[1]) : ""));
                                latch.countDown();
                            }
                            return null;
                        }
                    });
            call.getClass().getMethod("requestV2", cbCls).invoke(call, cb);
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
            for (int i = 0; i < holder.length(); i++) {
                JSONObject o = holder.optJSONObject(i);
                Object q = qCls.newInstance();
                setFieldObj(q, "queId", o.optString("queId"));
                setFieldObj(q, "wrappedQueId", o.optString("wrappedQueId"));
                setFieldObj(q, "queType", o.optString("queType"));
                setFieldObj(q, "bankType", o.optString("bankType"));
                setFieldObj(q, "sort", String.valueOf(i + 1));
                setFieldObj(q, "selectStatus", Integer.valueOf(1));
                setFieldObj(q, "targetQueType", new ArrayList<String>());
                out.add(q);
            }
        } catch (Throwable t) {
            XLModConfig.logAppend("[注入] fetchQuestionObjects 异常: " + t);
        }
        return out;
    }

    private static void setFieldObj(Object o, String name, Object v) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(o, v);
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setFieldIfEmpty(Object o, String name, String v) {
        try {
            if (v == null || v.isEmpty()) return;
            Object cur = fieldObj(o, name);
            if (cur instanceof String && !((String) cur).isEmpty()) return;
            if (cur != null && !(cur instanceof String)) return;
            setFieldObj(o, name, v);
        } catch (Throwable ignored) {
        }
    }

    public static void ensureInit(Activity act) {
        XLModConfig.init(act);
        installCrashLog();
        installLifecycle(act);
        startTimer(act);
        logInjectStatus();
        checkExternalHooks();
        XLModFeatures.startWatcher();          // 前台每分钟拉取远程配置（有变化实时生效）
        XLModFeatures.refreshAsync(act, true); // 启动即拉一次；拉不到 → 锁死（仅留隐藏类功能）
    }

    private static boolean sCrashHookInstalled = false;

    /** 全局未捕获异常捕获：把崩溃栈写入本地（面板可看），再交给系统默认处理 */
    public static void installCrashLog() {
        if (sCrashHookInstalled) return;
        sCrashHookInstalled = true;
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        sb.append(t == null ? "thread?" : t.getName()).append("\n");
                        if (e != null) {
                            java.io.StringWriter sw = new java.io.StringWriter();
                            e.printStackTrace(new java.io.PrintWriter(sw));
                            sb.append(sw.toString());
                        }
                        sb.append("\n----- 崩溃前面包屑（最后 30 步） -----\n").append(breadcrumbText());
                        XLModConfig.crashPut(sb.length() > 6000 ? sb.substring(sb.length() - 6000) : sb.toString());
                    } catch (Throwable t2) {
                    }
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            });
        } catch (Throwable t) {
        }
    }

    private static void trace(String s) {
        XLModConfig.logAppend(s);
    }

    /** 同步引擎激活标志（smali 端无引用读取用；引擎状态变化处调用） */
    private static void syncAutoActive() {
        try {
            boolean act = XLModConfig.isAutoChallenge() && (sAutoStep == 1 || sAutoStep == 2 || sAutoStep == 5);
            XLModConfig.sAutoEngineActive = act ? 1 : 0;
        } catch (Throwable t) {
        }
    }

    /** 弹窗自动确认：在自动打榜流程中，XN 秒后点击弹窗正按钮（XLAlertPopup.initButton 注入点调用） */
    public static void autoClickPositive(final View v) {
        try {
            if (XLModConfig.sAutoEngineActive != 1) return;
            if (v == null) return;
            final long delay = 600;
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (XLModConfig.sAutoEngineActive == 1 && v != null && v.isShown()) {
                            v.performClick();
                        }
                    } catch (Throwable t) {
                    }
                }
            }, delay);
        } catch (Throwable t) {
        }
    }

    private static int sFetchFailCount = 0;

    /** 题目/扣次获取失败处理：0=按原逻辑弹窗；1=自动重试；2=自动退出（超限后按一次消耗计，引擎继续下一局） */
    public static int autoHandleFetchFail() {
        try {
            if (!XLModConfig.isAutoChallenge()) return 0;
            if (sAutoStep != 1 && sAutoStep != 2) return 0;
            sFetchFailCount++;
            trace("自动打榜: 题目获取失败(第" + sFetchFailCount + "次)");
            if (sFetchFailCount <= 3) {
                return 1;
            }
            trace("自动打榜: 重试超限，退出本局按一次消耗计");
            sAutoBattlesThisSubject++;
            sAutoWaitingBattle = false;
            syncAutoActive();
            return 2;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 挑战次数已用完（costChallengeCount 返回未成功）：1=引擎标记本学科结束并退出本局；0=走原逻辑弹窗 */
    public static int autoHandleQuotaExhausted() {
        try {
            if (!XLModConfig.isAutoChallenge()) return 0;
            if (sAutoStep != 1 && sAutoStep != 2) return 0;
            // 服务端：每科每天最多三次 —— 本学科视为打完，直接跳下一学科
            sAutoBattlesThisSubject = XLModConfig.getBattlesPerSubject();
            sAutoWaitingBattle = false;
            syncAutoActive();
            trace("自动打榜: 挑战次数已用完，本学科结束");
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 登录请求里的设备信息串（[Android]版本[Model]品牌 型号 device）脱敏：隐私开启时置空 */
    public static String cleanDeviceInfo(String info) {
        try {
            if (XLModConfig.getPrivacyMode() != 0) {
                return "";
            }
        } catch (Throwable t) {
        }
        return info;
    }

    /** 教师工具：以自定义 userId 启动 学生创建/编辑 页（UPDATE 模式，提交时 saveStudent(classId, name, userId)） */
    public static void launchStudentCreate(Activity act, String userId) {
        XLModConfig.init(act);
        try {
            android.content.Intent i = new android.content.Intent(act, net.xuele.app.schoolmanage.activity.StudentCreateActivity.class);
            i.putExtra("PARAM_TYPE", "TYPE_STUDENT_UPDATE");
            if (userId != null && !userId.isEmpty()) {
                i.putExtra("PARAM_USER_ID", userId);
            }
            act.startActivity(i);
        } catch (Throwable t) {
        }
    }

    public static void autoSignIfNeeded(Activity act) {
        XLModConfig.init(act);
        installCrashLog();
        startTimer(act); // 未经过登录页（直接进主界面）时也保证计时器已启动
        // 云朵助手（独立开关，与自动签到解耦）
        autoCloudIfNeeded(act);
        // 自动打榜启动检查（内部，避免 classes.dex 新增引用）
        autoChallengeTick(act);
        if (!XLModConfig.isAutoSign()) return;
        try {
            String uid = net.xuele.android.common.login.LoginManager.getInstance().getUserId();
            // 指定时间后签到：未签 && 当前时间>=签到时间 → 执行（每次 resume 复查，无需重启）
            if (!XLModConfig.isAfterTime(XLModConfig.getSignTime())) return;
            // 两个签到接口独立去重（旧版共用一个标记会导致只签第一天）
            boolean spaceDone = XLModConfig.isSignedSpaceToday(uid);
            boolean endlessDone = XLModConfig.isSignedEndlessToday(uid);
            if (!spaceDone) {
                trace("自动签到: 用户空间签到 触发 (" + uid + ")");
                doUserSpaceSign(act, uid);
            } else if (!endlessDone) {
                // 仅打印一次状态，便于排查
                trace("自动签到: 用户空间已签，无尽大陆未签");
            }
            if (!endlessDone) {
                trace("自动签到: 无尽大陆签到 触发 (" + uid + ")");
                doEndlessSign(act, uid);
            }
        } catch (Throwable t) {
            // 静默失败
        }
    }

    // ================= 自动打榜引擎 =================
    private static String sAutoDate = "";
    private static int sAutoStep = 0;           // 0=待启动 1=等待开战 2=战斗中 3=等待结果处理
    private static int sAutoSubjectIdx = 0;
    private static int sAutoBattlesThisSubject = 0;
    private static boolean sAutoWaitingBattle = false;
    private static long sAutoLastStart = 0;

    /** 由 MainActivity.onResume(经autoSignIfNeeded) 触发：到点且未完成 → 启动当天打榜 */
    private static void autoChallengeTick(Activity act) {
        try {
            if (!XLModConfig.isAutoChallenge()) return;
            String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
            if (!today.equals(sAutoDate)) {
                sAutoDate = today;
                sAutoStep = 0;
                sAutoSubjectIdx = 0;
                sAutoBattlesThisSubject = 0;
                sAutoWaitingBattle = false;
                syncAutoActive();
            }
            String uid = net.xuele.android.common.login.LoginManager.getInstance().getUserId();
            if (XLModConfig.isChallengeDoneToday(uid)) return;
            if (!XLModConfig.isAfterTime(XLModConfig.getChallengeStartTime())) return;
            if (sAutoWaitingBattle) return;
            if (sAutoStep == 5) {
                // 学科探测页挂起超时 → 回退手动列表
                if (System.currentTimeMillis() - sAutoScanStart > 90000) {
                    trace("自动打榜: 学科探测页超时，回退手动列表");
                    sAutoStep = 0;
                    startWithSubjects(act, parseSubjects(XLModConfig.getChallengeSubjects()));
                }
                return;
            }
            if (sAutoStep != 0) return;
            startScan(act);
        } catch (Throwable t) {
            trace("自动打榜: tick异常 " + t);
        }
    }

    /** 手动触发：清除今日完成标记，重置状态机，立即执行一次完整自动流程 */
    public static void forceRunChallenge(Activity act) {
        XLModConfig.init(act);
        try {
            String uid = net.xuele.android.common.login.LoginManager.getInstance().getUserId();
            XLModConfig.clearChallengeDone(uid);
            String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
            sAutoDate = today;
            sAutoStep = 0;
            sAutoSubjectIdx = 0;
            sAutoBattlesThisSubject = 0;
            sAutoWaitingBattle = false;
            sAutoSubjects = null;
            trace("自动打榜: 手动触发单次流程");
            startScan(act);
        } catch (Throwable t) {
            trace("自动打榜: 手动触发异常 " + t);
        }
    }

    /** 打开金榜题名首页探测学科（自动触发与手动触发共用） */
    private static void startScan(Activity act) {
        try {
            sAutoStep = 5;
            sAutoScanStart = System.currentTimeMillis();
            syncAutoActive();
            trace("自动打榜: 打开金榜题名首页探测学科");
            net.xuele.xuelets.magicwork.v3.activity.CompetitionListActivity.start(act);
        } catch (Throwable t) {
            trace("自动打榜: 首页打开异常 " + t);
            sAutoStep = 0;
            syncAutoActive();
            startWithSubjects(act, parseSubjects(XLModConfig.getChallengeSubjects()));
        }
    }

    private static String[] sAutoSubjects = null;
    private static long sAutoScanStart = 0;

    /** 挂点：CompetitionListActivity 数据成功回调（onReqSuccess） */
    public static void autoOnSubjectsList(final Activity act, net.xuele.xuelets.magicwork.v3.model.RE_GetSubCenterList re) {
        try {
            if (sAutoStep != 5) return;
            java.util.ArrayList<String> subs = new java.util.ArrayList<String>();
            if (re != null && re.wrapper != null) {
                for (net.xuele.xuelets.magicwork.v3.model.RE_GetSubCenterList.WrapperDTO w : re.wrapper) {
                    if (w == null) continue;
                    // 金榜题名首页列表 = 可打项全集；只要带 subjectId 就收（不按 appType 过滤）
                    String sid = w.getSubjectId();
                    if (sid == null || sid.isEmpty()) continue;
                    String sname = w.getSubjectName();
                    if (sname == null || sname.isEmpty()) sname = sid;
                    subs.add(sid + ":" + sname);
                }
            }
            if (sAutoSubjects == null) sAutoSubjects = subs.toArray(new String[0]);
            trace("自动打榜: 首页探测学科数=" + subs.size() + (subs.isEmpty() ? "" : " 首个=" + subs.get(0)));
            if (subs.isEmpty()) {
                sAutoSubjects = parseSubjects(XLModConfig.getChallengeSubjects());
                trace("自动打榜: 首页列表为空，回退手动列表=" + (sAutoSubjects.length));
            }
            // 关闭探测页，进入打榜页
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        act.finish();
                    } catch (Throwable t) {
                    }
                }
            }, 500);
            startWithSubjects(act, sAutoSubjects);
        } catch (Throwable t) {
            trace("自动打榜: 学科解析异常 " + t);
            startWithSubjects(act, parseSubjects(XLModConfig.getChallengeSubjects()));
        }
    }

    /** 挂点：CompetitionListActivity 数据失败回调（onReqFailed） */
    public static void autoOnSubjectsFail(Activity act) {
        try {
            if (sAutoStep != 5) return;
            trace("自动打榜: 首页探测失败，回退手动列表");
            sAutoStep = 0;
            syncAutoActive();
            sAutoSubjects = parseSubjects(XLModConfig.getChallengeSubjects());
            startWithSubjects(act, sAutoSubjects);
        } catch (Throwable t) {
        }
    }

    private static void startWithSubjects(Activity act, String[] subs) {
        try {
            if (subs == null || subs.length == 0) {
                trace("自动打榜: 无学科可打，今日放弃");
                return;
            }
            sAutoSubjects = subs;
            sAutoSubjectIdx = 0;
            sAutoBattlesThisSubject = 0;
            sAutoStep = 1;
            sFetchFailCount = 0;
            syncAutoActive();
            trace("自动打榜: 启动 学科=" + subs[0] + " 列表=" + subs.length);
            launchRank(act, subs[0]);
        } catch (Throwable t) {
        }
    }

    private static String[] parseSubjects(String line) {
        if (line == null || line.isEmpty()) return new String[0];
        try {
            String[] parts = line.split(",");
            java.util.ArrayList<String> out = new java.util.ArrayList<String>();
            for (String p : parts) {
                if (p == null) continue;
                p = p.trim();
                if (p.isEmpty()) continue;
                out.add(p);
            }
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static void launchRank(Activity act, String subjectEntry) {
        try {
            String[] kv = subjectEntry.split(":", 2);
            if (kv.length == 0 || kv[0].isEmpty()) return;
            // 注意：必须用 ChallengeStudentRankActivity（学生金榜题名页，App 路由同款）；
            // 基类 ChallengeRankActivity 的 initHeadView 不加载头部，直接启动会因找不到月份视图 NPE
            android.content.Intent i = new android.content.Intent(act, net.xuele.xuelets.challenge.activity.ChallengeStudentRankActivity.class);
            i.putExtra(net.xuele.xuelets.challenge.activity.ChallengeRankActivity.PARAM_SUBJECT_ID, kv[0].trim());
            i.putExtra("subject_name", kv.length > 1 ? kv[1].trim() : kv[0].trim());
            // 从当前 activity 启动（会压在 MainActivity 之上）
            act.startActivity(i);
        } catch (Throwable t) {
        }
    }

    /** 挂点在 ChallengeRankActivity.onCreate 末尾（首次进入）与 onResume（战斗结束返回/跨学科） */
    public static void autoOnRankResume(final Activity act) {
        try {
            if (!XLModConfig.isAutoChallenge()) return;
            if (sAutoStep != 1) return;
            syncAutoActive();
            trace("自动打榜: rank恢复 step=1 本学科=" + sAutoBattlesThisSubject + "/" + XLModConfig.getBattlesPerSubject());
            String[] subs = sAutoSubjects != null ? sAutoSubjects : parseSubjects(XLModConfig.getChallengeSubjects());
            if (subs.length == 0) return;
            // 看门狗：上次开战指令后 15 秒无任何题目出现 → 视为云朵不足/次数已尽，跳下一学科
            if (sAutoWaitingBattle && System.currentTimeMillis() - sAutoLastStart > 15000) {
                trace("自动打榜: 看门狗-15秒无题，跳过该次");
                sAutoWaitingBattle = false;
                sAutoBattlesThisSubject++;
                sAutoStep = 1;
            }            if (sAutoBattlesThisSubject >= XLModConfig.getBattlesPerSubject()) {
                // 本学科完成：切换下一学科
                if (sAutoSubjectIdx + 1 < subs.length) {
                    sAutoSubjectIdx++;
                    sAutoBattlesThisSubject = 0;
                    final String next = subs[sAutoSubjectIdx];
                    trace("自动打榜: 换学科 -> " + next);
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                launchRank(act, next);
                                act.finish();
                            } catch (Throwable t) {
                                trace("自动打榜: 换学科异常 " + t);
                            }
                        }
                    }, XLModConfig.getChallengeEntryDelay());
                } else {
                    sAutoStep = 0;
                    syncAutoActive();
                    try {
                        net.xuele.android.common.login.LoginManager lm = net.xuele.android.common.login.LoginManager.getInstance();
                        XLModConfig.markChallengeDone(lm.getUserId());
                    } catch (Throwable t) {
                    }
                    trace("自动打榜: 全部完成，标记今日完成");
                    android.os.Handler h2 = new android.os.Handler(android.os.Looper.getMainLooper());
                    h2.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                act.finish();
                            } catch (Throwable t) {
                            }
                        }
                    }, XLModConfig.getChallengeExitDelay());
                }
                return;
            }
            // 发起同学对战（随机匹配）。rank 页已加载时 goChallengeStudent 内部会刷新云朵/次数
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        sAutoLastStart = System.currentTimeMillis();
                        sAutoWaitingBattle = true;
                        sFetchFailCount = 0;
                        syncAutoActive();
                        trace("自动打榜: 发起同学对战(点击FAB)");
                        ((net.xuele.xuelets.challenge.activity.ChallengeRankActivity) act).onFabMenuItemClick(2);
                    } catch (Throwable t) {
                        trace("自动打榜: 发起异常 " + t);
                        sAutoWaitingBattle = false;
                        syncAutoActive();
                    }
                }
            }, XLModConfig.getChallengeEntryDelay());
        } catch (Throwable t) {
            trace("自动打榜: rankResume异常 " + t);
        }
    }

    /** 挂点在悬浮窗 showAnswerFloat 内（每题显示时）：战斗中每道题都延迟自动提交 */
    private static void autoOnQuestionShown(final Activity act) {
        try {
            if (!XLModConfig.isAutoChallenge()) return;
            if (sAutoWaitingBattle) {
                // 本局第一题：进入战斗状态
                sAutoWaitingBattle = false;
                sAutoStep = 2;
                sFetchFailCount = 0;
                syncAutoActive();
            } else if (sAutoStep != 2) {
                // 不在战斗中：不处理
                return;
            }
            trace("自动打榜: 题目出现，定时提交");
            final int idel = XLModConfig.getChallengeAnswerDelay();
            final boolean[] done = {false};
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (done[0]) return;
                        done[0] = true;
                        if (act instanceof net.xuele.xuelets.challenge.activity.ChallengeQuestionBaseActivity) {
                            ((net.xuele.xuelets.challenge.activity.ChallengeQuestionBaseActivity) act).submitSingleQuestion(false);
                        }
                    } catch (Throwable t) {
                        trace("自动打榜: 提交异常 " + t);
                    }
                }
            }, Math.max(200, idel));
            // 兜底：7 秒后若仍未提交（异常情况）再补一次
            android.os.Handler h2 = new android.os.Handler(android.os.Looper.getMainLooper());
            h2.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!done[0]) {
                            done[0] = true;
                            if (act instanceof net.xuele.xuelets.challenge.activity.ChallengeQuestionBaseActivity) {
                                ((net.xuele.xuelets.challenge.activity.ChallengeQuestionBaseActivity) act).submitSingleQuestion(false);
                            }
                        }
                    } catch (Throwable t) {
                    }
                }
            }, Math.max(300, idel + 5000));
        } catch (Throwable t) {
            trace("自动打榜: questionShown异常 " + t);
        }
    }

    /** 挂点在 claimBattleCloudAfterResult 内（结果页 initAchieve）：延迟后自动关闭结果页回排行榜 */
    private static void autoResultExit(final Activity act) {
        try {
            if (!XLModConfig.isAutoChallenge()) return;
            if (sAutoStep != 2) return;
            sAutoStep = 1; // 返回排行榜续战
            sAutoBattlesThisSubject++;
            sFetchFailCount = 0;
            syncAutoActive();
            trace("自动打榜: 结果页退出 本学科已打=" + sAutoBattlesThisSubject);
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        act.finish();
                    } catch (Throwable t) {
                    }
                }
            }, XLModConfig.getChallengeExitDelay());
        } catch (Throwable t) {
            trace("自动打榜: resultExit异常 " + t);
        }
    }

    public static void autoCloudIfNeeded(Activity act) {
        XLModConfig.init(act);
        if (!XLModConfig.isAutoCloud()) return;
        try {
            String uid = net.xuele.android.common.login.LoginManager.getInstance().getUserId();
            if (XLModConfig.isCloudClaimedToday(uid)) return;
            XLModConfig.markCloudClaimed(uid);
            claimTaskClouds(act);
            claimBirthdayCloud(act);
        } catch (Throwable t) {
        }
    }

    /** 手动一键领取（Mod 面板按钮） */
    public static void manualClaimAll(Activity act) {
        XLModConfig.init(act);
        try {
            String uid = net.xuele.android.common.login.LoginManager.getInstance().getUserId();
            XLModConfig.markCloudClaimed(uid);
            claimTaskClouds(act);
            claimBirthdayCloud(act);
        } catch (Throwable t) {
        }
    }

    // ===== 云朵：任务点领奖 =====
    private static void claimTaskClouds(final Activity act) {
        try {
            net.xuele.xuelets.app.user.util.Api.ready.getUserTaskPoint()
                    .requestV2((androidx.lifecycle.l) act,
                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.xuelets.app.user.cloudflower.RE_GetUserIntegral>() {
                                @Override
                                public void onReqFailed(String s1, String s2) {
                                }

                                @Override
                                public void onReqSuccess(net.xuele.xuelets.app.user.cloudflower.RE_GetUserIntegral re) {
                                    try {
                                        if (re == null || re.getIntegralTasks() == null) return;
                                        final java.util.ArrayList<net.xuele.xuelets.app.user.cloudflower.UserIntegralInfo> pend = new java.util.ArrayList<net.xuele.xuelets.app.user.cloudflower.UserIntegralInfo>();
                                        for (net.xuele.xuelets.app.user.cloudflower.UserIntegralInfo t : re.getIntegralTasks()) {
                                            if (t != null && t.getFinishStatus() == 1) {
                                                pend.add(t);
                                            }
                                        }
                                        claimNextTask(act, pend, 0);
                                    } catch (Throwable t) {
                                    }
                                }
                            });
        } catch (Throwable t) {
        }
    }

    private static void claimNextTask(final Activity act, final java.util.ArrayList<net.xuele.xuelets.app.user.cloudflower.UserIntegralInfo> pend, final int idx) {
        try {
            if (idx >= pend.size()) return;
            final net.xuele.xuelets.app.user.cloudflower.UserIntegralInfo task = pend.get(idx);
            net.xuele.xuelets.app.user.util.Api.ready.receiveIntegral(task.getUserTaskId())
                    .requestV2((androidx.lifecycle.l) act,
                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.xuelets.app.user.personinfo.model.RE_ReceiveIntegral>() {
                                @Override
                                public void onReqFailed(String s1, String s2) {
                                    claimNextTask(act, pend, idx + 1);
                                }

                                @Override
                                public void onReqSuccess(net.xuele.xuelets.app.user.personinfo.model.RE_ReceiveIntegral re) {
                                    claimNextTask(act, pend, idx + 1);
                                }
                            });
        } catch (Throwable t) {
        }
    }

    // ===== 云朵：生日礼 =====
    private static void claimBirthdayCloud(final Activity act) {
        try {
            net.xuele.xuelets.app.user.util.Api.ready.getBirthdayRewardInfo()
                    .requestV2((androidx.lifecycle.l) act,
                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.xuelets.app.user.homepage.model.RE_BirthdayRewardInfo>() {
                                @Override
                                public void onReqFailed(String s1, String s2) {
                                }

                                @Override
                                public void onReqSuccess(net.xuele.xuelets.app.user.homepage.model.RE_BirthdayRewardInfo re) {
                                    try {
                                        if (re == null || re.wrapper == null) return;
                                        if (re.wrapper.has == 1) {
                                            net.xuele.xuelets.app.user.util.Api.ready.getBirthdayReward()
                                                    .requestV2((androidx.lifecycle.l) act,
                                                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.android.core.http.RE_Result>() {
                                                                @Override
                                                                public void onReqFailed(String s1, String s2) {
                                                                }

                                                                @Override
                                                                public void onReqSuccess(net.xuele.android.core.http.RE_Result r) {
                                                                }
                                                            });
                                        }
                                    } catch (Throwable t) {
                                    }
                                }
                            });
        } catch (Throwable t) {
        }
    }

    /** 每局挑战结束（结果页）自动领取胜利云朵（服务端按胜负/已领校验） */
    public static void claimBattleCloudAfterResult(Activity act, String challengeId, String monthSubject) {
        XLModConfig.init(act);
        autoResultExit(act);
        try {
            if (challengeId == null || challengeId.isEmpty()) return;
            if (monthSubject == null || monthSubject.isEmpty()) return;
            // 1) 自动领获胜云朵（开关控制）
            if (XLModConfig.isAutoCloud()) {
                net.xuele.xuelets.challenge.util.ChallengeApi.ready.getCloudAward(null, challengeId, monthSubject)
                        .requestV2((androidx.lifecycle.l) act,
                                new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.xuelets.challenge.model.re.RE_ReceiveAward>() {
                                    @Override
                                    public void onReqFailed(String s1, String s2) {
                                    }

                                    @Override
                                    public void onReqSuccess(net.xuele.xuelets.challenge.model.re.RE_ReceiveAward r) {
                                    }
                                });
            }
            // 2) 赛后采集全部答案 → 本地知识库（普通挑战下一局自动作答用）
            if (XLModConfig.isAutoAnswer() || XLModConfig.isShowAnswerFloat() || XLModConfig.isDebugFloat()) {
                harvestAnswers(act, challengeId, monthSubject);
            }
        } catch (Throwable t) {
        }
    }

    /** 赛后从详情数据采集每题答案，写入知识库（S| 正确ID列表 / F| 填空文本序列） */
    private static void harvestAnswers(Activity act, String challengeId, String monthSubject) {
        try {
            net.xuele.xuelets.challenge.util.ChallengeDetailHelper.loadQuestionList(
                    challengeId, monthSubject,
                    new net.xuele.xuelets.challenge.util.ChallengeDetailHelper.LoadDataInterface() {
                        @Override
                        public void loadQuestionFail(String s) {
                        }

                        @Override
                        public void loadQuestionListSuccess(java.util.ArrayList<M_ChallengeQuestion> list, java.util.HashMap<Integer, net.xuele.android.ui.question.ChallengeUserAnswer> map) {
                            try {
                                if (list == null) return;
                                for (M_ChallengeQuestion q : list) {
                                    if (q == null || q.questionId == null || q.questionId.isEmpty()) continue;
                                    sDetailMap.put(q.questionId, q);
                                    java.util.List<AnswersBean> ans = q.answers;
                                    if (ans == null) continue;
                                    if (parseQType(q) == 3) {
                                        // 填空：逐空文本（详情接口若带 answerContent 才有效）
                                        boolean any = false;
                                        for (AnswersBean a : ans) {
                                            if (a.answerContent != null && !a.answerContent.isEmpty()) {
                                                any = true;
                                                break;
                                            }
                                        }
                                        if (!any) continue;
                                        StringBuilder sb = new StringBuilder("F|");
                                        for (int i = 0; i < ans.size(); i++) {
                                            if (i > 0) sb.append("\u0001");
                                            sb.append(ans.get(i).answerContent == null ? "" : ans.get(i).answerContent);
                                        }
                                        XLModConfig.kbPut(q.questionId, sb.toString());
                                    } else {
                                        StringBuilder ids = new StringBuilder("S|");
                                        int n = 0;
                                        for (AnswersBean a : ans) {
                                            if (a.isCorrect != null && "1".equals(a.isCorrect.trim())) {
                                                if (n > 0) ids.append(",");
                                                ids.append(a.answerId);
                                                n++;
                                            }
                                        }
                                        if (n > 0) {
                                            XLModConfig.kbPut(q.questionId, ids.toString());
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                            }
                        }
                    });
        } catch (Throwable t) {
        }
    }

    private static void doUserSpaceSign(final Activity act, final String uid) {
        try {
            net.xuele.xuelets.app.user.util.Api.ready.doTodaySignIn()
                    .requestV2((androidx.lifecycle.l) act,
                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.xuelets.app.user.homepage.model.RE_SignInDetail>() {
                                @Override
                                public void onReqFailed(String s1, String s2) {
                                    trace("自动签到: 用户空间签到 失败 " + (s1 == null ? "" : s1) + " / " + (s2 == null ? "" : s2));
                                }

                                @Override
                                public void onReqSuccess(net.xuele.xuelets.app.user.homepage.model.RE_SignInDetail r) {
                                    XLModConfig.markSpaceSigned(uid);
                                    trace("自动签到: 用户空间签到 成功");
                                }
                            });
        } catch (Throwable t) {
            trace("自动签到: 用户空间签到 异常 " + t);
        }
    }

    private static void doEndlessSign(final Activity act, final String uid) {
        try {
            net.xuele.app.learnrecord.util.LearnRecordApi.ready.userSign()
                    .requestV2((androidx.lifecycle.l) act,
                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.app.learnrecord.model.RE_UserSign>() {
                                @Override
                                public void onReqFailed(String s1, String s2) {
                                    trace("自动签到: 无尽大陆签到 失败 " + (s1 == null ? "" : s1) + " / " + (s2 == null ? "" : s2));
                                }

                                @Override
                                public void onReqSuccess(net.xuele.app.learnrecord.model.RE_UserSign r) {
                                    XLModConfig.markEndlessSigned(uid);
                                    trace("自动签到: 无尽大陆签到 成功");
                                }
                            });
        } catch (Throwable t) {
            trace("自动签到: 无尽大陆签到 异常 " + t);
        }
    }

    // ================= 榜单用户ID =================
    public static String withUid(String name, String uid) {
        if (!XLModConfig.isShowRankUserId()) return name;
        if (name == null) name = "";
        if (uid == null || uid.isEmpty()) return name;
        return name + " (" + uid + ")";
    }

    // ================= 自动作答预填 =================
    public static ChallengeUserAnswer buildAutoAnswer(M_ChallengeQuestion q, ChallengeUserAnswer ua) {
        if (!XLModConfig.isAutoAnswer()) return ua;
        if (ua == null || q == null) return ua;
        try {
            // 0) 知识库（赛后采集）优先——普通挑战实时数据无答案标记
            if (q.questionId != null && !q.questionId.isEmpty() && applyKb(q.questionId, q, ua)) {
                return ua;
            }
            int t = parseQType(q);
            List<AnswersBean> answers = q.answers;
            if (answers == null || answers.isEmpty()) return ua;
            if (t == 3) { // 填空：按空位顺序填入正确答案文本
                ua.answerContentList.clear();
                for (AnswersBean a : answers) {
                    ua.answerContentList.add(a.answerContent == null ? "" : a.answerContent);
                }
            } else if (t == 11 || t == 12 || t == 2) { // 单选/多选/判断
                ua.answerIdList.clear();
                ua.answerContentList.clear();
                boolean single = (t == 11);
                for (AnswersBean a : answers) {
                    if (a.isCorrect != null && "1".equals(a.isCorrect.trim())) {
                        ua.answerIdList.add(a.answerId);
                        String c = (a.sortid != null && !a.sortid.isEmpty()) ? a.sortid : a.answerContent;
                        if (c == null) c = "";
                        ua.answerContentList.add(c);
                        if (single) break;
                    }
                }
            }
            // 51 听力 / 52 口语：答案不在题目数据中，交由悬浮窗展示解析
        } catch (Throwable t) {
        }
        return ua;
    }

    private static int parseQType(M_ChallengeQuestion q) {
        if (q == null || q.qType == null) return 0;
        try {
            return Integer.parseInt(q.qType.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 知识库应用：S|=正确ID列表（选项/判断），F|=填空文本序列。命中返回 true */
    private static boolean applyKb(String qid, M_ChallengeQuestion q, ChallengeUserAnswer ua) {
        try {
            String kb = XLModConfig.kbGet(qid);
            if (kb == null || kb.isEmpty()) return false;
            if (kb.startsWith("S|")) {
                if (q.answers == null) return false;
                java.util.HashSet<String> set = new java.util.HashSet<String>();
                for (String id : kb.substring(2).split(",")) {
                    if (id != null && !id.trim().isEmpty()) set.add(id.trim());
                }
                if (set.isEmpty()) return false;
                ua.answerIdList.clear();
                ua.answerContentList.clear();
                for (AnswersBean a : q.answers) {
                    if (set.contains(a.answerId)) {
                        ua.answerIdList.add(a.answerId);
                        String c = (a.sortid != null && !a.sortid.isEmpty()) ? a.sortid : a.answerContent;
                        ua.answerContentList.add(c == null ? "" : c);
                    }
                }
                return !ua.answerIdList.isEmpty();
            } else if (kb.startsWith("L|")) {
                // 听写题：标准答案文本
                ua.answerContentList.clear();
                ua.answerContentList.add(kb.substring(2));
                return true;
            } else if (kb.startsWith("F|")) {
                String[] parts = kb.substring(2).split("\u0001");
                if (parts.length == 0) return false;
                ua.answerIdList.clear();
                ua.answerContentList.clear();
                for (String p : parts) {
                    ua.answerContentList.add(p == null ? "" : p);
                }
                return true;
            }
        } catch (Throwable t) {
        }
        return false;
    }

    /**
     * 提交前调用：把已获取的答案（详情数据优先、判题接口缓存其次）写入用户答案。
     * 解决普通挑战本地无 isCorrect 时自动作答失效的问题。
     */
    public static ChallengeUserAnswer applyApiAnswers(M_ChallengeQuestion q, ChallengeUserAnswer ua) {
        if (!XLModConfig.isAutoAnswer()) return ua;
        if (ua == null || q == null) return ua;
        try {
            String qid = q.questionId == null ? "" : q.questionId;
            // 0) 听力(51)：标准答案文本（详情 sContent / 知识库），需先填，生成判题模型时才能附带
            if (parseQType(q) == 51) {
                String lt = listenAnswer(qid);
                if (lt != null && !lt.isEmpty()) {
                    ua.answerContentList.clear();
                    ua.answerContentList.add(lt);
                    return ua;
                }
            }
            if (q.answers == null) return ua;
            // 1) 知识库（赛后采集）优先
            if (!qid.isEmpty() && applyKb(qid, q, ua)) {
                return ua;
            }
            // 1) 详情数据升级优先（含填空文本与 isCorrect）
            M_ChallengeQuestion dq = sDetailMap.get(qid);
            if (dq != null && dq.answers != null) {
                boolean anyMark = false;
                for (AnswersBean a : dq.answers) {
                    if (a.isCorrect != null && "1".equals(a.isCorrect.trim())) {
                        anyMark = true;
                        break;
                    }
                }
                if (anyMark) {
                    ua.answerIdList.clear();
                    ua.answerContentList.clear();
                    for (AnswersBean a : dq.answers) {
                        if (a.isCorrect != null && "1".equals(a.isCorrect.trim())) {
                            ua.answerIdList.add(a.answerId);
                            String c = (a.sortid != null && !a.sortid.isEmpty()) ? a.sortid : a.answerContent;
                            ua.answerContentList.add(c == null ? "" : c);
                        }
                    }
                    return ua;
                }
                // 填空：详情数据按空位顺序给出答案文本
                if (parseQType(dq) == 3 && dq.answers.size() > 0 && dq.answers.get(0).answerContent != null) {
                    ua.answerIdList.clear();
                    ua.answerContentList.clear();
                    for (AnswersBean a : dq.answers) {
                        ua.answerContentList.add(a.answerContent == null ? "" : a.answerContent);
                    }
                    return ua;
                }
            }
            // 2) 判题接口缓存的正确答案ID
            java.util.List<String> right = sApiRightIds.get(qid);
            if (right != null && !right.isEmpty()) {
                ua.answerIdList.clear();
                ua.answerContentList.clear();
                for (AnswersBean a : q.answers) {
                    if (right.contains(a.answerId)) {
                        ua.answerIdList.add(a.answerId);
                        String c = (a.sortid != null && !a.sortid.isEmpty()) ? a.sortid : a.answerContent;
                        ua.answerContentList.add(c == null ? "" : c);
                    }
                }
                if (!ua.answerIdList.isEmpty()) return ua;
            }
        } catch (Throwable t) {
        }
        return ua;
    }

    // ================= 听力(51)自动作答 =================
    /** 取听力标准答案：知识库 > 详情缓存 */
    private static String listenAnswer(String qid) {
        try {
            if (qid == null || qid.isEmpty()) return "";
            String kb = XLModConfig.kbGet(qid);
            if (kb != null && kb.startsWith("L|")) return kb.substring(2);
            String t = sDetailListenText.get(qid);
            return t == null ? "" : t;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 听力答案回填：写入用户答案映射（供提交） + 尽力同步到界面输入框 */
    private static void applyListenAnswer(Activity act, net.xuele.xuelets.challenge.util.ChallengeParamHelper ph,
                                          M_ChallengeQuestion q, int pos) {
        try {
            if (!XLModConfig.isAutoAnswer()) return;
            if (act == null || q == null || parseQType(q) != 51) return;
            String qid = q.questionId == null ? "" : q.questionId;
            if (qid.isEmpty()) return;
            String text = listenAnswer(qid);
            if (text == null || text.isEmpty()) return;
            if (ph != null && ph.mUserAnswerMap != null) {
                net.xuele.android.ui.question.ChallengeUserAnswer ua = ph.mUserAnswerMap.get(pos);
                if (ua != null) {
                    ua.answerContentList.clear();
                    ua.answerContentList.add(text);
                }
            }
            setListenEditText(act, text);
            trace("自动作答: 听力填答 " + qid);
        } catch (Throwable t) {
        }
    }

    /** 按当前题目上下文回填（详情异步回来后调用） */
    private static void applyListenAnswerCurrent(Activity act) {
        try {
            if (sFloatPH == null || sFloatPos < 0) return;
            if (sFloatPH.mQuestionList == null || sFloatPos >= sFloatPH.mQuestionList.size()) return;
            applyListenAnswer(act, sFloatPH, sFloatPH.mQuestionList.get(sFloatPos), sFloatPos);
        } catch (Throwable t) {
        }
    }

    /** 反射给当前听力题输入框设置文本（字段未被混淆：mPagerAdapter/getCurrentPrimaryItem/mEtAnswer） */
    private static void setListenEditText(Activity act, String text) {
        if (fillListenEditTextByAdapter(act, text)) return;
        fillListenEditTextByFragments(act, text);
    }

    /** 路径1：答题页 mPagerAdapter → getCurrentPrimaryItem() → 当前题目 Fragment 的 mEtAnswer */
    private static boolean fillListenEditTextByAdapter(Activity act, String text) {
        try {
            java.lang.reflect.Field f = findField(act.getClass(), "mPagerAdapter");
            if (f == null) return false;
            f.setAccessible(true);
            Object adapter = f.get(act);
            if (adapter == null) return false;
            Object frag = adapter.getClass().getMethod("getCurrentPrimaryItem").invoke(adapter);
            return setFragmentListenText(frag, text);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 路径2：FragmentManager（含 ViewPager 内的子 Fragment）逐个找带 mEtAnswer 的听力题 Fragment */
    private static boolean fillListenEditTextByFragments(Activity act, String text) {
        try {
            Object fm = act.getClass().getMethod("getSupportFragmentManager").invoke(act);
            if (fm == null) return false;
            java.util.List<?> fs = (java.util.List<?>) fm.getClass().getMethod("getFragments").invoke(fm);
            if (fs == null) return false;
            for (int i = fs.size() - 1; i >= 0; i--) {
                Object frag = fs.get(i);
                if (frag == null) continue;
                if (setFragmentListenText(frag, text)) return true;
                try {
                    Object cfm = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    if (cfm == null) continue;
                    java.util.List<?> cs = (java.util.List<?>) cfm.getClass().getMethod("getFragments").invoke(cfm);
                    for (int j = cs.size() - 1; j >= 0; j--) {
                        if (setFragmentListenText(cs.get(j), text)) return true;
                    }
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
        }
        return false;
    }

    /** 给某个题目 Fragment 的听力输入框写入文本（内容相同则不重复 set，避免打断用户输入） */
    private static boolean setFragmentListenText(Object frag, String text) {
        if (frag == null || text == null || text.isEmpty()) return false;
        try {
            Object et = getFieldValue(frag, "mEtAnswer");
            if (et == null) return false;
            Object cur = et.getClass().getMethod("getText").invoke(et);
            if (cur != null && text.equals(cur.toString())) return true;
            et.getClass().getMethod("setText", CharSequence.class).invoke(et, text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getFieldValue(Object o, String name) {
        if (o == null) return null;
        java.lang.reflect.Field f = findField(o.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 听力题"提交前回填"（由 smali 注入在 ChallengeListenQuestionFragment.checkUserAnswered 顶部调用）。
     * 提交时 App 是拿输入框内容重建 answerContentList 的：答案取到了但没写进输入框 = 判定未作答，
     * 这条注入保证"无论详情接口什么时候回来"，提交那一刻输入框里一定有答案。
     */
    public static void listenFillAnswer(Object fragment) {
        try {
            if (fragment == null) return;
            if (!XLModConfig.isAutoAnswer()) return;
            Object ua = getFieldValue(fragment, "mUserAnswer");
            String text = "";
            java.util.List list = null;
            if (ua != null) {
                Object l = getFieldValue(ua, "answerContentList");
                if (l instanceof java.util.List) {
                    list = (java.util.List) l;
                    if (!list.isEmpty() && list.get(0) != null) text = String.valueOf(list.get(0));
                }
                if (text.isEmpty()) {
                    Object sc = getFieldValue(ua, "sContent");
                    if (sc != null) text = String.valueOf(sc);
                }
            }
            String qid = "";
            Object q = getFieldValue(fragment, "mQuestion");
            if (q != null) {
                Object qidO = getFieldValue(q, "questionId");
                if (qidO != null) qid = String.valueOf(qidO);
            }
            if (text.isEmpty() && !qid.isEmpty()) text = listenAnswer(qid);
            if (text == null || text.isEmpty()) return;
            if (list != null && (list.isEmpty() || !text.equals(String.valueOf(list.get(0))))) {
                list.clear();
                list.add(text);
            }
            boolean ok = setFragmentListenText(fragment, text);
            XLModConfig.logAppend("[听力] 提交前回填: " + qid + " → " + text + (ok ? "" : "（输入框未命中）"));
        } catch (Throwable t) {
        }
    }

    // ================= 答案悬浮窗 =================
    private static TextView sFloatView;
    private static boolean sFloatExpanded = true;

    public static void clearAnswerFloat() {
        sFloatView = null;
        sFloatExpanded = true;
        sLocalS = null;
        sApiS = null;
        sDetailS = null;
    }

    public static void showAnswerFloat(Activity act, net.xuele.xuelets.challenge.util.ChallengeParamHelper ph, int pos) {
        XLModConfig.init(act);
        autoOnQuestionShown(act);
        if (act == null || ph == null) return;
        // ===== 自动作答复用（与"答案悬浮窗"开关解耦）=====
        // 听力题(51)的答案只能从详情接口拿；而详情拉取原先写在 isShowAnswerFloat() 判断之后，
        // 于是"只开自动作答、不开悬浮窗"时永远拿不到答案 → 提交时输入框是空的。
        // 这里把题上下文登记 + 详情拉取提前到开关判断之前（同学对战同样适用）。
        if (XLModConfig.isAutoAnswer()) {
            try {
                java.util.ArrayList<M_ChallengeQuestion> qList0 = ph.mQuestionList;
                if (qList0 != null && pos >= 0 && pos < qList0.size()) {
                    M_ChallengeQuestion q0 = qList0.get(pos);
                    if (q0 != null) {
                        sFloatQId = q0.questionId == null ? "" : q0.questionId;
                        sFloatPH = ph;
                        sFloatPos = pos;
                        String logId0 = ph.logId == null ? "" : ph.logId;
                        String rk0 = ph.randomKey == null ? "" : ph.randomKey;
                        sFloatChallengeId = logId0.isEmpty() ? rk0 : logId0;
                        try {
                            if (ph.mHelper != null) sFloatMonthSubject = ph.mHelper.getCurMonthSubject();
                        } catch (Throwable t) {
                        }
                        if (parseQType(q0) == 51) {
                            applyListenAnswer(act, ph, q0, pos);
                            fetchByDetail(act); // 结果 → sDetailListenText → 回来后 applyListenAnswerCurrent 回填输入框
                            final Activity fAct0 = act;
                            final net.xuele.xuelets.challenge.util.ChallengeParamHelper fPh0 = ph;
                            final M_ChallengeQuestion fQ0 = q0;
                            final int fPos0 = pos;
                            act.getWindow().getDecorView().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    applyListenAnswer(fAct0, fPh0, fQ0, fPos0);
                                }
                            }, 1800);
                        }
                    }
                }
            } catch (Throwable t) {
            }
        }
        if (!XLModConfig.isShowAnswerFloat()) return;
        if (act == null || ph == null) return;
        try {
            java.util.ArrayList<M_ChallengeQuestion> qList = ph.mQuestionList;
            if (qList == null) return;
            if (pos < 0 || pos >= qList.size()) return;
            M_ChallengeQuestion q = qList.get(pos);
            if (q == null) return;
            String qid = q.questionId == null ? "" : q.questionId;
            sFloatQId = qid;
            String logId = ph.logId == null ? "" : ph.logId;
            String randomKey = ph.randomKey == null ? "" : ph.randomKey;
            String monthSubject = "";
            try {
                if (ph.mHelper != null) {
                    monthSubject = ph.mHelper.getCurMonthSubject();
                }
            } catch (Throwable t) {
            }
            // 详情接口ID：优先 挑战记录ID(logId)，其次 圆次 randomKey（普通挑战开局即由服务器下发）
            sFloatChallengeId = logId.isEmpty() ? randomKey : logId;
            sFloatMonthSubject = monthSubject;
            boolean isClassmate = ph.isChallengeClassmate;

            // ===== 听力题自动作答：先应用已知答案（知识库/已拉取的详情）=====
            // 详情拉取已提前到悬浮窗开关判断之前（见方法开头），这里只做"应用 + 延迟再应用"
            sFloatPH = ph;
            sFloatPos = pos;
            if (parseQType(q) == 51 && XLModConfig.isAutoAnswer()) {
                applyListenAnswer(act, ph, q, pos);
                final Activity fAct = act;
                final net.xuele.xuelets.challenge.util.ChallengeParamHelper fPh = ph;
                final M_ChallengeQuestion fQ = q;
                final int fPos = pos;
                act.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyListenAnswer(fAct, fPh, fQ, fPos);
                    }
                }, 1800);
            }

            if (isClassmate) {
                // 同学对战：本地数据 isCorrect 完全正确——只展示本地答案，
                // 禁用接口/详情（避免错误答案被缓存进自动作答）
                renderState(sLocalS = ensureState(act, sLocalS, "本地|", 110), "本地| " + buildAnswerText(q), qid);
                if (XLModConfig.isDebugFloat()) {
                    renderState(sApiS = ensureState(act, sApiS, "接口|", 195), "接口| 已禁用（对战直用本地）", qid);
                    renderState(sDetailS = ensureState(act, sDetailS, "详情|", 280), "详情| 已禁用（对战直用本地）", qid);
                }
                return;
            }

            if (XLModConfig.isDebugFloat()) {
                // 调试模式：本地/接口/详情 三个窗口同时显示（必须全部创建，否则只出一个）
                sLocalS = ensureState(act, sLocalS, "本地|", 110);
                sApiS = ensureState(act, sApiS, "接口|", 195);
                sDetailS = ensureState(act, sDetailS, "详情|", 280);
                renderState(sLocalS, "本地| " + buildAnswerText(q), qid);
                renderState(sApiS, "接口| 查询中…", qid);
                renderState(sDetailS, "详情| 查询中…" + (sFloatChallengeId.isEmpty() ? "（无挑战ID）" : ""), qid);
                fetchAnswerByCorrect(act, q); // 结果写到 sApiS
                fetchByDetail(act);            // 结果写到 sDetailS
                return;
            }
            String text = buildAnswerText(q);
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            if (sFloatView == null || sFloatView.getParent() != decor) {
                TextView tv = new TextView(act);
                tv.setTextSize(14);
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xCC212F3D);
                bg.setCornerRadius(dp(act, 8));
                tv.setBackground(bg);
                tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                tv.setMaxWidth(dp(act, 300));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.RIGHT);
                lp.topMargin = dp(act, 120);
                lp.rightMargin = dp(act, 12);
                tv.setLayoutParams(lp);
                tv.setOnTouchListener(new MoveListener());
                decor.addView(tv);
                sFloatView = tv;
            }
            // 新题目时恢复展开
            Object oldTag = sFloatView.getTag();
            if (oldTag == null || !text.equals(oldTag)) {
                sFloatExpanded = true;
            }
            sFloatView.setText(sFloatExpanded ? text : "答");
            sFloatView.setTag(text);
            // 答案获取：0=本地 1=判题接口(题目ID) 2=详情接口(挑战ID)
            int fm = XLModConfig.getAnswerFetchMode();
            if (fm == 1) {
                fetchAnswerByCorrect(act, q);
            } else if (fm == 2) {
                fetchByDetail(act);
            }
        } catch (Throwable t) {
        }
    }

    // ===== 调试三窗状态 =====
    static class FloatState {
        TextView view;
        boolean expanded = true;
        String text = "";
        String label = "";
        int top;
    }

    private static FloatState sLocalS;
    private static FloatState sApiS;
    private static FloatState sDetailS;

    private static FloatState ensureState(Activity act, FloatState st, String label, int top) {
        if (st == null) {
            st = new FloatState();
            st.label = label;
            st.top = top;
        }
        if (st.view == null || st.view.getParent() != act.getWindow().getDecorView()) {
            if (st.view != null && st.view.getParent() != null) {
                ((ViewGroup) st.view.getParent()).removeView(st.view);
            }
            TextView tv = new TextView(act);
            tv.setTextSize(13);
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC212F3D);
            bg.setCornerRadius(dp(act, 8));
            tv.setBackground(bg);
            tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            tv.setMaxWidth(dp(act, 280));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.RIGHT);
            lp.topMargin = dp(act, st.top);
            lp.rightMargin = dp(act, 12);
            tv.setLayoutParams(lp);
            tv.setOnTouchListener(new MoveListenerState(st));
            ((ViewGroup) act.getWindow().getDecorView()).addView(tv);
            st.view = tv;
        }
        return st;
    }

    private static void renderState(FloatState st, String text, String qid) {
        if (st == null || st.view == null) return;
        if (!qid.equals(sFloatQId)) return;
        st.text = text;
        final FloatState fs = st;
        final String fqid = qid;
        st.view.post(new Runnable() {
            @Override
            public void run() {
                if (fs.view == null) return;
                if (!fqid.equals(sFloatQId)) return;
                Object tag = fs.view.getTag();
                if (tag == null || !text.equals(tag)) fs.expanded = true;
                fs.view.setText(fs.expanded ? fs.text : fs.label);
                fs.view.setTag(fs.text);
            }
        });
    }

    static class MoveListenerState implements View.OnTouchListener {
        private final FloatState st;
        private int lastX;
        private int lastY;
        private int downX;
        private int downY;
        private boolean moved;

        MoveListenerState(FloatState st) {
            this.st = st;
        }

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (v.getTag() == null) return false;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = (int) ev.getRawX();
                    downY = (int) ev.getRawY();
                    lastX = downX;
                    lastY = downY;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) ev.getRawX() - lastX;
                    int dy = (int) ev.getRawY() - lastY;
                    if (dx == 0 && dy == 0) return true;
                    moved = true;
                    lp.topMargin += dy;
                    lp.rightMargin -= dx;
                    if (lp.topMargin < 0) lp.topMargin = 0;
                    if (lp.rightMargin < 0) lp.rightMargin = 0;
                    v.setLayoutParams(lp);
                    lastX = (int) ev.getRawX();
                    lastY = (int) ev.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && Math.abs((int) ev.getRawX() - downX) < 12 && Math.abs((int) ev.getRawY() - downY) < 12) {
                        st.expanded = !st.expanded;
                        ((TextView) v).setText(st.expanded ? st.text : st.label);
                    }
                    return true;
            }
            return false;
        }
    }

    private static String sFloatQId = "";
    private static String sFloatChallengeId = "";
    private static String sFloatMonthSubject = "";
    private static final java.util.HashSet<String> sFetchedQIds = new java.util.HashSet<String>();
    private static final java.util.concurrent.ConcurrentHashMap<String, M_ChallengeQuestion> sDetailMap = new java.util.concurrent.ConcurrentHashMap<String, M_ChallengeQuestion>();
    /** 判题接口返回的正确选项ID缓存：questionId -> rightAnswerIds */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>> sApiRightIds = new java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>>();
    /** 详情接口返回的听写(sContent)答案：questionId -> 文本 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> sDetailListenText = new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** 当前题目上下文（供详情异步回来后回填听力答案） */
    private static net.xuele.xuelets.challenge.util.ChallengeParamHelper sFloatPH = null;
    private static int sFloatPos = -1;
    private static boolean sDetailFetching = false;

    /** 详情接口：按挑战ID请求全量题目（官方解析器，含 isCorrect/sContent），识别为答案数据 */
    private static void fetchByDetail(Activity act) {
        try {
            if (sFloatChallengeId.isEmpty()) return;
            if (sDetailFetching) return;
            sDetailFetching = true;
            final Activity fAct = act;
            net.xuele.xuelets.challenge.util.ChallengeDetailHelper.loadQuestionList(
                    sFloatChallengeId, sFloatMonthSubject,
                    new net.xuele.xuelets.challenge.util.ChallengeDetailHelper.LoadDataInterface() {
                        @Override
                        public void loadQuestionFail(String s) {
                            sDetailFetching = false;
                            if (XLModConfig.isDebugFloat()) {
                                String t = "详情| 失败" + (s == null || s.isEmpty() ? "" : ": " + s);
                                renderState(sDetailS, t, sFloatQId);
                            }
                        }

                        @Override
                        public void loadQuestionListSuccess(java.util.ArrayList<M_ChallengeQuestion> list, java.util.HashMap<Integer, net.xuele.android.ui.question.ChallengeUserAnswer> map) {
                            sDetailFetching = false;
                            try {
                                if (list == null) return;
                                for (int i = 0; i < list.size(); i++) {
                                    M_ChallengeQuestion q = list.get(i);
                                    if (q == null || q.questionId == null) continue;
                                    sDetailMap.put(q.questionId, q);
                                    // 听写题：官方详情把标准答案放在 sContent（initAnswer → answerContentList）
                                    if (parseQType(q) == 51 && map != null) {
                                        net.xuele.android.ui.question.ChallengeUserAnswer u = map.get(i);
                                        if (u != null && u.answerContentList != null && !u.answerContentList.isEmpty()) {
                                            String txt = u.answerContentList.get(0);
                                            if (txt != null && !txt.isEmpty()) {
                                                sDetailListenText.put(q.questionId, txt);
                                                XLModConfig.kbPut(q.questionId, "L|" + txt);
                                            }
                                        }
                                    }
                                }
                                updateFloatFromDetail(fAct);
                                // 详情已到：若当前正是听力题，补一次回填（覆盖答案晚于题目绑定到达的情况）
                                applyListenAnswerCurrent(fAct);
                            } catch (Throwable t) {
                            }
                        }
                    });
        } catch (Throwable t) {
            sDetailFetching = false;
        }
    }

    /** 用详情数据刷新当前题目答案（若命中缓存） */
    private static void updateFloatFromDetail(Activity act) {
        try {
            if (act == null) return;
            String qid = sFloatQId;
            if (qid.isEmpty()) return;
            if (!sDetailMap.containsKey(qid)) return;
            M_ChallengeQuestion q = sDetailMap.get(qid);
            String text = buildAnswerText(q);
            if (text == null || text.length() == 0) return;
            final String t = "详情: " + text;
            final String id = qid;
            // 调试三窗模式：写入"详情"窗口
            if (XLModConfig.isDebugFloat()) {
                renderState(sDetailS, "详情| " + text, id);
                return;
            }
            final TextView tv = sFloatView;
            if (tv == null) return;
            tv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (id.equals(sFloatQId)) {
                            tv.setText(sFloatExpanded ? t : "答");
                            tv.setTag(t);
                        }
                    } catch (Throwable x) {
                    }
                }
            });
        } catch (Throwable t) {
        }
    }

    private static void fetchAnswerByCorrect(Activity act, M_ChallengeQuestion q) {
        try {
            String qid = q.questionId == null ? "" : q.questionId;
            if (qid.isEmpty()) return;
            if (sFetchedQIds.contains(qid)) return;
            sFetchedQIds.add(qid);
            String wqid = (q.wrappedQID != null && !q.wrappedQID.isEmpty()) ? q.wrappedQID : qid;
            int qt = 0;
            try {
                qt = net.xuele.android.common.tools.ConvertUtil.toIntForServer(q.qType);
            } catch (Throwable t) {
                qt = parseQType(q);
            }
            String qtype = (q.qType == null) ? "" : q.qType;
            final String info = "Q[" + qtype + "/" + qt + "/" + (wqid.length() > 16 ? wqid.substring(0, 16) + "…" : wqid) + "]";
            final String fqid = qid;
            final int fqt = qt;
            probeCorrect(act, q, wqid, fqt, true, info, fqid, qid);
        } catch (Throwable t) {
        }
    }

    /** 判题探测：先带虚拟答案，失败再换空答案，两次结果都展示 */
    private static void probeCorrect(final Activity act, final M_ChallengeQuestion q, final String wqid,
                                     final int qt, final boolean withDummy, final String info,
                                     final String fqid, final String cacheQid) {
        try {
            net.xuele.android.common.model.CorrectingQuestionModel model = new net.xuele.android.common.model.CorrectingQuestionModel();
            model.questionId = wqid;
            model.questionType = qt;
            model.userAnswers = new java.util.ArrayList<net.xuele.android.common.model.CorrectingQuestionModel.UserAnswerModel>();
            if (withDummy) {
                java.util.List<AnswersBean> ans = q.answers;
                if (qt == 3) {
                    if (ans != null) {
                        for (AnswersBean a : ans) {
                            net.xuele.android.common.model.CorrectingQuestionModel.UserAnswerModel u = new net.xuele.android.common.model.CorrectingQuestionModel.UserAnswerModel();
                            u.answerId = a.answerId;
                            u.userAnswer = (a.answerContent == null) ? "" : a.answerContent;
                            model.userAnswers.add(u);
                        }
                    }
                } else if (qt == 11 || qt == 12) {
                    if (ans != null && !ans.isEmpty()) {
                        net.xuele.android.common.model.CorrectingQuestionModel.UserAnswerModel u = new net.xuele.android.common.model.CorrectingQuestionModel.UserAnswerModel();
                        u.answerId = ans.get(0).answerId;
                        model.userAnswers.add(u);
                    }
                }
                // 判断(2)/听写(51)/口语(52)：与官方结构一致——不塞用户答案
            }
            final java.util.List<net.xuele.android.common.model.CorrectingQuestionModel> list = new java.util.ArrayList<net.xuele.android.common.model.CorrectingQuestionModel>();
            list.add(model);
            final boolean isDummy = withDummy;
            final M_ChallengeQuestion fq = q;
            net.xuele.android.common.CommonApi.ready.exerciseCorrect(list)
                    .requestV2((androidx.lifecycle.l) act,
                            new net.xuele.android.core.http.callback.ReqCallBackV2<net.xuele.android.common.model.RE_CorrectingQuestionModel>() {
                                @Override
                                public void onReqFailed(String s1, String s2) {
                                    String tag = isDummy ? "带答案" : "空答案";
                                    String msg = (s1 == null || s1.isEmpty()) ? "" : s1;
                                    if (msg.length() > 60) msg = msg.substring(0, 60);
                                    String t = "[接口|" + info + "] " + tag + "失败: " + msg;
                                    updateFloat(t, fqid);
                                    if (isDummy) {
                                        // 换空答案再试一次，结果合并不覆盖第一次（第二次失败则显示两次）
                                        probeCorrect(act, fq, wqid, qt, false, info, fqid, cacheQid);
                                    }
                                }

                                @Override
                                public void onReqSuccess(net.xuele.android.common.model.RE_CorrectingQuestionModel re) {
                                    try {
                                        String tag = isDummy ? "带答案" : "空答案";
                                        if (re == null || re.correctingQuestions == null || re.correctingQuestions.isEmpty()) {
                                            updateFloat("[接口|" + info + "] " + tag + "返回空", fqid);
                                            return;
                                        }
                                        net.xuele.android.common.model.RE_CorrectingQuestionModel.CorrectingResultDetail d = re.correctingQuestions.get(0);
                                        StringBuilder sb = new StringBuilder("[接口|" + info + "] " + tag + " code=" + d.resultCode);
                                        if (d.resultMessage != null && !d.resultMessage.isEmpty()) {
                                            sb.append(" ").append(d.resultMessage.length() > 40 ? d.resultMessage.substring(0, 40) : d.resultMessage);
                                        }
                                        sb.append("\n正确ID=");
                                        if (d.rightAnswerIds == null || d.rightAnswerIds.isEmpty()) {
                                            sb.append("无");
                                        } else {
                                            sb.append(joinStr(d.rightAnswerIds));
                                            // 缓存供自动作答
                                            if (!cacheQid.isEmpty()) {
                                                sApiRightIds.put(cacheQid, new java.util.ArrayList<String>(d.rightAnswerIds));
                                            }
                                            // 解析为选项字母
                                            StringBuilder opt = new StringBuilder("  → 选:");
                                            int shown = 0;
                                            char letter = 'A';
                                            java.util.List<AnswersBean> ans = fq.answers;
                                            if (ans != null) {
                                                for (AnswersBean a : ans) {
                                                    if (d.rightAnswerIds.contains(a.answerId)) {
                                                        if (shown > 0) opt.append("、");
                                                        String tg = (a.sortid != null && !a.sortid.isEmpty()) ? a.sortid : String.valueOf(letter);
                                                        opt.append(tg);
                                                        shown++;
                                                    }
                                                    if (letter < 'Z') letter++;
                                                }
                                            }
                                            if (shown == 0) opt.append("(无匹配选项)");
                                            sb.append(opt);
                                        }
                                        updateFloat(sb.toString(), fqid);
                                    } catch (Throwable t) {
                                    }
                                }
                            });
        } catch (Throwable t) {
        }
    }

    private static String joinStr(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    private static void updateFloat(String text, String qid) {
        try {
            if (!qid.equals(sFloatQId)) return; // 已切到其他题目
            // 调试三窗模式：写入"接口"窗口（text 自带 [接口] 前缀）
            if (XLModConfig.isDebugFloat()) {
                renderState(sApiS, text, qid);
                return;
            }
            if (sFloatView == null) return;
            final String t = text;
            final String id = qid;
            final TextView tv = sFloatView;
            tv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (tv.getTag() == null) return;
                        if (!id.equals(sFloatQId)) return;
                        tv.setText(t);
                        tv.setTag(t);
                    } catch (Throwable x) {
                    }
                }
            });
        } catch (Throwable t) {
        }
    }

    private static String buildAnswerText(M_ChallengeQuestion q) {
        StringBuilder sb = new StringBuilder();
        // 0) 知识库优先（赛后采集的正确答案/填空文本）
        if (q != null && q.questionId != null && !q.questionId.isEmpty()) {
            String kb = XLModConfig.kbGet(q.questionId);
            if (!kb.isEmpty()) {
                if (kb.startsWith("S|")) {
                    java.util.List<AnswersBean> ans = q.answers;
                    if (ans != null) {
                        java.util.HashSet<String> set = new java.util.HashSet<String>();
                        for (String id : kb.substring(2).split(",")) {
                            set.add(id.trim());
                        }
                        char letter = 'A';
                        for (AnswersBean a : ans) {
                            if (set.contains(a.answerId)) {
                                if (sb.length() > 0) sb.append("\n");
                                String c = a.answerContent == null ? "" : a.answerContent;
                                if (c.length() > 50) c = c.substring(0, 50) + "...";
                                sb.append("知识库答案").append(letter).append(": ").append(c);
                            }
                            if (letter < 'Z') letter++;
                        }
                    }
                } else if (kb.startsWith("L|")) {
                    sb.append("知识库听力: ").append(kb.substring(2));
                } else if (kb.startsWith("F|")) {
                    sb.append("知识库填空: ").append(kb.substring(2).replace("\u0001", " | "));
                }
                if (sb.length() > 0) return sb.toString();
            }
        }
        boolean anyCorrect = false;
        if (q.answers != null) {
            char letter = 'A';
            for (AnswersBean a : q.answers) {
                boolean correct = a.isCorrect != null && "1".equals(a.isCorrect.trim());
                if (correct) {
                    anyCorrect = true;
                    if (sb.length() > 0) sb.append("\n");
                    String content = a.answerContent == null ? "" : a.answerContent;
                    if (content.length() > 50) content = content.substring(0, 50) + "...";
                    sb.append("答案").append(letter).append(": ").append(content);
                }
                if (letter < 'Z') letter++;
            }
            if (!anyCorrect) {
                // 无 isCorrect 标记：可能是"仅选项未判读"（普通挑战）或填空/听力
                boolean hasContent = false;
                StringBuilder opts = new StringBuilder();
                int shown = 0;
                char oletter = 'A';
                for (AnswersBean a : q.answers) {
                    String c = a.answerContent == null ? "" : a.answerContent;
                    if (!c.isEmpty()) {
                        hasContent = true;
                        if (shown < 4) {
                            if (opts.length() > 0) opts.append("  ");
                            String cc = c.length() > 24 ? c.substring(0, 24) + "…" : c;
                            opts.append(oletter).append(".").append(cc);
                        }
                        shown++;
                    }
                    if (oletter < 'Z') oletter++;
                }
                if (hasContent) {
                    sb.append("本地: 仅选项(无答案标记): ").append(opts);
                    if (shown > 4) sb.append(" 等").append(shown).append("项");
                } else {
                    sb.append("本地无答案数据(填空/听力答案为空)");
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("本地无答案数据");
        }
        if (q.solution != null && !q.solution.isEmpty()) {
            String sol = q.solution.length() > 80 ? q.solution.substring(0, 80) + "..." : q.solution;
            sb.append("\n解析: ").append(sol);
        }
        return sb.length() == 0 ? "答案不可用" : sb.toString();
    }

    private static int dp(Activity act, int v) {
        return Math.round(act.getResources().getDisplayMetrics().density * v);
    }

    static class MoveListener implements View.OnTouchListener {
        private int lastX;
        private int lastY;
        private int downX;
        private int downY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (v.getTag() == null) return false;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = (int) ev.getRawX();
                    downY = (int) ev.getRawY();
                    lastX = downX;
                    lastY = downY;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) ev.getRawX() - lastX;
                    int dy = (int) ev.getRawY() - lastY;
                    if (dx == 0 && dy == 0) return true;
                    moved = true;
                    lp.topMargin += dy;
                    lp.rightMargin -= dx;
                    if (lp.topMargin < 0) lp.topMargin = 0;
                    if (lp.rightMargin < 0) lp.rightMargin = 0;
                    v.setLayoutParams(lp);
                    lastX = (int) ev.getRawX();
                    lastY = (int) ev.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    // 按下-抬起位移很小才算点击：折叠/展开
                    if (!moved && Math.abs((int) ev.getRawX() - downX) < 12 && Math.abs((int) ev.getRawY() - downY) < 12) {
                        sFloatExpanded = !sFloatExpanded;
                        Object text = v.getTag();
                        ((TextView) v).setText(sFloatExpanded && text != null ? text.toString() : "答");
                    }
                    return true;
            }
            return false;
        }
    }

    // ================= 设置页入口 =================
    public static void addSettingEntry(Activity act) {
        // 兜底版本：从 content 容器的第一个子视图找真正的列表容器
        try {
            View content = act.findViewById(android.R.id.content);
            ViewGroup vg = null;
            if (content instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) content;
                if (g.getChildCount() > 0 && g.getChildAt(0) instanceof ViewGroup) {
                    vg = (ViewGroup) g.getChildAt(0);
                } else {
                    vg = g;
                }
            }
            addSettingRow(act, vg);
        } catch (Throwable t) {
        }
    }

    public static void addSettingEntry(Activity act, View anchor) {
        XLModConfig.init(act);
        try {
            ViewGroup vg = null;
            if (anchor != null && anchor.getParent() instanceof ViewGroup) {
                vg = (ViewGroup) anchor.getParent();
            }
            addSettingRow(act, vg);
        } catch (Throwable t) {
        }
    }

    private static void addSettingRow(Activity act, ViewGroup vg) {
        try {
            if (vg == null) return;
            XLModConfig.init(act);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(0xFFFFFFFF);
            TextView tv = new TextView(act);
            tv.setText("Mod 功能");
            tv.setTextSize(16);
            tv.setTextColor(0xFF666666);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(act, 20), 0, dp(act, 20), 0);
            row.addView(tv, new LinearLayout.LayoutParams(0, dp(act, 45), 1f));
            TextView arrow = new TextView(act);
            arrow.setText(">");
            arrow.setTextSize(16);
            arrow.setTextColor(0xFFCCCCCC);
            arrow.setGravity(Gravity.CENTER_VERTICAL);
            arrow.setPadding(0, 0, dp(act, 16), 0);
            row.addView(arrow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(act, 45)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        act.startActivity(new Intent(act, XLModActivity.class));
                    } catch (Throwable t) {
                    }
                }
            });
            // 插入到 ActionBar 与阴影之后（index 2），越界则追加
            int idx = 2;
            if (idx > vg.getChildCount()) idx = vg.getChildCount();
            vg.addView(row, idx);
        } catch (Throwable t) {
        }
    }
}
