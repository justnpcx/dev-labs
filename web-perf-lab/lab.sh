#!/usr/bin/env bash
#
# 前端性能演练场 —— 唯一入口脚本
#
# 设计前提：这个演练场里有**故意**做慢、做卡、泄漏内存的页面，
# 所以默认只在 Docker 容器里跑，由 cgroup 硬限制兜底，绝不碰宿主。
#
#   ./lab.sh start      构建并启动
#   ./lab.sh stop       停止并移除
#   ./lab.sh status     容器状态 + 隔离边界校验 + 宿主负载
#   ./lab.sh limits     只看隔离边界
#   ./lab.sh ui         访问地址（含 SSH 隧道命令）
#   ./lab.sh log [n]    看容器日志
#   ./lab.sh shell      进容器
#   ./lab.sh routes     列出所有场景 URL
#
set -euo pipefail

cd "$(dirname "$0")"

CONTAINER=web-perf-lab
LAB_PORT=8082
LAB_URL="http://localhost:${LAB_PORT}"

# ───────────────────────────── 隔离校验 ─────────────────────────────

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

# 校验隔离边界是否**真的生效**。配置写了不等于内核生效了 ——
# 在 jvm-lab 上实测遇到过 swap 限制丢失、而 compose 和 docker inspect 都正常的情况。
verify_limits() {
  local raw mem swap pids cpu ver rc=0
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

  if [[ "$mem" =~ ^[0-9]+$ ]] && (( mem > 0 )); then
    printf "  ✓ 内存上限          %s MB\n" "$((mem / 1024 / 1024))"
  else
    printf "  ✗ 内存上限          未生效（值=%s）\n" "$mem"; rc=1
  fi

  if [[ "$ver" == "v2" ]]; then
    if [[ "$swap" == "0" ]]; then
      printf "  ✓ swap 已禁用       memory.swap.max = 0\n"
    else
      printf "  ✗ swap 未禁用       memory.swap.max = %s（应为 0）\n" "$swap"
      printf "    → 修：docker compose up -d --force-recreate\n"; rc=1
    fi
  else
    if [[ "$swap" == "$mem" ]]; then
      printf "  ✓ swap 已禁用\n"
    else
      printf "  ✗ swap 未禁用       memsw=%s vs mem=%s\n" "$swap" "$mem"; rc=1
    fi
  fi

  if [[ "$pids" =~ ^[0-9]+$ ]]; then
    printf "  ✓ 进程数上限        %s\n" "$pids"
  else
    printf "  ✗ 进程数上限        未生效（值=%s）\n" "$pids"; rc=1
  fi

  if [[ "$cpu" != "max" && "$cpu" != "-1" && -n "$cpu" && "$cpu" != "n/a" ]]; then
    printf "  ✓ CPU 配额          %s\n" "$cpu"
  else
    printf "  ✗ CPU 配额          未生效（值=%s）\n" "$cpu"; rc=1
  fi
  return $rc
}

# ───────────────────────────── 命令 ─────────────────────────────

do_start() {
  echo "构建并启动…"
  docker compose up -d --build
  for _ in $(seq 1 40); do
    if curl -sf "$LAB_URL/api/health" >/dev/null 2>&1; then
      echo "启动成功 → $LAB_URL/"
      echo
      echo "隔离边界校验："
      verify_limits || {
        echo
        echo "⚠ 有防线未生效。修法：docker compose up -d --force-recreate"
      }
      return 0
    fi
    sleep 0.5
  done
  echo "启动超时，看日志：./lab.sh log" >&2
  return 1
}

do_stop() {
  # 不要把 down 的错误吞掉 —— down 失败意味着容器没真删掉，
  # 下次 up 会复用旧 cgroup 配置（jvm-lab 上踩过）
  local out rc=0
  out="$(docker compose down 2>&1)" || rc=$?
  if (( rc != 0 )); then
    echo "  ⚠ docker compose down 失败（rc=$rc）："
    echo "$out" | sed 's/^/    /'
  fi
  echo "已停止"
}

do_status() {
  echo "=== 容器 ==="
  docker compose ps
  echo
  echo "=== 隔离边界校验 ==="
  verify_limits || true
  echo
  echo "=== 宿主负载（确认没被演练波及）==="
  echo "  $(uptime | sed 's/^ *//')"
  free -g | awk 'NR==2{printf "  宿主内存: 总 %sG / 已用 %sG / 可用 %sG\n",$2,$3,$7}'
}

do_limits() {
  echo "=== 隔离边界校验（从容器内读 cgroup 并判定）==="
  if verify_limits; then
    echo
    echo "  四道防线全部生效。"
  else
    echo
    echo "  ⚠ 有防线未生效！"
    return 1
  fi
}

do_ui() {
  local ip
  ip=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)
  echo "演练场地址：$LAB_URL/"
  echo
  echo "── 方式一：SSH 隧道（推荐）────────────────────────────────"
  echo "  在你自己的电脑上执行："
  echo "      ssh -L $LAB_PORT:127.0.0.1:$LAB_PORT root@${ip:-<这台机器的IP>}"
  echo "  然后浏览器打开： http://localhost:$LAB_PORT/"
  echo
  echo "── 方式二：挂到已有的 Cloudflare Tunnel ──────────────────"
  echo "  和 jvm-lab 一样：Public Hostname 指向 http://web-perf-lab:8080"
  echo "  （需要先把这个容器接入 ai-stack_ai-net）"
  echo
  if curl -sf "$LAB_URL/api/health" >/dev/null 2>&1; then
    echo "容器状态：运行中 ✓"
  else
    echo "容器状态：接口不通 ✗（先 ./lab.sh start）"
  fi
}

do_routes() {
  echo "所有场景（问题版 / 优化版成对，可开两个标签页并排对比）："
  echo
  echo "── Network 面板 ──────────────────────────────────────────"
  echo "  串行 vs 并行请求     $LAB_URL/network/waterfall/bad      vs  /good"
  echo "  缓存策略             $LAB_URL/network/cache/bad          vs  /good"
  echo "  压缩                 $LAB_URL/network/compress/bad       vs  /good"
  echo "  渲染阻塞 JS          $LAB_URL/network/blocking/bad       vs  /good"
  echo "  图片与 CLS           $LAB_URL/network/image/bad          vs  /good"
  echo "  CORS 预检            $LAB_URL/network/cors/bad           vs  /good"
  echo "  慢 TTFB 与重定向     $LAB_URL/network/ttfb/bad           vs  /good"
  echo
  echo "── Performance 面板 ─────────────────────────────────────"
  echo "  长任务               $LAB_URL/performance/longtask/bad   vs  /good"
  echo "  布局抖动             $LAB_URL/performance/thrash/bad     vs  /good"
  echo "  未节流事件监听       $LAB_URL/performance/listener/bad   vs  /good"
  echo "  内存泄漏             $LAB_URL/performance/leak/bad       vs  /good"
  echo "  动画属性             $LAB_URL/performance/animate/bad    vs  /good"
  echo
  echo "── JS 调试 ──────────────────────────────────────────────"
  echo "  动态 JS 替换         $LAB_URL/debug/override"
  echo "  断点全家桶           $LAB_URL/debug/breakpoints"
  echo
  echo "索引页：$LAB_URL/"
}

do_log() {
  docker logs --tail "${1:-50}" "$CONTAINER"
}

do_shell() {
  echo "进容器。服务端代码在 /app/server.js，页面在 /app/public/"
  docker exec -it "$CONTAINER" sh
}

do_help() {
  sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'
}

case "${1:-help}" in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; do_start ;;
  status)  do_status ;;
  limits)  do_limits ;;
  ui|web)  do_ui ;;
  routes)  do_routes ;;
  log)     shift; do_log "${1:-50}" ;;
  shell)   do_shell ;;
  help|-h|--help) do_help ;;
  *)
    echo "未知命令：$1" >&2
    do_help >&2
    exit 1
    ;;
esac
