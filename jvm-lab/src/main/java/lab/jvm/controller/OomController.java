package lab.jvm.controller;

import lab.jvm.support.ClassFactory;
import lab.jvm.support.LeakRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存类 OOM 演练。
 *
 * 共同套路：分配 → 挂到 LeakRegistry 让对象不可回收 → 触发 OOM。
 * 每个接口都会捕获 OutOfMemoryError 并把 message 原样返回，同时在日志里打出完整堆栈 ——
 * 这样你既能拿到"是哪一种 OOM"，也能在日志里看到真实崩溃现场。
 *
 * 注意：生产代码里绝不能像这里一样吞掉 OOM，这里只是为了教学方便。
 */
@RestController
@RequestMapping("/oom")
public class OomController {

    private static final Logger log = LoggerFactory.getLogger(OomController.class);

    /**
     * 最经典的一种：堆里塞满不可回收对象。
     * 期望：java.lang.OutOfMemoryError: Java heap space
     *
     * 默认参数在 -Xmx256m 下必定打爆：8MB × 64 = 512MB。
     * 想先观察"缓慢增长"再打爆，把 count 调小、多次调用即可。
     */
    @GetMapping("/heap")
    public Map<String, Object> heap(@RequestParam(defaultValue = "8") int mb,
                                    @RequestParam(defaultValue = "64") int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        int allocated = 0;
        try {
            for (int i = 0; i < count; i++) {
                byte[] block = new byte[mb * 1024 * 1024];
                block[0] = (byte) i;                       // 触碰首字节，确保真正提交物理页
                block[block.length - 1] = (byte) i;        // 触碰尾字节，避免"只申请不落页"的假象
                LeakRegistry.holdBytes(block);
                allocated++;
            }
            result.put("outcome", "no OOM");
            result.put("allocatedMb", (long) allocated * mb);
        } catch (OutOfMemoryError e) {
            log.error("触发 OOM（heap）：已分配 {} 次 × {}MB", allocated, mb, e);
            result.put("outcome", "OutOfMemoryError");
            result.put("errorType", "Java heap space");
            result.put("message", String.valueOf(e.getMessage()));
            result.put("allocatedMb", (long) allocated * mb);
        }
        result.put("bucketCount", LeakRegistry.byteBucketSize());
        result.put("hint", "观察完请调 POST /reset");
        return result;
    }

    /**
     * Metaspace 打爆。
     * 期望：java.lang.OutOfMemoryError: Metaspace
     *
     * 关键点：每个 Class 都是新的，且被 LeakRegistry 强引用，
     * 于是类无法卸载、承载它的 ClassLoader 也无法回收，元空间只增不减。
     *
     * 需要配 -XX:MaxMetaspaceSize=64m 才能快速复现；不设上限则受本机内存约束。
     */
    @GetMapping("/metaspace")
    public Map<String, Object> metaspace(@RequestParam(defaultValue = "20000") int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        int generated = 0;
        try {
            for (int i = 0; i < count; i++) {
                Class<?> clazz = ClassFactory.generate(LeakRegistry.nextClassSeq());
                LeakRegistry.holdClass(clazz);
                generated++;
            }
            result.put("outcome", "no OOM");
        } catch (OutOfMemoryError e) {
            log.error("触发 OOM（metaspace）：已生成 {} 个类", generated, e);
            result.put("outcome", "OutOfMemoryError");
            result.put("errorType", "Metaspace");
            result.put("message", String.valueOf(e.getMessage()));
        }
        result.put("generatedClasses", generated);
        result.put("bucketCount", LeakRegistry.classBucketSize());
        result.put("hint", "Metaspace 用量看 /status 的 nonHeapUsed，或 jstat -gcutil <pid> 的 M 列");
        return result;
    }

    /**
     * 堆外直接内存打爆。
     * 期望：java.lang.OutOfMemoryError: Direct buffer memory
     *
     * 坑点：allocateDirect 拿到的内存不在堆里，-Xmx 管不到它，
     * 只受 -XX:MaxDirectMemorySize 约束（不设时默认等于 -Xmx）。
     * 而且释放依赖 Cleaner —— 只要你还持有 ByteBuffer 引用，这块内存就还不了。
     */
    @GetMapping("/direct")
    public Map<String, Object> direct(@RequestParam(defaultValue = "16") int mb,
                                      @RequestParam(defaultValue = "64") int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        int allocated = 0;
        try {
            for (int i = 0; i < count; i++) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(mb * 1024 * 1024);
                buffer.put(0, (byte) 1);                   // 真正触碰，触发向 OS 申请
                LeakRegistry.holdDirect(buffer);
                allocated++;
            }
            result.put("outcome", "no OOM");
        } catch (OutOfMemoryError e) {
            log.error("触发 OOM（direct）：已分配 {} 次 × {}MB", allocated, mb, e);
            result.put("outcome", "OutOfMemoryError");
            result.put("errorType", "Direct buffer memory");
            result.put("message", String.valueOf(e.getMessage()));
        }
        result.put("allocatedMb", (long) allocated * mb);
        result.put("bucketCount", LeakRegistry.directBucketSize());
        result.put("hint", "直接内存用量看 /status 的 directBufferUsed");
        return result;
    }

    /**
     * 撑爆线程数。
     * 期望：java.lang.OutOfMemoryError: unable to create new native thread
     *
     * 注意这是"内存"报错但不是堆的问题：每个线程都要一份栈（-Xss，默认 1MB），
     * 真正的瓶颈通常是 ulimit -u（进程/线程数上限）或容器 pids_limit。
     *
     * 默认上限 300，避免在共享机器上失控；要复现需要自己把 count 调大，
     * 或把容器的 pids_limit / 宿主的 ulimit 调小。
     */
    @GetMapping("/threads")
    public Map<String, Object> threads(@RequestParam(defaultValue = "300") int count,
                                       @RequestParam(defaultValue = "300000") long holdMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        int created = 0;
        try {
            for (int i = 0; i < count; i++) {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(holdMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "lab-held-thread-" + i);
                t.setDaemon(true);                          // 守护线程，不阻塞 JVM 退出
                t.start();
                LeakRegistry.holdThread(t);
                created++;
            }
            result.put("outcome", "no OOM");
        } catch (OutOfMemoryError e) {
            log.error("触发 OOM（threads）：已创建 {} 个线程", created, e);
            result.put("outcome", "OutOfMemoryError");
            result.put("errorType", "unable to create new native thread");
            result.put("message", String.valueOf(e.getMessage()));
        }
        result.put("createdThreads", created);
        result.put("bucketCount", LeakRegistry.threadBucketSize());
        result.put("hint", "观察完请调 POST /reset（会打断这些线程）");
        return result;
    }

    /** 填充存活集用的块大小。128KB 是刻意选的，见 gcOverhead 的注释。 */
    private static final int LIVE_BLOCK_BYTES = 128 * 1024;

    /**
     * GC 开销超限。
     * 期望：java.lang.OutOfMemoryError: GC overhead limit exceeded
     *
     * ⚠️ 这个现象**只在使用 Serial / Parallel / CMS 时才会出现**。
     *    G1 不实现这个检查 —— 它会一直做 Full GC 空转下去，不 fail-fast。
     *    实测（256MB 堆、85% 存活）：G1 做了 198 次 Full GC，每次只回收 1MB，
     *    却始终不抛错，只是把接口拖到几百秒不返回。
     *    想复现这个错误，必须先切换 GC：
     *        ./lab.sh restart serial    （最经典，最容易复现）
     *        ./lab.sh restart parallel
     *
     * 原理：JVM 发现「GC 占了 98% 以上时间，却只回收了不到 2% 的堆」时抛这个错 ——
     * 潜台词是：再跑下去也只是白白烧 CPU，不如早点死。
     *
     * 要满足这个条件，存活集必须大到让每次 GC 都「捞不到货」。
     * 如果存活集只占堆的一半，Young GC 一次能回收十几 %，条件就不成立。
     *
     * 这里踩过两个坑，都很典型：
     *
     * 坑一：判断「填满了没有」不能读 Runtime 的 used —— 那个数字里混着尚未回收的垃圾，
     *      会让填充循环提前退出。必须按「已持有的字节数」推进。
     *
     * 坑二：填充块不能用 1MB。G1 的 region 大小是 堆/2048（下限 1MB），
     *      256MB 堆 → region = 1MB；而「大于等于 region 一半」的对象属于
     *      **humongous 对象**，会直接跳过 Young 区、在老年代申请**连续**的 region。
     *      结果就是：明明堆里还有 130MB 空闲，却因为找不到连续空间而在 120MB 处就 OOM。
     *      所以块大小必须明显小于 region 的一半 —— 这里用 128KB。
     *
     * @param livePercent  目标存活集占堆的比例。太大会在「填充阶段」就报 Java heap space
     * @param churnMillis  填满后持续制造垃圾的时长
     */
    @GetMapping("/gc-overhead")
    public Map<String, Object> gcOverhead(@RequestParam(defaultValue = "85") int livePercent,
                                          @RequestParam(defaultValue = "30000") long churnMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        long maxHeap = Runtime.getRuntime().maxMemory();
        long targetLiveBytes = maxHeap * livePercent / 100;

        long heldBytes = 0;
        int heldBlocks = 0;
        try {
            // 第一步：占住绝大部分堆（按已持有字节数推进，不读 Runtime.used）
            while (heldBytes < targetLiveBytes) {
                byte[] block = new byte[LIVE_BLOCK_BYTES];
                block[0] = 1;
                LeakRegistry.holdBytes(block);
                heldBytes += LIVE_BLOCK_BYTES;
                heldBlocks++;
            }
            result.put("liveMb", heldBytes >> 20);
            result.put("liveBlocks", heldBlocks);
            result.put("livePercentActual",
                    Math.round(heldBytes * 10000.0 / maxHeap) / 100.0);

            // 第二步：疯狂制造短命对象，一个都不留
            //
            // 内层批次刻意做小（256 个）：GC 空转时单次分配就可能触发一次 Full GC，
            // 批次太大会让「检查 deadline」这件事本身被拖到几十秒之后 ——
            // 接口迟迟不返回，看起来像卡死，而不是像 OOM。
            long deadline = System.currentTimeMillis() + churnMillis;
            long churnedBytes = 0;
            while (System.currentTimeMillis() < deadline) {
                for (int i = 0; i < 256; i++) {
                    byte[] trash = new byte[4096];
                    trash[0] = 1;                      // 防止被优化掉
                    churnedBytes += trash.length;
                }
            }
            result.put("outcome", "no OOM（GC 撑住了，把 livePercent 调大 3~5 再试）");
            result.put("churnedMb", churnedBytes >> 20);
        } catch (OutOfMemoryError e) {
            log.error("触发 OOM（gc-overhead）：存活 {}MB / 堆上限 {}MB",
                    heldBytes >> 20, maxHeap >> 20, e);
            result.put("outcome", "OutOfMemoryError");
            result.put("errorType", String.valueOf(e.getMessage()));
            result.put("liveMb", heldBytes >> 20);
            result.put("liveBlocks", heldBlocks);
            if ("Java heap space".equals(e.getMessage())) {
                result.put("note", "报的是 Java heap space，说明在「填满」阶段就炸了"
                        + " —— 把 livePercent 调小 3~5 再试");
            }
        }
        result.put("heapMaxMb", maxHeap >> 20);
        result.put("hint", "看 GC 日志：GC 频率暴涨但每次回收量极低；"
                + "也可用 ./lab.sh restart serial 在 Serial GC 下对比同一场景");
        return result;
    }
}
