# 界面修复验证证据

现场截图与同次操作的 UIAutomator XML。截图先取、XML 后取，二者可能相差数秒；短 Toast 和系统输入法可能不出现在 XML 中。

| 文件前缀 | 内容 |
| --- | --- |
| fixed-menu | 正常短台号、紧凑侧栏 |
| delivery-settings | 最终安装包的设置顶部焦点、全宽比例按钮、无时钟覆盖 |
| ime-single-settled | 搜索输入框按 OK 后的系统键盘 |
| search-down | CCTV2 搜索结果焦点 |
| final-epg-focus | 无数据时仍可进入节目单，焦点为今天 |
| final-epg-data | 成功刷新节目数据，焦点仍在刷新按钮 |
| final-line-right / final-line-left | 线路 1→2→1 |
| final-line-wrap / final-line-wrap-back | 线路 1→8→1 |
| final-channel-down / final-channel-up | 下键 CCTV2、上键 CCTV1 |
| delivery-bad-panel | 原先失败过的 TV BRICS 频道复验；本次已正常出画，设置可用，不能作为失败状态的复验结论 |

`build-final-qa.log` 和 `TEST-*.xml`：71 项回归测试。`build-signed.log`：最后一次 Release 构建。截图中的广播画面仅用于说明应用实际运行状态，不代表直播源授权、长期可用性或三网覆盖结果。
