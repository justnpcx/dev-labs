package lab.jvm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arthas 演练的靶子。
 *
 * <h2>这个类里的代码是**故意写坏的**</h2>
 *
 * 它存在的唯一目的，是给 Arthas 提供"值得被发现的问题"。
 * 真实项目里你不会知道问题在哪，所以才需要 Arthas；
 * 这里我们把问题**埋好**，然后让你用工具把它挖出来 —— 挖的过程才是学习内容。
 *
 * <h2>埋了四个问题，对应 Arthas 的四种用法</h2>
 *
 * <table border="1">
 *   <tr><th>问题</th><th>在哪</th><th>用哪个命令发现</th></tr>
 *   <tr>
 *     <td>① 调用链里有一层特别慢</td>
 *     <td>{@link #quotePrice(int)} 里的 300ms 等待</td>
 *     <td>{@code trace} —— 逐层耗时，一眼看出瓶颈在哪一层</td>
 *   </tr>
 *   <tr>
 *     <td>② VIP 折扣算错了</td>
 *     <td>{@link #applyDiscount(long, boolean)} 里的 {@code * 9 / 10}</td>
 *     <td>{@code jad} + {@code mc} + {@code retransform} —— 不重启改线上代码</td>
 *   </tr>
 *   <tr>
 *     <td>③ 每次调用都 new SimpleDateFormat</td>
 *     <td>{@link #formatStamp()}</td>
 *     <td>{@code watch} 看调用次数；{@code profiler} 看它的 CPU 占比</td>
 *   </tr>
 *   <tr>
 *     <td>④ 入参出参没日志，出问题只能猜</td>
 *     <td>整个类</td>
 *     <td>{@code watch} —— 不加日志、不重启，直接看方法的入参和返回值</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么这四个问题选得好</h2>
 *
 * 它们正好覆盖 Arthas 的三类核心场景：
 * <ul>
 *   <li><b>排错定位</b>（①④）—— 线上出问题，日志不够用，又不能重启加日志</li>
 *   <li><b>性能瓶颈</b>（①③）—— 知道慢，但不知道慢在哪一层</li>
 *   <li><b>热更新</b>（②）—— 改一行就好，但走发版流程要等几小时</li>
 * </ul>
 *
 * <h2>★ 一个真实踩到的坑：所有 {@code @RequestParam} 都写了显式的 name</h2>
 *
 * 这不是啰嗦，是**必须**的。原因：
 *
 * <p>Spring MVC 从 {@code @RequestParam} 上拿不到参数名时，会退回去读
 * **字节码里的局部变量表**（需要编译时加 {@code -parameters}）。
 * 项目本身的构建（Spring Boot parent pom）是带这个 flag 的，所以平时没问题。
 *
 * <p>但 <b>Arthas 的 {@code mc} 命令编译时不带 {@code -parameters}</b>。
 * 于是热更新之后的类里没有参数名信息，Spring 解析不了 ——
 * 调用直接 500：
 *
 * <pre>
 * java.lang.IllegalArgumentException: Name for argument of type [int] not specified,
 *   and parameter name information not available via reflection.
 *   Ensure that the compiler uses the '-parameters' flag.
 * </pre>
 *
 * <p>更阴险的是它**不一定立刻暴露**：Spring 会把解析好的参数元数据缓存起来，
 * 所以"热更新前调用过的接口"照样正常，只有"热更新后才第一次调用"的接口才会炸。
 * 这会让排查方向完全跑偏。
 *
 * <p>显式写 {@code name = "..."} 就彻底不依赖这个 flag 了 ——
 * 这也是生产代码里推荐的做法。
 */
@RestController
@RequestMapping("/demo")
public class ArthasDemoController {

    private static final Logger log = LoggerFactory.getLogger(ArthasDemoController.class);

    /**
     * 下单流程。
     *
     * <p>这条调用链是故意设计成"总耗时 300 多毫秒，但只有一层是慢的"——
     * 这正是 {@code trace} 要解决的问题：光看总耗时你只知道慢，
     * trace 之后你才知道该去优化哪一行。
     */
    @GetMapping("/order")
    public Map<String, Object> order(@RequestParam(name = "id", defaultValue = "1") int id) {
        long t0 = System.nanoTime();

        validate(id);
        long quote = quotePrice(id);
        int stock = checkInventory(id);
        long payable = applyDiscount(quote, id % 2 == 0);
        String stamp = formatStamp();

        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("orderId", id);
        r.put("quote", quote);
        r.put("payable", payable);
        r.put("stock", stock);
        r.put("stamp", stamp);
        r.put("totalMs", totalMs);
        r.put("hint", "总耗时里绝大部分在 quotePrice 一层 —— "
                + "用 `trace lab.jvm.controller.ArthasDemoController order` 看逐层耗时");
        return r;
    }

    /**
     * 报价查询。也是热更新场景的入口。
     *
     * <p>调用它两次对比，就能验证热更新是否生效 —— 不需要重启，不需要重新部署。
     */
    @GetMapping("/price")
    public Map<String, Object> price(@RequestParam(name = "amount", defaultValue = "100") long amount,
                                     @RequestParam(name = "vip", defaultValue = "true") boolean vip) {
        long payable = applyDiscount(amount, vip);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("amount", amount);
        r.put("vip", vip);
        r.put("payable", payable);
        // 刻意不写死折扣比例 —— 热更新只影响你改的那个方法，
        // 如果这里写死 "9 折"，热更新完 applyDiscount 之后两边就对不上了
        r.put("discountRule", vip ? "VIP 折扣（见 applyDiscount）" : "无折扣");
        r.put("hint", "VIP 应该是 8 折。想不改代码验证？用 jad + mc + retransform 热更新 —— "
                + "./lab.sh arthas-demo 有完整步骤，./lab.sh arthas-hotfix 是一键版");
        return r;
    }

    // ─────────────────────── 下面是"有问题"的那几层 ───────────────────────

    /** 参数校验。快，不是瓶颈。 */
    private void validate(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("订单号必须为正数：" + id);
        }
    }

    /**
     * ★ 问题①：这一层占了整个调用链 95% 以上的时间。
     *
     * <p>真实项目里这里通常是慢 SQL、远程调用、或者某个没加缓存的循环。
     * 这里用 sleep 模拟，效果一样：**总耗时看着很长，但你不知道长在哪**。
     */
    private long quotePrice(int id) {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 100L * id;
    }

    /** 查库存。快。 */
    private int checkInventory(int id) {
        return 100 - (id % 10);
    }

    /**
     * ★ 问题②：VIP 折扣写错了 —— 9 折应该是 8 折。
     *
     * <p>这是热更新场景的靶子。选它是因为：
     * <ul>
     *   <li>改动极小（一个字符），适合演示"改一行"</li>
     *   <li>结果**可验证** —— 调用前后返回值从 90 变成 80</li>
     *   <li>它是 private 方法，正好演示 {@code retransform} 不挑方法可见性</li>
     * </ul>
     *
     * <p>为什么生产上会需要热更新：这种一行改动走完整发版流程要几小时，
     * 而它可能正在每分钟造成真实的资损。
     */
    private long applyDiscount(long amount, boolean vip) {
        return vip ? amount * 9 / 10 : amount;
    }

    /**
     * ★ 问题③：每次调用都 new 一个 SimpleDateFormat。
     *
     * <p>这是 Java 里最经典的性能低级错误之一 —— SimpleDateFormat 不是线程安全的，
     * 所以很多人"为了安全"就在方法里 new 一个。结果是**每次调用都重新解析时区、
     * 加载 Locale 数据**，在高频路径上是实打实的开销。
     *
     * <p>正确做法：用 {@code DateTimeFormatter}（不可变、线程安全），
     * 或者用 ThreadLocal 缓存。
     */
    private String formatStamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    // ─────────────────────── 给 profiler 用的纯 CPU 热点 ───────────────────────

    /**
     * ★ 问题③的放大版：跑一批格式化，让 CPU 热点足够明显，profiler 才画得出火焰图。
     *
     * <p>单独开一个接口是因为 {@code /demo/order} 里只调一次，
     * 在火焰图上根本看不见 —— 想看性能问题得先把它放大。
     */
    @GetMapping("/format-loop")
    public Map<String, Object> formatLoop(@RequestParam(name = "times", defaultValue = "20000") int times) {
        long t0 = System.nanoTime();
        String last = "";
        for (int i = 0; i < times; i++) {
            last = formatStamp();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("times", times);
        r.put("elapsedMs", ms);
        r.put("last", last);
        r.put("hint", "用 `profiler start` 抓 10 秒火焰图，能在栈顶看到 "
                + "SimpleDateFormat.<init> —— 这就是每次都 new 的代价");
        return r;
    }

    /**
     * 给 {@code watch} 演示用的：返回一个列表，入参和返回值都值得观察。
     *
     * <p>{@code watch} 的典型用法是"这个方法收到的参数到底是什么"——
     * 线上偶发的问题往往卡在某个特定入参上，但日志里没打。
     */
    @GetMapping("/items")
    public List<String> items(@RequestParam(name = "count", defaultValue = "3") int count,
                              @RequestParam(name = "type", defaultValue = "normal") String type) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(type + "-item-" + i);
        }
        return list;
    }
}
