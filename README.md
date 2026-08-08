# 聚源TV（JuYuan TV）

基于开源项目 [horsemail/yourtv](https://github.com/horsemail/yourtv)（MIT 协议）深度定制的安卓 TV 直播应用，目标是做成"电视家类"体验：开箱即用、多源聚合、遥控器友好。

## 核心特性

- **开箱即用**：内置加密频道源（央视/卫视/地方/港澳台等 160+ 频道）与网页视频源，安装后立即播放，无需配置
- **多源聚合**：预置 13 个国内可用公共直播源，首次启动自动导入第一个可用源，其余源后台静默聚合（按"央视→卫视→省份→国家"分类合并，同频道多线路自动去重并按清晰度/稳定度排序）；源管理界面可随时切换、添加、删除
- **三级分级列表**：一级分类 央视/卫视/地方/海外/其他；央视/卫视 直接两级到频道，地方（省份）/海外（国家）/其他（分类）为 一级→地区/分类→频道 三级，遥控器右键/确认下钻、返回键逐级退出，路径提示实时显示当前位置
- **多线路**：单频道多线路自动测速排序，播放失败自动换线路（设置中可开关）
- **源格式全兼容**：
  - M3U（`#EXTM3U`/`#EXTINF`）
  - TXT（`分组名,#genre#` + `频道名,url`，国内电视家类源最常用格式）
  - JSON / hex 加密源（兼容 TVBox 生态的接口源）
  - `webview://` 网页视频源
- **EPG 节目单**：默认接入 fanmingming 节目单（可自定义 XMLTV 地址）
- **Android TV 原生体验**：Leanback 启动器、遥控器全键操作、手机画中画、熄屏播放
- **远程配置**：同一局域网内手机浏览器访问 `http://<电视IP>:34567` 即可推送直播源/EPG/代理配置

## 本分支相对上游的改动

| 改动 | 说明 |
| --- | --- |
| TXT `#genre#` 直播源支持 | 新增 `convertTxtToM3U()`，复用 M3U 解析管线（分组/多线路/EPG） |
| 预置公共直播源 | `SP.DEFAULT_SOURCES` 内置 5 个源，首次启动自动导入第一个可用源，失败自动切换镜像、下次启动重试 |
| 修复 HTTP 源下载 | 上游 `DownGithubPrivate` 强转 `HttpsURLConnection`，导致纯 `http://` 源全部下载失败；改为 `HttpURLConnection` 基类 |
| 清除上游私密凭据 | `cloudflare.txt`、`github_private.txt` 已清空（原为作者私有 API Key / 代理配置），验证功能退化为无码模式 |
 | 版本与更新隔离 | 版本号 2.6.0（versionCode 260），高于上游 2.4.3，避免被上游更新检查引导安装原版 APK |
 | 频道智能分类 | 新增 ChannelClassifier：央视（CCTV/CGTN 台号规范化）/ 地方卫视 / 地方频道（33 省+近 400 城市，含港澳台）/ 海外频道（按国家）/ 其他，参考电视家等主流 TV App 的分组习惯 |
 | 多源聚合多线路 | 首次导入成功后后台并发导入全部预置源，按"分类+规范名"合并（如 CCTV-1综合 与 CCTV1、Hunan TV 与 湖南卫视），央视/主流卫视线路达到 10~30 条，按清晰度降序排列 |
 | Toast 弹窗节流 | 全局 Toast 节流：同文案 8 秒内最多弹一次，根治观看中"解析源"类弹窗刷屏；自动导入源失败静默跳过（不再逐个弹失败提示） |
| 品牌化 | 应用名改为"聚源TV"，APK 输出名 `juyuan_tv_vX.Y.Z.apk` |
| 透明图标 | AI 生成图标（深蓝紫渐变 + 白色电视 + 橙色播放键），四角透明，适配任意电视背景 |
| 首启不卡顿 | 预置源导入延迟到界面就绪后、全部在 IO 线程执行、下载超时 8s/重试 2 次、失败 24h 内不重试 |
| 修复重进黑屏 | ① `updateConfig` 增加 context 就绪保护（重进时 ready() 早于 init() 的竞态崩溃）② 异常处理器不再自杀进程 ③ `onStop` 只暂停播放不释放（HOME 往返秒恢复）④ 大缓存字符串移出 SharedPreferences 改为文件缓存 |

## v2.4.6 更新内容

- 图标四角改为透明（适配任何电视背景，无黑边）
- 预置源精简为 7 个实测可用的源（移除 fanmingming demo.m3u 空壳列表与失效的 hujingguang）
- 修复首次启动卡"解析源"：导入延迟到界面就绪后，IO 线程执行，失败源快速跳过
- 修复退出后重进黑屏卡电视：竞态崩溃 + 异常处理器自杀 + 播放器释放策略三个根因全部修复
- 频道缓存从 SharedPreferences 迁移到文件，避免大字符串拖慢启动

## v2.5.0 中文化 + 国内网络可用源
 ## v2.6.0 智能分类 + 多源聚合 + 弹窗治理

## v2.7.0 三级分级列表 + 咪咕/百视通高质量源

- **三级分级频道列表**：侧栏一级分类固定为 央视 / 卫视 / 地方 / 海外 / 其他；央视、卫视两级直达频道；地方按省份（含港澳台与"未分类"兜底）、海外按国家/地区、其他按分类展示为三级，右键/确认键下钻地区，左键/返回键逐级退回，菜单顶部实时显示"地方 › 河北"式路径
- **咪咕/百视通等高质量源集成**：预置源扩至 16 个，新增 jk2024988/TV2024 咪咕2（62 台纯咪咕）、hououinkami AppleTV（咪咕 CCTV1-17 + APTV 8M 卫视，每日维护）、migu-sports 三源合并（含浙江卫视4K、湖南卫视4K、卫视超清、地方台）；解析时自动过滤咪咕 VOD 回放噪音（gslbmgspvod 域名）与历年春晚/更新时间等垃圾分组
- **高质源排序**：线路评分新增咪咕（miguvideo.com，运营商级 1080p+）、百视通/APTV 8M 高分档；4K/8K/超清改为词边界匹配（不再把 64k 音频、jiangsuhd 误判为 4K）；聚合排序加入稳定源加权；实测 CCTV1=24、CCTV4=31、CCTV5=39、湖南卫视=17、东方卫视=23、江苏卫视=12 条线路，咪咕线路 235 条、4K/UHD 线路 23 条
- **分类质量提升**：咪咕体育每日轮换分组（体育-今天05-10）归一为"体育"；iptv-org 组合分组（Animation;Kids）取首个分类；补齐 30+ 县市城市映射（七台河/余姚/上虞/东丰/云霄/伊犁等）与品牌映射（中国蓝→浙江、快乐垂钓→湖南）；"地方频道"分组无省市信息的频道归入 地方›未分类，不再丢台
- **修复**：播放频道位置定位到真实分组（避免停留在"全部频道"导致重开菜单导航错乱）；返回键退出下钻与关闭菜单幂等（同一 BACK 双分发不再误关菜单）；菜单路径提示与焦点分组同步

### v2.7.0 实测（Android TV 模拟器）

- 聚合 14~16 源 / 947 频道（v2.6.0 为 643~644）；分类分布：央视 53、卫视 77、地方 564（含未分类 114）、海外 39、其他 214
- 菜单导航：央视/卫视直接两级出频道；地方›省份›频道、海外›国家›频道、其他›分类›频道 三级下钻/逐级返回全部通过；播放 河北任丘综合 后重开菜单自动回到 地方›河北 并高亮当前频道
- 稳定性：多轮冷启动/切台/下钻/返回无崩溃、无 ANR

## v2.8.0 分级导航打磨 + 高质量源再升级

- **分级导航交互优化**：确认键在 央视/卫视/收藏/全部 或下钻后的地区/分类上直接进入频道列表（原来还需再按一次右键）；三级分类（地方/海外/其他）确认键直接下钻地区/分类，返回键逐级退出
- **新高质量源集成（预置 19 源）**：新增 hujingguang/ChinaIPTV cnTV1_ALL（405 台，含各省广电官方流 cztv/上海台/云南台、CCTV-16 4K 移动源、凤凰香港）、vbskycn/iptv iptv4（446 台，含东方卫视4K bestv.cn、苏州4K、CCTV4K）、vicjl/myIPTV TV-IPV4（58 台，黑龙江移动 OTT 央视/卫视）
- **点播/循环源过滤**：过滤影视资源站（ffzy）、斗鱼/虎牙 7x24 转播循环（metshop.top）、央视点播片段（newcntv.qcloudcdn.com / cntv.lxdns.com）、快手视频文件（kwimgs.com）、"XXXX年春晚"回放；聚合后噪音残留 0 条
- **质量评分扩展**：新增芒果TV（mgtv.com 湖南卫视4K）88 分、浙江广电（cztv/cztvcloud）87 分、运营商 OTT（chinamobile/dxhmt/gmcc/mobaibox）85 分档位
- **繁体/展示名规范化**：繁体"衛視/有線/新聞"等自动转简体并跨源合并（"北京衛視 (1080p) [Geo-blocked]" 与 北京卫视 合并为同一频道 19 条线路）；展示名清理 "(1080p) [Geo-blocked]" 等原始后缀；卡酷少儿归入 地方-北京
- **元数据严格清洗**：畸形 #EXTINF（tvg-id="" 垃圾标签不再成为频道名、属性写在逗号后的兼容）、#EXTVLCOPT 每频道 HTTP 头随线路保留（Referer/User-Agent）、频道级多线路质量排序、播放失败不跨频道漂移（保留当前频道由用户手动换线）
- **实测（Android TV 模拟器）**：19 源聚合 1190 频道；CCTV1=27、CCTV5=30、CCTV5+=14、CCTV16=17（含 4K 移动源）、CCTV4K=3、湖南卫视=20、东方卫视=21、浙江卫视=16、江苏卫视=21、广东卫视=22；菜单 央视/卫视 确认直达、地方›北京›频道、海外›美国›频道、其他›体育›频道 与返回退出全部通过；多轮冷启动/下钻/切台无崩溃

 - **频道分类参考电视家**：侧栏分组改为 央视 / 卫视 / 地方频道（按省份，含港澳台）/ 海外频道（按国家/地区）/ 其他，频道列表按"央视→卫视→省份→国家"固定排序，默认频道落在 CCTV1
 - **多源聚合（≥10 条线路）**：预置源扩至 13 个（新增 best-fan 省份/央视专项列表，移除失效的 YueChan APTV 与被区域封锁的 wcb1969）；央视主台 CCTV1-17 线路 13~29 条、主流卫视 10~20 条（湖南 17/东方 20/浙江 13/广东 13/北京 11/江苏 9 等），按清晰度（URL 关键词+实测分辨率）降序，坏线播放失败自动跳过
 - **弹窗刷屏治理**：全局 Toast 节流（同文案 8 秒 1 条）；自动导入/聚合阶段源失败静默跳过，不再反复弹"下载在线直播源失败"
 - **修复源切换误报**：激活源缓存缺失时不再自动整体替换频道列表（避免聚合结果被内置源覆盖），"已是当前源"不再误报"加载失败"；聚合静默导入不再并发抢占 active_source
 - **国内网络可用**：全部 13 个源为国内直连域名或 GitHub 国内镜像可加速地址，实测聚合 9~12 个源/643~644 频道
 
 ### v2.6.0 实测（Android TV 模拟器）
 
 - 全新安装：3 秒出"解析中"提示 → 约 23 秒 CCTV1 出画，无卡死、无失败弹窗刷屏
 - 二次启动：缓存列表秒出，直接起播
 - 分类分布：央视 49、卫视 48、香港 11/澳门 5/台湾 8、海外国家组（美/日/韩/俄/英等）、省份组（北京/上海/河北/江苏/浙江…）
 - 线路数：CCTV1=20、CCTV4=23、CCTV5=29、湖南卫视=17、东方卫视=20、北京卫视=11
 - 稳定性：连续观看 60 秒无崩溃、无弹窗刷屏

- **全界面中文**：默认语言改为中文（不再依赖系统语言设置）；分组名自动中文化（Entertainment→娱乐、News→新闻、Kids→少儿 等 30 个常用分类映射）
- **国内网络可用源**：默认源重排为 6 个国内直连/官方域名源（zbds.top、fanmingming.cn/.com、iptv-org、best-fan），移除被墙的 jsDelivr；GitHub 拉取自动走多个国内镜像（moeyy.xyz、ghfast.top、gh-proxy 等）且镜像优先、原地址兜底
- **解析超时保护**：单次源下载最长 45 秒放弃，避免弱网下"解析源"无限等待卡死
- **修复无限重试卡顿**：列表焦点重试与菜单可见重试分别限制 5 次/10 次，根治 20 次/秒的刷屏重试导致遥控卡顿
- **EPG / 刷新源入口**：设置面板新增"更新节目单""刷新直播源"按钮

## v2.4.7 性能优化

- **源预解析缓存**：解析后的频道列表存本地（`channels_list_cache.json`），启动直接读取秒出列表，不重新下载/解析；源内容另有 24h 文件缓存，仅源失效/缓存过期才重新拉取
- **1 秒启动**：启动 1 秒内出频道列表并开始播放（稳定源立即播放 + 缓存列表秒出 + 后台静默刷新，内容不变不替换 UI）
- **1 秒切台**：播放器低缓冲策略（500ms 出画面，默认 2.5s）、连接预热（OkHttp 连接池复用）、线路健康探测（并发 8 后台测全部频道，坏线跳过）、播放失败动态标记
- **高清稳定优先**：线路综合评分 = 延迟桶（实测探测）→ 清晰度（URL 关键词 + 实际分辨率缓存）→ 稳定源加权（播放 30s 成功的线路）；同频道线路延迟实测差异可达 75ms~2.9s，自动选最快高清线
- **快速换线**：停播换线超时 5s→2.5s，自动换线跳过已确认坏线

### 性能实测（Android TV 模拟器，运营商 IP 源）

- 二次启动：1 秒内出 436 台列表，稳定源立即播放
- 切台（健康线路）：1.0~1.5 秒出 1080p 画面（模拟器网络波动；真机国内宽带直连通常更快）
- 首播（未探测频道）：2~3 秒（含服务器响应，探测完成后第二次切台提速）

## 构建

环境要求：JDK 17、Android SDK（compileSdk 35，即 `platforms;android-35`）、Gradle 8.12（项目自带 wrapper）。

```powershell
# 首次构建前确认 SDK 路径（local.properties 已指向本机 D:\Android\sdk）
.\gradlew.bat assembleDebug
 # 产物：app\build\outputs\apk\debug\juyuan_tv_v2.7.0.apk

# 发布版（混淆压缩，约 12MB）
.\gradlew.bat assembleRelease
```

安装到 Android TV / 模拟器：

```powershell
 adb install -r app\build\outputs\apk\release\juyuan_tv_v2.7.0.apk
```

## 直播源管理

 ### 预置源（默认顺序，共 16 个，均为国内网络可用）
 
 1. `https://live.zbds.top/tv/iptv4.txt`（zbds：437 台/761 线路，TXT 格式，IPv4，每 6 小时更新）
 2. `https://live.fanmingming.cn/tv/m3u/ipv6.m3u`（fanmingming IPv6 全量：81 台，国内官方域名）
3. `https://live.fanmingming.com/tv/m3u/ipv6.m3u`（同上，.com 备用域名）
4. `https://live.fanmingming.cn/tv/m3u/ipv4.m3u`（fanmingming IPv4 全量，国内官方域名）
5. `https://raw.githubusercontent.com/jk2024988/TV2024/main/咪咕2.m3u`（咪咕 62 台：CCTV/卫视/地方，运营商级源）
6. `https://raw.githubusercontent.com/hououinkami/AppleTV/main/Source/China_v4.m3u`（咪咕 CCTV1-17 + APTV 8M 卫视，每日维护）
7. `https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/三源合并_央视体育地方综合源.m3u`（咪咕+地方 492 台，含浙江卫视4K/湖南卫视4K/卫视超清）
8. `https://iptv-org.github.io/iptv/countries/cn.m3u`（iptv-org 中国频道：153 台）
9. `https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8`（best-fan：63 台/445 线路，每日自动构建，GitHub 自动走国内镜像）
10. `https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8`（best-fan 省份专项：卫视/省台多线路）
11. `https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8`（best-fan 央视专项：CCTV 多线路）
12. `https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u`（aptv：国内卫视/央视线路）
13. `https://iptv-org.github.io/iptv/languages/zho.m3u`（iptv-org 中文全量：216 台）
14. `https://iptv-org.github.io/iptv/countries/hk.m3u`（iptv-org 香港）
15. `https://iptv-org.github.io/iptv/countries/tw.m3u`（iptv-org 台湾）
16. `https://iptv-org.github.io/iptv/countries/mo.m3u`（iptv-org 澳门）


> 注：v2.4.5 起移除了 fanmingming demo.m3u（实测为无流地址的空壳列表）和 hujingguang/ChinaIPTV（实测流地址全部失效）；v2.5.0 起移除被墙的 jsDelivr 镜像，GitHub 源自动经国内镜像加速；v2.6.0 起移除失效的 YueChan APTV（404）与被区域封锁的 wcb1969（GitHub 451），替换为 best-fan 省份/央视专项源；v2.7.0 起新增咪咕/百视通专项源（解析时自动过滤咪咕 VOD 回放与历年春晚/更新时间等垃圾分组）；v2.8.0 起新增 ChinaIPTV cnTV1_ALL、vbskycn iptv4、vicjl TV-IPV4 三个源，并过滤影视资源站/斗鱼虎牙循环/央视点播片段/快手视频文件等点播噪音。

首次启动按顺序自动导入第一个可用的源（任一成功即停止，其余可在源管理随时切换）；全部失败时下次启动重试。

修改方式：编辑 `app/src/main/java/com/horsenma/yourtv/SP.kt` 中的 `DEFAULT_SOURCES`（JSON 数组）。

## 打包签名

个人自用版本已配置 release 签名：

- 密钥库：`keystore/juyuan_tv.jks`（已在 `.gitignore` 中排除）
- 签名配置：`keystore.properties`（storePassword/keyPassword 默认 `juyuan2026`，alias `juyuan_tv`）
- 构建命令：`.\gradlew.bat assembleRelease`
- 产物：`app\build\outputs\apk\release\juyuan_tv_v2.7.0.apk`（约 12MB，已复制到 `dist\`）

⚠️ 密钥库请自行备份；丢失后无法对旧版本做覆盖升级。

## 图标与名称

- 应用名：聚源TV（三处 `strings.xml` 的 `app_name`）
- 图标：AI 生成后人工裁剪（深蓝紫渐变 + 白色电视 + 橙色播放键），替换了 `logo0.png`、`logo0_1.png`、`ic_launcher.png`、`banner0.png`（320x180 TV banner 规范）

### 添加自定义源

- **远程配置（推荐）**：手机/电脑与电视同一 Wi-Fi，浏览器打开 `http://<电视IP>:34567`，粘贴直播源 URL 或文本
- **应用内**：频道菜单 → 源管理（CGSR）→ 添加，支持 URL 或扫码
- **支持的源格式**：M3U、TXT（`#genre#`）、JSON、hex 加密（可用 yourtv 的[在线加解密工具](https://yourtvcrypto.horsenma.net)生成）、`webview://` 网页源

### EPG

设置 → EPG，默认 `https://live.fanmingming.cn/e.xml`，可填多个地址用逗号分隔。

## 目录结构

```
app/src/main/java/com/horsenma/yourtv/
├── MainViewModel.kt      # 核心：源解析（M3U/TXT/JSON）、导入、频道状态
├── MainActivity.kt       # 界面与遥控器交互
├── PlayerFragment.kt     # Media3 播放器、自动换线路
├── SimpleServer.kt       # 局域网远程配置服务（端口 34567）
├── DownGithubPrivate.kt  # 源下载（HTTP/HTTPS/GitHub 镜像）
├── SourceDecoder.kt      # hex 加密源解码（AES）
├── SP.kt                 # 偏好设置、预置源列表
└── res/raw/              # 内置源：channels.txt（加密）、webchannelsiniptv.txt、rawstablesource.txt
```

## 合规声明

- 本项目仅聚合用户自行配置的直播源地址，不内置、不提供任何侵权内容；请确保所用源具备合法授权
- 上游项目为 MIT 协议，本分支保留其版权声明；`README.upstream.md` 为上游原始说明
- 应用内嵌的预置源来自上游 yourtv 公开仓库与 fanmingming/live、iptv-org 等公共项目，均为社区公开维护的地址列表

## 致谢

- [horsemail/yourtv](https://github.com/horsemail/yourtv) — 代码底座
- [fanmingming/live](https://github.com/fanmingming/live) — 直播源、台标、EPG
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — 国际直播源
- [lizongying/my-tv](https://github.com/lizongying/my-tv) — 上游综合的功能参考
