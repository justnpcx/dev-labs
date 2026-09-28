# JVM 演练场 · 速查手册

## 0. 隔离边界（先看这个）

这个演练场会**主动制造 OOM、死锁、CPU 打满**，所以默认只在容器里跑。
四道防线全部由 cgroup 强制执行，改 JVM 参数也越不出去：

| 防线 | 配置 | 挡住什么 |
|---|---|---|
| 内存 | `mem_limit: 2g` + `memswap_limit: 2g` | 禁止用 swap 兜底。超限时是「容器内进程被杀」，而不是「整机换页卡死」 |
| 进程数 | `pids_limit: 320` | `/oom/threads` 撞的是容器自己的限制，碰不到宿主 `ulimit -u` |
| CPU | `cpus: "2.0"` | `/cpu/spin` 最多吃 2 个核，宿主还剩 2 个 |
| 端口 | `127.0.0.1:8081:8080` | 不对外网暴露 |
| 磁盘 | 容器日志 `max-size: 10m` × 3 | 防止日志把宿主磁盘写满 |
| 权限 | `cap_drop: ALL` + `read_only` + `no-new-privileges` | 缩小影响面 |

### 用 `./lab.sh limits` 校验（不要只看配置文件）

**配置写了不等于生效。** `./lab.sh limits` 会从容器内读 cgroup 文件并**逐项判定**，
返回非 0 表示有防线没生效：

```
=== 隔离边界校验（从容器内读 cgroup 并判定）===
  ✓ 内存上限          2048 MB
  ✓ swap 已禁用       memory.swap.max = 0
  ✓ 进程数上限        320
  ✓ CPU 配额          200000 100000

  四道防线全部生效。
```

`./lab.sh start` 和 `./lab.sh status` 也会自动跑这个校验。

**为什么必须做判定而不能只打印**：实测遇到过一次 `memory.swap.max` 变成了 `max`
（意味着容器可以拿宿主的 swap，超限时会拖慢整机而不是干脆被杀），
而 `docker-compose.yml` 的配置和 `docker inspect` 都显示正常 ——
只有读 cgroup 才能发现。原因是 compose `down` 有时不会真正删掉容器，
下次 `up` 会复用旧配置。修法：

```bash
docker compose up -d --force-recreate
```

> `./lab.sh host-start` 是绕过容器的逃生舱，需二次确认。**日常不要用。**

---

## 1. 快速开始

### 仪表盘（浏览器）

端口只绑 `127.0.0.1`，从你自己的电脑访问需要过 SSH 隧道：

```bash
# 在你自己的电脑上执行
ssh -L 8081:127.0.0.1:8081 root@<这台机器的IP>
# 然后浏览器打开 http://localhost:8081/
```

`./lab.sh ui` 会把上面这条命令直接打出来（含 IP）。

页面上有：堆 / 元空间 / 直接内存的实时曲线、线程数、GC 次数、**容器 cgroup 限制**、
一键触发各场景的按钮，以及**「日志与快照」面板**：

| 面板能做的事 | 说明 |
|---|---|
| 列出 `logs/` 和 `dumps/` 下所有文件 | 带大小和修改时间 |
| 查看 GC 日志 | 停顿行自动上色（黄=Young，红=Full） |
| 查看应用日志 | OOM 堆栈自动标红 |
| 一键生成堆快照 | `live=true` 会先做一次 Full GC，文件更小 |
| 下载 `.hprof` | 拿到本地用 MAT 分析 |

GC 日志支持**自动刷新（2 秒）**，做 GC 对比实验时开着它，能实时看到停顿频率和回收量变化。

> 想挂到公网域名访问（不用每次开隧道）→ 见 **[EXPOSE-VIA-TUNNEL.md](EXPOSE-VIA-TUNNEL.md)**。
> 已上线 `https://jvmlab.justnpc.com`，前面挂了 Cloudflare Access。

### 命令行

```bash
./lab.sh start              # 构建并启动容器（profile=default）
./lab.sh status             # 容器状态 + 资源上限 + JVM 指标 + 宿主负载
./lab.sh ui                 # 仪表盘地址与访问方式
./lab.sh limits             # 只看隔离边界 + 最终生效的 GC
./lab.sh trigger heap       # 触发堆 OOM
./lab.sh trigger reset      # 复位
./lab.sh dump-heap before   # 主动落堆快照
./lab.sh dump-thread 3 5    # 连续采 3 次线程栈（间隔 5 秒）
./lab.sh diagnose           # 一键采集诊断信息到 logs/
./lab.sh shell              # 进容器（jcmd/jmap/jstack 都在里面）
./lab.sh stop
```

📖 **界面逐场景操作步骤 → 见 [UI-GUIDE.md](UI-GUIDE.md)**
（含每个按钮悬停时浮出的核心代码说明）

演练场里 **JVM 的 PID 是 1**（Dockerfile 用了 `exec`）。所以容器内命令都是 `jcmd 1 ...`。

📖 **dump 文件怎么分析 → 见 [DUMP-ANALYSIS.md](DUMP-ANALYSIS.md)**

---

## 2. 场景对照表

| 场景 | 触发命令 | 期望现象 | 关键观察点 |
|---|---|---|---|
| 堆打爆 | `trigger heap` | `OutOfMemoryError: Java heap space` | 堆用量爬到 ~80% 就炸；`-Xmx` 管的就是它 |
| 慢速占堆 | `trigger heap-slow` | 不炸，堆单调上涨 | 对比 `reset` 前后 `heap.usedMb` |
| 元空间 | `trigger metaspace` | `OutOfMemoryError: Metaspace` | 约 8.8 万个类撑满 128m；复位后回落需要 Full GC |
| 直接内存 | `trigger direct` | `OutOfMemoryError: Direct buffer memory` | **`-Xmx` 管不到它**，只看 `MaxDirectMemorySize` |
| GC 空转 | `trigger gc-overhead` | `GC overhead limit exceeded` | **必须先 `restart serial`**，G1 不抛这个错，见专节 |
| 线程数 | `trigger threads` | `unable to create new native thread` | 报的是「内存」但根因是 `pids_limit` / `-Xss` |
| 栈溢出 | `trigger stack` | `StackOverflowError` | 返回 `depthReached`，改 `-Xss` 后对比这个数 |
| 死锁 | `trigger deadlock` | `jstack` 报 `Found one Java-level deadlock` | 死锁**无法**靠 `reset` 解除，要重启容器 |
| CPU 100% | `trigger cpu` | CPU 打满 2 核 | `top -H` 拿 tid → 转 16 进制 → `jstack` 里按 `nid` 找 |
| 渐进泄漏 | `trigger leak` | 堆缓慢上涨 | 两次 `jmap -histo` 对比 `LeakEntry` 实例数 |
| 复位 | `trigger reset` | 清空所有泄漏桶 | 清不掉死锁线程 |

---

## 2.5 专节：`GC overhead limit exceeded` 为什么必须先换 GC

这是全场**唯一一个不能直接复现**的现象，而且原因很反直觉，值得单独说清楚。

### 结论先行：G1 不会抛这个错，而且还需要**小 Young 区**

实测数据（256MB 堆、churn 15~20 秒）：

| 配置 | 结果 |
|---|---|
| G1，存活 85% | 198 次 `Pause Full`，每次 225M→224M（只回收 0.4%）→ **不报错**，接口被拖到几百秒 |
| Serial，存活 80/85/90%（Young 区默认占堆 1/3） | **秒回，不报错** |
| Serial + `-Xmn4m`，存活 80% | 见下方 profile 说明 |

两个独立的障碍：

**障碍一：G1 根本没实现这个检查。**
`GCOverheadChecker` 只被 Serial / Parallel / CMS 调用。G1 的做法是
一直做 Full GC 空转下去，不 fail-fast。

这个差异在生产上有实际后果：G1 的机器不会"干脆地死掉"，而是**慢慢烂掉** ——
请求越拖越长、CPU 被 GC 吃满，但日志里看不到明确的 OOM，
排查时容易误判成"应用变慢了"。

**障碍二：Young 区必须小。**
`freed_ratio` 是相对**堆容量**算的：

```
freed_ratio = 本次 GC 回收的字节 / 堆总容量

Serial 默认 Young 区 ≈ 堆的 1/3 = 85MB
  → 一次 Young GC 能回收最多 85MB / 256MB = 33%
  → 33% 远大于 2% 的门槛 → 条件永远不成立
```

所以必须把 Young 区压小。本项目为此提供了专用 profile：

```bash
./lab.sh restart gc-overhead     # = -XX:+UseSerialGC -Xmn4m
./lab.sh limits                  # 确认 GC 和 Young 区都对
./lab.sh trigger gc-overhead
```

`-Xmn4m` 让每次 Young GC 最多回收 `4MB / 256MB ≈ 1.6% < 2%` ✓，
配合老年代被存活集占满 → 频繁 GC 且回收极少 → 命中判定。

### 触发条件（两个必须同时满足）

```
gc_time_ratio > 0.98       GC 占用超过 98% 的时间
freed_ratio   < 0.02       每次 GC 回收的堆不到 2%
```

### 障碍三（最关键）：检查点只在"分配彻底失败"的那一刻才被调用

这是最难想到的一层，本项目实测出来的。

`GCOverheadChecker::check_gc_overhead_limit` **不是在每次 GC 之后调用**，
而是在**分配彻底失败、JVM 即将抛 `Java heap space` 的那一刻**才被调用：

```
分配请求
  → 尝试 Young GC  → 还是不够
  → 尝试 Full GC   → 还是不够
  → 此刻才检查 GC overhead 条件：
       条件成立   → 抛 GC overhead limit exceeded
       条件不成立 → 抛 Java heap space
```

实测证据（`gc-overhead` profile = Serial + `-Xmn4m`，存活 85%）：

```
GC(480) Pause Young (Allocation Failure) 239M->238M(255M) 1.728ms
相邻 GC 间隔：平均 0.001 秒              ← 每 1ms 一次，GC 背靠背运行
952 次 Pause Young，每次回收 ~1MB ≈ 0.39% 的堆
```

两个条件**其实都满足了**：

| 条件 | 门槛 | 实测 | 满足？ |
|---|---|---|---|
| `gc_time_ratio > 0.98` | 98% | ≈100%（GC 背靠背运行） | ✓ |
| `freed_ratio < 0.02` | 2% | 0.39% | ✓ |

**但仍然不报错。** 因为每次 Young GC 都能腾出约 1MB，
而 churn 循环每次只要 4KB —— **分配从未真正失败**，检查点根本没被执行到。

### 实测的触发窗口：够不到

继续把存活集往上推，结果是这样：

| `livePercent` | 实际存活 | 结果 |
|---|---|---|
| 90% | 222 MB | 不报错（GC 撑住） |
| 95% | 242 MB | 不报错（GC 撑住） |
| 97% | 243 MB 就炸 | **`Java heap space`（在填充阶段）** |
| 98% | 243 MB 就炸 | **`Java heap space`（在填充阶段）** |

255MB 的堆最多只能填进 243MB 的存活集，再往上填充本身就先 OOM 了。
也就是说：**"能填满"和"分配会失败"之间没有交集**，这个报错在当前配置下够不到。

### 实践结论

| | 结论 |
|---|---|
| **G1（JDK 9+ 的默认）** | 完全不会抛这个错，只会无限 Full GC 空转 |
| **Serial / Parallel / CMS** | 实现了检查，但需要堆满到分配失败，窗口极窄 |
| **这个报错的现代意义** | 已经很罕见了 —— 网上大量"复现教程"其实复现不出来 |

**所以这个演练场的正确用法不是死磕这句报错，而是观察"GC 空转"本身：**

```bash
./lab.sh restart gc-overhead
./lab.sh trigger gc-overhead
./lab.sh gc 40            # 看 GC 日志：频率、每次回收比例
```

看到「952 次 Young GC、每 1ms 一次、每次只回收 0.39%」这组数据，
你就真正理解了「为什么堆不能用得特别满」——
这比看到那句报错有价值得多，因为**这才是线上真实发生的事**：
G1 不会干脆地死，它会一直空转，让请求越来越慢，而日志里什么错都没有。

> 经验值：**堆保留 20%~30% 余量**。
> 堆越满，GC 越是在做无用功，最后要么长时间 Full GC 停顿、要么服务整体变慢。

### 怎么调

```bash
./lab.sh restart gc-overhead
curl "localhost:8081/oom/gc-overhead?livePercent=85&churnMillis=15000"
```

| 结果 | 说明 | 怎么调 |
|---|---|---|
| `Java heap space` | 存活集**太大**，在"填满"阶段就炸了 | `livePercent` 调**小** 3~5 |
| `no OOM（GC 撑住了）` | 存活集**太小**，GC 每次都能回收一大片 | `livePercent` 调**大** 3~5 |
| 接口几十秒不返回 | 大概率是 G1 —— 它在空转但不报错 | 换成 `gc-overhead` profile |
| `GC overhead limit exceeded` | 命中（**很难**，见上文实测窗口） | 记下这个 `livePercent` |

### 顺带发现一：G1 的 humongous 对象会「提前 OOM」

搭这个场景时实际踩到的坑。

实现填充存活集时，我一开始用 **1MB 的数组**。结果是：**堆里明明还有 130MB 空闲，
却在只持有 120MB 时就报 `Java heap space` 了。**

原因是 G1 的 **humongous 对象**机制：

```
G1 region 大小 = 堆大小 / 2048，下限 1MB
  256MB 堆 → 256MB / 2048 = 128KB → 被下限拉到 1MB

对象大小 >= region 的一半  →  判定为 humongous 对象
  1MB 数组 >= 512KB  →  是 humongous
```

humongous 对象的行为和普通对象完全不同：

| | 普通对象 | humongous 对象 |
|---|---|---|
| 分配位置 | 先落 Young 区（Eden） | **直接进老年代** |
| 空间要求 | 单个 region 内即可 | **需要连续的一整组 region** |
| 回收时机 | Young GC 就能回收 | 要等 Mixed GC / Full GC |
| 碎片敏感度 | 低 | **极高** |

于是 120 个 1MB 的 humongous 对象把老年代切得七零八落之后，
再来一个就找不到连续空间了 —— 哪怕总的空闲字节数还很充裕。

**修法**：填充块改成 **128KB**（明显小于 region 的一半），
分配就回到 Young 区，行为和普通对象一致了。

**这条经验直接可用于生产**：如果你在做大文件 / 大图片 / 大 JSON 的处理，
单个对象超过 G1 region 的一半时就会踩上 humongous 分配的坑 ——
表现为「堆还没满就 OOM」或「Full GC 频繁」。
常见对策是**把大对象拆小**（流式处理、分片读写），而不是一味调大 `-Xmx`。

> `-XX:G1HeapRegionSize` 可以显式指定 region 大小，但它是**启动时固定**的，
> 且会影响整个堆的管理粒度，不是随便调的旋钮。

### 顺带发现二：判断"堆填满了没有"不能用 `Runtime.used`

第一版实现里，填充循环的条件是 `rt.totalMemory() - rt.freeMemory() < targetLive`。
结果存活集只到 45% 循环就退出了。

原因：`Runtime` 的 used 里**混着尚未回收的垃圾**。
你看到"已用 243MB"，其中可能有 128MB 是垃圾，真正的存活集只有 115MB。

**正确做法是按「自己实际持有的字节数」推进**，而不是读 JVM 的用量统计。
这个坑在写压测工具、内存巡检脚本时同样会遇到。

---

### 方式 A：进容器（推荐）

```bash
./lab.sh shell
```

```bash
jps -l                                  # 有哪些 Java 进程
jcmd 1 VM.command_line                  # 实际生效的启动参数 ← 最该先看这个
jcmd 1 VM.flags                         # 所有可写 VM 标志的最终值
jcmd 1 GC.heap_info                     # 堆/GC 概览
jstat -gcutil 1 1000                    # 每秒一次分代统计（M 列 = Metaspace）
jmap -histo 1 | head -30                # 对象直方图
jcmd 1 Thread.print                     # 线程栈（等价 jstack）
jcmd 1 VM.native_memory summary         # 原生内存分布 ← 看清「堆只占一部分」
jcmd 1 GC.heap_dump /dumps/manual.hprof # 主动 dump
```

### 方式 B：一条命令搞定（不进容器）

```bash
./lab.sh diagnose          # 上面 8 项全部采集，写到 logs/diagnose-<时间>.txt
./lab.sh dump-thread 3 5   # 线程栈连续采 3 次（间隔 5 秒）
docker exec jvm-lab jstat -gcutil 1
docker exec jvm-lab jcmd 1 GC.heap_info
```

### 方式 C：界面按钮

仪表盘「日志与快照」面板里有 **采集线程栈** 按钮（3 次采样 + 自动高亮 + 摘要）。
另有「生成堆快照（live / 含垃圾）」。详见 [UI-GUIDE.md](UI-GUIDE.md)。

### 分代统计各列含义（`jstat -gcutil`）

```
S0 S1 E   O   M   CCS   YGC YGCT  FGC FGCT  CGC CGCT  GCT
│  │  │   │   │   │     │   │     │   │     │   │     └ 总 GC 时间
│  │  │   │   │   │     │   │     │   │     └───┴────── ZGC/并发收集器
│  │  │   │   │   │     │   │     └───┴────────────── Full GC 次数/耗时
│  │  │   │   │   │     └───┴──────────────────────── Young GC 次数/耗时
│  │  │   │   │   └───────────────────────────────── 压缩类空间
│  │  │   │   └───────────────────────────────────── Metaspace 使用率 ★
│  │  │   └───────────────────────────────────────── 老年代使用率
│  │  └───────────────────────────────────────────── Eden 使用率
└──┴──────────────────────────────────────────────── Survivor 两个区
```

**判读要点**：`FGC` 一直涨而 `O` 降不下来 → 老年代真有存活对象，不是「再 GC 一次就好了」。

### CPU 100% 定位三步

```bash
# 1. 容器内找最耗 CPU 的线程（容器里没有 top，用 jcmd 代替）
jcmd 1 Thread.print | grep -A 5 "lab-cpu-spin"
# 或从宿主看：docker stats jvm-lab
# 2. 线程号转 16 进制（jstack 里是 nid=0x... 的十六进制）
printf '%x\n' <tid>
# 3. 按 nid 定位
jcmd 1 Thread.print | grep -A 20 "nid=0x<hex>"
```

---

## 4. 参数实验：换 GC 看差异

```bash
./lab.sh restart g1           # 显式 G1 + MaxGCPauseMillis=50
./lab.sh restart parallel     # 吞吐优先
./lab.sh restart serial       # 单线程，日志最好读
./lab.sh restart zgc          # 亚毫秒暂停（JDK17 需 UnlockExperimentalVMOptions）
./lab.sh restart gc-overhead  # ★ 专用：Serial + -Xmn4m，为了复现 GC overhead limit exceeded
```

换完**务必先确认 GC 真的换了**，再跑场景：

```bash
./lab.sh limits             # 看「最终生效的 GC」那一节
```

> ⚠️ 你以为设了 G1 就真的是 G1 吗？见下面「常见坑」里的 2GB 阈值那条。
> 这台机器上默认情况下 JVM 选的是 **Serial GC**，不是 G1。

然后看 GC 日志：

```bash
./lab.sh gc 60
```

对比时盯这几个数：

| 观察项 | 在 GC 日志里找什么 |
|---|---|
| 停顿时间 | `Pause Young` / `Pause Full` 后面的毫秒数 |
| 频率 | 单位时间内 `Pause` 出现几次 |
| 回收效果 | 每次 GC 前后堆占用掉了多少 |
| 晋升 | `Pause Young (Concurrent Start)` 与老年代增长的关系 |

`-Xlog:gc*` 里的关键字段：

```
[2026-09-26T23:10:11.123+0800][12.345s][info][gc,heap] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 45M->12M(256M) 3.456ms
│                            │       │    │      │                 │                       │         └ 停顿毫秒数
│                            │       │    │      │                 │                       └ 回收前->回收后(总容量)
│                            │       │    │      │                 └ GC 原因
│                            │       │    │      └ GC 序号 + 阶段
│                            │       │    └ 日志级别
│                            │       └ 相对 JVM 启动的秒数
│                            └ 时间戳
└ 应用名/进程号
```

---

## 5. 堆转储分析

OOM 时会自动落盘到 `./dumps/`（`-XX:+HeapDumpOnOutOfMemoryError`）。

**注意体积**：一次堆 OOM 会产生约等于 `-Xmx` 大小的 hprof（256m 堆 → ~240MB 文件）。
演练几次就吃掉几个 G，务必定期 `./lab.sh clean`。

### 快速版（不用装工具）

```bash
docker exec jvm-lab jmap -histo 1 | head -30
```

看 `lab.jvm.support.LeakEntry` 或 `[B`（byte 数组）的**实例数**和**总字节数**。

### 完整版（MAT）

```bash
# 1. 先做一次基线 dump
docker exec jvm-lab jcmd 1 GC.heap_dump /dumps/base.hprof
# 2. 触发泄漏
./lab.sh trigger leak
./lab.sh trigger leak
# 3. 再做一次
docker exec jvm-lab jcmd 1 GC.heap_dump /dumps/after.hprof
# 4. 用 MAT 打开两份 dump，对比 Histogram → 看 dominator tree
```

MAT 需要 GUI。纯命令行可以下载 MAT 的 `ParseHeapDump.sh`（arm64 版本可用）。

---

## 6. 关键参数速查

| 参数 | 作用 | 本演练场的值 | 说明 |
|---|---|---|---|
| `-Xms` / `-Xmx` | 堆初始/上限 | 256m / 256m | 固定住，让 GC 行为可复现 |
| `-Xss` | 每线程栈大小 | 512k | 调小 → 栈溢出更快，能开更多线程 |
| `-XX:MaxMetaspaceSize` | 元空间上限 | 128m | **不设就受宿主内存约束，复现不出来** |
| `-XX:MaxDirectMemorySize` | 堆外直接内存上限 | 64m | 不设时默认等于 `-Xmx` |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时落堆快照 | 开 | |
| `-XX:HeapDumpPath` | 快照落盘位置 | `/dumps` | 映射到宿主的 `./dumps` |
| `-XX:NativeMemoryTracking` | 原生内存追踪 | `summary` | 让 `jcmd VM.native_memory` 可用 |
| `-Xlog:gc*` | GC 日志 | 开 | JDK 9+ 统一语法，替代 JDK 8 的 `-XX:+PrintGCDetails` |

---

## 7. 常见坑

### ★ 容器内存低于 2GB 会让 JVM 偷偷降级成 Serial GC

这是本项目搭建时**实际踩到的坑**，也是容器化部署里极其常见的事故来源。

HotSpot 的 `is_server_class_machine()` 要求 **CPU ≥ 2 且内存 ≥ 2GB**，两个条件缺一不可。
不满足时，JVM 认为"这是台小机器"，把默认 GC 从 G1 降级成 **Serial GC**（单线程 GC）——
而你完全不会收到任何警告。

本项目最初把 `mem_limit` 设成 `1536m`，结果 `./lab.sh limits` 显示：

```
-XX:+UseSerialGC          ← 不是我配的，是 JVM 自己选的
-XX:MaxHeapSize=268435456
```

**排查方法**：永远不要假设 GC，去看 `jcmd 1 VM.flags | grep Use.*GC`。
`./lab.sh limits` 已经把这一步内置了。

**想亲眼看到这个降级**：把 `docker-compose.yml` 里的 `mem_limit` 改成 `1536m`，
`./lab.sh restart`，再看 `./lab.sh limits` 的 GC 那一节。

### `MemoryMXBean.getMax()` 不等于 `-Xmx`

`/status` 里 `heap.maxMb` 显示 **247**，但 `-Xmx256m` 是生效的
（`jcmd VM.flags` 里 `MaxHeapSize=268435456` 正好是 256MB）。

差的那 9MB 是**分代收集器的 Survivor 空间**：`getMax()` 返回的是"可用"上限，
而 Survivor 区虽然在堆内，却不会被算进可用空间。所以
**不要用 `getMax()` 反推 `-Xmx`**，要确认就用 `jcmd 1 VM.flags`。

### OOM 之后所有请求都失败

堆是满的，不清掉后续什么都干不了。先 `./lab.sh trigger reset`。

### Metaspace 复位后没立刻回落

类卸载发生在 Full GC 期间，而 Full GC 是并发的。
`reset` 后等 1-2 秒再看 `/status`。如果一直不降，说明类被定义进了长生命周期的 ClassLoader
（本项目的 `LabClassLoader` 就是为了避免这个问题才存在的）。

### Metaspace 和直接内存的 OOM，heap dump 里什么都看不到

它们**都不在堆里**。排查前先用 `/status` 确认是哪块内存爆了：

| `/status` 里哪一项满了 | 是哪里的问题 | 用什么工具 |
|---|---|---|
| `heap.usedMb` | 堆 | heap dump + MAT |
| `metaspace.usedMb` | 元空间（类元数据） | `jstat -gcutil` 的 M 列、`jcmd VM.native_memory` |
| `directBuffer.usedMb` | 堆外直接内存 | `/status`、`jcmd VM.native_memory` |

### 死锁无法复位

`reset` 只能清引用、打断线程，改不了已经形成的锁等待环。重启容器：`./lab.sh restart`。

### `reset` 的 `System.gc()` 只是建议

JVM 可以不理会（加了 `-XX:+DisableExplicitGC` 就完全不理会）。
生产代码里绝不该显式调 `System.gc()`，一次 Full GC 会让整个应用停顿。

### 容器内没有 `top`

用 `jcmd 1 Thread.print` 代替，或者从宿主 `docker stats jvm-lab`。

### 改了 `mem_limit` 要重新 `up`

`./lab.sh restart` 走的是 `docker compose down` + `up`，会重建 cgroup。
但如果你手工只 `docker restart`，cgroup 限制不会更新。

### Spring Boot 的属性优先级

命令行参数 > 环境变量 > `application.yml`。
这台机器的环境里存在 `SERVER__PORT` 这类变量，会被宽松绑定识别成 `server.port`，
把配置文件里的端口顶掉。所以本项目改用命令行参数 `--server.port=8080` 指定端口
（命令行优先级最高，压得过环境变量）。这是排查「配置不生效」时的经典盲区。

### Tomcat 会静默丢弃含非 ASCII 字符的响应头

给响应头塞中文，Tomcat **不报错、不打日志、响应里就是没有这个头**。
排查时你会以为是代码没执行 —— 实测踩过一次：`X-Lab-Warning` 的值写了中文，
头凭空消失，改回英文立刻出现。

**规则**：响应头的值只能是 ASCII。要传中文就放 body 里，或者做 URL 编码。

### 堆快照下载失败 / 502

| 现象 | 原因 | 处理 |
|---|---|---|
| **502 "Host Error"** 但文件明明在 | ⚠ 多半不是文件的问题 —— 见下方说明 | 先按下面三步排查 |
| 502 且文件不存在 | 空 body 的 400 被 Cloudflare 显示成 502 | 已修：现在返回 404 + JSON 说明 |
| 文件列表里没有那个文件 | 已被 `./lab.sh clean` 清理，或容器重启后失效 | 点「刷新列表」看现在有什么 |
| 下载下来打不开 | 是二进制 hprof，必须用 MAT | 见 [DUMP-ANALYSIS.md](DUMP-ANALYSIS.md) |

**排查 502 的三步**（能把范围砍一半）：

```bash
curl -sI localhost:8081/<路径>     # 宿主机上直接看真实状态码
docker logs jvm-lab --tail 50       # 应用有没有报错
./lab.sh log 30
```

源站返回 200 但公网 502 → 问题在 Cloudflare 那层（响应体大小 / 超时 / 空 body）；
源站本身就报错 → 问题在应用里。

**关于大文件**：Cloudflare 免费版上传限制 100MB，响应体没有公开的明确上限。
本项目的经验阈值是 100MB，超过会在列表里标黄并弹确认框。
要稳就走 SSH 隧道（`./lab.sh ui` 有命令）。详见
[EXPOSE-VIA-TUNNEL.md](EXPOSE-VIA-TUNNEL.md) 的「已知限制」。

> 顺带说清两个量级差 7 倍的原因：OOM 自动落盘发生在堆**已经满了**的时刻，
> 里面塞满即将被回收的垃圾；live 快照会先做一次 Full GC 只留存活对象。
> 235MB vs 31MB。
