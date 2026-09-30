package lab.jvm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池演练 —— 生产事故里出现频率最高的一类。
 *
 * 为什么它比 OOM 更值得练：
 *   OOM 至少会崩，你会立刻知道出事了。
 *   线程池配错往往**不崩**：队列无限堆积、任务被静默丢弃、请求超时但服务还活着 ——
 *   表现出来是"偶发超时"、"数据偶尔对不上"，最难排查。
 *
 * 三个必练现象：
 *   1. 无界队列 → 队列把堆吃光（本质还是 OOM，但根因在线程池配置）
 *   2. 拒绝策略选错 → 任务被静默丢弃，比抛异常危险得多
 *   3. 有界队列 + 正确策略 → 快速失败 / 背压
 *
 * ⚠ 和 OOM 场景一样，观察完请调 POST /reset（会关掉这里创建的线程池）。
 */
@RestController
@RequestMapping("/pool")
public class PoolController {

    private static final Logger log = LoggerFactory.getLogger(PoolController.class);

    /** 当前演练用的池子，静态持有是为了 /reset 能关掉它们 */
    private static volatile ThreadPoolExecutor currentPool;

    /** 各策略下被拒绝（或本应被拒绝）的任务数，用于对比 */
    private static final AtomicInteger COMPLETED = new AtomicInteger();

    /**
     * 无界队列：队列无限长，`execute` 永远不抛 RejectedExecutionException。
     *
     * 期望：java.lang.OutOfMemoryError: Java heap space
     *
     * 这里刻意让每个任务**捕获一份 payload**（模拟请求上下文：解析好的报文、
     * 用户对象、SQL 结果集……）。这是关键 —— 队列里堆的不只是任务对象本身，
     * 而是**每个待处理请求的完整上下文**。真实的 OOM 就是这么来的。
     *
     * 所以默认参数会打爆：2 个线程消费，4000 个任务 × 64KB payload ≈ 256MB。
     *
     * ★ 这就是为什么《阿里巴巴 Java 开发手册》强制要求：
     *   不用 Executors.newFixedThreadPool / newSingleThreadExecutor ——
     *   它们内部就是无界的 LinkedBlockingQueue，队列能一直涨到把堆吃光。
     */
    @GetMapping("/unbounded")
    public Map<String, Object> unbounded(@RequestParam(defaultValue = "4000") int tasks,
                                         @RequestParam(defaultValue = "64") int payloadKb,
                                         @RequestParam(defaultValue = "1000") long taskMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        COMPLETED.set(0);

        // 只有 2 个线程消费，队列不设上限 —— 提交速度远超消费速度
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),          // ← 无界。问题就在这一行
                namedFactory("lab-pool-unbounded-"));
        currentPool = pool;

        int submitted = 0;
        int queueDepthAtOom = 0;
        try {
            for (int i = 0; i < tasks; i++) {
                // 模拟"每个请求的上下文"。任务被排进队列时，这块内存就跟着一起等
                byte[] payload = new byte[payloadKb * 1024];
                payload[0] = (byte) i;
                payload[payload.length - 1] = (byte) i;   // 触碰尾字节，确保真的提交物理页

                pool.execute(() -> {
                    try {
                        Thread.sleep(taskMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // 读一下 payload，防止被 JIT 判定为无用而优化掉
                    if (payload[0] == 127) {
                        log.debug("unreachable {}", payload.length);
                    }
                    COMPLETED.incrementAndGet();
                });
                submitted++;
            }
            result.put("outcome", "no OOM（把 tasks 或 payloadKb 调大再试）");
        } catch (OutOfMemoryError e) {
            log.error("触发 OOM（线程池无界队列）：已提交 {} 个任务 × {}KB payload",
                    submitted, payloadKb, e);
            result.put("outcome", "OutOfMemoryError");
            result.put("errorType", String.valueOf(e.getMessage()));
            result.put("submittedBeforeOom", submitted);
            queueDepthAtOom = pool.getQueue().size();
            result.put("queueDepthAtOom", queueDepthAtOom);
            result.put("approxQueueMb", (long) queueDepthAtOom * payloadKb / 1024);
            result.put("note", "报的是 Java heap space，但根因是队列无界 —— "
                    + "看 jstack 会发现线程本身没问题，堆里全是排队等着的任务上下文");
        }

        // ★ 这一步是必须的，而且很容易漏：
        // shutdownNow() 返回的 List **持有所有被丢弃的任务**，而每个任务都捕获着 payload。
        // 如果把这个 List 存下来（或者只是拿着不用），那几百 MB 就还是可达的。
        // 所以：不保留返回值，关掉池。
        pool.shutdownNow();
        currentPool = null;

        // ⚠ 注意：走到 OOM 分支时，**这个方法的 JSON 响应很可能回不来**。
        // 原因是双重的：
        //   1. 堆在那一刻是 100% 满的，序列化响应本身就会再次 OOM
        //   2. 启动参数带 -XX:+HeapDumpOnOutOfMemoryError，
        //      JVM 会在抛异常之前先在这个线程里尝试写 heapdump
        // 这不是本场景特有的问题 —— 演练场原有的 /oom/heap 也是这样。
        // 真正的"证据"在日志和仪表盘曲线里，不在响应体里：
        //   docker logs jvm-lab | grep -A 5 OutOfMemoryError
        // 所以下面仍然把结果拼出来，能返回就返回，返回不了就靠日志。
        System.gc();

        result.put("droppedOnShutdown", queueDepthAtOom);
        result.put("completed", COMPLETED.get());
        result.put("hint", "对比 /pool/bounded —— 有界队列会在队列满时立刻拒绝，而不是把堆吃光");
        result.put("howToObserve", "jmap -histo:live <pid> | head 看什么对象最多；"
                + "或 /actuator/heapdump 后丢进 MAT 看 dominator tree");
        return result;
    }

    /**
     * 有界队列 + 拒绝策略。
     *
     * 这是**正确的做法**：队列满了就明确地拒绝，而不是无限堆积。
     * 但"怎么拒绝"有四种策略，行为差别巨大：
     *
     *   abort   抛 RejectedExecutionException  —— 快速失败，调用方必须处理
     *   caller  提交任务的线程自己跑           —— **背压**，提交速度被拖慢，最优雅
     *   discard 静默丢掉                      —— ⚠ 最危险：数据没了，还没有任何异常
     *   oldest  丢掉队列里最老的那个再重试提交 —— 适合"只要最新的"场景（如行情推送）
     *
     * 默认用 abort，参数 policy 可切换，跑一遍对比 rejected 的数字。
     */
    @GetMapping("/bounded")
    public Map<String, Object> bounded(
            @RequestParam(name = "policy", defaultValue = "abort") String policyName,
            @RequestParam(defaultValue = "60") int tasks,
            @RequestParam(defaultValue = "16") int queueSize,
            @RequestParam(defaultValue = "50") long taskMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        COMPLETED.set(0);

        RejectedExecutionHandler policy = switch (policyName) {
            case "caller" -> new ThreadPoolExecutor.CallerRunsPolicy();
            case "discard" -> new ThreadPoolExecutor.DiscardPolicy();
            case "oldest" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            default -> new ThreadPoolExecutor.AbortPolicy();
        };

        // 包一层计数 —— 这是这个场景的关键。
        // 光看 execute() 有没有抛异常，你会发现 discard / oldest 的 rejected 永远是 0：
        // 它们**静默**处理掉，调用方毫不知情。那才是最危险的地方。
        // 包一层之后四种策略的拒绝数都能看到，行为差异才变得可比较。
        AtomicInteger rejectedCount = new AtomicInteger();
        RejectedExecutionHandler handler = (r, executor) -> {
            rejectedCount.incrementAndGet();
            policy.rejectedExecution(r, executor);
        };

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),   // ← 有界。满了就是满了
                namedFactory("lab-pool-bounded-"),
                handler);
        currentPool = pool;

        int accepted = 0;
        long t0 = System.currentTimeMillis();

        for (int i = 0; i < tasks; i++) {
            try {
                pool.execute(() -> {
                    try {
                        Thread.sleep(taskMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    COMPLETED.incrementAndGet();
                });
                accepted++;
            } catch (RejectedExecutionException e) {
                // 只有 AbortPolicy 会走到这里 —— 它把拒绝**抛给调用方**，
                // 逼你处理。其他三种策略都是悄悄消化掉，不会走到这个 catch。
                // rejectedCount 已经在包装器里加过了，这里不重复计数。
            }
        }
        long submitCost = System.currentTimeMillis() - t0;
        int rejected = rejectedCount.get();

        result.put("policy", policyName);
        result.put("capacity", "核心线程 2 + 队列 " + queueSize + " = 最多容纳 " + (2 + queueSize));
        result.put("submitted", tasks);
        result.put("accepted", accepted);
        result.put("rejected", rejected);
        result.put("lost", rejected);
        result.put("submitCostMillis", submitCost);
        result.put("note", switch (policyName) {
            case "caller" -> "CallerRunsPolicy：被拒绝的任务由**提交线程自己执行**。"
                    + "所以 submitCost 明显变长 —— 这不是 bug，是**背压**："
                    + "上游被拖慢，自然就不会再猛发请求了。生产环境首选。";
            case "discard" -> "DiscardPolicy：任务被**静默丢弃**。"
                    + "注意 rejected=" + rejected + " 而 submitCost 只有 " + submitCost + "ms —— "
                    + "接口飞快返回成功，但 " + rejected + " 个任务根本没执行，"
                    + "而且**没有异常、没有日志**。这是四种策略里最危险的一种。";
            case "oldest" -> "DiscardOldestPolicy：丢掉队列里最老的任务腾位置，"
                    + "本次丢了 " + rejected + " 个。适合'只关心最新值'的场景"
                    + "（行情推送、监控采样），不适合有业务含义的任务 —— "
                    + "被丢的可能是唯一一条重要消息。";
            default -> "AbortPolicy：队列满直接抛 RejectedExecutionException，"
                    + "本次抛出 " + rejected + " 次。快速失败，"
                    + "但**调用方必须 catch**，否则异常会一路冒到用户那里。";
        });
        result.put("hint", "四种都跑一遍，重点对比 rejected 和 submitCostMillis 这两个数字的组合");

        // 演示完就关，避免 2 个线程继续跑几十秒。
        // 同样不保留 shutdownNow() 的返回值 —— 它会持有被丢弃的任务。
        int droppedBounded = pool.getQueue().size();
        pool.shutdownNow();
        currentPool = null;
        result.put("droppedOnShutdown", droppedBounded);
        return result;
    }

    /** 手动关掉当前演练线程池。 */
    @GetMapping("/stop")
    public Map<String, Object> stop() {
        Map<String, Object> result = new LinkedHashMap<>();
        ThreadPoolExecutor pool = currentPool;
        if (pool == null) {
            result.put("outcome", "没有正在运行的演练线程池");
            return result;
        }
        int dropped = pool.getQueue().size();
        pool.shutdownNow();
        currentPool = null;
        result.put("outcome", "已关闭");
        result.put("droppedTasks", dropped);
        return result;
    }

    /** 线程池里线程的命名很重要 —— jstack 里一眼就能认出是谁。 */    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);        // 守护线程，不阻塞 JVM 退出
            return t;
        };
    }

    /** 给 /reset 用：关掉所有演练线程池。 */
    public static int shutdownAll() {
        ThreadPoolExecutor pool = currentPool;
        if (pool == null) {
            return 0;
        }
        int n = pool.shutdownNow().size();
        currentPool = null;
        return n;
    }
}
