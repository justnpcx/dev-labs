#!/usr/bin/env bash
#
# JVM 演练场 —— 唯一入口脚本（容器优先）
#
# 设计前提：这个演练场会主动制造 OOM / 死锁 / CPU 打满，
# 所以默认**只在 Docker 容器里跑**，由 cgroup 硬限制兜底，绝不碰宿主。
#
#   ./lab.sh start [profile]   构建并启动容器（默认 default）
#   ./lab.sh stop              停止并移除容器
#   ./lab.sh restart [profile] 换 GC 参数做对比实验时最常用
#   ./lab.sh status            容器状态 + 实际生效的资源上限 + JVM 指标
#   ./lab.sh ui                仪表盘地址与访问方式（含 SSH 隧道命令）
#   ./lab.sh limits            只看容器被限制成了什么样
#   ./lab.sh log [n]           看容器日志尾部
#   ./lab.sh gc [n]            看 GC 日志尾部
#   ./lab.sh shell             进容器（jcmd/jmap/jstack 都在里面）
#   ./lab.sh dump-heap [标签]  主动落一份堆快照到 dumps/
#   ./lab.sh dump-thread [次数] [间隔秒]  连续采线程栈（默认 1 次）
#   ./lab.sh trigger <场景>    触发某个演练场景
#   ./lab.sh arthas [命令]     进 Arthas 控制台；带参数则跑一批命令后退出
#   ./lab.sh arthas-demo       打印 Arthas 三个场景（排错/性能/热更新）的完整步骤
#   ./lab.sh arthas-hotfix     一键跑完整热更新链路（jad→改→mc→retransform→验证）
#   ./lab.sh arthas-reset      重置 Arthas 服务端（改了启动参数后要用）
#   ./lab.sh diagnose          在容器内采集诊断信息到 logs/
#   ./lab.sh clean             清理历史 dump 与诊断文件（dump 很占地方）
#   ./lab.sh profiles          列出所有 JVM 参数预设
#   ./lab.sh help
#
# 逃生舱（不推荐，需二次确认）：
#   ./lab.sh host-start [profile]   绕过容器直接在宿主跑 —— 只在你明确知道
#                                   自己在干什么时用，它会占用宿主资源
#
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$PWD"
DUMPS="$ROOT/dumps"
LOGS="$ROOT/logs"
ENV_FILE="$ROOT/.env"
APP_LOG="$LOGS/app.log"
GC_LOG="$LOGS/gc.log"
CONTAINER="jvm-lab"
JAR="target/jvm-lab.jar"
PIDFILE="$LOGS/host-lab.pid"

LAB_PORT="${LAB_PORT:-8081}"
LAB_URL="${LAB_URL:-http://localhost:$LAB_PORT}"

mkdir -p "$DUMPS" "$LOGS"

# ---------------------------------------------------------------- 参数预设

profile_opts() {
  local profile="$1"
  local limits="-Xms256m -Xmx256m -XX:MaxDirectMemorySize=64m -XX:MaxMetaspaceSize=128m -Xss512k"
  local gc=""
  case "$profile" in
    default)  gc="" ;;
    g1)       gc="-XX:+UseG1GC -XX:MaxGCPauseMillis=50" ;;
    parallel) gc="-XX:+UseParallelGC" ;;
    serial)   gc="-XX:+UseSerialGC" ;;
    # JDK 17 的 ZGC 仍标记为实验特性，必须 UnlockExperimentalVMOptions
    zgc)      gc="-XX:+UnlockExperimentalVMOptions -XX:+UseZGC" ;;
    # 专为复现 GC overhead limit exceeded 调的：
    #   Serial GC —— 只有 Serial/Parallel/CMS 实现了这个检查，G1 没有
    #   -Xmn4m    —— 把 Young 区压到 4MB。这是关键：
    #                freed_ratio 是相对「堆容量」算的，Young 区默认占堆 1/3，
    #                一次 Young GC 能回收 30%+，永远达不到「回收 < 2%」的门槛。
    #                压到 4MB 后，每次最多回收 4/256 ≈ 1.6% < 2% ✓
    #   配合老年代被存活集占满 → 频繁 GC 且回收极少 → 命中判定
    gc-overhead) gc="-XX:+UseSerialGC -Xmn4m" ;;
    # 专为「本地内存泄漏」调的：NMT 从 summary 提到 detail（级别由 write_env 注入）。
    #   ★ 这个级别**只能在启动时指定**，事后改不了 —— 这正是本场景要教的点之一：
    #     summary 只按类别汇总（多半只看到 "Other" 涨），
    #     detail 才会给出调用栈，你才知道"是谁分配的"。
    #   代价：detail 本身有内存和性能开销（约 5~10%），生产上不要长期开着。
    nmt-detail) limits="-Xms256m -Xmx256m -XX:MaxDirectMemorySize=64m -XX:MaxMetaspaceSize=128m -Xss512k" ;;
    # 对照组：2G 堆。容器 mem_limit 是 2g，所以这个 profile 必须配合
    # 调大 mem_limit 才能用 —— 见下方 do_start 里的检查
    bigheap)  limits="-Xms2g -Xmx2g -Xss1m"; gc="-XX:+UseG1GC" ;;
    nolimit)  limits="-Xms256m -Xmx256m"; gc="" ;;
    *)
      echo "未知 profile：$profile" >&2
      echo "可选：default g1 parallel serial zgc gc-overhead nmt-detail bigheap nolimit" >&2
      exit 1
      ;;
  esac
  echo "$limits|$gc"
}

list_profiles() {
  cat <<'EOF'
profile    说明
--------   --------------------------------------------------------------
default    小堆 + 各类上限齐全，复现全部 OOM 场景（推荐日常用这个）
g1         default + 显式 G1，并设 MaxGCPauseMillis=50，用来对比暂停时间
parallel   default + Parallel GC，看吞吐优先的 GC 日志长什么样
serial     default + Serial GC，单线程 GC，最容易读懂日志
zgc        default + ZGC（JDK17 需 UnlockExperimentalVMOptions），看亚毫秒暂停
gc-overhead ★ 专为复现 GC overhead limit exceeded 调的：Serial GC + -Xmn4m
           小 Young 区是关键 —— 否则每次 GC 回收比例远超 2%，永远触发不了
nmt-detail ★ 专为「本地内存泄漏」调的：NMT 级别从 summary 提到 detail
           只有 detail 才给调用栈；而它**只能在启动时指定**，事后改不了
           代价是约 5~10% 的内存与性能开销，生产上别长期开
bigheap    2G 堆，对照组：同样的泄漏在大堆下能撑多久（需同时调大 mem_limit）
nolimit    只设 -Xmx，不设 Metaspace/直接内存上限，看 OOM 形态如何变化

提示：profile 只决定 JVM 参数。容器本身的资源上限（内存/进程数/CPU）
      写在 docker-compose.yml 里，两者是独立的 —— 正是这种"双层限制"
      保证了无论 JVM 参数怎么改，都越不出容器。
EOF
}

write_env() {
  local profile="$1"
  local pair limits gc nmt="summary"
  pair="$(profile_opts "$profile")"
  limits="${pair%%|*}"
  gc="${pair##*|}"

  # NMT 级别单独拎出来：它**只能在启动时指定**，而且 detail 有开销。
  # 不能写死在 LAB_COMMON 里 —— 那样 profile 再传 detail 也会被后面的 summary 覆盖
  # （HotSpot 同名参数后出现的生效），表现为"改了 profile 却没生效"。
  [[ "$profile" == "nmt-detail" ]] && nmt="detail"

  cat > "$ENV_FILE" <<EOF
# 由 ./lab.sh start <profile> 自动生成（profile=$profile），不要手改
LAB_JVM_LIMITS=$limits
LAB_GC=$gc
LAB_COMMON=-Duser.home=/tmp -Djava.io.tmpdir=/arthas-tmp -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps -XX:NativeMemoryTracking=$nmt -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M
EOF
}

# 为什么要有 -Duser.home=/tmp：
#   容器根文件系统是只读的，而 Java 的 user.home 取自 passwd 数据库（/root）。
#   Arthas 的 jad / mc / dump 这些命令会往 user.home/logs/arthas/ 写中间文件，
#   不改的话会报 "make sure you have write permission of the directory
#   /root/logs/arthas/classdump"。
#
#   ★ 注意这个参数必须加在**目标 JVM** 上，不能只加在 arthas-boot 客户端上 ——
#     Arthas 的核心是作为 agent 跑在目标 JVM 里的，classdump 路径在那里解析。
#     实测：只给客户端传 -Duser.home 完全没用。
#
#   应用的日志路径是绝对的（/logs/app.log），不受 user.home 影响。
#
# 为什么还要 -Djava.io.tmpdir=/arthas-tmp：
#   Arthas 的 profiler 会把 async-profiler 的 native 库解压到 java.io.tmpdir
#   再 mmap 执行。而 Docker 的 tmpfs 默认带 noexec（/tmp 就是），
#   直接报 "failed to map segment from shared object"。
#   compose 里单开了一个带 exec 的 /arthas-tmp 专门给它用。
#   ★ 这是生产加固容器里装 profiler 会踩到的同一个坑。

# ---------------------------------------------------------------- 生命周期

do_start() {
  local profile="${1:-default}"

  if [[ "$profile" == "bigheap" ]]; then
    echo "警告：bigheap 用 2G 堆，但容器 mem_limit 只有 1536m，容器会立刻被 OOM kill。"
    echo "      要用这个 profile，先把 docker-compose.yml 里的 mem_limit 调到 4g 再试。"
    exit 1
  fi

  write_env "$profile"
  echo "启动 profile=$profile（容器方式，资源受 cgroup 硬限制）"
  grep -E '^LAB_(JVM_LIMITS|GC)=' "$ENV_FILE" | sed 's/^/  /'

  docker compose up -d --build

  for _ in $(seq 1 60); do
    if curl -sf "$LAB_URL/status" >/dev/null 2>&1; then
      echo "启动成功 → $LAB_URL/status"
      echo
      echo "隔离边界校验："
      if ! verify_limits; then
        echo
        echo "⚠ 有防线未生效。修法：docker compose up -d --force-recreate"
        echo "  （compose down 有时不会真正删掉容器，会复用旧 cgroup 配置）"
      fi
      return 0
    fi
    if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" != "true" ]]; then
      echo "容器已退出，看日志："
      docker compose logs --tail=30
      exit 1
    fi
    sleep 0.5
  done
  echo "60 秒还没起来，看日志：./lab.sh log"
  exit 1
}

do_stop() {
  # 不要用 `2>/dev/null || true` 把 down 的错误吞掉。
  # down 失败意味着容器没被真正删除，下次 up 会**复用旧配置**，
  # 表现为"改了 mem_limit 却不生效"这类诡异问题（实测踩过一次：
  # 容器带着旧的 cgroup 配置活着，memory.swap.max 变成了 max，
  # 而 compose 配置和 docker inspect 都显示正常）。
  local down_out down_rc=0
  down_out="$(docker compose down 2>&1)" || down_rc=$?
  if (( down_rc != 0 )); then
    echo "  ⚠ docker compose down 失败（rc=$down_rc）："
    echo "$down_out" | sed 's/^/    /'
    echo "  → 容器可能没被删除，下次启动会复用旧配置。建议手动排查后 docker compose down"
  fi
  # 顺带清掉可能存在的宿主直跑残留
  if [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE"
  fi
  echo "已停止"
}

do_restart() {
  do_stop
  do_start "${1:-default}"
}

# 从容器内部读 cgroup 限制 —— 这是"限制到底生效没有"的唯一可信来源。
# 注意 cgroup v1 和 v2 的文件路径不同，两个都试。
container_limits() {
  if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" != "true" ]]; then
    echo "容器未运行"
    return 0
  fi
  docker exec "$CONTAINER" sh -c '
    if [ -f /sys/fs/cgroup/memory.max ]; then
      echo "内存上限(cgroup v2): $(cat /sys/fs/cgroup/memory.max)"
      echo "内存+swap上限:      $(cat /sys/fs/cgroup/memory.swap.max 2>/dev/null || echo n/a)"
      echo "进程数上限:         $(cat /sys/fs/cgroup/pids.max)"
      echo "CPU 配额:           $(cat /sys/fs/cgroup/cpu.max)"
    elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
      echo "内存上限(cgroup v1): $(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
      echo "进程数上限:         $(cat /sys/fs/cgroup/pids/pids.max 2>/dev/null || echo n/a)"
      echo "CPU 配额:           $(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us)"
    else
      echo "读不到 cgroup 限制文件"
    fi
  ' 2>/dev/null
}

# 从容器内读 cgroup 原始值。cgroup v1/v2 路径不同，两个都试。
read_cgroup() {
  docker exec "$CONTAINER" sh -c '
    if [ -f /sys/fs/cgroup/memory.max ]; then
      printf "ver=v2\n"
      printf "mem=%s\n"  "$(cat /sys/fs/cgroup/memory.max)"
      printf "swap=%s\n" "$(cat /sys/fs/cgroup/memory.swap.max 2>/dev/null || echo n/a)"
      printf "pids=%s\n" "$(cat /sys/fs/cgroup/pids.max 2>/dev/null || echo n/a)"
      printf "cpu=%s\n"  "$(cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo n/a)"
    else
      printf "ver=v1\n"
      printf "mem=%s\n"  "$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo n/a)"
      printf "swap=%s\n" "$(cat /sys/fs/cgroup/memory/memory.memsw.limit_in_bytes 2>/dev/null || echo n/a)"
      printf "pids=%s\n" "$(cat /sys/fs/cgroup/pids/pids.max 2>/dev/null || echo n/a)"
      printf "cpu=%s\n"  "$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null || echo n/a)"
    fi' 2>/dev/null
}

# 校验四道防线是否真的生效。
#
# 为什么需要这个函数：docker-compose.yml 里写了 mem_limit / memswap_limit，
# 不代表内核真的生效了 —— 实测遇到过 swap 限制丢失（memory.swap.max 变成 max，
# 意味着容器可以拿宿主的 swap），而 compose 配置和 docker inspect 都显示正常。
# 只看配置文件永远发现不了这种问题，必须读 cgroup 并**做判定**。
#
# 返回 0 = 全部通过，1 = 有项目不通过。
verify_limits() {
  local raw mem swap pids cpu ver
  raw="$(read_cgroup)"
  if [[ -z "$raw" ]]; then
    echo "  ✗ 读不到 cgroup（容器没跑？）"
    return 1
  fi

  mem=$(sed -n 's/^mem=//p'  <<<"$raw")
  swap=$(sed -n 's/^swap=//p' <<<"$raw")
  pids=$(sed -n 's/^pids=//p' <<<"$raw")
  cpu=$(sed -n 's/^cpu=//p'  <<<"$raw")
  ver=$(sed -n 's/^ver=//p'  <<<"$raw")

  local rc=0

  # 内存上限：必须是具体数字，不能是 max
  if [[ "$mem" =~ ^[0-9]+$ ]] && (( mem > 0 )); then
    printf "  ✓ 内存上限          %s MB\n" "$((mem / 1024 / 1024))"
  else
    printf "  ✗ 内存上限          未生效（值=%s）\n" "$mem"
    rc=1
  fi

  # swap：v2 下必须是 0；v1 下 memsw 必须等于 mem
  if [[ "$ver" == "v2" ]]; then
    if [[ "$swap" == "0" ]]; then
      printf "  ✓ swap 已禁用       memory.swap.max = 0\n"
    else
      printf "  ✗ swap 未禁用       memory.swap.max = %s（应为 0）\n" "$swap"
      printf "    → 危险：容器可以用宿主 swap，超限时会拖慢整机而不是干脆被杀\n"
      printf "    → 修：docker compose up -d --force-recreate\n"
      rc=1
    fi
  else
    if [[ "$swap" == "$mem" ]]; then
      printf "  ✓ swap 已禁用       memsw.limit == memory.limit\n"
    else
      printf "  ✗ swap 未禁用       memsw=%s vs mem=%s\n" "$swap" "$mem"
      rc=1
    fi
  fi

  # 进程数上限
  if [[ "$pids" =~ ^[0-9]+$ ]]; then
    printf "  ✓ 进程数上限        %s\n" "$pids"
  else
    printf "  ✗ 进程数上限        未生效（值=%s）\n" "$pids"
    rc=1
  fi

  # CPU 配额：v2 是 "quota period"，v1 是 quota（-1 表示不限）
  if [[ "$cpu" != "max" && "$cpu" != "-1" && -n "$cpu" && "$cpu" != "n/a" ]]; then
    printf "  ✓ CPU 配额          %s\n" "$cpu"
  else
    printf "  ✗ CPU 配额          未生效（值=%s）\n" "$cpu"
    rc=1
  fi

  return $rc
}

do_limits() {
  echo "=== 隔离边界校验（从容器内读 cgroup 并判定）==="
  if verify_limits; then
    echo
    echo "  四道防线全部生效。"
  else
    echo
    echo "  ⚠ 有防线未生效！演练前请先修好 —— 否则 OOM 可能波及宿主。"
    return 1
  fi
  echo
  echo "=== JVM 眼里的机器长什么样（GC 选择就看这几个数） ==="
  # 注意：JVM 读的是 cgroup 文件，不是 /proc/meminfo —— /proc/meminfo 没有命名空间，
  # 容器里看到的永远是宿主的总内存，别拿它判断容器限制。
  docker exec "$CONTAINER" jcmd 1 VM.info 2>/dev/null | grep -E '^\s*(CPUs|Memory):' | sed 's/^/  /'
  echo
  echo "=== 最终生效的 GC（可能和你以为的不一样） ==="
  docker exec "$CONTAINER" jcmd 1 VM.flags 2>/dev/null \
    | tr ' ' '\n' | grep -E '^\-XX:\+Use.*GC$' | sed 's/^/  /'
  echo "  （只有 UseSerialGC / UseParallelGC / UseG1GC / UseZGC 中的一个为真）"
  echo
  echo "=== 宿主真实负载（对比看，确认没被影响） ==="
  echo "  $(uptime | sed 's/^ *//')"
  free -g | awk 'NR==2{printf "  宿主内存: 总 %sG / 已用 %sG / 可用 %sG\n",$2,$3,$7}'
}

do_status() {
  if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" != "true" ]]; then
    echo "容器未运行。用 ./lab.sh start 启动"
    exit 1
  fi
  docker compose ps
  echo
  echo "--- 隔离边界校验 ---"
  verify_limits || true
  echo
  echo "--- JVM 指标 ---"
  curl -s "$LAB_URL/status" | pretty_json || echo "（接口不通，看 ./lab.sh log）"
  echo
  echo "--- 宿主负载（确认没被演练波及） ---"
  echo "  $(uptime | sed 's/^ *//')"
}

do_log() {
  docker compose logs --tail="${1:-60}" --no-log-prefix 2>/dev/null || echo "容器未运行"
}

do_gc() {
  if [[ -f "$GC_LOG" ]]; then
    tail -n "${1:-40}" "$GC_LOG"
  else
    echo "还没有 GC 日志（容器内的 /logs 映射到 ./logs）"
  fi
}

do_shell() {
  echo "进入容器。里面的诊断工具：jcmd / jmap / jstack / jstat / jps"
  echo "JVM 的 PID 是 1，例如：jcmd 1 GC.heap_info"
  echo
  docker exec -it "$CONTAINER" bash || docker exec -it "$CONTAINER" sh
}

# 仪表盘访问方式。端口刻意只绑 127.0.0.1（这台机器可能对外有公网 IP，
# 不能把一个"随便 OOM 的玩具"暴露出去），所以从本地浏览器要过隧道。
do_ui() {
  local ip
  ip=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)

  echo "仪表盘地址：$LAB_URL/"
  echo
  echo "── 方式一：SSH 隧道（推荐，零改动）────────────────────────"
  echo "  在你自己的电脑上执行："
  echo "      ssh -L $LAB_PORT:127.0.0.1:$LAB_PORT root@${ip:-<这台机器的IP>}"
  echo "  然后浏览器打开： http://localhost:$LAB_PORT/"
  echo
  echo "── 方式二：在这台机器上用命令行看（无浏览器时）──────────"
  echo "      curl -s $LAB_URL/status | python3 -m json.tool"
  echo "      ./lab.sh status"
  echo
  echo "── 方式三：Cloudflare Tunnel（服务端已就绪，后台配置需你操作）──"
  echo "  已把 jvm-lab 接入 ai-stack_ai-net，cloudflared 可直接访问 http://jvm-lab:8080。"
  echo "  隧道是远程管理型，ingress 规则只能在 Cloudflare 后台加："
  echo "      完整步骤见 docs/EXPOSE-VIA-TUNNEL.md"
  echo
  echo "  ⚠ 暴露前务必配 Cloudflare Access，否则任何人都能一键打崩服务、下载整个堆。"
  echo
  if curl -sf "$LAB_URL/status" >/dev/null 2>&1; then
    echo "容器状态：运行中 ✓"
  else
    echo "容器状态：接口不通 ✗（先 ./lab.sh start）"
  fi
}

# ---------------------------------------------------------------- dump 采集

# 主动落一份堆快照（不依赖 OOM）。两份 dump 对比是定位泄漏的标准做法。
do_dump_heap() {
  local tag="${1:-manual}"
  local file="/dumps/${tag}-$(date +%Y%m%d-%H%M%S).hprof"
  echo "正在 dump 堆（会 STW 停顿，堆越大越久）..."
  docker exec "$CONTAINER" jcmd 1 GC.heap_dump "$file"
  echo "已生成：dumps/$(basename "$file")"
  ls -lh "$DUMPS/$(basename "$file")" 2>/dev/null | sed 's/^/  /'
  echo
  echo "分析：见 docs/DUMP-ANALYSIS.md"
}

# 连续采 n 次线程栈。
# 单次 dump 只是快照，看不出「哪些线程是卡住不动的」——
# 必须连续采样对比，栈一直不变的那些才是问题所在。
do_dump_thread() {
  local times="${1:-1}"
  local interval="${2:-5}"
  local out="$LOGS/threaddump-$(date +%Y%m%d-%H%M%S).txt"
  for i in $(seq 1 "$times"); do
    {
      echo "=================== 第 $i/$times 次采样  $(date -Iseconds) ==================="
      docker exec "$CONTAINER" jcmd 1 Thread.print
    } >> "$out" 2>&1
    echo "第 $i/$times 次已采集"
    [[ "$i" -lt "$times" ]] && sleep "$interval"
  done
  echo
  echo "已写入：$out"
  echo
  echo "--- 死锁检测 ---"
  grep -A 25 'Found one Java-level deadlock' "$out" | head -30 || echo "  未发现死锁"
  echo
  echo "--- 线程状态分布 ---"
  grep -oE 'java\.lang\.Thread\.State: [A-Z_]+' "$out" | sort | uniq -c | sort -rn | sed 's/^/  /'
  echo
  echo "--- 线程名分组 Top 10（同名线程堆积往往就是问题） ---"
  # 只匹配真正的线程头（形如 "name" #12），否则会把死锁报告段里的线程名也算进来
  grep -oE '^"[^"]+" #[0-9]+' "$out" | sed 's/["#0-9]//g' | sed 's/ *$//' \
    | sed 's/-[0-9]*$//' | sort | uniq -c | sort -rn | head -10 | sed 's/^/  /'
}

# ---------------------------------------------------------------- 触发场景

pretty_json() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  elif command -v python3 >/dev/null 2>&1; then
    # ensure_ascii=False，否则中文全变成 \uXXXX 看不出人话
    python3 -c 'import sys,json; print(json.dumps(json.load(sys.stdin), indent=2, ensure_ascii=False))'
  else
    cat
  fi
}

do_trigger() {
  local scenario="${1:-help}"
  local url
  case "$scenario" in
    heap)        url="$LAB_URL/oom/heap?mb=8&count=64" ;;
    heap-slow)   url="$LAB_URL/oom/heap?mb=4&count=5" ;;
    metaspace)   url="$LAB_URL/oom/metaspace?count=200000" ;;
    direct)      url="$LAB_URL/oom/direct?mb=16&count=64" ;;
    threads)     url="$LAB_URL/oom/threads?count=400" ;;
    gc-overhead) url="$LAB_URL/oom/gc-overhead?livePercent=80&churnMillis=20000" ;;
    stack)       url="$LAB_URL/stack/overflow" ;;
    deadlock)    url="$LAB_URL/lock/deadlock" ;;
    contention)  url="$LAB_URL/lock/contention?threads=8&holdMillis=5000" ;;
    lock-bench)  url="$LAB_URL/lock/benchmark?threads=8&iterations=500000" ;;
    lock-stop)   url="$LAB_URL/lock/stop" ;;
    cpu)         url="$LAB_URL/cpu/spin?threads=2" ;;
    cpu-stop)    url="$LAB_URL/cpu/stop" ;;
    pool)        url="$LAB_URL/pool/unbounded?tasks=4000&payloadKb=64" ;;
    pool-reject) url="$LAB_URL/pool/bounded?policy=caller&tasks=60" ;;
    pool-stop)   url="$LAB_URL/pool/stop" ;;
    jit)         url="$LAB_URL/jit/warmup?rounds=20&iters=300000" ;;
    jit-deopt)   url="$LAB_URL/jit/deopt?rounds=10&iters=800000" ;;
    connpool)    url="$LAB_URL/connpool/burst?concurrency=20&queryMs=3000&acquireTimeoutMs=1000" ;;
    connpool-leak) url="$LAB_URL/connpool/burst?concurrency=3&queryMs=200&leak=3" ;;
    connpool-stats) url="$LAB_URL/connpool/stats" ;;
    connpool-reset) url="$LAB_URL/connpool/reset" ;;
    native-leak) url="$LAB_URL/native/leak?mb=1400&chunkMb=64" ;;
    native-soft) url="$LAB_URL/native/leak?mb=300&chunkMb=32" ;;
    native-stats) url="$LAB_URL/native/stats" ;;
    native-nmt)  url="$LAB_URL/native/nmt" ;;
    native-free) url="$LAB_URL/native/free" ;;
    leak)        url="$LAB_URL/leak/static?mb=2&count=5" ;;
    gc-summary)  url="$LAB_URL/gc/summary" ;;
    status)      url="$LAB_URL/status" ;;
    reset)       curl -s -X POST "$LAB_URL/reset" | pretty_json; echo; return 0 ;;
    help|*)
      cat <<EOF
用法：./lab.sh trigger <场景>

  内存类
    heap         打爆堆          → Java heap space（8MB × 64）
    heap-slow    慢速占堆        → 观察增长，不 OOM（4MB × 5）
    metaspace    动态生成类      → Metaspace
    direct       堆外直接内存    → Direct buffer memory
    gc-overhead  GC 空转         → GC overhead limit exceeded
                 ★ 必须先换 profile：./lab.sh restart gc-overhead
                   （Serial GC + -Xmn4m；G1 不实现这个检查，小 Young 区是触发关键）
                 可选参数 livePercent（默认 80）：存活集占堆的比例。
                 报 Java heap space 就调小，报"没 OOM"就调大，3~5 一档地试。
    threads      疯狂建线程      → unable to create new native thread

  并发 / CPU 类
    stack        无限递归        → StackOverflowError
    deadlock     交叉加锁        → jstack 报 Found one Java-level deadlock
    contention   锁竞争现场      → jstack 看 BLOCKED + waiting to lock
                 8 线程抢 1 把锁，各持有 5 秒；吞吐恒等于 1/5 秒，与线程数无关
    lock-bench   锁方式对比      → 全局锁 / 分段锁 / AtomicLong 耗时对比
    lock-stop    停掉竞争线程
    cpu          打满 CPU        → 配合 top -H + jstack 定位
    cpu-stop     停掉空转线程

  线程池类（生产事故频率最高）
    pool         无界队列堆积    → Java heap space，但根因是队列无界
                 4000 个任务 × 64KB 请求上下文 ≈ 256MB 堆被队列吃光
                 ★ 这就是《阿里规约》禁用 Executors.newFixedThreadPool 的原因
                 ⚠ 响应体可能拿不到（堆满时序列化会再 OOM）——
                   看日志：docker logs jvm-lab | grep -A 5 OutOfMemoryError
    pool-reject  拒绝策略对比    → abort / caller / discard / oldest
                 改 policy= 参数跑一遍，对比 rejected 与 submitCostMillis
                 caller 是背压（提交线程自己跑），discard 是静默丢任务（最危险）
    pool-stop    关掉演练线程池

  编译器类
    jit          JIT 预热        → 同一段代码前几轮慢、之后陡降几十倍
                 返回每轮单独耗时，看哪一轮开始掉 → 那就是 C2 编译完成的点
                 加 -XX:+PrintCompilation 可在日志里对到具体时刻
    jit-deopt    JIT 去优化      → 调用点从单态变多态 → made not entrant
                 ⚠ 只在每个 JVM 生命周期内发生一次（同一个调用点）。
                   想看它先 ./lab.sh restart，第二次跑就是 1.00x
                 实测 5 次冷启动：去优化那一轮 spike 2.8x ~ 14x（波动大），
                   重新编译后稳态基本回到原速 —— 是"抖一下"不是"一直慢"

  连接池类（和线程池现象相反：CPU 闲但超时）
    connpool     池耗尽          → 池 5 / 并发 20 / 慢查询 3s → 8 个请求超时
                 ★ 关键：CPU 是闲的，瓶颈在"等待"不在"计算"，
                   监控上表现为「CPU 低 + 超时多」，容易被误判成网络问题
    connpool-leak 连接泄漏       → 借了不还（release 没写在 finally 里）
                 ★ 比池太小危险得多：它是**单调恶化**的，池会一路降到 0，
                   所有请求永久超时，只能重启恢复
    connpool-stats 看池状态      → available / inUse / leakedTotal
    connpool-reset 重建池        → 相当于重启应用（泄漏的连接找不回来）

  本地内存类（唯一一个「日志直接断掉」的场景）
    native-soft  温和泄漏        → 300MB。不打死容器，用来练观测手法
                 ★ 关键动作：对比 heap / direct / rss 三个数字 ——
                   前两个是 JVM 记账的，第三个是进程实际占的，
                   对不上的部分就是本地内存
    native-leak  打死容器        → 1400MB，灌到 cgroup 上限 → SIGKILL
                 ⚠ 容器不会自动重启，演练完要 ./lab.sh start
                 ★ 和 Java OOM 的区别：Java OOM 抛异常打堆栈，
                   这个日志**戛然而止**，OutOfMemoryError 计数是 0
                   事后证据只在容器外：docker inspect 的 OOMKilled、
                   宿主 dmesg 的 "Memory cgroup out of memory"
    native-stats 看快照          → heap / direct / 泄漏量 / RSS / cgroup
    native-nmt   跑 NMT          → jcmd VM.native_memory summary 原始输出
                 ★ summary 只给类别（Other 涨了），要调用栈得
                   ./lab.sh restart nmt-detail —— 级别只能在启动时定
    native-free  归还            → 模拟"修好代码后重启"。
                   真实泄漏没这个按钮：指针早丢了

  泄漏类
    leak         渐进式缓存泄漏  → 配合两次 jmap -histo 对比

  解读类（读证据、做决策 —— 其他场景都是制造问题）
    gc-summary   解读 GC 日志    → 停顿统计 / 分布 / 分配速率 / 规则诊断
                 ★ 先跑几个场景制造 GC：./lab.sh trigger leak 或 heap-slow
                 ★ 想对比不同 GC：./lab.sh restart g1 | parallel | serial | zgc
                   （ZGC 的日志格式完全不同，解析器单独适配过：
                     一条周期 = 3 个阶段停顿，会另外给出 cycleCount）
                   换 profile 再跑同一份负载，然后回到这个命令对比

  工具
    status       看 JVM 全景
    reset        清空所有泄漏 + 复位（同时关线程池、停锁竞争线程）
EOF
      return 0
      ;;
  esac

  echo "GET $url"
  curl -s --max-time 300 "$url" | pretty_json
  echo
}

# ---------------------------------------------------------------- 诊断采集

do_diagnose() {
  if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" != "true" ]]; then
    echo "容器未运行，先 ./lab.sh start"
    exit 1
  fi

  local out="$LOGS/diagnose-$(date +%Y%m%d-%H%M%S).txt"
  {
    echo "=================== 诊断快照 $(date -Iseconds) ==================="
    echo
    echo "### 0. 容器资源上限（cgroup）"
    container_limits
    echo
    # 容器里 java 是 PID 1（Dockerfile 里用了 exec），所以下面都针对 PID 1
    docker exec "$CONTAINER" sh -c '
      set +e
      echo "### 1. Java 进程（jps -l）";                jps -l
      echo; echo "### 2. 启动参数（jcmd VM.command_line）"; jcmd 1 VM.command_line
      echo; echo "### 3. 可写 VM 标志（jcmd VM.flags）";   jcmd 1 VM.flags
      echo; echo "### 4. 堆与 GC 概览（jcmd GC.heap_info）"; jcmd 1 GC.heap_info
      echo; echo "### 5. 分代统计（jstat -gcutil）"
      echo "    S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT"
      jstat -gcutil 1
      echo; echo "### 6. 对象直方图 Top 30（jmap -histo）"
      echo "    关注 [B（byte数组）和 lab.jvm.support.* 的实例数"
      jmap -histo 1 | head -30
      echo; echo "### 7. 线程栈（jcmd Thread.print）"
      echo "    搜 deadlock / lab-cpu-spin / lab-deadlock 看目标线程"
      jcmd 1 Thread.print
      echo; echo "### 8. 原生内存分布（jcmd VM.native_memory summary）"
      echo "    这一项会告诉你：堆只占进程内存的一部分"
      jcmd 1 VM.native_memory summary
    '
  } > "$out" 2>&1

  echo "已写入：$out"
  echo
  echo "--- 快速摘要 ---"
  grep -E '^###|Found one Java-level deadlock' "$out" || true
  echo
  docker exec "$CONTAINER" jstat -gcutil 1 2>/dev/null || true
}

# ---------------------------------------------------------------- 清理

# 一次 heap OOM 就会落一个和 -Xmx 差不多大的 hprof（256MB 堆 → 约 240MB 文件），
# 演练几次就能把宿主磁盘吃掉好几个 G。所以单独给一个清理入口。
do_clean() {
  local dumps_count dumps_size logs_size
  dumps_count=$(find "$DUMPS" -name '*.hprof' 2>/dev/null | wc -l)
  dumps_size=$(du -sh "$DUMPS" 2>/dev/null | cut -f1)
  logs_size=$(du -sh "$LOGS" 2>/dev/null | cut -f1)
  echo "dumps/ : $dumps_count 个 hprof，共 ${dumps_size:-0}"
  echo "logs/  : ${logs_size:-0}"
  read -r -p "确认删除 dumps/*.hprof 与 logs/ 下的历史诊断文件？[y/N] " ans
  [[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "已取消"; return 0; }

  find "$DUMPS" -name '*.hprof' -delete
  find "$LOGS" -name 'diagnose-*.txt' -delete
  if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" != "true" ]]; then
    : > "$APP_LOG" 2>/dev/null || true
    : > "$GC_LOG" 2>/dev/null || true
  fi
  echo "已清理"
}

# ---------------------------------------------------------------- 逃生舱

# 绕过容器直接在宿主跑。明确需要时才用 —— 它占用的是宿主资源，
# 而且 /oom/threads 撞的会是宿主的 ulimit -u。
do_host_start() {
  local profile="${1:-default}"
  cat <<'WARN'
================================ 警告 ================================
你正在选择「宿主直跑」模式。这个模式：
  * 不受 cgroup 限制，直接消耗宿主内存 / CPU / 进程数
  * /cpu/spin 会和同机的 AI 栈抢 CPU
  * /oom/threads 撞的是宿主的 ulimit -u，可能影响其他服务
除非你非常清楚自己在做什么，否则请改用容器模式：./lab.sh start
=====================================================================
WARN
  read -r -p "仍要继续？输入 yes 确认：" ans
  [[ "$ans" == "yes" ]] || { echo "已取消"; return 0; }

  local pair limits gc
  pair="$(profile_opts "$profile")"
  limits="${pair%%|*}"
  gc="${pair##*|}"

  [[ -f "$JAR" ]] || { echo "没找到 $JAR，先 mvn -B clean package -DskipTests"; exit 1; }

  # shellcheck disable=SC2086
  nohup java $limits $gc \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$DUMPS" \
    -XX:NativeMemoryTracking=summary \
    -Xlog:gc*:file="$GC_LOG":time,uptime,level,tags:filecount=5,filesize=10M \
    -jar "$JAR" --server.port="$LAB_PORT" > "$APP_LOG" 2>&1 &
  echo $! > "$PIDFILE"
  echo "宿主直跑已启动 pid=$(cat "$PIDFILE") → $LAB_URL/status"
  echo "停止：./lab.sh stop"
}

# ---------------------------------------------------------------- 入口

# ---------------------------------------------------------------- Arthas

# Arthas 已打进镜像（/opt/arthas），不需要运行时下载。
# 容器根文件系统是只读的，但 Arthas 要往 $HOME 写日志和会话文件 ——
# 所以把 HOME 指到 tmpfs 下的 /tmp/arthas-home。
ARTHAS_HOME_IN_CONTAINER=/tmp/arthas-home
# ★ 必须显式传 -Duser.home，不能只靠 $HOME 环境变量：
#   Java 的 user.home 取自 passwd 数据库（这里是 /root），
#   而容器根文件系统只读 —— 表现为 jad 报
#   "fail to dump class file ... make sure you have write permission of
#    /root/logs/arthas/classdump"。只设 HOME= 是不管用的。
ARTHAS_JAVA_OPTS="-Duser.home=$ARTHAS_HOME_IN_CONTAINER"

container_running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" == "true" ]]
}

# 用法：
#   ./lab.sh arthas                      进入交互式控制台
#   ./lab.sh arthas "trace ...; quit"    跑一批命令后退出
do_arthas() {
  if ! container_running; then
    echo "容器没在跑。先 ./lab.sh start"
    return 1
  fi

  if [[ $# -eq 0 ]]; then
    echo "进入 Arthas 控制台（目标：容器内 PID 1 的 JVM）"
    echo "  quit  退出控制台（Arthas 服务端继续驻留，下次连得更快）"
    echo "  stop  连服务端一起关掉"
    echo
    docker exec -it "$CONTAINER" sh -c \
      "mkdir -p $ARTHAS_HOME_IN_CONTAINER; cd /opt/arthas; HOME=$ARTHAS_HOME_IN_CONTAINER exec java $ARTHAS_JAVA_OPTS -jar arthas-boot.jar --arthas-home /opt/arthas 1"
    return
  fi

  # 批量模式：把命令**通过 stdin 管道**喂给 arthas 客户端。
  #
  # ★ 这里有三个坑，都是实测踩出来的：
  #
  #   1. 分号不是分隔符。批处理是**一行一条命令** ——
  #      写成一行 "version; thread -n 2" 会被当成一个命令名，
  #      报 `version;: command not found`。所以要先把 ; 换成换行。
  #
  #   2. 非 TTY 下 arthas 客户端跑完命令**不会自己退出**，会一直等 stdin。
  #      用 -f <文件> 或 -c "<命令>" 都一样 —— 实测每次都挂到超时，
  #      还留下一堆僵尸 JVM 进程（攒了 12 个才发现）。
  #      用管道就没这个问题：stdin 到 EOF，客户端正常退出（实测 0.5 秒）。
  #
  #   3. 还要**逐条喂、留间隔**。一次性把命令全灌进去会丢输出 ——
  #      实测 3 次里有 1 次 `version` 的结果没打出来：客户端把 stdin 读完后
  #      就直接退出了，没等命令执行结果刷出来。每条之间留 1 秒就稳了。
  #
  #   4. 加 timeout 兜底。attach 偶尔会慢（目标 JVM 正忙的时候）。
  local batch
  batch="$(mktemp)"
  printf '%s\n' "$*" | tr ';' '\n' | sed '/^[[:space:]]*$/d' > "$batch"
  # 没给退出命令的话补一个，否则会停在提示符等输入
  if ! grep -qE '^[[:space:]]*(quit|stop)[[:space:]]*$' "$batch"; then
    echo "quit" >> "$batch"
  fi

  # 逐条喂命令的子 shell。放成函数是因为管道里没法直接写循环。
  #
  # ★ ARTHAS_WAIT：给 trace / watch / stack / monitor 这类**长驻命令**留观察窗口。
  #   它们的语义是"等目标方法被调用"，命令本身不返回 ——
  #   如果紧接着就发 quit，等于什么都没看到。
  #   用法：ARTHAS_WAIT=20 ./lab.sh arthas "trace xxx order"
  #
  # ★ 观察窗口结束后必须先发一个单独的 `q` 再发 quit。
  #   这类长驻命令会**拦截单键输入**（q 用来中止自己），
  #   直接把 "quit" 发过去的话，开头的 q 会被它吃掉，
  #   剩下 "uit" 变成一个不存在的命令，客户端就永远退不出去
  #   （实测卡到 timeout，报 `uit: command not found`）。
  arthas_feed() {
    local lines=() cmd
    while IFS= read -r cmd; do lines+=("$cmd"); done < "$batch"
    local n=${#lines[@]} i
    for ((i = 0; i < n; i++)); do
      cmd="${lines[i]}"
      if (( i == n - 1 )) && [[ "$cmd" =~ ^[[:space:]]*(quit|stop)[[:space:]]*$ ]]; then
        if [[ "${ARTHAS_WAIT:-0}" != "0" ]]; then
          sleep "$ARTHAS_WAIT"
          printf 'q\n'          # 中止长驻命令，把单键输入模式收回来
          sleep 1
        fi
      fi
      printf '%s\n' "$cmd"
      sleep "${ARTHAS_CMD_GAP:-1}"
    done
  }

  # 输出不是终端时（管道 / 重定向）把 ANSI 转义码剥掉。
  # 实测 NO_COLOR=1 和 TERM=dumb 都关不掉 Arthas 的颜色 ——
  # 不剥的话 `./lab.sh arthas "sc -d X" | grep classLoaderHash` 会匹配不上，
  # 因为实际内容是 "\033[1mclassLoaderHash\033[0m   28a418fc"。
  # 这也是终端里看着正常、一进脚本就出错的那类坑。
  local rc
  if [[ -t 1 ]]; then
    timeout "${ARTHAS_TIMEOUT:-120}" docker exec -i "$CONTAINER" sh -c \
      "mkdir -p $ARTHAS_HOME_IN_CONTAINER; cd /opt/arthas; HOME=$ARTHAS_HOME_IN_CONTAINER exec java $ARTHAS_JAVA_OPTS -jar arthas-boot.jar --arthas-home /opt/arthas 1" < <(arthas_feed)
    rc=$?
  else
    timeout "${ARTHAS_TIMEOUT:-120}" docker exec -i "$CONTAINER" sh -c \
      "mkdir -p $ARTHAS_HOME_IN_CONTAINER; cd /opt/arthas; HOME=$ARTHAS_HOME_IN_CONTAINER exec java $ARTHAS_JAVA_OPTS -jar arthas-boot.jar --arthas-home /opt/arthas 1" < <(arthas_feed) \
      | sed 's/\x1b\[[0-9;]*[a-zA-Z]//g'
    rc=${PIPESTATUS[0]}
  fi
  rm -f "$batch"
  if (( rc == 124 )); then
    echo
    echo "⚠ 超时（${ARTHAS_TIMEOUT:-120}s）。目标 JVM 可能正忙，或 Arthas 服务端状态异常。"
    echo "  重置：./lab.sh arthas-reset"
  fi
  return $rc
}

# Arthas 服务端是**驻留在目标 JVM 里**的，启动参数（比如 user.home）在第一次
# 启动时就定下来了。如果第一次是用旧参数启动的，后面客户端传什么都改不了 ——
# 表现为 jad 一直报 /root/logs/arthas 没有写权限。
# 这个命令用来把服务端整个换掉。
do_arthas_reset() {
  if ! container_running; then
    echo "容器没在跑。"
    return 1
  fi
  docker exec "$CONTAINER" sh -c 'pkill -f arthas-boot 2>/dev/null; true'
  printf 'stop\n' | timeout 60 docker exec -i "$CONTAINER" sh -c \
    "cd /opt/arthas; HOME=$ARTHAS_HOME_IN_CONTAINER exec java $ARTHAS_JAVA_OPTS -jar arthas-boot.jar --arthas-home /opt/arthas 1" >/dev/null 2>&1
  echo "Arthas 服务端已重置（下次调用会用新的启动参数重新 attach）"
}

# 把三个场景的完整命令序列打出来。照着贴就行 —— 不用记。
do_arthas_demo() {
  cat <<'EOF'
Arthas 演练场 —— 三个场景，靶子是 /demo/* 那几个接口。

Arthas 已经打进镜像（/opt/arthas），不需要下载。先启动：

    ./lab.sh start

────────────────────────────────────────────────────────────
场景一 · 排错定位：日志不够用，又不能重启加日志
────────────────────────────────────────────────────────────

先自己打一下这个接口，看总耗时：

    curl -s 'localhost:8081/demo/order?id=1' | python3 -m json.tool

300 多毫秒。但**慢在哪一层**？日志里没有。

    ARTHAS_WAIT=20 ./lab.sh arthas "trace lab.jvm.controller.ArthasDemoController order"

然后在另一个终端反复打接口：

    for i in 1 2 3 4 5; do curl -s -o /dev/null "localhost:8081/demo/order?id=$i"; sleep 3; done

输出会逐层列出每个子调用的耗时和占比。实测 quotePrice 一层吃掉 99.9%。

★ 为什么要有 ARTHAS_WAIT：trace 的语义是"等目标方法被调用"，命令本身
  不返回。不给观察窗口的话脚本会立刻发 quit，什么都看不到。
★ 为什么 trace 要配合打接口：它只对**命令生效之后**发生的调用生效。

再看方法的入参出参（同样不用加日志）：

    ARTHAS_WAIT=20 ./lab.sh arthas "watch lab.jvm.controller.ArthasDemoController applyDiscount '{params, returnObj}' -x 2"

────────────────────────────────────────────────────────────
场景二 · 性能瓶颈：知道慢，不知道是谁在烧 CPU
────────────────────────────────────────────────────────────

先制造 CPU 热点：

    for i in 1 2 3; do curl -s -o /dev/null 'localhost:8081/demo/format-loop?times=30000'; done

抓火焰图（start 和 stop 是两条独立命令，profiler 在目标 JVM 里后台跑）：

    ./lab.sh arthas "profiler start"
    curl -s -o /dev/null 'localhost:8081/demo/format-loop?times=30000'
    ./lab.sh arthas "profiler stop --format html --file /tmp/flame.html"

然后把火焰图拷出来看：

    docker exec -i jvm-lab sh -c 'cat /tmp/flame.html' > flame.html

★ 火焰图上 SimpleDateFormat.<init> 会非常显眼 —— 那是"每次调用都 new"
  的代价。这个错误在代码 review 时基本看不出来，在火焰图上藏不住。

也可以直接看最忙的线程：

    ./lab.sh arthas "thread -n 3"

────────────────────────────────────────────────────────────
场景三 · 紧急热更新：改一个字符，但发版要等几小时
────────────────────────────────────────────────────────────

一键跑完整链路（jad → 改 → mc → retransform → 验证）：

    ./lab.sh arthas-hotfix

它会打印每一步，最后对比改前改后的返回值：90 → 80，**进程没有重启**。

想手工走一遍的话，步骤是：

 ① jad 反编译         ./lab.sh arthas "jad --source-only lab.jvm.controller.ArthasDemoController"
 ② 改一个字符         applyDiscount 里 '* 9L / 10L' → '* 8L / 10L'
 ③ 取 classLoaderHash ./lab.sh arthas "sc -d lab.jvm.controller.ArthasDemoController"
 ④ 送进容器           docker exec -i jvm-lab sh -c 'cat > /tmp/ArthasDemoController.java' < 改好的.java
 ⑤ 编译               ./lab.sh arthas "mc -c <hash> /tmp/ArthasDemoController.java -d /tmp"
 ⑥ 生效               ./lab.sh arthas "retransform /tmp/lab/jvm/controller/ArthasDemoController.class"
 ⑦ 验证               curl -s 'localhost:8081/demo/price?amount=100&vip=true'

手工做的话有三个坑等着你（arthas-hotfix 都替你处理了）：

  ① 文件名必须和 public 类同名。存成 Demo.java 会报
     "class ArthasDemoController is public, should be declared in a file
      named ArthasDemoController.java"
  ② mc 编译时**不带 -parameters**，字节码里没有参数名信息。
     所以所有 @RequestParam 都写了显式的 name= ——
     不写的话热更新之后 Spring 解析不了参数，接口直接 500：
     "Name for argument of type [int] not specified, and parameter name
      information not available via reflection"
     ★ 更阴险的是它不一定立刻暴露：Spring 缓存了参数元数据，
       "热更新前调用过"的接口照常工作，只有"热更新后才第一次调用"的才会炸。
  ③ 命令行输出带 ANSI 转义码，`| grep classLoaderHash` 匹配不上。
     lab.sh 在输出不是终端时会自动剥掉。

★ 热更新只在内存里，不落盘 —— ./lab.sh restart 就恢复原样。
★ 生产上用这个要非常克制：绕过发版流程 = 绕过代码评审和回滚机制。

────────────────────────────────────────────────────────────
其他常用命令
────────────────────────────────────────────────────────────

    dashboard                实时面板（线程/内存/GC），类似 top
    thread -b                只找**死锁**的线程  ← 配合 ./lab.sh trigger deadlock
    jvm                      当前 JVM 的详细信息
    heapdump /tmp/a.hprof    堆快照（和 jmap 等价）
    ognl '@java.lang.System@getProperty("java.version")'   执行任意表达式
    sc -d <类全名>            看类是从哪个 jar 加载的
    getstatic                看静态字段的值
    quit                     退出控制台（Arthas 服务端继续驻留，下次连得更快）
    stop                     连 Arthas 服务端一起关掉

    不想敲长命令？直接 ./lab.sh arthas 进交互式控制台。

出问题时的自救：

    ./lab.sh arthas-reset    重置 Arthas 服务端。
      Arthas 的核心是作为 agent **驻留在目标 JVM 里**的，启动参数在第一次
      启动时就定下来了。如果它是在改配置之前启动的，后面客户端传什么都
      改不了（比如 jad 一直报 /root/logs/arthas 没写权限）。

EOF
}

# 热更新一键演示：jad → 改 → mc → retransform → 验证，全程不重启。
#
# 为什么要做成命令：这条链路有 7 个步骤、3 个隐藏的坑
# （文件名必须和 public 类同名、mc 要 classLoaderHash、ANSI 转义），
# 手工敲一遍很容易卡住。命令跑通之后，再回头看 arthas-demo 里的分步说明，
# 每一步在干什么就清楚了。
do_arthas_hotfix() {
  if ! container_running; then
    echo "容器没在跑。先 ./lab.sh start"
    return 1
  fi

  local cls=lab.jvm.controller.ArthasDemoController
  local work
  work="$(mktemp -d)"
  # ★ 文件名必须和 public 类同名，否则 mc 报
  #   "class X is public, should be declared in a file named X.java"
  local src="$work/ArthasDemoController.java"

  echo "① 改之前："
  curl -s --max-time 20 "$LAB_URL/demo/price?amount=100&vip=true" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print("   payable =",d["payable"],"  (",d["discountRule"],")")' 2>/dev/null \
    || { echo "   拿不到响应，先 ./lab.sh start"; rm -rf "$work"; return 1; }

  echo "② jad 反编译出当前源码"
  # 掐掉 Arthas 的控制台噪声：从 "jad --source-only" 那行之后，到下一个提示符之前
  ./lab.sh arthas "jad --source-only $cls" 2>&1 \
    | awk '/jad --source-only/{f=1;next} /^\[arthas@/{f=0} f' \
    | sed '/^[[:space:]]*$/d' > "$src"
  if ! grep -q 'applyDiscount' "$src"; then
    echo "   反编译失败，内容不对。先 ./lab.sh arthas-reset 再试"
    rm -rf "$work"; return 1
  fi
  echo "   拿到 $(wc -l < "$src") 行源码"

  echo "③ 改一个字符：VIP 9 折 → 8 折"
  if ! grep -q 'amount \* 9L / 10L' "$src"; then
    echo "   没找到目标行（可能已经被热更新过了）。./lab.sh restart 恢复原状再试"
    rm -rf "$work"; return 1
  fi
  sed -i 's/amount \* 9L \/ 10L/amount * 8L \/ 10L/' "$src"

  echo "④ 取 ClassLoader hash"
  local hash
  hash="$(./lab.sh arthas "sc -d $cls" 2>&1 \
          | grep -oE 'classLoaderHash[[:space:]]+[0-9a-f]+' | awk '{print $2}' | head -1)"
  if [[ -z "$hash" ]]; then
    echo "   取不到 hash。先 ./lab.sh arthas-reset 再试"
    rm -rf "$work"; return 1
  fi
  echo "   classLoaderHash = $hash"

  echo "⑤ 把源码送进容器（根文件系统只读，docker cp 用不了，走 stdin）"
  docker exec -i "$CONTAINER" sh -c "cat > /tmp/ArthasDemoController.java" < "$src"

  echo "⑥ mc 编译"
  local mc_out
  mc_out="$(./lab.sh arthas "mc -c $hash /tmp/ArthasDemoController.java -d /tmp" 2>&1)"
  if ! grep -q 'Memory compiler output' <<<"$mc_out"; then
    echo "$mc_out" | grep -iE 'error|message' | head -4 | sed 's/^/   /'
    rm -rf "$work"; return 1
  fi
  echo "   编译成功"

  echo "⑦ retransform 让新字节码生效"
  local rt_out
  rt_out="$(./lab.sh arthas "retransform /tmp/lab/jvm/controller/ArthasDemoController.class" 2>&1)"
  if ! grep -q 'retransform success' <<<"$rt_out"; then
    echo "$rt_out" | tail -4 | sed 's/^/   /'
    rm -rf "$work"; return 1
  fi
  echo "   已生效"

  echo "⑧ 改之后（注意：进程没有重启）"
  curl -s --max-time 20 "$LAB_URL/demo/price?amount=100&vip=true" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print("   payable =",d["payable"],"  (",d["discountRule"],")")'

  rm -rf "$work"
  cat <<'EOF'

   对照一下：90 → 80，说明新代码已经在跑着的 JVM 里生效了。
   ★ 这个改动只在内存里，不落盘 —— ./lab.sh restart 就恢复原样。
   ★ 生产上用热更新要非常克制：绕过发版流程 = 绕过代码评审和回滚机制。
EOF
}

# ---------------------------------------------------------------- 主入口

case "${1:-help}" in
  start)      shift; do_start "${1:-default}" ;;
  stop)       do_stop ;;
  restart)    shift; do_restart "${1:-default}" ;;
  status)     do_status ;;
  limits)     do_limits ;;
  log)        shift; do_log "${1:-60}" ;;
  gc)         shift; do_gc "${1:-40}" ;;
  shell)      do_shell ;;
  ui|web)     do_ui ;;
  dump-heap)  shift; do_dump_heap "${1:-manual}" ;;
  dump-thread) shift; do_dump_thread "${1:-1}" "${2:-5}" ;;
  trigger)    shift; do_trigger "${1:-help}" ;;
  arthas)     shift; do_arthas "$@" ;;
  arthas-demo) do_arthas_demo ;;
  arthas-hotfix) do_arthas_hotfix ;;
  arthas-reset) do_arthas_reset ;;
  diagnose)   do_diagnose ;;
  clean)      do_clean ;;
  profiles)   list_profiles ;;
  host-start) shift; do_host_start "${1:-default}" ;;
  help|*)
    sed -n '3,34p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
