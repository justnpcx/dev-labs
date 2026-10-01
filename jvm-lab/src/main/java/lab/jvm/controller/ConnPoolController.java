package lab.jvm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接池耗尽演练。
 *
 * 和「线程池无界队列」是同一类问题，但现象完全相反，所以值得单独练：
 *
 *   线程池无界队列 → 任务无限堆积 → **堆被吃光**（CPU 忙、内存爆）
 *   连接池耗尽     → 请求排队等连接 → **超时**（CPU 很闲、内存正常）
 *
 * 后者更常见也更难查：监控上 CPU 20%、内存 30%、GC 正常，
 * 但接口大面积超时。因为瓶颈在**等待**，不在计算。
 *
 * 真实场景里这个"连接"通常是：
 *   - 数据库连接（HikariCP / Druid）
 *   - HTTP 客户端连接（OkHttp / Apache HttpClient 的连接池）
 *   - Redis 连接
 *   - 甚至是我们自己写的线程池 + 有界队列
 *
 * 两种典型成因，本场景都能复现：
 *   ① 池太小 / 单次占用太久（慢查询）→ 正常排队，调大池或加超时
 *   ② **借了不还**（异常路径没释放）→ 池越来越小，最终完全耗尽
 */
@RestController
@RequestMapping("/connpool")
public class ConnPoolController {

    private static final Logger log = LoggerFactory.getLogger(ConnPoolController.class);

    /**
     * 模拟一个连接池。
     *
     * 用 Semaphore 表示"可用的连接数"：acquire = 借出，release = 归还。
     * 真实连接池（Hikari 等）内部也是这个结构，只是多了健康检查、保活、
     * 连接有效性验证这些。
     */
    static final class FakePool {
        final Semaphore permits;
        final int size;
        final AtomicInteger borrowed = new AtomicInteger();
        final AtomicInteger returned = new AtomicInteger();
        final AtomicInteger leaked = new AtomicInteger();
        final AtomicInteger timeouts = new AtomicInteger();

        FakePool(int size) {
            this.size = size;
            this.permits = new Semaphore(size, true);   // fair=true，模拟真实池的排队行为
        }

        int available() { return permits.availablePermits(); }
        int inUse() { return size - leaked.get() - permits.availablePermits(); }
    }

    private static volatile FakePool pool = new FakePool(5);

    /**
     * 初始化连接池。改 size 可以对比"池太小"和"池够大"的区别。
     */
    @GetMapping("/init")
    public Map<String, Object> init(@RequestParam(defaultValue = "5") int size) {
        pool = new FakePool(Math.max(1, Math.min(size, 200)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("outcome", "连接池已重建");
        r.put("poolSize", pool.size);
        r.put("hint", "接下来用 /connpool/burst 模拟并发请求抢连接");
        return r;
    }

    /**
     * 模拟 N 个并发请求抢连接池。
     *
     * 为什么做成"一个请求内部发 N 个并发"，而不是让你手动开 N 个标签页：
     * 手动开的并发数不可控、时序也难复现。这样一次调用就是一个干净的实验。
     *
     * @param concurrency      并发请求数
     * @param queryMs          每个请求占用连接多久（模拟慢查询）
     * @param acquireTimeoutMs 等连接的超时时间（真实项目里通常 1~3 秒）
     * @param leak             前 N 个请求"借了不还"（模拟异常路径没释放连接）
     */
    @GetMapping("/burst")
    public Map<String, Object> burst(
            @RequestParam(defaultValue = "20") int concurrency,
            @RequestParam(defaultValue = "3000") long queryMs,
            @RequestParam(defaultValue = "1000") long acquireTimeoutMs,
            @RequestParam(defaultValue = "0") int leak) {

        concurrency = Math.max(1, Math.min(concurrency, 200));
        leak = Math.max(0, Math.min(leak, concurrency));

        FakePool p = pool;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger timeout = new AtomicInteger();
        AtomicInteger leakedNow = new AtomicInteger();
        List<Long> waits = new ArrayList<>();

        long t0 = System.currentTimeMillis();
        CountDownLatch done = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            final boolean leakThis = i < leak;
            Thread t = new Thread(() -> {
                try {
                    long w0 = System.currentTimeMillis();
                    // ★ 关键：**带超时**地等连接。真实项目里没设超时的等待，
                    //   会变成"线程一直挂着"，那才是最糟的形态。
                    boolean got = p.permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
                    long waited = System.currentTimeMillis() - w0;
                    synchronized (waits) { waits.add(waited); }

                    if (!got) {
                        timeout.incrementAndGet();
                        p.timeouts.incrementAndGet();
                        return;
                    }
                    p.borrowed.incrementAndGet();
                    try {
                        Thread.sleep(queryMs);          // 模拟慢查询占住连接
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ok.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (leakThis) {
                        // 模拟"异常路径忘了归还" —— 连接就这么漏了
                        leakedNow.incrementAndGet();
                        p.leaked.incrementAndGet();
                    } else {
                        p.returned.incrementAndGet();
                        p.permits.release();
                    }
                    done.countDown();
                }
            }, "lab-conn-" + i);
            t.setDaemon(true);
            t.start();
        }

        try {
            done.await(queryMs + acquireTimeoutMs + 5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long wall = System.currentTimeMillis() - t0;

        long maxWait = waits.stream().mapToLong(Long::longValue).max().orElse(0);
        double avgWait = waits.stream().mapToLong(Long::longValue).average().orElse(0);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("poolSize", p.size);
        r.put("concurrency", concurrency);
        r.put("queryMs", queryMs);
        r.put("acquireTimeoutMs", acquireTimeoutMs);
        r.put("succeeded", ok.get());
        r.put("timedOut", timeout.get());
        r.put("leakedThisRound", leakedNow.get());
        r.put("maxWaitMs", maxWait);
        r.put("avgWaitMs", Math.round(avgWait));
        r.put("wallTimeMs", wall);
        r.put("poolAvailableAfter", p.available());
        r.put("poolLeakedTotal", p.leaked.get());

        // ── 自动诊断：把数字翻译成结论 ──
        List<String> notes = new ArrayList<>();
        if (timeout.get() > 0 && leakedNow.get() == 0) {
            notes.add("有 " + timeout.get() + " 个请求等不到连接。池只有 " + p.size
                    + " 个，而并发是 " + concurrency + "，每个占用 " + queryMs
                    + "ms —— 理论上最多只能同时服务 " + p.size + " 个。");
            notes.add("注意 CPU 是**闲的** —— 瓶颈在等待，不在计算。"
                    + "这类问题在监控上表现为「CPU 低 + 超时多」，很容易被误判成网络问题。");
            notes.add("三个方向：① 池调大 ② 把单次占用时间降下来（慢查询优化）"
                    + " ③ 缩短 acquireTimeout 让它快速失败，别把上游线程也拖住");
        }
        if (leakedNow.get() > 0) {
            notes.add("⚠ 这一轮有 " + leakedNow.get() + " 个连接**借了没还**。"
                    + "池的可用数已经降到 " + p.available() + "。");
            notes.add("连接泄漏比池太小危险得多：它是**单调恶化**的 —— "
                    + "每来一轮就少几个，最终池会变成 0，所有请求永久超时，重启才能恢复。");
            notes.add("典型成因：拿到连接后抛异常，而 release 写在 try 块里而不是 finally 里；"
                    + "或者用了事务但没提交/回滚。");
        }
        if (notes.isEmpty()) {
            notes.add("没有超时也没有泄漏 —— 池容量够用。"
                    + "试试把 concurrency 调大，或者把 poolSize 调小，制造出耗尽。");
        }
        r.put("diagnosis", notes);

        log.info("连接池演练：池 {} / 并发 {} / 成功 {} / 超时 {} / 泄漏 {}",
                p.size, concurrency, ok.get(), timeout.get(), leakedNow.get());
        return r;
    }

    /** 看池的当前状态。泄漏之后多刷几次，能看到可用连接数一路下降。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        FakePool p = pool;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("poolSize", p.size);
        r.put("available", p.available());
        r.put("inUse", p.inUse());
        r.put("leakedTotal", p.leaked.get());
        r.put("borrowedTotal", p.borrowed.get());
        r.put("returnedTotal", p.returned.get());
        r.put("timeoutsTotal", p.timeouts.get());
        r.put("note", "borrowed - returned = 没还回来的连接数。"
                + "正常情况下应该等于 0；泄漏时它会一直涨。");
        return r;
    }

    /** 重建池（相当于重启应用）。泄漏的连接没法"找回来"，只能整个换掉。 */
    @GetMapping("/reset")
    public Map<String, Object> reset() {
        int lost = pool.leaked.get();
        pool = new FakePool(pool.size);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("outcome", "连接池已重建");
        r.put("discardedLeakedConnections", lost);
        r.put("note", "真实环境里泄漏的连接只能靠重启释放 —— "
                + "这正是连接泄漏最讨厌的地方：它不会自己好");
        return r;
    }

    /** 给 /reset 用。返回被丢弃的泄漏连接数，让调用方能如实报出来。 */
    public static int resetPool() {
        int lost = pool.leaked.get();
        pool = new FakePool(pool.size);
        return lost;
    }
}
