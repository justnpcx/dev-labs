/* 跑在独立线程里的 Worker。
 *
 * 主线程和 Worker 是**真正并行**的两条线程 ——
 * 这里忙等多久，主线程都毫无感觉。
 *
 * 代价：
 *   · 不能访问 DOM（Worker 里没有 window / document）
 *   · 和主线程通信要 postMessage，数据会被结构化克隆（有序列化开销）
 *   · 不是所有环境都支持（老旧浏览器 / 部分 CSP 配置下会被拦）
 */

self.onmessage = function (e) {
  const ms = (e.data && e.data.ms) || 800;
  const deadline = Date.now() + ms;
  let x = 0;
  while (Date.now() < deadline) {
    x += Math.sqrt(x + 1);
  }
  self.postMessage({ result: x, blockedMs: ms });
};
