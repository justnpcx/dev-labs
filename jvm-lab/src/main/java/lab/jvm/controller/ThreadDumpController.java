package lab.jvm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 线程栈采集。
 *
 * 为什么调 jcmd 而不是用 ThreadMXBean？
 * ThreadMXBean.dumpAllThreads() 拿不到 **nid**（操作系统线程号）——
 * 而 nid 是 CPU 飙高时把 `top -H` 和线程栈对上的唯一钥匙。
 * jcmd Thread.print 的输出和线上 jstack 完全一致，包括 nid，
 * 所以直接调它，保证你在演练场看到的和在生产上看到的是一回事。
 *
 * 镜像用的是 temurin JDK，jcmd 一定在。容器内 JVM 是 PID 1，
 * 用 ProcessHandle.current().pid() 动态取，不写死。
 *
 * 为什么默认采 3 次？
 * 单次 dump 只能告诉你"此刻在哪"，看不出"卡住没动"。
 * 连续采样对比，栈一直不变的那些线程才是问题所在。
 * 这是文档里反复强调的「三次采样法」，所以做成默认行为。
 */
@RestController
@RequestMapping("/threads")
public class ThreadDumpController {

    private static final Logger log = LoggerFactory.getLogger(ThreadDumpController.class);

    /** 采样上限，防止有人用 samples=99999 把接口挂死 */
    private static final int MAX_SAMPLES = 10;
    private static final int MAX_INTERVAL_SECONDS = 30;

    private static final Path LOG_DIR = Path.of("/logs");

    /** jcmd 输出的线程头： "name" #tid daemon prio=5 os_prio=0 tid=0x.. nid=0x.. 状态 */
    private static final Pattern THREAD_HEAD =
            Pattern.compile("^\"([^\"]+)\"\\s+#(\\d+)\\s+(.*)$");

    /** java.lang.Thread.State: RUNNABLE 这类行 */
    private static final Pattern THREAD_STATE =
            Pattern.compile("java\\.lang\\.Thread\\.State:\\s+([A-Z_]+)");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @PostMapping("/dump")
    public ResponseEntity<Map<String, Object>> dump(
            @RequestParam(defaultValue = "3") int samples,
            @RequestParam(defaultValue = "2") int intervalSeconds) {

        int n = Math.min(Math.max(samples, 1), MAX_SAMPLES);
        int interval = Math.min(Math.max(intervalSeconds, 0), MAX_INTERVAL_SECONDS);

        String fileName = "threaddump-" + LocalDateTime.now().format(STAMP) + ".txt";
        Path outFile = LOG_DIR.resolve(fileName);

        StringBuilder all = new StringBuilder();
        List<String> errors = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            try {
                String text = runJcmdThreadPrint();
                all.append("=================== 第 ").append(i).append('/').append(n)
                   .append(" 次采样  ").append(LocalDateTime.now()).append(" ===================\n")
                   .append(text).append('\n');
            } catch (Exception e) {
                log.error("第 {} 次线程栈采集失败", i, e);
                errors.add("第 " + i + " 次失败：" + e.getMessage());
            }
            if (i < n && interval > 0) {
                try {
                    Thread.sleep(interval * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (all.length() == 0) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("outcome", "采集失败");
            err.put("errors", errors);
            err.put("hint", "容器镜像里没有 jcmd？或者 /logs 不可写？");
            return ResponseEntity.internalServerError().body(err);
        }

        try {
            Files.writeString(outFile, all.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写线程栈文件失败", e);
            errors.add("写文件失败：" + e.getMessage());
        }

        String text = all.toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "采集完成");
        result.put("samples", n);
        result.put("intervalSeconds", interval);
        result.put("file", "logs/" + fileName);
        result.put("sizeBytes", text.getBytes(StandardCharsets.UTF_8).length);
        result.putAll(summarize(text));
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        result.put("hint", "在下面「日志与快照」里点开这个文件看完整栈；"
                + "重点比对多次采样中**栈一直不变**的线程");
        return ResponseEntity.ok(result);
    }

    /**
     * 调 jcmd 拿线程栈。
     * 先读干输出再 waitFor —— 反过来的话，输出量大时管道缓冲区写满，
     * jcmd 会阻塞在 write 上永远不退出，waitFor 就死等到超时。
     */
    private String runJcmdThreadPrint() throws IOException, InterruptedException {
        long pid = ProcessHandle.current().pid();
        Process p = new ProcessBuilder("jcmd", String.valueOf(pid), "Thread.print")
                .redirectErrorStream(true)
                .start();

        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("jcmd 30 秒未返回");
        }
        if (p.exitValue() != 0) {
            throw new IOException("jcmd 退出码 " + p.exitValue() + "：" + output.strip());
        }
        return output;
    }

    /**
     * 从 jcmd 文本里抽摘要 —— 界面上先看摘要，需要细节再点开文件。
     *
     * 注意：这里只统计**最后一次采样**的线程状态分布，
     * 多次采样混在一起统计会失真（同一条线程被数多次）。
     */
    private Map<String, Object> summarize(String text) {
        Map<String, Object> summary = new LinkedHashMap<>();

        // 死锁检测用 ThreadMXBean.findDeadlockedThreads()，不要去解析 jcmd 文本。
        //
        // 踩过的坑一：jcmd 写的是 "Found ONE Java-level deadlock"（英文单词），
        // 不是 "Found 1 ..."。按 `Found (\d+)` 写正则永远匹配不上，
        // deadlockCount 会一直报 0，看起来像"没有死锁"，非常误导。
        // MXBean 是 JVM 内部维护等待图后给出的权威答案，也不受输出格式变化影响。
        //
        // 踩过的坑二（语义）：字段名不能叫 deadlockCount。
        // jcmd 的 "Found one Java-level deadlock" 数的是**环的个数**，
        // 而 MXBean 返回的是**线程数组** —— 一个环通常涉及 2 个线程。
        // 实测：1 个环时 jcmd 说 "one"，MXBean 返回 2 个线程。
        // 叫 deadlockCount 会让人以为"有 2 个死锁"，所以改名成 deadlockedThreadCount。
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = tmx.findDeadlockedThreads();
        if (deadlocked == null || deadlocked.length == 0) {
            summary.put("deadlockedThreadCount", 0);
        } else {
            List<String> names = new ArrayList<>();
            for (ThreadInfo ti : tmx.getThreadInfo(deadlocked)) {
                if (ti != null) {
                    names.add(ti.getThreadName());
                }
            }
            summary.put("deadlockedThreadCount", deadlocked.length);
            summary.put("deadlockedThreads", names);
            // 说清语义，免得有人拿这个数去和 jcmd 的 "Found N" 对不上
            summary.put("deadlockNote",
                    "这是**线程数**，不是死锁环数。jcmd 的 \"Found one Java-level deadlock\" "
                            + "数的是环，一个环通常涉及 2 个线程。");
        }

        // 只取最后一次采样段落来统计状态和线程名
        int lastMark = text.lastIndexOf("次采样");
        String lastSection = lastMark > 0 ? text.substring(lastMark) : text;

        Map<String, Integer> states = new LinkedHashMap<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        int threadCount = 0;

        for (String line : lastSection.split("\n")) {
            Matcher head = THREAD_HEAD.matcher(line);
            if (head.find()) {
                threadCount++;
                names.merge(normalizeName(head.group(1)), 1, Integer::sum);
                continue;
            }
            Matcher st = THREAD_STATE.matcher(line);
            if (st.find()) {
                states.merge(st.group(1), 1, Integer::sum);
            }
        }

        summary.put("threadCount", threadCount);
        summary.put("stateDistribution", states);
        summary.put("topThreadNames", topN(names, 8));
        return summary;
    }

    /** 去掉名字末尾的序号，把 lab-cpu-spin-0 / -1 归成一类 */
    private String normalizeName(String name) {
        return name.replaceAll("-\\d+$", "");
    }

    private Map<String, Integer> topN(Map<String, Integer> src, int n) {
        return src.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue()),
                        LinkedHashMap::putAll);
    }
}
