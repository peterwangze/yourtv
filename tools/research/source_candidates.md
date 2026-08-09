# 聚源TV 候选源调研报告（2026-08-09）

> 调研范围：国内可用高质量 IPTV 直播源（重点：卫视/地方频道覆盖 + 国内可直连），GitHub 加速镜像现状。
> 方法：GitHub API 查活跃度（43 仓库）→ jsdelivr data API 定位真实文件路径 → urllib 实际下载（UA=Mozilla）→ 解析频道构成（央视/卫视/地方/OTT/噪音）→ 每源分层抽样 5-15 条 URL 做 Range GET 存活探测（6s 超时）→ 对存活 URL 二次校验是否为真实 HLS（#EXTM3U）。
> **测试网络说明**：本机出口 IP 为东京（iill.top 反爬页泄露 ipOrig=123.100.137.61, JP）。因此：全球可访问的源（GitHub raw/CF）测得的存活率可靠；**国内运营商 OTT（咪咕/移动/电信 PLTV、cmvideo、广东电信等）在本机测得失败≠国内不可用**，此类源一律标注「需国内网络验证，本机无法核实」。所有数值均为当日实测。

## 一、候选源总表

推荐度综合：维护活跃度、卫视/地方覆盖、抽样存活率、国内直连性、格式兼容、噪音占比。

| 源名 | URL | 最近更新 | 总频道数 | 央视 | 卫视 | 地方 | OTT/咪咕 | 噪音% | 抽样存活 | 托管域名 | 国内直连性 | 推荐度 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **vbskycn/iptv** | https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u | 2026-08-09 | 449 | 28 | 44 | 310 | 3 | 14.3% | 14/15 (93.3%)，8/8 真HLS | raw.githubusercontent | 镜像加速 | ★★★★★ |
| **best-fan/iptv-sources** cn_all | https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8 | 2026-08-08 | 431 | 206 | 212 | 11 | 0 | 0.5% | 15/15 (100%)，真HLS | raw.githubusercontent | 镜像加速 | ★★★★★ |
| **best-fan/iptv-sources** cn_province | https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8 | 2026-08-08 | 212 | 0 | 212 | 0 | 0 | 0% | 5/5 (100%) | raw.githubusercontent | 镜像加速 | ★★★★★ |
| **zbds（爱直播）** | https://live.zbds.top/tv/iptv4.txt | 2026-08-09（文件内更新时间戳） | 785 | 105 | 188 | 412 | 7 | 9.3% | 14/15 (93.3%)，7/8 真HLS | live.zbds.top | 国内直连 ✓ | ★★★★★ |
| **CCSH/IPTV** live_lite | https://raw.githubusercontent.com/CCSH/IPTV/master/live_lite.m3u | 2026-08-09 | 2233 | 390 | **797** | 711 | 81 | 10.5% | 13/15 (86.7%)，8/8 真HLS | raw.githubusercontent | 镜像加速 | ★★★★★（新增首选） |
| **CCSH/IPTV** live（全量） | https://raw.githubusercontent.com/CCSH/IPTV/master/live.m3u | 2026-08-09 | 4270 | 390 | **831** | 2416 | 157 | 10.7% | 14/15 (93.3%) | raw.githubusercontent | 镜像加速 | ★★★★（新增备选） |
| **YueChan/Live** GNTV | https://raw.githubusercontent.com/YueChan/Live/main/GNTV.m3u | 2026-08-08 | 50 | 2 | 0 | 46 | 1 | 2% | 7/7 (100%)，6/7 真HLS | raw.githubusercontent | 镜像加速 | ★★★★（新增） |
| **YanG-1989/m3u** Gather | https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u | 2026-08-07 | 129 | 0 | 0 | 76 | **44**（cmvideo 咪咕移动） | 7% | 抽样 0/5（境外不通） | raw.githubusercontent | 国内需验证（cmvideo 系国内 OTT） | ★★★（国内验证后新增） |
| **YanG-1989/m3u** Migu | https://raw.githubusercontent.com/YanG-1989/m3u/main/Migu.m3u | 2026-08-07 | 45 | 0 | 0 | 0 | **45**（cmvideo） | 0% | 0/5（境外不通） | raw.githubusercontent | 国内需验证 | ★★★（国内验证后新增） |
| **suxuang/myIPTV** ipv4 | https://raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u | 2026-06-19 | 1224 | 135 | 174 | 632 | 118 | 12.3% | 3/15 (20%) | raw.githubusercontent | 镜像加速 | ★★（谨慎，量大质弱） |
| **ngo5/IPTV** | https://raw.githubusercontent.com/ngo5/IPTV/main/m3u/ipv4.m3u | 2026-08-01 | 58 | 19 | 35 | 1 | 3 | 0% | 0/12（境外不通） | raw.githubusercontent | 国内需验证 | ★★（国内验证候选） |
| **fanmingming/live** index.m3u | https://live.fanmingming.com/tv/m3u/index.m3u | 2026-08-09 | 94 | 33 | 61 | 0 | 0 | 0% | 0/10（GD_CUCC 404 / gitv 403） | live.fanmingming.com | 文件可达，内容当前多失效 | ★★（降级） |
| **fanmingming/live** ipv6.m3u | https://live.fanmingming.com/tv/m3u/ipv6.m3u | 2026-08-09 | 82 | 24 | 34 | 20 | 4 | 0% | 3/13 (23.1%)，需 IPv6 | live.fanmingming.com | 需 IPv6 网络 | ★★（IPv6 用户保留） |
| **jk2024988/TV2024** 咪咕2 | https://raw.githubusercontent.com/jk2024988/TV2024/main/%E5%92%AA%E5%92%952.m3u | 2026-07-25 | 62 | 19 | 11 | 0 | 29 | 4.8% | 0/11（HTTP 660 签名过期） | raw.githubusercontent | 国内需验证 | ★★（降级/监测） |
| **hujingguang/ChinaIPTV** cnTV1 | https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV1_ALL.m3u8 | 2026-08-09（自动更新） | 405 | 22 | 37 | 257 | 75 | 3.5% | 1/15 (6.7%) | raw.githubusercontent | 镜像加速 | ★（降级） |
| **iptv-org/iptv** cn | https://iptv-org.github.io/iptv/countries/cn.m3u | 2026-08-09 | 154 | 35 | 26 | 86 | 5 | 1.3% | 4/15 (26.7%) | iptv-org.github.io | 国内可达 | ★★★（保留） |
| **iptv-org/iptv** zho | https://iptv-org.github.io/iptv/languages/zho.m3u | 2026-08-09 | 219 | 40 | 26 | 142 | 7 | 1.4% | 7/15 (46.7%) | iptv-org.github.io | 国内可达 | ★★★（保留） |
| **Kimentanm/aptv** | https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u | 2026-07-20 | 120 | 25 | 40 | 9 | 0 | 38.3%（春晚mp4） | 4/13 (30.8%)，4/4 真HLS | raw.githubusercontent | 镜像加速 | ★★★（保留+过滤） |
| **zhmzjj310144/migu-sports** | https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/%E4%B8%89%E6%BA%90%E5%90%88%E5%B9%B6_%E5%A4%AE%E8%A7%86%E4%BD%93%E8%82%B2%E5%9C%B0%E6%96%B9%E7%BB%BC%E5%90%88%E6%BA%90.m3u | 2026-07-12 | 492 | 47 | 29 | 114 | 152 | 30.5% | 5/15 (33.3%)，存活者 5/5 真HLS | raw.githubusercontent | 国内需验证 | ★★★（保留，回放需过滤） |
| **Free-TV/IPTV** china | https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_china.m3u8 | 2026-07-07 | 17 | 12 | 0 | 5 | 0 | 0% | 1/10 (10%) | raw.githubusercontent | 镜像加速 | ★（不建议） |
| **hououinkami/AppleTV** China_v4 | https://raw.githubusercontent.com/hououinkami/AppleTV/main/Source/China_v4.m3u | 2026-08-09 | 43 | 18 | 25 | 0 | 0 | 0% | 0/10 | raw.githubusercontent | 镜像加速 | ★（当前全挂，移除/降级） |
| **vicjl/myIPTV**（=BurningC4 同源） | https://raw.githubusercontent.com/vicjl/myIPTV/main/TV-IPV4.m3u | 2026-05-13 | 58 | 18 | 35 | 1 | 4 | 0% | 0/12 | raw.githubusercontent | 镜像加速 | ★（移除） |
| **suxuang/myIPTV** 咪咕直播.txt | https://raw.githubusercontent.com/suxuang/myIPTV/main/%E5%92%AA%E5%92%95%E7%9B%B4%E6%92%AD.txt | 2026-06-19 | 165 | 28 | 25 | 49 | 56 | 4.2% | 表面 10/12，实测 0/8 真HLS（代理返回错误页） | raw.githubusercontent | 代理 8.138.7.223 已失效 | ★（不建议） |
| **suxuang/myIPTV** 广东电信.txt | https://raw.githubusercontent.com/suxuang/myIPTV/main/%E5%B9%BF%E4%B8%9C%E7%94%B5%E4%BF%A1.txt | 2026-06-19 | 371 | 77 | 109 | 163 | 2 | 5.4% | 0/11（rtp 组播代理全挂） | raw.githubusercontent | 需国内宽带 | ★（不建议） |
| **suxuang/myIPTV** 移动IPTV.m3u | https://raw.githubusercontent.com/suxuang/myIPTV/main/%E7%A7%BB%E5%8A%A8IPTV.m3u | 2026-06-19 | 124 | 36 | 62 | 4 | 15 | 5.6% | 0/15（移动 PLTV 境外不通） | raw.githubusercontent | 需国内移动宽带验证 | ★★（国内验证候选） |
| **qwerttvv/Beijing-IPTV** | https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/IPTV-Mobile.m3u（Unicom 同） | 2026-07-15 | 134 | 27 | 35 | 61 | 0 | 8.2% | 0/11（LAN udpxy/组播地址） | raw.githubusercontent | 仅北京本地宽带 | ★（不建议） |
| **zhmzjj310144/migu-sports** migu_tv | https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/migu_tv.m3u | 2026-07-12 | 341 | 25 | 22 | 151 | 0 | 41.9% | 0/11（342 条指向 192.168.0.103 内网） | raw.githubusercontent | 内网地址不可用 | ★（不建议） |
| **zhmzjj310144/migu-sports** 央视综合源 | https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/%E5%A4%AE%E8%A7%86%E7%BB%BC%E5%90%88%E6%BA%90.m3u | 2026-07-12 | 28 | 25 | 0 | 0 | 3 | 0% | 0/8（migu 660 签名过期） | raw.githubusercontent | 国内需验证 | ★★（监测） |
| **Meroser/IPTV** IPTV-demo | https://raw.githubusercontent.com/Meroser/IPTV/main/IPTV-demo.m3u | 2026-03-29（此后停更） | 432 | 28 | 43 | 157 | 30 | 40.3% | 4/15 (26.7%) | raw.githubusercontent | 镜像加速 | ★（不建议，停更） |
| **anguszh/Meroser-IPTV**（fork） | https://raw.githubusercontent.com/anguszh/Meroser-IPTV/main/IPTV.m3u | 2024-02-14 | 213 | 26 | 40 | 65 | 65 | 8% | 1/15 (6.7%) | raw.githubusercontent | 镜像加速 | ★（不建议，停更） |
| **tv.iill.top** Gather/CN | https://tv.iill.top/m3u/Gather | — | — | — | — | — | — | — | 返回反爬 HTML（parklogic） | tv.iill.top | 已反爬 | ★（无法核实内容） |
| **YueChan/Live** IPTV.m3u / CUTV.txt | https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u | 2026-08-08 | 90 | 27 | 26 | 0 | 0 | 0% | rtp 组播/rtsp 回看地址，互联网不可播 | raw.githubusercontent | 仅组播环境 | ★（不建议） |
| **imDazui/Tvlist** | https://raw.githubusercontent.com/imDazui/Tvlist-awesome-m3u-m3u8/master/m3u/CCTV.m3u | 2025-11-14 | 大量运营商组播/rtsp | — | — | — | — | — | 未抽样（历史组播为主） | raw.githubusercontent | 需各运营商网络 | ★（不建议，停更） |
| **Guovin/iptv-api** | 需自部署生成 output/result.m3u | 2026-08-07 | — | — | — | — | — | — | — | — | 需自部署 | ★★（自部署方案，非直接源） |
| **HerbertHe/iptv-sources** | 仓库仅源码无现成 m3u | 2026-08-09 | 0 | — | — | — | — | — | — | — | — | ★（非直接源） |
| **kimwang1978/collect-tv-txt** | 仓库活跃（2026-08-09），但文件路径无法核实（GitHub 页面超时/jsdelivr 403） | 2026-08-09 | — | — | — | — | — | — | — | — | 无法核实 | ⚠️ 无法核实 |
| **Tommy-70/iptv、HOTMOVE/iptv、supersuiee/iptv、Yiov/wo、guoweiok/tv、islovezz/iptv、sx1978/iptv、wudongdefeng/iptv、zxing003/iptv、SilentDemonSD/IPTV、luongz/iptv、fenxp/iptv、imldl/iptv、yamaya315/iptv、120001240/IPTV 等** | 均已 404（GitHub API 实测不存在） | — | — | — | — | — | — | — | — | — | 不存在 | ✗ 移除 |

## 二、推荐新增源（前 6，含完整 URL）

1. **CCSH/IPTV live_lite.m3u**（首选，卫视/地方覆盖最大提升）
   `https://raw.githubusercontent.com/CCSH/IPTV/master/live_lite.m3u`
   证据：仓库 2026-08-09 当日推送（386★，活跃）；2233 条 = 390 央视行 + **797 卫视行** + 711 地方行；抽样 13/15 存活（86.7%），8/8 为真 HLS；噪音约 10.5%（多线路冗余行，按台去重后可大幅压缩）。卫视覆盖（湖南/浙江/江苏/东方/北京…含多线路）直接解决"卫视仅 50 频道"痛点。
2. **CCSH/IPTV live.m3u**（全量版备选）
   `https://raw.githubusercontent.com/CCSH/IPTV/master/live.m3u`
   证据：4270 条（48 个分组），831 卫视行 + 2416 地方行，抽样 14/15 (93.3%)。文件 1.1MB，适合"源管理"二级选项而非默认源（首载慢）。
3. **YueChan/Live GNTV.m3u**
   `https://raw.githubusercontent.com/YueChan/Live/main/GNTV.m3u`
   证据：仓库 2026-08-08 推送；50 频道（浙江系地方台 + 台湾新闻/4K），抽样 7/7 存活（100%），6/7 真 HLS，噪音 2%。小而精的地方台补充。
4. **YanG-1989/m3u Gather.m3u**（国内 OTT 高价值，需国内网络复验）
   `https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u`
   证据：仓库 2026-08-07 推送（11.4k★）；内含 44 条 gslbserv.itv.cmvideo.cn 咪咕「移动」OTT（晴彩/咪咕直播 4K 等）+ 若干卫视；本机（东京）403，**无法核实国内可用性**，但 cmvideo 系国内移动 OTT 官方域，建议加入后在国内真机验证。
5. **YanG-1989/m3u Migu.m3u**（咪咕纯 OTT 子集）
   `https://raw.githubusercontent.com/YanG-1989/m3u/main/Migu.m3u`
   证据：45 条全为 cmvideo 咪咕移动 OTT，0 噪音；同 4 需国内验证。可与 Gather 二选一（Gather 含 80 条斗鱼/虎牙非电视内容，建议优先 Migu.m3u 或用 Gather 时过滤非 TV 域名）。
6. **ngo5/IPTV m3u/ipv4.m3u**（国内验证候选）
   `https://raw.githubusercontent.com/ngo5/IPTV/main/m3u/ipv4.m3u`
   证据：仓库 2026-08-01 推送（5.8k★）；58 频道（19 央视 + 35 卫视）；本机 0/12（推测国内运营商源），需国内验证后决定。

> 补充：suxuang/myIPTV ipv4.m3u（1224 条、135 央视/174 卫视/632 地方）覆盖面大但抽样存活仅 20%，可作"源管理"中的可选补充源，不建议放入默认前几位。

## 三、建议保留 / 降级 / 移除

**保留（现有 19 源中）**：zbds iptv4.txt、vbskycn iptv4.m3u、best-fan cn_all/cn_province/cn_cctv、iptv-org cn/zho/hk/tw/mo、aptv iptv.m3u、migu_sports（需过滤回放）。

**移除**：
- `live.fanmingming.cn/*` 两个源：本机 SSL UNEXPECTED_EOF 不可达（2026-08-09 复测，见 _manifest.json 与本次下载）；且上游已删除 `tv/m3u/ipv4.m3u` 路径（git 稀疏克隆 + README 确认），官方仅存 `tv/m3u/index.m3u` 等。建议替换为 `https://live.fanmingming.com/tv/m3u/index.m3u`（文件可达 28517B，但内容抽样 0/10 存活——GD_CUCC 404/江西 gitv 403，仅浙江卫视等个别官方台存活；**建议保留 EPG `e.xml`，频道列表降级**）。
- `vicjl TV-IPV4.m3u` 与 `BurningC4 TV-IPV4.m3u`：两者字节级同源（8021B），本机抽样 0/12 全挂；vicjl 仓库 2026-05 后停更。
- `hououinkami China_v4.m3u`：本机 0/10 全挂（live.aikan.miguvideo.com 连接即断）→ 降级/监测：若国内实测可用可保留，否则移除。
- 已确认 404 不存在的仓库（见总表末尾），不再尝试。

**降级/监测**：jk2024988 咪咕2（migu HTTP 660 = 签名过期，文件 2026-07-25 后未更新，建议保留在源列表尾部并加自动刷新监测）；hujingguang cnTV1（1/15 存活，自动更新但质量差）；fanmingming ipv6（仅 IPv6 网络可用，国内 IPv6 用户可留）。

## 四、GitHub 加速镜像实测（2026-08-09，东京出口）

基准 1：`fanmingming/live main/tv/m3u/index.m3u`（上游新路径，确认存在）；基准 2：`vbskycn/iptv master/tv/iptv4.m3u`（直连稳定）。每组最多 3 次尝试取最优（Range bytes=0-0，8s 超时），并对 200 响应校验是否为 HTML 假页面。完整 67 组数据见 mirror_test.json / mirror_summary.md。

**可用镜像（按耗时排序，206 有效透传）**：
1. gh-proxy.com — 296–325ms ✅（现 App 列表第 11 位，应前移）
2. github.horsenma.top — 311–464ms ✅（第 8 位）
3. cdn.jsdelivr.net（`/gh/{owner}/{repo}@{branch}/path`）— 304–573ms ✅（结构不同，需特判；适合静态 m3u）
4. gh.llkk.cc — 560–653ms ✅（第 5 位）
5. ghfast.top — 960ms，对 fanmingming 路径不稳定（另一轮 403/超时）✅ 仅后备（第 2 位）
6. ghproxy.net — 1074–1155ms ✅（**不在现列表，建议新增**）
7. 直连 raw.githubusercontent.com — 491–1282ms，抖动大（国内通常被墙/慢；**建议作为镜像后的最后兜底，现 App 未包含直连**）

**不可用（建议从 getUrls 移除）**：github.moeyy.xyz（SSL EOF）、cf.ghproxy.cc / www.ghproxy.cc / ghproxy.cc（证书过期）、ghp.ci（SSL EOF）、ghproxy.click（SSL EOF）、raw.gitmirror.com（SSL EOF）、ghps.cc（SSL EOF）、mirror.ghproxy.com（SSL EOF）、hub.gitmirror.com（SSL EOF）、gh.ddlc.top（429 限流）、ghproxy.cn（返回 6956B HTML 假 200，非文件）、gh.con.sh（"Suspent due to abuse report" 已被封）、gh-proxy.llyke.com（404）。

**上游路径变更（必须处理）**：fanmingming/live 在 2026-08-09 推送后已删除 `tv/m3u/ipv4.m3u`（git 稀疏克隆验证 HEAD 树 + README 确认），官方当前文件为 `tv/m3u/index.m3u`、`ipv6.m3u`、`itv.m3u`、`demo.m3u`；README 新推荐的 `live.fanmingming.cn` 直连域名本次网络不可达（5s SSL EOF），需真机验证。App 中任何 `tv/m3u/ipv4.m3u` 直连/镜像引用都会 404，必须换路径。

**配置建议**：Utils.getUrls 镜像列表调整为 `[gh-proxy.com, github.horsenma.top, gh.llkk.cc, ghfast.top, ghproxy.net]`，末尾追加直连 raw；其余 7 个删除。更优做法：对多个镜像并发竞速（race），取最先成功的 206，可将有效延迟稳定在 300–650ms（优于直连 400–1300ms 且抖动大）；jsdelivr 作为结构不同的第二形态兜底。提示：本机在东京，国内出口对镜像的排序可能不同，App 已有"逐个尝试"逻辑可容忍顺序差异，但**移除已确认不可用的 7 个可显著降低首源加载时间**（现列表前 3 位中 2 个不可用：moeyy、llyke）。

## 五、对「分类优化」的输入（供下一阶段使用）

1. **噪音来源**：aptv 38.3%（历年春晚 mp4/VOD）、migu_sports 30.5%（"体育-昨天xx-xx"回放组）、migu_tv 41.9%（内网地址）、meroser_demo 40.3%。过滤规则建议：组名含「回放/点播/昨天/春晚」→ 整组 noise；URL 域名含 gslbmgspvod/mp4/playback → noise。
2. **卫视识别**：标题含「卫视」即可覆盖 95%+；需补充别名表：凤凰(资讯/中文/香港)、翡翠台、TVB、星空、纬来、东森、中天、民视、台视、华视、公视（港澳台归入「港澳台」而非「卫视」）。
3. **地方频道分组**：zbds/ccsh 的 local 行建议按省份关键词（country_keywords.json/province_maps.json 已有基础）映射到「省份」组，无法映射的归「其他」；当前 classified.json 中大量「纪录频道/体育频道/音乐频道」等专题组来自 zbds 的 group-title 直用，需要白名单 + 正则回退双轨。
4. **单线路问题**：ccsh（新增）与 vbskycn 提供同台多线路，可在合并时按台名（mergeKey）聚合提升"地方频道多线路"比例。

## 六、证据文件索引

- 实测数据：tools/research/github_meta.json（43 仓库 API）、mirror_test.json + mirror_summary.md（67 组镜像探测）、analysis.json（频道构成）、probe_results.json（抽样存活明细）、jsdelivr_listing.json（文件路径）
- 下载样本：tools/research/sources/（现有 19 源）、tools/research/downloads/（新候选 30+ 文件）
- 复现脚本：rs_download.py / rs_download2.py / rs_download3.py / rs_list_repos.py / rs_jsdelivr_list.py / rs_analyze.py / rs_probe.py
- 无法核实项：kimwang1978/collect-tv-txt 文件路径（页面超时）、全部国内 OTT 源在国内网络的真实存活率（本机东京）、tv.iill.top 内容（反爬）。
