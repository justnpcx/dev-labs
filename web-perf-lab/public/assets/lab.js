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

/* ─────────────────── 主题 ───────────────────
 *
 * 实际主题已经由 <head> 里的引导脚本写好了（那一步必须在样式表之前同步跑，
 * 否则会闪一下）。这里只负责三件事：
 *   1. 把切换控件注入页头
 *   2. 用户点的时候改写偏好并重算
 *   3. 偏好为 auto 时跟随系统实时变化
 *
 * 偏好存 localStorage，刷新后保持。三态：跟随系统 / 浅色 / 深色。
 */
const LAB_THEME_KEY = 'lab-theme';
const LAB_THEME_NAME = { auto: '跟随系统', light: '浅色', dark: '深色' };

function labResolveTheme(pref) {
  if (pref !== 'auto') return pref;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function labApplyTheme(pref, persist) {
  const root = document.documentElement;
  root.dataset.themePref = pref;
  root.dataset.theme = labResolveTheme(pref);
  if (persist) {
    try { localStorage.setItem(LAB_THEME_KEY, pref); } catch (e) { /* 隐私模式忽略 */ }
  }
  document.querySelectorAll('.theme-switch button').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.themePref === pref));
  });
}

function labInjectThemeSwitch() {
  // 场景页放页头最右边；索引页没有页头，放 hero 里预留的插槽
  const host = document.querySelector('#themeSlot')
            || document.querySelector('.lab-header')
            || document.querySelector('.hero');
  if (!host || host.querySelector('.theme-switch')) return;

  const box = document.createElement('div');
  box.className = 'theme-switch';
  box.setAttribute('role', 'group');
  box.setAttribute('aria-label', '主题');

  ['auto', 'light', 'dark'].forEach((pref) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.dataset.themePref = pref;
    b.textContent = LAB_THEME_NAME[pref];
    b.addEventListener('click', () => labApplyTheme(pref, true));
    box.appendChild(b);
  });
  host.appendChild(box);

  // 只在 auto 模式下跟随系统。用户显式选了浅/深，就不该被系统设置覆盖。
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (document.documentElement.dataset.themePref === 'auto') labApplyTheme('auto', false);
  });

  labApplyTheme(document.documentElement.dataset.themePref || 'auto', false);
}

onLoad(labInjectThemeSwitch);

/* ─────────────────── 难度分层 ───────────────────
 *
 * 21 个场景按"需要多少前置知识 + 要跨几层推理"分成四级，
 * 建议按 初级 → 中级 → 高级 → 终极 的顺序走。
 *
 * 为什么不把等级写进每个 HTML：那样 42 个文件都要改一遍，
 * 而且以后调整分级要再改一遍。集中在这里，一处生效。
 */
const LAB_LEVELS = {
  // 初级：看懂"浏览器怎么加载一个页面"，现象肉眼可见
  1: [
    'network/waterfall',    // 请求之间怎么排队
    'network/cache',        // 缓存头怎么起作用
    'network/compress',     // 传输体积
    'network/image',        // 图片与布局偏移
    'network/blocking',     // 渲染阻塞
    'performance/animate',  // 动画走哪条渲染路径
  ],
  // 中级：要会用面板才能定位，但因果链是单向的
  2: [
    'network/ttfb',         // 拆 Timing 面板
    'network/priority',     // 资源优先级排序
    'network/font',         // 字体发现晚 + FOIT/FOUT
    'performance/listener', // 事件频率 vs 渲染帧率
    'performance/thrash',   // 强制同步布局
    'performance/longtask', // 长任务切分
    'ssr/csr',              // 白屏期：CSR vs 服务端直出
    'debug/override',       // 不改代码改线上 JS
    'debug/breakpoints',    // 断点全家桶
  ],
  // 高级：要跨"网络 / 渲染 / 线程 / 缓存"几层一起推理，且要改架构
  3: [
    'network/cors',         // 跨源 + 预检
    'performance/inp',      // 输入延迟，Core Web Vital
    'performance/worker',   // 把计算搬出主线程
    'performance/virtual',  // 长列表：复杂度从 O(n) 降到 O(视口)
    'cache/sw',             // 应用层缓存，策略得自己写对
    'ssr/hydration',        // 可见 ≠ 可交互
    'ssr/mismatch',         // 两端渲染不一致，SSR 白做
  ],
  // 终极：没有"改一行就好"的解法，要靠工具反复逼近
  4: [
    'performance/leak',     // 内存泄漏：Retainers 定位引用链
    'lighthouse/audit',     // 综合评分：前面所有点的相互作用
  ],
};

const LAB_LEVEL_NAME = { 1: '初级', 2: '中级', 3: '高级', 4: '终极' };

/** 当前页面属于哪一级（不在表里就返回 0） */
function labLevelOf(pathname) {
  const m = pathname.match(/^\/(network|performance|debug|lighthouse|cache)\/([a-z0-9-]+)/i);
  if (!m) return 0;
  const key = `${m[1].toLowerCase()}/${m[2].toLowerCase()}`;
  for (const lv of [1, 2, 3, 4]) {
    if (LAB_LEVELS[lv].includes(key)) return lv;
  }
  return 0;
}

/** 把等级徽章插进页头（紧跟在「问题版 / 优化版」徽章后面） */
function labInjectLevelBadge() {
  const lv = labLevelOf(location.pathname);
  if (!lv) return;
  const header = document.querySelector('.lab-header');
  if (!header) return;

  const badge = document.createElement('span');
  badge.className = `badge lv${lv}`;
  badge.textContent = LAB_LEVEL_NAME[lv];
  badge.title = '难度分级：初级 → 中级 → 高级 → 终极';

  const first = header.querySelector('.badge');
  if (first && first.nextSibling) header.insertBefore(badge, first.nextSibling);
  else header.insertBefore(badge, header.firstChild);
}

onLoad(labInjectLevelBadge);

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
