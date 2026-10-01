package lab.jvm.controller;

import lab.jvm.support.JvmStats;
import lab.jvm.support.LeakRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 状态查看与复位。
 *
 * /status 是演练场的仪表盘：每次触发一个场景前后都来看一眼，你才能把
 * "我做了什么" 和 "JVM 里发生了什么" 对应起来。
 *
 * /reset 是必备的安全阀：OOM 之后堆是满的，不清掉后续所有请求都会失败。
 */
@RestController
public class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    /** JVM 全景快照。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return JvmStats.snapshot();
    }

    /**
     * 复位：清空所有泄漏桶 + 丢弃动态类加载器 + 打断空转/休眠线程 + 触发一次 Full GC。
     *
     * 注意 System.gc() 默认只是"建议"，JVM 可以不理会（加了 -XX:+DisableExplicitGC 就完全不理会）。
     * 这里显式调用是为了教学演示 —— 生产代码里绝不该这么干，一次 Full GC 会让整个应用停顿。
     *
     * 复位后 Metaspace 的回落可能有一两秒延迟：类卸载发生在 Full GC 期间，
     * 而 Full GC 是并发的。多刷新几次 /status 就能看到 usedMb 掉下来。
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "true") boolean gc) {
        String cleared = LeakRegistry.clearBuckets();
        int stoppedThreads = LeakRegistry.stopHeldThreads();
        int stoppedSpins = LeakRegistry.stopSpinThreads();
        // 线程池和锁竞争线程不在 LeakRegistry 里（它们有自己的生命周期），
        // 但同样是"不清掉就会一直占资源"的东西，一起收掉。
        int droppedPoolTasks = PoolController.shutdownAll();
        int stoppedContention = LockController.stopContentionThreads();
        // 连接池也一样：泄漏的连接是"再也还不回来"的，只有重建池才能恢复。
        // 真实环境里这一步等于重启应用 —— 所以页面上要明确说清。
        int discardedConnections = ConnPoolController.resetPool();

        if (gc) {
            System.gc();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "已复位");
        result.put("cleared", cleared);
        result.put("stoppedHeldThreads", stoppedThreads);
        result.put("stoppedSpinThreads", stoppedSpins);
        result.put("droppedPoolTasks", droppedPoolTasks);
        result.put("stoppedContentionThreads", stoppedContention);
        result.put("discardedLeakedConnections", discardedConnections);
        result.put("explicitGc", gc);
        result.put("note", "死锁线程无法通过复位解除；Metaspace 的回落要等 Full GC 跑完，稍等再看 /status");
        result.put("heap", JvmStats.heap());
        result.put("metaspace", JvmStats.metaspace());
        log.info("复位完成：{}，打断线程 {}/{}，丢弃池任务 {}，锁竞争线程 {}",
                cleared, stoppedThreads, stoppedSpins, droppedPoolTasks, stoppedContention);
        return result;
    }
}
