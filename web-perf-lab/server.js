'use strict';

/*
 * 前端性能演练场 —— 零依赖 HTTP 服务
 *
 * 为什么不用 Express：这个演练场是拿来练 DevTools 的，服务端越透明越好。
 * 零依赖意味着 docker build 不需要 npm install，构建快、没有供应链风险，
 * 而且你能直接读这个文件就知道每个响应头是怎么来的 —— 这正是演练场该有的样子。
 *
 * 三类路由：
 *   /api/*       动态接口（慢响应、重定向链、CORS 预检、大数据量）
 *   /assets/*    静态资源，**按目录前缀决定缓存与压缩策略**，供对比
 *   /{network|performance|debug}/<场景>/{bad|good}   场景页面
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8080);
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2',
};

// ───────────────────────────── 工具 ─────────────────────────────

function send(res, status, body, headers = {}) {
  // 兜底：已经发过响应就别再发。
  // 正常流程走不到这里，但路由的返回值约定一旦写错（比如漏了 return true），
  // 就会变成"发两次"，Node 会直接抛 ERR_HTTP_HEADERS_SENT 把进程干掉。
  // 演练场崩了比返回一个错误页糟糕得多，所以留这一道。
  if (res.headersSent) {
    console.error('[warn] 响应已发送，忽略重复的 send()：', res.req?.url);
    return;
  }
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8');
  res.writeHead(status, { 'Content-Length': buf.length, ...headers });
  res.end(buf);
}

function sendJson(res, obj, headers = {}) {
  send(res, 200, JSON.stringify(obj, null, 2),
       { 'Content-Type': MIME['.json'], ...headers });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ─────────────────── 动态生成 PNG（避免往仓库塞二进制） ───────────────────

/** CRC32，PNG 每个 chunk 都要带 */
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

/**
 * 最小 PNG 编码器（8 位 RGB）。
 *
 * 为什么要自己写：图片场景需要"大图"来演示体积和 CLS，
 * 但把二进制图片提交进 git 很脏。动态生成就没这个问题，
 * 而且参数可调，能现场对比不同体积。
 *
 * @param noise 0 = 平滑渐变（用 Up 滤波，deflate 后极小）
 *              1 = 随机噪声（压不动，体积接近原始大小）
 *
 * 关于滤波：PNG 每行开头那个字节是 filter type。
 *   0 = None（原始值）
 *   2 = Up（减去上一行同位置的像素）
 * 渐变图用 Up 滤波后，相邻行差值接近 0，deflate 能压到几千分之一 ——
 * 这正是真实 PNG 编码器会做的事（它们自适应选最优滤波）。
 * 用 None 的话渐变也压不动，演示效果会差很多（实测 123KB vs 1KB）。
 */
function makePng(w, h, noise) {
  const bpp = 3;                       // RGB 每像素 3 字节
  const stride = w * bpp;

  // 第一步：先算出**原始像素**。
  // 必须单独存一份 —— Up 滤波要减的是上一行的原始值，
  // 不是上一行滤波后的值（这是最容易写错的地方，我第一版就写错了）。
  const orig = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * stride + x * bpp;
      if (noise) {
        orig[i] = (Math.random() * 256) | 0;
        orig[i + 1] = (Math.random() * 256) | 0;
        orig[i + 2] = (Math.random() * 256) | 0;
      } else {
        orig[i] = (x * 255 / w) | 0;
        orig[i + 1] = (y * 255 / h) | 0;
        orig[i + 2] = 128;
      }
    }
  }

  // 第二步：按行滤波。每行开头 1 字节是 filter type。
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    const rowStart = y * (stride + 1);
    raw[rowStart] = noise ? 0 : 2;          // 0 = None, 2 = Up
    for (let x = 0; x < w; x++) {
      const si = y * stride + x * bpp;      // 源：原始像素
      const di = rowStart + 1 + x * bpp;    // 目标：滤波后
      if (!noise && y > 0) {
        const ui = si - stride;             // 上一行同位置的**原始**值
        raw[di] = (orig[si] - orig[ui]) & 0xff;
        raw[di + 1] = (orig[si + 1] - orig[ui + 1]) & 0xff;
        raw[di + 2] = (orig[si + 2] - orig[ui + 2]) & 0xff;
      } else {
        raw[di] = orig[si];
        raw[di + 1] = orig[si + 1];
        raw[di + 2] = orig[si + 2];
      }
    }
  }

  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td), 0);
    return Buffer.concat([len, td, crc]);
  };

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;    // bit depth
  ihdr[9] = 2;    // color type: truecolor RGB
  // 10/11/12 = compression/filter/interlace，全 0

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 造一段可压缩性很好的文本（重复内容多，gzip 收益明显） */
function makeBigJs(kb) {
  const lines = [];
  let i = 0;
  while (Buffer.byteLength(lines.join('\n'), 'utf8') < kb * 1024) {
    lines.push(`window.LAB_DATA_${i} = { id: ${i}, name: "item-${i}", `
             + `desc: "这是第 ${i} 条用于演示压缩效果的重复数据", tags: ["a","b","c"] };`);
    i++;
  }
  return lines.join('\n');
}

// ─────────────────────── 静态资源：缓存策略对照 ───────────────────────

/**
 * /assets/nocache/*   —— 完全不设缓存头。每次刷新都 200 全量重传。
 * /assets/cached/*    —— 强缓存一年。第二次刷新浏览器直接用本地副本，
 *                        Network 面板里显示 "(disk cache)"，压根不发请求。
 * /assets/etag/*      —— 协商缓存。带 ETag，浏览器发 If-None-Match，
 *                        服务端回 304（无 body）。对比 200 的 Size 列。
 */
function serveAssetVariant(req, res, urlPath) {
  const m = urlPath.match(/^\/assets\/(nocache|cached|etag|nogzip|gzip)\/(.+)$/);
  if (!m) return false;

  const [, variant, rel] = m;
  const file = path.join(PUBLIC_DIR, 'assets', rel);

  // 防路径穿越
  if (!path.resolve(file).startsWith(path.resolve(PUBLIC_DIR))) {
    send(res, 403, 'forbidden');
    return true;
  }

  const ext = path.extname(file);
  let body;
  try {
    if (rel === 'big.js') {
      body = Buffer.from(makeBigJs(300), 'utf8');   // 动态生成 300KB 文本
    } else {
      body = fs.readFileSync(file);
    }
  } catch {
    send(res, 404, `资源不存在：${urlPath}\n\n提示：/assets/{nocache|cached|etag|nogzip|gzip}/<文件名>`);
    return true;
  }

  const headers = { 'Content-Type': MIME[ext] || 'application/octet-stream' };

  if (variant === 'nocache') {
    // 故意什么都不设。浏览器每次都会重新下载。
    // 注意：不设 Cache-Control 时浏览器仍可能用启发式缓存，
    // 所以这里额外加 no-store 才是真的"每次都下"——但为了演示
    // "没配缓存头会怎样"，我们连 no-store 都不加，保持裸奔。
    headers['Cache-Control'] = 'no-store';   // 想看纯裸奔就把这行注释掉
  } else if (variant === 'cached') {
    headers['Cache-Control'] = 'public, max-age=31536000, immutable';
  } else if (variant === 'etag') {
    headers['Cache-Control'] = 'no-cache';   // no-cache = 可以缓存，但每次要校验
    headers['ETag'] = `"lab-${Buffer.byteLength(body)}-${ext}"`;
    const inm = req.headers['if-none-match'];
    if (inm && inm === headers['ETag']) {
      res.writeHead(304, { ETag: headers['ETag'], 'Cache-Control': 'no-cache' });
      res.end();
      return true;
    }
  } else if (variant === 'nogzip') {
    headers['Cache-Control'] = 'no-store';
    headers['X-Lab-Note'] = 'no-compression';
  } else if (variant === 'gzip') {
    headers['Cache-Control'] = 'no-store';
    const accept = String(req.headers['accept-encoding'] || '');
    if (accept.includes('br')) {
      body = zlib.brotliCompressSync(body);
      headers['Content-Encoding'] = 'br';
    } else if (accept.includes('gzip')) {
      body = zlib.gzipSync(body);
      headers['Content-Encoding'] = 'gzip';
    }
    headers['Vary'] = 'Accept-Encoding';
  }

  send(res, 200, body, headers);
  return true;
}

// ─────────── Lighthouse 场景：动态生成"大块未使用"资源 ───────────

/**
 * 生成一份**大量规则用不上**的 CSS。
 *
 * Lighthouse 的 "Reduce unused CSS" 审计要求文件够大（几十 KB）才会报，
 * 而把这么大的文件提交进仓库很脏 —— 何况它全是无意义的规则。
 * 动态生成既省仓库，又能调参（?kb= 控制大小）。
 */
function makeUnusedCss(kb) {
  const out = [];
  let i = 0;
  while (Buffer.byteLength(out.join('\n'), 'utf8') < kb * 1024) {
    const hue = i % 360;
    out.push(
      `.legacy-module-${i} .widget-${i} > .item-${i}:hover {`
      + ` color: hsl(${hue}, 60%, 45%);`
      + ` border-color: hsl(${(hue + 30) % 360}, 60%, 45%);`
      + ` transition: all 0.2s ease-in-out; }`,
      `.legacy-module-${i}[data-state="open"] .panel-${i} {`
      + ` transform: translateZ(0); opacity: ${(i % 10) / 10}; }`
    );
    i++;
  }
  return out.join('\n');
}

/** 生成一份**大部分用不到**的 JS，外加一个真正的长任务 */
function makeUnusedJs(kb) {
  const out = ['/* 大部分是没被调用的历史代码 —— Lighthouse 的 "unused JavaScript" 会算进去 */'];
  let i = 0;
  while (Buffer.byteLength(out.join('\n'), 'utf8') < kb * 1024) {
    out.push(
      `function legacyHelper${i}(input) {`,
      `  // 这段代码从来没有被调用过`,
      `  const normalized = String(input || '').trim().toLowerCase();`,
      `  return normalized.split('').reverse().join('').repeat(${(i % 3) + 1});`,
      `}`,
      `window.LEGACY_${i} = legacyHelper${i};`
    );
    i++;
  }
  return out.join('\n');
}

/**
 * /assets/lh/*  —— Lighthouse 场景专用资源
 *
 * bad 版本是动态生成的（大块未使用），good 版本是真实的小文件。
 * 两者都会被浏览器下载，所以"未使用率"的对比是真实的。
 *
 * ⚠ 返回值约定：处理了就返回 true，没匹配上返回 false。
 * 这里踩过一次坑：写成 `return send(...)` —— send() 没有返回值，
 * 于是函数返回 undefined，调用方 `if (serveLighthouseAsset(...)) return`
 * 判断为假，继续往下走到静态兜底又 send 了一次，
 * 触发 ERR_HTTP_HEADERS_SENT，把整个进程搞崩了。
 */
function serveLighthouseAsset(req, res, urlPath) {
  const m = urlPath.match(/^\/assets\/lh\/([a-z0-9.-]+)$/);
  if (!m) return false;

  const name = m[1];
  const params = new URL(req.url, 'http://x').searchParams;

  if (name === 'theme-bad.css') {
    const kb = Math.min(Number(params.get('kb') || 45), 300);
    send(res, 200, makeUnusedCss(kb), {
      'Content-Type': MIME['.css'],
      'Cache-Control': 'no-store',          // 故意不缓存，Lighthouse 会扣分
    });
    return true;
  }

  if (name === 'app-bad.js') {
    const kb = Math.min(Number(params.get('kb') || 60), 300);
    send(res, 200, makeUnusedJs(kb), {
      'Content-Type': MIME['.js'],
      'Cache-Control': 'no-store',
    });
    return true;
  }

  if (name === 'block-bad.js') {
    // 渲染阻塞 + 长任务：在 head 里同步引入时，白屏 + TBT 暴涨
    const ms = Math.min(Number(params.get('ms') || 600), 3000);
    send(res, 200,
      `(function(){var d=Date.now()+${ms},x=0;while(Date.now()<d){x+=Math.sqrt(x+1);}`
      + `window.__LH_BLOCK=x;})();`, {
        'Content-Type': MIME['.js'],
        'Cache-Control': 'no-store',
      });
    return true;
  }

  // 其余（good 版本）走静态文件
  const file = path.join(PUBLIC_DIR, 'assets', 'lh', name);
  if (!path.resolve(file).startsWith(path.resolve(PUBLIC_DIR)) || !fs.existsSync(file)) {
    send(res, 404, '资源不存在');
    return true;
  }
  const ext = path.extname(file);
  send(res, 200, fs.readFileSync(file), {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Cache-Control': 'public, max-age=31536000, immutable',
  });
  return true;
}

// ───────── 演练用资源：虚拟列表数据 / 字体 / Service Worker ─────────

/**
 * 生成 N 条记录，给"长列表渲染"场景用。
 *
 * 为什么放服务端生成：一万条记录写进 HTML 会让页面文件巨大且没法调参。
 * 服务端生成可以现场改 n，对比 1 千 / 1 万 / 5 万条时 DOM 节点数和内存的差别。
 */
function makeItems(n) {
  const items = new Array(n);
  for (let i = 0; i < n; i++) {
    items[i] = {
      id: i,
      name: `记录 ${i}`,
      desc: `第 ${i} 条，用于演示长列表对主线程、内存和滚动帧率的影响`,
      tags: ['lab', 'list', i % 5 === 0 ? 'hot' : 'cold'],
    };
  }
  return items;
}

/**
 * 一个**故意不是合法字体**的占位载荷。
 *
 * 为什么不放真字体：把几百 KB 的二进制提交进仓库很脏（和图片同理），
 * 而构建期去装字体又引入了对包管理器的网络依赖。
 *
 * 但演示依然成立 —— `font-display: block` 造成的 FOIT（文字不可见）
 * 取决于**字体下载耗时**，不取决于下载完之后能不能解析成功。
 * 所以"延迟返回"就足以把 block 和 swap 的差别演示清楚。
 */
function makePlaceholderFont(bytes) {
  const buf = Buffer.alloc(bytes);
  buf.write('wOFF', 0, 'ascii');          // 看起来像个 woff，但内容不是
  for (let i = 4; i < bytes; i++) buf[i] = (i * 31) & 0xff;
  return buf;
}

/**
 * /cache/sw/<mode>/{sw.js,data.json} —— Service Worker 场景专用。
 *
 * 为什么把 SW 放在**场景页自己的路径下**（而不是单独的 /sw-lab/ 前缀）：
 *   Service Worker 的作用域 = 脚本所在目录。脚本在 /cache/sw/bad/sw.js，
 *   作用域就是 /cache/sw/bad/ —— 正好把场景页 /cache/sw/bad 和它的数据源
 *   一起圈进去，别的一律不受影响。
 *
 *   一开始写成 /sw-lab/bad/sw.js（页面在 /cache/sw/bad），结果页面落在作用域外：
 *   虽然 SW 仍然按 URL 拦截 /sw-lab/ 下的请求，但 navigator.serviceWorker.ready
 *   永远不 resolve（它要求"当前页面在作用域内"），页面状态卡在 installing。
 *   放到同一条路径下就没这个歧义了。
 *
 * bad / good 用两个不同子路径，是因为同一个作用域只能有一个 SW ——
 * 两个页面抢一个作用域会互相覆盖。
 */
function serveSwLab(req, res, urlPath) {
  const m = urlPath.match(/^\/cache\/sw\/(bad|good)\/(sw\.js|data\.json)$/);
  if (!m) return false;

  const [, mode, file] = m;
  const params = new URL(req.url, 'http://x').searchParams;

  if (file === 'sw.js') {
    send(res, 200, swSource(mode), {
      'Content-Type': MIME['.js'],
      // SW 脚本本身绝不缓存，否则改了代码刷新不生效 —— 这是 SW 最常见的坑
      'Cache-Control': 'no-store',
      // ★ 这个头是必须的，而且原因很反直觉：
      // 浏览器给 SW 脚本规定的「最大作用域」= 脚本所在目录（这里是 /cache/sw/bad/）。
      // 页面注册时如果请求一个**更宽**的作用域（/cache/sw/bad，少一个尾斜杠），
      // 会被直接拒绝：
      //   "The path of the provided scope is not under the max scope allowed"
      // 想放宽就必须由服务端显式发这个头。它是服务端对"这个脚本可以管多宽"的授权。
      'Service-Worker-Allowed': `/cache/sw/${mode}`,
    });
    return true;
  }

  // data.json：延迟可调，用来放大"等网络"和"用缓存"的差别
  const ms = Math.min(Number(params.get('ms') || 800), 5000);
  const version = params.get('v') || '1';
  setTimeout(() => {
    sendJson(res, {
      version,
      generatedAt: new Date().toISOString(),
      payload: makeItems(20),
      note: `这条数据是 ${ms}ms 之后才生成的`,
    }, { 'Cache-Control': 'no-store' });
  }, ms);
  return true;
}

/** 两个 SW 实现，差别只在 fetch 处理那几行 —— 这正是场景要对比的东西 */
function swSource(mode) {
  if (mode === 'bad') {
    return `/* 问题版：SW 挂上了，但 fetch 直接透传 —— 每次都要等完整网络延迟。
   SW 在这里只是个"透明的旁路"，用户感受到的和没有 SW 一模一样。 */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (!url.pathname.endsWith('/data.json')) return;   // 只管数据，别的放行
  event.respondWith(fetch(event.request));
});
`;
  }
  return `/* 优化版：stale-while-revalidate —— 先给缓存，后台再更新。
   这是应用层缓存里性价比最高的策略：用户永远不等网络，
   数据新鲜度只落后一个请求周期。 */
const CACHE = 'sw-lab-good-v1';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (!url.pathname.endsWith('/data.json')) return;

  // 网络请求和"给页面响应"是**两条并行的路**，这是这个策略的关键。
  const network = (async () => {
    const cache = await caches.open(CACHE);
    const resp = await fetch(event.request);
    const body = await resp.clone().text();
    // 重新构造一个干净的 Response 再存。
    // 直接 put 原始响应会把源站的 Cache-Control: no-store 一起带进缓存，
    // 有些实现会因此拒绝存储 —— 这个坑很隐蔽。
    await cache.put(event.request, new Response(body, {
      headers: { 'Content-Type': 'application/json' },
    }));
    return resp;
  })();

  // 不 await 它，但要 waitUntil 让 SW 活到写完缓存
  event.waitUntil(network.catch(() => {}));

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(event.request);

    if (cached) {
      // 命中缓存 → 立刻返回，**完全不等网络**。
      // 加个标记头，方便在页面和 DevTools 里确认走的是哪条路。
      const body = await cached.text();
      return new Response(body, {
        headers: { 'Content-Type': 'application/json', 'X-Lab-From': 'sw-cache' },
      });
    }
    return (await network) || new Response('{}', {
      headers: { 'Content-Type': 'application/json', 'X-Lab-From': 'fallback' },
    });
  })());
});
`;
}

// ─────────────────────────── API ───────────────────────────

async function handleApi(req, res, url) {
  const p = url.pathname;

  // 健康检查（给容器 healthcheck 用，不参与演练）
  if (p === '/api/health') {
    return sendJson(res, { status: 'UP' }, { 'Cache-Control': 'no-store' });
  }

  // 慢响应：延迟**在发响应头之前**，所以拖的是 TTFB（Waiting for server response）
  if (p === '/api/slow') {
    const ms = Math.min(Number(url.searchParams.get('ms') || 1500), 10000);
    await sleep(ms);
    return sendJson(res, { ok: true, delayedMs: ms, note: '这段延迟发生在响应头之前，体现在 TTFB' },
                    { 'Cache-Control': 'no-store' });
  }

  // 重定向链：/api/chain?n=3 会连续跳 n 次才到终点
  if (p === '/api/chain') {
    const n = Math.min(Number(url.searchParams.get('n') || 3), 10);
    if (n > 0) {
      res.writeHead(302, {
        Location: `/api/chain?n=${n - 1}`,
        'Cache-Control': 'no-store',
      });
      return res.end();
    }
    return sendJson(res, { ok: true, note: '重定向链终点' }, { 'Cache-Control': 'no-store' });
  }

  // 小 JSON：给瀑布流场景用，每个 300ms
  if (p.startsWith('/api/item/')) {
    const id = p.split('/').pop();
    await sleep(300);
    return sendJson(res, { id, name: `数据 ${id}`, value: Math.random().toFixed(4) },
                    { 'Cache-Control': 'no-store' });
  }

  // CORS：简单请求（不触发预检）
  if (p === '/api/cors/simple') {
    return sendJson(res, { ok: true, kind: 'simple' }, {
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
  }

  // CORS：带自定义头 → 触发 OPTIONS 预检
  if (p === '/api/cors/preflight') {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
        'Access-Control-Allow-Headers': 'X-Lab-Token',
        'Access-Control-Max-Age': '0',        // 设为 0，每次都重新预检，方便反复观察
        'Cache-Control': 'no-store',
      });
      return res.end();
    }
    return sendJson(res, { ok: true, kind: 'preflight', token: req.headers['x-lab-token'] || null }, {
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
  }

  // 大数据量：默认 800KB JSON，用来对比压缩前后
  if (p === '/api/big') {
    const kb = Math.min(Number(url.searchParams.get('kb') || 800), 5000);
    const items = [];
    for (let i = 0; i < kb; i++) {
      items.push({ id: i, name: `记录-${i}`, desc: '这是一条用于撑大响应体的重复文本内容', tags: ['x', 'y', 'z'] });
    }
    return sendJson(res, { count: items.length, items }, { 'Cache-Control': 'no-store' });
  }

  // 动态生成图片。
  //   ?noise=1    → 随机噪声，几乎压不动，体积巨大（演示"未优化图片"）
  //   不带 noise  → 平滑渐变，同样尺寸但只有几十 KB
  //   ?nocache=1  → 不缓存。优先级场景靠它保证每次刷新都真的重新下载，
  //                 否则第二次刷新命中缓存，Priority 和耗时都看不出来了
  if (p === '/api/image') {
    const w = Math.min(Number(url.searchParams.get('w') || 800), 2000);
    const h = Math.min(Number(url.searchParams.get('h') || 600), 2000);
    const noise = url.searchParams.get('noise') === '1' ? 1 : 0;
    const delay = Math.min(Number(url.searchParams.get('delay') || 0), 5000);
    const nocache = url.searchParams.get('nocache') === '1';
    if (delay) await sleep(delay);
    const png = makePng(w, h, noise);
    return send(res, 200, png, {
      'Content-Type': 'image/png',
      'Cache-Control': nocache ? 'no-store' : 'public, max-age=3600',
      'X-Lab-Bytes': String(png.length),
    });
  }

  // 长列表数据源。n 默认 5000，上限 50000 —— 再大浏览器自己就先卡住了，
  // 反而看不出"优化前后"的差别。
  if (p === '/api/items') {
    const n = Math.min(Number(url.searchParams.get('n') || 5000), 50000);
    return sendJson(res, { count: n, items: makeItems(n) },
                    { 'Cache-Control': 'no-store' });
  }

  // 渲染阻塞脚本：等 ms 毫秒后返回一小段合法 JS。
  //
  // 为什么需要它：优先级场景要制造"HTML 解析被卡住"，但用动态生成的 300KB
  // 脚本会有个副作用 —— 服务端生成它是**同步 CPU 活**，Node 单线程会被它占住，
  // 于是所有请求（包括我们要观察的那张图）都被拖慢。那样测出来的是服务端瓶颈，
  // 不是浏览器的资源调度行为。
  // 这个端点纯粹 setTimeout，服务端几乎零开销，只让浏览器真的等。
  if (p === '/api/block.js') {
    const ms = Math.min(Number(url.searchParams.get('ms') || 600), 5000);
    await sleep(ms);
    return send(res, 200,
      '/* 阻塞脚本：解析到这里时 HTML 解析器必须停下来等它 */'
      + 'window.__LAB_BLOCKED = (window.__LAB_BLOCKED || 0) + 1;', {
        'Content-Type': MIME['.js'],
        'Cache-Control': 'no-store',
      });
  }

  // 延迟返回的占位字体。delay 决定 FOIT 有多长（见 makePlaceholderFont 的注释）。
  if (p === '/api/font') {
    const delay = Math.min(Number(url.searchParams.get('delay') || 2500), 8000);
    const kb = Math.min(Number(url.searchParams.get('kb') || 24), 500);
    await sleep(delay);
    return send(res, 200, makePlaceholderFont(kb * 1024), {
      'Content-Type': 'font/woff2',
      // 不缓存，否则第二次刷新字体是瞬时的，FOIT 就看不到了
      'Cache-Control': 'no-store',
      'X-Lab-Note': 'placeholder-font-payload',
    });
  }

  send(res, 404, '未知 API');
}

// ─────────────────────── 场景页面路由 ───────────────────────

const SCENARIO_DIR = {
  network: 'network', performance: 'performance', debug: 'debug',
  lighthouse: 'lighthouse', cache: 'cache',
};

function serveScenario(res, urlPath) {
  // /network/waterfall/bad   →  scenarios/network/waterfall-bad.html
  // /debug/override          →  scenarios/debug/override.html
  // /lighthouse/audit/bad    →  scenarios/lighthouse/audit-bad.html
  // /cache/sw/bad            →  scenarios/cache/sw-bad.html
  const m = urlPath.match(/^\/(network|performance|debug|lighthouse|cache)\/([a-z0-9-]+)(?:\/(bad|good))?\/?$/i);
  if (!m) return false;

  const [, group, name, variant] = m;
  const file = path.join(PUBLIC_DIR, 'scenarios', SCENARIO_DIR[group],
                         variant ? `${name}-${variant}.html` : `${name}.html`);

  if (!path.resolve(file).startsWith(path.resolve(PUBLIC_DIR)) || !fs.existsSync(file)) {
    send(res, 404, `场景不存在：${urlPath}\n\n回索引看看有哪些：/`);
    return true;
  }
  send(res, 200, fs.readFileSync(file), { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-store' });
  return true;
}

// ─────────────────────────── 主入口 ───────────────────────────

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;

  // 演练场的页面一律不缓存，否则改完刷新看不到效果
  try {
    if (p.startsWith('/api/')) return await handleApi(req, res, url);
    if (serveAssetVariant(req, res, p)) return;
    if (serveLighthouseAsset(req, res, p)) return;
    if (serveSwLab(req, res, p)) return;
    if (serveScenario(res, p)) return;

    // 其余走 public/ 静态目录
    let file = p === '/' ? '/index.html' : p;
    const abs = path.join(PUBLIC_DIR, file);
    if (!path.resolve(abs).startsWith(path.resolve(PUBLIC_DIR)) || !fs.existsSync(abs) || fs.statSync(abs).isDirectory()) {
      return send(res, 404, '页面不存在。回索引：/');
    }
    send(res, 200, fs.readFileSync(abs), {
      'Content-Type': MIME[path.extname(abs)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
  } catch (e) {
    console.error('[error]', req.method, p, e);
    send(res, 500, '服务端错误：' + e.message);
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`前端性能演练场已启动 → http://0.0.0.0:${PORT}/`);
});
