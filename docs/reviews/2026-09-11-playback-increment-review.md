# 播放演进增量代码检视

归档日期：2026-09-11。状态：**8 项问题待修复，2 项风险待验证**。

本次先提交既有本地实现，再归档检视意见；实现提交不代表以下问题已修复。本档记录本轮检视结论，不替代原有全仓审计，也不构成发布验收。

## 1. 基线与范围

- 设计基线：`0f7db347d4f0f2f7ab930104dd6679a01c140395`，见[架构演进设计](../architecture/2026-09-08/README.md)。
- 被检视实现：`bb8bd0dfab5dcfaf364140b650d38cf2625e98d3`（`feat: introduce playback session coordination and recovery`）。
- 增量：10 个文件，1,173 行新增、258 行删除。下文行号均对应此实现提交。
- 检视覆盖全部增量及相关调用链：`ListAdapter.kt`、`MainActivity.kt`、`MainViewModel.kt`、`PlayerFragment.kt`、`ProgramFragment.kt`、`SP.kt`、`SourceSelectFragment.kt`、`playback/core/PlaybackContract.kt`、`playback/core/PlaybackCoordinator.kt`、`PlaybackCoordinatorTest.kt`。
- 主要方法：静态调用链分析、现有单元测试与 Debug 构建、对已编译协调器运行可控时钟探针。未进行电视实机或三网环境验证。

当前增量建立了播放会话、尝试编号、网络代际和恢复预算，并接入首帧观测、定时器清理、线路索引映射与当前播放引用。这些方向值得保留，但 Activity、Fragment 和协调器仍共同控制播放，预算和状态边界尚未闭合。按原设计属于 M1 的部分落地；后续阶段未实现的内容不在这里重复列为新增缺陷。

## 2. 问题总览

| 编号 | 优先级 | 问题 | 证据 | 状态 |
| --- | --- | --- | --- | --- |
| R01 | P1 | 错误后的重试可能永久跳过看门狗 | 静态调用链 | 待修复 |
| R02 | P1 | 同线重试耗尽总预算，可用备用线得不到机会 | JVM 探针 | 待修复 |
| R03 | P1 | Activity 绕过协调器，前后台状态可能失配 | 静态调用链与框架时序 | 待修复 |
| R04 | P1 | 手动换线未同步游标，恢复时漏掉可用线路 | JVM 探针 | 待修复 |
| R05 | P1 | 跨线频次上限同时阻断同线重试 | JVM 探针 | 待修复 |
| R06 | P1 | 短时断网立即暂停已有缓冲 | 静态调用链 | 待修复 |
| R07 | P2 | 重连、手动操作和关闭自动换线时预算生命周期错误 | JVM 探针 | 待修复 |
| R08 | P2 | 网络监测只识别有无网络，遗漏默认路由更换 | 静态调用链与 API 契约 | 待修复 |

P1 优先处理可能造成长时间黑屏、恢复失效或直接打断观看的问题；P2 为仍需修复的特定路径问题。静态确认不等于实机复现，证据边界分别列明。

## 3. 逐项意见

### R01：错误后的重试可能永久跳过看门狗

**位置：** [PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 1032、1354、342 行；[MainActivity.kt](../../app/src/main/java/com/horsenma/yourtv/MainActivity.kt) 第 1226 行。

**触发与原因：** 出错后点击重试，Activity 隐藏错误界面并直接调用 `playerFragment.play(tvModel)`，但新播放尝试没有清除旧 `errInfo`。看门狗发现错误文本非空就继续调度并返回；清除错误文本又依赖成功首帧。若重试后一直 BUFFERING 且不抛异常，就无法通过超时退出。

**建议：** 新尝试开始时清理旧错误展示状态；看门狗是否生效由当前会话状态与尝试身份决定，避免依赖 UI 文案。

**验收：** 制造首次失败，点击重试后令数据源持续等待且不返回首帧、不抛异常；必须在预算内进入恢复或终止状态，不能无限缓冲。补充覆盖真实重试入口的集成测试。

### R02：同线重试耗尽总预算，备用线得不到机会

**位置：** [PlaybackCoordinator.kt](../../app/src/main/java/com/horsenma/yourtv/playback/core/PlaybackCoordinator.kt) 第 196–201 行；[PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 467、1092 行。

**触发与原因：** 初播总预算 20 秒、单次首帧等待 8 秒，但策略优先执行两次完整同线重试。A 不可用、B/C 可用时，在第 8 秒和第 16 秒仍重试 A，第 20 秒耗尽预算，B/C 从未尝试。播放中恢复的 12 秒预算存在相同冲突。

**建议：** 区分短暂分片请求重试与完整重新 prepare；给尚未尝试的备用线预留时间。根据剩余预算决定重试或跨线，避免次数配置使备用策略不可达。

**验收：** A 持续超时、B 可播时，初播与播放中恢复都应在总预算内尝试 B；同时验证无可用线路时仍按预算终止。JVM 探针已确认当前实现仅尝试 A。

### R03：Activity 绕过协调器，前后台状态可能失配

**位置：** [MainActivity.kt](../../app/src/main/java/com/horsenma/yourtv/MainActivity.kt) 第 1639、1656 行；[PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 1732、1758 行。

**触发与原因：** 关闭熄屏音频时，Fragment 暂停会挂起协调器；Activity 恢复时却直接调用播放器 `play()`。Fragment 在稍后的恢复回调中仅当 `isPlaying == false` 才恢复协调器，因此播放器已开始播放时，协调器可能仍为 SUSPENDED 并拒绝后续回调。AndroidX FragmentActivity 在 `onPostResume` 才恢复 Fragment，支持该调用顺序分析。

默认开启熄屏音频的路径也需统一：Activity 的 `onStop` 暂停底层播放器却未同步挂起协调器，后台网络恢复事件可能重新启动播放。

**建议：** 生命周期与后台播放策略统一进入单一播放控制入口，由协调器决定并执行暂停、恢复和网络事件响应；Activity 不再直接改变底层播放器状态。

**验收：** 分别覆盖熄屏音频开/关、HOME 往返、锁屏、画中画与后台断网重连，断言物理播放状态、会话状态和首帧事件接收一致。上述是静态确认的状态失配路径，尚无设备复现记录。

### R04：手动换线未同步游标，恢复时漏掉可用线路

**位置：** [PlaybackCoordinator.kt](../../app/src/main/java/com/horsenma/yourtv/playback/core/PlaybackCoordinator.kt) 第 81、284 行。

**触发与原因：** `beginAttempt` 更新 `selectedLineId`，没有更新 `lineCursor`；自动选下一线时同时排除游标位置和已尝试线路。A/B 两线下手动选择 B，稳定播放后已尝试集合只有 B，但游标仍指 A。B 后续失败便无法回到 A。

**建议：** 用实际选中线路身份作为唯一依据，或在所有开始尝试的入口同步游标，消除两份状态分歧。

**验收：** A → 手动 B → 稳定 60 秒 → B 失败，重试耗尽后应选择 A。JVM 探针当前返回 `ShowError(UNKNOWN)`，未尝试 A。

### R05：跨线频次上限同时阻断同线重试

**位置：** [PlaybackCoordinator.kt](../../app/src/main/java/com/horsenma/yourtv/playback/core/PlaybackCoordinator.kt) 第 214 行。

**触发与原因：** `switchesInWindow < max` 同时包住同线重试与跨线逻辑。五分钟内发生两次跨线后，即使已经稳定播放 60 秒且同线重试计数归零，下一次短暂故障也直接报错。

**建议：** 跨线频次限制仅限制跨线动作；同线重试单独受次数、时间预算约束。

**验收：** 达到跨线频次上限后，稳定播放再遇到瞬时故障，仍允许预算内同线恢复；不得借此绕过跨线限制。JVM 探针已确认当前直接返回 `ShowError(TIMEOUT)`。

### R06：短时断网立即暂停已有缓冲

**位置：** [PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 425、380 行。

**触发与原因：** `WaitForNetwork` 立即调用 `player.pause()`，未考虑已经缓冲的视频；网络回来后又 stop、清空并重新 prepare。短时无线抖动会被放大成可见停播和重新首帧等待。

**建议：** 短时断网优先消费已有缓冲，缓冲耗尽后再呈现等待状态；同一媒体仍可续播时尽量保留播放器与缓冲。需要重建时再执行完整 prepare。

**验收：** 在已有缓冲时注入短断网，验证画面连续性、重连后的续播与 prepare 次数；长断网应稳定进入等待状态，恢复后可继续播放。当前结论来自静态调用链，未测试真实缓冲与网络。

### R07：恢复预算生命周期错误

**位置：** [PlaybackCoordinator.kt](../../app/src/main/java/com/horsenma/yourtv/playback/core/PlaybackCoordinator.kt) 第 81、185、258 行。

**触发与原因：** 网络重连复用原 `startedAt` / `recoveryStartedAt`，离线等待计入恢复期限；手动发起新尝试也沿用已过期的预算。另一方面，关闭自动换线的分支在检查总时间前直接允许同线重试，反而能越过期限。

**建议：** 明确定义一次恢复周期及重置入口；离线等待暂停或排除计时，显式用户重试和选线启动符合产品语义的新周期；所有策略分支先遵守统一总预算。

**验收：** 覆盖离线 60 秒后重连、预算耗尽后手动选择另一线、关闭自动换线后超过预算三个场景。当前探针分别得到“首次错误即终止”“新选线路立即终止”“超过 20 秒仍返回重试”。

### R08：网络监测遗漏默认路由更换

**位置：** [PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 266、301 行。

**触发与原因：** 注册 INTERNET 条件的 `registerNetworkCallback` 观察所有符合条件的网络，并将状态压缩为有/无网络；布尔值不变即返回。Wi-Fi、以太网、VPN 默认路径变化时，只要始终有一个网络存在，就可能不更新网络代际。

**建议：** API 24 及以上观察默认网络；API 23 提供兼容路径，结合实际活动网络身份判断变更。区分“存在网络”“默认路径变化”与“媒体端点可达”，不以系统 VALIDATED 作为大陆直播源可播的硬门槛。

**验收：** 覆盖默认路由切换、多网络同时在线、无外网验证但直播端点可达的局域网；正确更新代际并过滤旧回调，同时避免无关网络变化打断观看。尚未进行实网验证。

## 4. 待验证风险

### R09：READY 或仅音频播放可能绕过视频首帧期限

[PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 1088 行的首帧看门狗依赖 `!isPlaying && BUFFERING`。若播放器 READY 且音频在播，但视频首帧始终未到，可能一直黑屏。静态上存在缺失分支，尚未用媒体样本复现。

建议将视频首帧期限与逻辑 `isPlaying` 分离，并明确纯音频源的处理规则。以“仅音频”“音频正常但视频解码失败”“READY 后迟迟无视频帧”样本验证后确定修复与优先级。

### R10：重新绑定监听器不一定隔离旧媒体的首帧回调

[PlayerFragment.kt](../../app/src/main/java/com/horsenma/yourtv/PlayerFragment.kt) 第 259 行附近使用新监听器令牌隔离尝试。但复用 ExoPlayer 与 Surface 时，旧 renderer 已入队的首帧通知可能经过内部 ComponentListener 分发给当前监听器。单靠新闭包的令牌不能证明事件来自新媒体。

Media3 1.5.1 源码显示首帧通知经 `handler.post`，ExoPlayer 的组件监听器依据输出对象匹配后再通知当前监听器集合。这支持时序风险分析，不能据此声称已复现串帧归因。

建议结合媒体身份、事件时间/渲染时间及尝试边界验证回调来源。用快速 A/B/A 切台、同 Surface 复用、延迟旧事件的测试确认，要求旧帧不能让新尝试进入已首帧状态或记录新线路成功。

## 5. 验证证据与限制

本轮检视时执行以下命令成功，耗时约 1 分 16 秒，46 个任务执行；提交前再次核对了已有测试报告。归档提交只新增文档与入口，没有重新执行构建。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PlaybackCoordinatorTest" --tests "*ChannelClassifierTest" --tests "*ChannelMetadataParserTest" --tests "*SourceNetworkPolicyTest" --tests "*SourceQualityTest" :app:assembleDebug --offline --rerun-tasks --console=plain
```

| 测试类 | 用例数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| PlaybackCoordinatorTest | 14 | 0 / 0 / 0 |
| ChannelClassifierTest | 19 | 0 / 0 / 0 |
| ChannelMetadataParserTest | 13 | 0 / 0 / 0 |
| SourceNetworkPolicyTest | 3 | 0 / 0 / 0 |
| SourceQualityTest | 3 | 0 / 0 / 0 |
| 合计 | 52 | 0 / 0 / 0 |

以上是指定测试集合，不是全量测试或 Android 集成验收。两项原有 Research 测试因包含硬编码路径且缺少断言，未纳入本轮验证。已有 SDK XML、Manifest 和弃用 API 警告未阻断构建；`git diff --check` 通过。

额外 JVM 探针直接调用已编译的协调器，注入可控时钟；预算参数为初播 20 秒、最多 3 条候选线、播放中恢复 12 秒、同线重试 2 次、5 分钟最多跨线 2 次。下表记录观测结果，不代表已加入正式回归测试。

| 场景 | 实际结果 |
| --- | --- |
| 初播 A 超时，B/C 尚未尝试 | 8 秒重试 A，16 秒重试 A，20 秒报 TIMEOUT；只尝试 A |
| 已首帧，1 秒时开始故障恢复 | 1 秒和 9 秒同线重试，13 秒报 TIMEOUT；未尝试 B |
| 关闭自动换线，经过 20,001 毫秒 | 仍返回 RetrySameLine |
| 手动选 B，稳定 60 秒后反复报错 | 返回 ShowError(UNKNOWN)，未回到 A |
| 已跨线两次，稳定后再次报错 | 直接 ShowError(TIMEOUT)，没有同线重试 |
| 离线等待一分钟后重连并报错 | 直接 ShowError(TIMEOUT) |
| 预算耗尽后手动开始 B，再报错 | 直接 ShowError(TIMEOUT)，沿用旧期限 |

`adb devices -l` 未发现连接设备。因此没有验证电视遥控器焦点、HOME 往返、真实媒体首帧、解码器兼容性或实际网络切换，也没有电信/联通/移动线路质量测量。不能由 JVM 测试与构建通过推断三网观看质量达标。

## 6. 修复顺序与后续验收

1. 先闭合生命周期、重试入口和总预算：R01、R02、R03、R07，避免无限等待或恢复入口失效。
2. 修正选线状态与频次限制：R04、R05，保证已有可用线路仍有机会参与恢复。
3. 完成断网缓冲与默认路由识别：R06、R08，并验证 R09/R10 的首帧归因边界。
4. 将上述可控时钟反例纳入正式回归，再覆盖 Fragment/Activity 的集成路径与电视实机。新增测试应验证可观察行为，避免只复述实现。
5. 三网验收分别记录冷启动首帧、换台首帧、卡顿时长、恢复成功率和恢复期间请求数；包含高峰期、短断网、跨默认路由、不可达主线与可用备用线。优先减少无效重连、重复探测与缓冲丢弃；直播内容本身仍依赖可用网络，不能以降低网络依赖承诺离线观看。

## 7. 参考依据

- [ConnectivityManager 官方 API](https://developer.android.com/reference/android/net/ConnectivityManager)：普通网络回调与默认网络回调的语义和版本要求。
- [AndroidX FragmentActivity 源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/fragment/fragment/src/main/java/androidx/fragment/app/FragmentActivity.java)：`onResume`、`onPostResume` 和 `onResumeFragments` 的顺序；链接为上游主分支，长期复核需结合项目实际依赖版本。
- [Player.Listener 官方 API](https://developer.android.com/reference/androidx/media3/common/Player.Listener)：首帧回调语义。
- [Media3 1.5.1 ExoPlayerImpl](https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java)与[VideoRendererEventListener](https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/VideoRendererEventListener.java)：首帧通知投递与分发路径。
