# dev-labs

一组**动手型演练场**。每个都遵循同样的原则：

1. **容器隔离** —— 演练会主动制造故障（OOM、主线程阻塞、内存泄漏），
   全部跑在 Docker 容器里，由 cgroup 硬限制兜底，不碰宿主。
2. **可复现** —— 每个现象都能一键触发，参数可调，不是"看文档想象"。
3. **对照学习** —— 问题版和优化版并排，配「在工具里看哪里、应该看到什么」的观察清单。
4. **零魔法** —— 服务端代码短到你能一口气读完，没有框架黑盒。
5. **按难度分级** —— 初级 → 中级 → 高级 → 终极，顺着走，别跳级。
6. **主题跟随系统** —— 浅色 / 深色 / 跟随系统三态，默认跟随系统。
   颜色全部走 CSS 变量，`color-scheme` 也跟着设，滚动条和表单控件不会突兀。

| 演练场 | 练什么 | 入口 |
|---|---|---|
| [jvm-lab](jvm-lab/) | JVM 现象：OOM、GC 行为、死锁、堆/线程 dump 分析 | `cd jvm-lab && ./lab.sh start` |
| [web-perf-lab](web-perf-lab/) | Chrome DevTools：Network / Performance 面板实战、动态 JS 替换 | `cd web-perf-lab && ./lab.sh start` |

---

## jvm-lab

容器化的 JVM 现象复现环境。覆盖 **18 个现象**，全部可用一个按钮触发，
**按难度分四级**（初级建立直觉 → 中级学会用工具 → 高级理解实现 → 终极直面生产事故）：

- **初级**：堆 OOM、慢速占堆、栈溢出、CPU 打满
- **中级**：直接内存 OOM、线程数 OOM、死锁、渐进式缓存泄漏
- **高级**：Metaspace OOM、GC 空转、锁竞争现场、锁方式对比、连接池耗尽
- **终极**：线程池无界队列 OOM、拒绝策略对比、JIT 预热、JIT 去优化

另有一个**解读类**场景：`trigger gc-summary` 把真实 GC 日志解析成结论
（停顿分布 / 分配速率 / 规则诊断）—— 这是全场唯一"读证据、做决策"而非"制造问题"的场景。

带一个零依赖的 Web 仪表盘：实时曲线、cgroup 限制校验、
日志与快照面板（采集线程栈 / 生成堆快照 / 看 GC 日志 / 解读 GC 日志），
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

面向 Chrome DevTools 的实战演练场。**26 个场景，每个都有问题版 / 优化版两个独立页面**，
可以开两个标签页并排对比 Network 和 Performance 面板。

场景**按难度分四级**，建议从初级顺着往上走：

| 级别 | 练什么 | 个数 |
|---|---|---|
| **初级** | 看懂浏览器怎么加载一个页面 —— 请求排队、缓存、压缩、CLS、渲染阻塞、动画属性 | 6 |
| **中级** | 学会用面板定位 —— Timing 拆解、资源优先级、字体、事件节流、布局抖动、长任务、断点调试 | 8 |
| **高级** | 跨层推理、要改架构 —— CORS 预检、INP 交互延迟、Web Worker 卸载、虚拟滚动、Service Worker 缓存 | 5 |
| **终极** | 没有"改一行就好"的解法 —— 内存泄漏（Retainers 定位引用链）、Lighthouse 综合评分 | 2 |

每个场景页左侧都有一份「**观察清单**」：在哪个面板、点哪里、应该看到什么、
预期数字是多少。不是"自己摸索"，是"照着验证"。
页头的彩色徽章就是当前场景的难度级别。

> **CORS 预检**那个场景需要真跨源，**只能用 `http://localhost:8082` 打开** ——
> 公网入口的 Cloudflare Access 会 403 掉预检请求。详见 web-perf-lab 的「已知限制」。

```bash
cd web-perf-lab
./lab.sh start
./lab.sh ui       # 访问方式
./lab.sh routes   # 全部场景 URL（按难度分组）
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
