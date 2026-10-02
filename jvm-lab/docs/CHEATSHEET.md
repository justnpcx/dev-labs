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

共 14 个现象，按**难度四级**排列（仪表盘上也是这个顺序）。
分级的依据是「要定位它得懂多少东西」，不是「现象有多严重」。

### 初级 · 现象直白，报错信息就说明一切

| 场景 | 触发命令 | 期望现象 | 关键观察点 |
|---|---|---|---|
| 堆打爆 | `trigger heap` | `OutOfMemoryError: Java heap space` | 堆用量爬到 ~80% 就炸；`-Xmx` 管的就是它 |
| 慢速占堆 | `trigger heap-slow` | 不炸，堆单调上涨 | 对比 `reset` 前后 `heap.usedMb` |
| 栈溢出 | `trigger stack` | `StackOverflowError` | 返回 `depthReached`，改 `-Xss` 后对比这个数 |
| CPU 100% | `trigger cpu` | CPU 打满 2 核 | `top -H` 拿 tid → 转 16 进制 → `jstack` 里按 `nid` 找 |
| 停止空转 | `trigger cpu-stop` | 空转线程退出 | 空转线程**不会自己停**，必须 interrupt |

### 中级 · 要配合 jstack / jmap / top 才能定位

| 场景 | 触发命令 | 期望现象 | 关键观察点 |
|---|---|---|---|
| 直接内存 | `trigger direct` | `OutOfMemoryError: Direct buffer memory` | **`-Xmx` 管不到它**，只看 `MaxDirectMemorySize` |
| 线程数 | `trigger threads` | `unable to create new native thread` | 报的是「内存」但根因是 `pids_limit` / `-Xss` |
| 死锁 | `trigger deadlock` | `jstack` 报 `Found one Java-level deadlock` | 死锁**无法**靠 `reset` 解除，要重启容器 |
| 渐进泄漏 | `trigger leak` | 堆缓慢上涨 | 两次 `jmap -histo` 对比 `LeakEntry` 实例数 |

### 高级 · 要理解 GC / 类加载 / 锁的实现

| 场景 | 触发命令 | 期望现象 | 关键观察点 |
|---|---|---|---|
| 元空间 | `trigger metaspace` | `OutOfMemoryError: Metaspace` | 约 8.8 万个类撑满 128m；复位后回落需要 Full GC |
| GC 空转 | `trigger gc-overhead` | `GC overhead limit exceeded` | **必须先 `restart serial`**，G1 不抛这个错，见专节 |
| 锁竞争现场 | `trigger contention` | `jstack` 里 7 个线程 `BLOCKED` | 8 线程抢 1 把锁；吞吐恒等于 `1/holdMillis`，**与线程数无关** |
| 锁方式对比 | `trigger lock-bench` | 全局锁 ≫ 分段锁 ≈ AtomicLong | 全局锁耗时 ≈ 单线程耗时 × 线程数 |

### 终极 · 生产事故级，多个因素交织

| 场景 | 触发命令 | 期望现象 | 关键观察点 |
|---|---|---|---|
| 线程池无界队列 | `trigger pool` | `Java heap space`，但根因是队列无界 | 队列里堆的是**每个请求的完整上下文**；见专节 2.6 |
| 拒绝策略对比 | `trigger pool-reject` | 四种策略的 accepted / rejected 差异 | `discard` 丢 42 个任务却 2ms 返回；`caller` 是背压 |
| 连接池耗尽 | `trigger connpool` | 请求大面积超时，但 **CPU 很闲** | 瓶颈在**等待**不在计算；见专节 2.9 |
| 连接池泄漏 | `trigger connpool-leak` | 可用连接数**单调下降**到 0 | 比池太小危险得多，只能重启恢复；见专节 2.9 |
| JIT 预热 | `trigger jit` | 前几轮慢，某轮开始陡降几十倍 | 返回**每轮单独**耗时；陡降点 = C2 编译完成 |
| JIT 去优化 | `trigger jit-deopt` | 一轮突然 spike 6~10 倍，之后回到原速 | ⚠ **每个 JVM 只发生一次**，先 `restart`；见专节 2.10 |
| **本地内存泄漏** | `trigger native-soft` | 堆和直接内存**都不动**，只有 RSS 涨 | 唯一一个「日志直接断掉」的场景；见专节 2.11 |

### 解读类 —— 读证据、做决策

前面 17 个场景都是"制造问题"，只有这个是把**已有的日志**解析成结论。
真实调优工作就长这样：拿到日志 → 读出瓶颈 → 调参 → 再跑一遍对比。

| 场景 | 触发命令 | 说明 |
|---|---|---|
| GC 日志解读 | `trigger gc-summary` | 停顿统计 / 分布直方图 / 分配速率 / **规则诊断** |

仪表盘上也有对应面板（「GC 日志解读」→ 分析按钮）。

**怎么用出效果**：

```bash
./lab.sh trigger leak          # 先制造一些 GC
./lab.sh trigger heap-slow
curl -s localhost:8081/gc/summary | python3 -m json.tool | less

# 换 GC 跑同一份负载，对比同一组数字
./lab.sh restart parallel && ./lab.sh trigger heap-slow && curl -s localhost:8081/gc/summary
./lab.sh restart g1       && ./lab.sh trigger heap-slow && curl -s localhost:8081/gc/summary
./lab.sh restart zgc      && ./lab.sh trigger heap-slow && curl -s localhost:8081/gc/summary
```

**ZGC 的日志格式和另外三个完全不同**，解析器单独适配过：

| | G1 / Parallel / Serial | ZGC |
|---|---|---|
| 停顿行 | `Pause Young (Normal) 24M->3M(256M) 12.3ms` | `Pause Mark Start 0.007ms`（**没有堆用量**） |
| 一次 GC | 1 条停顿 | **3 条**阶段停顿（Mark Start / Mark End / Relocate Start） |
| 类型 | Young / Full / Mixed | 只有那三个阶段 |
| 次数字段 | `gcCount` | `gcCount`（阶段）+ **`cycleCount`（周期）** |

实测同一份负载（256M 堆，ZGC profile）：

```
gcCount 24（阶段）  cycleCount 8（周期）  ← 正好 3 倍
总停顿 0.36ms   平均 0.01ms   最大 0.03ms
byType {Mark Start: 8, Mark End: 8, Relocate Start: 8}
```

**这就是 ZGC 卖的东西**：亚毫秒停顿，而且**停顿不随堆变大而变长**
（标记/移动/重定位全做成并发了）。代价是更高的 CPU 占用和内存开销。

注意 ZGC 下「Full GC 频繁」「Young GC 太频繁」两条规则**不会命中** ——
它压根没有 Young/Full 之分。诊断输出里会明确说这一点，
免得你以为解析漏了。

**它会给出什么**：

- `overview`：GC 次数、总/平均/最大停顿、**GC 时间占比**（生产上超 5% 就该警惕）
- `pauseHistogram`：停顿分布 —— 平均值会骗人，一次 800ms 的毛刺藏在
  200 次 5ms 里，均值完全看不出来，必须看尾部
- `allocationRate`：靠 `sum(Young GC 回收量)/时间` 估算的分配速率
- `diagnosis`：**规则式诊断**，每条结论都标注了依据哪个数字、以及下一步该做什么

诊断用的是可读规则而不是"AI 分析"，规则本身也写在源码里 ——
不同意可以自己改。四条规则：

1. Full GC 频繁 + 每次回收量 < 堆的 5% → 存活集过大或内存泄漏
2. Young GC 间隔 < 200ms → 分配速率太高，或 Young 区太小
3. 最大停顿 > 平均停顿 × 8 → 长尾毛刺，比"整体慢"糟糕得多
4. GC 时间占比 > 5% → 大量 CPU 花在 GC 上而不是业务逻辑上

### 工具

| 场景 | 触发命令 | 说明 |
|---|---|---|
| 看状态 | `trigger status` | JVM 全景快照 |
| 复位 | `trigger reset` | 清空泄漏桶 + 关线程池 + 停锁竞争线程 + 停空转线程 + 重建连接池 + 归还本地内存 |

### 日志与快照的清理

仪表盘的「日志与快照」面板支持清空：单个文件（选中后点「清空」）
或一整类（「清空日志」/「清空快照」）。也可以直接调接口：

```bash
curl -X POST 'localhost:8081/files/clear?path=logs/gc.log'   # 清一个
curl -X POST 'localhost:8081/files/clear?dir=logs'           # 清一类
curl -X POST 'localhost:8081/files/clear?dir=dumps'
```

**关键设计：正在被写入的文件用「截断」而不是「删除」。**

`logs/gc.log` 被 JVM 的 `-Xlog` 持有，`logs/app.log` 被 logback 持有。
直接 `delete` 的话文件从目录里消失了，但**写入方的 fd 还指着那个 inode** ——
它会继续往里写，只是你再也看不见了。后果：

- 磁盘空间**一点没释放**（inode 被引用着）
- 日志面板**永远空着**，直到重启才恢复

所以规则是：**被持有 → truncate；没被持有 → delete**。
堆快照（235MB）属于后者，删掉才真的把空间还回去。

实测截断之后 JVM / logback 会**从偏移 0 重新写**，不会留下 NUL 空洞
（这一点验证过 —— 早期担心过稀疏文件，实际是干净的）。

怎么判断"被持有"：扫 `/proc/self/fd` 里的符号链接。
比维护一份"哪些文件是活跃的"白名单靠谱 —— 白名单会在你改了 logback
配置之后**悄悄失效**。

---

## 2.6 专节：线程池配错的两种典型形态

生产事故里线程池的出场频率比 OOM 高得多，而且**更阴险** ——
OOM 至少会崩，你会立刻知道出事了；线程池配错往往不崩，
表现出来只是"偶发超时"和"数据偶尔对不上"。

### 形态一：无界队列把堆吃光

```java
// ✗ 永远不拒绝，队列能一直涨
new ThreadPoolExecutor(2, 2, 0, MILLISECONDS, new LinkedBlockingQueue<>());

// ✓ 有界 + 明确的拒绝策略
new ThreadPoolExecutor(2, 2, 0, MILLISECONDS,
        new ArrayBlockingQueue<>(200),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

`Executors.newFixedThreadPool(n)` 和 `newSingleThreadExecutor()` 内部就是
**无界的 `LinkedBlockingQueue`** —— 这正是《阿里巴巴 Java 开发手册》强制
禁用这两个工厂方法的原因。

**关键认知**：队列里堆的**不只是任务对象**，而是每个待处理请求的完整上下文
（解析好的报文、用户对象、SQL 结果集……）。所以 OOM 的规模远大于
"任务数量 × 几十字节"这个直觉。

实测：2 个消费线程、4000 个任务 × 64KB 上下文 ≈ 256MB，堆直接被打满。
报的是 `Java heap space`，但 `jstack` 里线程全都正常 —— **根因在线程池配置，不在内存**。

> ⚠ 这个场景的 **HTTP 响应体可能拿不到**：堆满时序列化响应会再次 OOM，
> 而且启动参数带 `-XX:+HeapDumpOnOutOfMemoryError`，JVM 会先在 OOM 线程里
> 尝试写 heapdump。证据在日志里：`docker logs jvm-lab | grep -A 5 OutOfMemoryError`。
> 原有的「堆 OOM」按钮也是同样的行为。

### 形态二：拒绝策略选错，任务被静默丢弃

四种策略，行为差别巨大（实测数据，`tasks=60, queueSize=16, taskMillis=20`）：

| 策略 | accepted | rejected | submitCost | 说明 |
|---|---|---|---|---|
| `AbortPolicy` | 18 | 42 | 2ms | 抛异常，**调用方必须 catch** |
| `CallerRunsPolicy` | 60 | 15 | **302ms** | 提交线程自己跑 → **背压** |
| `DiscardPolicy` | 60 | 42 | **2ms** | ⚠ **静默丢 42 个任务** |
| `DiscardOldestPolicy` | 60 | 42 | 5ms | 丢最老的，适合"只要最新值" |

**最危险的是 `DiscardPolicy`**：接口 2ms 返回成功，42 个任务根本没执行，
而且**没有异常、没有日志**。线上表现是"偶发数据对不上"，能查一整天。

`CallerRunsPolicy` 的 302ms 不是 bug，是**背压**：提交线程被拖慢，
上游自然不会再猛发请求。这是绝大多数场景下的正确选择。

### 怎么观察

```bash
# 响应体可能拿不到，所以直接看日志
docker logs jvm-lab | grep -A 5 OutOfMemoryError

# 看堆里到底是什么对象最多
docker exec jvm-lab jmap -histo:live 1 | head -15

# 对比四种策略
for p in abort caller discard oldest; do
  echo "--- $p ---"
  curl -s "localhost:8081/pool/bounded?policy=$p&tasks=60&queueSize=16&taskMillis=20" \
    | python3 -m json.tool | grep -E 'accepted|rejected|submitCost'
done
```

---

## 2.7 专节：锁竞争 —— 比死锁更常见，也更难发现

死锁是「卡住不动」，一眼能看出来。**锁竞争**是「照常跑，但吞吐上不去」，
而且**加线程越多越慢** —— 不看 `jstack` 基本发现不了。

```
死锁：   A 等 B、B 等 A   —— 有环，JVM 能自动检测并报出来
锁竞争： 8 个人抢 1 个坑位 —— 没有环，结构上完全正常，性能上完全不能接受
```

### 现场

`trigger contention` 会起 8 个线程抢同一把锁，每个持有 5 秒：

```bash
docker exec jvm-lab jstack 1 | grep -A 2 'lab-lock-contention'
# 1 个 RUNNABLE（持有锁）
# 7 个 BLOCKED (on object monitor) —— waiting for monitor entry
```

**吞吐量恒等于 `1 / holdMillis`，和线程数无关。** 加线程只会让等待队列更长。

### 量化对比

`trigger lock-bench` 跑同一份计数工作，只换同步方式（实测 4 线程 × 20 万次）：

| 方式 | 耗时 | 为什么 |
|---|---|---|
| 全局锁 `synchronized (GLOBAL_LOCK)` | **319ms** | 临界区完全串行 |
| 分段锁（每线程一把） | 187ms | 无争用，并行 |
| `AtomicLong` | **23ms** | 无锁 CAS |

全局锁的耗时 ≈ 单线程耗时 × 线程数。**但这是没预热的微基准**，
别当生产结论 —— 见下面的 JIT 专节。

### 优化方向

1. **缩小临界区**：把远程调用、IO、日志这些挪到锁外面（收益最大）
2. **降低锁粒度**：`ConcurrentHashMap` 的分段思想；每线程独立对象
3. **换无锁结构**：`AtomicLong` / `LongAdder`（高争用下 `LongAdder` 更优）
4. **读写分离**：`ReentrantReadWriteLock`，读多写少时收益明显

---

## 2.8 专节：JIT 预热 —— 为什么你的微基准测试是错的

`trigger jit` 跑 20 轮同样的计算，返回**每轮单独**的耗时。
实测（300000 次/轮）：

```
[2082, 604, 625, 595, 589, 609, ...]   ← 首轮 2082µs，之后稳定在 ~600µs
加速比 3.5x
```

同一段代码，跑第一次和跑第十次能差几倍到几十倍。

### 三个阶段

| 阶段 | 触发条件 | 相对速度 |
|---|---|---|
| 解释执行 | 一上来就是 | 1x |
| C1 编译 | 方法被调用若干次 | ~10x |
| C2 编译 | 调用次数更多（分层编译下 JVM 动态决定） | ~100x |

### 为什么必须每轮单独返回

如果只返回总耗时，这个现象就被**平均掉**了 —— 而这正是微基准骗人的方式：

```
你的"优化前"代码刚好没预热  → 显得慢
你的"优化后"代码刚好预热过  → 显得快
结论：快了 3 倍！上线：毫无变化
```

### 怎么观察

```bash
# 日志里出现某个方法名的那一刻，就是它被编译了
./lab.sh restart && docker logs jvm-lab | grep -i 'compilation\|%'

# 关掉分层编译，曲线会完全不同（只有 C2，没有中间的 C1 阶段）
java -XX:-TieredCompilation -jar jvm-lab.jar
```

**结论**：任何微基准不预热，测的都是解释器的速度，和线上跑了几小时的 JVM
完全不是一回事。这就是 **JMH** 存在的理由 —— 它把预热、死代码消除、
常量折叠这些坑都处理好了。自己手写 `System.nanoTime()` 包一圈，几乎必然测错。

---

## 2.9 专节：连接池耗尽 —— CPU 很闲，但请求全超时

和 2.6 的线程池是同一类问题（资源有界、请求无限），但**现象完全相反**：

| | 线程池无界队列 | 连接池耗尽 |
|---|---|---|
| 资源满时 | 任务**堆进队列** | 请求**等在门口** |
| CPU | 忙（在跑任务） | **闲**（在等连接） |
| 内存 | **被吃光** → OOM | 正常 |
| 症状 | `Java heap space` | 大面积**超时** |

**为什么更难查**：监控上看 CPU 20%、GC 正常、内存正常，但接口 504。
第一反应通常是"网络问题"或"下游挂了"，实际是自己把连接池占满了。

### 复现

```bash
./lab.sh trigger connpool          # 池 5 / 并发 20 / 慢查询 3s / 等待上限 1s
./lab.sh trigger connpool-stats    # 看 available / inUse / leakedTotal
./lab.sh trigger connpool-reset    # 重建池
```

实测：20 个并发里 **8 个超时**，成功的那批每个都等了 3 秒以上。

### 两种成因，严重程度差一个量级

**① 池太小 / 慢查询太长** —— 池 5 但同时来了 20 个请求。这是**容量问题**，
表现是稳定的、可预测的：并发超过池大小就开始排队。

**② 借了不还（连接泄漏）** —— 真实项目里几乎都是这个：

```java
Connection c = pool.getConnection();
doQuery(c);
c.close();              // ← 中间抛异常，这行永远执行不到
```

正确写法是 `try (Connection c = pool.getConnection()) { ... }`，
或者至少 `finally` 里关。实测泄漏 3 个连接后：

```
池大小 5  可用 2  使用中 3  泄漏累计 3
```

**它是单调恶化的** —— 池会一路降到 0，之后**所有**请求永久超时，
只能重启恢复。所以线上看到"连接池可用数持续下降"，别犹豫，直接按 P0 处理。

### 怎么在监控上认出来

- **CPU 低 + 超时多 + 线程数不多** → 优先怀疑连接池
- 线程栈里大量线程卡在 `acquire` / `getConnection`（`jstack` 一看便知）
- 池的 `activeCount` 贴着 `maxPoolSize` 不下来

### 调优方向（按优先级）

1. **先堵泄漏** —— 池调多大都填不满一个漏的池
2. **缩短单次持有时间** —— 慢查询、把 `doQuery` 挪出事务、别在事务里调外部 HTTP
3. 最后才考虑调大池 —— 池越大，DB 侧压力越大，而且**等待时间反而可能更长**
   （更多并发打同一个 DB）。池大小应该由 DB 的连接上限倒推，不是拍脑袋。

---

## 2.10 专节：JIT 去优化 —— 为什么跑着跑着卡一下

`trigger jit-deopt` 让同一个调用点先只收到 `Circle`，再混入 `Square`。

### 发生了什么

```
阶段一：数组里全是 Circle → 调用点**单态** → JIT 把 area() 内联进循环
        循环体变成纯算术，跑得飞快

阶段二：混入 Square → 调用点变**多态** → 之前的假设失效
        已编译代码被标记 made not entrant → 退回解释执行
        → 重新编译一份带类型检查的版本
```

### ⚠ 它只在每个 JVM 生命周期内发生一次

这是这个场景**最容易让人以为坏了**的地方：第一次跑能看到 spike，
第二次跑就是 `1.00x` —— 因为 JIT 已经为多态调用点编译好了。
想看它必须先 `./lab.sh restart`。接口里会如实返回 `deoptObserved` 说明状态。

### 实测（5 次冷启动）

```
去优化那一轮：spike 2.8x ~ 14x（波动很大）
重新编译后稳态：多态最好 ≈ 单态最好（5 次都在 0.97 ~ 1.05x）
```

**spike 为什么波动这么大**：它取决于"单态那次刚好被编译得多快"，
而 C2 编译的时机本身受启动噪声影响。所以**别记具体倍数，记量级**。

**关键结论是稳态那行**：重新编译后多态版本能跑到和单态一样快。
所以这是 **"抖一下"，不是"一直慢"** —— 多态本身不是罪，
JIT 会为新的类型分布重新优化，只是切换的那一瞬间要付代价。

### 为什么难排查

线上偶发一次卡顿，等你去看的时候早就恢复常态了 —— **现场不留痕**。
只能靠提前开日志：

```bash
# 看到同一方法被编译多次，以及 made not entrant / made zombie
java -XX:+PrintCompilation -jar jvm-lab.jar

# 想看更细的（deoptimization 原因）
java -XX:+UnlockDiagnosticVMOptions -XX:+TraceDeoptimization -jar jvm-lab.jar
```

### 什么时候该怀疑它

- 压测中途换了数据分布 → 前后数字**不可比**
- 灰度期间新老逻辑并存 → 同一个调用点收到两种类型
- 缓存预热期 / 冷启动后第一批请求
- 日志里出现 `made not entrant` / `made zombie`

### 怎么避免

1. 别在热点路径上让同一个变量承载多种类型
2. 用接口但保证实现单一，或者干脆拆成两个方法
3. 已经多态且无法避免时，用类型判断提前分流

**注意**：多态本身不是罪，JIT 为多态场景重新优化后性能是够用的。
真正的问题是**切换的那一瞬间**，以及切换期间测出来的数字不可信。

---

## 2.11 专节：本地内存泄漏 —— 唯一一个「日志直接断掉」的场景

这是全场唯一一个 **Java 堆完全正常，但进程被内核杀掉** 的现象。

### 三道内存上限，一道都管不住它

| 参数 | 管什么 | 管不管 Unsafe.allocateMemory |
|---|---|---|
| `-Xmx256m` | Java 堆 | ❌ |
| `-XX:MaxDirectMemorySize=64m` | `ByteBuffer.allocateDirect` | ❌ |
| `-XX:MaxMetaspaceSize=128m` | 类元数据 | ❌ |
| **容器 `mem_limit`** | 整个进程的物理内存 | ✅ **只有它** |

前三个是 **JVM 自己记账** 的，`Unsafe.allocateMemory` 是**裸 malloc**，
JVM 根本不知道这块内存存在。所以现象不是 `OutOfMemoryError`，
而是 **cgroup 先到上限 → 内核 SIGKILL**。

### 实测

```bash
./lab.sh trigger native-soft      # 温和版，300MB，不打死容器
```

```
heap    : 20 → 21 MB      ← 纹丝不动
direct  :  0 MB           ← 纹丝不动
rss     : 317 → 638 MB    ← +320MB
cgroup  : 299 → 620 / 2048 MB
```

继续灌到 80% 之后：

```
./lab.sh trigger native-leak      # 1400MB，会打死容器
→ 容器 Exited (137)，OOMKilled=true，且**不会自动重启**
```

### ★ 怎么和 Java OOM 区分开

| | Java OOM | 本地内存泄漏 |
|---|---|---|
| 异常 | `OutOfMemoryError` + 完整堆栈 | **没有**（`grep OutOfMemoryError` 计数为 0）|
| 日志 | 有完整的现场 | **戛然而止** |
| 应用 | 还活着，能返回响应 | **已经死了，仪表盘打不开** |
| 证据在哪 | 应用日志里 | **容器外面** |

事后取证只能从外面来：

```bash
docker inspect jvm-lab --format 'OOMKilled={{.State.OOMKilled}}'   # true
docker ps -a --filter name=jvm-lab                                  # Exited (137)
dmesg -T | grep -i 'oom\|killed process'
# Memory cgroup out of memory: Killed process 827761 (java)
#   anon-rss:2091372kB  constraint=CONSTRAINT_MEMCG
```

`constraint=CONSTRAINT_MEMCG` 这行很关键 —— 它说明是**容器自己的限制**触发的，
不是宿主内存不够。两者的处置方式完全不同。

### 怎么定位（在出事之前）

```bash
./lab.sh trigger native-stats    # 对比 heap / direct / rss 三个数字
./lab.sh trigger native-nmt      # jcmd VM.native_memory summary
```

**核心手法：三个数字对不上，差值就是本地内存。**
前两个是 JVM 记账的，`rss` 是进程实际占的物理内存。

NMT 会告诉你哪个**类别**在涨：

```
-  Other (reserved=327768KB, committed=327768KB)   ← 就是它
```

但 `summary` 级别**看不到调用栈**。要知道"是谁分配的"必须用 `detail`：

```bash
./lab.sh restart nmt-detail      # 级别只能在**启动时**指定，事后改不了
./lab.sh trigger native-soft
./lab.sh trigger native-nmt      # 这次输出里会有调用栈
```

**"级别只能在启动时定"本身就是一个值得记住的点** ——
线上出了事才想起来开 detail，只能等下次重启。

### 真实世界里它长什么样

纯 Java 代码很少直接 `Unsafe.allocateMemory`。真实的本地内存泄漏来自
**JNI 库在 native 代码里 malloc 了却忘了 free**：

- Netty 的池化分配器（`-Dio.netty.maxDirectMemory` 相关的坑）
- 压缩库（zstd / snappy 的 native 实现）
- JDBC 驱动（尤其是老的 Oracle / DB2 驱动）
- 各类加解密、图像处理的 native 实现

表现都一样：**堆正常、GC 正常、CPU 正常，但 RSS 一直涨，最后被 OOM kill**。

### 处理

1. **先确认是不是本地内存** —— 三个数字对不上就是
2. **定位到具体的库** —— NMT detail、`pmap`、`/proc/<pid>/smaps`
3. **升级或替换那个库** —— 这类泄漏你改不了，只能等上游修
4. **临时兜底** —— 限制那个库的缓存上限（大多数库都有配置项），
   或者把有问题的功能拆到独立进程里

**注意**：`-Xmx` 调小、换 GC、调 GC 参数 —— 这些**全都没用**。
本地内存不归 JVM 管。

### 演练场里的两个"作弊"之处

- `trigger native-free` 能把它还回去 —— 真实泄漏**还不了**，指针早丢了。
  这个接口的定位是"模拟修好代码后重启"，不是"有一个能救急的按钮"。
- 用 `Unsafe.allocateMemory` 而不是真的 JNI —— 纯 Java 环境写不了 JNI，
  但语义完全一致（拿了就还不了，JVM 不记账）。

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

## 3. Arthas：线上排错、性能定位、热更新

前面所有场景都是"**制造**问题"，Arthas 是"**在没有现场的情况下把问题挖出来**"。

它解决的是一个很具体的困境：**线上出问题了，但日志不够用，而你又不能重启加日志。**
Arthas 直接挂到运行中的 JVM 上，看方法调用、耗时、入参出参，甚至改代码 —— 都不用重启。

已打进镜像（`/opt/arthas`，4.3.5），不需要联网下载。

```bash
./lab.sh arthas-demo      # 打印三个场景的完整步骤（照着贴就行）
./lab.sh arthas           # 进交互式控制台
./lab.sh arthas "命令"    # 跑一批命令后退出
./lab.sh arthas-hotfix    # 一键跑完整热更新链路
./lab.sh arthas-reset     # 重置 Arthas 服务端（见下方"驻留"那条）
```

靶子是 `/demo/*` 那几个接口，里面**故意埋了四处问题**。

### 3.1 排错定位 —— 慢在哪一层？

```bash
curl -s 'localhost:8081/demo/order?id=1'    # 总耗时 300ms
```

300ms 是慢，但**慢在哪一层**？日志里没有。用 `trace`：

```bash
ARTHAS_WAIT=20 ./lab.sh arthas "trace lab.jvm.controller.ArthasDemoController order"
# 另一个终端反复打接口
for i in 1 2 3 4 5; do curl -s -o /dev/null "localhost:8081/demo/order?id=$i"; sleep 3; done
```

实测输出：

```
`---[300.984231ms] ArthasDemoController:order()
    +---[0.01% 0.033841ms ] validate()          #78
    +---[99.76% 300.270299ms ] quotePrice()     #79   ← 就是它
    +---[0.01% 0.01888ms ] checkInventory()     #80
    +---[0.01% 0.033921ms ] applyDiscount()     #81
    `---[0.09% 0.272325ms ] formatStamp()       #82
```

**不用改一行代码、不用重启，直接看到时间花在哪个方法上。**

看入参出参同样不用加日志：

```bash
ARTHAS_WAIT=20 ./lab.sh arthas "watch lab.jvm.controller.ArthasDemoController applyDiscount '{params, returnObj}' -x 2"
```

> **为什么要 `ARTHAS_WAIT`**：`trace` 的语义是"等目标方法被调用"，命令本身
> 不返回。不给观察窗口的话脚本会立刻发 `quit`，等于什么都没看到。
> 而且它只对**命令生效之后**发生的调用生效 —— 所以要配合打接口。

### 3.2 性能瓶颈 —— 谁在烧 CPU

```bash
for i in 1 2 3; do curl -s -o /dev/null 'localhost:8081/demo/format-loop?times=30000'; done
./lab.sh arthas "profiler start"
curl -s -o /dev/null 'localhost:8081/demo/format-loop?times=30000'
./lab.sh arthas "profiler stop --format html --file /tmp/flame.html"
docker exec -i jvm-lab sh -c 'cat /tmp/flame.html' > flame.html
```

火焰图上 `SimpleDateFormat.<init>` 会非常显眼 —— 那是"每次调用都 new 一个"的代价。
这个错误在代码 review 时基本看不出来，在火焰图上藏不住。

看最忙的线程：`./lab.sh arthas "thread -n 3"`；只找死锁：`thread -b`。

### 3.3 紧急热更新 —— 改一个字符，但发版要等几小时

```bash
./lab.sh arthas-hotfix
```

一键跑完 `jad → 改 → mc → retransform → 验证`，实测返回值 **90 → 80**，**进程没有重启**。

想手工走一遍（`arthas-hotfix` 里的每一步）：

| 步骤 | 命令 |
|---|---|
| ① 反编译 | `./lab.sh arthas "jad --source-only lab.jvm.controller.ArthasDemoController"` |
| ② 改一个字符 | `applyDiscount` 里 `* 9L / 10L` → `* 8L / 10L` |
| ③ 取 ClassLoader hash | `./lab.sh arthas "sc -d lab.jvm.controller.ArthasDemoController"` |
| ④ 送进容器 | `docker exec -i jvm-lab sh -c 'cat > /tmp/ArthasDemoController.java' < 改好的.java` |
| ⑤ 编译 | `./lab.sh arthas "mc -c <hash> /tmp/ArthasDemoController.java -d /tmp"` |
| ⑥ 生效 | `./lab.sh arthas "retransform /tmp/lab/jvm/controller/ArthasDemoController.class"` |
| ⑦ 验证 | `curl -s 'localhost:8081/demo/price?amount=100&vip=true'` |

> **改动只在内存里，不落盘** —— `./lab.sh restart` 就恢复原样。
> 生产上用这个要非常克制：**绕过发版流程 = 绕过代码评审和回滚机制**。

### 3.4 ★ 踩过的坑（每一条都是实测出来的）

这些坑的共同点是：**现象和根因隔得很远，不看注释根本猜不到**。

| 坑 | 现象 | 根因 |
|---|---|---|
| **`user.home` 是目标 JVM 的** | `jad` 报 `/root/logs/arthas/classdump` 没有写权限，但给客户端传 `-Duser.home` 完全没用 | Arthas 核心是作为 **agent 跑在目标 JVM 里**的，classdump 路径在那里解析。必须给**目标 JVM** 加 `-Duser.home=/tmp` |
| **Arthas 服务端会驻留** | 改了启动参数却一直不生效 | agent 一旦 attach 就常驻，参数第一次就定死了。要 `./lab.sh arthas-reset` 换掉 |
| **tmpfs 默认 `noexec`** | `profiler start` 报 `failed to map segment from shared object` | Docker 给 tmpfs 默认加 `noexec`，而 async-profiler 是 native 库需要 `mmap` 成可执行段。本 lab 单开了带 `exec` 的 `/arthas-tmp` |
| **`mc` 不带 `-parameters`** | 热更新后接口 500：`Name for argument of type [int] not specified` | `mc` 编译时没有 `-parameters`，字节码里没有参数名，Spring 解析不了。**更阴险的是它不一定立刻暴露** —— Spring 缓存了参数元数据，"热更新前调用过"的接口照常工作，只有"热更新后才第一次调用"的才会炸 |
| **`mc` 要求文件名 = 类名** | `class X is public, should be declared in a file named X.java` | Java 语言规定。源码存成 `Demo.java` 就是不行 |
| **输出带 ANSI 转义码** | `\| grep classLoaderHash` 匹配不上，终端里看着却正常 | 实际内容是 `\033[1mclassLoaderHash\033[0m`。`NO_COLOR=1` 和 `TERM=dumb` 都关不掉，只能在输出不是终端时自己剥 |
| **长驻命令拦截单键** | 发 `quit` 后卡死，报 `uit: command not found` | `trace`/`watch` 这类命令会拦截单键输入（`q` 用来中止自己），把 `quit` 开头的 `q` 吃掉了。必须先发一个单独的 `q` 再发 `quit` |
| **非 TTY 下客户端不退出** | 用 `-f <文件>` 或 `-c "<命令>"` 每次都挂到超时，还留下一堆僵尸 JVM | 非 TTY 时 arthas 客户端跑完命令不会自己退。改用 stdin 管道，且**逐条喂、留间隔** —— 一次性灌进去会丢输出（实测 3 次里 1 次丢） |

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
