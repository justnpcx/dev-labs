package lab.jvm.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 安全地列出 / 读取容器内的日志与堆快照。
 *
 * 这个类存在的唯一理由是**路径穿越防护**。
 * 容器是只读根文件系统，但 /logs 和 /dumps 是挂载进来的可写卷 ——
 * 一旦有人能构造 path 参数读到 /etc/shadow 或 jar 里的配置，就麻烦了。
 * 三层防护：
 *   1. 拒绝含 ".." 的路径
 *   2. 规范化后必须落在白名单根目录下（startsWith 检查）
 *   3. 用 toRealPath() 解析符号链接后**再检查一次**，防止软链绕过
 */
public final class LabFiles {

    private static final Logger log = LoggerFactory.getLogger(LabFiles.class);

    /** 只允许访问这两个目录，别的一律拒绝 */
    private static final List<Path> ROOTS = List.of(Path.of("/logs"), Path.of("/dumps"));

    /** 单次最多返回多少行，防止有人用 lines=99999999 把内存打爆 */
    private static final int MAX_LINES = 5000;

    private LabFiles() {
    }

    /** 列出白名单目录下的所有常规文件。 */
    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong(LabFiles::lastModified).reversed())
                        .forEach(p -> result.add(describe(p)));
            } catch (IOException e) {
                log.warn("列出 {} 失败: {}", root, e.getMessage());
            }
        }
        return result;
    }

    private static Map<String, Object> describe(Path p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", relativize(p));
        map.put("name", p.getFileName().toString());
        map.put("dir", p.getParent().getFileName().toString());
        map.put("sizeBytes", size(p));
        map.put("modifiedAt", lastModified(p));
        map.put("text", isText(p.getFileName().toString()));
        return map;
    }

    /** 以 /logs 或 /dumps 为基准的相对路径，例如 logs/gc.log */
    private static String relativize(Path p) {
        return p.getParent().getFileName() + "/" + p.getFileName();
    }

    /**
     * 读取文本文件的末尾若干行。
     * 用 RandomAccessFile 从尾部倒着读，避免把几百 MB 的日志整个load进内存。
     */
    public static Map<String, Object> tail(String relPath, int lines) throws IOException {
        Path path = resolve(relPath);
        int want = Math.min(Math.max(lines, 1), MAX_LINES);

        List<String> tailLines = readLastLines(path, want);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", relPath);
        map.put("lines", tailLines.size());
        map.put("sizeBytes", size(path));
        map.put("modifiedAt", lastModified(path));
        map.put("content", String.join("\n", tailLines));
        return map;
    }

    /**
     * 从文件尾部倒着读 n 行。
     * 大文件（GC 日志滚起来可以到几十 MB）必须这么读 ——
     * Files.readAllLines 会把整个文件读进堆，本来就在排查内存问题，别火上浇油。
     */
    private static List<String> readLastLines(Path path, int n) throws IOException {
        List<String> out = new ArrayList<>(n);
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) {
                return out;
            }
            // 从尾部往前找 n 个换行符，估算起始位置
            long pos = fileLength - 1;
            int newlines = 0;
            long chunk = 64 * 1024;
            boolean foundEnough = false;

            while (pos >= 0 && !foundEnough) {
                long start = Math.max(0, pos - chunk);
                byte[] buf = new byte[(int) (pos - start)];
                raf.seek(start);
                raf.readFully(buf);
                for (int i = buf.length - 1; i >= 0; i--) {
                    if (buf[i] == '\n' && ++newlines > n) {
                        foundEnough = true;
                        start = start + i + 1;
                        break;
                    }
                }
                pos = start;
                if (pos == 0) {
                    break;
                }
            }

            long startPos = Math.max(0, pos);
            raf.seek(startPos);
            byte[] rest = new byte[(int) (fileLength - startPos)];
            raf.readFully(rest);
            String text = new String(rest, StandardCharsets.UTF_8);
            for (String line : text.split("\n", -1)) {
                out.add(line);
            }
        }
        // 去掉首尾可能残留的空行
        while (!out.isEmpty() && out.get(0).isEmpty()) {
            out.remove(0);
        }
        if (out.size() > n) {
            out = new ArrayList<>(out.subList(out.size() - n, out.size()));
        }
        return out;
    }

    /**
     * 把相对路径解析成真实文件路径，三层防护见类注释。
     *
     * @throws IllegalArgumentException 路径非法或不在白名单内
     */
    public static Path resolve(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        if (relPath.contains("..")) {
            throw new IllegalArgumentException("路径不允许包含 ..");
        }

        Path candidate = Path.of("/").resolve(relPath).normalize();

        // 第二层：规范化后必须落在白名单根目录下
        if (ROOTS.stream().noneMatch(candidate::startsWith)) {
            throw new IllegalArgumentException("只允许访问 /logs 与 /dumps 下的文件");
        }

        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("文件不存在或不是常规文件：" + relPath);
        }

        // 第三层：解析软链后再检查一次，防止有人放一个指向 /etc 的软链
        try {
            Path real = candidate.toRealPath();
            if (ROOTS.stream().noneMatch(real::startsWith)) {
                throw new IllegalArgumentException("符号链接指向了白名单之外的路径");
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("无法解析路径：" + relPath);
        }
    }

    /** 生成一个新的、带时间戳的堆快照文件名（绝对路径）。 */
    public static String newHeapDumpPath() {
        return "/dumps/heap-" + System.currentTimeMillis() + ".hprof";
    }

    /**
     * 清空一个文件。
     *
     * <h2>为什么不是无脑 delete</h2>
     *
     * 关键在于**这个文件是不是正被本进程持有**：
     *
     * <ul>
     *   <li>{@code logs/gc.log} —— 被 JVM 的 {@code -Xlog} 持有</li>
     *   <li>{@code logs/app.log} —— 被 logback 的 FileAppender 持有</li>
     * </ul>
     *
     * 直接 {@code delete} 的话，文件从目录里消失了，但**写入方的 fd 还指着那个
     * inode** —— 它会继续往里写，只是你再也看不见了。后果有两个：
     * 磁盘空间一点没释放，而日志面板会一直空着（直到重启才恢复）。
     * 这是"清空日志"这类功能最常见的坑。
     *
     * <p>所以：**被持有 → truncate（保留 inode）；没被持有 → delete**。
     * 堆快照（235MB）就属于后者，删掉才真的把空间还回去。
     *
     * <p>怎么判断"被持有"：扫 {@code /proc/self/fd} 里的符号链接。
     * 这是 Linux 上唯一可靠的判据，比维护一份"哪些文件是活跃的"白名单强 ——
     * 白名单会在你改了 logback 配置之后悄悄失效。
     */
    public static Map<String, Object> clear(String relPath) throws IOException {
        Path file = resolve(relPath);
        long before = Files.size(file);
        boolean held = isHeldOpen(file);

        String how;
        if (held) {
            // 只截断、不删除。实测：截断后 JVM / logback 会从偏移 0 重新写，
            // 不会留下 NUL 空洞（早期担心过稀疏文件，验证下来是干净的）
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(0);
            }
            how = "truncate";
        } else {
            Files.delete(file);
            how = "delete";
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", relPath);
        m.put("how", how);
        m.put("freedBytes", before);
        m.put("heldOpen", held);
        m.put("explain", held
                ? "文件正被本进程持有（JVM 的 -Xlog 或 logback），所以用「截断」而不是删除 —— "
                  + "删了的话写入方会继续往那个看不见的 inode 里写，空间不释放、面板永远空着"
                : "文件没有被任何进程持有，直接删除");
        return m;
    }

    /**
     * 清空整个目录（logs / dumps / all）。
     *
     * <p>逐个调用 {@link #clear}，所以每个文件各自走"持有就截断、否则删除"的逻辑。
     * 单个失败不影响其他文件，失败原因会收集到 errors 里返回。
     */
    public static Map<String, Object> purge(String dir) {
        List<Path> targets = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!"all".equals(dir) && !root.getFileName().toString().equals(dir)) {
                continue;
            }
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isRegularFile).forEach(targets::add);
            } catch (IOException e) {
                log.warn("列出 {} 失败: {}", root, e.getMessage());
            }
        }

        List<Map<String, Object>> cleared = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        long freed = 0;

        for (Path p : targets) {
            String rel = relativize(p);
            try {
                Map<String, Object> r = clear(rel);
                cleared.add(r);
                freed += ((Number) r.get("freedBytes")).longValue();
            } catch (IOException | IllegalArgumentException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("path", rel);
                err.put("error", e.getMessage());
                errors.add(err);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir);
        m.put("clearedCount", cleared.size());
        m.put("freedBytes", freed);
        m.put("freedMb", freed / 1024 / 1024);
        m.put("cleared", cleared);
        if (!errors.isEmpty()) {
            m.put("errors", errors);
        }
        return m;
    }

    /**
     * 这个文件现在是否被本进程打开着。
     *
     * <p>{@code /proc/self/fd} 下每个条目是一个指向被打开文件的符号链接，
     * 读它的目标和我们解析出来的真实路径比一下就知道。
     *
     * <p>注意要比较 {@code toRealPath()} 之后的路径 —— 容器里 /logs 是挂载点，
     * 未经解析的路径可能和 fd 指向的不是同一个字符串。
     */
    private static boolean isHeldOpen(Path file) {
        Path real;
        try {
            real = file.toRealPath();
        } catch (IOException e) {
            return false;
        }
        Path fdDir = Path.of("/proc/self/fd");
        if (!Files.isDirectory(fdDir)) {
            return false;   // 非 Linux 环境，退化成"当作没被持有"
        }
        try (Stream<Path> fds = Files.list(fdDir)) {
            return fds.anyMatch(fd -> {
                try {
                    return Files.readSymbolicLink(fd).equals(real);
                } catch (IOException e) {
                    return false;   // fd 可能在我们扫描的过程中被关掉了
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isText(String name) {
        String lower = name.toLowerCase();
        // 压缩过的轮转日志（app.log.2026-09-26.0.gz）名字里也含 ".log"，
        // 但它是二进制，必须排除掉
        if (lower.endsWith(".gz") || lower.endsWith(".zip")
                || lower.endsWith(".hprof") || lower.endsWith(".jar")) {
            return false;
        }
        // 注意 gc.log.1 / gc.log.2 这类轮转文件：后缀是数字而不是 .log，
        // 只判断 endsWith(".log") 会把它们误判成二进制
        return lower.contains(".log") || lower.endsWith(".txt") || lower.endsWith(".out");
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
