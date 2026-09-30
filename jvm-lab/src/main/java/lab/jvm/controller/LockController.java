package lab.jvm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 锁竞争演练。
 *
 * 死锁（/lock/deadlock）是"卡住不动"，一眼能看出来。
 * **锁竞争**要阴险得多：程序照常跑，只是吞吐量莫名其妙上不去 ——
 * 而且加线程越多越慢。这类问题不看 jstack 基本发现不了。
 *
 * 两个现象：
 *   1. 竞争现场 —— 一堆线程 BLOCKED 在 "waiting to lock" 上，可以直接 jstack 抓
 *   2. 量化对比 —— 全局锁 vs 分段锁 vs 无锁，跑同一个计数任务，看耗时差多少
 */
@RestController
public class LockController {

    private static final Logger log = LoggerFactory.getLogger(LockController.class);

    /** 一把全局锁，所有线程抢它 —— 这就是竞争的源头 */
    private static final Object GLOBAL_LOCK = new Object();

    /** 分段锁：每个线程一把。争用消失了，但代价是计数不再是一个整体 */
    private static final Object[] STRIPED_LOCKS = new Object[64];

    static {
        for (int i = 0; i < STRIPED_LOCKS.length; i++) {
            STRIPED_LOCKS[i] = new Object();
        }
    }

    private static final AtomicLong ATOMIC = new AtomicLong();
    private static final AtomicLong PLAIN = new AtomicLong();

    /** 持有锁不放的线程，用于制造可观察的 BLOCKED 现场 */
    private static final List<Thread> HOLDERS = new ArrayList<>();

    /**
     * 制造锁竞争现场：N 个线程同时抢一把锁，每个拿到后持有 holdMillis 才放。
     *
     * 期望：jstack 里 1 个线程 RUNNABLE，其余全部 BLOCKED 在
     *      "- waiting to lock <0x...> (a java.lang.Object)"
     *
     * 这比死锁更常见：**没有环，只是排队**。死锁是 A 等 B、B 等 A，
     * 而这里是 8 个人抢 1 个坑位 —— 结构上完全正常，性能上完全不能接受。
     */
    @GetMapping("/lock/contention")
    public Map<String, Object> contention(@RequestParam(defaultValue = "8") int threads,
                                          @RequestParam(defaultValue = "5000") long holdMillis) {
        int started = 0;
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    synchronized (GLOBAL_LOCK) {
                        // 真实场景里这里可能是：一次远程调用、一次慢查询、
                        // 或者干脆只是"锁的范围写大了" —— 把不该包进来的耗时操作包进来了
                        try {
                            Thread.sleep(holdMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "lab-lock-contention-" + i);
            t.setDaemon(true);
            t.start();
            HOLDERS.add(t);
            started++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "已启动竞争线程");
        result.put("threads", started);
        result.put("holdMillis", holdMillis);
        result.put("expectedThroughput",
                String.format("%.1f 次/秒（= 1000/%d，和线程数无关）", 1000.0 / holdMillis, holdMillis));
        result.put("note", "无论开几个线程，同一时刻只有 1 个能进临界区 —— "
                + "吞吐量被锁串行化了。加线程不会变快，只会让队列更长。");
        result.put("howToObserve", "jstack <pid> | grep -A 3 'lab-lock-contention'  "
                + "→ 看 BLOCKED 和 'waiting to lock'");
        result.put("howToStop", "POST /lock/stop");
        log.info("锁竞争现场已建立：{} 个线程抢 1 把锁，各持有 {}ms", started, holdMillis);
        return result;
    }

    /**
     * 量化对比三种计数方式的吞吐。
     *
     * 同一个任务（把计数器加 iterations 次），三种实现：
     *   全局锁   synchronized (GLOBAL_LOCK)   —— 完全串行
     *   分段锁   每线程一把锁                  —— 完全并行
     *   无锁     AtomicLong                    —— 完全并行 + CAS
     *
     * 期望结果：全局锁明显最慢，而且**线程数越多，差距越大**。
     * 分段锁和 AtomicLong 接近；AtomicLong 在高争用下会因为 CAS 自旋而略慢于分段锁。
     */
    @GetMapping("/lock/benchmark")
    public Map<String, Object> benchmark(@RequestParam(defaultValue = "8") int threads,
                                         @RequestParam(defaultValue = "500000") int iterations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads", threads);
        result.put("iterationsPerThread", iterations);
        result.put("totalOps", (long) threads * iterations);

        result.put("globalLockMillis", timeIt(threads, iterations, Mode.GLOBAL_LOCK));
        result.put("stripedLockMillis", timeIt(threads, iterations, Mode.STRIPED_LOCK));
        result.put("atomicMillis", timeIt(threads, iterations, Mode.ATOMIC));

        result.put("note", "同一份工作，只换了同步方式。全局锁的耗时基本等于"
                + "『单线程耗时 × 线程数』—— 因为临界区是串行的。");
        result.put("howToObserve", "跑的时候同时开一个终端："
                + "jstack <pid> | grep -c BLOCKED  —— 全局锁阶段这个数字会很高");
        result.put("caveat", "这是刻意简化的微基准，不能当生产结论："
                + "① 没有预热（见 /jit/warmup）② 计数器加法太短，锁开销被放大 "
                + "③ 真实临界区往往更长，锁的占比会小很多。");
        return result;
    }

    /** 停掉竞争线程。不停的话它们会一直占着 CPU 和锁。 */
    @GetMapping("/lock/stop")
    public Map<String, Object> stop() {
        int n = 0;
        synchronized (HOLDERS) {
            for (Thread t : HOLDERS) {
                t.interrupt();
                n++;
            }
            HOLDERS.clear();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "已打断竞争线程");
        result.put("stopped", n);
        result.put("note", "这些线程在 sleep 里被 interrupt，会立刻退出；"
                + "如果是真实的死锁，interrupt 是没用的");
        return result;
    }

    private enum Mode { GLOBAL_LOCK, STRIPED_LOCK, ATOMIC }

    private long timeIt(int threads, int iterations, Mode mode) {
        PLAIN.set(0);
        ATOMIC.set(0);
        Thread[] ts = new Thread[threads];

        long t0 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                switch (mode) {
                    case GLOBAL_LOCK -> {
                        // 所有人都抢同一把锁
                        for (int k = 0; k < iterations; k++) {
                            synchronized (GLOBAL_LOCK) {
                                PLAIN.incrementAndGet();
                            }
                        }
                    }
                    case STRIPED_LOCK -> {
                        // 每个人抢自己那把，互不干扰
                        Object lock = STRIPED_LOCKS[idx % STRIPED_LOCKS.length];
                        for (int k = 0; k < iterations; k++) {
                            synchronized (lock) {
                                PLAIN.incrementAndGet();
                            }
                        }
                    }
                    case ATOMIC -> {
                        for (int k = 0; k < iterations; k++) {
                            ATOMIC.incrementAndGet();
                        }
                    }
                }
            }, "lab-lock-bench-" + mode + "-" + i);
            ts[i].start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** 供 /reset 使用。 */
    public static int stopContentionThreads() {
        int n = 0;
        synchronized (HOLDERS) {
            for (Thread t : HOLDERS) {
                t.interrupt();
                n++;
            }
            HOLDERS.clear();
        }
        return n;
    }
}
