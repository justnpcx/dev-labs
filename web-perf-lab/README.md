# web-perf-lab

面向 **Chrome DevTools** 的实战演练场。14 个场景，每个都有**问题版 / 优化版**
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

### Network 面板（7 个）

| 场景 | 练什么 |
|---|---|
| 串行 vs 并行请求 | Waterfall 阶梯、`Stalled` 长不等于服务器慢 |
| 缓存策略 | 强缓存 / 协商缓存 / 无缓存；304 为什么没有 body |
| 响应压缩 | Size 列两个数字怎么读；`Vary` 头漏了会出事故 |
| 渲染阻塞 JS | 同步 script 导致白屏；`defer` 只解决白屏不解决卡 |
| 图片与 CLS | 宽高缺失导致布局偏移；懒加载与 LCP 优先级 |
| CORS 预检 | 什么时候触发 OPTIONS；`Max-Age` 的作用 |
| 慢 TTFB 与重定向链 | Timing 面板拆解；重定向为什么贵 |

### Performance 面板（5 个）

| 场景 | 练什么 |
|---|---|
| 长任务阻塞主线程 | Main 轨道红色三角；切片让出 vs Web Worker |
| 布局抖动 | 读写交替导致强制同步布局；批量读 → 批量写 |
| 未节流的事件监听 | scroll 触发次数远高于渲染帧；rAF 节流 |
| 内存泄漏 | Detached DOM；Retainers 面板定位引用链 |
| 动画属性选择 | `left/top` vs `transform`；合成层与 `will-change` |

### JS 调试（2 个）

| 场景 | 练什么 |
|---|---|
| **动态 JS 替换** | Sources → Overrides（Local Overrides），改线上 JS 即时生效、刷新保留 |
| **断点全家桶** | 条件断点、日志点、DOM 断点、Fetch 断点、事件监听断点、Blackboxing |

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
| `/{network\|performance\|debug}/<场景>/<bad\|good>` | 场景页 |
| `/assets/{nocache\|cached\|etag}/{文件}` | 三种缓存策略对照 |
| `/assets/{nogzip\|gzip}/{文件}` | 压缩对照（300KB 脚本） |
| `/api/slow?ms=N` | 延迟响应（拖 TTFB） |
| `/api/chain?n=N` | N 次重定向 |
| `/api/item/:id` | 300ms 延迟的小 JSON（瀑布流用） |
| `/api/cors/simple` | 简单请求（不触发预检） |
| `/api/cors/preflight` | 带自定义头（触发预检） |
| `/api/image?w=&h=&noise=1` | 动态生成 PNG |

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

- **CORS 场景**：同源页面上的跨域请求需要另一个源。
  当前用自定义头触发预检来演示，效果等价但不是真正的跨域。
- **内存泄漏场景**：`performance.memory` 是 Chrome 专有 API，
  Firefox / Safari 上显示不出来。堆快照对比只在 Chrome 有效。
- **Worker 场景**：部分严格的 CSP 环境会拦 Worker，这里没设 CSP，可以正常跑。
- **大文件下载**：不涉及。图片最大也就几百 KB。
