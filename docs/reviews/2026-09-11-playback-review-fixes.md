# 播放检视问题修复记录

日期：2026-09-11。原始问题见[增量检视](2026-09-11-playback-increment-review.md)。修复基线为 `3c0bfa0`，被检视实现为 `bb8bd0d`；本记录与修复代码一同提交。

## 处理结果

| 编号 | 修复行为 | 验证与边界 |
| --- | --- | --- |
| R01 | 新会话、手动尝试与恢复 prepare 清除旧错误；看门狗依据协调器终止/挂起状态判断；显式错误重试不被同线防抖拦截 | 静态复核 Activity 重试入口；正式回归覆盖超时后新尝试、旧回调拒绝及首帧超时策略；实际按钮点击路径待设备验收 |
| R02 | 初播经过一个完整尝试时长后优先给未试备用线机会；播放中恢复在剩余时间不足以继续同线重试并等待备用线时转向备用线 | 可控时钟覆盖初播 A 超时后 B 出画、恢复 A 重试后 B 出画；总期限仍为初播 20 秒/恢复 12 秒 |
| R03 | Activity 恢复不再直接调用播放器；暂停通过 Fragment 同步挂起协调器，恢复依据 SUSPENDED 状态，不依赖物理 isPlaying；熄屏、PiP 和 ensurePlaying 入口使用同一恢复入口 | 回归覆盖挂起时网络事件不能复活、长时间后台后恢复预算；HOME、熄屏与 PiP 时序待设备验收 |
| R04 | 手动选线同步游标，重置本次尝试集合；频道目录刷新带来的新线路可加入候选 | 回归覆盖手动 A→B、稳定后 B 失败再次选择 A |
| R05 | 滚动跨线频次仅限制跨线；同线重试独立受次数与时间限制 | 回归覆盖两次跨线后仍可同线重试，但不能继续跨线 |
| R06 | WaitForNetwork 不再暂停播放器；恢复网络时若仍在健康播放，则保留媒体项、尝试编号及缓冲，只更新网络代际 | 回归确认短断网后无 Prepare 动作、旧代际回调失效；真实缓冲连续性待媒体设备验证 |
| R07 | 显式尝试、前台恢复、网络重连建立新的有效等待周期；离线时间不吞掉重连预算；关闭自动换线也先遵守统一时间限制 | 回归覆盖离线一分钟、预算耗尽后手动选线、关闭自动换线时的初播与恢复期限 |
| R08 | API 24+ 使用默认网络回调；API 23 在回调和已有健康轮询中读取 activeNetwork 身份；不依赖 VALIDATED，不额外发探测请求 | 静态复核注册版本分支与旧网络 onLost 过滤；真实 Wi-Fi/以太网/VPN 切换待设备验证 |
| R09 | 无视频首帧期限与 READY/isPlaying 分离；明确无视频轨且音频轨已选中并播放的纯音频源可确认起播 | 首帧策略单测覆盖未出画超时、总预算提前到期、暂停/离线排除；真实音频/解码异常样本待验证 |
| R10 | 每次 prepare 写入唯一 MediaItem ID，并沿用到自定义 MediaSource；首帧改用 AnalyticsListener，检查事件时间线媒体身份、渲染时间及会话/尝试/网络令牌 | 单测覆盖快速 A/B/A 的旧媒体 ID 和早于新请求的渲染事件；真实 Media3 事件队列与解码器时序待验证 |

R09/R10 是对原静态风险的主动防护，不声称此前已在设备上复现。R01–R08 的代码修复完成同样不等于电视设备和三网验收完成。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PlaybackCoordinatorTest" --tests "*PlaybackEvidenceTest" --tests "*ChannelClassifierTest" --tests "*ChannelMetadataParserTest" --tests "*SourceNetworkPolicyTest" --tests "*SourceQualityTest" :app:assembleDebug --offline --console=plain
```

指定测试集合共 **64 项**：PlaybackCoordinatorTest 23、PlaybackEvidenceTest 3、ChannelClassifierTest 19、ChannelMetadataParserTest 13、SourceNetworkPolicyTest 3、SourceQualityTest 3。全部通过，失败、错误、跳过均为 0；Debug 构建通过。本轮新增 12 项回归。原有两个 Research 测试仍未纳入此集合，原因沿用原检视记录。

`git diff --check` 通过。构建仍有既有 SDK XML/弃用 API 等警告，没有将这些警告扩大为本次修复范围。

设备验证尝试：启动本机 Android TV API 36 模拟器 `tv_api36`（无窗口），ADB 持续返回 `unauthorized`；尝试重新连接、冷启动和重启 ADB 后仍无法获得设备 shell。没有清空模拟器数据，也没有成功安装本次 APK、执行遥控器操作或采集播放截图。此结果是环境阻塞，不能记录为设备验收通过。

## 保留的验收工作

后续设备验证已解除 ADB 授权阻塞，结果见 [Android TV 模拟器测试报告](2026-09-11-android-tv-emulator-qa.md)。部分场景获得运行证据，但发现稳定源冷启动失败后无错误入口的问题，整体验收仍未通过；下列事项以该报告中的覆盖边界为准。

1. 在已授权电视设备上验证错误面板重试、长时间缓冲、HOME 往返、熄屏音频开/关和 PiP。
2. 用音频正常但无视频帧、纯音频、延迟旧首帧和快速 A/B/A 切换样本验证首帧归因及兼容性。
3. 在有缓冲时注入短断网，再验证缓冲耗尽后的长断网；切换 Wi-Fi/以太网/VPN 默认路径，包含 API 23 设备。
4. 分别在大陆电信、联通、移动网络上测量首帧、卡顿与恢复成功率。本轮没有新的三网线路测量，不能据此宣称直播源质量已达标。

首帧事件的接口依据为 [Media3 1.5.1 AnalyticsListener](https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/analytics/AnalyticsListener.java)：使用事件时间线与 `renderTimeMs`，避免只凭当前 Player.Listener 闭包认定事件归属。
