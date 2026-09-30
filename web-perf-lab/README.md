# web-perf-lab

面向 **Chrome DevTools** 的实战演练场。21 个场景，按
**初级 → 中级 → 高级 → 终极** 四级排列，每个都有**问题版 / 优化版**
两个独立页面 —— 开两个标签页并排对比 Network 和 Performance 面板。

## 快速开始

```bash
./lab.sh start       # 构建并启动（容器，端口 127.0.0.1:8082）
./lab.sh ui          # 打印访问方式（含 SSH 隧道命令）
./lab.sh routes      # 列出全部场景 URL
./lab.sh limits      # 校验隔离边界
./lab.sh stop
```

浏览器打开 <http://localhost:8082/>。

**开始之前先做三件事**：
1. 按 `F12` 打开 DevTools
2. 切到 **Network** 面板，勾上 **Disable cache**（否则缓存场景看不出来）
3. 每次测完按 `Ctrl+Shift+R` 硬刷新

## 场景清单

21 个场景按难度分四级。**建议从初级顺着往上走** ——
每一级都在用前一级的技能，跳着看容易变成"照着点一遍但没懂"。

### 初级 · 看懂浏览器怎么加载一个页面（6 个）

现象肉眼可见，不需要会看 Performance 面板。

| 场景 | 练什么 |
|---|---|
| 串行 vs 并行请求 | Waterfall 阶梯、`Stalled` 长不等于服务器慢 |
| 缓存策略 | 强缓存 / 协商缓存 / 无缓存；304 为什么没有 body |
| 响应压缩 | Size 列两个数字怎么读；`Vary` 头漏了会出事故 |
| 图片与 CLS | 宽高缺失导致布局偏移；懒加载与 LCP 优先级 |
| 渲染阻塞 JS | 同步 script 导致白屏；`defer` 只解决白屏不解决卡 |
| 动画属性选择 | `left/top` vs `transform`；合成层与 `will-change` |

### 中级 · 学会用面板定位问题（8 个）

要会用 Network 的 Timing、Performance 的火焰图，但因果链是单向的。

| 场景 | 练什么 |
|---|---|
| 慢 TTFB 与重定向链 | Timing 面板拆解；重定向为什么贵 |
| 资源优先级 | `loading="lazy"` 用错地方；preload / fetchpriority / preconnect |
| 字体加载 | 字体为什么"发现得晚"；FOIT vs FOUT；`font-display` 五个值 |
| 未节流的事件监听 | scroll 触发次数远高于渲染帧；rAF 节流 |
| 布局抖动 | 读写交替导致强制同步布局；批量读 → 批量写 |
| 长任务阻塞主线程 | Main 轨道红色三角；切片让出 vs Web Worker |
| **动态 JS 替换** | Sources → Overrides，改线上 JS 即时生效、刷新保留 |
| **断点全家桶** | 条件断点、日志点、DOM 断点、Fetch 断点、事件监听断点、Blackboxing |

### 高级 · 跨层推理，要改架构（5 个）

要同时考虑网络、渲染、线程、缓存几层，解法往往不是"改一行"。

| 场景 | 练什么 |
|---|---|
| CORS 预检 | 什么时候触发 OPTIONS；`Max-Age` 的作用。**⚠ 仅本地可跑** |
| 交互延迟 INP | 主线程忙 → 输入排队；`scheduler.yield` 分片让出 |
| 主线程卸载 | Web Worker 的四个硬约束；结构化克隆的成本 |
| 长列表渲染 | 虚拟滚动：复杂度从 `O(n)` 降到 `O(视口)`；`translateY` vs `top` |
| Service Worker 缓存 | SW 透传 = 白装；stale-while-revalidate；缓存版本号 |

### 终极 · 没有「改一行就好」的解法（2 个）

| 场景 | 练什么 |
|---|---|
| 内存泄漏 | Detached DOM；**Retainers** 面板定位引用链 |
| Lighthouse 综合评分 | **整合测试** —— 一次踩满四类审计，看前面学的东西怎么互相影响 |

前面 20 个场景各练**一个**技术点，Lighthouse 是**综合评分**：
它把几十项审计加权算成 Performance / Accessibility / Best Practices / SEO
四个分数，正好用来验证你是不是真的把前面那些点串起来了。

问题版故意踩的坑（每一项都对应报告里一个具体审计条目）：

| 分类 | 故意制造的失败 |
|---|---|
| Performance | 渲染阻塞 script + 45KB 无用 CSS + 60KB 死代码 JS + 2.4MB 未优化首屏图 + 无缓存头 + 1200 个冗余 DOM 节点 |
| Accessibility | 无 `lang`、图片无 `alt`、表单无 `label`、按钮无名称、标题跳级、对比度 3.3:1、字号 10px、链接文字无意义 |
| Best Practices | console 报错、使用已废弃 API、图片显示比例与固有比例不符 |
| SEO | 无 meta description、链接不可爬取 |

**这个场景的页面形态和前面不同**：bad/good 是**干净的被测页面**，
不带侧边观察清单 —— 清单本身会塞 DOM 节点、加文本、可能引入对比度问题，
**会污染 Lighthouse 的评分**。所以指南单独放在 `/lighthouse/guide`，
页面右下角有个小浮层跳过去。

跑法：`F12` → **Lighthouse** 标签 → 勾四个分类 → Analyze page load。
**务必用无痕窗口**（扩展会拖慢加载，分数失真），同一个页面多跑几次取中位数。

> 具体分数取决于机器负载，以实测为准。但这个差距一定存在：
> 问题版 Performance 会低于 50，优化版应该到 90+。

## 每个场景页的结构

```
┌──────────────────────────────────────────────────────┐
│ ← 索引   [问题版]   场景名        看优化版 →          │
├──────────────────┬───────────────────────────────────┤
│ DevTools         │                                   │
│ 观察清单          │        演示区                      │
│                  │                                   │
│ 1. 打开 Network   │   （按钮 / 可交互的演示）           │
│ 2. 勾 Disable...  │                                   │
│ 3. 看 Waterfall   │                                   │
│                  │                                   │
│ ┌──────────────┐ │                                   │
│ │ 预期：...     │ │                                   │
│ └──────────────┘ │                                   │
│ ┌──────────────┐ │                                   │
│ │ ⚠ 坑点说明    │ │                                   │
│ └──────────────┘ │                                   │
└──────────────────┴───────────────────────────────────┘
```

**左侧「观察清单」是核心** —— 它告诉你：在哪个面板、点哪里、应该看到什么。
不是"自己摸索"，是"照着验证"。

## 服务端设计

**零 npm 依赖**，只用 Node 内置模块（`http` / `fs` / `zlib`）。理由：

- 演练场是拿来练 DevTools 的，服务端越透明越好 —— 你能一口气读完 `server.js`
- 没有 `npm install`，构建只有 `COPY`
- 没有供应链风险

### 路由

| 路径 | 用途 |
|---|---|
| `/{network\|performance\|debug\|lighthouse\|cache}/<场景>/<bad\|good>` | 场景页 |
| `/assets/{nocache\|cached\|etag}/{文件}` | 三种缓存策略对照 |
| `/assets/{nogzip\|gzip}/{文件}` | 压缩对照（300KB 脚本） |
| `/assets/lh/theme-bad.css?kb=N` | 动态生成的"大块未使用 CSS"（默认 45KB） |
| `/assets/lh/app-bad.js?kb=N` | 动态生成的"大块死代码 JS"（默认 60KB） |
| `/assets/lh/block-bad.js?ms=N` | 渲染阻塞 + 长任务脚本 |
| `/api/slow?ms=N` | 延迟响应（拖 TTFB） |
| `/api/chain?n=N` | N 次重定向 |
| `/api/item/:id` | 300ms 延迟的小 JSON（瀑布流用） |
| `/api/cors/simple` | 简单请求（不触发预检） |
| `/api/cors/preflight` | 带自定义头（触发预检） |
| `/api/image?w=&h=&noise=1&delay=&nocache=1` | 动态生成 PNG |
| `/api/items?n=N` | 长列表数据（虚拟滚动用） |
| `/api/font?delay=N&kb=N` | 延迟返回的**占位字体**（演示 FOIT/FOUT） |
| `/cache/sw/{bad\|good}/sw.js` | Service Worker 脚本。**作用域 = 脚本所在目录**，所以限在 `/cache/sw/<mode>/` 内，不污染其他场景 |
| `/cache/sw/{bad\|good}/data.json?ms=N` | SW 场景的数据源（延迟可调） |
### 难度分级是怎么实现的

21 个场景分四级，但**等级没有写进任何场景页** ——
`assets/lab.js` 里有一张 `LAB_LEVELS` 表，页面加载时按 `location.pathname`
查出等级，自动往页头插一个彩色徽章。

这样调整分级只改一处，不用动 42 个 HTML 文件。

## 主题

支持**浅色 / 深色 / 跟随系统**三态，默认跟随系统。

- 所有颜色都是 CSS 变量，两套主题共用同一组变量名，组件不写死颜色
- 实际主题解析成 `light`/`dark` 写在 `<html data-theme>` 上，
  同时设置 `color-scheme`（滚动条、表单控件才会跟着变）
- 偏好存 `localStorage`，刷新后保持；选「跟随系统」时会实时响应系统切换

**引导脚本必须内联在 `<head>` 里、同步执行**（每个页面的样式表之后）：

```html
<script>
(function () {
  var pref = 'auto';
  try { pref = localStorage.getItem('lab-theme') || 'auto'; } catch (e) {}
  var mq = window.matchMedia('(prefers-color-scheme: dark)');
  document.documentElement.dataset.theme = pref === 'auto' ? (mq.matches ? 'dark' : 'light') : pref;
  document.documentElement.dataset.themePref = pref;
})();
</script>
```

放到 `lab.js`（在 body 末尾）里会**先按系统主题画一帧再切**，出现明显闪烁（FOUC）。
这是主题方案里最容易漏的一步。

切换控件由 `lab.js` 注入：场景页插进 `.lab-header` 最右边，
索引页插进 hero 里的 `#themeSlot`。

> 例外：`lighthouse/audit-{bad,good}.html` **故意不加载 lab.css、也不注入主题脚本** ——
> 它们是给 Lighthouse 打分的干净被测页面，多一个脚本或一条样式都会影响评分。

### 路由的返回值约定（踩过坑）

`serveAssetVariant` / `serveLighthouseAsset` / `serveScenario` 三个函数
**处理了就返回 `true`，没匹配上返回 `false`**。调用方是：

```js
if (serveAssetVariant(req, res, p)) return;
if (serveLighthouseAsset(req, res, p)) return;
if (serveScenario(res, p)) return;
// 都没匹配上 → 走静态兜底
```

加 Lighthouse 时踩过一次：写成 `return send(res, 200, ...)` ——
`send()` 没有返回值，函数返回 `undefined`，调用方判断为假，
于是继续往下走到静态兜底**又发了一次响应**，
触发 `ERR_HTTP_HEADERS_SENT`，**整个 Node 进程直接退出**。

`send()` 里现在有一道 `res.headersSent` 兜底，但根本解法是**遵守返回值约定**。

### 为什么图片是动态生成的

图片场景需要一张"大图"来演示体积和 CLS，但把二进制图片提交进 git 很脏。
所以服务端自己实现了一个**最小 PNG 编码器**（`makePng()`），参数可调：

```
/api/image?w=400&h=300           →  1.7 KB   （平滑渐变 + Up 滤波）
/api/image?w=400&h=300&noise=1   →  360 KB   （随机噪声，压不动）
```

同样尺寸、同样是 PNG，**差 200 倍** —— 这比"PNG vs WebP"更能说明
「内容决定体积」这件事。

## 隔离边界

和 `jvm-lab` 一样，这个演练场里的页面是**故意**做慢、做卡、泄漏内存的，
所以默认只在容器里跑：

| 防线 | 配置 |
|---|---|
| 内存 | `mem_limit: 512m` + `memswap_limit: 512m`（禁 swap） |
| 进程数 | `pids_limit: 128` |
| CPU | `cpus: "1.0"` |
| 端口 | 只绑 `127.0.0.1:8082` |
| 权限 | `cap_drop: ALL` + `read_only` + `no-new-privileges` + 非 root 用户 |

`./lab.sh limits` 从容器内读 cgroup 文件并**逐项判定** ——
配置写了不等于内核生效了。

## 怎么用它练到"大神"

按这个顺序走，每个场景 15~20 分钟：

1. **先看问题版**，把观察清单走一遍，确认你能看到那个现象
2. **猜一下原因**，写在纸上
3. **切到优化版**，看差别，验证你的猜测
4. **看页面右侧的代码对比**，理解改动为什么有效
5. **回到问题版，用 Performance 面板录制**，亲自找到那个慢的地方
6. **打开 DevTools 的 Layers / Rendering 面板**，看看更底层的表现

进阶玩法：

- **Lighthouse 打分**：对每个 good 版跑一次，看四项指标
- **弱网模拟**：Network 面板的 throttling 选 Slow 3G，重跑一遍所有场景
- **CPU 降速**：Performance 面板的 CPU 选 4x/6x slowdown，
  让长任务和布局抖动更明显
- **真机调试**：用 `chrome://inspect` 连手机，看移动端的真实表现

## 已知限制

- **CORS 场景只能在本地跑**。预检只在**跨源**时发生，所以页面必须从
  `localhost` 打 `127.0.0.1`（同机同端口，但 host 不同 = 不同 origin）。
  两个场景页都用 `labTwinOrigin()` 算这个兄弟源。

  **不要用公网域名打开** —— 公网入口挂了 Cloudflare Access，
  而浏览器发预检时不带 Access 的 cookie，会被 **403** 挡掉，
  Network 里一条 OPTIONS 都看不到。页面检测到非本地访问时会禁用按钮并给出隧道命令。
  正确姿势：

  ```bash
  ssh -L 8082:127.0.0.1:8082 root@<IP>
  # 浏览器打开 http://localhost:8082/network/cors/bad
  ```
- **内存泄漏场景**：`performance.memory` 是 Chrome 专有 API，
  Firefox / Safari 上显示不出来。堆快照对比只在 Chrome 有效。
- **Worker 场景**：部分严格的 CSP 环境会拦 Worker，这里没设 CSP，可以正常跑。
- **大文件下载**：不涉及。图片最大也就几百 KB。
