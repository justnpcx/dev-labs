/* SSR 场景共用：一份「服务端和客户端都要跑」的渲染代码。
 *
 * 真实框架靠构建工具把同一份组件源码分别打进服务端包和客户端包；
 * 这个演练场没有构建步骤，所以同一份逻辑在 server.js 里也有一份，
 * 内容**逐字一致**。这恰恰是 SSR 能成立的前提：
 *   两边跑同一份 render + 同一份数据  →  产出的 HTML 必须一模一样
 * 一旦这条被打破（用了 Date.now() / Math.random() / locale 格式化），
 * 就会出现 hydration mismatch —— 见 /ssr/mismatch 场景。
 */

const SSR_NAMES = ['机械键盘', '人体工学椅', '27 寸显示器', '降噪耳机', '升降桌',
                   '显示器支架', '无线鼠标', 'USB-C 扩展坞', '笔记本支架', '桌面音箱'];

/** 和 server.js 的 ssrProducts() 逐字一致 */
function ssrProducts(n) {
  const out = new Array(n);
  for (let i = 0; i < n; i++) {
    out[i] = {
      id: i,
      name: SSR_NAMES[i % SSR_NAMES.length] + ' #' + i,
      price: 99 + (i * 37) % 900,
    };
  }
  return out;
}

/** 和 server.js 的 ssrItemHtml() 逐字一致 */
function ssrItemHtml(p) {
  return `<li class="pcard" data-id="${p.id}">`
       + `<span class="pthumb"></span>`
       + `<span class="pname">${p.name}</span>`
       + `<span class="pprice">¥${p.price}</span>`
       + `<span class="ptags"><i>现货</i><i>包邮</i></span>`
       + `<button class="padd" data-add="${p.id}">加入购物车</button>`
       + `</li>`;
}

function ssrListHtml(items) {
  return items.map(ssrItemHtml).join('');
}

/* ─────────────────── hydration ───────────────────
 *
 * hydration 到底在干什么？三件事：
 *   ① 重新执行一遍组件，算出"这个位置应该长什么样"
 *   ② 和服务端已经渲染好的 DOM 逐一比对（框架叫 reconciliation / diff）
 *   ③ 给已有节点挂上事件监听
 *
 * 它**不会重新创建 DOM** —— 那 SSR 就白做了。但①和②是实打实的 CPU 开销，
 * 节点越多越慢。这就是"页面看起来好了，但点了没反应"的来源：
 * FCP/LCP 早就到了，hydration 还没跑完，页面处于「可见但不可交互」的状态。
 */

let cartCount = 0;

function onAdd(ev) {
  const id = ev.currentTarget.dataset.add;
  cartCount++;
  labLog(`加入购物车：商品 #${id}（累计 ${cartCount} 件）`);
}

/** 内部节点计数，防止上面的工作被优化掉 */
let fiberNodes = 0;

/**
 * 给一个已经渲染好的节点做 hydration。返回比对是否一致。
 *
 * ⚠ 这个函数我改了三次，每次都是因为"测出来的数字和现象对不上"：
 *
 * 第一次：只比对根节点就完事 → 1500 个条目只花 22ms，完全体现不出"卡住主线程"。
 *   真实框架的 hydration 是**逐元素**走的，开销正比于元素数。
 *
 * 第二次：改成对每个元素 setAttribute → 那是**写** DOM，会让样式失效。
 *   同步版本里这些写被攒到最后一次性重算，分片版本一让出就触发一次重算，
 *   结果分片版反而慢了 8 倍，把结论完全带偏。
 *
 * 第三次（现在）：改成**建内部节点树**（框架里叫 fiber / vnode）。
 *   这才是 hydration 真正在做的事 —— 为每个元素分配一个内部节点对象、
 *   逐个子节点做 reconcile。开销同样正比于元素数，但全是 JS 对象分配，
 *   不碰 DOM，所以不会引入"写 DOM 才有的"副作用。
 */
function hydrateNode(node, p) {
  // ① 重新执行组件
  const expected = ssrItemHtml(p);
  // ② 和现有 DOM 比对
  const match = node.outerHTML === expected;

  // ③ 为这棵子树建内部节点（这一步才是 hydration 的主要开销）
  const kids = node.children;
  const fiber = { tag: node.tagName, children: [] };
  for (let i = 0; i < kids.length; i++) {
    const child = { tag: kids[i].tagName, cls: kids[i].className, children: [] };
    const grand = kids[i].children;
    for (let j = 0; j < grand.length; j++) {
      child.children.push({ tag: grand[j].tagName, text: grand[j].textContent });
    }
    fiber.children.push(child);
  }
  fiberNodes += fiber.children.length;

  // ④ 挂事件
  node.querySelector('.padd').addEventListener('click', onAdd);
  return match;
}

/**
 * 一次性 hydration：所有节点一口气处理完。
 * 期间主线程被独占 —— 用户点击全部排在队列里，表现为"点了没反应"。
 */
function hydrateAll(root, props) {
  const nodes = root.children;
  let mismatched = 0;
  for (let i = 0; i < nodes.length; i++) {
    if (!hydrateNode(nodes[i], props.items[i])) mismatched++;
  }
  return { count: nodes.length, mismatched };
}

/**
 * 分片 hydration：每处理一批就把主线程让出去一次。
 * 总工作量不变（甚至因为调度开销略微变多），但页面**很快就变得可交互**。
 *
 * 这也是真实框架在做的事：React 18 的 selective hydration、
 * 各种 partial hydration 方案，本质都是"别一口气把主线程占死"。
 */
async function hydrateChunked(root, props, chunkSize = 1000) {
  const nodes = root.children;
  let mismatched = 0;
  for (let i = 0; i < nodes.length; i += chunkSize) {
    const end = Math.min(i + chunkSize, nodes.length);
    for (let j = i; j < end; j++) {
      if (!hydrateNode(nodes[j], props.items[j])) mismatched++;
    }
    await yieldToMain();
  }
  return { count: nodes.length, mismatched };
}

/** 把主线程还给浏览器。优先用 scheduler.yield()，退回到 setTimeout。 */
function yieldToMain() {
  if (typeof scheduler !== 'undefined' && typeof scheduler.yield === 'function') {
    return scheduler.yield();
  }
  return new Promise((r) => setTimeout(r, 0));
}
