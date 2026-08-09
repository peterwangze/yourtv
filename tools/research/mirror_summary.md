# GitHub 加速镜像实测总结（2026-08-09，上海时区网络环境）

## 测试方法

- 基准文件：
  - `https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/index.m3u`（当前 main 分支真实存在的总列表；旧文件 `tv/m3u/ipv4.m3u` 已从仓库删除，仅作历史对比）
  - `https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u`（存在，直连稳定）
- 每个组合最多 3 次尝试（每次 Range: bytes=0-0，超时 8s，UA=Mozilla/5.0），取最优结果；200 响应会检查内容是否为 HTML/错误页而非源文件。
- 完整数据见 `mirror_test.json`，共 67 组测试。

## 关键发现：上游文件已变更

fanmingming/live 在 2026-08-09 推送后，`tv/m3u/ipv4.m3u` 已从 main 分支移除（git 稀疏克隆验证 HEAD 树，README 也已不再引用该文件）。当前官方文件为 `tv/m3u/index.m3u`、`tv/m3u/ipv6.m3u`、`tv/m3u/itv.m3u`、`tv/m3u/demo.m3u`。任何仍使用旧 `ipv4.m3u` 直连/镜像都会得到 404——这不是镜像问题，是源 URL 失效，**必须更换源路径**。

## 可用镜像排序（按耗时，206 有效透传）

| 排名 | 镜像 | index.m3u 耗时 | iptv4.m3u 耗时 | 说明 |
|---|---|---|---|---|
| 1 | `https://gh-proxy.com/` | 325ms | 296ms | 两个基准均最快，最稳定 |
| 2 | `https://github.horsenma.top/` | 311ms | 464ms | 非常快，两基准均可用 |
| 3 | `https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}` | 573ms | 304ms | 不同 URL 结构（无前缀拼接），需按 `gh/` 格式；可用且快 |
| 4 | `https://gh.llkk.cc/` | 653ms | 560ms | 较快，两基准均可用 |
| 5 | `https://ghfast.top/` | 超时(8s) | 960ms | 对 fanmingming 路径不稳定（另一轮返回 403），仅作后备 |
| 6 | `https://ghproxy.net/` | 1155ms | 1074ms | 可用但较慢 |
| — | 直连 `raw.githubusercontent.com` | 491ms | 1282ms | fanmingming 路径抖动严重（多轮测试约 60% 返回 404），vbskycn 稳定 |

## 不可用镜像（全部失败）

| 镜像 | 现象 |
|---|---|
| `https://ghproxy.cn/` | 返回 200 但内容是自身 HTML 页面（6956B），不是源文件 |
| `https://gh.con.sh/` | 返回 "Suspent due to abuse report"（已被封） |
| `https://gh.ddlc.top/` | 全部 429 限流 |
| `https://gh-proxy.llyke.com/` | 全部 404（服务失效） |
| `https://github.moeyy.xyz/` | SSL EOF / 超时，不可达 |
| `https://ghp.ci/` | SSL EOF，不可达 |
| `https://ghproxy.click/` | SSL EOF，不可达 |
| `https://raw.gitmirror.com/` | SSL EOF，不可达 |
| `https://ghps.cc/` | SSL EOF / 超时 |
| `https://mirror.ghproxy.com/` | SSL EOF，不可达 |
| `https://hub.gitmirror.com/` | SSL EOF，不可达 |
| `https://cf.ghproxy.cc/` / `https://www.ghproxy.cc/` / `https://ghproxy.cc/` | TLS 证书已过期（certificate verify failed） |

## 官方直连域名

- `https://live.fanmingming.com/tv/m3u/ipv4.m3u`：404（旧路径已删除）。
- `https://live.fanmingming.cn/tv/m3u/index.m3u` 等 README 推荐的新地址：本次从测试网络**不可达**（5s SSL EOF / 超时，连续多轮），需在真实设备网络下再验证。

## 结论与建议

1. 源 URL 必须从 `tv/m3u/ipv4.m3u` 迁移到 `tv/m3u/index.m3u`（或按频道组拆分的 itv.m3u），否则直连和镜像全部 404。
2. 加载方式建议按顺序做多路兜底：`gh-proxy.com` → `github.horsenma.top` → jsdelivr → `gh.llkk.cc`，全部失败再尝试直连 raw / 官方域名。
3. 建议对源 URL 做并发竞速（race）：多个镜像同时发起，取最先成功的 206 响应，可把有效延迟稳定在 300–650ms，优于直连（400–1300ms 且抖动）。
4. jsdelivr 作为独立通道价值高：结构不同、速度接近最快镜像，可作为镜像之外的第二形态兜底。
