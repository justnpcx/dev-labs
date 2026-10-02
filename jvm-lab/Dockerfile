# ---------- 构建阶段 ----------
FROM maven:3-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，把依赖层单独缓存 —— 改代码时不用重新下依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Arthas 阶段 ----------
# 把完整发行包在**构建期**下载好，而不是运行时下。
#
# 为什么不在运行时下（arthas-boot.jar 默认会自己下 17MB）：
#   1. 容器不一定有外网 —— 演练场里网络本来就该是可控的
#   2. 首次执行要等下载，交互体验很差
#   3. 版本漂移：今天跑得好好的，明天上游发新版可能就变了
#
# 用 jar xf 而不是 unzip：JDK 镜像里一定有 jar，但不一定有 unzip。
FROM eclipse-temurin:17-jdk AS arthas

ARG ARTHAS_VERSION=4.3.5
RUN set -eux; \
    curl -fsSL -o /tmp/arthas.zip \
      "https://github.com/alibaba/arthas/releases/download/arthas-all-${ARTHAS_VERSION}/arthas-bin.zip"; \
    mkdir -p /opt/arthas; \
    cd /opt/arthas; \
    jar xf /tmp/arthas.zip; \
    rm /tmp/arthas.zip; \
    # math-game.jar 是官方自带的演示程序，演练场里用不上，去掉省 2MB
    rm -f math-game.jar; \
    ls -la /opt/arthas

# ---------- 运行阶段 ----------
# 必须用 jdk 镜像而不是 jre 镜像：
#   jcmd / jmap / jstack / jps / jstat 这些诊断工具都在 JDK 里，
#   JRE 镜像里没有 —— 而它们正是这个演练场存在的意义。
FROM eclipse-temurin:17-jdk

WORKDIR /app
COPY --from=build /build/target/jvm-lab.jar /app/jvm-lab.jar
COPY --from=arthas /opt/arthas /opt/arthas

# dump 和 GC 日志都往外挂，容器删了数据还在
RUN mkdir -p /dumps /logs
VOLUME ["/dumps", "/logs"]

EXPOSE 8080

# JAVA_OPTS 由 docker-compose 注入，方便改参数重启做对比实验
# --server.port 放在最后：命令行参数优先级高于环境变量和 application.yml，
# 这样即便外部注入了 SERVER__PORT 之类的变量也不会顶掉端口
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/jvm-lab.jar --server.port=8080"]
