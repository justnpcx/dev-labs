# ---------- 构建阶段 ----------
FROM maven:3-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，把依赖层单独缓存 —— 改代码时不用重新下依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
# 必须用 jdk 镜像而不是 jre 镜像：
#   jcmd / jmap / jstack / jps / jstat 这些诊断工具都在 JDK 里，
#   JRE 镜像里没有 —— 而它们正是这个演练场存在的意义。
FROM eclipse-temurin:17-jdk

WORKDIR /app
COPY --from=build /build/target/jvm-lab.jar /app/jvm-lab.jar

# dump 和 GC 日志都往外挂，容器删了数据还在
RUN mkdir -p /dumps /logs
VOLUME ["/dumps", "/logs"]

EXPOSE 8080

# JAVA_OPTS 由 docker-compose 注入，方便改参数重启做对比实验
# --server.port 放在最后：命令行参数优先级高于环境变量和 application.yml，
# 这样即便外部注入了 SERVER__PORT 之类的变量也不会顶掉端口
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/jvm-lab.jar --server.port=8080"]
