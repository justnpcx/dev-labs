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

/**
 * JIT 预热演练。
 *
 * 这个场景不制造故障，它解释的是**一个几乎所有人都会踩的认知陷阱**：
 * 「同一段代码，跑第一次和跑第一万次，速度可能差 100 倍。」
 *
 * 为什么值得单独练：
 *   你写了一个"优化"，跑一次测试发现快了 3 倍，于是上线 ——
 *   结果线上毫无变化，甚至更慢。原因往往是：
 *     - 你的"优化前"那段刚好还没预热（显得慢）
 *     - 你的"优化后"那段刚好已经在别处预热过（显得快）
 *   微基准测试的所有结论，不预热就都是噪声。
 *
 * JVM 里同一个方法的三个阶段：
 *   解释执行    C1 编译       C2 编译
 *   （逐条解释） （带简单优化） （激进的深度优化）
 *   慢          ~10x          ~100x
 *
 * 判断依据是方法的调用次数（-XX:CompileThreshold，分层编译下由 JVM 动态决定）。
 */
@RestController
@RequestMapping("/jit")
public class JitController {

    private static final Logger log = LoggerFactory.getLogger(JitController.class);

    /**
     * 跑 N 轮同样的计算，返回每轮的耗时。
     *
     * 期望：前几轮明显慢，然后在某轮突然掉下来（掉的那个点就是编译完成的位置）。
     *
     * 为什么每轮的结果要单独返回而不是只给一个总数：
     * 只给总数的话，这个现象就被平均掉了 —— 而这正是微基准测试最容易骗人的地方。
     */
    @GetMapping("/warmup")
    public Map<String, Object> warmup(@RequestParam(defaultValue = "20") int rounds,
                                      @RequestParam(defaultValue = "300000") int iters) {
        rounds = Math.max(1, Math.min(rounds, 200));
        iters = Math.max(1, Math.min(iters, 5_000_000));

        List<Long> micros = new ArrayList<>(rounds);
        double sink = 0;
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            sink += workload(iters);
            micros.add((System.nanoTime() - t0) / 1000);
        }

        long first = micros.get(0);
        long best = micros.stream().mapToLong(Long::longValue).min().orElse(first);
        long last = micros.get(micros.size() - 1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rounds", rounds);
        result.put("iterationsPerRound", iters);
        result.put("microsPerRound", micros);
        result.put("firstRoundMicros", first);
        result.put("bestRoundMicros", best);
        result.put("lastRoundMicros", last);
        result.put("speedupFirstToBest", String.format("%.1fx", first * 1.0 / Math.max(1, best)));
        result.put("sink", String.format("%.3f", sink));   // 防止整个循环被优化掉

        result.put("whatYouShouldSee", "前几轮慢，某一轮开始陡降，之后稳定在低位。"
                + "那个陡降点就是 C2 编译完成的位置。");
        result.put("howToObserve", "启动时加 -XX:+PrintCompilation（或 -Xlog:jit+compilation=info），"
                + "日志里出现某个方法名的那一刻，就是它被编译了；"
                + "再加 -XX:-TieredCompilation 关掉分层编译，对比曲线会完全不同。");
        result.put("whyItMatters", "任何微基准如果不预热，测的都是解释器的速度 —— "
                + "和线上跑了几小时的 JVM 完全不是一回事。这就是 JMH 存在的理由。");
        log.info("JIT 预热：首轮 {}µs，最好 {}µs，加速 {:.1f}x",
                first, best, first * 1.0 / Math.max(1, best));
        return result;
    }

    /**
     * 故意写得"值得被 JIT 优化"：
     *   - 循环体小 → 容易被内联
     *   - Math.sqrt 是 intrinsic → 会被替换成硬件指令
     *   - 常量折叠、循环展开、逃逸分析都能作用上
     *
     * 如果写一个包含复杂分支或大量多态调用的方法，优化空间小，曲线就会平得多。
     */
    private static double workload(int iters) {
        double x = 1.0;
        for (int i = 0; i < iters; i++) {
            x = Math.sqrt(x * 1.0000001 + i);
            if (x > 1e9) {
                x = 1.0;
            }
        }
        return x;
    }
}
