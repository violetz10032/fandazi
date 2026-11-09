# 全局 UI 与视觉主题 · 审查报告与设计规范

> 适用版本：1.2.3（versionCode 16）· 最后核对：2026-11-09 · 1.2.2 冻结基线：`1870052` / `bc26bb8`。

> 对象：`index.html`（单文件、零构建）。目标是把颜色、圆角、字阶、阴影、间距统一为 CSS 变量，把公共组件接入 token，并在同一套 token 上铺 5 套皮肤；随后加主题中心与「适中」动效。
>
> 本文分两部分：**A. 审查报告**（现状盘点、缺口、风险）与 **B. 设计规范**（token 方案、主题架构、组件规范、动效规范、验收方式）。文中所有结论都对应到仓库里的真实实现，未实现的能力不写进规范。

---

## A. 审查报告

### A1. 页面清单（底部 5 个一级视图 + 业务弹层）

| 一级视图 | `data-view` | 主要内容 | 主要组件 |
| --- | --- | --- | --- |
| 记录 | `record` | 店铺卡片列表、草稿区、记录详情抽屉、AA 分账、分享图 | 卡片、标签、分段控件、抽屉、弹窗、空态 |
| 推荐 | `recommend` | 每日推荐、朋友推荐、聚会、健身、在家吃（含一周菜单 / 采购 / 烹饪模式） | 卡片、按钮组、表单、空态、错误态 |
| 抽签 | `pick` | 抽签筛选、结果卡、权重日志、纯随机模式 | 卡片、分段控件、表单、结果动画 |
| 照片 | `photos` | 照片中心、引用索引、回收站 | 网格卡片、抽屉、轻提示 |
| 我的 | `more` | 外观 / 图标 / 地图底图 / WebDAV / 备份恢复 / 诊断 / 关于 | 分段控件、按钮、面板、日志块 |

弹层类：`#sheetMask`（底部抽屉）、`.modal-mask`（居中弹窗）、`#toast`（轻提示）、盲盒悬浮球（`#blindMask`）、分享图弹层。

### A2. 组件清单（需要接 token 的公共件）

按钮（`.btn` / `.btn-primary` / `.btn-soft` / `.btn-danger` / `.chip-toggle`）、卡片（`.card` / `.card.flat`）、表单（`.field` + `input/select/textarea`）、弹窗（`.modal`）、抽屉（`.sheet`）、空态（`.empty`）、加载态（`.log` / 结果占位）、错误态（`.notice` / `.legal` / 失败 toast）、轻提示（`.toast`）、标签（`.tag`）、分段控件（`.stat-seg`）、顶部栏（`.topbar`）、底部导航（`.tabbar`）。

### A3. 现有硬编码样式盘点（实测）

用脚本扫描 `:root` / `html[data-theme="dark"]` / `html[data-skin="*"]` 之外的颜色声明，**只剩 1 处**：

```
831: @keyframes flash{0%{box-shadow:0 0 0 0 rgba(232,89,12,.35)}100%{box-shadow:0 0 0 14px rgba(232,89,12,0)}}
```

结论：颜色基本已 token 化，缺口不是「颜色散落」，而是：

1. 该处 `flash` 写死暖橙，P3/P4/P5/Cream 下高亮环仍是橙色（旧色残留）。
2. **间距 / 字阶 / 行高 / 字体没有 token**：`padding:14px`、`font-size:13px`、`15px/1.55` 等仍散落在组件规则里。
3. 主题只有 `light/dark + none/p3/p4/p5`，**缺 Cream Editorial**，也缺「恢复默认」「预览」「当前标识」。
4. 动效只有零散的 `viewIn` / `up` / `blindRoll`，没有统一开关、没有系统 `prefers-reduced-motion` 兜底、页面不可见时动画照跑。

### A4. 风险

| 风险 | 说明 | 处置 |
| --- | --- | --- |
| 主题切换影响业务数据 | 皮肤只写 `localStorage` 的 `fandazi.theme/skin/skinTexture/motion` | 切换路径不触碰 `fandazi.v2`、`fandazi.feedback`、`fandazi.dishes`、IndexedDB、同步键 |
| 触碰地图三条路径 | 网页高德直载 / APK `map-embed.html` / Canvas 回退 | 主题只改 CSS 变量，不改地图加载与绘制逻辑；`applyTheme/applySkin` 只调用既有 `mapDraw()` 重绘 |
| 体积红线 3 MB（原 2.2 MB 已放宽） | 单文件内联 | 新增 CSS/JS 控制在几十 KB；每次提交报字节数。性能闸门保留：每阶段报首屏可交互时间与 1000 条记录滚动帧耗时，明显退化先停下报数 |
| 兼容性 | `color-mix()` 需 Chrome 111+ | 仅用于一处高亮环；不支持时退化为 `transparent` 环，功能不受影响 |
| 主题名与旧数据 | 旧 `fandazi.skin` 只可能是 `none/p3/p4/p5` | 读取函数用白名单 `SKIN_ORDER`，旧值照常可用，新增 `cream` 不影响旧值 |

---

## B. 设计规范

### B1. Token 方案（三层）

1. **基础层**（` :root `，Warm Living 为基准）
   - 颜色：`--bg/--bg-2/--card/--ink/--ink2/--ink3/--line/--accent/--accent2/--soft/--soft-ink/--accent-line/--chip/--seg-bg/--seg-on/--panel/--canvas/--icon-bg/--glass/--mask/--topbar` 及地图、图表、状态色（`--good-*` / `--mid-*` / `--low-*` / `--warn-*` / `--danger-*`）。
   - 间距：`--sp-1:4px` … `--sp-6:32px`；卡片内边距 `--card-pad`、卡片间距 `--gap-card`。
   - 字阶：`--fs-xs:11px` / `--fs-sm:12.5px` / `--fs-base:15px` / `--fs-lg:17px` / `--fs-xl:22px` / `--fs-2xl:28px`；行高 `--lh-tight/--lh-base/--lh-loose`；字距 `--track/--track-wide`。
   - 字体：`--font-ui`（系统 UI 栈）、`--font-display`（默认等于 UI 栈，Cream 才换成衬线）。
   - 形状：`--r`（皮肤基准圆角）→ `--r-card/--r-btn/--r-tag/--r-field/--r-modal/--r-sheet/--r-toast/--r-pill`。
   - 阴影：`--shadow/--shadow-1/--shadow-2/--shadow-btn`。
   - 动效：`--dur-fast:140ms` / `--dur-base:180ms` / `--ease`。
2. **明暗层**（`html[data-theme="dark"]`）：只覆盖颜色与阴影，不改形状与间距。
3. **皮肤层**（`html[data-skin="..."]` 及其 `[data-theme="dark"]`）：只覆盖颜色 / 字体 / 圆角 / 阴影，再叠加少量「形态修饰」选择器（边框、圆角、标题排版）；不改组件结构、不改 DOM。

优先级：皮肤块写在明暗块之后，同名变量由更具体的选择器胜出（`html[data-skin="p5"][data-theme="dark"]` > `html[data-theme="dark"]` > `:root`）。

### B2. 主题架构（5 套设计语言）

| 值 | 名称 | 默认明暗 | 设计语言（不是换色） |
| --- | --- | --- | --- |
| `none` | Warm Living | 跟随系统 | 暖白纸感 `#FAF7F2` / 橙 `#E8590C`；16px 圆角、柔和低对比阴影、无衬线 |
| `cream` | Cream Editorial | 浅色 | 奶油纸 `#F6F1E7`、砖红 `#9B4526`；**衬线标题**、2px 直角、无阴影、细线分隔、行高放宽到 1.8 |
| `p3` | P3 Moonlight | 深色 | 深蓝水面 `#04121F`、青色 `#62D6F5`；胶囊按钮、圆形 logo、标题字距 2px、细分隔线、噪点+径向光 |
| `p4` | P4 Golden Days | 浅色 | 亮黄 `#FFC400` 底 `#FFF7DF`；电视方框：卡片上下 3px 粗边、按钮胶囊、标题下 3px 色条、扫描线纹理 |
| `p5` | P5 Phantom Style | 深色 | 红 `#D8001F` / 墨黑 / 纸白；2px 硬切角、硬投影（无模糊）、标题斜切、章节标题左侧色块、网点纹理 |

持久化键：`fandazi.theme`（light/dark/auto）、`fandazi.skin`（none/cream/p3/p4/p5）、`fandazi.skinTexture`（on/off）、`fandazi.motion`（mid/reduced）、`fandazi.iconStyle`。

### B3. 主题中心（我的 → 外观）

- **预览**：`#skinPreview` 为每套皮肤渲染一张卡（浅色底 + 深色底 + 强调色三个色块，取值来自 `SKIN_META` / `SKIN_ACCENT`，不写死）。
- **切换**：预览卡与 `#skinSeg` 都走同一个 `setSkin()`；切皮肤同时套用该皮肤默认明暗（`SKIN_DEFAULT`）。
- **当前标识**：当前卡右下角显示「当前」；`#themeNow` 一行写清「皮肤 · 明暗 · 纹理 · 动效」。
- **恢复默认**：`#btnThemeReset` → `resetAppearance()`：皮肤 `none`、明暗 `auto`、纹理 `on`、动效 `mid`，**不触碰任何业务数据**。
- **持久化**：三个键写入 `localStorage`；重开页面由 `<head>` 内联脚本在首屏前套用 `data-theme/data-skin/data-skin-texture/data-motion`，避免闪白。

### B4. 组件接入规范

- 卡片：`background:var(--card)`、`border:1px solid var(--line)`、`border-radius:var(--r-card)`、`padding:var(--card-pad)`、`box-shadow:var(--shadow)`。
- 按钮：`border-radius:var(--r-btn)`、`font-size:var(--fs-sm)`；主按钮 `--accent/--accent-ink`。
- 表单 / 弹窗 / 抽屉 / 标签 / 轻提示：分别使用 `--r-field/--r-modal/--r-sheet/--r-tag/--r-toast`，颜色全部走语义 token。
- 空态 / 加载态 / 错误态：`--ink3/--ink2`、`--notice-*`、`--danger-*`，不使用裸色值。
- 禁止：组件里新增 `#hex` / `rgba(...)` 字面量；需要新颜色时先在 `:root` 与每套皮肤里加 token。

### B5. 动效规范（适中）

- 只使用 `transform` 与 `opacity`，不动 `width/height/top/left`，不做 `filter:blur()` 动画。
- 覆盖点：页面转场（`.view` → `viewIn`）、卡片按压（`.card:active` `scale(.996)`）、收藏反馈（收藏按钮 `.fav-pop`）、结果卡（`.draw-card` / `.pop-in`）、盲盒摇动（既有 `blindRoll`）、旅行随机（选中卡 `.pop-in`）。
- 时长：`--dur-fast 140ms` / `--dur-base 180ms` / 结果入场 260ms；缓动 `--ease`。
- 关闭方式：
  - 用户开关 `fandazi.motion = reduced` → `html[data-motion="off"]`，全局把动画/过渡压到 0.01ms；
  - 系统 `prefers-reduced-motion: reduce` 且用户未显式选 `mid` 时，自动按 reduced 生效；
  - 页面不可见：`visibilitychange` 写 `data-page-hidden`，`animation-play-state:paused`。

### B6. 验收方式

- headless Chrome 截图矩阵：**主题 × 一级视图**（5 套皮肤，各自默认明暗 + Warm 深浅对照）× 5 个视图，输出到 `.tmp/theme-shots/`。
- 每张图同时断言：`document.documentElement.scrollWidth === 375`（375 宽无横向溢出）、`data-theme/data-skin` 与预期一致、无 JS 异常 / console error。
- 人工核对：逐个对照是否仍有旧主题色残留（尤其 `flash` 高亮环、地图标记色、图表系列色）。
