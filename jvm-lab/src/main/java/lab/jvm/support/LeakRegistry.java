package lab.jvm.support;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局「泄漏桶」。
 *
 * 演练 OOM 的关键不是"怎么分配内存"，而是"怎么让对象不被回收"。
 * 所有场景都把对象强引用挂在这里，于是：
 *   1. 对象永远可达 —— 这正是真实泄漏的形态
 *   2. 可以一键清空复位，不用重启 JVM
 *
 * 注意这里是 synchronized 静态方法：刻意不追求并发性能，只保证桶本身不会因为
 * 并发写而丢数据（丢数据会让你误判"为什么没 OOM"）。
 */
public final class LeakRegistry {

    /** 大对象字节数组 —— 用于 Java heap space OOM 与渐进泄漏 */
    private static final List<byte[]> BYTE_BUCKETS = new ArrayList<>();

    /** 直接内存引用 —— 用于 Direct buffer memory OOM。
     *  必须持有 ByteBuffer 本身：DirectByteBuffer 的回收依赖 Cleaner，
     *  一旦强引用消失，GC 就会通过 Cleaner 把堆外内存还回去，OOM 就复现不出来。 */
    private static final List<ByteBuffer> DIRECT_BUCKETS = new ArrayList<>();

    /** 动态生成的 Class 引用 —— 用于 Metaspace OOM。
     *  持有 Class 就持有它所属的 ClassLoader，ClassLoader 活着，类就无法卸载。 */
    private static final List<Class<?>> CLASS_BUCKETS = new ArrayList<>();

    /** 停不下来的线程 —— 用于 unable to create new native thread */
    private static final List<Thread> THREAD_BUCKETS = new ArrayList<>();

    /** 空转线程 —— 用于 CPU 飙高 + top -H / jstack 定位 */
    private static final List<Thread> SPIN_BUCKETS = new ArrayList<>();

    /** 死锁线程 —— 只做记录，方便 /status 展示 */
    private static final List<Thread> DEADLOCK_BUCKETS = new ArrayList<>();

    /**
     * 静态「缓存」—— 模拟生产里最常见的泄漏：缓存加了却没有淘汰策略 / 没有 TTL。
     * 用 Map 而不是 List，是因为真实事故里长这样的基本都是缓存。
     */
    private static final Map<String, LeakEntry> STATIC_CACHE = new ConcurrentHashMap<>();

    private static final AtomicLong CLASS_SEQ = new AtomicLong();

    private LeakRegistry() {
    }

    public static synchronized void holdBytes(byte[] block) {
        BYTE_BUCKETS.add(block);
    }

    public static synchronized int byteBucketSize() {
        return BYTE_BUCKETS.size();
    }

    public static synchronized void holdDirect(ByteBuffer buffer) {
        DIRECT_BUCKETS.add(buffer);
    }

    public static synchronized int directBucketSize() {
        return DIRECT_BUCKETS.size();
    }

    public static synchronized void holdClass(Class<?> clazz) {
        CLASS_BUCKETS.add(clazz);
    }

    public static synchronized int classBucketSize() {
        return CLASS_BUCKETS.size();
    }

    public static synchronized void holdThread(Thread thread) {
        THREAD_BUCKETS.add(thread);
    }

    public static synchronized int threadBucketSize() {
        return THREAD_BUCKETS.size();
    }

    public static synchronized void holdSpinThread(Thread thread) {
        SPIN_BUCKETS.add(thread);
    }

    public static synchronized int spinBucketSize() {
        return SPIN_BUCKETS.size();
    }

    public static synchronized void holdDeadlockThread(Thread thread) {
        DEADLOCK_BUCKETS.add(thread);
    }

    public static synchronized int deadlockBucketSize() {
        return DEADLOCK_BUCKETS.size();
    }

    public static void cachePut(String key, LeakEntry entry) {
        STATIC_CACHE.put(key, entry);
    }

    public static int cacheSize() {
        return STATIC_CACHE.size();
    }

    public static long nextClassSeq() {
        return CLASS_SEQ.incrementAndGet();
    }

    /**
     * 清空所有「持有引用」的桶。
     * 调用后对象变为不可达，但真正的内存回收要等下一次 GC —— 所以配套调用 System.gc()。
     *
     * @return 各桶清空前的数量，便于确认复位确实发生了
     */
    public static synchronized String clearBuckets() {
        // 关键一步：丢掉所有代的 ClassLoader。
        // 只清 CLASS_BUCKETS 是不够的 —— 类还在 ClassLoader 里挂着，
        // 必须让 ClassLoader 本身不可达，Full GC 才会连类带元数据一起卸载。
        int droppedLoaders = ClassFactory.reset();

        String summary = "cleared bytes=%d direct=%d classes=%d threads=%d deadlock=%d cache=%d classLoaders=%d"
                .formatted(BYTE_BUCKETS.size(), DIRECT_BUCKETS.size(),
                        CLASS_BUCKETS.size(), THREAD_BUCKETS.size(),
                        DEADLOCK_BUCKETS.size(), STATIC_CACHE.size(), droppedLoaders);
        BYTE_BUCKETS.clear();
        DIRECT_BUCKETS.clear();
        CLASS_BUCKETS.clear();
        THREAD_BUCKETS.clear();
        DEADLOCK_BUCKETS.clear();
        STATIC_CACHE.clear();
        return summary;
    }

    /**
     * 打断所有被持有的休眠线程。
     * 这些线程在 sleep，interrupt 会让它们立刻从 sleep 抛出并结束。
     */
    public static synchronized int stopHeldThreads() {
        int n = 0;
        for (Thread t : THREAD_BUCKETS) {
            t.interrupt();
            n++;
        }
        THREAD_BUCKETS.clear();
        return n;
    }

    /**
     * 打断所有空转线程。空转线程不会自己退出，必须显式 interrupt，
     * 否则 CPU 会一直跑满（这是"演练场把宿主机搞挂"最常见的原因）。
     */
    public static synchronized int stopSpinThreads() {
        int n = 0;
        for (Thread t : SPIN_BUCKETS) {
            t.interrupt();
            n++;
        }
        SPIN_BUCKETS.clear();
        return n;
    }
}
