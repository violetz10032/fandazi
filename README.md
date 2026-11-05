# 饭搭子 · fandazi

记录吃过的店，按场景、时间与距离决定下一顿。

单页应用，**零构建、零依赖、零 CDN、零后端**；所有记录、评分、照片只存在你自己的设备上，不抓取任何平台数据。

**在线使用**：<https://violetz10032.github.io/fandazi/>（HTTPS，可直接安装为桌面/手机应用）

---

## 是什么

吃饭这件事有三个高频问题：去过哪家、这次吃什么、一群人吃什么。饭搭子把它们放进一个本地应用：

- **记录**：店名、商圈、人均、人数、四维评分（口味 / 环境 / 服务 / 性价比）、口味标签、忌口、营业与适合时段、手填距离与出行耗时。
- **推荐**：日常（1 主推 + 2 备选，附中文理由）、聚会（2–3 个方案含总价与人均）、健身（按热量/蛋白目标汇总）、朋友（可复制推荐文案）。
- **抽签**：价格区间内按评分与新鲜度加权随机，权重表与随机数可复核。
- **分享**：1080×1350 分享图、长图、CSV/JSON/文本导出，以及与好友的 `.fanzi.json` 本地互导。

## 技术形态

| 项 | 说明 |
| --- | --- |
| 实现 | 原生 HTML + CSS + JavaScript，**全部内联在 `index.html`** 一个文件里 |
| 数据 | 结构化数据存 `localStorage`，照片压缩后存 `IndexedDB`，均在本机 |
| PWA | `manifest.webmanifest` + `sw.js`，仅缓存同源静态资源 |
| 网络 | 除用户主动点击的外链（美团 / 大众点评 / 高德 / 百度地图搜索）外，**0 个外部请求** |
| 定位 | 仅在用户点击「用当前位置」时取一次位置；有坐标的店按 Haversine 实算距离，无坐标仍用手填估算（见下文「定位与距离」） |
| 兼容 | v1/v2 旧数据自动迁移到当前数据版本（v3） |

## 本地运行

```bash
git clone https://github.com/violetz10032/fandazi.git
cd fandazi
python -m http.server 8765
# 打开 http://localhost:8765/index.html
```

三种打开方式的差别：

| 方式 | 能做什么 | 不能用什么 |
| --- | --- | --- |
| 双击 `index.html`（`file://`） | 记录、推荐、抽签、导出、分享图全部可用 | Service Worker、安装到桌面、浏览器通知 |
| 本地静态服务器（`http://localhost`） | 以上全部 + 安装 + 离线 + 更新提示 | — |
| HTTPS 静态托管（如 GitHub Pages） | 完整的 PWA，手机也可安装 | — |

> `http://` 局域网 IP（如 `http://192.168.x.x:8765`）不是安全上下文，浏览器会禁用 Service Worker 与安装入口。手机安装请用 HTTPS 部署。

## 部署为可安装应用（GitHub Pages）

仓库本身就是一个可直接发布的静态站点，无需构建：

1. 进入仓库 **Settings → Pages**；
2. Source 选择 **Deploy from a branch**，分支选 `main`、目录选 `/ (root)`，保存；
3. 等待约 1 分钟，访问 `https://<owner>.github.io/fandazi/`；
4. 地址栏出现安装图标（Chrome/Edge）或「安装应用」菜单项，点击即可安装；iPhone/iPad 用 Safari「分享 → 添加到主屏幕」。

本项目已按上述配置开启 Pages（`main` + `/ (root)`，legacy 构建），当前地址 <https://violetz10032.github.io/fandazi/>；后续往 `main` 推送会自动重新发布。

部署要点：

- **必须走 HTTPS**：Service Worker 只在安全上下文（`https://` 或 `localhost`）生效；`file://` 与 `http://` 下会自动降级，功能不受影响但无法安装/离线。
- 全部资源用相对路径（`./`），因此放在任意子路径下都能正常工作。
- `manifest.webmanifest` 应以 `application/manifest+json` 或 `application/json` 返回，`sw.js` 应返回 200。
- 换了域名或移动了图标目录后，若旧缓存未更新，可在「我的 → 诊断」或浏览器开发者工具里清除站点数据。

## Android APK

不想装浏览器扩展、或想有个桌面图标直接用，可以用 Actions 云端打出来的 APK。**构建在 GitHub 的免费 runner 上完成，本地不需要 JDK / Android SDK**。

### 拿现成的 APK

1. 打开 <https://github.com/violetz10032/fandazi/actions/workflows/android.yml>；
2. 选最近一次成功的运行，页面底部 **Artifacts → `fandazi-debug-<版本>-<提交短哈希>`**，下载解压得到 `app-debug.apk`（约 3.7 MB）；
3. 手机上传该文件，安装时系统会要求「允许来自此来源安装应用」（未知来源），同意后即可安装。

Artifact 只保留 **14 天**，过期后重新跑一次工作流即可。

### 触发构建

| 方式 | 说明 |
| --- | --- |
| 手动 | Actions → **Android APK** → **Run workflow**（分支 `main`） |
| 自动 | 推送到 `main` 且改动了 `android-app/**`、`index.html`、`manifest.webmanifest`、`sw.js`、`icons/**`、工作流文件本身 |

其他改动（文档、`docs/`）不会触发构建，避免每次提交都跑一遍。

### 工程结构

```
android-app/
├── capacitor.config.json     # appId com.fandazi.app，appName 饭搭子，webDir=www
├── package.json              # Capacitor 6.2.3 + @capacitor/filesystem/@capacitor/share 6.0.4
├── scripts/copy-www.js       # 从仓库根同步 index.html、manifest、sw.js、icons 到 www/
├── www/                      # WebView 加载的本地资产（由脚本同步，勿手改）
└── android/                  # Capacitor 生成的 Android 工程（Gradle + 原生壳）
```

`index.html` 仍然只有一份"源头"在仓库根，`www/` 里的副本在 CI 里用 `npm run sync:www` 重新生成，所以不必担心两边不同步。`node_modules/`、`android/build/`、`android/.gradle/` 都在 `.gitignore` 里。

本地想自己改原生壳、重新出包：

```bash
cd android-app
npm ci
npm run sync:android        # 同步 web 资产 + 生成 Capacitor 桥接代码
cd android && ./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

（这一步需要本机有 JDK 17 与 Android SDK；只想要 APK 的话用上面的 Actions 方式即可。）

### 签名与分发限制

- 当前是 **debug 签名**（Gradle 自动用 debug keystore 签名，`applicationId` = `com.fandazi.app`，`versionName` 1.0.1、`versionCode` 2）。
- debug APK **只能自己装**：需要手动允许「未知来源」，也不能上传 Google Play / 国内应用商店，部分国产 ROM 会额外提示风险。
- 真要正式发布，需要自己的 keystore + `release` 构建类型，并把 keystore 以 GitHub Secrets（base64）形式注入 CI，`./gradlew assembleRelease` 后再用 `apksigner` 校验；keystore 文件本身绝不能进仓库。

### 导出与分享（v1.0.1 起已支持）

APK 内接了 `@capacitor/filesystem` + `@capacitor/share`：**CSV / JSON / 文本导出、分享图、长图、好友 `.fanzi.json` 分享、巡店清单分享**都会先写入应用缓存目录（`Directory.Cache`），再调起系统分享面板；在面板里选「保存到文件 / 下载」即可落到 Android 下载目录，也可以直接发给微信等应用。

实现方式（`index.html` 里的“Capacitor 原生桥接”一段）：

- 能力探测用 `window.Capacitor.isNativePlatform() === true`，并确认 `Plugins.Filesystem`、`Plugins.Share` 存在；**不看 UA**。
- 原生分支：`Filesystem.writeFile({ directory:'CACHE' })` → `Filesystem.getUri()` → `Share.share({ files:[uri] })`；纯文本走 `Share.share({ text })`。
- 网页版分支：仍然是原来的 `<a download>` + Blob 与 `navigator.share`，**代码路径原样保留**，行为与 v1 一致。
- 用户取消分享面板（cancel）不报错也不弹失败提示；真失败会 toast 写明原因，并记到「我的 → 诊断」错误日志。

| 操作 | 网页版 / 在线地址 | APK（WebView） |
| --- | --- | --- |
| 导出 CSV / JSON | 浏览器直接下载到下载目录 | 分享面板 → 手动选「保存到文件/下载」 |
| 保存分享图 / 长图 | 直接下载 PNG | 分享面板 → 保存或直接发给别人 |
| 系统分享（清单/好友包） | `navigator.share`（手机浏览器支持） | Capacitor Share 原生分享面板 |
| 剪贴板复制 | Clipboard API / `execCommand` 兜底 | 同左（`https://localhost` 属安全上下文） |

### 运行形态与限制

- WebView 加载的是**打包在 APK 里的本地资源**（`assets/public`），首次打开不需要联网，数据同样只存在本机 WebView 的存储里。
- 已确认 APK 内包含 `assets/public/index.html`、`icons/`、`manifest.webmanifest`、`sw.js`；资源全部是 `./` 相对路径，因此在 `file://`、WebView 本地资源、GitHub Pages 子路径下都能找到图标与清单。
- FileProvider 用 `${applicationId}.fileprovider`（`com.fandazi.app.fileprovider`），`file_paths.xml` 已覆盖 `cache-path`、`files-path`、`external-path`，与 Share 插件读取的文件 URI 一致。
- Service Worker 在 Capacitor 默认的 `https://localhost` 源下是可选的：注册失败只会在「我的 → 诊断」里记一条日志，不会白屏（页面是纯静态资源）。
- **仍需真机确认的部分**：本机没有 Android SDK/模拟器，所以“导出→分享面板→保存到下载”这一条链路只做了静态检查与云端编译，**没有在真机点过按钮**。装机后请按下面的自测清单走一遍。
- APK 与在线 PWA 是**两份独立的数据**：APK 里的记录不会同步到网页版，反之亦然。想迁移就用「我的 → 数据 → 导出 JSON」再在另一侧导入。

### APK 自测清单（装机后 1 分钟）

1. 打开应用 →「我的」→「数据」→ **导出 JSON**：应弹出系统分享面板，里面能看到 `饭搭子-美食记录-<日期>.json`；
2. 在分享面板选「保存到文件」或「下载」→ 到文件管理器确认文件存在、用文本编辑器能打开；
3. 「我的」→「数据」→ **导出 CSV** → 同上，确认有中文表头（BOM 不应乱码）；
4. 「今天吃什么」→ **分享图** → 预览页点「保存」或「分享」→ 分享面板里有 PNG（1080×1350）；
5. 「巡店」→ **复制/分享清单** → 出现系统分享面板而不是只提示“已复制”；
6. 任意列表 → 导出弹层 → **分享给好友（.fanzi.json）** → 能调起分享；取消一次，应无报错提示。

任何一步失败，在「我的 → 诊断」里能看到对应的错误记录，把错误文案发回来即可定位。

## 定位与距离

距离有两种来源，界面始终分开标注，不混为一谈：

| 来源 | 什么时候有 | 界面写法 | 参与筛选 / 排序 |
| --- | --- | --- | --- |
| 定位计算 | 店铺记录里有经纬度，且你点过「用当前位置」 | `距我约 0.5 km（定位）` | 是（按坐标实算） |
| 手填估算 | 记录里填了「距离 km」，但没有店铺坐标 | `距我约 1.5 km（手填）` | 是（按手填值） |
| 都没有 | 既没坐标也没手填 | 不显示 / 「未填距离」 | 否 |

### 怎么用

1. 「今天吃什么」的筛选区点 **用当前位置** → 允许授权后显示 `已定位（±20m，17:53）· 有坐标的店按真实距离`；旁边的 **清除定位** 可恢复全部手填。
2. 记一餐 / 编辑记录时，在「经纬度 lng / lat」下方点 **用当前位置填入店铺坐标**。这里写入的是**店铺坐标**，不会覆盖「我的位置」缓存（否则距离基准会变成店铺自身）。
3. 有坐标的店用 Haversine（球面距离，R=6371.0088 km）实时计算，保留 1 位小数；没坐标的店继续用手填的 km，并标注「手填」。

### 权限与环境

| 环境 | 能否定位 | 行为 |
| --- | --- | --- |
| HTTPS（GitHub Pages） | 可以 | 首次点击弹系统授权 |
| `http://localhost` / `127.0.0.1` | 可以 | 本地调试等同安全上下文 |
| `http://` 局域网 IP | 不可以 | 中文提示「不是安全上下文」，回退手填，不白屏 |
| 双击 `file://` 打开 | 不可以 | 提示当前环境无定位接口，回退手填 |
| APK（Capacitor WebView） | **未真机验证** | WebView 可能无定位接口/权限；失败时提示并回退手填 |

- 只在点击按钮时取一次位置（`enableHighAccuracy:false`、`timeout:10s`），**不后台持续定位、不做轨迹**。
- 位置缓存在 `localStorage` 的 `fandazi.geo.last`（`{lat,lng,acc,at}`，坐标保留 5 位小数），只存本机、不写入记录、不上传。
- 拒绝授权（code 1）、超时（code 3）、定位不可用（code 2）、无定位接口都有对应中文提示，按钮会恢复可再次点击。
- 距离全部在本机计算，**不引入地图 SDK、不调第三方 API**；「打开平台 / 导航」仍是你主动点击的外链。
- 店铺坐标是记录的可选字段（`lat` / `lng`），旧数据不填也完全兼容，不需要迁移。

## 备份与恢复

「我的 → 数据」里有两类导出，用途不同：

| 方式 | 包含内容 | 适合 |
| --- | --- | --- |
| 导出 JSON / CSV | 记录字段，**不含照片** | 看数据、导表格、导入到其他工具 |
| **完整备份**（`.fandazi-backup.json`） | 全部记录 + 全部配置 + **全部照片** + 校验值 | 换机、清缓存前留底、整库还原 |
| 分享给好友（`.fanzi.json`） | 可分享的记录，默认不含照片与私人备注 | 给朋友推荐，不是备份 |

### 完整备份

1. 「我的 → 数据」→ **完整备份**，面板会先显示记录条数、照片张数与**预计体积**；
2. 点「生成并下载备份」，浏览器下载到下载目录；APK 内会调起系统分享面板，选「保存到文件」即可；
3. 生成后记下 `fandazi.backup.last`（时间、记录条数、文件大小），「我的 → 数据」会显示「上次备份：X 天前（N 条 / M.B）」；
4. 距上次备份超过 7 天，或自上次备份后新增/修改 ≥10 条记录，「我的」页顶部会出现一条备份提醒（复用应用内提醒条），点「去备份」直达面板。

文件结构（`schemaVersion` 与当前数据结构版本一致，本版为 3）：

```json
{
  "format": "fandazi-backup",
  "schemaVersion": 3,
  "exportedAt": "2026-11-05T...",
  "checksum": { "algo": "SHA-256", "scope": "canonical(payload+photos)", "value": "…64 位十六进制…" },
  "stats": { "records": 30, "photos": 5, "photoBytes": 29167, … },
  "payload": { "version": 3, "settings": {…}, "blacklist": [...], "wishlist": [...], "votes": [...], "places": [...], "localConfig": {…}, "records": [...] },
  "photos": [ { "id": "p1", "dataUrl": "data:image/jpeg;base64,…" } ]
}
```

- **校验值**：对「规范化 JSON（对象键排序）后的 payload + photos」计算 SHA-256。导入时先校验，失败直接拒绝，不会写入任何一条记录。Web Crypto 可用时优先用它，`http://` 局域网等非安全上下文自动退到内置纯 JS 实现，两种实现结果一致（已用 `abc` / 长文本做交叉测试）。
- **版本**：备份里的 `schemaVersion` 高于当前应用时拒绝并提示「请先升级应用」；低于当前时走应用已有的迁移路径（旧版记录会被补默认字段）。
- **体积代价**：照片按原来的压缩规则（每张 ≤360px、最多 80 张、总量 >4MB 提示）存成 data URL，base64 以文本内联进 JSON，因此备份体积 ≈ 照片字节数 + 记录文本；照片越多，生成与下载越慢、占用内存越多。预计体积超过 50 MB 时面板会提醒，但仍可继续。
- **照片**：备份会读 IndexedDB `fandazi.media/photos` 里的**全部**照片（包含已删除记录遗留的孤儿照片）；恢复时按原 `photoId` 映射回记录，已存在的同 id 照片不会重复覆盖。导入结果会报告恢复了多少张、多少字节，以及是否有记录引用了备份里不存在的照片。

### 恢复与恢复点

恢复入口是「我的 → 数据」→ **恢复完整备份**，选文件后会先做校验并展示摘要（备份时间、版本、记录/照片/配置条数、合并模式下的可新增/重复/冲突数量），再选择：

| 模式 | 行为 |
| --- | --- |
| 合并（默认） | 按现有规则去重：`城市 + 店名 + 日期` 相同即视为同一条，人均差 ≤10% 也归为同一条；只写入「可新增」，重复与冲突保留本机 |
| 完整替换 | 用备份内容替换当前全部记录与配置，还原备份原样（记录 id、时间、照片映射都保留） |

无论哪种模式，**导入前都会自动把当前数据存成本机恢复点**：

- 存在 IndexedDB `fandazi.media/snapshots`（与应用照片同一个库，只保留**最近 2 份**，超出自动删除最旧的一份）；
- 恢复点保存记录、偏好、黑名单、常用地点、想去、投票与引导/教程标记，照片**只存 id 引用，不重复占空间**；
- 恢复点不是长期存档：下一次导入或恢复会把它挤掉；如果导入后误删了照片，恢复点也找不回来（备份文件才是长期底稿）；
- 导入/恢复完成后，结果弹层里有「恢复到恢复前」；「我的 → 数据」也会列出最近 2 个恢复点，可单独恢复。

失败不会出现「导入一半、老数据没了」：流程是**先校验 → 写照片 → 内存里构建新数据 → 原子切换 localStorage（`fandazi.v1`）并回读校验**，任一步失败都会清理本次新写入的照片、保留原数据；失败提示会区分校验失败、版本过旧/过高、照片写入失败、空间不足。

### 常见错误处理

| 提示 | 原因 | 处理 |
| --- | --- | --- |
| 校验失败：文件内容与校验值不一致 | 文件被手改、下载不完整或传输损坏 | 换一份完好的备份；不要用文本编辑器改备份内容 |
| 不是有效的 JSON：文件可能被截断 | 文件只下载了一半 | 重新下载；确认文件以 `.fandazi-backup.json` 结尾且能完整打开 |
| 备份版本 X 高于当前应用支持的 3 | 用新版本应用备份、旧版本应用恢复 | 先升级应用（或换用新版 APK / 在线版）再恢复 |
| 备份文件缺少校验值 | 不是完整备份（可能是普通 JSON / 好友包） | 用「完整备份」生成的文件；好友包请走「导入好友分享」 |
| 照片写入失败 / 空间不足 | IndexedDB 空间不够、被其他标签页占用或无权限 | 清理浏览器存储、关闭其他标签页后重试；原数据不会被改动 |
| 恢复点不存在或已被覆盖 | 又做了一次导入，旧恢复点已被挤掉 | 用备份文件重新恢复；恢复点只保留最近 2 份 |

**未实现**：云备份 / 跨设备自动同步 / 备份加密与密码保护 / 定时自动备份；完整备份文件是本机明文 JSON，请自行保管。恢复点同样只在本机，清浏览器数据会一起消失。

## 功能清单

| 能力 | 状态 |
| --- | --- |
| 记录增删改查 + 搜索 / 筛选 / 排序 + 分页 | 完成 |
| 四维评分（兼容旧综合分） | 完成 |
| 口味标签 / 忌口 / 黑名单 / 想去 | 完成 |
| 每天吃什么（1 主 2 备 + 一键记一餐） | 完成 |
| 聚会方案 + 本机投票清单 | 完成 |
| 健身目标 + 今日汇总 + 免责声明 | 完成 |
| 时间推荐（营业时段 / 适合时段 / 工作日与周末 / 新鲜度） | 完成 |
| 距离推荐（真实定位 / 手填距离 / 出行耗时 / 半径与分钟筛选） | 完成 |
| 抽签（加权随机，权重可复核） | 完成 |
| 月度统计 / 场景分布 / 日历 | 完成 |
| 分享图（1080×1350）与分享长图 | 完成 |
| 批量导出 CSV / JSON / 文本清单 / 长图 | 完成 |
| 完整备份 `.fandazi-backup.json`（含照片 / SHA-256 / 恢复点 / 版本迁移） | 完成 |
| 好友分享 `.fanzi.json` / 链接 / 二维码，含预览去重与一次性撤销 | 完成 |
| PWA 安装 / 离线缓存 / 更新提示 | 完成 |
| 云同步 / 地图选点 / 多人实时投票 | 未实现（需要后端，当前保持纯本地） |

更细的功能对照与设计取舍见 [docs/features.md](docs/features.md)，架构、算法与实测数据见 [docs/framework.md](docs/framework.md)。

## 目录结构

```
fandazi/
├── index.html                # 应用本体（内联 CSS/JS）
├── manifest.webmanifest      # PWA 清单
├── sw.js                     # Service Worker（同源预缓存）
├── icons/
│   ├── icon-192.png
│   ├── icon-512.png
│   ├── icon-192-maskable.png
│   └── icon-512-maskable.png
├── docs/
│   ├── framework.md          # 框架方案、算法、实测
│   └── features.md           # 同类产品参考点与差异
├── android-app/              # Capacitor Android 工程（见上文「Android APK」）
│   ├── capacitor.config.json
│   ├── package.json
│   ├── scripts/copy-www.js
│   ├── www/                  # 从根目录同步的 web 资产
│   └── android/              # Gradle 工程
├── .github/workflows/android.yml  # 云端构建 APK
├── LICENSE
└── README.md
```

历史版本（`index-v1.html` … `index-v7.html`）不进入仓库，本地保留在 `_backups/`（已在 `.gitignore` 中忽略）。版本演进请看 `git log`。

## 数据与隐私

- 记录存 `localStorage`（键 `fandazi.v1`），照片与最近 2 份恢复点存 `IndexedDB`（`fandazi.media`），**不上传、不注册账号、无后端**。
- 数据只在这台设备的这个浏览器里：换浏览器、清缓存、换设备都会丢，建议定期「我的 → 数据 → 完整备份」（含照片）。
- 应用不抓取任何平台数据；外部链接仅在用户主动点击时跳转，且不带渠道参数。
- 营养与热量为历史记录估算，**不构成营养或医疗建议**。
- 导出与好友分享会把数据带出本机，导出前请确认是否包含隐私内容；分享包默认不含照片与私人备注。

## 已知限制

1. 出行耗时、营业时间仍为手填；距离对「有坐标 + 点过定位」的店是实算，其余仍是手填估算，可能与实际有偏差。定位只在点击时取一次，不查实时营业状态。
2. 首次离线使用必须先联网打开一次（Service Worker 需要一次在线安装）。
3. iOS 不支持本项目使用的浏览器通知，应用内提醒条仍可用。
4. 无后端，因此没有多人实时投票、云同步、跨设备自动同步。
5. JSON 含照片时体积显著增大；1000 条记录导出长图约 84 页，低内存设备可能较慢。完整备份把照片内联为 base64，50 MB 以上会明显变慢。
6. 完整备份与恢复点均在本地：没有云备份、跨设备自动同步或备份加密；恢复点只保留最近 2 份。

## 许可

[MIT](LICENSE)
