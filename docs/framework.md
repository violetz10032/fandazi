# 饭搭子 · 框架方案（v6 · PWA + 时间×距离推荐 + 批量导出 + 好友分享 + 互导教程）

> 一个「美食记录 + 场景决策 + 时间/距离推荐 + 价格区间抽签 + 批量导出 + 好友本地互导」的单页应用。
> 当前实现：`index.html` 单文件内联 CSS/JS；同级 `manifest.webmanifest`、`sw.js`、4 个本地图标。
> 数据版本：3（旧 v1/v2 自动迁移）；应用版本：v6。
> 历史版本：`_backups/index-v1.html`（v1）… `index-v7.html`（最新一份备份）。备份文件不入库，版本演进见 git 提交历史。
> 日期：2026-11-05

---

## 0. 定位与边界

| 维度 | 说明 |
| --- | --- |
| 产品目标 | 记录吃过的店 → 按场景/时间/距离给出可解释推荐 → 一键记成一餐 / 批量导出 / 分享 |
| 四个场景 | 日常、聚会、健身、朋友 |
| 移动端结构 | 底部 4 标签：记录 / 推荐 / 抽签 / 我的 |
| 数据来源 | **只有用户手动录入**，不抓取任何平台数据 |
| 数据存储 | `localStorage` 存结构化数据 + `IndexedDB` 存照片，全部留在本机 |
| 技术栈 | 原生 HTML + CSS + JavaScript；零构建、零 CDN、零第三方脚本 |
| PWA | Manifest + Service Worker（仅同源静态资源） |
| 位置能力 | **不申请定位权限**；距离、耗时、营业时间均为手填估算 |
| 通知 | 默认只用应用内提醒条；浏览器通知仅在用户点击按钮后申请 |
| 非目标 | 店铺发现/榜单/团购比价、账号、云同步、多人实时投票、服务端推送 |

---

## 1. 系统架构

### 1.1 分层

```
视图层   view-record / view-recommend / view-pick / view-more
         recordCard / decideCard / drawCard / placeBox / diagnostics
领域层   buildStores / dailyScore / runDaily / runDecideNow / runDraw
         timeCtx / isOpenAt / travelMinutesOf / weekFitOf / repeatPenalty
         selectAllFiltered / buildCsv / buildShareList
存储层   localStorage['fandazi.v1']（结构化数据）
         localStorage['fandazi.v1.bak']（上次可解析副本）
         localStorage['fandazi.v1.raw']（迁移失败原始副本）
         IndexedDB['fandazi.media'] / 'photos'（压缩照片）
容错层   safeRender / logError / debounce / storesCache / 分页 / 错误日志
PWA 层   manifest.webmanifest / sw.js / registerSW / updateBar
外链层   美团 / 大众点评 / 高德 / 百度地图关键词搜索（仅用户点击）
```

关键入口（行号会随编辑漂移，仅作定位）：

| 能力 | 位置 |
| --- | --- |
| 数据迁移 / 字段校验 | [index.html](../index.html#L1054) |
| 读取 / 写盘 | [index.html](../index.html#L1192) |
| 店铺聚合 | [index.html](../index.html#L1515) |
| 时间上下文 | [index.html](../index.html#L1636) |
| 营业判断 | [index.html](../index.html#L1656) |
| 出行耗时估算 | [index.html](../index.html#L1662) |
| 综合评分 | [index.html](../index.html#L1715) |
| 日常推荐筛选 | [index.html](../index.html#L1774) |
| 抽签 | [index.html](../index.html#L1852) |
| 提醒条 / 通知 | [index.html](../index.html#L3554) |
| SW 注册 / 更新 | [index.html](../index.html#L3637) |
| 分享卡绘制 | [index.html](../index.html#L3744) |
| 批量导出 | [index.html](../index.html#L3946) |
| CSV 生成 | [index.html](../index.html#L3989) |
| 长图绘制 | [index.html](../index.html#L4117) |
| 长图逐页生成 | [index.html](../index.html#L4187) |

### 1.2 视图与状态

- `showView(name)` 切换 4 个主视图；`viewScroll` 记录离开时的滚动位置，返回时恢复。
- 推荐页内部四段 `daily / party / fitness / friend`，`showPanel` 统一切换，不再占用底部标签。
- 所有子视图由 `safeRender` 包裹，一个视图报错不影响其他视图。

---

## 2. PWA 缓存策略与离线边界

### 2.1 清单与图标

`manifest.webmanifest`：

- `name` = 饭搭子 · 美食记录与聚餐决策，`short_name` = 饭搭子
- `start_url` / `scope` = `./`，适配子路径部署
- `display: standalone`，`orientation: portrait`
- `theme_color: #e8590c`，`background_color: #faf7f2`
- 4 个图标：192/512 普通图标 + 192/512 maskable（本地 Pillow 生成，无第三方 Logo）

### 2.2 `sw.js` 生命周期

| 阶段 | 行为 |
| --- | --- |
| `install` | `cache.addAll` 预缓存 `index.html`、manifest、4 图标；单个失败不阻断安装 |
| `activate` | 删除所有以 `fandazi-cache-` 开头且不等于当前 `CACHE` 的旧缓存；`clients.claim()` 接管已开页面 |
| `message` | `SKIP_WAITING` → `self.skipWaiting()`；`VERSION` → 回传当前版本号 |
| `fetch` | 只处理 `GET`；**跨域请求直接 return，不 respondWith、不缓存、不代理** |
| 导航请求 | 网络优先；成功后把 `index.html` 写入缓存；失败回退缓存 `index.html`；都没有则返回 503 文本 |
| 同源静态 | 缓存优先 + 后台更新（stale-while-revalidate） |

缓存名格式：`fandazi-cache-fandazi-pwa-v4-20261105`。版本号变更即触发旧缓存清理。

### 2.3 更新提示

1. 注册时若存在 `reg.waiting` 且有 controller → 显示「有新版本可用 · 点此刷新」。
2. 监听 `updatefound`，新 worker 进入 `installed` 且已有 controller → 显示提示。
3. 点击刷新：`waiting.postMessage({type:'SKIP_WAITING'})` → `controllerchange` → `location.reload()`。
4. 每 60 分钟调用一次 `reg.update()`，长时间打开也能发现新版本。
5. 不自动刷新，避免打断正在填写的表单。

### 2.4 离线边界

| 场景 | 能否使用 | 说明 |
| --- | --- | --- |
| 首次访问前完全离线 | 否 | 必须先联网打开一次，SW 才能写入缓存 |
| 已访问过、断网刷新 | 是 | 导航回退缓存 `index.html`；记录来自本机存储 |
| 第三方平台链接 | 不缓存 | 断网时由浏览器自身报错，应用不代理 |
| 照片/记录写入 | 是 | 只走 localStorage / IndexedDB |
| 后台推送 / 定时同步 | 无 | 未注册 push、未使用 Background Sync |
| `file://` 双击 | 部分 | 应用全部功能可用；SW/安装/通知不可用，静默降级 |
| `http://局域网IP` | 部分 | 非安全上下文，SW 与安装被浏览器禁用；仅普通网页功能 |

### 2.5 安装与降级

- `registerSW()` 先判断 `location.protocol` 为 `http:` / `https:`，否则直接 return，不抛错。
- `renderInstall()` 区分三种状态：已 standalone、`file://` 双击、http(s) 可安装/需手动添加。
- `beforeinstallprompt` 事件被保存为 `deferredInstall`，用户点击「安装到桌面」才调用 `prompt()`。
- `appinstalled` 后刷新安装区文案。

---

## 3. 数据模型（version 3）

### 3.1 记录字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 客户端生成 |
| `name` | string | 店名（聚合键一部分） |
| `city` / `area` / `address` | string | 城市 / 商圈或地标 / 地址 |
| `lat` / `lng` | number \| null | 预留，不采集定位 |
| `distanceKm` | number \| null | **手填**“与我距离”，0.1–999，保留 1 位小数 |
| `openStart` / `openEnd` | `HH:MM` \| `''` | 营业时段起止；可留空；支持跨夜 |
| `fitMeals` | string[] | `breakfast/lunch/tea/dinner/night` 多选 |
| `dayType` | string | `any` / `weekday` / `weekend` |
| `travelMode` | string | `walk` / `bike` / `car` / `''` |
| `travelMin` | number \| null | 手填耗时分钟，0–600 |
| `fromPlace` | string | 常用地点 id |
| `visitDate` | `YYYY-MM-DD` | 到访日期；非法值回退 `2000-01-01` |
| `people` | number | ≥1 |
| `totalPrice` | number \| null | 总价 |
| `perPrice` | number | 人均；为空由总价/人数推导 |
| `category` | string | 品类 / 菜系 |
| `rating` | number \| null | 原综合评分（兼容旧数据） |
| `ratings` | object | `{taste, env, service, value}`，0–5，0=未评 |
| `tags` | string[] | 口味 / 品类标签 |
| `mealType` | string | `breakfast/lunch/dinner/night/''` |
| `scene` | string | `daily/family/friends/team/birthday` |
| `note` | string | 备注 ≤300 字 |
| `photo` | string \| null | 旧版 base64（迁移后置空） |
| `photoId` | string \| null | IndexedDB 照片键 |
| `kcal` / `protein` | number \| null | 估算热量 / 蛋白质（非医疗建议） |
| `fitnessFriendly` | boolean | 健身友好标记 |
| `source` | string | `manual` / `daily` / `again` 等 |
| `createdAt` / `updatedAt` | ISO string | 时间戳 |

### 3.2 常用地点 `places`

```json
[{ "id": "p1", "name": "家", "isDefault": true },
 { "id": "p2", "name": "公司", "isDefault": false }]
```

- 首次迁移时种下「家 / 公司 / 学校」三条，默认「家」。
- 记录里的 `fromPlace` 存地点 id；删除地点后旧记录仍显示空，不崩。
- `settings.defaultPlace` 存默认地点 id；找不到时回退 `places[0]`。

### 3.3 根结构

```json
{
  "version": 3,
  "settings": {
    "city": "上海",
    "tasteTags": ["清淡"],
    "restrictions": ["香菜"],
    "fitnessGoal": "maintain",
    "lastNotified": "2026-11-05|dinner",
    "notifyDenied": false,
    "defaultPlace": "p1",
    "noRepeatWeek": false
  },
  "records": [],
  "blacklist": [],
  "wishlist": [],
  "votes": [],
  "places": []
}
```

聚合键：`keyOf = city.toLowerCase() + "|" + name.toLowerCase()`。

### 3.4 迁移与校验

- `migrate(raw)` 对每个字段做白名单与范围校验：`hhmm()` 校验 0–23/0–59 并补零；`km()` 限制 1–999；`fitMeals` 只保留五种枚举；`dayType/travelMode/mealType/scene` 不在枚举内回退默认值。
- `version` 统一写 3；旧数据缺字段时补默认，不丢原记录。
- 解析失败回退 `.bak`；迁移抛错保留 `.raw` 并以空库启动，页面不白屏。
- 写盘前与 `lastSavedStr` 比较，内容相同跳过；写入前旧值存 `.bak`。

---

## 4. 时间×距离推荐

### 4.1 时间上下文 `timeCtx()`

```
isWeekend = 周六/周日
minutes   = 当前小时*60 + 分钟
clock     = HH:MM
dayName   = 周几
```

测试钩子 `__fandaziTest.clock(h, m, weekend)` 可固定时间，便于验证 8:00 / 12:00 / 18:40 / 23:00。

### 4.2 营业判断 `isOpenAt(store, mins)`

```
未填起止 → null（不判定为打烊，仅提示“营业时间未填”）
e <= s   → 跨夜：mins >= s || mins < e   // 如 17:00–02:00
否则     → s <= mins < e
```

`minutesUntilOpen` 返回“还有多久开门”，用于空结果提示。

### 4.3 时段匹配

- 用户选择的时段（早/午/晚/夜）先过 `mealFit(category, meal)` 词表。
- 若记录填了 `fitMeals`：命中该时段 → `1`；未命中 → `0.3`；未填则沿用词表分。
- `weekFitOf`：`dayType=any` → `1`；标记周末且当前周末 → `1`，否则 `0.35`；标记工作日且当前工作日 → `1`，否则 `0.35`。
- 「现在吃什么」按当前小时把 `mealSeg` 切到对应时段，并打开「此刻营业」过滤。

### 4.4 新鲜度与“这周不重复”

- `novelty = clamp(days / 30, 0, 1)`：越久没去越高。
- `repeatPenalty(days)`：≤0.5 天 `0.25`；<2 天 `0.45`；<4 天 `0.65`；<8 天 `0.85`；否则 `1`；想去清单新店 `1`。
- 「这周不重复」：以**本地周一 00:00** 为界（`mondayStr()` 用本地日期，不用 UTC），`lastVisit >= 周一` 的店直接跳过。

### 4.5 距离与耗时

```
手填 distanceKm（km）
出行方式 walk/bike/car
若填了 travelMin → 直接使用
否则估算：
  walk: 12 分钟/km
  bike: 4 分钟/km
  car:  2.5 分钟/km + 3 分钟（取车/停车假设）
  travelMin = max(1, round(km × speed (+ 3 for car)))
```

- `distText`：小于 1 km 显示米，否则显示 km。
- `travelMinutesOf` 未填距离且未填分钟时返回 `null`，不做臆测。
- 所有界面文案均标注「手填估算」「不调用系统定位」。

### 4.6 综合评分 `dailyScore`

```
pf  = priceFitRange(人均, 预算下限, 上限)
q   = qualityOf(店)                 // 四维 + 综合分
tf  = tasteFit(店, 口味偏好)        // 0.55 + 0.45×命中占比
mf  = mealFit(品类, 时段)           // 词表
mealVal = fitMeals 命中 ? 1 : 0.3 ; 未填用 mf
nov = clamp(days/30, 0, 1)
trv = travelMin ? clamp(1 - travelMin/(travelMax||45), 0.15, 1) : 0.6
wk  = weekFitOf(店, 是否周末)

工作日权重：p .28  q .22  t .16  m .14  n .10  tr .08  wk .02
周末权重：  p .24  q .20  t .14  m .16  n .14  tr .08  wk .04

base  = 100 × (p·pf + q·q + t·tf + m·mealVal + n·nov + tr·trv + wk·wk)
score = round(base × repeatPenalty)
```

- 用户明确选中的时段权重更高（周末 `m` 从 .14 提到 .16，`n` 从 .10 提到 .14）。
- 距离权重固定 .08，避免“近但难吃”压倒一切；同时用硬筛选（半径/分钟）兜底。
- 想去清单的新店按 `q=0.55`、`nov=1`、`repeatPenalty=1` 参与。

### 4.7 筛选、排序与空结果

顺序：黑名单 → 忌口 → 预算 → 此刻营业 → 这周不重复 → 半径 → 分钟可达。

- 半径：`nearOnly` 开启时要求 `0 < distanceKm <= radius`；未填距离视为不满足。
- 分钟：`maxTravel` 开启时要求 `travelMinutesOf > 0 && <= maxTravel`。
- 排序：默认按 `score` 降序；「按距离排序」按 `distanceKm` 升序，距离缺失排末尾，再按分数。
- 空结果：`runDaily` 同时返回 `nearest`（最近耗时）、`nearestOpen`（最快开门）、`nearestKm`（最近距离）与 `skipped` 原因；UI 显示最近候选、开门时间、已开启筛选，并提供「一键放宽筛选」关闭全部过滤，不出现空白榜。

### 4.8 抽签 `runDraw`

```
w = 1 + 1.5×(rating/5 或 0.55) + 1.0×e^(-days/180)
```

- 过滤：黑名单、忌口、未填人均、区间外；可选固定「只看 3 km 内」与「X 分钟内可达」。
- 空集合 / 总权重 ≤0：直接返回提示，不进入抽样。
- 抽样：`r = random × Σw`，有界累加，命中第一个 `r < acc`；展示权重表、随机数，便于复现。
- 抽签结果为随机，不代表推荐排序（界面已注明）。

### 4.9 推荐理由

每条理由来自实际参与计算的字段，例如：

- `现在 18:40 营业中（17:00–02:00）`
- `距你约 500 m，骑行约 2 分钟（手填估算），约 公司 出发`
- `近 3 天吃过，降权（×0.65）`
- `你标记它适合晚餐`
- `你标记它更偏周末场景，当前不匹配（×0.35）`
- `很久没去（3 周），换个口味`

理由不足时回退为中性描述，不编造评分或距离。

---

## 5. 批量导出与分享

### 5.1 选择

- 记录页「批量」进入选择模式，卡片左上出现勾选框；再次点「完成」退出并清空。
- 「全选筛选结果」使用当前 `listState.items`；「全选全部」遍历 `db.records`；「清空」取消全部。
- 选择状态 `selectedIds` 以 id 为键，翻页后仍保留。

### 5.2 字段开关与隐私

| 开关 | 默认 | 说明 |
| --- | --- | --- |
| 私人备注 | **关闭** | 打开后 CSV/JSON/文本含 `note` |
| 照片 | **关闭** | JSON 内嵌压缩 dataURL；CSV 只标注「有（本机）」；长图/文本不含 |
| 价格 | 开启 | 人均、总价 |
| 评分 | 开启 | 综合评分；JSON 另含四维 |

导出弹窗固定提示：**「导出内容会离开本机，请确认是否含隐私信息；默认不含照片和私人备注。」**
每次导出末尾追加「由「饭搭子」本地生成于 …」与「数据来自本人记录，不代表平台评价」。

### 5.3 CSV

- `\uFEFF` BOM + `\r\n`，Excel 直接打开中文不乱码。
- 列顺序：店名、城市、商圈、地址、到访日期、时段、人数、品类、标签、距离km、出发地点、出行方式、耗时分钟、营业时间、适合时段、工作日周末、人均、总价、综合评分（备注可选）。
- 单元格转义：含逗号/引号/换行时用双引号包裹并转义。
- 末尾空行 + 生成说明 + 免责声明。

### 5.4 JSON

```json
{
  "应用": "饭搭子",
  "生成时间": "ISO",
  "说明": "由「饭搭子」在本机导出，未上传服务器；照片仅在本机 IndexedDB。",
  "免责声明": "数据来自本人记录，不代表平台评价",
  "字段选项": { "note": false, "photo": false, "price": true, "rating": true },
  "记录数": 3,
  "记录": [ { "店名": "...", "人均": 80, ... } ]
}
```

- 逐条 `setTimeout(0)` 组装，避免 1000 条同步阻塞。
- 照片仅在开关开启时异步读取 IndexedDB 并内嵌 dataURL；读取失败不影响其他字段。

### 5.5 文本清单

```
饭搭子 · 分享清单
共 N 家 · 生成 YYYY-MM-DD

1. 店名｜¥人均/人｜距离（手填）｜评分 X｜理由
...

由「饭搭子」在本机生成，未上传服务器。
数据来自本人记录，不代表平台评价。
```

- 支持一键复制；`navigator.share` 可用时走系统分享，取消/失败回退复制。

### 5.6 长图

| 项 | 值 |
| --- | --- |
| 宽度 | 1080 px |
| 每页条数 | 12 家 |
| 页高 | `232 + n×190 + (n-1)×20 + 176` |
| 页头 | 橙色渐变、标题、总数/日期/免责声明、页码 |
| 卡片 | 序号、店名（单行截断）、人均/人数/品类/距离/评分/日期、理由（单行截断） |
| 页脚 | 「数据来自本人记录，不代表平台评价」+ 本机生成/手填估算说明 |
| 生成 | 逐页 `toBlob`，blob 存入 `longBlobs`，canvas 立即 `width=0` 释放 |
| 进度 | `正在生成 x/y 页…`，完成后显示页数与用时 |
| 保存 | 逐张下载 PNG，间隔 650 ms，避免浏览器拦截连续下载 |
| 分享 | 最多 10 张 `File` 走 `navigator.share`，不支持则回退保存 |
| 关闭 | 撤销全部 `objectURL`、清空预览容器 |

1000 条 → 84 页，实测 2377 ms（<3 s），全程有进度提示，不需要额外分批按钮。

### 5.7 单张分享卡（A2）

- 1080×1350，本地 Canvas；页头渐变 + 品牌名；主体白卡；理由列表；底部水印与免责声明。
- 三种数据源：日常推荐（店名/人均/人数/理由）、抽签（结果与区间）、朋友推荐。
- 保存 PNG；`navigator.share({files})` 可用时系统分享，失败/取消回退下载；绘图异常回退复制文字。
- `drawWrapped` 单行截断加省略号，避免文字越界。

### 5.8 好友分享包与导入（B4）

#### 5.8.1 `.fanzi.json` 字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `format` | string | 是 | 固定 `fandazi-share` |
| `version` | number | 是 | 当前 `1`；大于当前版本拒绝导入 |
| `exportedAt` | ISO string | 是 | 生成时间 |
| `source` | string | 否 | 分享者昵称，缺省「好友」；导入后写入 `importedFrom` |
| `disclaimer` | string | 是 | 固定「数据来自对方记录，仅供参考」 |
| `records` | array | 是 | 记录数组，字段与数据模型 3.1 同名 |

记录默认只含公开字段（店名、城市、商圈、地址、日期、时段、人数、人均/总价、品类、标签、评分、距离、出行、营业/适合时段、工作日周末、四维评分、健身字段）。**默认不含 `note`、`photo`**；导出弹窗勾选后才写入。`photo` 为压缩 dataURL，导入后写入 IndexedDB 并清空内联字段，记录只保留 `photoId`。

#### 5.8.2 链接格式与长度

链接为 `当前页面地址#fanzi=<base64url(payload)>`，payload 使用数组压缩：

```json
{"f":"fz","v":1,"s":"好友","e":"ISO","r":[
  ["店名","城市","2026-11-01",2,88,"面食",4.2,"清淡|热闹"],
  ["店名2","城市","2026-11-02",3,66,"火锅",4.5,"辣"]
]}
```

数组顺序：`name, city, visitDate, people, perPrice, category, rating, tags(以|连接)`。二维码由内联实现生成：Byte 模式、纠错 L、自动版本、mask 0，Canvas 740×740 + 4 模块静区；不引外部库、不请求二维码服务。

| 限制 | 值 | 超限行为 |
| --- | --- | --- |
| 记录条数 | ≤ 80 | 返回「请改用 .fanzi.json 文件分享」 |
| 压缩后字符数 | ≤ 4096 | 同上 |
| 二维码容量 | v40-L 约 2953 字节 | 链接仍可复制，二维码区提示改用复制链接 |

#### 5.8.3 去重与冲突

去重键：**城市 + 店名 + 日期 + 人均**（字符串去首尾空格、店名/城市小写比较）。价格比较：

| 情况 | 判定 | 默认处理 |
| --- | --- | --- |
| 人均差 ≤ `max(双方人均)×10%` | 重复 `dup` | 跳过；可切换全局「用对方的覆盖」 |
| 同城市+店名+日期，但人均差 >10% | 冲突 `conflict` | 保留我的；可逐条或批量覆盖 |
| 无同键记录 | 新增 `new` | 直接写入 |

同一批内先写入的新记录也会加入去重池，避免包内重复条目被重复新增。

#### 5.8.4 导入流程与撤销

1. `parseImportPayload`：支持 `#fanzi=` 链接、新版包、旧版 `{记录:[...]}` / `{records:[...]}` / 纯数组；中文键映射（店名/城市/到访日期/时段/出行方式/适合时段/工作日周末/营业时间等）。
2. `normalizeImportedRecord` 走既有 `migrate`，缺失店名则丢弃；不信任来源 `photoId`。
3. `importClassify` 生成预览行；预览显示新增/重复/冲突、前 5 条差异，冲突列表可逐条选择。
4. 确认后按 40 条/批 `setTimeout(0)` 写入并显示进度；照片逐张 `photoPut`。
5. 新增记录生成新 id，写入 `importedFrom / importedAt / source='import'`；覆盖保留原 id 与 `createdAt`。
6. 导入前深拷贝 `db.records` 与新增照片 id 到内存快照 `importSnapshot`；「撤销」恢复整表并删除本次新增照片。快照仅本次打开有效。

错误处理：JSON 解析失败、链接解码失败、`format=fandazi-share` 缺版本/版本 >1、无记录、记录缺店名、文件 >10MB 均给出内联提示，不中断页面。所有解析与打包均在本机完成，无网络请求。

### 5.9 互导教程（v6）

#### 5.9.1 结构与入口

- 独立覆盖层：`#tutMask`（`.guide-mask`）内为 `#tutCard`、`#tutBody`，复用新手引导的 `.guide` 布局与安全区。
- 顶部 `#tutTabSend / #tutTabRecv` 切换角色；内容由 `tutHTML(role)` 生成，三步 + 4 条 FAQ，示意图为内联 SVG。
- 三个入口：
  1. 「我的 → 帮助 → 互导教程」→ 默认发起方（`tutDefaultRole`）。
  2. 分享弹窗底部「不知道怎么用？看教程」→ `openTutorial('send')`。
  3. 导入弹窗底部「不知道怎么用？看教程」→ `openTutorial('recv')`。
- 也支持从导入弹层已打开时由测试/外部入口推断：`tutDefaultRole()` 检测 `#importMask` 可见则返回 `recv`，否则 `send`。

#### 5.9.2 状态与触发标记

| 项 | 说明 |
| --- | --- |
| `tutOpenFlag` | 教程层是否打开；打开时 `body.style.overflow='hidden'`，关闭恢复 |
| `tutRole` | `send` / `recv`；切换只重绘 `#tutBody`，不触碰记录列表 |
| `tutHintKind` | 轻提示对应的角色，点击「看教程」直接定位 |
| `localStorage['fandazi.tut.hint']` | 首次展示轻提示即写入，关闭或点击教程后不再出现 |
| `tutPrevOverflow` | 关闭时恢复页面滚动状态 |

- 首次打开分享或导入弹窗，`showTutHint('send'/'recv')` 显示底部可关闭轻提示；关闭弹窗或点提示的叉号都会隐藏提示，标记已写入。
- 新手引导优先：`initGuide` 在 `#tutMask` 可见时不自动弹；`openTutorial` 在 `guideOpenFlag` 为真时拒绝打开并提示「先看完新手引导」，不会反向触发引导。
- 教程本身不写引导标记，关闭教程后引导状态保持原样。

#### 5.9.3 布局与交互约束

- `tutViewport()` 把 `visualViewport.height` 写入 `#tutMask` 的 `--guide-vh`，键盘弹起/矮视口时卡片随视口收缩。
- `.guide-body{min-height:0}` 让正文区域可收缩滚动；`.guide-head` 与 `.guide-privacy/.guide-actions` 使用不透明白底，避免正文透出到按钮区。
- 底部按钮含 `env(safe-area-inset-bottom)`；Esc、遮罩点击、关闭按钮、知道了均可关闭。
- 全静态 DOM 渲染，不读取记录、不发请求、不加载外部图片；1000 条记录下打开与切换角色均 <5 ms。

---

## 6. 提醒与通知合规

### 6.1 应用内提醒条

- 时段映射：5–9 早餐、10–14 午餐、15–20 晚餐、其余夜宵（按小时）。
- 若今天已有该时段 `mealType` 记录 → 不提醒。
- 最近一条记录距今 <3 小时 → 不提醒（刚记过不催）。
- 文案包含当前时间、该记哪顿、上次记录时间；可「去记录」或关闭。
- 关闭状态按 `今天|时段` 记住，同一天同顿不再弹。
- 纯前端计算，不使用服务端推送。

### 6.2 浏览器通知

| 规则 | 实现 |
| --- | --- |
| 不自动申请 | 只有点击「开启浏览器通知」才调用 `Notification.requestPermission()` |
| 拒绝后不再询问 | `permission === 'denied'` 或 `settings.notifyDenied` 时按钮禁用并说明 |
| 去重 | `settings.lastNotified` 存 `今天|时段`，同顿只发一次 |
| 环境不支持 | `file://`、iOS、部分浏览器显示「通知不可用」，提醒条仍可用 |
| 无服务端 | 不注册 Push、不请求订阅、不上报 token |
| 失败静默 | `Notification` 构造异常写错误日志，不影响页面 |

---

## 7. 手机端体验

- 底部固定 4 标签，`min-height: 54px`，拇指可达；主内容 `padding-bottom: calc(94px + env(safe-area-inset-bottom))`。
- 顶栏 `env(safe-area-inset-top)`；弹层底部 `env(safe-area-inset-bottom)`，适配刘海屏与手势条。
- 主操作「就吃这个」「再来一次」使用 `.btn-lg`，`min-height: 48px`、`font-size: 16px`。
- 键盘适配：监听 `visualViewport.resize`，把弹层底部上移；`focusin` 时把输入框滚入可视区。
- 视图切换 `.view` 180 ms 淡入位移过渡；`viewScroll` 保留各标签滚动位置。
- `haptic(pattern)` 在可用时调用 `navigator.vibrate`；不可用静默忽略。
- 无横向溢出（375×812 实测）。

---

## 8. 性能实测（2026-11-05）

环境：本机 Chrome headless + CDP；1000 条记录 / 60 家店；移动端 375×812、DPR2、6× CPU 降速；数据写入 localStorage 后重开页面。

| 项目 | 结果 |
| --- | --- |
| 启动（1000 条桌面） | boot ≈ 16 ms，导航 ≈ 28 ms |
| 搜索 / 决定 / 抽签（1000 条桌面） | 1.5 ms / 0.4 ms / 0.2 ms |
| 移动 6×（1000 条） | boot 约 53 ms，导航约 220 ms，决定约 2.9 ms，搜索约 11 ms |
| localStorage | 1000 条约 616 KB（不含照片） |
| 时段推荐 8:00 / 12:00 / 18:40 / 23:00 | 营业不符 0 家 |
| 距离筛选 0.5 / 3 / 10 km | 11 / 34 / 60 家，公里升序 |
| 分钟筛选 6 / 36 / 120 分钟 | 19 / 48 / 60 家 |
| 3 条导出 | CSV 395 B、JSON 2529 B、文本 267 字、长图 1 页 26 ms |
| 1000 条导出 | CSV 3 ms / 88 KB、文本 1 ms / 67 KB、长图 84 页 2377 ms（墙钟 2496 ms） |
| 分享卡 | 1080×1350 PNG 约 440 KB |
| PWA | manifest 0 错误、installability 0 错误、SW 已接管、离线刷新正常 |
| 移动四标签 | 1–13 ms，无横向溢出 |
| 好友互导（10 条） | 清空前后互导，条数 10，`importedFrom` 10 条；撤销后 0 条 |
| 重复导入 | 同包第二次 10 条全部 `dup`，写入 0 |
| 价格边界 | +8% → `dup`；+12% → `conflict` |
| 链接（20 条） | 1687 字符、二维码 137×137（v30）；与 Python qrcode 逐模块比对 0 差异 |
| 1000 条导入 | 解析 6 ms、写入 100 ms；预览与写入均有进度 |
| 移动导入预览 | 375×812 滚动区 1187 / 视口 715，按钮完整可见 |
| 互导教程（1000 条） | 打开 2.2 ms、切换角色 0.7 / 1.1 ms，均 <200 ms |
| 互导教程移动端 | 375×812 正文滚动区 663 / 视口 623；375×520 按钮仍在视口内 |
| 教程入口与提示 | 帮助入口默认发起方；分享/导入弹窗链接角色正确；轻提示仅首次出现 |
| 控制台 / 网络 | 无错误；0 个外部请求 |

> 真实低端机的内存、图片解码与浏览器差异会让数字更差；本表是本机可复现实测。

---

## 9. 已知限制

1. 距离、耗时、营业时间均为手填，可能与真实情况有偏差；不调用定位、不查实时营业状态。
2. 首次离线安装必须联网打开一次；`file://` 与 `http://局域网IP` 不支持 SW/安装。
3. iOS 不支持本项目使用的浏览器通知；应用内提醒条可用。
4. 数据只在本机，换浏览器/清缓存/换设备会丢；需定期导出 JSON。
5. 长图最多一次生成全部页并保存在内存 blob；1000 条约 84 页、几十 MB，低内存设备可能较慢。
6. JSON 含照片时体积会显著增大（压缩图 dataURL）。
7. 抽签距离筛选固定 3 km；日常推荐支持 0.5–10 km 半径。
8. 状态栏/通知与 PWA 行为受浏览器与系统版本影响，未覆盖所有机型。
9. 压测为本机 headless 结果，不代表所有低端机。
10. 链接分享依赖当前页面地址：`file://` 或 `127.0.0.1` 下的链接只适合本机/同路径场景；跨设备优先用 `.fanzi.json` 文件。链接二维码受 v40-L 容量限制，超长时只能复制链接。
11. 导入撤销快照保存在内存，刷新或关闭页面后失效；跨会话恢复请使用导出的 JSON 备份。
12. 冲突选项的「覆盖」会替换对方记录中的可见字段，但不会删除本机原有照片。
13. 互导教程为静态说明层，不直接替你勾选记录或执行分享/导入；轻提示只出现一次，清空 `localStorage['fandazi.tut.hint']` 或清除站点数据后会再次出现。

---

## 10. 验证与文件

### 10.1 自动化验证（本机已执行）

| 脚本 | 覆盖 | 结果 |
| --- | --- | --- |
| 语法提取 + `node --check` | 内联脚本 | 通过 |
| PWA 回归 | manifest、安装性、SW、缓存、离线、提醒、分享图下载 | 通过 |
| B 批回归 | 四时段、周末权重、不重复、0.5/3/10 km、分钟筛选、空结果、3 条与 1000 条导出、长图分页、移动四标签 | 通过 |
| B4 分享/导入 | 互导 10 条、重复导入、8%/12% 边界、20 条链接、81 条阈值、二维码矩阵比对、损坏 JSON/version 2/11MB、1000 条耗时、375×812 预览 | 通过 |
| 互导教程 | 帮助/分享/导入入口角色、首次提示与标记、引导冲突、1000 条耗时、375×812 与 375×520 布局、控制台/外部请求 | 通过 |
| 移动截图 | 375×812 推荐页/抽签页/导入预览/教程顶底部；375×520 矮视口 | 布局正常，无溢出 |

### 10.2 手工自测清单

- [x] 双击 `index.html` 全功能可用，无 PWA 报错
- [x] `python -m http.server` 下可安装、可离线刷新
- [x] 改系统时间到 8:00 / 12:00 / 18:40 / 23:00，推荐符合营业与时段
- [x] 0.5 / 3 / 10 km 筛选排序正确，空结果有提示与放宽按钮
- [x] 3 条与全部记录导出 CSV（Excel 中文正常）、JSON、文本、长图
- [x] 长图 1080 宽、12 家/页、文字不截断、页脚免责声明
- [x] 移动 375×812 四标签切换、安全区、键盘不遮挡
- [x] 通知仅点击申请，拒绝后不再弹
- [x] 1000 条下启动与批量导出无明显卡顿
- [x] 好友 A 导出 10 条 → 清空后导入，条数/标记正确；重复导入不新增
- [x] 8% 判重复、12% 判冲突；20 条链接可导入，81 条提示改用文件
- [x] 损坏 JSON / 错误版本 / 11MB 文件均有提示；旧版中文键 JSON 可识别
- [x] 375×812 导入预览可滚动，确认按钮不被遮挡
- [x] 「我的 → 帮助」与分享/导入弹窗入口打开教程，角色默认值正确
- [x] 首次打开分享/导入弹窗出现轻提示，关闭后刷新不再出现
- [x] 清除引导标记后先弹新手引导，教程被拒绝且不叠加；引导关闭后可正常看教程
- [x] 375×812 教程可滚动、FAQ 完整；375×520 矮视口按钮仍在安全区

### 10.3 文件

| 文件 | 作用 |
| --- | --- |
| `index.html` | v6 应用本体 |
| `manifest.webmanifest` | PWA 清单 |
| `sw.js` | Service Worker（v5 缓存） |
| `icons/icon-192.png` / `icons/icon-512.png` | 普通图标 |
| `icons/icon-192-maskable.png` / `icons/icon-512-maskable.png` | maskable 图标 |
| `_backups/index-v1.html` … `index-v7.html` | 历史版本备份（`.gitignore` 忽略，不入库） |
| `README.md` | 项目介绍、本地运行、部署与功能清单 |
| `docs/framework.md` | 本文件 |
| `docs/features.md` | 同类产品参考与差异 |
