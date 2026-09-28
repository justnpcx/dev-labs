/* 一个"普通大小"的业务脚本，用于缓存 / 压缩场景对比。
   内容不重要，重要的是它的体积 —— 让缓存命中与未命中的差别看得见。 */

(function () {
  'use strict';

  const LAB = {
    version: '1.0.0',
    startedAt: new Date().toISOString(),
  };

  // 塞一些重复内容，让 gzip 有发挥空间
  const TEMPLATES = [
    '这是一段用于撑大体积的重复文本，模拟真实业务脚本里的字符串常量。',
    '接口请求失败时应当给出可读的错误提示，而不是把原始堆栈丢给用户。',
    '列表渲染要注意虚拟滚动，否则几千条数据会把主线程占满。',
    '事件监听记得解绑，尤其是挂在 window 上的那些。',
    '定时器要在组件卸载时清掉，否则会一直持有闭包里的引用。',
  ];

  function pick(n) {
    const out = [];
    for (let i = 0; i < n; i++) out.push(TEMPLATES[i % TEMPLATES.length]);
    return out;
  }

  LAB.messages = pick(120);

  LAB.init = function () {
    const el = document.getElementById('app-status');
    if (el) el.textContent = `app.js 已执行（${LAB.version}）`;
    return LAB;
  };

  window.LAB = LAB;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', LAB.init);
  } else {
    LAB.init();
  }
})();
