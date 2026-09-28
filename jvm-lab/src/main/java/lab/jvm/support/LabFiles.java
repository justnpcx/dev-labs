package lab.jvm.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
