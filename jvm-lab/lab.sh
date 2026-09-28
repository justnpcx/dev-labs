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
    # 对照组：2G 堆。容器 mem_limit 是 2g，所以这个 profile 必须配合
    # 调大 mem_limit 才能用 —— 见下方 do_start 里的检查
    bigheap)  limits="-Xms2g -Xmx2g -Xss1m"; gc="-XX:+UseG1GC" ;;
    nolimit)  limits="-Xms256m -Xmx256m"; gc="" ;;
    *)
      echo "未知 profile：$profile" >&2
      echo "可选：default g1 parallel serial zgc gc-overhead bigheap nolimit" >&2
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
bigheap    2G 堆，对照组：同样的泄漏在大堆下能撑多久（需同时调大 mem_limit）
nolimit    只设 -Xmx，不设 Metaspace/直接内存上限，看 OOM 形态如何变化

提示：profile 只决定 JVM 参数。容器本身的资源上限（内存/进程数/CPU）
      写在 docker-compose.yml 里，两者是独立的 —— 正是这种"双层限制"
      保证了无论 JVM 参数怎么改，都越不出容器。
EOF
}

write_env() {
  local profile="$1"
  local pair limits gc
  pair="$(profile_opts "$profile")"
  limits="${pair%%|*}"
  gc="${pair##*|}"

  cat > "$ENV_FILE" <<EOF
# 由 ./lab.sh start <profile> 自动生成（profile=$profile），不要手改
LAB_JVM_LIMITS=$limits
LAB_GC=$gc
LAB_COMMON=-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps -XX:NativeMemoryTracking=summary -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M
EOF
}

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
    cpu)         url="$LAB_URL/cpu/spin?threads=2" ;;
    cpu-stop)    url="$LAB_URL/cpu/stop" ;;
    leak)        url="$LAB_URL/leak/static?mb=2&count=5" ;;
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
    cpu          打满 CPU        → 配合 top -H + jstack 定位
    cpu-stop     停掉空转线程

  泄漏类
    leak         渐进式缓存泄漏  → 配合两次 jmap -histo 对比

  工具
    status       看 JVM 全景
    reset        清空所有泄漏 + 复位
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
  diagnose)   do_diagnose ;;
  clean)      do_clean ;;
  profiles)   list_profiles ;;
  host-start) shift; do_host_start "${1:-default}" ;;
  help|*)
    sed -n '3,29p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
