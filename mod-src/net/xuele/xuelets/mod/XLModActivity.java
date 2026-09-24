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
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;

/**
 * Mod 功能面板（设置页进入）：Miuix / MIUI 视觉风格，纯代码构建 UI，无资源、无 Compose 依赖。
 * 功能与配置键与旧版完全一致，仅重构视觉与交互布局；API 19+ 可用。
 */
public class XLModActivity extends Activity {

    /** 面板版本号（页面标题后展示） */
    private static final String MOD_VER = "V4.0";

    // Miuix / MIUI 风格色板
    private static final int C_BG     = 0xFFF4F5F7;
    private static final int C_CARD   = 0xFFFFFFFF;
    private static final int C_TEXT   = 0xFF262626;
    private static final int C_SUB    = 0xFF666666;
    private static final int C_TIP    = 0xFF9A9A9A;
    private static final int C_DIV    = 0xFFF0F0F0;
    private static final int C_ACCENT = 0xFF2E7CF6;
    private static final int C_BORDER = 0xFFE4E7EC;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        XLModConfig.init(this);
        XLModConfig.syncHwTargetsWithFile();
        XLModFeatures.refreshAsync(this, false);   // 远程功能区开关（失败不影响使用，默认全开）
        XLModHelper.installLifecycle(this);
        XLModHelper.startTimer(this);
        XLModFeatures.setListener(new XLModFeatures.Listener() {
            @Override
            public void onConfigChanged() {
                // 配置有变化 → 立即重建界面（实时生效）
                try {
                    rebuildUi();
                    showNoticeIfAny();
                } catch (Throwable ignored) {
                }
            }
        });
        try {
            buildUi();
        } catch (Throwable t) {
            setContentView(buildSimpleView("初始化失败: " + t.getMessage()));
        }
        showNoticeIfAny();
    }

    /** 发作业修复：检查教师身份 → 切"校级管理" → 显示悬浮窗 → 打开通知"选择发送对象"页（不附加任何参数） */
    private void hwFixCapture() {
        if (!XLModConfig.isHwFixEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("发作业修复未开启")
                    .setMessage("请先打开上方「发作业修复」开关，再点本按钮。")
                    .setPositiveButton("现在开启", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            XLModConfig.setHwFixEnabled(true);
                            Toast.makeText(XLModActivity.this, "已开启，请重新进入本页使开关显示同步", Toast.LENGTH_SHORT).show();
                            hwFixCapture();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!XLModConfig.isTeacherMod()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要教师身份")
                    .setMessage("抓取班级/学生数据需要教师身份（服务端按教师身份放行相关接口）。是否现在开启「教师身份」并把身份等级设为「校级管理」？")
                    .setPositiveButton("开启并继续", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            XLModConfig.setTeacherMod(true);
                            XLModConfig.setIdentityLevel(3);
                            Toast.makeText(XLModActivity.this, "已开启教师身份（校级管理）", Toast.LENGTH_SHORT).show();
                            XLModHelper.showTargetFloat(XLModActivity.this);
                            XLModHelper.launchTargetPicker(XLModActivity.this);
                            XLModHelper.startAutoCapture(XLModActivity.this);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        XLModConfig.setIdentityLevel(3);
        Toast.makeText(this, "身份等级已切到「校级管理」，正在打开选择发送对象页（已开启自动抓取）", Toast.LENGTH_SHORT).show();
        XLModHelper.showTargetFloat(this);
        XLModHelper.launchTargetPicker(this);
        XLModHelper.startAutoCapture(this);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(30));

        // ===== 顶部标题卡片 =====
        LinearLayout headCard = card();
        headCard.setPadding(dp(18), dp(16), dp(18), dp(14));
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("学乐云 Mod 功能");
        title.setTextSize(21);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView version = new TextView(this);
        version.setText(MOD_VER);
        version.setTextSize(11.5f);
        version.setTextColor(C_ACCENT);
        version.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xFFEAF1FE);
        chipBg.setCornerRadius(dp(9));
        version.setBackground(chipBg);
        version.setPadding(dp(10), dp(4), dp(10), dp(4));
        headRow.addView(version);
        headCard.addView(headRow);
        TextView subtitle = new TextView(this);
        subtitle.setText("所有修改即时保存 · 金榜题名相关功能在答题页生效");
        subtitle.setTextSize(12);
        subtitle.setTextColor(C_TIP);
        subtitle.setPadding(0, dp(7), 0, 0);
        headCard.addView(subtitle);
        addCard(content, headCard);

        int rowId = 0;

        // ===== 功能开关（远程配置 / 授权） =====
        addGroupHeader(content, "授权状态（远程配置）");
        LinearLayout cardFeat = card();
        final TextView tvFeat = new TextView(this);
        tvFeat.setTextSize(12.5f);
        tvFeat.setTextColor(XLModFeatures.loaded() ? 0xFF333333 : 0xFFD32F2F);
        tvFeat.setPadding(dp(12), dp(10), dp(12), dp(4));
        StringBuilder sbFeat = new StringBuilder(XLModFeatures.statusText());
        if (!XLModFeatures.notice().isEmpty()) {
            sbFeat.append("\n\n【公告】").append(XLModFeatures.notice());
        }
        tvFeat.setText(sbFeat.toString());
        cardFeat.addView(tvFeat);
        actionButton(cardFeat, "立即校验授权（拉取签名配置）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                XLModFeatures.refreshAsync(XLModActivity.this, true);
                Toast.makeText(XLModActivity.this, "正在校验授权…（签名不过或过期都会保持锁定）", Toast.LENGTH_SHORT).show();
            }
        });
        addCard(content, cardFeat);
        tip(content, "授权配置由作者私钥签名，端上只验签（地址写死，无法自建配置解锁）；未通过校验或已过期时，除「隐私隐藏 / 日志」外全部锁定；App 在前台每 1 分钟自动校验，配置有变化立即生效。");

        // ===== 教师身份 =====
        boolean gIdentity = addGroupHeaderGated(content, "教师身份", "identity");
        LinearLayout cardTeacher = card();
        switchRow(cardTeacher,
                "教师身份（开启后 isTeacher/校级/班主任/教研/教育局身份检测全部返回真，教学权限白名单生效）",
                XLModConfig.isTeacherMod(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setTeacherMod(((MiuixSwitch) v).isChecked()); } });
        spinnerRow(cardTeacher, "身份等级",
                new String[]{"全部（最高级，旧版行为）", "教师", "班主任", "校级管理", "教研成员", "教育局管理"},
                Math.min(5, Math.max(0, XLModConfig.getIdentityLevel())),
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setIdentityLevel(pos); } });
        EditText etPositionName = inputRow(cardTeacher, "职务名称", XLModConfig.getFixPositionName(), ++rowId);
        final EditText fEtPositionName = etPositionName;
        etPositionName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setFixPositionName(fEtPositionName.getText().toString());
            }
        });
        EditText etPositionId = inputRow(cardTeacher, "职务ID", XLModConfig.getFixPositionId(), ++rowId);
        final EditText fEtPositionId = etPositionId;
        etPositionId.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setFixPositionId(fEtPositionId.getText().toString());
            }
        });
        EditText etDutyName = inputRow(cardTeacher, "身份名称", XLModConfig.getFixDutyName(), ++rowId);
        final EditText fEtDutyName = etDutyName;
        etDutyName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setFixDutyName(fEtDutyName.getText().toString());
            }
        });
        addCardGated(content, cardTeacher, gIdentity);
        tipGated(content, gIdentity, "选择要伪装的身份等级（不选就是原版最高级）。职务/职位名称只是本机显示用，随便填即可。\n开启后登录、切换角色不会再被要求去「学乐云管理」App。");

        // ===== 教师工具 =====
        boolean gTools = addGroupHeaderGated(content, "教师工具", "teacher_tools");
        LinearLayout cardTeacherTool = card();
        EditText etUser = inputRow(cardTeacherTool, "学生userId", XLModConfig.getLaunchUserId(), ++rowId);
        final EditText fEtUser = etUser;
        etUser.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setLaunchUserId(fEtUser.getText().toString());
            }
        });
        actionButton(cardTeacherTool, "打开学生创建/编辑（自定义userId）", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            XLModHelper.launchStudentCreate(XLModActivity.this, XLModConfig.getLaunchUserId());
                            android.widget.Toast.makeText(XLModActivity.this, "已打开（编辑模式，userId 见页内\"账号\"栏）", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                        }
                    }
                });
        addCardGated(content, cardTeacherTool, gTools);
        tipGated(content, gTools, "一般不用改。用「编辑模式」打开学生页时，顶部会显示你填的 userId；创建模式由服务器分配账号。");

        // ===== 视频压缩 =====
        boolean gCloud = addGroupHeaderGated(content, "视频上传压缩", "cloud_keep");
        LinearLayout cardCompress = card();
        spinnerRow(cardCompress, "压缩模式",
                new String[]{"原版行为", "不压缩（原始文件上传）", "固定档位", "自定义码率"},
                XLModConfig.getCompressMode(),
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setCompressMode(pos); } });
        spinnerRow(cardCompress, "档位",
                new String[]{"450P(800K)", "720P(2M)", "1080P(2M)", "2K(3M)"},
                XLModConfig.getCompressLevel() - 1,
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setCompressLevel(pos + 1); } });
        EditText etKbps = inputRow(cardCompress, "码率(kbps)", String.valueOf(XLModConfig.getCompressKbps()), ++rowId);
        final EditText fEtKbps = etKbps;
        etKbps.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    XLModConfig.setCompressKbps(Integer.parseInt(fEtKbps.getText().toString().trim()));
                } catch (Throwable t) {
                    XLModConfig.setCompressKbps(0);
                }
            }
        });
        addCardGated(content, cardCompress, gCloud);
        tipGated(content, gCloud, "大于 10MB 的视频才会压缩。懒得调就点上面的「一键套用成功配方」，其余保持默认。");

        LinearLayout cardCloudKeep = card();
        switchRow(cardCloudKeep,
                "云端保持原片（头部伪装：假装已压缩）",
                XLModConfig.isCloudKeepOriginal(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setCloudKeepOriginal(((MiuixSwitch) v).isChecked()); } });
        actionButton(cardCloudKeep, "一键套用成功配方（不压缩 + 容器伪装 + bin 扩展名）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                XLModConfig.setCompressMode(1);          // 走"云端保持原片"分支
                XLModConfig.setCloudKeepOriginal(true);  // 开启云原片（跳过本地压缩 + 头部伪装）
                XLModConfig.setHwUploadExtMode(0);       // 0 = bin 伪装扩展名
                Toast.makeText(XLModActivity.this, "已套用：不压缩 + 云端保持原片 + bin 扩展名（重进本页可看到开关状态）", Toast.LENGTH_LONG).show();
                XLModConfig.logAppend("[云原片] 已一键套用成功配方: compressMode=1, cloudKeepOriginal=on, uploadExt=bin");
            }
        });
        addCardGated(content, cardCloudKeep, gCloud);
        // 上传扩展名：bin 伪装 / 原始媒体扩展名（云原片开启时强制 bin，不受本项影响）
        LinearLayout cardUploadExt = card();
        spinnerRow(cardUploadExt, "上传扩展名（云原片开启时强制 bin）",
                new String[]{"bin（伪装扩展名，云原片用这个）", "媒体文件（原扩展名；仅云原片关闭时生效）"},
                XLModConfig.getHwUploadExtMode() == 1 ? 1 : 0,
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setHwUploadExtMode(pos); } });
        addCardGated(content, cardUploadExt, gCloud);
        tipGated(content, gCloud, "生效三要素：开关打开 + 扩展名 bin + 不重编码 —— 点「一键套用成功配方」会自动设好。");
        // 醒目提示：不同设备/系统/厂商对系统库阉割可能导致伪装视频无法在软件内播放
        TextView warn = new TextView(this);
        warn.setText("注意：由于某些设备某些系统某些厂家对系统库进行了阉割，所以导致伪装过的视频无法在软件内播放，现已完成发作业功能，建议搭配使用。");
        warn.setTextSize(13);
        warn.setTextColor(0xFFE64340);
        warn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(0xFFFFF0F0);
        warnBg.setCornerRadius(dp(8));
        warn.setBackground(warnBg);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(-1, -2);
        warnLp.setMargins(dp(4), dp(8), dp(4), dp(4));
        content.addView(warn, warnLp);
        tipGated(content, gCloud, "一句话原理：本地不重编码、只把容器宽高写成 720P、扩展名改成 bin，服务端就当成「已压缩」不再转码。\n验证：上传后看下载链接 —— `<md5>.mp4` = 成功；`mp4_<md5>.mp4` = 被转码了。");

        // ===== 通知管理 =====
        boolean gNotify = addGroupHeaderGated(content, "通知管理", "notify_recall");
        LinearLayout cardNotify = card();
        switchRow(cardNotify,
                "已发通知删除常态化（撤回并删除+删除同时出现）",
                XLModConfig.isRecallAlways(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setRecallAlways(((MiuixSwitch) v).isChecked()); } });
        addCardGated(content, cardNotify, gNotify);
        tipGated(content, gNotify, "打开后：通知详情右上角菜单里「撤回并删除」和「删除」始终都在（原版超过 2 小时撤回入口会消失）。\n超过 2 小时撤回可能被服务器拒绝，失败就用「删除」。");

        // ===== 布置作业（修复） =====
        boolean gHw = addGroupHeaderGated(content, "布置作业（修复）", "homework");
        LinearLayout cardHwFix = card();

        sHwStatusView = new TextView(this);
        sHwStatusView.setTextSize(12f);
        sHwStatusView.setTextColor(0xFF4A4F57);
        sHwStatusView.setLineSpacing(dp(2), 1.0f);
        sHwStatusView.setPadding(dp(18), dp(12), dp(18), dp(8));
        sHwStatusView.setText(hwStatusText());
        cardHwFix.addView(sHwStatusView);

        switchRow(cardHwFix,
                "启用布置作业修复（本地数据注入原版页面）",
                XLModConfig.isHwFixEnabled(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setHwFixEnabled(((MiuixSwitch) v).isChecked()); refreshHwStatus(); } });

        // ---------- ① 抓取 ----------
        sectionLabel(cardHwFix, "① 抓取（点击后在页面里点年级、再点班级，抓完自动补全学生）");
        actionButton(cardHwFix, "一键抓取：打开「选择发送对象」页", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { hwFixCapture(); }
        });
        actionButton(cardHwFix, "观看视频教程（用浏览器打开）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://wwbje.lanzn.com/b00wnuwf6f"));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    XLModConfig.logAppend("[教程] 打开视频教程失败: " + t);
                    Toast.makeText(XLModActivity.this, "打开失败（没有可用浏览器？）：" + t, Toast.LENGTH_SHORT).show();
                }
            }
        });
        tipGated(cardHwFix, gHw, "悬浮窗（浮在屏幕右侧，可拖动）：\n· 抓取 = 立刻抓一次，换班级后点它即可，数据自动合并\n· 注入 = 班级没显示出来时按一下\n· 导出 = 备份数据；收起 = 收成小条\n★ 抓过一次就不用再点「一键抓取」了，日常用悬浮窗的「抓取」即可。");
        actionButton(cardHwFix, "显示抓取悬浮窗（日常用它抓）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { XLModHelper.toggleTargetFloat(XLModActivity.this); refreshHwStatus(); }
        });
        final int[] hwTypes = {3, 4, 0, 2, 1};
        int hwTypeIdx = 0;
        for (int i = 0; i < hwTypes.length; i++) {
            if (hwTypes[i] == XLModConfig.getHwTargetType()) { hwTypeIdx = i; break; }
        }
        spinnerRow(cardHwFix, "抓取目标类型",
                new String[]{"学生(推荐)", "班级", "教师", "学校", "教育管理"},
                hwTypeIdx,
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setHwTargetType(hwTypes[pos]); } });
        actionButton(cardHwFix, "刷新并合并本地数据（不覆盖已有课本/课时）", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                XLModConfig.syncHwTargetsWithFile();
                XLModHelper.captureAll(false);
                XLModHelper.captureStudentsForClasses(XLModActivity.this, 0);
                refreshHwStatus();
            }
        });

        // ---------- ② 教材与课时 ----------
        sectionLabel(cardHwFix, "② 教材与课时（课本自己选，课时单独读）");
        tipGated(cardHwFix, gHw, "抓课本：①「打开教材页」→ ②按顺序点 学科 → 年级 → 教材版本 → ③回面板「读取课本」→ ④在「课本」里选一本 → ⑤「读取课时」→ 在「课时」里选要用的课时。\n课本和课时各读一次即可，之后一直有效。");
        actionButton(cardHwFix, "打开教材页（选学科 / 年级 / 教材版本）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!XLModConfig.isHwFixEnabled()) {
                    Toast.makeText(XLModActivity.this, "请先打开上面的「启用布置作业修复」开关", Toast.LENGTH_SHORT).show();
                    return;
                }
                XLModConfig.setIdentityLevel(3);
                XLModHelper.showTargetFloat(XLModActivity.this);
                XLModHelper.launchBookPicker(XLModActivity.this);
            }
        });
        actionButton(cardHwFix, "读取课本（把教材页里的课本存入本地）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { XLModHelper.captureBooksFromPage(XLModActivity.this); refreshHwStatus(); }
        });

        // 课本下拉（用户自选要用的课本）+ 读取课时 + 课时下拉
        final java.util.List<String> bkIds = new java.util.ArrayList<>();
        final java.util.List<String> bkNames = new java.util.ArrayList<>();
        final java.util.List<String> lsIds = new java.util.ArrayList<>();
        final java.util.List<String> lsNames = new java.util.ArrayList<>();
        final java.util.List<String> lsSubjects = new java.util.ArrayList<>();
        final java.util.List<String> lsSubjectNames = new java.util.ArrayList<>();
        final java.util.List<String> lsGrades = new java.util.ArrayList<>();
        try {
            String json = XLModConfig.getHwTargetsJson();
            org.json.JSONObject cap = new org.json.JSONObject(json == null || json.isEmpty() ? "{}" : json);
            org.json.JSONArray bs = cap.optJSONArray("books");
            if (bs != null) {
                for (int i = 0; i < bs.length(); i++) {
                    org.json.JSONObject b = bs.optJSONObject(i);
                    if (b == null || b.optString("bookId").isEmpty()) continue;
                    bkIds.add(b.optString("bookId"));
                    int n = 0;
                    org.json.JSONArray us = b.optJSONArray("units");
                    if (us != null) {
                        for (int u = 0; u < us.length(); u++) {
                            org.json.JSONObject un = us.optJSONObject(u);
                            org.json.JSONArray lls = un == null ? null : un.optJSONArray("lessons");
                            if (lls != null) {
                                n += lls.length();
                                for (int l = 0; l < lls.length(); l++) {
                                    org.json.JSONObject ln = lls.optJSONObject(l);
                                    if (ln == null || ln.optString("lessonId").isEmpty()) continue;
                                    lsIds.add(ln.optString("lessonId"));
                                    lsNames.add(ln.optString("lessonName") + "（" + b.optString("bookName") + "/" + un.optString("unitName") + "）");
                                    lsSubjects.add(b.optString("subjectId"));
                                    lsSubjectNames.add(b.optString("subjectName"));
                                    lsGrades.add(b.optString("gradeNum"));
                                }
                            }
                        }
                    }
                    bkNames.add(b.optString("bookName", b.optString("bookId")) + "（" + n + " 课时）");
                }
            }
        } catch (Throwable ignored) {
        }
        int bkIdx = 0;
        {
            String saved = XLModConfig.getHwUseBookId();
            for (int i = 0; i < bkIds.size(); i++) {
                if (bkIds.get(i).equals(saved)) { bkIdx = i; break; }
            }
        }
        spinnerRow(cardHwFix, "课本",
                bkNames.isEmpty() ? new String[]{"（未抓到课本）"} : bkNames.toArray(new String[0]), bkIdx,
                new SpinnerWatcher() {
                    public void onPos(int pos) {
                        if (pos >= 0 && pos < bkIds.size()) {
                            XLModConfig.setHwUseBookId(bkIds.get(pos));
                            Toast.makeText(XLModActivity.this, "已选用课本：" + bkNames.get(pos), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        actionButton(cardHwFix, "读取课时（为上面选中的课本拉取单元/课时）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { XLModHelper.captureLessons(XLModActivity.this); refreshHwStatus(); }
        });
        final String[] chosenLesson = {XLModConfig.getHwUseLessonId()};
        int lsIdx = 0;
        for (int i = 0; i < lsIds.size(); i++) {
            if (lsIds.get(i).equals(chosenLesson[0])) { lsIdx = i; break; }
        }
        spinnerRow(cardHwFix, "课时（注入/发布使用）",
                lsNames.isEmpty() ? new String[]{"（未读到课时）"} : lsNames.toArray(new String[0]), lsIdx,
                new SpinnerWatcher() {
                    public void onPos(int pos) {
                        if (pos >= 0 && pos < lsIds.size()) {
                            XLModConfig.setHwUseLessonId(lsIds.get(pos));
                            chosenLesson[0] = lsIds.get(pos);
                            XLModHelper.prefetchQuestions(lsIds.get(pos), XLModConfig.getHwQCount());
                            Toast.makeText(XLModActivity.this, "已选用课时：" + lsNames.get(pos), Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        // ---------- 数据管理 ----------
        sectionLabel(cardHwFix, "数据管理");
        actionButton(cardHwFix, "导出数据（/sdcard/Download/xlmod_hw_targets.json）", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) { XLModHelper.exportTargets(XLModActivity.this); }
        });
        actionButton(cardHwFix, "导入数据（从上面的文件恢复）", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) { XLModHelper.importTargets(XLModActivity.this); refreshHwStatus(); }
        });
        addCardGated(content, cardHwFix, gHw);
        tipGated(content, gHw, "数据只增不减：重复抓取只合并，不会清空。\n文件：/sdcard/Download/xlmod_hw_data.json（另有 .bak 备份）。");

        // ===== 自动签到 =====
        boolean gSign = addGroupHeaderGated(content, "自动签到", "auto_sign");
        LinearLayout cardSign = card();
        switchRow(cardSign,
                "循环计时器（每 5 秒检查一次时间；签到/打榜/云朵到点即执行，不必再切页面触发）",
                XLModConfig.isTimerEnabled(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setTimerEnabled(((MiuixSwitch) v).isChecked()); } });
        switchRow(cardSign,
                "自动签到（打开App时自动执行用户空间签到+无尽大陆签到）",
                XLModConfig.isAutoSign(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setAutoSign(((MiuixSwitch) v).isChecked()); } });
        EditText etSignTime = inputRow(cardSign, "签到时间(HH:mm)", XLModConfig.getSignTime(), ++rowId);
        final EditText fEtSignTime = etSignTime;
        etSignTime.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setSignTime(fEtSignTime.getText().toString());
            }
        });
        addCardGated(content, cardSign, gSign);
        tipGated(content, gSign, "计时器状态：" + XLModHelper.timerStatusText()
                + "\n填 「00:00」 表示任意时间；到点自动签到，失败会自动重试。自动打榜也用同一个计时器。");

        // ===== 自动打榜 =====
        boolean gChallenge = addGroupHeaderGated(content, "自动打榜（金榜题名·同学对战）", "auto_challenge");
        LinearLayout cardAuto = card();
        switchRow(cardAuto,
                "全自动打榜：到点自动开打/自动答题/自动退出，每学科上限后自动换学科，全打完标记今日完成",
                XLModConfig.isAutoChallenge(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setAutoChallenge(((MiuixSwitch) v).isChecked()); } });
        actionButton(cardAuto, "手动执行一次自动打榜（无视今日标记/时间）", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            XLModHelper.forceRunChallenge(XLModActivity.this);
                            android.widget.Toast.makeText(XLModActivity.this, "已触发，将自动打开金榜题名首页探测学科", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(XLModActivity.this, "触发失败: " + t, android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        EditText etStart = inputRow(cardAuto, "开始时间(HH:mm)", XLModConfig.getChallengeStartTime(), ++rowId);
        final EditText fEtStart = etStart;
        etStart.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setChallengeStartTime(fEtStart.getText().toString());
            }
        });
        EditText etEntry = inputRow(cardAuto, "进入延迟(ms)", String.valueOf(XLModConfig.getChallengeEntryDelay()), ++rowId);
        final EditText fEtEntry = etEntry;
        etEntry.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try { XLModConfig.setChallengeEntryDelay(Integer.parseInt(fEtEntry.getText().toString().trim())); } catch (Throwable t) {}
            }
        });
        EditText etAns = inputRow(cardAuto, "答题延迟(ms)", String.valueOf(XLModConfig.getChallengeAnswerDelay()), ++rowId);
        final EditText fEtAns = etAns;
        etAns.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try { XLModConfig.setChallengeAnswerDelay(Integer.parseInt(fEtAns.getText().toString().trim())); } catch (Throwable t) {}
            }
        });
        EditText etExit = inputRow(cardAuto, "退出延迟(ms)", String.valueOf(XLModConfig.getChallengeExitDelay()), ++rowId);
        final EditText fEtExit = etExit;
        etExit.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try { XLModConfig.setChallengeExitDelay(Integer.parseInt(fEtExit.getText().toString().trim())); } catch (Throwable t) {}
            }
        });
        EditText etCap = inputRow(cardAuto, "每学科次数", String.valueOf(XLModConfig.getBattlesPerSubject()), ++rowId);
        final EditText fEtCap = etCap;
        etCap.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    int v = Integer.parseInt(fEtCap.getText().toString().trim());
                    if (v < 1) v = 1;
                    if (v > 3) v = 3;
                    XLModConfig.setBattlesPerSubject(v);
                } catch (Throwable t) {}
            }
        });
        EditText etSubs = inputRow(cardAuto, "学科列表(id:名称)", XLModConfig.getChallengeSubjects(), ++rowId);
        final EditText fEtSubs = etSubs;
        etSubs.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setChallengeSubjects(fEtSubs.getText().toString());
            }
        });
        addCardGated(content, cardAuto, gChallenge);
        tipGated(content, gChallenge, "到点自动进「同学对战」：自动答题、获胜自动退出，学科打满自动换下一科。\n需要先开启「自动作答」；打榜页出现后请不要手动操作。");

        // ===== 云朵助手 =====
        boolean gFlower = addGroupHeaderGated(content, "云朵助手", "cloud_flower");
        LinearLayout cardCloud = card();
        switchRow(cardCloud,
                "自动领取云朵（任务云朵+生日云朵，每天一次；对战获胜云朵在每局结束自动领取）",
                XLModConfig.isAutoCloud(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setAutoCloud(((MiuixSwitch) v).isChecked()); } });
        actionButton(cardCloud, "立即领取云朵", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            XLModHelper.manualClaimAll(XLModActivity.this);
                            android.widget.Toast.makeText(XLModActivity.this, "已发起领取（结果以云朵明细为准）", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                        }
                    }
                });
        addCardGated(content, cardCloud, gFlower);
        tipGated(content, gFlower, "把能领的云朵自动领掉（签到、学习任务、生日、对战奖励）。没有可领的会提示。");

        // ===== 排行榜 =====
        boolean gRank = addGroupHeaderGated(content, "排行榜", "rank");
        LinearLayout cardRank = card();
        switchRow(cardRank,
                "榜单显示用户ID（用户名后追加ID）",
                XLModConfig.isShowRankUserId(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setShowRankUserId(((MiuixSwitch) v).isChecked()); } });
        addCardGated(content, cardRank, gRank);

        // ===== 金榜题名 =====
        boolean gAnswer = addGroupHeaderGated(content, "金榜题名", "answer");
        LinearLayout cardChallenge = card();
        switchRow(cardChallenge,
                "自动作答（选择/判断/填空自动填写答案，全部题型）",
                XLModConfig.isAutoAnswer(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setAutoAnswer(((MiuixSwitch) v).isChecked()); } });
        switchRow(cardChallenge,
                "答题页答案悬浮窗（可拖动，点击折叠/展开）",
                XLModConfig.isShowAnswerFloat(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setShowAnswerFloat(((MiuixSwitch) v).isChecked()); } });
        spinnerRow(cardChallenge, "答案获取方式",
                new String[]{"本地数据", "判题接口（题目ID→exercise/correct）", "详情接口（挑战ID→queryCompetitionDetail）"},
                Math.min(2, Math.max(0, XLModConfig.getAnswerFetchMode())),
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setAnswerFetchMode(pos); } });
        switchRow(cardChallenge,
                "调试：三窗对比（同时显示 本地/接口/详情 三个悬浮窗）",
                XLModConfig.isDebugFloat(),
                new View.OnClickListener() { public void onClick(View v) { XLModConfig.setDebugFloat(((MiuixSwitch) v).isChecked()); } });
        addCardGated(content, cardChallenge, gAnswer);
        tipGated(content, gAnswer, "答案来源：①本地题目标记 ②判题接口 ③详情接口，三窗调试可对比。\n口语题无法自动作答；英语听力已修复自动回填。");

        // ===== 隐私隐藏 =====
        boolean gPrivacy = addGroupHeaderGated(content, "隐私隐藏", "privacy");
        LinearLayout cardPrivacy = card();
        spinnerRow(cardPrivacy, "设备ID模式",
                new String[]{"关闭（原样上报）", "空值（去除设备ID标识）", "自定义值"},
                Math.min(2, Math.max(0, XLModConfig.getPrivacyMode())),
                new SpinnerWatcher() { public void onPos(int pos) { XLModConfig.setPrivacyMode(pos); } });
        EditText etPrivacy = inputRow(cardPrivacy, "自定义值", XLModConfig.getPrivacyDeviceId(), ++rowId);
        final EditText fEtPrivacy = etPrivacy;
        etPrivacy.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
                XLModConfig.setPrivacyDeviceId(fEtPrivacy.getText().toString());
            }
        });
        addCardGated(content, cardPrivacy, gPrivacy);
        tipGated(content, gPrivacy, "伪装请求里的 deviceId（MD5(androidId+IMEI+机型) 或安装 UUID）。\n注意：登录发生在启动早期，改完请重启 App 再登录。");

        // ===== 日志 =====
        boolean gLogs = addGroupHeaderGated(content, "日志", "logs");
        LinearLayout cardLog = card();
        actionButton(cardLog, "查看运行/崩溃日志", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            ScrollView sv = new ScrollView(XLModActivity.this);
                            TextView tv = new TextView(XLModActivity.this);
                            tv.setTextSize(12);
                            tv.setTypeface(Typeface.MONOSPACE);
                            tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                            String crash = XLModConfig.crashGet();
                            String log = XLModConfig.logGet();
                            StringBuilder sb = new StringBuilder();
                            if (!crash.isEmpty()) {
                                sb.append("===== 最近崩溃 =====\n").append(crash).append("\n\n");
                            }
                            sb.append("===== 运行日志 =====\n").append(log.isEmpty() ? "(空)" : log);
                            tv.setText(sb.toString());
                            sv.addView(tv);
                            new AlertDialog.Builder(XLModActivity.this)
                                    .setTitle("Mod 日志")
                                    .setView(sv)
                                    .setPositiveButton("清除日志", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            XLModConfig.logClear();
                                        }
                                    })
                                    .setNegativeButton("关闭", null)
                                    .show();
                        } catch (Throwable t) {
                        }
                    }
                });
        actionButton(cardLog, "导出日志到下载目录", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            File out = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            File f = new File(out, "xlmod_log.txt");
                            FileWriter w = new FileWriter(f);
                            String crash = XLModConfig.crashGet();
                            if (!crash.isEmpty()) {
                                w.write("===== 最近崩溃 =====\n" + crash + "\n\n");
                            }
                            w.write("===== 运行日志 =====\n" + (XLModConfig.logGet().isEmpty() ? "(空)" : XLModConfig.logGet()));
                            w.close();
                            android.widget.Toast.makeText(XLModActivity.this, "已导出: " + f.getAbsolutePath(), android.widget.Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(XLModActivity.this, "导出失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
        addCardGated(content, cardLog, gLogs);
        tipGated(content, gLogs, "复现问题后点导出，把 /sdcard/Download/xlmod_log.txt 发我即可。");

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    // ================= Miuix 风格 UI 辅助 =================

    /** 分组标题（灰色小字，位于卡片上方） */
    @Override
    protected void onResume() {
        super.onResume();
        refreshHwStatus();
    }

    private static TextView sHwStatusView = null;

    /** 发作业板块状态文本（本地数据总览） */
    private String hwStatusText() {
        try {
            String json = XLModConfig.getHwTargetsJson();
            org.json.JSONObject o = new org.json.JSONObject(json == null || json.isEmpty() ? "{}" : json);
            int lessons = 0;
            org.json.JSONArray bs = o.optJSONArray("books");
            if (bs != null) {
                for (int i = 0; i < bs.length(); i++) {
                    org.json.JSONObject b = bs.optJSONObject(i);
                    org.json.JSONArray us = b == null ? null : b.optJSONArray("units");
                    if (us == null) continue;
                    for (int u = 0; u < us.length(); u++) {
                        org.json.JSONObject un = us.optJSONObject(u);
                        org.json.JSONArray ls = un == null ? null : un.optJSONArray("lessons");
                        if (ls != null) lessons += ls.length();
                    }
                }
            }
            return "本地数据：" + "年级 " + lenOf(o, "grades") + " · 班级 " + lenOf(o, "classes")
                    + " · 学生 " + lenOf(o, "students") + " · 课本 " + lenOf(o, "books") + " · 课时 " + lessons
                    + "\n开关 " + (XLModConfig.isHwFixEnabled() ? "开" : "关")
                    + " · 自动抓取 " + (XLModHelper.isAutoCaptureRunning() ? "运行中" : "已停止")
                    + " · 悬浮窗 " + (XLModHelper.isTargetFloatVisible() ? "显示" : "隐藏")
                    + "\n文件 /sdcard/Download/xlmod_hw_data.json";
        } catch (Throwable t) {
            return "本地数据读取失败: " + t;
        }
    }

    private int lenOf(org.json.JSONObject o, String k) {
        org.json.JSONArray a = o.optJSONArray(k);
        return a == null ? 0 : a.length();
    }

    private void refreshHwStatus() {
        try {
            if (sHwStatusView != null) sHwStatusView.setText(hwStatusText());
        } catch (Throwable ignored) {
        }
    }

    /** 分组小标题（蓝色，用于卡片内的小节，如 ① 抓取） */
    private void sectionLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFF1976D2);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(18), dp(16), dp(18), dp(4));
        parent.addView(tv);
    }

    /** 分组标题（卡片外，带蓝色竖条：比纯灰字更清晰的分区感） */
    /** 重新构建整个面板（远程配置变更时调用，实现"实时更改"） */
    private void rebuildUi() {
        buildUi();
    }

    /** 每次打开面板都展示公告（配置里的 notice） */
    private void showNoticeIfAny() {
        final String notice = XLModFeatures.notice();
        if (notice == null || notice.isEmpty()) return;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("公告")
                    .setMessage(notice)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 功能区开关（远程配置；默认开） */
    private boolean featOn(String id) {
        return XLModFeatures.enabled(id);
    }

    /** 分组标题（带远程开关）：返回该功能区是否启用；关闭时标题后追加提示 */
    private boolean addGroupHeaderGated(LinearLayout parent, String text, String featureId) {
        boolean on = featOn(featureId);
        addGroupHeader(parent, on ? text : text + "（已被远程配置关闭）");
        return on;
    }

    /** 卡片：功能区关闭时不添加卡片 */
    private void addCardGated(LinearLayout parent, View card, boolean on) {
        if (on) addCard(parent, card);
    }

    /** 简介：功能区关闭时不显示 */
    private void tipGated(LinearLayout parent, boolean on, String text) {
        if (on) tip(parent, text);
    }

    private void addGroupHeader(LinearLayout parent, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(20), dp(14), dp(8));
        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(C_ACCENT);
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        row.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(14)));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTextColor(0xFF3C4043);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(9), 0, 0, 0);
        row.addView(tv);
        parent.addView(row);
    }

    /** 白色圆角卡片（统一圆角/描边/投影） */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(C_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(1, C_BORDER);
        card.setBackground(gd);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(1));
        }
        return card;
    }

    private void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        parent.addView(card, lp);
    }

    /** 行容器：白底、左右留白、垂直居中 */
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(12), 0);
        row.setMinimumHeight(dp(52));
        return row;
    }

    private TextView rowLabel(String text) {
        TextView lab = new TextView(this);
        lab.setText(text);
        lab.setTextSize(15);
        lab.setTextColor(C_TEXT);
        lab.setMaxLines(2);
        return lab;
    }

    /** 开关行：点击整行也可切换；开关为 Miuix/MIUI 风格（固定 46×24dp 自定义绘制，不受系统主题影响比例） */
    private void switchRow(LinearLayout card, String label, boolean checked, final View.OnClickListener listener) {
        final LinearLayout row = row();
        TextView tv = rowLabel(label);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        final MiuixSwitch sw = miuixSwitch(checked);
        // 自定义 View 不会像系统 Switch 自动翻转：开关自身与整行都执行"翻转+回调"
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
                listener.onClick(sw);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
                listener.onClick(sw);
            }
        });
        card.addView(row);
        card.addView(divider());
    }

    /** Miuix/MIUI 风格开关：固定 46×24dp（比例 1.92）+ 18dp 白钮，自绘不受系统 Switch 拉伸影响 */
    private MiuixSwitch miuixSwitch(boolean checked) {
        MiuixSwitch sw = new MiuixSwitch(this);
        sw.setChecked(checked);
        return sw;
    }

    /** MIUI 风格开关控件：自绘胶囊轨道 + 白色圆钮；onMeasure 固定尺寸，任何主题/密度下比例一致 */
    static class MiuixSwitch extends View {
        private boolean checked;
        private final GradientDrawable trackOn;
        private final GradientDrawable trackOff;
        private final GradientDrawable thumb;

        MiuixSwitch(Context c) {
            super(c);
            setClickable(true);
            trackOn = new GradientDrawable();
            trackOn.setColor(0xFF07C160);
            trackOn.setCornerRadius(d(c, 12));
            trackOff = new GradientDrawable();
            trackOff.setColor(0xFFE9E9EB);
            trackOff.setCornerRadius(d(c, 12));
            thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(0xFFFFFFFF);
            thumb.setStroke(d(c, 1), 0xFFD5D5D8);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean b) {
            checked = b;
            setBackground(b ? trackOn : trackOff);
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(d(getContext(), 46), d(getContext(), 24));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int pad = d(getContext(), 3);
            int size = getHeight() - pad * 2;
            int left = checked ? (getWidth() - pad - size) : pad;
            thumb.setBounds(left, pad, left + size, pad + size);
            thumb.draw(canvas);
        }

        static int d(Context c, int v) {
            return Math.round(c.getResources().getDisplayMetrics().density * v);
        }
    }

    /** 输入行：左标签 + 右值（右对齐） */
    private EditText inputRow(LinearLayout card, String label, String value, int id) {
        LinearLayout row = row();
        TextView lab = rowLabel(label);
        row.addView(lab, new LinearLayout.LayoutParams(dp(104), -2));
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(15);
        et.setTextColor(C_TEXT);
        et.setGravity(Gravity.RIGHT);
        et.setSingleLine(true);
        et.setId(id);
        et.setPadding(dp(8), 0, dp(8), 0);
        et.setBackgroundColor(Color.TRANSPARENT);
        row.addView(et, new LinearLayout.LayoutParams(0, dp(50), 1f));
        card.addView(row);
        card.addView(divider());
        return et;
    }

    /** 下拉行：左标签 + 右侧 Spinner（选中值右对齐、超长省略号，避免被挤成半截） */
    private Spinner spinnerRow(LinearLayout card, String label, String[] items, int defaultIndex,
                               final SpinnerWatcher watcher) {
        LinearLayout row = row();
        TextView lab = rowLabel(label);
        row.addView(lab, new LinearLayout.LayoutParams(dp(104), -2));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    tv.setTextSize(15);
                    tv.setTextColor(C_TEXT);
                    tv.setSingleLine(true);
                    tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tv.setPadding(0, 0, dp(4), 0);
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner sp = new Spinner(this);
        sp.setAdapter(adapter);
        sp.setSelection(defaultIndex);
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long l) {
                if (watcher != null) watcher.onPos(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        row.addView(sp, new LinearLayout.LayoutParams(0, dp(50), 1f));
        card.addView(row);
        card.addView(divider());
        return sp;
    }

    /** 卡片内动作按钮：primary=MIUI 蓝底白字；secondary=白底蓝字描边 */
    private void actionButton(LinearLayout parent, String text, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        if (primary) {
            gd.setColor(C_ACCENT);
            b.setTextColor(Color.WHITE);
        } else {
            gd.setColor(C_CARD);
            gd.setStroke(dp(1), C_ACCENT);
            b.setTextColor(C_ACCENT);
        }
        b.setBackground(gd);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.setMargins(dp(14), dp(10), dp(14), dp(10));
        parent.addView(b, lp);
    }

    /** 说明文字（近白便签底 + 细描边；支持 **加粗** 标记，不再把星号显示出来） */
    private void tip(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(mdBold(text));
        tv.setTextSize(12);
        tv.setTextColor(0xFF6E7681);
        tv.setLineSpacing(dp(3), 1.05f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFBFCFD);
        bg.setCornerRadius(dp(10));
        bg.setStroke(1, 0xFFEAEDF2);
        tv.setBackground(bg);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(4), dp(6), dp(4), dp(12));
        parent.addView(tv, lp);
    }

    /** 极简 Markdown：只处理 **加粗**（去掉标记并把中间文字加粗），其余原样 */
    private CharSequence mdBold(String text) {
        if (text == null || text.indexOf("**") < 0) return text;
        StringBuilder sb = new StringBuilder();
        java.util.ArrayList<int[]> spans = new java.util.ArrayList<int[]>();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("**", i);
            if (start < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, start);
            int end = text.indexOf("**", start + 2);
            if (end < 0) {
                sb.append(text, start, text.length());
                break;
            }
            int s = sb.length();
            sb.append(text, start + 2, end);
            spans.add(new int[]{s, sb.length()});
            i = end + 2;
        }
        android.text.SpannableString ss = new android.text.SpannableString(sb.toString());
        for (int k = 0; k < spans.size(); k++) {
            int[] sp = spans.get(k);
            ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), sp[0], sp[1],
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private View buildSimpleView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setPadding(dp(24), dp(24), dp(24), dp(24));
        return tv;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    interface SpinnerWatcher {
        void onPos(int pos);
    }

    static abstract class SimpleWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
