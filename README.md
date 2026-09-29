# dev-labs

一组**动手型演练场**。每个都遵循同样的原则：

1. **容器隔离** —— 演练会主动制造故障（OOM、主线程阻塞、内存泄漏），
   全部跑在 Docker 容器里，由 cgroup 硬限制兜底，不碰宿主。
2. **可复现** —— 每个现象都能一键触发，参数可调，不是"看文档想象"。
3. **对照学习** —— 问题版和优化版并排，配「在工具里看哪里、应该看到什么」的观察清单。
4. **零魔法** —— 服务端代码短到你能一口气读完，没有框架黑盒。

| 演练场 | 练什么 | 入口 |
|---|---|---|
| [jvm-lab](jvm-lab/) | JVM 现象：OOM、GC 行为、死锁、堆/线程 dump 分析 | `cd jvm-lab && ./lab.sh start` |
| [web-perf-lab](web-perf-lab/) | Chrome DevTools：Network / Performance 面板实战、动态 JS 替换 | `cd web-perf-lab && ./lab.sh start` |

---

## jvm-lab

容器化的 JVM 现象复现环境。覆盖 9 类现象，全部可用一个按钮触发：

堆 OOM、Metaspace OOM、直接内存 OOM、线程数 OOM、GC 空转、
栈溢出、死锁、CPU 空转、渐进式静态缓存泄漏。

带一个零依赖的 Web 仪表盘：实时曲线、cgroup 限制校验、
日志与快照面板（采集线程栈 / 生成堆快照 / 看 GC 日志），
**每个场景按钮悬停会浮出对应的核心源码**。

```bash
cd jvm-lab
./lab.sh start          # 启动
./lab.sh ui             # 打印仪表盘地址与访问方式
./lab.sh limits         # 校验四道隔离防线是否真的生效
./lab.sh trigger help   # 看全部场景
```

文档：[CHEATSHEET](jvm-lab/docs/CHEATSHEET.md) ·
[界面操作指南](jvm-lab/docs/UI-GUIDE.md) ·
[dump 分析](jvm-lab/docs/DUMP-ANALYSIS.md) ·
[公网暴露](jvm-lab/docs/EXPOSE-VIA-TUNNEL.md)

---

## web-perf-lab

面向 Chrome DevTools 的实战演练场。**15 个场景，每个都有问题版 / 优化版两个独立页面**，
可以开两个标签页并排对比 Network 和 Performance 面板。

每个场景页左侧都有一份「**观察清单**」：在哪个面板、点哪里、应该看到什么、
预期数字是多少。不是"自己摸索"，是"照着验证"。

**Network 面板（7 个）**：串行 vs 并行请求、缓存策略（强缓存 / 协商缓存 / 无缓存）、
响应压缩、渲染阻塞 JS、图片与 CLS、CORS 预检（**⚠ 仅本地可跑**，需跨源，
公网入口的 Access 会 403 掉预检）、慢 TTFB 与重定向链

**Performance 面板（5 个）**：长任务阻塞主线程、布局抖动（强制同步布局）、
未节流的事件监听、内存泄漏（Detached DOM）、`left/top` vs `transform` 动画

**JS 调试（2 个）**：动态 JS 替换（Local Overrides）、断点全家桶
（条件断点 / 日志点 / DOM 断点 / Fetch 断点 / 事件监听断点 / Blackboxing）

**Lighthouse 综合评分（1 个）**：整合测试 —— 一次踩满 Performance /
Accessibility / Best Practices / SEO 四类审计，看前面学的点怎么互相影响。

```bash
cd web-perf-lab
./lab.sh start
./lab.sh ui       # 访问方式
./lab.sh routes   # 全部场景 URL
```

服务端**零 npm 依赖**，只用 Node 内置模块 —— 演练场是拿来练 DevTools 的，
服务端越透明越好，你能一口气读完 `server.js`。

详见 [web-perf-lab/README.md](web-perf-lab/README.md)。

---

## 为什么每个演练场都强调容器隔离

这些演练场**故意**会打满 CPU、吃光内存、阻塞主线程。
如果直接在宿主机上跑，一次失控就可能把整台机器（连同上面其他服务）拖死。

所以每个演练场都显式设置了 `mem_limit` / `pids_limit` / `cpus`，
端口只绑 `127.0.0.1`，并提供 `./lab.sh limits` 这类命令
**从容器内读 cgroup 文件并逐项判定** —— 因为"配置写了"和"真的生效了"是两回事。
