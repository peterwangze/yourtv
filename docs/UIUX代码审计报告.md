# 聚源TV UI/UX 与易用性代码审计报告

> 审计性质：只读代码级审计（未修改任何源码）
> 审计日期：2026-08-09
> 版本基线：v3.0.0（versionCode 300，见 version.json）
> 审计方法：对指定 Kotlin 源码与 res 资源做全量行读 + 关键交互链路交叉定位；对照 Google Android TV 10-foot UX 规范与主流电视直播应用（电视家、DIYP、TiviMate、YouTube TV、Apple TV）的通用交互实践给出差距判断。
> 结论依据均为代码行号证据；未包含真机视觉验证，最终以真机回归为准。

---

## 一、审计范围

### 1.1 源码文件

| 文件 | 审查重点 |
|---|---|
| `app/src/main/java/com/horsenma/yourtv/MenuFragment.kt` | 三级导航逻辑、nav_path、重复项、焦点切换、自动隐藏、源切换器 |
| `app/src/main/java/com/horsenma/yourtv/ListAdapter.kt` | 列表行布局、焦点样式、选中态、当前播放频道标识、环绕导航 |
| `app/src/main/java/com/horsenma/yourtv/GroupAdapter.kt` | 分组列表焦点、刷新策略、环绕导航 |
| `app/src/main/java/com/horsenma/yourtv/ProgramFragment.kt` / `ProgramAdapter.kt` | EPG 当前时刻定位、高亮、滚动、加载/空态、自动隐藏 |
| `app/src/main/java/com/horsenma/yourtv/InfoFragment.kt` | 切台信息卡内容、时长、自动隐藏、EPG 取数 |
| `app/src/main/java/com/horsenma/yourtv/SettingFragment.kt` | 设置分组、条目层级、开关/按钮焦点可达性、长度 |
| `app/src/main/java/com/horsenma/yourtv/MainActivity.kt` | 按键映射（MENU/SETTINGS/OK/方向键/数字键）、退出提示、快捷入口、菜单/设置自动隐藏 |
| `app/src/main/java/com/horsenma/yourtv/YourTVApplication.kt` | toast 节流、显示缩放体系 |
| `app/src/main/java/com/horsenma/yourtv/LoadingFragment.kt` / `ErrorFragment.kt` | 加载态、错误态表现 |
| `app/src/main/java/com/horsenma/yourtv/models/TVGroupModel.kt` / `TVListModel.kt` / `ChannelClassifier.kt` | 导航模型、重复项来源、当前播放定位 |
| `app/src/main/java/com/horsenma/yourtv/ChannelFragment.kt` / `TimeFragment.kt` / `SourcesAdapter.kt` / `Ext.kt` | 数字直拨、时间角标、线路列表、toast 通道 |

### 1.2 布局与资源文件

`menu.xml`、`list_item.xml`、`group_item.xml`、`info.xml`、`program.xml`、`program_item.xml`、`setting.xml`、`player.xml`、`loading.xml`、`channel.xml`、`error.xml`、`sources_item.xml`、`item_source.xml`、`modal.xml`、`dialog_confirmation.xml`、`userconfirm.xml`；`colors.xml`、`styles.xml`、`themes.xml`、关键 drawable（`focus_background`、`focus_border`、`button_scale`、`button_focus_selector`、`item_background`、`custom_progress_drawable`、`circle_background`）。

---

## 二、发现清单

### P0 —— 阻断 / 崩溃风险

#### F1　数字直拨路径存在空指针崩溃风险（P0）
- **位置**：`ChannelFragment.kt:78`（`viewModel.groupModel.getCurrent()!!`）；触发链 `MainActivity.kt:1320-1323`（数字键）→ `MainActivity.kt:1100-1113`（`showChannel`）→ `ChannelFragment.kt:76-98`（`show(channel)`）。
- **现象**：`TVGroupModel.getCurrent()` 在 `tvGroupValue.size < 3` 或"全部频道"分组为空时返回 null（`TVGroupModel.kt:247-255`）。解析异常、收藏与全部频道均为空、聚合结果不足 3 组时，用户按任意数字键即触发 `!!` 崩溃。
- **10 英尺影响**：数字直拨是遥控器高频操作，崩溃即阻断直播主流程；且崩溃发生在 `onKeyDown` 主线程，表现为主界面闪退。
- **建议方向**：`show(channel)` 改为空安全处理；数字键入口增加与 `isInputDisabled` 同级的"数据就绪 + 分组数 >= 3"守卫；未就绪时给出中文提示而非崩溃。

#### F2　ErrorFragment 双重 inflate，错误恢复界面适配全部失效（P0）
- **位置**：`ErrorFragment.kt:19-34`。
- **现象**：`onCreateView` 第一次 inflate（19 行）后完成 logo 尺寸、msg 边距与字号缩放（23-30 行），随后 32 行**再次 inflate 并返回第二个视图树**。所有 `px2Px`/`px2PxFont` 适配作用在第一个被丢弃的树；每次创建都重复 inflate 一次完整布局。
- **10 英尺影响**：错误页是"播放失败"的唯一恢复出口，在弱网 IPTV 场景高频出现；4K/2K 电视上错误图标与文字不按分辨率放大（`px2Px` 失效），远距离可读性下降；重复 inflate 浪费内存与主线程时间。
- **建议方向**：删除 32 行的第二次 inflate，保留第一次适配结果；顺带为错误页补充"重试"入口（见 F17）。

### P1 —— 明显影响易用性

#### F3　MENU/SETTINGS 键被设计成隐藏连击手势，单按无任何反应（P1）
- **位置**：`MainActivity.kt:108-110`（`MENU_PRESS_INTERVAL=300ms`、`REQUIRED_MENU_PRESSES=4`）、`MainActivity.kt:1233-1247`（`handleSettingsKeyPress`）、`MainActivity.kt:1325-1332`（KEYCODE_MENU/SETTINGS 分支）、`MainActivity.kt:132-140`（`handleTapRunnable`）。
- **现象**：按一次 MENU/SETTINGS 仅开始计数；500ms 内按 2 次才打开频道菜单，300ms 内连按 4 次才打开设置；单按等于无操作。触摸屏同理：单击无反应、双击开菜单、四连击开设置（`MainActivity.kt:653-683`）。
- **10 英尺影响**：与主流电视遥控器心智完全不符（主流：MENU 一次即出菜单、SETTINGS 一次即出设置）。用户按 MENU 无响应会重复按压，产生"失灵"错觉；隐藏手势不可发现、不可记忆。
- **建议方向**：MENU 单按打开频道菜单、SETTINGS 单按打开设置（可保留连击作为隐藏快捷入口，但不应作为唯一入口）；触摸单击可改为显示信息卡/暂停菜单。

#### F4　列表右键被静默映射为"收藏/取消收藏"，且无任何反馈（P1）
- **位置**：`ListAdapter.kt:132-136`；`list_item.xml:47-48`（heart `clickable=true, focusable=false`）。
- **现象**：频道行聚焦时按 DPAD_RIGHT 直接翻转 `like` 状态，无 toast、无动画；heart 图标不可聚焦，用户无法感知该行的"收藏"控件存在。
- **10 英尺影响**：用户按右键的本意是"进入下一层级/展开"，实际收藏状态被悄悄修改，误操作不可见；右侧方向键在列表里失去"前进"语义。
- **建议方向**：移除右键收藏映射；收藏改为可聚焦的心形按钮（OK 触发）或长按菜单，并加即时视觉反馈（图标切换动画 + 短暂提示）。

#### F5　频道菜单缺少"当前正在播放"的持久标识（P1）
- **位置**：`ListAdapter.kt:53-198`（bind/focus 仅处理焦点态与收藏态，未消费 `positionPlayingValue`）；`GroupAdapter.kt:78-95`（同）；播放位置数据存在于 `TVListModel.kt:48-57`。
- **现象**：打开菜单时只有初始焦点落在当前频道上；一旦用户上下移动，没有任何标记指示哪个频道正在播放。
- **10 英尺影响**：主流电视直播应用（电视家、DIYP、TiviMate）均在列表用"正在播放"角标/色条/▶ 图标标记当前频道；缺失导致用户在长列表中迷失，无法快速找回当前节目。
- **建议方向**：`list_item` 增加 playing 指示器（左侧色条或 ▶ 角标，名称加粗），绑定 `positionPlayingValue`；分组行可显示该组是否含当前频道。

#### F6　EPG 当前节目高亮过弱、无空态、无加载态，且 OK 键行为错乱（P1）
- **位置**：`ProgramAdapter.kt:118-135`（当前行仅文字变白色）；`ProgramFragment.kt:56-77`（每次显示重建 adapter、`index<0` 不滚动、无空态视图）；`program_item.xml:9`（背景固定 `@color/blur`）；OK 键错乱见 F10。
- **现象**：① "现在"行与普通行仅靠白色/灰色文字区分，无背景高亮、无"现在"徽标；② 定位方式为 `scrollToPositionWithOffset(index,0)`，把当前节目顶到列表顶端，用户看不到其后的节目；③ EPG 为空时面板空白（仅 `MainActivity.kt:1157-1174` 在呼出前提示一次）；④ 每次显示都新建 adapter，列表闪烁。
- **10 英尺影响**：EPG 是"左键"呼出的核心功能，用户需要 3 秒内确认"现在播什么、接下来是什么"；当前实现辨识度低，弱光环境下更明显。
- **建议方向**：当前行加高亮背景 + "现在"徽标（时间轴式更佳）；定位改为当前行居中且允许向上回看；空态显示"暂无节目单"；adapter 实例复用。

#### F7　设置页 17 个开关 + 6 个按钮平铺一页，无分组、无层级、文字偏小（P1）
- **位置**：`setting.xml:13-433`（330dp 宽 ScrollView 单列）；`SettingFragment.kt:367-433`（按钮与开关统一 `textSizeSwitch` = `switchChannelReversal.textSize` = 14sp）；`setting.xml:215-216`（`switchEnableWebviewType` 红色 18sp 与整体不一致）。
- **现象**：全部条目无分区标题，顺序为"服务端/频道配置/清除/检查更新/验证用户/赞赏 → 17 个开关 → 退出"，无播放/显示/通用分组；按钮文字 14sp，10 英尺下偏小；开关条目行高偏矮。
- **10 英尺影响**：查找目标设置项需整页滚动记忆位置，长列表无定位感；主流电视设置（Google TV/Apple TV）均按语义分组或二级页组织。
- **建议方向**：按"播放与解码 / 显示与时间 / 导航与收藏 / 网络与源 / 关于"分组加标题；或改二级子页；条目文字提升至 18sp 起、行高 >= 48dp；去除红色高危文案样式。

#### F8　设置页"退出"做成开关，一开即退出、无确认（P1）
- **位置**：`SettingFragment.kt:241-247`。
- **现象**：`switchExit` 勾选 `isChecked=true` 立即 `finishAffinity()`。
- **10 英尺影响**：方向键+OK 的误触成本极低，没有任何二次确认；与"清除设置"有确认对话框形成不一致的心智。
- **建议方向**：改为按钮 + 确认对话框，或直接沿用系统 BACK 两次退出；开关形式应删除。

#### F9　"紧凑的菜单"设置每次启动被强制覆盖为 true（P1）
- **位置**：`YourTVApplication.kt:73`（`SP.compactMenu = true`）；持久化定义 `SP.kt:211-214`（SharedPreferences 存储）。
- **现象**：`Application.onCreate` 无条件写入 true，覆盖用户上次的选择；设置页开关（`SettingFragment.kt:167-173`）只能生效到本次进程结束。
- **10 英尺影响**：用户显式配置被静默重置，设置可信度受损。
- **建议方向**：删除该行，或改为"仅当首次安装（无存储键）时写默认值"。

#### F10　EPG 面板打开时按 OK 会弹出频道菜单（P1）
- **位置**：`ProgramFragment.kt:108-110`（`onKey` 恒返回 false）；`MainActivity.kt:1366-1413`（ENTER 分支 600ms 后 `handleEnterRunnable` 打开 menuFragment）。
- **现象**：`ProgramAdapter` 不消费 ENTER，事件上抛到 Activity 的 OK 分支，600ms 后打开频道菜单。
- **10 英尺影响**：用户在 EPG 里按确认，期望"选中/关闭"，却弹出一个不相干的全屏菜单，交互错乱感强。
- **建议方向**：`programFragment` 可见时在 Activity 的 ENTER 分支显式短路（消费为关闭 EPG 或选中当前节目）；同时让 `ProgramAdapter` 支持选中态。

#### F11　焦点态表达四套并存、无缩放/描边动画，与全局青色不一致（P1）
- **位置**：`ListAdapter.kt:180-188`（整行背景变 `R.color.focus`）、`GroupAdapter.kt:134-142`（同）、`ProgramAdapter.kt:118-135`（仅文字变色）、`SettingFragment.kt:378-385`（按钮仅文字变色）、`SettingFragment.kt:416-432`（开关仅文字变色）；`button_scale.xml`（1.1x 缩放动画）已存在但**未被任何布局引用**；`focus_background.xml:6` 使用 `holo_blue_light` 描边，仅源切换器使用，与应用青色 `colors.xml:2`（#0096A6）不一致。
- **现象**：同一应用内焦点表达方式不统一，列表行无圆角/描边/缩放；设置按钮聚焦时仅文字变白（默认灰色按钮底上白字对比度不足）；`item_background.xml`（粉色）同样未被引用。
- **10 英尺影响**：10 英尺场景依赖"焦点即指针"的即时辨识；不统一的焦点语言增加扫视成本，弱光下文字色变化几乎不可见。
- **建议方向**：统一 StateListDrawable（`state_focused` 青色描边 + 浅色填充 + 圆角 + 轻微缩放），所有列表与设置条目复用；删除未引用的粉色/蓝色资源或统一色板。

#### F12　信息卡节目名可能显示已结束节目，且每次切台新建位图（P1）
- **位置**：`InfoFragment.kt:117-122`（`filter { it.beginTime < now }` 后取 `last()`，未过滤 `endTime`）；`InfoFragment.kt:90-114`（每次 `show()` 新建 300x180 ARGB_8888 Bitmap + Canvas + Paint）。
- **现象**：① 节目已结束且后无节目时，信息卡仍显示过期节目名；② 快速切台时连续分配位图，旧图不回收，内存抖动。
- **10 英尺影响**：信息卡内容可信度下降；快速连按上下键切台时 GC 压力增大，可能引起瞬时卡顿。
- **建议方向**：过滤条件改为 `beginTime < now && endTime > now`；位图改为静态缓存/复用；desc 增加时间范围（"HH:mm-HH:mm 节目名"）。

### P2 —— 改进项

#### F13　紧凑模式宽度两处计算不一致（P2）
- **位置**：`MenuFragment.kt:82-86`（`groupWidth * 2 / 3`）与 `MenuFragment.kt:227-239`（`groupWidth * 4 / 5`）。
- **现象**：首次启动进入菜单与设置切换"紧凑的菜单"后，分组列宽度不同，布局跳动。
- **建议方向**：统一为一个常量/函数。

#### F14　分组列表顶部 UP 聚焦到 GONE 控件并消费按键，组列表环绕不对称（P2）
- **位置**：`MenuFragment.kt:120-136`（顶部 UP → `sourceSwitcherPrev.requestFocus()`）；`menu.xml:21-22`（`source_switcher_container` 默认 `gone`）、`menu.xml:88`（`nextFocusUp` 指向 GONE 控件）；`GroupAdapter.kt:103-112`（仅 DOWN 环绕，无 UP 环绕）。
- **现象**：源切换器隐藏时（`SOURCE_FILE_SWITCHER_ENABLED=false`，`MenuFragment.kt:916`），分组列表顶部按 UP 会对不可见控件请求焦点并消费事件；分组列表底部可环绕到顶部、顶部却不能环绕到底部，行为不对称。
- **建议方向**：顶部 UP 改为环绕到底部（与列表一致），或让焦点落到菜单头；移除对 GONE 控件的 nextFocusUp。

#### F15　菜单更新全量刷新（P2）
- **位置**：`GroupAdapter.kt:177-181`（`notifyDataSetChanged()`）；`MenuFragment.kt:216-225`（`update()` 每次 post 后 `changed()` + `listAdapter.update()`）；`MenuFragment.kt:273-280`（分组焦点变化即触发整组列表更新）。
- **现象**：分组焦点每移动一次就 submitList 一次右侧列表；数据变更全量重绘，滚动位置与焦点存在抖动风险。
- **建议方向**：分组焦点切换时仅按需更新；`changed()` 改用可辨识的 payload 或按条目 notify。

#### F16　加载页黑屏转圈无文案，removeFragment 死代码（P2）
- **位置**：`loading.xml:1-21`（纯黑底 + ProgressBar）；`LoadingFragment.kt:61-70`（`removeFragment()` 从未被调用，外部一律 `hideFragment`）。
- **现象**：首启"解析源"期间用户只看到黑屏 + 转圈，无"正在加载/请稍候"文案；callback 机制为死代码。
- **建议方向**：加载页加品牌 Logo + "正在加载频道列表…"文案；移除死代码或统一为 remove 语义。

#### F17　错误页无重试/操作入口（P2）
- **位置**：`error.xml:1-32`（图标 + 单行 msg）；`ErrorFragment.kt` 无按键处理。
- **现象**：错误页只有静态提示，用户只能按返回/方向键盲操作恢复。
- **建议方向**：增加"重试"按钮（重新触发播放/换源），并消费 OK/BACK 给出明确行为。

#### F18　触摸手势零提示（P2）
- **位置**：`MainActivity.kt:632-794`（左 1/3 竖向滑动=亮度、右 1/3=音量、中间竖向 fling=切台、双击=菜单、长按=EPG）。
- **现象**：功能完整但完全无引导，平板/触摸屏用户靠摸索。
- **建议方向**：首次进入弹一次性手势引导卡（或设置页"手势说明"入口）。

#### F19　切源提示绕过全局 Toast 节流（P2）
- **位置**：`MenuFragment.kt:827/854/875/882`（`Toast.makeText` 直调）；`MainActivity.kt:943-957`（`showSourceInfo` 自建 30f 字号 Toast，5s 后 cancel）。
- **现象**：全局节流（`YourTVApplication.kt:145-162`，同文案 8s 一次）对切源/线路提示不生效，快速切源时提示叠加。
- **建议方向**：统一走 `YourTVApplication.toast` 或为其增加专门的短时去重队列。

#### F20　LiveData 节流观察者 observeForever 未移除（P2）
- **位置**：`MainActivity.kt:541-552`（`throttle()` 内 `observeForever`，无 `removeObserver`）。
- **现象**：Activity 每次重建（进程重建/配置变更）会重新注册，旧 observer 不释放，存在重复触发风险。
- **建议方向**：改用 `observe(viewLifecycleOwner)` 或持有 observer 引用在 `onDestroy` 移除。

#### F21　EPG 每次显示重建 adapter 与布局管理器（P2）
- **位置**：`ProgramFragment.kt:56-77`（`onVisible()` 每次 `ProgramAdapter(...)` + `setAdapter` + 新 `LinearLayoutManager`）。
- **现象**：滚动位置无法保持，重建开销浪费。
- **建议方向**：adapter/layoutManager 懒初始化一次，数据变化走 notify 更新。

#### F22　线路列表编号从 00 开始、URL 过长无层级（P2）
- **位置**：`SourcesAdapter.kt:125`（`"%02d".format(position)`）；`sources_item.xml:25-37`（URL 单行 `ellipsize="start"`，500dp 宽）。
- **现象**：显示"00、01…"与用户习惯的 1 起始不符；长 URL 只露尾巴，难以辨别线路归属。
- **建议方向**：编号 1 起始；标题改为"线路 N（源站域名）"摘要 + 完整 URL 可展开。

#### F23　菜单源切换器约 300 行死代码（P2）
- **位置**：`MenuFragment.kt:594-891`（`setupSourceSwitcher`/`updateSourceSwitcher`/`updateDisplaySource`/`switchSource`/双击计数），开关 `SOURCE_FILE_SWITCHER_ENABLED = false`（`MenuFragment.kt:916`）；`MenuFragment.kt:689-690` 重复调用 `updateSourceText()`。
- **现象**：死代码增加维护成本与误读风险（`switchSource` 仍直调 Toast）。
- **建议方向**：删除或纳入特性开关统一管理；顺带清理重复调用。

---

## 三、易用性亮点（保留项）

1. **三级导航 + 路径提示**：`MenuFragment.kt:539-561` 的 `nav_path` 显示"地方 › 北京"式层级路径，并对三级分类给出"按确认进入地区"引导文案，优于多数同类应用。
2. **两级直达**：央视/卫视/收藏/全部按确认键直接进入频道列表（`MenuFragment.kt:287-310`），减少按键次数，符合主流电视操作习惯。
3. **跨源聚合去重**：`ChannelClassifier` 按"分类|地区|规范名"合并跨源频道，繁体转简体、CCTV 别名归一，频道列表质量高。
4. **列表增量更新**：`ListAdapter.kt:284-295` 使用 DiffUtil，频道数据刷新不闪屏。
5. **焦点重试有上限**：`ListAdapter.kt:38-40/241-257`（5 次）、`MenuFragment.kt:455-462`（10 次），已修复无限 post 重试的旧问题。
6. **全局 Toast 节流**：`YourTVApplication.kt:145-162` 同文案 8 秒去重，避免多源导入刷屏。
7. **自动隐藏时长合理**：菜单 10s（`MainActivity.kt:99`）、设置 60s（`MainActivity.kt:100`）、信息卡/EPG/频道号 5s、音量 2s，与主流 3-10s 区间一致。
8. **BACK 双击退出**：`MainActivity.kt:1311-1318` 带"再按一次返回退出"提示，符合 TV 退出惯例。
9. **三位频道号输入**：`ChannelFragment.kt:76-98` 支持逐位累积输入，5s 超时自动播放。
10. **触摸手势完整**：亮度/音量/滑动切台/双击菜单/长按 EPG（`MainActivity.kt:632-794`），覆盖触摸屏设备。
11. **列表循环环绕**：`ListAdapter.kt:99-131` 上下均环绕，长列表快速回卷。
12. **无缝切台基建**：`player.xml:27-42` 备用播放器预加载下一频道，是 3.0 切台体验的核心保障。
13. **全中文界面**：strings.xml 中文文案 175+ 项，默认源/分类均为中文，符合国内电视用户预期。

---

## 四、与主流 TV UX 最佳实践对照的差距摘要

| 维度 | 主流做法（Google TV / Apple TV / 电视家 / DIYP / TiviMate） | 聚源TV 当前实现 | 差距 |
|---|---|---|---|
| 焦点视觉 | 统一焦点环 + 缩放动画 + 高对比描边 | 4 套颜色方案并存、无动画、存在未引用焦点资源 | 高 |
| 遥控器映射 | MENU=菜单、SETTINGS=设置、OK=确认 | 单按无反应，双击/四连击隐藏手势 | 高 |
| 当前播放标识 | 列表"正在播放"角标/色条常驻 | 无任何 playing 态视觉 | 高 |
| 操作反馈 | 收藏/切源/切台即时反馈（动画+提示） | 右键收藏静默无反馈 | 高 |
| EPG 可读性 | "现在"行高亮 + 时间轴 + 空态 | 仅文字变色、顶对齐、无空态 | 中 |
| 设置结构 | 语义分组或二级页 | 单页 23 个条目平铺无分组 | 高 |
| 错误恢复 | 错误页带"重试"动作 | 静态提示无操作入口 | 中 |
| 加载反馈 | 品牌图 + 文案 + 进度 | 黑屏 + 转圈无文案 | 中 |
| 文字规格 | 正文 >=18sp、条目高度 >=48dp（10 英尺） | 设置按钮/开关 14sp、行高偏矮 | 中 |
| 信息卡 | 频道名 + Logo + 当前节目（含时间）+ 进度 | 名称 + Logo + 单行节目名（可能过期） | 中 |
| 自动隐藏 | 3-10s | 5-10s | 低（一致） |
| 数字直拨 | 3 位输入 + 提示 | 已支持 3 位，但无引导文案 | 低 |

---

## 五、优先建议清单

按"影响 ÷ 成本"排序，并给出建议承载版本（当前基线 v3.0.0 / 300）：

| # | 建议 | 级别 | 涉及位置 | 建议版本 |
|---|---|---|---|---|
| 1 | 删除 ErrorFragment 二次 inflate，恢复错误页缩放适配 | P0 | `ErrorFragment.kt:32` | v3.1.0 |
| 2 | 数字直拨空安全守卫 + 未就绪提示 | P0 | `ChannelFragment.kt:78`、`MainActivity.kt:1320-1323` | v3.1.0 |
| 3 | MENU/SETTINGS 改单按直达，隐藏连击降级为附加入口 | P1 | `MainActivity.kt:1233-1247/1325-1332` | v3.1.0 |
| 4 | 列表增加"正在播放"常驻标识 | P1 | `ListAdapter.kt`、`list_item.xml` | v3.1.0 |
| 5 | 移除右键静默收藏；收藏改可聚焦控件 + 反馈 | P1 | `ListAdapter.kt:132-136`、`list_item.xml:47-48` | v3.1.0 |
| 6 | EPG 当前行高亮 + "现在"徽标 + 空态 + OK 键短路 | P1 | `ProgramAdapter.kt`、`ProgramFragment.kt`、`MainActivity.kt:1366-1413` | v3.1.0 |
| 7 | 设置页按语义分组（或二级页）+ 字号/行高提升 | P1 | `setting.xml`、`SettingFragment.kt` | v3.2.0 |
| 8 | 修复 compactMenu 强制覆盖；"退出"改按钮+确认 | P1 | `YourTVApplication.kt:73`、`SettingFragment.kt:241-247` | v3.1.0 |
| 9 | 统一焦点视觉（描边+缩放，复用一套 drawable） | P1/P2 | 全部列表与设置条目、`button_scale.xml` | v3.2.0 |
| 10 | 信息卡过滤已结束节目 + 位图复用；错误页加"重试" | P2 | `InfoFragment.kt:90-122`、`error.xml` | v3.2.0 |

**版本承载建议**：v3.1.0 集中 P0+P1 中与"按键映射、当前播放标识、EPG、崩溃修复"强相关的 8 项（改动面小、回归风险低）；v3.2.0 承载设置重构与焦点视觉统一（涉及布局与资源，需视觉回归）；v3.3.0 处理 P2 长尾（死代码清理、手势引导、节流统一）。

**测试建议**：每次演进必须回归以下不退化基线——① 首启解析总时长 <= 45s；② 上下键连续切台 20 次无卡顿/无闪黑；③ 备用播放器预加载生效（切台无黑屏停顿）；④ 数字直拨、三位输入、双击退出；⑤ 菜单/EPG/设置自动隐藏计时；⑥ 收藏增删即时反映到"我的收藏"；⑦ 错误恢复路径（断网→错误页→重试/切台）。

---
*本文档为只读审计交付物；发现项均可在对应文件行号复核。*
