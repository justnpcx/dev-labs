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

    // ─────────────────────── JIT 反优化 ───────────────────────

    /**
     * 两个形状实现，用来制造「单态 → 多态」的转变。
     *
     * 这是 JIT 反优化最典型的触发方式：JIT 会观察每个调用点**实际收到过几种类型**。
     * 只有一种时（单态）它敢大胆内联；出现第二种时就得退回去（去优化）。
     */
    private interface Shape {
        double area();
    }

    private static final class Circle implements Shape {
        final double r;
        Circle(double r) { this.r = r; }
        public double area() { return Math.PI * r * r; }
    }

    private static final class Square implements Shape {
        final double a;
        Square(double a) { this.a = a; }
        public double area() { return a * a; }
    }

    /** 这个循环里的 sh.area() 就是被 JIT 观察的调用点 */
    private static double sumAreas(Shape[] shapes, int iters) {
        double s = 0;
        int n = shapes.length;
        for (int i = 0; i < iters; i++) {
            s += shapes[i % n].area();
        }
        return s;
    }

    /**
     * JIT 反优化（deoptimization）演练。
     *
     * 这个场景回答一个很实际的问题：**为什么我的接口跑着跑着变慢了？**
     *
     * 阶段一：数组里全是 Circle → 调用点**单态** → JIT 把 Circle.area() 内联进循环，
     *         循环体变成纯算术，跑得飞快。
     * 阶段二：混入 Square → 调用点变**多态** → JIT 发现之前的假设不成立，
     *         把已编译的代码标记为 "made not entrant"（失效），退回解释执行，
     *         再重新编译一份带类型检查的版本。
     *
     * 这个过程**是自动的、正确的**，但那一瞬间性能会掉一个数量级。
     * 如果你在压测中途改了数据分布，测出来的数字就完全不可比。
     *
     * 观察方式：
     *   -XX:+PrintCompilation  → 日志里会看到同一方法的多次编译记录，
     *                            以及 "made not entrant" / "made zombie"
     *   -XX:+UnlockDiagnosticVMOptions -XX:+TraceDeoptimization
     */
    @GetMapping("/deopt")
    public Map<String, Object> deopt(@RequestParam(defaultValue = "10") int rounds,
                                     @RequestParam(defaultValue = "800000") int iters) {
        rounds = Math.max(2, Math.min(rounds, 100));
        iters = Math.max(1000, Math.min(iters, 20_000_000));
        int half = rounds / 2;

        Shape[] monomorphic = new Shape[64];
        for (int i = 0; i < monomorphic.length; i++) monomorphic[i] = new Circle(i + 1);

        // 阶段一先跑几轮，确保 JIT 已经完成编译并内联
        for (int i = 0; i < 5; i++) sumAreas(monomorphic, iters);

        List<Long> mono = new ArrayList<>(half);
        for (int i = 0; i < half; i++) {
            long t = System.nanoTime();
            sumAreas(monomorphic, iters);
            mono.add((System.nanoTime() - t) / 1000);
        }

        // 混入第二种实现 —— 调用点从此不再单态
        Shape[] polymorphic = new Shape[64];
        for (int i = 0; i < polymorphic.length; i++) {
            polymorphic[i] = (i % 2 == 0) ? new Circle(i + 1) : new Square(i + 1);
        }

        List<Long> poly = new ArrayList<>(rounds - half);
        for (int i = 0; i < rounds - half; i++) {
            long t = System.nanoTime();
            sumAreas(polymorphic, iters);
            poly.add((System.nanoTime() - t) / 1000);
        }

        long monoBest = mono.stream().mapToLong(Long::longValue).min().orElse(0);
        long polyBest = poly.stream().mapToLong(Long::longValue).min().orElse(0);
        long monoFirst = mono.get(0);
        long polyFirst = poly.get(0);

        // 去优化是**一次性**的：同一个调用点被去优化并重新编译之后，
        // 后续调用直接走新版本，不会再去优化。
        // 所以只有在 JVM 冷启动后第一次跑，才能看到那个 spike ——
        // 这个特性必须如实告诉用户，否则他会以为接口坏了（实测第 2 次起就是 1.00x）。
        boolean deoptObserved = polyFirst > monoBest * 1.5;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deoptObserved", deoptObserved);
        result.put("roundsEachPhase", half);
        result.put("iterationsPerRound", iters);
        result.put("monomorphicMicros", mono);
        result.put("polymorphicMicros", poly);
        result.put("monoFirstMicros", monoFirst);
        result.put("monoBestMicros", monoBest);
        result.put("polyFirstMicros", polyFirst);
        result.put("polyBestMicros", polyBest);
        // 去优化的代价是**瞬时**的：那一轮要退回去重新编译
        result.put("deoptSpike", String.format("%.2fx",
                polyFirst * 1.0 / Math.max(1, monoBest)));
        // 而稳态代价很小 —— JIT 会为多态场景重新编译一份
        result.put("steadyStateSlowdown", String.format("%.2fx",
                polyBest * 1.0 / Math.max(1, monoBest)));

        result.put("whatHappened",
                "前半段数组里全是 Circle，调用点单态，JIT 把 area() 内联进循环，"
              + "循环体是纯算术。后半段混入 Square，调用点变多态，JIT 之前的假设失效 —— "
              + "已编译代码被标记为 made not entrant，那一轮退回解释执行，"
              + "之后重新编译一份带类型检查的版本。");
        result.put("readTheNumbers", deoptObserved
                ? "注意两个倍数差很多：deoptSpike（第一轮 / 单态最好）是**瞬时**代价，"
                + "steadyStateSlowdown（多态最好 / 单态最好）是**稳态**代价。"
                + "本机实测 spike 可以到 10 倍以上，而稳态通常只慢一点点 —— "
                + "JIT 会为多态场景重新优化，稳态损失远小于第一眼印象。"
                : "⚠ 这次没看到去优化（spike ≈ 1.0x）—— 因为**这个 JVM 已经跑过这个场景了**。\n"
                + "去优化是一次性的：调用点被去优化并重新编译后，后续调用直接走新版本。\n"
                + "想看到它，先 ./lab.sh restart 再跑一次。");
        result.put("oneShot", "去优化只在**每个 JVM 生命周期内发生一次**（针对同一个调用点）。"
                + "这也是它难排查的原因之一 —— 线上偶发一次卡顿，等你去看的时候早恢复常态了，"
                + "只能靠 GC 日志、PrintCompilation 这类**留痕**手段回溯。");
        result.put("whyItMatters",
                "去优化本身是**自动且正确**的，但它意味着：数据分布一变，性能就抖一下。"
              + "压测中途换数据、灰度期间新老逻辑并存、缓存预热期 —— "
              + "这些时候测出来的数字都不可比。"
              + "看到「跑着跑着卡一下，之后又正常」先怀疑类型不稳定。");
        result.put("howToObserve",
                "启动加 -XX:+PrintCompilation，日志里会看到同一方法被编译多次，"
              + "以及 made not entrant / made zombie 字样；"
              + "想看更细的加 -XX:+UnlockDiagnosticVMOptions -XX:+TraceDeoptimization。");
        result.put("howToFix",
                "① 别在热点路径上让同一个变量承载多种类型"
              + " ② 用接口但保证实现单一，或者干脆拆成两个方法"
              + " ③ 已经多态且无法避免时，考虑用类型判断提前分流");
        log.info("JIT 去优化：单态最好 {}µs，多态首轮 {}µs（spike {}），多态稳态 {}µs",
                monoBest, polyFirst, result.get("deoptSpike"), polyBest);
        return result;
    }
}
