# 注入点说明（低粒度）

> 因版权原因，**修改宿主 App 的那部分（smali 注入骨架）不随仓库分发**，因此这里不列类名、方法名与补丁内容。
> 本页只说明：开源部分**对外暴露了哪些稳定接口**，以及注入代码必须遵守的约束。

## 1. 开源部分对外暴露的接口（注入骨架会调用这些）

| 功能 | 公开接口（`net.xuele.xuelets.mod`） |
|---|---|
| 初始化 | `XLModHelper.ensureInit(Activity)`、`XLModHelper.installLifecycle(Activity)`、`XLModHelper.installCrashLog()` |
| 云原片 | `XLModConfig.isCloudKeepOriginal()`、`XLModHelper.disguiseFileForCloud(File)`、`XLModHelper.disguisedMd5(File)`、`XLModHelper.uploadExtFor(String)`、`XLModHelper.prepareCloudKeepOriginal(List)` |
| 布置作业 | `XLModHelper.hwOnAssignActivityLaunch(Activity)`、`XLModHelper.hwInjectedClassesOrNull()`、`XLModHelper.hwInjectClasses(List)`、`XLModHelper.hwInjectAssignParamFromFragment(Object)`、`XLModHelper.hwEnsureSelectQuestions(Object)` |
| 身份 | `XLModConfig.isTeacherMod()`、`XLModConfig.sIdentityLevel`、`XLModConfig.getFix*()` |
| 答题/打榜 | `XLModHelper.buildAutoAnswer(...)`、`applyApiAnswers(...)`、`showAnswerFloat(...)`、`autoOnRankResume(...)`、`claimBattleCloudAfterResult(...)` |
| 入口 | `XLModHelper.addSettingEntry(Activity, View)`、`XLModHelper.cleanDeviceInfo(String)` |

## 2. 注入代码必须遵守的约束

1. 调用的方法名/签名必须出现在 `obf-rules.pro` 的 keep 清单里（否则 R8 改名 → `NoSuchMethodError` → 功能静默失效）；
2. 所有 Mod 方法内部自行 try/catch，异常只写日志，**不得影响宿主原流程**；
3. 注入点分布在**三条上传链路**上时（分块 / 整文件 / 业务层）必须全部覆盖，否则会出现"部分场景失效"；
4. 注入骨架与 `classes7/8/9.dex` 必须一起分发（互锁机制：删任一 dex → 字符串密钥错误 → 整体失效）。

## 3. 闭源部分的边界

* 我们**不提供**宿主 App 的原始代码、资源、反编译产物与补丁文本；
* 本仓库代码可单独编译、阅读与提交 PR，只需保持 §1 的接口签名不变。
