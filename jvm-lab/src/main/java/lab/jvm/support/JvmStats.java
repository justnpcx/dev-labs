package lab.jvm.support;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 JVM 的关键指标聚合成一个 Map，供 /status 展示。
 *
 * 刻意只用 java.lang.management（JMX）—— 不引入 Micrometer 之外的任何东西，
 * 这样你能看清"这些数字到底从哪来"，而不是被一层层封装盖住。
 */
public final class JvmStats {

    private static final long MB = 1024L * 1024L;

    private JvmStats() {
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("heap", heap());
        root.put("metaspace", metaspace());
        root.put("directBuffer", directBuffer());
        root.put("threads", threads());
        root.put("gc", gc());
        root.put("jvmArgs", jvmArgs());
        root.put("buckets", buckets());
        // 容器隔离边界 —— 界面上要能看到"限制到底生效了没有"
        root.put("container", ContainerLimits.snapshot());
        return root;
    }

    /** 堆用量。usedPercent 是判断"离 OOM 还有多远"最直接的指标。 */
    public static Map<String, Object> heap() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("usedMb", heap.getUsed() / MB);
        map.put("committedMb", heap.getCommitted() / MB);
        map.put("maxMb", heap.getMax() / MB);
        map.put("usedPercent", heap.getMax() > 0
                ? Math.round(heap.getUsed() * 10000.0 / heap.getMax()) / 100.0
                : -1);
        return map;
    }

    /** Metaspace 用量。只有设了 -XX:MaxMetaspaceSize，maxMb 才不是 -1。 */
    public static Map<String, Object> metaspace() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(pool.getName())) {
                MemoryUsage usage = pool.getUsage();
                map.put("usedMb", usage.getUsed() / MB);
                map.put("committedMb", usage.getCommitted() / MB);
                map.put("maxMb", usage.getMax() < 0 ? -1 : usage.getMax() / MB);
                return map;
            }
        }
        return map;
    }

    /**
     * 直接内存（堆外）。
     * 注意它不在堆里，-Xmx 管不到，只受 -XX:MaxDirectMemorySize 约束。
     */
    public static Map<String, Object> directBuffer() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                map.put("usedMb", pool.getMemoryUsed() / MB);
                map.put("capacityMb", pool.getTotalCapacity() / MB);
                map.put("bufferCount", pool.getCount());
                return map;
            }
        }
        return map;
    }

    public static Map<String, Object> threads() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", bean.getThreadCount());
        map.put("peakCount", bean.getPeakThreadCount());
        map.put("daemonCount", bean.getDaemonThreadCount());
        map.put("totalStarted", bean.getTotalStartedThreadCount());
        long[] deadlocked = bean.findDeadlockedThreads();
        map.put("deadlockedCount", deadlocked == null ? 0 : deadlocked.length);
        return map;
    }

    /** 各收集器的累计次数与耗时 —— 配合 GC 日志一起看，判断"GC 是不是在空转"。 */
    public static List<Map<String, Object>> gc() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", bean.getName());
            map.put("count", bean.getCollectionCount());
            map.put("timeMs", bean.getCollectionTime());
            list.add(map);
        }
        return list;
    }

    /**
     * 实际生效的 JVM 启动参数。
     * 这是最容易被忽略但最有用的一项：你以为加了 -Xmx，其实可能被环境变量或容器限制覆盖了。
     */
    public static List<String> jvmArgs() {
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        List<String> args = new ArrayList<>(bean.getInputArguments());
        return args;
    }

    /** 各「泄漏桶」当前持有量，用来确认演练到底生效了没有。 */
    public static Map<String, Object> buckets() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("heldByteArrays", LeakRegistry.byteBucketSize());
        map.put("heldDirectBuffers", LeakRegistry.directBucketSize());
        map.put("heldClasses", LeakRegistry.classBucketSize());
        map.put("classLoaderGenerations", ClassFactory.generationCount());
        map.put("heldThreads", LeakRegistry.threadBucketSize());
        map.put("spinThreads", LeakRegistry.spinBucketSize());
        map.put("deadlockThreads", LeakRegistry.deadlockBucketSize());
        map.put("staticCacheEntries", LeakRegistry.cacheSize());
        return map;
    }
}
