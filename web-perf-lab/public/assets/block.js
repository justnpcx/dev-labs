/* 这个脚本**故意**在主线程上忙等 1.5 秒。
 *
 * 用途：演示「渲染阻塞脚本」。
 * 它在 <head> 里被同步引入时，浏览器必须下载 + 执行完它，
 * 才会继续解析后面的 HTML —— 所以首屏会白屏 1.5 秒以上。
 *
 * 对比：同一个文件改成 <script defer> 或 async 引入，
 * 白屏消失（但执行时机不同，看观察清单）。 */

(function () {
  const deadline = Date.now() + 1500;
  // 纯计算忙等。不用 setTimeout —— 那是异步的，不会阻塞解析。
  let x = 0;
  while (Date.now() < deadline) {
    x += Math.sqrt(x + 1);
  }
  // 把结果挂到 window，防止被 JIT 优化掉整段循环
  window.__LAB_BLOCK_RESULT = x;
})();
