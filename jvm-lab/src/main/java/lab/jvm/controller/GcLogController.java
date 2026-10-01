package lab.jvm.controller;

import lab.jvm.support.LabFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC 日志解读。
 *
 * 为什么需要这个场景：演练场里其他所有现象都是"制造问题"，
 * 只有这个是**"读证据、做决策"** —— 而后者才是 JVM 调优的日常工作。
 *
 * 真实流程是这样的：
 *   ① 拿到一份 GC 日志（线上随时都在写）
 *   ② 从里面读出：停顿多久、多频繁、回收效率如何
 *   ③ 判断瓶颈在哪（分配太快？存活集太大？还是参数没配对？）
 *   ④ 调一个参数，再跑同一份负载，对比日志
 *
 * 这个接口做的是 ①②（解析 + 自动诊断），③④ 靠 ./lab.sh restart &lt;profile&gt; 换 GC 复现。
 *
 * 日志格式来自启动参数：
 *   -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M
 * 一行典型内容：
 *   [2026-10-01T02:40:45.123+0900][1.234s][info][gc] GC(12) Pause Young (Normal)
 *       (G1 Evacuation Pause) 24M-&gt;3M(256M) 12.345ms
 */
@RestController
@RequestMapping("/gc")
public class GcLogController {

    private static final Logger log = LoggerFactory.getLogger(GcLogController.class);

    /**
     * 匹配一条带停顿的 GC 记录。
     *
     * 分组：① GC 序号 ② 类型（Young / Full / Mixed / ...）
     *      ③ 回收前 MB ④ 回收后 MB ⑤ 堆总大小 MB ⑥ 停顿时长 ms
     *
     * 注意日志里还有大量**不带停顿**的行（并发标记的各个阶段），
     * 那些不该计入停顿统计 —— 它们不 STW。这个正则只挑 "Pause" 行。
     */
    private static final Pattern PAUSE = Pattern.compile(
            "GC\\((\\d+)\\)\\s+Pause\\s+(\\w+)[^0-9]*(\\d+)M->(\\d+)M\\((\\d+)M\\)\\s+([\\d.]+)ms");

    /**
     * ZGC 的 STW 阶段停顿，格式和上面**完全不同**：
     *
     *   [.][0.747s][info][gc,phases] GC(0) Pause Mark Start 0.007ms
     *
     * 没有 <code>24M-&gt;3M(256M)</code> 这段堆用量 —— 所以上面那个正则一条都匹配不上。
     * 这是个很容易踩的坑：换到 ZGC 之后 GC 解读会**静默返回空**，看起来像功能坏了。
     *
     * 类型只有 Mark Start / Mark End / Relocate Start 三种（ZGC 的其他阶段是并发的，
     * 不停顿）。注意**一次 GC 周期会产生 3 条这样的停顿**，所以按条数统计时
     * gcCount 约等于周期数的 3 倍 —— 这也是为什么下面要单独给出 cycleCount。
     */
    private static final Pattern ZGC_PHASE = Pattern.compile(
            "GC\\((\\d+)\\)\\s+Pause\\s+([A-Za-z][A-Za-z ]*?)\\s+([\\d.]+)ms\\s*$");

    /**
     * ZGC 的**周期**汇总行（这才是"一次 GC"）：
     *
     *   [.][0.795s][info][gc] GC(0) Garbage Collection (Warmup) 28M(11%)-&gt;10M(4%)
     *
     * 用它算周期数和回收量（分配速率要靠这个，因为 ZGC 没有 Young GC 的概念）。
     */
    private static final Pattern ZGC_CYCLE = Pattern.compile(
            "GC\\((\\d+)\\)\\s+Garbage Collection\\s+\\(([^)]*)\\)\\s+(\\d+)M\\(\\d+%\\)->(\\d+)M\\(\\d+%\\)");

    /** 从日志行里取 uptime（形如 [12.345s]），用来算 GC 间隔 */
    private static final Pattern UPTIME = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "200000") int maxLines,
            @RequestParam(defaultValue = "0") int tailMb) {

        Map<String, Object> result = new LinkedHashMap<>();

        // 复用 LabFiles 的路径校验（它做了三层防护），文件不存在时它会抛
        Path file;
        try {
            file = LabFiles.resolve("logs/gc.log");
        } catch (IllegalArgumentException e) {
            result.put("outcome", "还没有 GC 日志");
            result.put("hint", "启动参数里带了 -Xlog:gc*:file=/logs/gc.log，"
                    + "跑一会儿（或点几个演练场景）再来看");
            return result;
        }

        List<Pause> pauses = new ArrayList<>();
        List<long[]> zgcCycles = new ArrayList<>();   // {uptimeMs, beforeMb, afterMb}
        String collector = "unknown";
        long totalLines = 0;
        long startUptimeMs = -1;
        long endUptimeMs = -1;
        String firstTs = null, lastTs = null;

        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                totalLines++;
                Matcher ts = UPTIME.matcher(line);
                long uptime = ts.find() ? (long) (Double.parseDouble(ts.group(1)) * 1000) : -1;
                if (uptime >= 0) {
                    if (startUptimeMs < 0) startUptimeMs = uptime;
                    endUptimeMs = uptime;
                }
                if (firstTs == null) {
                    int b = line.indexOf('[');
                    int e = line.indexOf(']');
                    if (b == 0 && e > 1) firstTs = line.substring(1, e);
                }
                int b = line.indexOf('[');
                int e = line.indexOf(']');
                if (b == 0 && e > 1) lastTs = line.substring(1, e);

                Matcher m = PAUSE.matcher(line);
                if (m.find()) {
                    pauses.add(new Pause(
                            Long.parseLong(m.group(1)),
                            m.group(2),
                            Long.parseLong(m.group(3)),
                            Long.parseLong(m.group(4)),
                            Long.parseLong(m.group(5)),
                            Double.parseDouble(m.group(6)),
                            uptime));
                } else {
                    // G1 / Parallel / Serial 没匹配上，再试 ZGC 的阶段停顿
                    Matcher z = ZGC_PHASE.matcher(line);
                    if (z.find()) {
                        pauses.add(new Pause(
                                Long.parseLong(z.group(1)),
                                z.group(2),
                                -1, -1, -1,          // ZGC 的停顿行不带堆用量
                                Double.parseDouble(z.group(3)),
                                uptime));
                    }
                }

                Matcher zc = ZGC_CYCLE.matcher(line);
                if (zc.find()) {
                    zgcCycles.add(new long[]{
                            uptime,
                            Long.parseLong(zc.group(3)),
                            Long.parseLong(zc.group(4))});
                }

                if ("unknown".equals(collector)) {
                    if (line.contains("The Z Garbage Collector")) collector = "zgc";
                    else if (line.contains("G1 Evacuation Pause")) collector = "g1";
                    else if (line.contains("Using Parallel")) collector = "parallel";
                    else if (line.contains("Using Serial")) collector = "serial";
                }
                // 防止读一个巨大的日志把响应拖死
                if (totalLines > maxLines) break;
            }
        } catch (IOException e) {
            result.put("outcome", "读取 GC 日志失败");
            result.put("error", e.getMessage());
            return result;
        }

        result.put("outcome", pauses.isEmpty() ? "日志里还没有带停顿的 GC 记录" : "ok");
        result.put("collector", collector);
        result.put("logFile", file.toString());
        result.put("logSizeKb", sizeKb(file));
        result.put("linesScanned", totalLines);
        result.put("firstTimestamp", firstTs);
        result.put("lastTimestamp", lastTs);

        if (pauses.isEmpty()) {
            result.put("hint", "刚启动的 JVM 可能还没触发过 GC。点几个演练场景再来");
            return result;
        }

        // ── ① 总览 ──
        double totalPauseMs = 0;
        double maxPauseMs = 0;
        Pause worst = null;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Double> typePauseMs = new LinkedHashMap<>();

        for (Pause p : pauses) {
            totalPauseMs += p.ms;
            if (p.ms > maxPauseMs) { maxPauseMs = p.ms; worst = p; }
            byType.merge(p.type, 1, Integer::sum);
            typePauseMs.merge(p.type, p.ms, Double::sum);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("gcCount", pauses.size());
        if ("zgc".equals(collector)) {
            // ZGC 一条周期 = 3 条阶段停顿。不说明的话 gcCount 会被误读成"GC 次数"
            overview.put("cycleCount", zgcCycles.size());
            overview.put("countNote", "gcCount 是**阶段停顿条数**；ZGC 一个周期有 3 个"
                    + "STW 阶段（Mark Start / Mark End / Relocate Start），"
                    + "所以真正的 GC 周期数是 cycleCount");
        }
        overview.put("totalPauseMs", round(totalPauseMs));
        overview.put("avgPauseMs", round(totalPauseMs / pauses.size()));
        overview.put("maxPauseMs", round(maxPauseMs));
        overview.put("maxPauseAt", worst == null ? null : (worst.uptimeMs + "ms 时（GC#" + worst.id + " " + worst.type + "）"));
        if (startUptimeMs >= 0 && endUptimeMs > startUptimeMs) {
            long span = endUptimeMs - startUptimeMs;
            overview.put("spanSeconds", round(span / 1000.0));
            // 吞吐量：GC 停顿占总时间的比例。生产上超过 5% 就该警惕了。
            overview.put("pauseRatioPercent", round(totalPauseMs * 100.0 / span));
        }
        overview.put("byType", byType);
        overview.put("pauseMsByType", typePauseMs);
        result.put("overview", overview);

        // ── ② 停顿分布 ──
        result.put("pauseHistogram", histogram(pauses));

        // ── ③ 分配速率（从回收量 + 间隔推算）──
        result.put("allocationRate", allocationRate(pauses, zgcCycles));

        // ── ④ 自动诊断 ──
        result.put("diagnosis", diagnose(pauses, totalPauseMs, startUptimeMs, endUptimeMs, collector));

        result.put("recentPauses", pauses.subList(Math.max(0, pauses.size() - 12), pauses.size()));
        result.put("howToCompare", "换 GC 再跑同一份负载：./lab.sh restart g1 | parallel | serial，"
                + "然后回到这个接口对比 overview 和 pauseHistogram");
        return result;
    }

    /** 停顿分布。平均值会骗人 —— 一次 800ms 的毛刺藏在 200 次 5ms 里，均值看不出来。 */
    private List<Map<String, Object>> histogram(List<Pause> pauses) {
        double[] edges = {10, 50, 100, 500, Double.MAX_VALUE};
        String[] labels = {"< 10ms", "10~50ms", "50~100ms", "100~500ms", "> 500ms"};
        int[] counts = new int[edges.length];
        for (Pause p : pauses) {
            for (int i = 0; i < edges.length; i++) {
                if (p.ms < edges[i]) { counts[i]++; break; }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("range", labels[i]);
            row.put("count", counts[i]);
            row.put("percent", round(counts[i] * 100.0 / pauses.size()));
            out.add(row);
        }
        return out;
    }

    /**
     * 估算分配速率。
     *
     * 原理：Young GC 回收掉的量 ≈ 这段时间内新分配的量（大部分对象朝生夕死）。
     * 所以 sum(回收量) / 时间 = 分配速率。
     *
     * 注意这只是估算：如果有大对象直接进老年代，或者有对象活过了 Young，
     * 这个数字会偏低。但它对判断量级足够了。
     */
    private Map<String, Object> allocationRate(List<Pause> pauses, List<long[]> zgcCycles) {
        Map<String, Object> map = new LinkedHashMap<>();
        long reclaimedMb = 0;
        long firstUp = -1, lastUp = -1;
        int youngCount = 0;

        // ZGC 没有 Young GC 的概念，用「周期回收量」算 —— 语义一样：
        // 一个周期回收掉多少，约等于这段时间新分配了多少。
        if (!zgcCycles.isEmpty()) {
            for (long[] c : zgcCycles) {
                reclaimedMb += Math.max(0, c[1] - c[2]);
                youngCount++;
                if (c[0] >= 0) {
                    if (firstUp < 0) firstUp = c[0];
                    lastUp = c[0];
                }
            }
            map.put("basis", "ZGC 周期回收量（ZGC 没有 Young GC 的概念）");
        } else {
            for (Pause p : pauses) {
                if (!"Young".equals(p.type) && !"Mixed".equals(p.type)) continue;
                reclaimedMb += Math.max(0, p.beforeMb - p.afterMb);
                youngCount++;
                if (p.uptimeMs >= 0) {
                    if (firstUp < 0) firstUp = p.uptimeMs;
                    lastUp = p.uptimeMs;
                }
            }
            map.put("basis", "sum(Young/Mixed GC 回收量) / 时间");
        }

        map.put("youngGcCount", youngCount);
        map.put("reclaimedMb", reclaimedMb);
        if (firstUp > 0 && lastUp > firstUp) {
            double seconds = (lastUp - firstUp) / 1000.0;
            map.put("windowSeconds", round(seconds));
            map.put("mbPerSecond", round(reclaimedMb / seconds));
            map.put("youngIntervalMs", round((lastUp - firstUp) / (double) Math.max(1, youngCount)));
        }
        map.put("caveat", "这是估算：靠 sum(回收量)/时间 推出来的。"
                + "有大对象直接进老年代时会偏低，但判断量级够用");
        return map;
    }

    /**
     * 规则式诊断。
     *
     * 这里刻意用**可读的规则**而不是"AI 分析" ——
     * 每条结论都要能追溯到日志里的具体数字，这样你才能学会自己看。
     * 规则本身也写出来，不同意可以自己改。
     */
    private List<Map<String, Object>> diagnose(List<Pause> pauses,
                                               double totalPauseMs,
                                               long startUptimeMs,
                                               long endUptimeMs,
                                               String collector) {
        List<Map<String, Object>> findings = new ArrayList<>();

        // ZGC 先说清楚"为什么下面看不到 Young/Full 的规则命中" ——
        // 否则使用者会以为解析漏了。ZGC 是并发收集器，压根没有 Young/Full 之分。
        if ("zgc".equals(collector)) {
            double zmax = 0, zsum = 0;
            for (Pause p : pauses) { zmax = Math.max(zmax, p.ms); zsum += p.ms; }
            Map<String, Object> f = finding("info",
                    "这是 ZGC 的日志：停顿只有三个阶段（Mark Start / Mark End / Relocate Start），"
                            + "最大 " + round(zmax) + "ms、平均 " + round(zsum / pauses.size()) + "ms");
            f.put("likely", "ZGC 的设计目标就是亚毫秒停顿，且停顿**不随堆大小增长** —— "
                    + "因为它把标记、移动、重定位都做成了并发。代价是更高的 CPU 占用和更多的内存开销");
            f.put("action", "所以下面「Full GC 频繁」「Young GC 太频繁」两条规则**不会命中** —— "
                    + "ZGC 没有 Young/Full 之分，不是解析漏了。"
                    + "要对比就换回 g1/parallel/serial 跑同一份负载，比 pauseHistogram 的尾部");
            findings.add(f);
        }

        int fullCount = 0;
        long fullReclaimedMb = 0;
        long heapMaxMb = 0;
        for (Pause p : pauses) {
            if ("Full".equals(p.type)) {
                fullCount++;
                fullReclaimedMb += Math.max(0, p.beforeMb - p.afterMb);
            }
            heapMaxMb = Math.max(heapMaxMb, p.totalMb);
        }

        // 规则 1：Full GC 频繁 + 每次回收量都很低 → 老年代里大部分是活对象
        if (fullCount >= 3) {
            double avgReclaim = fullReclaimedMb / (double) fullCount;
            double ratio = heapMaxMb > 0 ? avgReclaim * 100.0 / heapMaxMb : 0;
            Map<String, Object> f = finding(
                    ratio < 5 ? "warning" : "info",
                    "Full GC 发生了 " + fullCount + " 次，平均每次只回收 "
                            + round(avgReclaim) + "MB（占堆 " + round(ratio) + "%）");
            if (ratio < 5) {
                f.put("likely", "存活集过大或存在内存泄漏 —— 老年代里几乎都是活对象，"
                        + "Full GC 捞不到东西。去看 /status 的 heap 曲线是不是只涨不跌");
                f.put("action", "① 先排除泄漏（两次堆快照对比）② 如果确认都是活对象，"
                        + "那就是堆本身不够，该调大 -Xmx 而不是调 GC 参数");
            } else {
                f.put("likely", "老年代确实有垃圾要收，属于正常行为");
            }
            findings.add(f);
        }

        // 规则 2：Young GC 太频繁 → 分配速率高 或 Young 区太小
        long firstUp = -1, lastUp = -1;
        int youngCount = 0;
        for (Pause p : pauses) {
            if ("Young".equals(p.type)) {
                youngCount++;
                if (p.uptimeMs >= 0) { if (firstUp < 0) firstUp = p.uptimeMs; lastUp = p.uptimeMs; }
            }
        }
        if (youngCount >= 5 && firstUp > 0 && lastUp > firstUp) {
            double interval = (lastUp - firstUp) / (double) youngCount;
            Map<String, Object> f = finding(interval < 200 ? "warning" : "info",
                    "Young GC 平均每 " + round(interval) + "ms 一次（共 " + youngCount + " 次）");
            if (interval < 200) {
                f.put("likely", "分配速率太高，或者 Young 区太小");
                f.put("action", "① 先看是谁在猛分配（jmap -histo 或 async-profiler 的 alloc 模式）"
                        + "② 如果分配量确实大且大部分朝生夕死，调大 Young 区（-Xmn / -XX:NewRatio）"
                        + "能让每次 GC 撑更久");
            }
            findings.add(f);
        }

        // 规则 3：停顿毛刺 —— 均值会骗人
        double max = 0, sum = 0;
        for (Pause p : pauses) { max = Math.max(max, p.ms); sum += p.ms; }
        double avg = sum / pauses.size();
        if (max > 100 && max > avg * 8) {
            Map<String, Object> f = finding("warning",
                    "存在停顿毛刺：最大 " + round(max) + "ms，而平均只有 " + round(avg) + "ms");
            f.put("likely", "典型的长尾问题。均值看着还行，但用户偶尔会遇到一次明显的卡顿 —— "
                    + "这比「整体慢一点」糟糕得多");
            f.put("action", "① 看直方图的尾部区间 ② 用 jstat -gcutil 或 GC 日志定位那一次"
                    + "是 Young 还是 Full ③ 长毛刺多半来自 Full GC 或 humongous 对象分配");
            findings.add(f);
        }

        // 规则 4：吞吐量（GC 停顿占比）
        if (startUptimeMs >= 0 && endUptimeMs > startUptimeMs) {
            double span = endUptimeMs - startUptimeMs;
            double ratio = totalPauseMs * 100.0 / span;
            Map<String, Object> f = finding(ratio > 5 ? "warning" : "info",
                    "GC 停顿占总运行时间的 " + round(ratio) + "%（" + round(totalPauseMs)
                            + "ms / " + round(span / 1000.0) + "s）");
            f.put("likely", ratio > 5
                    ? "超过 5% 就该警惕了 —— 大量 CPU 花在 GC 上而不是业务逻辑上"
                    : "属于健康范围");
            if (ratio > 5) {
                f.put("action", "先别急着换 GC。优先查：是不是存活集太大？是不是在频繁分配大对象？"
                        + "参数调优的收益通常远小于代码层面的修复");
            }
            findings.add(f);
        }

        if (findings.isEmpty()) {
            findings.add(finding("ok", "没发现明显异常 —— GC 次数、停顿、回收效率都在正常范围"));
        }
        return findings;
    }

    private Map<String, Object> finding(String level, String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("what", what);
        return m;
    }

    private long sizeKb(Path f) {
        try { return Files.size(f) / 1024; } catch (IOException e) { return -1; }
    }

    private double round(double v) { return Math.round(v * 100) / 100.0; }

    /** 一条带停顿的 GC 记录 */
    public static class Pause {
        public final long id;
        public final String type;
        public final long beforeMb;
        public final long afterMb;
        public final long totalMb;
        public final double ms;
        public final long uptimeMs;

        public Pause(long id, String type, long beforeMb, long afterMb,
                     long totalMb, double ms, long uptimeMs) {
            this.id = id;
            this.type = type;
            this.beforeMb = beforeMb;
            this.afterMb = afterMb;
            this.totalMb = totalMb;
            this.ms = ms;
            this.uptimeMs = uptimeMs;
        }
    }
}
