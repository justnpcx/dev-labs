# Heap Dump 与 Thread Dump 分析指南

## 0. 先建立心智模型：两者是不同的问题

| | **Heap Dump** | **Thread Dump** |
|---|---|---|
| 是什么 | 某一时刻堆里**所有对象**的完整快照 | 某一时刻**所有线程的调用栈** |
| 体积 | 和 `-Xmx` 同量级（几百 MB） | 几十 KB |
| 采集成本 | **会 STW**，堆大时停顿数秒到数十秒 | 几乎零成本，可随时采 |
| 回答什么问题 | 谁在占内存？**谁持有它不让它释放？** | 线程卡在哪？**谁在等谁？** |
| 类比 | 内存的 X 光片 | 线程的集体照 |
| 对应命令 | `./lab.sh dump-heap` | `./lab.sh dump-thread 3 5` |

**排查 OOM 通常两个都要看**：
先用 thread dump 排除"线程暴涨导致的 native OOM"，再用 heap dump 定位"谁在占堆"。

---

# 第一部分：Heap Dump

## 1.1 怎么拿到

| 方式 | 命令 | 适用场景 |
|---|---|---|
| **网页一键生成** | 仪表盘「日志与快照」→ 生成堆快照 | ★ 最省事，生成后可直接点下载 |
| OOM 自动落盘 | `-XX:+HeapDumpOnOutOfMemoryError` | ★ 生产必备，本项目已开 |
| 主动 dump | `./lab.sh dump-heap before` | 做前后对比 |
| 容器内 | `jcmd 1 GC.heap_dump /dumps/x.hprof` | 最原始的方式 |
| 只 dump 存活对象 | `jmap -dump:live,format=b,file=x.hprof 1` | 先 Full GC 再 dump，文件小很多 |
| HTTP 接口 | `curl localhost:8081/actuator/heapdump -o x.hprof` | 不方便进容器时 |

> OOM 自动落盘的文件名是 `java_pid<pid>.hprof`，本演练场里 JVM 是 PID 1，
> 所以是 `dumps/java_pid1.hprof`（约 235MB）。
>
> ⚠️ **但它不一定存在**，实测有三个坑：
> 1. **每个 JVM 进程只落一次盘** —— HotSpot 用静态原子标记保证 OOM 报告只做一次。
>    想每次都留证据必须重启 JVM。
> 2. **只有堆和元空间 OOM 会落盘**；直接内存 OOM 和线程数 OOM **不会**。
> 3. 235MB 经 Cloudflare 下载大概率 502 —— 用手动 live 快照（约 31MB）替代。
>
> 详细实测数据见 [UI-GUIDE.md](UI-GUIDE.md) 的「堆快照的三个反直觉行为」。

> ⚠️ **不要在生产高峰期随手 dump**。`jcmd GC.heap_dump` 会 STW；
> `jmap -dump:live` 更狠，它会先触发一次 Full GC。

## 1.2 不用任何工具的第一步：直方图

大部分问题到这一步就有方向了，比打开 MAT 快得多。

```bash
# 先做一次 Full GC 再统计，去掉垃圾对象干扰（数字更干净）
docker exec jvm-lab jmap -histo:live 1 | head -30
# 或者
docker exec jvm-lab jcmd 1 GC.class_histogram | head -30
```

输出长这样，按**占用字节数**倒序：

```
 num     #instances         #bytes  class name
------------------------------------------------
   1:         20480      536870912  [B                    ← byte 数组，512MB
   2:         20480       655360  lab.jvm.support.LeakEntry  ← 2 万个实例
   3:          1200        96000  [I
```

**判读方法：间隔一段时间采两次，对比 `#instances` 谁在涨。**
涨的那个类就是泄漏的载体。在本演练场里，预期看到 `LeakEntry` 和 `[B` 同步增长。

⚠️ 光看 `[B` 只能知道"有很多 byte 数组"，**不知道是谁在持有它们**。
要回答"谁持有"，必须上 MAT 的 Path to GC Roots。

## 1.3 MAT 的核心概念：Shallow vs Retained（最重要的一节）

这两个概念搞不清，MAT 就白用了。

| 概念 | 定义 | 例子 |
|---|---|---|
| **Shallow Heap** | 对象**自身**占的字节，不含它引用的对象 | 一个 `HashMap` 对象本身 ≈ 48 字节 |
| **Retained Heap** | 这个对象被回收后，**能连带释放**的总字节 | 同一个 `HashMap`，装满了数据 → 可能是 500MB |

**看内存问题永远看 Retained Heap，不看 Shallow Heap。**

一个 `ConcurrentHashMap` 的 Shallow Heap 只有几十字节，看起来人畜无害；
但它的 Retained Heap 可能是 500MB —— 因为整个泄漏的对象图都挂在它下面。

## 1.4 MAT 的四个视图 + 一个关键操作

| 视图 | 作用 | 什么时候用 |
|---|---|---|
| **Histogram** | 按类聚合，看实例数和字节数 | 快速找"哪个类数量异常" |
| **Dominator Tree** | 按**支配关系**排序，按 Retained Heap 倒序 | ★ 找"谁真正占着内存"，最常用 |
| **Top Consumers** | MAT 预先算好的 Top 类 / 包 / ClassLoader | 想快速知道"大头在哪" |
| **Leak Suspects** | 自动生成的泄漏嫌疑报告 | ★ 入口，但**不要盲信**，它经常误报 |

### 关键操作：Path to GC Roots（排除弱引用）

这是从"谁占内存"走到"谁**不让它释放**"的唯一路径。

```
在 Histogram / Dominator Tree 里右键某个对象
  → Merge Shortest Paths to GC Roots
  → exclude all phantom/weak/soft references      ← 必须选这个
```

**为什么必须排除弱引用？**
弱引用（`WeakReference`/`SoftReference`）**本来就不阻止对象被回收**。
如果不排除，你会看到一堆 `WeakReference` 持有链，全是噪音，把真正的问题藏起来。
（典型噪音源：`ThreadLocalMap`、各种缓存框架的弱引用包装。）

排除之后的引用链，就是真正导致对象无法回收的那条路。

## 1.5 实战流程：两次 dump 对比法

```bash
# 1. 基线
./lab.sh dump-heap before

# 2. 制造泄漏（多来几次，让差异明显）
./lab.sh trigger leak
./lab.sh trigger leak
./lab.sh trigger leak

# 3. 再采一次
./lab.sh dump-heap after
```

然后在 MAT 里：

1. 打开 `before.hprof`，再打开 `after.hprof`
2. 各看 Histogram，把两个 Histogram 加入 **Compare Basket**（工具栏那个对比图标）
3. 找**实例数增长最多**的类
4. 在这个类上右键 → `Merge Shortest Paths to GC Roots` → `exclude all phantom/weak/soft references`
5. 顺着引用链往上走，直到看到一个"不该长期活着"的持有者

**本演练场的预期结果**：

```
LeakEntry 实例数：before = 0  →  after = 15
引用链：
  lab.jvm.support.LeakEntry
  ← java.util.concurrent.ConcurrentHashMap$Node.val
  ← java.util.concurrent.ConcurrentHashMap$Node[]
  ← java.util.concurrent.ConcurrentHashMap.table
  ← lab.jvm.support.LeakRegistry.STATIC_CACHE      ★ 就是这里：静态缓存，永不淘汰
  ← <Java System Class Loader> 的静态字段
  ← (GC Root)
```

看到 `static` 字段 + `ConcurrentHashMap` + 只增不减 → 结论就是：
**缓存没有淘汰策略 / 没有 TTL**。这是生产环境第一大泄漏类型。

## 1.6 没有 GUI 时的替代方案

```bash
# 1) MAT 的命令行版（arm64 有构建）
#    下载后：
./ParseHeapDump.sh /path/to/dump.hprof org.eclipse.mat.api:suspects
#    会生成 leak suspects 的 HTML 报告

# 2) 直方图（够用）
docker exec jvm-lab jcmd 1 GC.class_histogram

# 3) Arthas 直接在线看实例（不用 dump 文件）
#    ./lab.sh shell 进去后：
#    arthas-boot.jar 附着到 PID 1
#    vmtool --action getInstances --className lab.jvm.support.LeakEntry --limit 5
```

---

# 第二部分：Thread Dump

## 2.1 怎么拿到

| 方式 | 命令 | 适用场景 |
|---|---|---|
| **网页一键采集** | 仪表盘「日志与快照」→ 采集线程栈 | ★ 最省事。默认 **3 次采样**，采完自动打开 |
| 连续采样 | `./lab.sh dump-thread 3 5` | 采 3 次，间隔 5 秒 |
| 容器内 | `jcmd 1 Thread.print` | 最原始 |
| 单个线程 | `jcmd 1 Thread.print -l` | 带锁信息（默认就有） |
| JSON 格式 | `curl localhost:8081/actuator/threaddump` | 要给程序解析时 |
| 触发式 | `kill -3 1` | 打到容器标准输出 |

> **为什么默认要采 3 次**：单次 dump 只能告诉你"此刻在哪"，看不出"卡住没动"。
> 必须连续采样对比 —— 见 2.6 节。

## 2.2 一行怎么读

```
"lab-deadlock-A" #25 prio=5 os_prio=0 cpu=1.23ms elapsed=45.67s tid=0x0000ffff8c1a2000 nid=0x2a waiting for monitor entry
│                │   │            │              │               │                      │    └ 当前状态
│                │   │            │              │               │                      └ nid：操作系统线程号（十六进制）★
│                │   │            │              │               └ tid：JVM 内部线程 ID
│                │   │            │              └ elapsed：线程存活时长
│                │   │            └ cpu：累计消耗的 CPU 时间 ★
│                │   └ os_prio：操作系统优先级
│                └ JVM 内优先级
└ 线程名 ★ 给线程起名是最有价值的排查投资
```

下一行是关键状态：
```
   java.lang.Thread.State: BLOCKED (on object monitor)
```

## 2.3 四种状态的含义（以及最容易踩的误解）

| 状态 | 含义 | 排查意义 |
|---|---|---|
| `RUNNABLE` | 正在执行，或阻塞在 IO 上 | 若栈停在业务代码里且 CPU 高 → 死循环/热点 |
| `BLOCKED` | 等待 `synchronized` 锁 | 大量线程 `BLOCKED` 指向同一把锁 → 锁竞争或死锁 |
| `WAITING` | 无限期等待（`wait()`/`join()`/`park()`） | 线程池空闲线程通常就是它，**正常** |
| `TIMED_WAITING` | 限时等待（`sleep(n)`/`parkNanos`） | 大量堆在 sleep / 等连接超时 |

> ⚠️ **`RUNNABLE` 不等于"正在消耗 CPU"**。
> 等 socket 读、等文件 IO 的线程状态也是 `RUNNABLE`。
> 判断是否吃 CPU，必须看**栈里是什么** —— 栈在 `socketRead0` 是等 IO，栈在业务循环里才是真烧 CPU。

## 2.4 锁信息怎么读（死锁判读的基础）

```
- waiting to lock <0x0000000712a1b2c3> (a java.lang.Object)   ← 我正在等这把锁
- locked <0x0000000712a1b2c3> (a java.lang.Object)            ← 我正拿着这把锁
- parking to wait for <0x...> (a java.util.concurrent.locks...)  ← AQS 的 park
```

**判读诀窍：盯那个 `0x` 地址。**
同一个地址，在 A 线程里是 `locked`，在 B 线程里是 `waiting to lock` —— 这就是锁竞争的现场。

## 2.5 死锁段落：JVM 直接告诉你答案

`jcmd Thread.print` 的**末尾**会有一段：

```
Found one Java-level deadlock:
=============================
"lab-deadlock-A":
  waiting to lock monitor 0x0000ffff6c0012c8 (object 0x0000000712a1b2c3, a java.lang.Object),
  which is held by "lab-deadlock-B"

"lab-deadlock-B":
  waiting to lock monitor 0x0000ffff6c003a58 (object 0x0000000712a1b2c3, a java.lang.Object),
  which is held by "lab-deadlock-A"

Java stack information for the threads listed above:
===================================================
...
Found 1 deadlock.
```

JVM 内部维护着锁的**等待图**，一旦检测到环就直接报出来 —— 不需要你自己推。
你只需要搜 `Found one Java-level deadlock` 这个字符串。

## 2.6 三次采样法（这一节最重要）

**单次 thread dump 只能告诉你"此刻在哪"，无法告诉你"是不是卡住不动了"。**

正常工作的线程，栈是一直在变的。所以正确做法是连续采样、然后对比：

```bash
./lab.sh dump-thread 3 5      # 采 3 次，每次间隔 5 秒
```

然后在输出文件里搜同一个线程名，比较三次的栈：

- **栈完全一样** → 真的卡住了 ★ 这就是你要找的
- **栈在变化** → 它在正常干活，别管它

这也是为什么 `./lab.sh dump-thread` 默认支持连续采样 —— 单次采集基本没有诊断价值。

## 2.7 症状 → 判读对照表

| 症状 | thread dump 里的特征 |
|---|---|
| CPU 100% | 少数线程 `RUNNABLE`，栈停在业务循环里；用 `nid` 对应 `top -H` |
| 接口全部卡住 | 大量 `BLOCKED`，`waiting to lock` 指向**同一个地址** |
| 死锁 | 搜到 `Found one Java-level deadlock` |
| 线程数暴涨 | 同名线程几百个（如 `lab-held-thread-*`），栈都停在同一个地方 |
| 连接池耗尽 | 大量线程栈里出现 `getConnection` / `borrowObject` / `pool.take` |
| 内存泄漏 | **thread dump 通常看不出来**，必须上 heap dump。但如果大量线程各自持有一个大对象，也能从"线程数 × 单线程持有量"估算 |

---

# 第三部分：本演练场的对照实验

| 演练场景 | 该看哪种 dump | 预期看到什么 |
|---|---|---|
| `trigger heap` | Heap | `[B` 实例数暴涨，Retained 巨大；Path to GC Roots 指向 `LeakRegistry.BYTE_BUCKETS` |
| `trigger leak` | Heap ★ | `LeakEntry` 实例数从 0 递增；Roots 指向 `STATIC_CACHE`（静态缓存无淘汰） |
| `trigger metaspace` | 都不用 | Metaspace 不在堆里！看 `jstat -gcutil` 的 `M` 列，或用 `jcmd VM.native_memory` |
| `trigger direct` | 都不用 | 直接内存也不在堆里，看 `/status` 的 `directBuffer.usedMb` |
| `trigger threads` | Thread | 几百个 `lab-held-thread-*`，状态全是 `TIMED_WAITING`（在 sleep） |
| `trigger stack` | Thread | 一个线程的栈深达几千层，全是 `lab.jvm.controller.ThreadController.recurse` |
| `trigger deadlock` | Thread ★ | 末尾 `Found one Java-level deadlock`，两个线程互相等 |
| `trigger cpu` | Thread ★ | `lab-cpu-spin-*` 状态 `RUNNABLE`，`cpu=` 数值很大，栈停在 `ThreadController.lambda` |

> **注意 `trigger metaspace` 和 `trigger direct` 这两行**：
> 它们的 OOM 都**不发生在堆里**，所以 heap dump 里什么都看不到。
> 这是排查 OOM 时最容易走错的路 —— 先确认是**哪块内存**爆了，再决定用什么工具。
> 用 `/status` 一眼就能分辨：`heap.usedMb` 满了是堆问题，
> `metaspace.usedMb` 满了是元空间问题，`directBuffer.usedMb` 满了是堆外问题。

---

# 第四部分：采集时的注意事项

1. **heap dump 会 STW**，堆越大停顿越久。生产环境避开高峰，优先用 `-XX:+HeapDumpOnOutOfMemoryError` 让它自动落盘。
2. **dump 文件含敏感数据** —— 堆里有用户的密码、token、手机号。不要随手外发，分析完及时删。
3. **thread dump 几乎无成本**，可以放心连续采。`dump-thread 3 5` 是安全且推荐的做法。
4. **容器里 dump 必须挂载卷**。本项目已经把 `./dumps` 和 `./logs` 映射出来了，容器删了数据还在。
5. **`jmap -dump:live` 会先触发 Full GC**，这是它的优点（文件干净）也是风险（停顿更长）。线上慎用。
6. **及时清理**：一次堆 OOM 产生约等于 `-Xmx` 的 hprof。本项目 256m 堆 → 约 240MB 文件。定期 `./lab.sh clean`。
