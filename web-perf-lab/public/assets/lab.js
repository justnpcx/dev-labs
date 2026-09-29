/* 场景页共用的几个小工具。刻意保持极简 —— 
   真正的学习点在 DevTools 里，不在这里。 */

/** 往页面的 #log 里追加一行（没有 #log 就忽略） */
function labLog(msg) {
  const el = document.getElementById('log');
  if (!el) return;
  const t = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  el.textContent += `[${t}] ${msg}\n`;
  el.scrollTop = el.scrollHeight;
}

/** 页面加载后自动执行一次，用于"刷新就能看到现象"的场景 */
function onLoad(fn) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', fn);
  } else {
    fn();
  }
}

/** 本演练场的「兄弟源」：同一台服务器、同一个端口，只是 host 不同。
    浏览器眼里 localhost 和 127.0.0.1 是两个不同的 origin，
    所以从 localhost 打 127.0.0.1 是真的跨源 —— 这是 CORS 场景能触发预检的唯一前提。
    （相对路径 fetch 是同源，永远不会预检。） */
function labTwinOrigin() {
  const twin = location.hostname === 'localhost' ? '127.0.0.1' : 'localhost';
  return `${location.protocol}//${twin}${location.port ? ':' + location.port : ''}`;
}

/** 是否通过本地回环访问。CORS 场景要求跨源，只有本地才满足：
    公网入口挂了 Cloudflare Access，而预检请求不带 Access 的 cookie，会被 403 挡掉。 */
function labIsLocalOrigin() {
  return location.hostname === 'localhost' || location.hostname === '127.0.0.1';
}

/** CORS 场景专用的降级：非本地访问时禁用按钮并说明原因，而不是"点了没反应" */
function labRequireLocalOrigin() {
  if (labIsLocalOrigin()) return true;
  const notice = document.getElementById('remoteNotice');
  if (notice) notice.hidden = false;
  const btn = document.getElementById('runBtn');
  if (btn) {
    btn.disabled = true;
    btn.textContent = '仅本地可用';
  }
  const status = document.getElementById('status');
  if (status) status.textContent = '请用 SSH 隧道本地访问';
  return false;
}

/** 显示当前页面的关键指标（有些场景用它证明"页面自己也知道自己慢"） */
function showMetrics() {
  const el = document.getElementById('metrics');
  if (!el) return;
  const nav = performance.getEntriesByType('navigation')[0];
  if (!nav) return;
  const rows = [
    ['TTFB（等待服务器响应）', nav.responseStart - nav.requestStart],
    ['DOMContentLoaded', nav.domContentLoadedEventEnd - nav.startTime],
    ['Load 完成', nav.loadEventEnd - nav.startTime],
    ['DOM 节点数', document.getElementsByTagName('*').length],
  ];
  el.innerHTML = rows.map(([k, v]) =>
    `<div class="row" style="justify-content:space-between;margin:0;padding:4px 0;border-bottom:1px dashed #222a35">
       <span class="hint">${k}</span>
       <span class="mono">${typeof v === 'number' ? v.toFixed(1) : v}${typeof v === 'number' ? ' ms' : ''}</span>
     </div>`).join('');
}

onLoad(() => setTimeout(showMetrics, 100));
