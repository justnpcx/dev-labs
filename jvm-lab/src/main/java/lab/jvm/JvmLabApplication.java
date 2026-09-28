package lab.jvm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * JVM 演练场入口。
 *
 * 设计原则：
 *  1. 零外部依赖 —— 不连 MySQL / Redis，启动只需 1 秒，把噪音降到最低
 *  2. 单进程单 jar —— 随时可以改 -Xmx / GC 参数重启对比
 *  3. 每个接口对应一个可复现的 JVM 现象，并且都有复位入口
 */
@SpringBootApplication
public class JvmLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(JvmLabApplication.class, args);
    }
}
