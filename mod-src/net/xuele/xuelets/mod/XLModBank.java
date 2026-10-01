/*
 * XLMod — 学乐云客户端增强模块
 * Copyright (C) 2026 wg-1337
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本文件是 XLMod 的一部分：你可以按 GNU Affero 通用公共许可证第 3 版（或更高版本）条款
 * 使用、修改与再分发；通过网络提供服务时须向使用者提供对应源码。详见仓库根目录 LICENSE。
 */

package net.xuele.xuelets.mod;

import net.xuele.android.ui.question.AnswersBean;
import net.xuele.android.ui.question.ChallengeUserAnswer;
import net.xuele.xuelets.challenge.model.M_ChallengeQuestion;
import net.xuele.xuelets.challenge.util.ChallengeParamHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对战题库（V4.3p）：把**同学对战**里打过的题目整题记录下来（题干 + 选项 + 正确答案），
 * 普通挑战遇到同一道题时直接用题库里的**正确答案**作答。
 *
 * <p><b>为什么记"正确答案"而不是"选项序号"</b>：普通挑战与同学对战的选项顺序经常不同
 * （同一道题 A/B/C/D 会打乱），所以入库时同时记 选项文本 / 选项ID / 选项字母，
 * 应用时按 <b>文本优先</b> 匹配回当前题目的选项，其次才用ID、字母兜底 —— 顺序变了也照样答对。</p>
 *
 * <p>存储：内存索引 + <code>/sdcard/Download/xlmod_qbank.json</code>（读不到下载目录时退回应用私有目录）。
 * 只在同学对战页采一次（按挑战ID去重），保存节流 5 秒，避免频繁写盘。</p>
 */
public final class XLModBank {

    /** 题库条目上限（超出后按时间淘汰最旧的） */
    private static final int MAX_ITEMS = 2000;

    // 题型 id（**以宿主的映射为准**：3=填空、51=口语(录音，无输入框)、52=听力/听写(有输入框)）
    private static final int QT_FILL = 3;
    private static final int QT_SPOKEN = 51;
    private static final int QT_LISTEN = 52;

    private static final ConcurrentHashMap<String, JSONObject> sById = new ConcurrentHashMap<String, JSONObject>();
    private static final ConcurrentHashMap<String, String> sSig2Id = new ConcurrentHashMap<String, String>();

    private static volatile boolean sLoaded = false;
    private static volatile boolean sDirty = false;
    private static volatile long sLastSaveAt = 0L;
    private static volatile long sLastHarvestAt = 0L;
    private static volatile long sLastWriteOkAt = 0L;
    private static volatile int sHarvestedBattles = 0;
    private static volatile String sLastBattleKey = "";
    private static volatile String sLastError = "";

    private XLModBank() {
    }

    // ================= 开关 =================

    public static boolean enabled() {
        return XLModConfig.isBankEnabled();
    }

    // ================= 采集（同学对战） =================

    /**
     * 整局采集：同学对战每题出现时调用（内部按 挑战ID+学科 去重，一局只跑一次）。
     *
     * @return 本局新入库题目数
     */
    public static int harvestBattle(ChallengeParamHelper ph, String subjectId) {
        try {
            if (!enabled() || ph == null) return 0;
            String key = (ph.logId == null ? "" : ph.logId) + "|" + (ph.randomKey == null ? "" : ph.randomKey)
                    + "|" + (subjectId == null ? "" : subjectId);
            if (key.equals(sLastBattleKey) && System.currentTimeMillis() - sLastHarvestAt < 300000L) return 0;
            sLastBattleKey = key;
            sLastHarvestAt = System.currentTimeMillis();
            List<M_ChallengeQuestion> list = ph.mQuestionList;
            if (list == null || list.isEmpty()) return 0;
            int n = 0;
            for (M_ChallengeQuestion q : list) {
                if (harvestQuestion(q, "classmate")) n++;
            }
            sHarvestedBattles++;
            save(false);
            XLModConfig.logAppend("[题库] 同学对战采集: 本局入库 " + n + " 题，题库共 " + size() + " 题"
                    + "（学科 " + (subjectId == null || subjectId.isEmpty() ? "?" : subjectId) + "）");
            return n;
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
            return 0;
        }
    }

    /**
     * V5.0p：**只收录题目本身**（题干 + 选项 + 题型），不要求有答案。
     * 普通挑战的题目原本一个都不入库（本地没有答案标记），现在答题时先把题目收下来，
     * 等结算时从「挑战详情」把答案补上（{@link #harvestQuestion} 会合并进同一条目）。
     */
    public static boolean noteQuestion(M_ChallengeQuestion q, String src) {
        try {
            if (!enabled() || q == null) return false;
            String qid = q.questionId == null ? "" : q.questionId.trim();
            if (qid.isEmpty()) return false;
            int type = typeOf(q);
            if (type == QT_SPOKEN) return false;            // 口语录音题没有文本答案，不收
            JSONObject e = new JSONObject();
            e.put("id", qid);
            e.put("t", type);
            e.put("c", cut(q.content, 300));
            e.put("src", src == null ? "" : src);
            e.put("ts", System.currentTimeMillis());
            JSONArray opts = new JSONArray();
            List<AnswersBean> ans = q.answers;
            if (ans != null) {
                for (int i = 0; i < ans.size(); i++) {
                    AnswersBean a = ans.get(i);
                    if (a == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("i", a.answerId == null ? "" : a.answerId);
                    o.put("c", cut(a.answerContent, 200));
                    o.put("d", a.sortid == null ? "" : a.sortid);
                    opts.put(o);
                }
            }
            if (opts.length() > 0) e.put("opts", opts);
            e.put("sig", sigOf(type, q.content));
            put(qid, e);                                    // put() 会继承旧条目里已有的答案
            sDirty = true;
            save(false);
            return true;
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
            return false;
        }
    }

    /**
     * 单题入库（同学对战 / 详情接口共用）。
     *
     * <p><b>V4.6p 重要修正</b>：填空(3)/听力(52) 的文本答案**只认"带正确标记"的**；
     * 以前"没有 isCorrect 就拿 answerContent 当标准答案"，会把学生自己提交的答案
     * （尤其是盲填的「不会」）当成正确答案存进题库 —— 这条路径已彻底关闭，
     * 标准答案改由详情答案映射提供（{@link #putListen} / {@link #putFillFromDetail}）。</p>
     */
    public static boolean harvestQuestion(M_ChallengeQuestion q, String src) {
        try {
            if (!enabled() || q == null) return false;
            String qid = q.questionId == null ? "" : q.questionId.trim();
            if (qid.isEmpty()) return false;
            int type = typeOf(q);
            JSONObject e = new JSONObject();
            e.put("id", qid);
            e.put("t", type);
            e.put("c", cut(q.content, 300));
            e.put("src", src == null ? "" : src);
            e.put("ts", System.currentTimeMillis());
            JSONArray opts = new JSONArray();
            JSONArray right = new JSONArray();
            JSONArray fill = new JSONArray();
            List<AnswersBean> ans = q.answers;
            if (ans != null) {
                for (int i = 0; i < ans.size(); i++) {
                    AnswersBean a = ans.get(i);
                    if (a == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("i", a.answerId == null ? "" : a.answerId);
                    o.put("c", cut(a.answerContent, 200));
                    o.put("d", a.sortid == null ? "" : a.sortid);
                    opts.put(o);
                    boolean correct = a.isCorrect != null && "1".equals(a.isCorrect.trim());
                    if (correct) right.put(i);
                    if ((type == QT_FILL || type == QT_LISTEN) && correct && !isJunk(a.answerContent)) {
                        fill.put(a.answerContent.trim());    // 只有带正确标记的文本才收
                    }
                }
            }
            if (type == QT_FILL) {
                if (fill.length() == 0) {
                    // V5.0p：没答案也先把题目收下来（答案等结算时从详情补，put() 会合并）
                    noteQuestion(q, src);
                    return false;
                }
                e.put("f", fill);
            } else if (type == QT_LISTEN) {
                if (fill.length() == 0) {
                    noteQuestion(q, src);
                    return false;
                }
                e.put("l", fill.optString(0, "").trim());
            } else if (type == QT_SPOKEN) {
                return false;                                 // 口语（51）是录音题：没有可填的文本答案
            } else {
                if (right.length() == 0) {
                    noteQuestion(q, src);                     // 只收了题目，等详情补答案
                    return false;
                }
                e.put("k", right);
            }
            if (opts.length() > 0) e.put("opts", opts);
            e.put("sig", sigOf(type, q.content));
            put(qid, e);
            return true;
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
            return false;
        }
    }

    /**
     * V4.6p：判断"垃圾答案"——空、盲填内容（面板可配的「不会」等）。
     * 这类文本永不入库、也永不当作已命中的答案。
     */
    public static boolean isJunk(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return true;
        try {
            String blind = XLModConfig.getBlindFillText();
            if (blind != null && !blind.isEmpty() && t.equals(blind.trim())) return true;
            if ("不会".equals(t) || "不知道".equals(t)) return true;   // 兜底：常见盲填词
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 听力/听写题(52)标准答案入库 —— 来源必须是**详情答案映射的 listenServerDesc（其次 sContent）**，
     * 不是题目选项里的 answerContent（那里可能是学生自己提交的答案）。
     */
    public static void putListen(String qid, String text) {
        try {
            if (!enabled() || qid == null || qid.trim().isEmpty()) return;
            if (isJunk(text)) return;                       // V4.6p：盲填内容永不入库
            JSONObject e = sById.get(qid.trim());
            if (e == null) {
                e = new JSONObject();
                e.put("id", qid.trim());
                e.put("t", QT_LISTEN);
                e.put("c", "");
                e.put("src", "detail");
            }
            e.put("l", text.trim());
            e.put("ts", System.currentTimeMillis());
            put(qid.trim(), e);
            save(false);
        } catch (Throwable ignored) {
        }
    }

    /** V4.6p：填空题(3)标准答案入库（来源=详情答案映射 answerContentList，即每空的 sContent） */
    public static void putFillFromDetail(String qid, java.util.List<String> texts) {
        try {
            if (!enabled() || qid == null || qid.trim().isEmpty()) return;
            if (texts == null || texts.isEmpty()) return;
            JSONArray f = new JSONArray();
            for (String s : texts) {
                if (isJunk(s)) return;                      // 有一空是垃圾就整题不记（宁缺勿错）
                f.put(s.trim());
            }
            if (f.length() == 0) return;
            JSONObject e = sById.get(qid.trim());
            if (e == null) {
                e = new JSONObject();
                e.put("id", qid.trim());
                e.put("t", QT_FILL);
                e.put("c", "");
                e.put("src", "detail");
            }
            if (e.optInt("t", 0) != QT_FILL) return;
            e.put("f", f);
            e.put("ts", System.currentTimeMillis());
            put(qid.trim(), e);
            save(false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 写入/合并条目。
     *
     * <p><b>V5.0p 修复</b>：以前"旧条目有答案、新条目没答案"时会把整个条目换成新的 →
     * **已收录的答案被覆盖丢失**。现在改成**合并**：新条目缺的答案字段从旧条目继承。</p>
     */
    private static void put(String qid, JSONObject e) {
        try {
            load();
            JSONObject old = sById.get(qid);
            if (old != null && old.optInt("t", 0) == e.optInt("t", 0)) {
                // 答案字段：谁有留谁（新条目优先，缺的从旧条目继承）
                if (!e.has("k") && old.has("k")) e.put("k", old.optJSONArray("k"));
                if (!e.has("f") && old.has("f")) e.put("f", old.optJSONArray("f"));
                if (!e.has("l") && old.has("l")) e.put("l", old.optString("l", ""));
                if (e.optJSONArray("opts") == null && old.optJSONArray("opts") != null) {
                    e.put("opts", old.optJSONArray("opts"));
                }
                if ((e.optString("c", "")).isEmpty() && !old.optString("c", "").isEmpty()) {
                    e.put("c", old.optString("c", ""));
                }
                if (old.optString("src", "").length() > 0) e.put("src", old.optString("src", ""));
                if (old.optLong("ts", 0L) > 0) e.put("ts", old.optLong("ts", 0L));
            }
            sById.put(qid, e);
            String sig = e.optString("sig", "");
            if (!sig.isEmpty()) sSig2Id.put(sig, qid);
            sDirty = true;
            if (sById.size() > MAX_ITEMS) trim();
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
        }
    }

    /** 某题是否已经收录到答案（面板/日志用） */
    public static boolean hasAnswerFor(String qid) {
        try {
            load();
            if (qid == null) return false;
            return hasAnswer(sById.get(qid.trim()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasAnswer(JSONObject e) {
        if (e == null) return false;
        if (e.has("k") && e.optJSONArray("k") != null && e.optJSONArray("k").length() > 0) return true;
        if (e.has("f") && e.optJSONArray("f") != null && e.optJSONArray("f").length() > 0) return true;
        return e.has("l") && !e.optString("l", "").isEmpty();
    }

    private static void trim() {
        try {
            String oldest = null;
            long ts = Long.MAX_VALUE;
            for (Iterator<String> it = sById.keySet().iterator(); it.hasNext(); ) {
                String k = it.next();
                JSONObject e = sById.get(k);
                long t = e == null ? 0L : e.optLong("ts", 0L);
                if (t < ts) {
                    ts = t;
                    oldest = k;
                }
            }
            if (oldest != null) {
                JSONObject e = sById.remove(oldest);
                if (e != null) sSig2Id.remove(e.optString("sig", ""));
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 应用（普通挑战） =================

    /**
     * 用题库作答：命中则把正确答案写进 {@code ua}。
     * 选项题按 <b>选项文本优先</b> 匹配当前题目（顺序不同也能对上），其次选项ID，最后选项字母。
     */
    public static boolean apply(String qid, M_ChallengeQuestion q, ChallengeUserAnswer ua) {
        try {
            if (!enabled() || ua == null || q == null) return false;
            JSONObject e = find(qid, q);
            if (e == null) return false;
            int type = e.optInt("t", 0);
            if (type == QT_FILL) {
                JSONArray f = e.optJSONArray("f");
                if (f == null || f.length() == 0) return false;
                ua.answerIdList.clear();
                ua.answerContentList.clear();
                for (int i = 0; i < f.length(); i++) ua.answerContentList.add(f.optString(i, ""));
                return true;
            }
            if (type == QT_LISTEN) {
                // 听力/听写（52）：标准答案文本（l 优先，其次 f）
                String l = e.optString("l", "");
                if (l.isEmpty()) {
                    JSONArray f = e.optJSONArray("f");
                    if (f != null && f.length() > 0) l = f.optString(0, "");
                }
                if (l.isEmpty()) return false;
                ua.answerIdList.clear();
                ua.answerContentList.clear();
                ua.answerContentList.add(l);
                return true;
            }
            if (type == QT_SPOKEN) return false;      // 口语（51）：录音题，无法用文本作答
            JSONArray opts = e.optJSONArray("opts");
            JSONArray k = e.optJSONArray("k");
            if (opts == null || k == null || k.length() == 0) return false;
            List<AnswersBean> cur = q.answers;
            if (cur == null || cur.isEmpty()) return false;
            ua.answerIdList.clear();
            ua.answerContentList.clear();
            int matched = 0;
            for (int i = 0; i < k.length(); i++) {
                int idx = k.optInt(i, -1);
                if (idx < 0 || idx >= opts.length()) continue;
                JSONObject o = opts.optJSONObject(idx);
                if (o == null) continue;
                String content = o.optString("c", "");
                String aid = o.optString("i", "");
                String letter = o.optString("d", "");
                AnswersBean hit = byContent(cur, content);
                if (hit == null) hit = byId(cur, aid);
                if (hit == null) hit = byLetter(cur, letter);
                if (hit == null) continue;
                ua.answerIdList.add(hit.answerId == null ? "" : hit.answerId);
                String c = (hit.sortid != null && !hit.sortid.isEmpty()) ? hit.sortid
                        : (hit.answerContent == null ? "" : hit.answerContent);
                ua.answerContentList.add(c);
                matched++;
            }
            if (matched > 0) {
                XLModConfig.logAppend("[题库] 命中作答: " + qid + " → " + matched + " 项（顺序已按当前题目重排）");
                return true;
            }
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
        }
        return false;
    }

    /** 题库状态读取失败兜底 */
    public static String listenTextOf(String qid) {
        try {
            if (!enabled() || qid == null || qid.trim().isEmpty()) return "";
            JSONObject e = sById.get(qid.trim());
            if (e == null) return "";
            if (e.optInt("t", 0) != QT_LISTEN) return "";
            String l = e.optString("l", "");
            if (!l.isEmpty()) return l;
            JSONArray f = e.optJSONArray("f");
            return (f != null && f.length() > 0) ? f.optString(0, "") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * V4.7p：不管条目记的是哪种题型，只要里面有文本答案就取出来
     * （旧版本可能把听力答案记成 51 型，导致按类型查不到）。
     */
    public static String anyTextOf(String qid) {
        try {
            if (!enabled() || qid == null || qid.trim().isEmpty()) return "";
            JSONObject e = sById.get(qid.trim());
            if (e == null) return "";
            String l = e.optString("l", "");
            if (!isJunk(l)) return l.trim();
            JSONArray f = e.optJSONArray("f");
            if (f != null && f.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < f.length(); i++) {
                    String s = f.optString(i, "");
                    if (isJunk(s)) return "";
                    if (i > 0) sb.append(" ");
                    sb.append(s.trim());
                }
                return sb.toString();
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject find(String qid, M_ChallengeQuestion q) {
        load();
        if (qid != null && !qid.trim().isEmpty()) {
            JSONObject e = sById.get(qid.trim());
            if (e != null && hasAnswer(e)) return e;
        }
        if (q == null) return null;
        String sig = sigOf(typeOf(q), q.content);
        String id = sSig2Id.get(sig);
        if (id == null) return null;
        JSONObject e = sById.get(id);
        return (e != null && hasAnswer(e)) ? e : null;
    }

    private static AnswersBean byContent(List<AnswersBean> list, String content) {
        String want = norm(content);
        if (want.isEmpty()) return null;
        for (AnswersBean a : list) {
            if (a == null) continue;
            if (norm(a.answerContent).equals(want)) return a;
        }
        return null;
    }

    private static AnswersBean byId(List<AnswersBean> list, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (AnswersBean a : list) {
            if (a != null && id.trim().equals(a.answerId == null ? "" : a.answerId.trim())) return a;
        }
        return null;
    }

    private static AnswersBean byLetter(List<AnswersBean> list, String letter) {
        if (letter == null || letter.trim().isEmpty()) return null;
        for (AnswersBean a : list) {
            if (a != null && letter.trim().equalsIgnoreCase(a.sortid == null ? "" : a.sortid.trim())) return a;
        }
        return null;
    }

    // ================= 统计 / 维护 =================

    public static int size() {
        load();
        return sById.size();
    }

    public static String statsText() {
        try {
            load();
            int withAnswer = 0;
            long last = 0L;
            for (JSONObject e : sById.values()) {
                if (hasAnswer(e)) withAnswer++;
                long t = e.optLong("ts", 0L);
                if (t > last) last = t;
            }
            String when = last <= 0 ? "（暂无）"
                    : new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(last));
            return "题库：" + sById.size() + " 题（可用答案 " + withAnswer + " 题）· 已采集 "
                    + sHarvestedBattles + " 局对战 · 最近更新 " + when
                    + "\n开关 " + (enabled() ? "开（普通挑战优先用题库作答）" : "关")
                    + " · 文件 " + file().getAbsolutePath()
                    + (sLastError.isEmpty() ? "" : "\n最近异常：" + sLastError);
        } catch (Throwable t) {
            return "题库状态读取失败: " + t;
        }
    }

    /** 导出题库到 /sdcard/Download/xlmod_qbank.json（返回路径或错误说明） */
    public static String exportToFile() {
        try {
            load();
            String json = toJson().toString();
            File f = file();
            writeFile(f, json);
            return f.getAbsolutePath() + "（" + sById.size() + " 题，" + json.length() + " 字符）";
        } catch (Throwable t) {
            return "导出失败: " + t;
        }
    }

    public static void clear() {
        try {
            sById.clear();
            sSig2Id.clear();
            sHarvestedBattles = 0;
            sLastBattleKey = "";
            sDirty = true;
            save(true);
            XLModConfig.logAppend("[题库] 已清空");
        } catch (Throwable ignored) {
        }
    }

    // ================= 存取 =================

    private static File file() {
        try {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return new File(dir, "xlmod_qbank.json");
        } catch (Throwable ignored) {
        }
        try {
            android.content.Context c = XLModConfig.appCtx();
            if (c != null) return new File(c.getFilesDir(), "xlmod_qbank.json");
        } catch (Throwable ignored) {
        }
        return new File("xlmod_qbank.json");
    }

    private static void load() {
        if (sLoaded) return;
        synchronized (XLModBank.class) {
            if (sLoaded) return;
            sLoaded = true;
            try {
                File f = file();
                if (f == null || !f.exists() || f.length() <= 0) return;
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                in.close();
                JSONObject root = new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
                JSONObject items = root.optJSONObject("items");
                if (items == null) return;
                Iterator<String> it = items.keys();
                while (it.hasNext()) {
                    String qid = it.next();
                    JSONObject e = items.optJSONObject(qid);
                    if (e == null) continue;
                    sById.put(qid, e);
                    String sig = e.optString("sig", "");
                    if (!sig.isEmpty()) sSig2Id.put(sig, qid);
                }
                XLModConfig.logAppend("[题库] 已加载本地题库: " + sById.size() + " 题");
                purgeJunkEntries();
            } catch (Throwable t) {
                sLastError = "读取题库失败: " + t;
            }
        }
    }

    /**
     * V4.6p：清理历史污染 —— 旧版本可能把盲填内容（「不会」等）当成正确答案存进题库。
     * 载入时跑一次：丢掉"答案全是垃圾"的条目，以及条目里垃圾的 f/l 字段。
     */
    private static void purgeJunkEntries() {
        try {
            java.util.ArrayList<String> drop = new java.util.ArrayList<String>();
            int cleaned = 0;
            for (String qid : new java.util.ArrayList<String>(sById.keySet())) {
                JSONObject e = sById.get(qid);
                if (e == null) continue;
                int type = e.optInt("t", 0);
                if (type != QT_FILL && type != QT_LISTEN) continue;
                // V5.0p：只收题目、还没答案的条目（骨架）不动它 —— 等详情补答案
                if (!e.has("f") && !e.has("l") && !e.has("k")) continue;
                boolean hasReal = false;
                JSONArray f = e.optJSONArray("f");
                if (f != null) {
                    for (int i = 0; i < f.length(); i++) {
                        if (!isJunk(f.optString(i, ""))) hasReal = true;
                    }
                }
                if (!isJunk(e.optString("l", ""))) hasReal = true;
                // 带正确标记的选项也算真答案
                JSONArray k = e.optJSONArray("k");
                if (k != null && k.length() > 0) hasReal = true;
                if (!hasReal) {
                    drop.add(qid);
                    continue;
                }
                boolean fixed = false;
                if (f != null) {
                    JSONArray nf = new JSONArray();
                    for (int i = 0; i < f.length(); i++) {
                        String s = f.optString(i, "");
                        if (isJunk(s)) {
                            fixed = true;
                        } else {
                            nf.put(s);
                        }
                    }
                    if (fixed) {
                        if (nf.length() == 0) {
                            e.remove("f");
                        } else {
                            e.put("f", nf);
                        }
                    }
                }
                if (isJunk(e.optString("l", "")) && e.has("l")) {
                    e.remove("l");
                    fixed = true;
                }
                if (fixed) cleaned++;
            }
            for (String qid : drop) {
                JSONObject e = sById.remove(qid);
                if (e != null) sSig2Id.remove(e.optString("sig", ""));
            }
            if (!drop.isEmpty() || cleaned > 0) {
                sDirty = true;
                XLModConfig.logAppend("[题库] 清理盲填垃圾: 删除 " + drop.size() + " 题、修正 "
                        + cleaned + " 题（旧版本把「" + XLModConfig.getBlindFillText() + "」当成过正确答案）");
                save(true);
            }
        } catch (Throwable t) {
            sLastError = "清理题库失败: " + t;
        }
    }

    private static JSONObject toJson() {
        JSONObject root = new JSONObject();
        JSONObject items = new JSONObject();
        try {
            root.put("v", 1);
            root.put("ts", System.currentTimeMillis());
            for (String k : sById.keySet()) {
                JSONObject e = sById.get(k);
                if (e != null) items.put(k, e);
            }
            root.put("items", items);
        } catch (Throwable ignored) {
        }
        return root;
    }

    /** 节流保存（默认 5 秒一次；force=true 立即写） */
    public static void save(boolean force) {
        try {
            if (!sDirty && !force) return;
            long now = System.currentTimeMillis();
            if (!force && now - sLastSaveAt < 5000L) return;
            sLastSaveAt = now;
            sDirty = false;
            writeFile(file(), toJson().toString());
        } catch (Throwable t) {
            sLastError = "保存题库失败: " + t;
        }
    }

    private static void writeFile(File f, String json) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            out.write(json.getBytes("UTF-8"));
            out.close();
            sLastWriteOkAt = System.currentTimeMillis();
        } catch (Throwable t) {
            sLastError = "写题库文件失败: " + t;
        }
    }

    // ================= 工具 =================

    private static int typeOf(M_ChallengeQuestion q) {
        if (q == null || q.qType == null) return 0;
        try {
            return Integer.parseInt(q.qType.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 内容指纹：题型 + 题干（去空白/转小写）——普通挑战与同学对战同一道题指纹相同 */
    public static String sigOf(int type, String content) {
        return XLModCrypto.md5Hex((type + "|" + norm(content)).getBytes());
    }

    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x3000) continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** 供面板/诊断使用：题库文件路径 */
    public static String filePathText() {
        return file().getAbsolutePath();
    }

    /** 最近一次写入成功时间（诊断用） */
    public static long lastWriteAt() {
        return sLastWriteOkAt;
    }
}
