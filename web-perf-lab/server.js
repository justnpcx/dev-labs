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
  //   ?noise=1  → 随机噪声，几乎压不动，体积巨大（演示"未优化图片"）
  //   不带 noise → 平滑渐变，同样尺寸但只有几十 KB
  if (p === '/api/image') {
    const w = Math.min(Number(url.searchParams.get('w') || 800), 2000);
    const h = Math.min(Number(url.searchParams.get('h') || 600), 2000);
    const noise = url.searchParams.get('noise') === '1' ? 1 : 0;
    const delay = Math.min(Number(url.searchParams.get('delay') || 0), 5000);
    if (delay) await sleep(delay);
    const png = makePng(w, h, noise);
    return send(res, 200, png, {
      'Content-Type': 'image/png',
      'Cache-Control': 'public, max-age=3600',
      'X-Lab-Bytes': String(png.length),
    });
  }

  send(res, 404, '未知 API');
}

// ─────────────────────── 场景页面路由 ───────────────────────

const SCENARIO_DIR = { network: 'network', performance: 'performance', debug: 'debug' };

function serveScenario(res, urlPath) {
  // /network/waterfall/bad  →  scenarios/network/waterfall-bad.html
  // /debug/override         →  scenarios/debug/override.html
  const m = urlPath.match(/^\/(network|performance|debug)\/([a-z0-9-]+)(?:\/(bad|good))?\/?$/i);
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
