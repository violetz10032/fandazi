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
| 定位 | **不申请定位权限**，距离与耗时均为手填估算 |
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
| 距离推荐（手填距离 / 出行耗时 / 半径与分钟筛选） | 完成 |
| 抽签（加权随机，权重可复核） | 完成 |
| 月度统计 / 场景分布 / 日历 | 完成 |
| 分享图（1080×1350）与分享长图 | 完成 |
| 批量导出 CSV / JSON / 文本清单 / 长图 | 完成 |
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

- 记录存 `localStorage`（键 `fandazi.v1`），照片存 `IndexedDB`（`fandazi.media`），**不上传、不注册账号、无后端**。
- 数据只在这台设备的这个浏览器里：换浏览器、清缓存、换设备都会丢，建议定期「我的 → 数据 → 导出 JSON」备份。
- 应用不抓取任何平台数据；外部链接仅在用户主动点击时跳转，且不带渠道参数。
- 营养与热量为历史记录估算，**不构成营养或医疗建议**。
- 导出与好友分享会把数据带出本机，导出前请确认是否包含隐私内容；分享包默认不含照片与私人备注。

## 已知限制

1. 距离、耗时、营业时间均为手填，可能与实际有偏差；不调用定位、不查实时营业状态。
2. 首次离线使用必须先联网打开一次（Service Worker 需要一次在线安装）。
3. iOS 不支持本项目使用的浏览器通知，应用内提醒条仍可用。
4. 无后端，因此没有多人实时投票、云同步、跨设备自动同步。
5. JSON 含照片时体积显著增大；1000 条记录导出长图约 84 页，低内存设备可能较慢。

## 许可

[MIT](LICENSE)
