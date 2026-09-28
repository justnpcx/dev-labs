package lab.jvm.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取容器的 cgroup 资源限制。
 *
 * 为什么在应用里读这个？因为这是**验证隔离是否生效的唯一可信来源**。
 * docker-compose.yml 里写了 mem_limit 不代表内核真的生效了 ——
 * 只有容器内读到的 cgroup 文件才作数。
 *
 * cgroup v1 和 v2 的文件路径完全不同，两个都要试：
 *   v2: /sys/fs/cgroup/memory.max          （单个文件，值是字节数或 "max"）
 *   v1: /sys/fs/cgroup/memory/memory.limit_in_bytes
 */
public final class ContainerLimits {

    private static final Logger log = LoggerFactory.getLogger(ContainerLimits.class);

    private static final long MB = 1024L * 1024L;

    private ContainerLimits() {
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cgroupVersion", detectVersion());

        map.put("memoryLimitMb", bytesToMb(readFirst(
                "/sys/fs/cgroup/memory.max",
                "/sys/fs/cgroup/memory/memory.limit_in_bytes")));
        map.put("memoryUsedMb", bytesToMb(readFirst(
                "/sys/fs/cgroup/memory.current",
                "/sys/fs/cgroup/memory/memory.usage_in_bytes")));
        map.put("memoryPeakMb", bytesToMb(readFirst(
                "/sys/fs/cgroup/memory.peak",
                "/sys/fs/cgroup/memory/memory.max_usage_in_bytes")));
        // 为 0 表示禁止使用 swap —— 这是"超限时进程被杀而不是整机卡死"的关键
        map.put("swapLimitMb", bytesToMb(readFirst(
                "/sys/fs/cgroup/memory.swap.max",
                "/sys/fs/cgroup/memory/memory.memsw.limit_in_bytes")));

        map.put("pidsLimit", readFirst("/sys/fs/cgroup/pids.max",
                "/sys/fs/cgroup/pids/pids.max"));
        map.put("pidsCurrent", readFirst("/sys/fs/cgroup/pids.current",
                "/sys/fs/cgroup/pids/pids.current"));

        map.put("cpuMax", readFirst("/sys/fs/cgroup/cpu.max", null));
        map.put("cpuQuotaUs", readFirst("/sys/fs/cgroup/cpu/cpu.cfs_quota_us", null));

        // OOM 事件计数：oomKill 一旦不为 0，就说明内存上限被真正触发过
        map.put("oomKillCount", readOomKill());
        return map;
    }

    private static String detectVersion() {
        if (Files.exists(Path.of("/sys/fs/cgroup/memory.max"))) {
            return "v2";
        }
        if (Files.exists(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"))) {
            return "v1";
        }
        return "unknown";
    }

    /** 按顺序返回第一个存在且可读的文件内容；都不存在返回 null。 */
    private static String readFirst(String... paths) {
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            try {
                Path p = Path.of(path);
                if (Files.isReadable(p)) {
                    return Files.readString(p).trim();
                }
            } catch (IOException e) {
                log.debug("读取 {} 失败: {}", path, e.getMessage());
            }
        }
        return null;
    }

    /** "max" 表示无上限，返回 -1；字节数换算成 MB。 */
    private static long bytesToMb(String raw) {
        if (raw == null) {
            return -1;
        }
        if ("max".equals(raw)) {
            return -1;
        }
        try {
            return Long.parseLong(raw) / MB;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 从 memory.events 里解析 oom_kill 计数。
     * 文件长这样：low 0 / high 0 / max 38 / oom 1 / oom_kill 1 / ...
     */
    private static long readOomKill() {
        String content = readFirst("/sys/fs/cgroup/memory.events",
                "/sys/fs/cgroup/memory/memory.oom_control");
        if (content == null) {
            return -1;
        }
        for (String line : content.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && "oom_kill".equals(parts[0])) {
                try {
                    return Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
