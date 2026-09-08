# 本次审计与文档验证记录

代码基线：`1b47d7446074b9bf26931e8ed0945c6a6630723d`，分支 `main`，版本 v3.4.0。审计开始于 2026-09-08，文档完成于 2026-09-09。工作区为 Windows/PowerShell。本次修改范围为设计归档、基线统计脚本/输出及根 README 导航，未修改应用运行代码。

## 1. 代码与数据盘点

`git ls-files` 与固定提交 Git 对象盘点得到 287 个文件；按功能域分类并扫描网络、生命周期、焦点、可变状态和媒体事件信号。主播放、目录、网络、持久化、UI 输入、EPG、Web 适配、远程服务和构建/工具路径深入阅读；普通辅助文件结合调用关系核对。二进制不进行源代码级内部证明。

复算命令（仓库根目录）：

```powershell
python -X utf8 docs/architecture/2026-09-08/audit_snapshot.py
```

脚本从固定 Git 基线读取，无网络请求，仅更新本归档的 `inventory.csv` 与 `snapshot.json`，不会把新设计文档算进原始代码规模，也不会读未跟踪的个人配置。清单含每文件 SHA-256、文本行数、分类和扫描信号；信号不是漏洞结论。内置快照结果：521 频道、1003 线路槽位、976 唯一 URL；363 单线路频道、431 单 host 频道。全部属于静态结构统计。

## 2. JVM 单测实际重跑

先执行的离线测试全部 UP-TO-DATE，不能作为本次重新执行测试的证据；随后强制重跑：

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --rerun-tasks --console=plain
```

结果：退出码 0，`BUILD SUCCESSFUL`，约 57 秒，26 个任务实际执行。读取 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 得到：

| 类 | tests | failures | errors | skipped | 说明 |
|---|---:|---:|---:|---:|---|
| ChannelClassifierTest | 19 | 0 | 0 | 0 | 分类/名称规范化 |
| ChannelMetadataParserTest | 13 | 0 | 0 | 元数据 |
| SourceNetworkPolicyTest | 3 | 0 | 0 | 当前网络候选规则的有限样例 |
| SourceQualityTest | 3 | 0 | 0 | 当前质量评分的有限样例 |
| ResearchClassificationDump | 1 | 0 | 0 | 无断言的研究输出 |
| ResearchProbe | 1 | 0 | 0 | 无断言的研究输出 |
| 合计 | 40 | 0 | 0 | 0 | 38 个带断言测试 + 2 个研究方法 |

两个研究类依赖机器绝对路径并可能写研究 JSON；本次写出内容与基线相同，测试后 Git 受控文件无差异。通过不证明这些测试在其他路径可复现，亦不覆盖首帧、长播、焦点、EPG、迁移或恢复时序。

构建存在既有警告，包括 Manifest package 被忽略、重复 READ_EXTERNAL_STORAGE 声明、deprecated API 和部分 Kotlin 可空类型问题。首次检查还出现 SDK XML 工具版本告警。没有将这些警告当成本轮设计提交引入的失败。本次未执行 release 签名发布或安装 APK。

## 3. 依赖与 AAR

```powershell
.\gradlew.bat :app:dependencyInsight --dependency androidx.media3 --configuration debugRuntimeClasspath --offline --console=plain
```

退出码 0，Media3 实际解析到 **1.5.1**，其中 1.1.1 声明被选择的 1.5.1 替代。因此审计指出声明与维护负担，不声称已经发生 Media3 版本冲突崩溃。另有旧 ExoPlayer 2.19.1 声明，主代码 import 使用 Media3，需在后续依赖整理时独立验证移除。

本地 FFmpeg AAR 包目录包含 armeabi-v7a、arm64-v8a、x86、x86_64 JNI，以及音频 renderer/decoder 和 `ExperimentalFfmpegVideoRenderer` 类。检查只证明这些制品存在，不证明视频扩展已在当前 factory 正确启用、软件解码性能、二进制可复现来源或所有设备 ABI 安装结果。

## 4. 可重复的本地缺口复核

使用已编译的实际 Kotlin 类与 JDK 17 jshell 执行合成输入；没有发起媒体请求。此环境 JDK 为 Eclipse Adoptium 17.0.19，Gradle 用户缓存为 `D:\Android\gradle`。首次 classpath 指向错误的默认缓存导致缺 kotlin-stdlib，修正路径后执行成功；该设置失误不计为产品缺陷。

复核输入与结果：

```java
SourceNetworkPolicy.INSTANCE.carrier("https://fc-live.example/live.m3u8")
// PRIVATE —— 普通域名错误命中 IPv6 私网前缀

SourceNetworkPolicy.INSTANCE.isUsablePublicCandidate("http://192.168.1.8/live.m3u8")
// false —— 公网过滤正确；缺口在此规则也用于显式用户 LAN 导入

ChannelClassifier.INSTANCE.mergeKey("CCTV1", "").hashCode()
// -1060187977 —— 不能用此身份作为显示番号或列表下标
```

这证明静态规则与身份数据的特定行为，不证明现有 UI 在所有路径必然崩溃。与真实调用关联后分别形成 A09、A17。

## 5. 设备与实网范围

`adb devices -l` 执行成功，但设备列表为空。因此本次没有真机/模拟器 UI 截图、遥控器回放、媒体首帧/长播、丢帧、温度或三网测试。没有新运行公共直播源探测；仓库历史 `tools/research` 结果只用于理解演进与工具逻辑，不混入当前质量数据。

由于缺少用户设备、省份、运营商测试接入与预登记频道集，本设计中的 4 秒/6 秒首帧、缓冲/流量预算、源资格和连续观看目标均须在实施阶段验证。**本次可以交付设计与代码证据，不能给出“电信/联通/移动实测全部稳定”的结论。**

## 6. 官方资料核对

审计期间在线读取 Android 官方文档及 XMLTV 官方 DTD，用于核对而非用经验猜测协议/API：

| 主题 | 已核对来源 |
|---|---|
| 五向导航、焦点、返回 | [TV navigation](https://developer.android.com/training/tv/get-started/navigation)、[controllers](https://developer.android.com/training/tv/get-started/controllers) |
| 网络变化与实际连接能力 | [Reading network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)、[ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager) |
| 真实视频首帧与质量事件 | [Player.Listener](https://developer.android.com/reference/androidx/media3/common/Player.Listener)、[Analytics](https://developer.android.com/media/media3/exoplayer/analytics) |
| 直播窗口与支持边界 | [Live streaming](https://developer.android.com/media/media3/exoplayer/live-streaming)、[Supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats) |
| 音轨、后台生命周期 | [Track selection](https://developer.android.com/media/media3/exoplayer/track-selection)、[TV audio capabilities](https://developer.android.com/training/tv/playback/audio-capabilities)、[Background playback](https://developer.android.com/media/media3/session/background-playback) |
| 平台 TLS 信任 | [Network security configuration](https://developer.android.com/privacy-and-security/security-config) |
| EPG 关联 | [XMLTV DTD](https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd) |

文档的参数和阶段目标为本项目设计选择；官方资料没有为这些公共源或当前硬件提供性能保证。Media3 等版本采用仓库实际解析结果，后续升级须再核对对应版本 API。

## 7. 归档复核

提交前检查本地 Markdown 链接的目标存在、代码行锚点在文件范围内、代码围栏成对、快照与清单行数一致、重新生成结果一致，以及 `git diff --check`。文件/行号检查仅验证定位存在，结论仍以审计中的实际调用关系为依据。提交后核对 Git 提交文件范围和工作区状态。

2026-09-09 复核结果：6 份归档 Markdown，62 个本地链接（含 40 个代码行锚点）均通过目标/范围检查，代码围栏成对，根 README 入口正确。首轮发现审计链接多退了一层目录，修正后重跑无错误。287 行清单与快照计数一致，复算前后 SHA-256 相同：

```text
inventory.csv  ff714318b2b00005e7095cb7f38408090b97acdfc18ca47c4f8eadd8cd34373e
snapshot.json 2817873fa4a8e13936be560a7b07ce131348f38bc59bbc40f2c51e899c04873f
```

`git diff --check` 通过。Git 的 LF/CRLF 提示属于当前工作区换行配置，没有内容空白错误。未运行的设备/实网计划不标记为通过；后续实现需要维护独立的实测报告，不覆盖这份基线记录。
