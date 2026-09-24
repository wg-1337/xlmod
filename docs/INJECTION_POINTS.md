# 注入点清单（不含原版代码）

> 说明：因版权原因，**修改宿主 App 的 smali 补丁不随仓库分发**；这里只列出"在哪个类的哪个方法、什么时机、做什么"，
> 供开源部分（`mod-src/**`）对齐与复现。所有被调用的方法名都必须在 `obf-rules.pro` 的 keep 清单里。

## A. 云端保持原片（上传链路）

| 类 | 方法 | 注入时机 | 做什么 |
|---|---|---|---|
| `net.xuele.android.common.upload.task.SingleFileTask` | `compressVideo()` | 方法入口 | `getCompressMode()==1` 或 `isCloudKeepOriginal()` → 跳过本地压缩，直接 `prepareSplitFile(原文件, 原md5)` |
| 同上 | `prepareSplitFile(File, String)`（分块上传 `blockUploadInit` 前） | 参数就绪后 | 云原片 + 视频 → 文件换成 `disguiseFileForCloud(file)`、md5 换成 `disguisedMd5(file)` |
| 同上 | `uploadFile(...)`（整文件上传 `upload` 前） | 同上 | 同上 |
| 同上 | 两处扩展名变量赋值 | 调用接口前 | `ext = uploadExtFor(name)`（云原片开启 → `"bin"`） |
| `net.xuele.android.common.compress.info.VideoFormatHelper` | `getResolutionBitRate(int)` | 方法入口 | 云原片开启 → 返回 100 Mbps（使 `needCompress` 恒 false，双保险） |
| `net.xuele.android.common.compress.info.VideoUtils` | `needCompress(String)` | 方法入口 | 仅调用 `XLModHelper.logNeedCompress(path)` 打日志（不改判定） |
| `net.xuele.android.common.upload.FileUploadManager` | 上传前处理资源列表处 | 列表就绪后 | `prepareCloudKeepOriginal(list)`：业务层把 path/size/md5/ext 一并改成伪装值 |

## B. 布置作业修复

| 类 | 方法 | 注入时机 | 做什么 |
|---|---|---|---|
| `net.xuele.xuelets.homework.activity.AssignHomeworkActivity` | `onCreate(Bundle)` | 末尾 | `hwOnAssignActivityLaunch(this)`：状态日志 + 预拉题 + 课时缓存 |
| 同上 | `getClasses()` | 入口 | `hwInjectedClassesOrNull()` 非空 → 写入 `mClasses` + `loadSuccess()` 并 return（跳过服务端请求） |
| `AssignHomeworkActivity$5`（`getClassAndStudent` 回调） | `onReqSuccess` | 方法入口 | `hwInjectClasses(classList)` 合并抓取到的班级 |
| 同上 | `onReqFailed` | 方法入口 | 失败时用本地班级兜底注入 |
| `net.xuele.xuelets.homework.fragment.AssignHomeworkFragment` | `updateViews(AssignHomeworkActivity)` | 入口 | `hwInjectAssignParamFromFragment(this)`：补课时/课本参数 + 预填课时缓存 |
| `net.xuele.xuelets.homework.helper.AssignWorkHelper` | `getQuestions(AssignWorkParam)` | 入口 | `hwEnsureSelectQuestions(param)`：补课时 + 未选题时按课时拉题注入 |

## C. 身份伪装 / 自动答题 / 其它

| 功能 | 注入位置（类 → 方法） | 调用 |
|---|---|---|
| 教师身份 | `LoginManager`、`M_User`、`TeachAuthUtil`、`UserLimitManager` 等身份判定方法 | `isTeacherMod()` / `sIdentityLevel` / `getFix*()` |
| 听力自动填答 | `ChallengeListenQuestionFragment.checkUserAnswered` | `listenFillAnswer(...)` |
| 答题自动化 | `ChallengeQuestionBaseActivity`（取题/提交/换题） | `buildAutoAnswer` / `applyApiAnswers` / `autoHandleFetchFail` / `showAnswerFloat` |
| 自动打榜 | `ChallengeRankActivity` / `CompetitionListActivity$1` / `ChallengeResultV2Activity` | `autoOnRankResume` / `autoOnSubjectsList` / `claimBattleCloudAfterResult` |
| 榜单昵称 | `LearnScoreListAdapter`、`RankListAdapter`、`ChallengeStudentRankAdapter$ViewHolder` | `withUid(...)` |
| 设置入口 | `SettingActivity` | `addSettingEntry(activity, view)` |
| 插件初始化 | `LoginActivity` | `ensureInit(activity)` / `cleanDeviceInfo(...)` / `autoSignIfNeeded(...)` |

## D. 约束

1. 注入点调用的方法名/签名 **必须** 出现在 `obf-rules.pro` 的 keep 清单里；
2. 注入代码要"失败不影响原流程"：所有 Mod 方法内部 try/catch，异常只写日志；
3. 新增注入点后，同步更新本文件与 `OPEN_SOURCE_SCOPE.md` §3 的接口清单。
