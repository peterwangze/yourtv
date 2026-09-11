# Android TV 播放修复设备测试

日期：2026-09-11。被测代码：`316f5a0`，版本 3.4.0，包名 `com.horsenma.yourtv`。本轮使用 Test Android Apps 的 android-emulator-qa 工作流，通过 ADB 安装、遥控器按键、UI XML、截图和日志验证。本提交只归档测试结论，没有修改应用实现。

## 结论

**整体验收未通过。** 普通选台、坏线恢复、错误后再次尝试、HOME 往返和熄屏恢复获得了设备证据；冷启动恢复历史稳定线路后，该线路失败时仍可能黑屏，缺少错误和重试入口，属于观看主路径的 P1 问题。另发现手动导入的局域网地址被公共源过滤策略删除。

64 项指定单元测试结果均为通过，Debug 构建通过。设备测试不等于大陆电信、联通、移动三网验收，尚不能认定源质量和全部恢复时序达标。

## 环境与模拟器重建

- Windows 主机；Android Emulator 37.1.11；Android TV API 36、x86_64、系统镜像 revision 4；1920×1080；ADB `emulator-5554`。
- 原 `tv_api36` 持续 unauthorized。用户授权原目录重装后，保留旧 AVD，在 **`D:\Android\avd\tv_api36.avd`** 重建新 AVD，注册文件仍为 `D:\Android\avd\tv_api36.ini`。复用已安装的系统镜像，没有卸载整个 SDK。
- 原 AVD 及 ini 备份：`D:\Android\tmp\tv-qa-20260911\old-avd`。新设备成功进入 `device` 状态并安装 APK。
- APK：`app/build/outputs/apk/debug/juyuan_tv_v3.4.0.apk`，22,112,419 字节，SHA-256：`ED4439B7BCE4076769E4DDEC46A0010CF91652128C529787DDA6FF8D29C1BAF6`。
- 无窗口、无宿主音频输出运行。默认网络为 Ethernet/eth0。没有 root，系统镜像不支持本次尝试的 Ethernet shell 控制；新版 emulator console 也没有 QEMU monitor。
- 日志、截图的设备时间为 UTC；主机为 UTC+8。测试分为 06:45–07:01 和 12:53–13:04 两段设备时间。
- 收尾已清除**新建模拟器中本轮创建的应用测试数据**（包含临时源和应用代理）、移除 ADB 端口转发、停止本地服务及模拟器；保留 APK 安装、原目录新 AVD、旧 AVD 备份和测试证据。

## 样本和方法

先验证应用初装自带的 CCTV1，再导入可控 HLS 样本：A 为带时间码的视频，B 为蓝色视频，两者均为 H.264/AAC；Audio 为 AAC-only；Retry 为单线延迟响应；Backup 为慢线和 B 两条线路。视频样本 640×360、25fps、60 秒 VOD、约 2 秒一个分片。本次不把这些 VOD 样本等同于持续直播或长稳压测。

本地服务绑定 `127.0.0.1:18765`，应用代理指向 `http://10.0.2.2:18765`。源地址使用 `qa.test`，由这个服务处理 HTTP 代理的绝对 URL，不依赖公共 DNS。此安排用于绕过下述局域网地址过滤问题，不是应用代码修复。应用配置服务经 `adb forward tcp:18766 tcp:34567` 暴露，向 `/api/import-text` POST M3U 文本。

归档日志仅规范化换行与行尾空白；原始采集文件保留在本地证据目录。

[频道表](evidence/2026-09-11-emulator/channels.m3u)、[服务脚本](evidence/2026-09-11-emulator/server.py)、[ADB 辅助脚本](evidence/2026-09-11-emulator/qa.py) 已归档。完整本地样本和原始证据留在 `D:\Android\tmp\tv-qa-20260911`。辅助脚本中的 ADB 路径和序列号针对本机，跨机运行需调整；其 select 动作按 XML 节点中心注入点击，某些按钮需要再按 OK 激活。方向键、HOME、SLEEP/WAKEUP、错误页重试均实际通过 ADB 按键执行。

服务控制接口：`/control?retry=slow` 让 Retry 等待 35 秒后返回 504，`retry=good` 则重定向至 A；`/slow.m3u8` 始终延迟；`/control?outage=true` 暂停后续媒体请求处理，`outage=false` 恢复。重新启动服务时状态重置为 retry=slow、outage=false。脚本所在目录需有 `a/`、`b/`、`audio/` 样本。

## 结果与证据

| 场景 | 结果 | 观察及边界 |
| --- | --- | --- |
| 指定单测与构建 | 通过 | 6 个测试类共 64 项，失败/错误均 0；本次再次运行 Gradle 为 UP-TO-DATE，复用前次实际执行结果，不声称重新执行了全部用例。命令见前份修复记录 |
| 初装真实源 CCTV1 | 通过本次冒烟 | 06:45:36.547 起播请求，37.740 首帧，约 1.19 秒；观察 20 秒以上。仅当前主机网络的一次观察；原始 `initial.png/xml` 留在本地证据目录 |
| 本地 B 视频 | 通过 | 请求至首帧约 0.69 秒，截图符合蓝色视频；原始 `local-b-proxy.*` 留在本地 |
| 手动切入慢线，再恢复备用线 | 通过本场景 | QA Backup 右键切入 slow：06:57:21.893，29.903 首帧超时，30.248 备用线首帧，约 8.35 秒恢复。[日志](evidence/2026-09-11-emulator/manual-line-recovery.log) |
| 单线失败显示错误页 | 通过 | 普通选中 QA Retry，在约 8、16、20 秒推进恢复并终止，显示“播放错误/重试” |
| 错误页再次尝试，线路仍失败 | 通过本次有效触发 | 12:59:15.026 出现 retryCurrentPlayback，12:59:35.097 再次报错，约 20.07 秒；[日志](evidence/2026-09-11-emulator/repeat-timeout-confirmed.log)、[UI XML](evidence/2026-09-11-emulator/repeat-timeout-confirmed.xml)。此前有按键未实际触发的样本，不计为一次成功重试 |
| 错误后源恢复，再按重试 | 通过恢复结果 | 06:59:16.655 重试，18.263 首帧；中途有一次 Source error 后恢复，不能描述为全程无错误。[日志](evidence/2026-09-11-emulator/retry-success.log) |
| HOME 往返，熄屏音频关闭 | 通过本场景 | 06:51:03.635 暂停；返回后重新准备，06:51:06.710 首帧。[日志](evidence/2026-09-11-emulator/home-return-false.log) |
| SLEEP/WAKEUP，熄屏音频关闭 | 通过恢复结果 | dumpsys power 确认 Asleep；13:00:40 暂停，42 秒恢复，43.271 首帧。期间一次 Source error，随后恢复。[日志](evidence/2026-09-11-emulator/sleep-wake-false.log) |
| 纯音频，无视频轨 | 通过起播判定，有限覆盖 | isPlaying=true、首帧判定路径接受音频，观察超过 12 秒，没有无视频首帧超时；途中一次源错误后恢复。日志沿用“rendered first frame”，不代表真的生成视频帧；未听测音质。[日志](evidence/2026-09-11-emulator/audio-only.log) |
| 连续频道键输入 | 通过有限冒烟 | 连续 DOWN、DOWN、UP、UP 后最终 QA B 出图，无应用崩溃；日志有防抖，没有形成可证明的完整 A/B/A 媒体事件序列，不据此关闭旧首帧归因风险。[日志](evidence/2026-09-11-emulator/rapid-switch.log)、[截图](evidence/2026-09-11-emulator/rapid-switch.png) |
| 服务暂停 3 秒 | 不计入断网覆盖 | 播放始终 isPlaying=true，没有重新准备；但暂停窗口没有记录到受阻媒体 GET，不能证明测试实际施加了传输中断，更不能替代系统默认网络切换。[观察日志](evidence/2026-09-11-emulator/source-pause-3s.log) |
| 冷启动恢复的稳定线变坏 | **失败，P1** | 协调器已终止但没有错误页，详见 QA-01 |
| 手动导入局域网源 | **失败，P2** | 导入成功、频道可见，但线路列表被过滤为空，详见 QA-02 |

测试期未观察到应用进程崩溃。启动早期有一次 `uiautomator` 自身在系统服务尚未就绪时崩溃（PID 624），不能误算为应用崩溃。部分本地源操作出现 `Source error`；熄屏恢复时完整异常链为 `HttpDataSourceException / ProtocolException: unexpected end of stream`，现有证据尚不能区分样本服务和应用数据读取问题，不将这些过程描述为无卡顿或无中间错误。

## QA-01：稳定源冷启动失败后无错误入口（P1）

复现条件：先让 QA Retry 正常播放并保存为稳定来源；停止应用/设备，将服务切回 retry=slow；重新启动应用，等待超过 20 秒。

实际记录：12:53:26.941 播放恢复出来的 QA Retry；12:53:27.716 主界面当前频道却为 QA A，随后走“Stable source already playing”分支。12:53:34.998、43.043、47.082 依次触发 8 秒、8 秒、剩余 4 秒的超时。12:53:59 截图只剩黑屏和时钟，UI XML 没有错误或重试控件。后续再次观察仍没有自动恢复。

预期：在终止时显示可操作的失败状态和重试入口，并保证当前频道、播放对象和错误观察一致。

代码定位及推断：`MainViewModel.kt:555` 从稳定记录新建独立 `TVModel`，随后 triggerPlay；`MainActivity.kt:601` 的 watch 只观察 listModel 内对象的 errInfo，且回调要求对象等于 playerFragment.tvModel；`MainActivity.kt:300` 一带的稳定源分支可能提前返回，跳过后续列表对象对齐。日志中的频道身份分裂与这条路径一致，推断实际播放对象的终止错误没有被界面订阅。这是当前版本缺陷，尚未通过旧 APK 对照实验确定由哪个历史提交引入。

建议修复：为实际播放会话建立独立、稳定的界面状态订阅；快速恢复历史源后必须统一频道身份，并在列表重建时保持会话和 UI 状态的一致性。回归需覆盖“历史稳定频道不是列表首项，冷启动立即失效”，断言终止错误可见且按 OK 能建立新尝试。

证据：[启动日志](evidence/2026-09-11-emulator/resumed-test.log)、[超时日志](evidence/2026-09-11-emulator/before-ok-retry.log)、[UI XML](evidence/2026-09-11-emulator/before-ok-retry.xml)。

![超过恢复预算后仅显示黑屏和时钟](evidence/2026-09-11-emulator/before-ok-retry.png)

## QA-02：公共源策略误用于手动局域网源（P2）

将样本 URL 改为 `http://10.0.2.2:18765/...` 后手动导入：API 成功、频道出现，选台时 uris 为空，服务未收到媒体请求。使用 `qa.test` 经本地代理访问同一媒体后可以播放。原失败阶段未保留完整独立日志，证据强度低于 QA-01，应在修复前再补一份独立设备复现。

静态确认：`SourceNetworkPolicy.isUsablePublicCandidate` 拒绝 PRIVATE；`diversify` 无条件应用该过滤；`MainViewModel` 排序调用链将其用于导入数据。该逻辑在修复前的 `3c0bfa0` 已存在，不是本次播放修复新增。此策略会影响局域网网关、家庭 IPTV 转发等低外网依赖场景。

建议区分自动聚合的公共候选与用户显式配置的本地/运营商内网来源。公共发现可保持过滤，显式导入应保留合法私网地址，并针对来源类别实施排序和诊断。

## 仍需验收

1. QA-01 修复后的冷启动失败 UI、遥控器重试，以及 QA-02 独立复现和修复验证。
2. 真正的短断网、长断网、缓冲耗尽及默认网络身份变化；包括 API 23 和 Wi-Fi/以太网/VPN 切换。
3. 音频正常但视频无法出帧的解码样本、精确 A/B/A 旧首帧事件、长时间播放和低端实体电视。
4. 熄屏音频开启及 PiP。TV 上设置开关因 `isTouchScreen=false` 隐藏，PiP 也被应用跳过；本次没有用修改偏好值的方法冒充用户 UI 验收。
5. 大陆三家运营商分别测首帧分布、每小时卡顿时长、恢复成功率及源有效率；本机一次真实源起播不提供三网可用性结论。
