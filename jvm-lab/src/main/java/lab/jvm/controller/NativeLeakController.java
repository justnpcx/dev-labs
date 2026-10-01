package lab.jvm.controller;

import lab.jvm.support.ContainerLimits;
import lab.jvm.support.JvmStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本地内存（native memory）泄漏。
 *
 * <h2>这是演练场里唯一一个「Java 堆完全正常，但进程被内核杀掉」的场景</h2>
 *
 * 前面那些 OOM 都是 JVM 自己抛的（{@code OutOfMemoryError}），JVM 还活着，
 * 你还能拿到响应。这个不一样：泄漏的是 JVM 堆**之外**的内存，
 * JVM 自己不知道，也不会抛异常 —— 直到 cgroup 先到上限，
 * **内核直接把进程 SIGKILL 掉**。日志里没有堆栈，只有一条 OOM kill 记录。
 *
 * <h2>为什么用 Unsafe.allocateMemory</h2>
 *
 * 真实世界里本地内存泄漏几乎都来自 JNI 库（Netty 的池化分配器、压缩库、
 * JDBC 驱动）在 native 代码里 malloc 了却忘了 free。纯 Java 环境没法写 JNI，
 * 所以用 {@code Unsafe.allocateMemory} 模拟 —— 它就是**裸的 malloc**，
 * 语义完全一致：拿了就还不了，JVM 也不记账。
 *
 * <h2>★ 核心教学点：三道「内存上限」一个都管不住它</h2>
 *
 * <pre>
 *   -Xmx256m                   只管 Java 堆
 *   -XX:MaxDirectMemorySize    只管 ByteBuffer.allocateDirect（JVM 自己记账的）
 *   Unsafe.allocateMemory      ← 谁都不管，裸 malloc
 * </pre>
 *
 * 唯一能挡住它的是**容器自己的 mem_limit**。所以现象是
 * 「容器被 OOM kill」而不是「Java heap space」—— 这个区别决定了你该去看哪里。
 *
 * <h2>怎么定位</h2>
 *
 * <ol>
 *   <li>{@code /native/stats} 看 RSS / cgroup 用量：堆不动、直接内存不动、RSS 一直涨</li>
 *   <li>{@code jcmd &lt;pid&gt; VM.native_memory summary} 看哪个类别在涨</li>
 *   <li>但 summary 级别只能告诉你类别（多半是 "Other"），**看不到调用栈**。
 *       要看到"是谁分配的"，必须在**启动时**就加 {@code -XX:NativeMemoryTracking=detail}
 *       —— 事后改不了。用 {@code ./lab.sh restart nmt-detail} 换过去。</li>
 * </ol>
 */
@RestController
@RequestMapping("/native")
public class NativeLeakController {

    private static final Logger log = LoggerFactory.getLogger(NativeLeakController.class);

    /**
     * 拿 Unsafe 的标准姿势：{@code Unsafe.getUnsafe()} 会检查调用方的类加载器，
     * 只有 bootstrap 加载的类才放行，应用代码调用会直接抛 SecurityException。
     * 所以得反射拿那个私有的 {@code theUnsafe} 字段。
     *
     * JDK 17 里 sun.misc.Unsafe 还在（在 jdk.unsupported 模块里），实测无需 --add-opens。
     * 但它是「internal proprietary API」，高版本 JDK 会逐步移除 —— 编译期会有警告，
     * 生产代码别这么写。
     */
    private static final Object UNSAFE;
    private static final java.lang.reflect.Method ALLOCATE_MEMORY;
    private static final java.lang.reflect.Method FREE_MEMORY;
    private static final java.lang.reflect.Method SET_MEMORY;

    static {
        Object u = null;
        java.lang.reflect.Method alloc = null, free = null, set = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = f.get(null);
            alloc = unsafeClass.getMethod("allocateMemory", long.class);
            free = unsafeClass.getMethod("freeMemory", long.class);
            set = unsafeClass.getMethod("setMemory", long.class, long.class, byte.class);
        } catch (ReflectiveOperationException e) {
            LoggerFactory.getLogger(NativeLeakController.class)
                    .warn("拿不到 Unsafe，本地内存泄漏场景不可用", e);
        }
        UNSAFE = u;
        ALLOCATE_MEMORY = alloc;
        FREE_MEMORY = free;
        SET_MEMORY = set;
    }

    /** 已分配的 native 块地址。**故意持有引用** —— 这就是"忘了 free"的那部分。 */
    private static final List<Long> LEAKED = new ArrayList<>();
    private static long allocatedBytes = 0;

    /**
     * 泄漏 N MB 的本地内存。
     *
     * <p>分块分配是为了模拟"一次泄漏一点、慢慢涨"的真实形态 ——
     * 真实项目里没人会一次 malloc 1GB，都是每个请求漏几 KB，几天后出事。
     *
     * @param mb      总共泄漏多少 MB
     * @param chunkMb 每块多大（越小越像真实泄漏，但块数多了地址开销也大）
     */
    @GetMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "200") int mb,
                                    @RequestParam(defaultValue = "32") int chunkMb) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (UNSAFE == null) {
            result.put("outcome", "Unsafe 不可用，这个场景跑不了");
            return result;
        }
        if (mb <= 0 || mb > 4096) {
            result.put("outcome", "mb 要在 1~4096 之间");
            return result;
        }

        long before = rssKb();
        long chunkBytes = Math.max(1, chunkMb) * 1024L * 1024L;
        int chunks = (int) Math.ceil(mb * 1024.0 * 1024.0 / chunkBytes);

        int ok = 0;
        try {
            for (int i = 0; i < chunks; i++) {
                long p = (Long) ALLOCATE_MEMORY.invoke(UNSAFE, chunkBytes);
                // ★ 必须**真的写一遍**。malloc 拿到的只是虚拟地址空间，
                //   不碰它就不会有物理页，RSS 一点不涨 —— 那样演示就失败了。
                //   这也是排查时容易误判的点：NMT 说分配了，RSS 却没动。
                SET_MEMORY.invoke(UNSAFE, p, chunkBytes, (byte) 0);
                synchronized (LEAKED) {
                    LEAKED.add(p);
                    allocatedBytes += chunkBytes;
                }
                ok++;
            }
            result.put("outcome", "已泄漏 " + mb + " MB 本地内存");
        } catch (Throwable t) {
            // 分配失败通常是宿主/容器的地址空间或内存真的不够了。
            // 注意：这里 catch 的是 Throwable —— 因为它可能是 OutOfMemoryError。
            log.error("本地内存分配失败，已分配 {} 块", ok, t);
            result.put("outcome", "分配过程中失败（已泄漏 " + (ok * chunkMb) + " MB）");
            result.put("error", String.valueOf(t.getMessage()));
        }

        result.put("chunks", ok);
        result.put("chunkMb", chunkMb);
        result.put("leakedTotalMb", allocatedBytes / 1024 / 1024);
        result.put("rssDeltaMb", (rssKb() - before) / 1024);
        result.putAll(snapshot());
        result.put("note", "这批内存**没有任何办法自动回收** —— 没有引用计数、没有 Cleaner、"
                + "GC 也看不到它。真实环境里唯一的解法是重启进程。"
                + "演练场里可以点 /native/free 手动还回去，那是在模拟「修好了代码之后重启」。");
        result.put("watch", "看 heap / directBuffer 两个数字：它们**一点都不涨**。"
                + "涨的只有 rss 和 cgroup 用量");
        return result;
    }

    /** 当前快照：堆 / 直接内存 / 我们泄漏的 / 进程 RSS / 容器 cgroup。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(snapshot());
        result.put("howToRead", "heap 和 directBuffer 是 JVM 自己记账的（前者 -Xmx 管，"
                + "后者 MaxDirectMemorySize 管）；rss 是整个进程实际占的物理内存，"
                + "**包含 JVM 不记账的那部分**。三者对不上，差的就是本地内存");
        return result;
    }

    /**
     * 把泄漏的本地内存还回去。
     *
     * <p>**真实环境的泄漏是还不了的** —— 你根本没有那些指针。这里能还，
     * 是因为演练场自己记着地址。所以这个接口的定位是"模拟修好代码后重启"，
     * 不是"有一个能救急的接口"。
     */
    @GetMapping("/free")
    public Map<String, Object> free() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (UNSAFE == null) {
            result.put("outcome", "Unsafe 不可用");
            return result;
        }
        long before = rssKb();
        int n;
        synchronized (LEAKED) {
            n = LEAKED.size();
            for (Long p : LEAKED) {
                try {
                    FREE_MEMORY.invoke(UNSAFE, p);
                } catch (Throwable t) {
                    log.warn("free 失败：{}", t.toString());
                }
            }
            LEAKED.clear();
            allocatedBytes = 0;
        }
        result.put("outcome", "已归还 " + n + " 块本地内存");
        result.put("rssDeltaMb", (rssKb() - before) / 1024);
        result.putAll(snapshot());
        result.put("note", "真实泄漏没有这个按钮 —— 指针早就丢了，"
                + "只能重启进程。所以本地内存泄漏的定位必须在**出事之前**就布好观测手段");
        return result;
    }

    /**
     * 直接跑 {@code jcmd VM.native_memory summary}，把原始输出返回。
     *
     * <p>为什么要在应用里跑 jcmd：NMT 的数据只在进程内部，没有 JMX 接口，
     * 唯一的口子就是这个命令行工具。容器里 PID 是 1。
     *
     * <p>注意 {@code summary} 和 {@code detail} 的差别 —— 这是本场景最实用的一点：
     * summary 只按类别汇总（多半只看到 "Other" 在涨），
     * detail 才会给出**调用栈**。而 detail 必须在启动时就开。
     */
    @GetMapping("/nmt")
    public Map<String, Object> nmt() {
        Map<String, Object> result = new LinkedHashMap<>();
        long pid = ProcessHandle.current().pid();
        String level = "summary";
        result.put("pid", pid);
        result.put("command", "jcmd " + pid + " VM.native_memory summary");

        try {
            Process p = new ProcessBuilder("jcmd", String.valueOf(pid), "VM.native_memory", "summary")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                out = sb.toString();
            }
            if (!done) {
                p.destroyForcibly();
                result.put("outcome", "jcmd 超时");
            } else {
                result.put("outcome", "ok");
            }
            result.put("output", out);

            // 级别从**启动参数**里读，而不是从 NMT 输出里抠。
            // NMT 的输出长这样：
            //     Native Memory Tracking:
            //     (Omitting categories weighting less than 1KB)
            //     Total: reserved=...
            // 中间那行是「省略了小类别」的提示，不是级别 —— 早先按输出解析会得到
            // "(Omitting" 这种垃圾值。启动参数才是权威来源。
            for (String arg : JvmStats.jvmArgs()) {
                if (arg.startsWith("-XX:NativeMemoryTracking=")) {
                    level = arg.substring("-XX:NativeMemoryTracking=".length());
                }
            }
            result.put("trackingLevel", level);
            if ("summary".equals(level)) {
                result.put("limitation", "当前是 **summary** 级别：只能看到类别（多半是 Other）在涨，"
                        + "**看不到调用栈**。要定位「是谁分配的」，必须在**启动时**加 "
                        + "-XX:NativeMemoryTracking=detail —— 事后改不了。"
                        + "用 ./lab.sh restart nmt-detail 换过去再复现一次");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            result.put("outcome", "跑 jcmd 失败");
            result.put("error", e.getMessage());
            result.put("hint", "容器里 PID 是 1；如果 attach 失败，看看 /tmp 是不是可写"
                    + "（HotSpot 的 attach socket 放在那）");
        }
        return result;
    }

    /** 给 /reset 用。返回归还的块数。 */
    public static synchronized int releaseAll() {
        if (UNSAFE == null) return 0;
        int n;
        synchronized (LEAKED) {
            n = LEAKED.size();
            for (Long p : LEAKED) {
                try {
                    FREE_MEMORY.invoke(UNSAFE, p);
                } catch (Throwable ignored) {
                    // 尽力而为
                }
            }
            LEAKED.clear();
            allocatedBytes = 0;
        }
        return n;
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> heap = JvmStats.heap();
        m.put("heap", heap);

        Map<String, Object> direct = JvmStats.directBuffer();
        m.put("directBuffer", direct);

        Map<String, Object> nativeMem = new LinkedHashMap<>();
        nativeMem.put("leakedMb", allocatedBytes / 1024 / 1024);
        nativeMem.put("blocks", LEAKED.size());
        nativeMem.put("note", "JVM 完全不知道这块内存 —— 它不在堆里，也不在直接内存里");
        m.put("leakedNative", nativeMem);

        long rssKb = rssKb();
        m.put("rssMb", rssKb < 0 ? null : rssKb / 1024);

        Map<String, Object> limits = ContainerLimits.snapshot();
        m.put("cgroup", limits);

        // 把「cgroup 用了多少 / 上限多少」直接算成百分比，一眼能看出离被杀还有多远
        Long usedMb = asLong(limits.get("memoryUsedMb"));
        Long limitMb = asLong(limits.get("memoryLimitMb"));
        if (usedMb != null && limitMb != null && limitMb > 0) {
            m.put("cgroupUsedPercent", Math.round(usedMb * 10000.0 / limitMb) / 100.0);
            m.put("headroomMb", limitMb - usedMb);
        }
        return m;
    }

    /** /proc/self/status 里的 VmRSS —— 进程实际占的物理内存。 */
    private static long rssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);   // 单位 kB
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // 读不到就返回 -1，调用方会把它当"未知"
        }
        return -1;
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }
}
