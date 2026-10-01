package lab.jvm.controller;

import com.sun.management.HotSpotDiagnosticMXBean;
import lab.jvm.support.LabFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志与堆快照的查看 / 下载 / 生成。
 *
 * 为什么需要它：演练场产生的产物（GC 日志、OOM 时的堆快照、应用日志里的 OOM 堆栈）
 * 原本只能进容器敲命令或去宿主找文件。这个控制器把它们搬到网页上。
 *
 * 安全：所有文件访问都走 LabFiles.resolve()，它做了三层路径穿越防护。
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    /** 列出 /logs 和 /dumps 下的所有文件。 */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", LabFiles.list());
        return result;
    }

    /**
     * 读取文本文件末尾若干行。
     *
     * @param path  相对路径，例如 logs/gc.log
     * @param lines 读多少行，默认 300，上限 5000
     */
    @GetMapping("/content")
    public ResponseEntity<Map<String, Object>> content(@RequestParam String path,
                                                       @RequestParam(defaultValue = "300") int lines) {
        try {
            return ResponseEntity.ok(LabFiles.tail(path, lines));
        } catch (IllegalArgumentException e) {
            // 路径非法 —— 返回 400 而不是 500，这是客户端的问题
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (IOException e) {
            log.warn("读取 {} 失败: {}", path, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "读取失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * 清空一个文件，或整个目录。
     *
     * <p>两种用法二选一：
     * <ul>
     *   <li>{@code POST /files/clear?path=logs/gc.log} —— 清一个</li>
     *   <li>{@code POST /files/clear?dir=logs|dumps|all} —— 清一类</li>
     * </ul>
     *
     * <p>具体是"截断"还是"删除"由 {@link LabFiles#clear} 判断 ——
     * 正在被 JVM / logback 持有的日志必须截断，删了会让写入方写进一个
     * 看不见的 inode，空间不释放、面板永远空着。
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(@RequestParam(required = false) String path,
                                                     @RequestParam(required = false) String dir) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (dir != null && !dir.isBlank()) {
                if (!List.of("logs", "dumps", "all").contains(dir)) {
                    result.put("error", "dir 只允许 logs / dumps / all");
                    return ResponseEntity.badRequest().body(result);
                }
                Map<String, Object> purged = LabFiles.purge(dir);
                log.info("清空 {}：{} 个文件，释放 {} MB",
                        dir, purged.get("clearedCount"), purged.get("freedMb"));
                return ResponseEntity.ok(purged);
            }
            if (path == null || path.isBlank()) {
                result.put("error", "要么给 path（清一个文件），要么给 dir（清一类）");
                return ResponseEntity.badRequest().body(result);
            }
            Map<String, Object> cleared = LabFiles.clear(path);
            log.info("清空 {}（{}），释放 {} 字节",
                    path, cleared.get("how"), cleared.get("freedBytes"));
            return ResponseEntity.ok(cleared);
        } catch (IllegalArgumentException e) {
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (IOException e) {
            log.warn("清空失败: {}", e.getMessage());
            result.put("error", "清空失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 主动生成一份堆快照。
     *
     * 底层是 HotSpotDiagnosticMXBean.dumpHeap，等价于 jcmd GC.heap_dump。
     * live=true 会**先触发一次 Full GC** 只保留存活对象 —— 文件更小更聚焦，
     * 但停顿更长。演练场里堆只有 256MB，两种都很快。
     */
    @PostMapping("/dump")
    public Map<String, Object> dumpHeap(@RequestParam(defaultValue = "true") boolean live) {
        String path = LabFiles.newHeapDumpPath();
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory
                    .getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            bean.dumpHeap(path, live);

            long cost = System.currentTimeMillis() - start;
            result.put("outcome", "堆快照已生成");
            result.put("path", path.replace("/dumps/", "dumps/"));
            result.put("liveOnly", live);
            result.put("costMs", cost);
            result.put("note", live
                    ? "live=true：dump 前做了一次 Full GC，只保留存活对象"
                    : "live=false：包含垃圾对象，文件更大");
            log.info("生成堆快照 {}（live={}, {}ms）", path, live, cost);
        } catch (IOException e) {
            result.put("outcome", "失败");
            result.put("error", e.getMessage());
            log.error("生成堆快照失败", e);
        }
        return result;
    }

    /** Cloudflare 免费版对响应体有 100MB 量级的限制，超过就容易 502。 */
    private static final long TUNNEL_SAFE_BYTES = 100L * 1024 * 1024;

    /**
     * 下载文件（主要是 .hprof 堆快照，拿去给 MAT 分析）。
     *
     * 两个踩过的坑：
     *
     * 坑一：文件不存在时**不能返回空 body 的 400**。
     *      实测经 Cloudflare 访问时，空 body 的 400 会被显示成 502 Bad Gateway，
     *      用户看到的是"Host Error"，完全不知道是文件没了。
     *      改成带 JSON 说明的 404。
     *
     * 坑二：堆快照有两个量级 ——
     *      OOM 自动落盘的 java_pid1.hprof 约 235MB（超过 Cloudflare 的限制）
     *      手动生成的 live 快照约 31MB（能顺利下载）
     *      所以这里对超大文件加一个响应头提示，前端会显示警告。
     */
    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam String path) {
        Path file;
        try {
            file = LabFiles.resolve(path);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "文件不存在或不可访问");
            err.put("detail", e.getMessage());
            err.put("path", path);
            err.put("hint", "文件可能已被 ./lab.sh clean 清理，或容器重启后失效。"
                    + "回仪表盘点「刷新列表」看现在有什么。");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(err);
        }

        long size = file.toFile().length();
        String name = file.getFileName().toString();
        MediaType type = name.endsWith(".hprof")
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.TEXT_PLAIN;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(size);
        // 用 ContentDisposition 构造，而不是 setContentDispositionFormData ——
        // 后者会生成 "form-data; name=\"attachment\"; filename=..."，
        // 浏览器不会当成附件下载，而且 form-data 语义也不对。
        ContentDisposition.Builder disposition = ContentDisposition.attachment();
        if (name.chars().allMatch(c -> c < 128)) {
            // 纯 ASCII（本项目所有文件名都是）→ 干净的 filename="xxx"
            disposition.filename(name);
        } else {
            // 含非 ASCII → 附带 RFC 5987 的 filename*=UTF-8''... ，
            // 否则非 ASCII 文件名会在头里变成乱码
            disposition.filename(name, StandardCharsets.UTF_8);
        }
        headers.setContentDisposition(disposition.build());

        if (size > TUNNEL_SAFE_BYTES) {
            // ⚠ 这个头的值必须是纯 ASCII。
            // Tomcat 对含非 ASCII 字符的响应头是**静默丢弃**的 ——
            // 不报错、不打日志、响应里就是没有。第一版写了中文，头直接消失了。
            headers.add("X-Lab-Warning",
                    "file is " + (size / 1024 / 1024)
                            + "MB, over the 100MB tunnel-safe limit; "
                            + "download via Cloudflare may fail with 502. "
                            + "Use an SSH tunnel, or generate a live dump (~31MB).");
        }

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(type)
                .body(new FileSystemResource(file));
    }
}
