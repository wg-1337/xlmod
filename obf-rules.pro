# ===================== XLMod 混淆 keep 清单 =====================
# 只保留"原 App smali / Manifest / 反射锚点"必须原样的符号；
# 其余（XLModHelper/XLModActivity/XLModConfig 内部成员、内部类、字符串调用点）全部
# 交给 R8 full-mode 重命名 + 内联 + 常量合并 + 死代码剔除。

-dontwarn **
-dontnote net.xuele.**
-dontnote androidx.**

# ---- Manifest：Activity 类名 / 构造函数 / onCreate 覆写 ----
-keep class net.xuele.xuelets.mod.XLModActivity {
    public <init>();
    protected void onCreate(android.os.Bundle);
}

# ---- classes.dex/classes4/classes6 smali 直接引用的符号（sget / invoke-static）----
-keep class net.xuele.xuelets.mod.XLModConfig {
    int sPrivacyMode;
    java.lang.String sPrivacyDeviceId;
    int sAutoEngineActive;
    int sIdentityLevel;
    boolean isTeacherMod();
    java.lang.String getFixPositionName();
    java.lang.String getFixPositionId();
    java.lang.String getFixDutyName();
    int getCompressKbps();
    int getCompressMode();
    int getCompressLevel();
    boolean isAutoChallenge();
    boolean isCloudKeepOriginal();
    boolean isRecallAlways();
}

-keep class net.xuele.xuelets.mod.XLModHelper {
    void autoSignIfNeeded(android.app.Activity);
    void autoClickPositive(android.view.View);
    java.lang.String withUid(java.lang.String, java.lang.String);
    void autoOnSubjectsList(android.app.Activity, net.xuele.xuelets.magicwork.v3.model.RE_GetSubCenterList);
    void autoOnSubjectsFail(android.app.Activity);
    void ensureInit(android.app.Activity);
    java.lang.String cleanDeviceInfo(java.lang.String);
    void addSettingEntry(android.app.Activity, android.view.View);
    int autoHandleQuotaExhausted();
    int autoHandleFetchFail();
    void clearAnswerFloat();
    void showAnswerFloat(android.app.Activity, net.xuele.xuelets.challenge.util.ChallengeParamHelper, int);
    net.xuele.android.ui.question.ChallengeUserAnswer buildAutoAnswer(net.xuele.xuelets.challenge.model.M_ChallengeQuestion, net.xuele.android.ui.question.ChallengeUserAnswer);
    net.xuele.android.ui.question.ChallengeUserAnswer applyApiAnswers(net.xuele.xuelets.challenge.model.M_ChallengeQuestion, net.xuele.android.ui.question.ChallengeUserAnswer);
    void autoOnRankResume(android.app.Activity);
    void claimBattleCloudAfterResult(android.app.Activity, java.lang.String, java.lang.String);
    java.io.File disguiseFileForCloud(java.io.File);
    java.lang.String disguisedMd5(java.io.File);
    java.lang.String uploadExtFor(java.lang.String);
    void logNeedCompress(java.lang.String);
    void prepareCloudKeepOriginal(java.util.List);
    void listenFillAnswer(java.lang.Object);
    java.util.List hwInjectClasses(java.util.List);
    java.util.List hwInjectedClassesOrNull();
    void hwEnsureSelectQuestions(java.lang.Object);
    void hwInjectAssignParamFromFragment(java.lang.Object);
    void hwInjectAssignParam(java.lang.Object);
    void hwPrefillUnitsCache(java.lang.String);
    void hwOnAssignActivityLaunch(android.app.Activity);
    void forceInjectIntoTopActivity();
    void captureStudentsForClasses(android.app.Activity, int);
    void mergeBooksKeepUnits(org.json.JSONObject, org.json.JSONObject);
    void captureClassesViaWorkFilter();
}
