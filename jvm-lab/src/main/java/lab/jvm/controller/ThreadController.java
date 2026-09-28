package lab.jvm.controller;

import lab.jvm.support.LeakRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线程 / 栈 / CPU / 锁 类演练。
 *
 * 这一组练的不是"内存不够"，而是"怎么用工具定位"：
 *   StackOverflowError → 看堆栈
 *   死锁               → jstack 自动检测
 *   CPU 飙高           → top -H 拿线程号，再 jstack 里按 nid 找
 */
@RestController
public class ThreadController {

    private static final Logger log = LoggerFactory.getLogger(ThreadController.class);

    /** 死锁用的一对锁，必须是所有请求共享的同一对对象 */
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();

    /** 当前递归深度，仅用于把"栈有多深才炸"这个数字报出来 */
    private volatile int depth = 0;

    /**
     * 栈溢出。
     * 期望：java.lang.StackOverflowError
     *
     * 栈深度由 -Xss 决定（默认 1MB ≈ 一万多层）。
     * 把 -Xss 调到 256k 再跑一次，对比 depthReached 的变化，就直观了。
     */
    @GetMapping("/stack/overflow")
    public Map<String, Object> stackOverflow() {
        Map<String, Object> result = new LinkedHashMap<>();
        depth = 0;
        try {
            recurse(1);
            result.put("outcome", "no overflow（不太可能）");
        } catch (StackOverflowError e) {
            // 在最外层捕获是安全的：栈此时已经展开回浅层
            result.put("outcome", "StackOverflowError");
            result.put("depthReached", depth);
            result.put("hint", "改 -Xss 后对比 depthReached；再看 jstack 里这个线程的栈");
            log.info("栈溢出：深度 {}（-Xss 越大这个数越大）", depth);
        }
        return result;
    }

    private void recurse(int n) {
        // 用几个局部 long 撑住栈帧，避免被 JIT 优化成尾递归
        long a = n;
        long b = n + 1L;
        long c = n + 2L;
        depth = n;
        if (a + b + c == Long.MIN_VALUE) {
            return;                        // 永远不成立，只为让上面的变量"被使用"
        }
        recurse(n + 1);
    }

    /**
     * 死锁。
     * 期望：jstack 输出末尾出现 "Found one Java-level deadlock"
     *
     * 两个线程以相反顺序拿同一对锁。注意 sleep 不是必须的，但加上能稳定复现 ——
     * 没有它，两个线程可能恰好一个跑完再另一个开始，就锁不上了。
     */
    @GetMapping("/lock/deadlock")
    public Map<String, Object> deadlock() {
        Thread t1 = new Thread(() -> {
            synchronized (LOCK_A) {
                sleepQuietly(200);
                synchronized (LOCK_B) {
                    log.info("不可能到达这里");
                }
            }
        }, "lab-deadlock-A");

        Thread t2 = new Thread(() -> {
            synchronized (LOCK_B) {
                sleepQuietly(200);
                synchronized (LOCK_A) {
                    log.info("不可能到达这里");
                }
            }
        }, "lab-deadlock-B");

        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        LeakRegistry.holdDeadlockThread(t1);
        LeakRegistry.holdDeadlockThread(t2);

        sleepQuietly(400);                 // 等一下，让死锁真正形成

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "两个线程已进入死锁");
        result.put("threads", "lab-deadlock-A / lab-deadlock-B");
        result.put("howToObserve", "jstack <pid> | grep -A 30 'Found one Java-level deadlock'");
        result.put("note", "死锁线程无法通过 /reset 解除，重启进程即可（守护线程不会阻塞退出）");
        return result;
    }

    /**
     * 把 CPU 打满，用来练"CPU 100% 怎么定位"。
     *
     * 定位链路：
     *   top -H -p <pid>            找到最耗 CPU 的线程号（十进制）
     *   printf '%x\n' <线程号>      转成十六进制，就是 jstack 里的 nid
     *   jstack <pid> | grep -A 20 nid=0x<十六进制>
     *   或用 Arthas：thread -n 3
     */
    @GetMapping("/cpu/spin")
    public Map<String, Object> cpuSpin(@RequestParam(defaultValue = "2") int threads) {
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                double x = 1.0;
                while (!Thread.currentThread().isInterrupted()) {
                    x = Math.sqrt(x + 1.0);    // 纯计算，不阻塞，稳定吃满一个核
                }
            }, "lab-cpu-spin-" + i);
            t.setDaemon(true);
            t.start();
            LeakRegistry.holdSpinThread(t);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "已启动空转线程");
        result.put("spinThreads", threads);
        result.put("totalSpinThreads", LeakRegistry.spinBucketSize());
        result.put("howToObserve", "top -H -p <pid> → printf '%x\\n' <tid> → jstack <pid> | grep nid=0x<hex>");
        result.put("howToStop", "POST /cpu/stop 或 POST /reset");
        return result;
    }

    /** 停掉所有空转线程。这是必须提供的 —— 否则 CPU 会一直跑满。 */
    @GetMapping("/cpu/stop")
    public Map<String, Object> cpuStop() {
        int stopped = LeakRegistry.stopSpinThreads();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "已打断空转线程");
        result.put("stopped", stopped);
        return result;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
