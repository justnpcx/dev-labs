package lab.jvm.controller;

import lab.jvm.support.JvmStats;
import lab.jvm.support.LeakEntry;
import lab.jvm.support.LeakRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渐进式泄漏演练。
 *
 * 和 /oom/heap 的区别很重要：
 *   /oom/heap     —— 一次性打爆，目的是"看到 OOM 长什么样"
 *   /leak/static  —— 每次只加一点点，目的是"学会用工具观察增长趋势"
 *
 * 真实生产事故几乎都是后者：没人会一次分配 512MB，都是一天涨一点，
 * 直到某个凌晨突然 OOM。所以这个接口要配合下面的流程用：
 *
 *   1. 反复调用 /leak/static?mb=2&count=5，每次看 /status 的 heap.usedMb
 *   2. jmap -histo <pid> | head -20            记下 LeakEntry 的实例数
 *   3. 再调几次，重新 jmap -histo | head -20    对比实例数是不是在涨
 *   4. jmap -dump:live,format=b,file=a.hprof <pid>，再触发一轮，dump 第二次
 *   5. 用 MAT 对比两份 dump，看 "Leak Suspects" 指向谁
 */
@RestController
@RequestMapping("/leak")
public class LeakController {

    private static final Logger log = LoggerFactory.getLogger(LeakController.class);

    private static final AtomicLong KEY_SEQ = new AtomicLong();

    /**
     * 往静态缓存里塞对象，永不淘汰。
     *
     * @param mb    每个条目占多少 MB
     * @param count 这次塞几个
     */
    @GetMapping("/static")
    public Map<String, Object> staticLeak(@RequestParam(defaultValue = "2") int mb,
                                          @RequestParam(defaultValue = "5") int count) {
        for (int i = 0; i < count; i++) {
            byte[] payload = new byte[mb * 1024 * 1024];
            payload[0] = (byte) i;
            payload[payload.length - 1] = (byte) i;
            String key = "entry-" + KEY_SEQ.incrementAndGet();
            LeakRegistry.cachePut(key, new LeakEntry(key, payload));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "已写入静态缓存（无淘汰策略）");
        result.put("addedEntries", count);
        result.put("addedMb", (long) mb * count);
        result.put("staticCacheEntries", LeakRegistry.cacheSize());
        result.put("heap", JvmStats.heap());
        result.put("howToObserve", "连续调用本接口，观察 heap.usedMb 单调上涨且不回落");
        result.put("howToAnalyze", "jmap -histo <pid> | head -20 看 lab.jvm.support.LeakEntry 实例数");
        log.info("静态缓存泄漏：+{} 条，当前共 {} 条", count, LeakRegistry.cacheSize());
        return result;
    }
}
