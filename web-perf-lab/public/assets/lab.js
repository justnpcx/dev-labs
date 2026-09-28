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
