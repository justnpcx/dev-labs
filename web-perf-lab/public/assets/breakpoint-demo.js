/* 断点练习的演示逻辑。放在独立文件里，方便你在 Sources 面板找到并打断点。 */

(function () {
  'use strict';

  const B = {
    // 供「条件断点」练习：一个循环，你只想在第 7 次停下来
    loopSum(n) {
      let sum = 0;
      for (let i = 1; i <= n; i++) {
        sum += i * i;              // ← 在这一行打条件断点：i === 7
      }
      return sum;
    },

    // 供「DOM 断点」练习：会修改一个节点的属性
    mutateDom() {
      const el = document.getElementById('domTarget');
      if (!el) return;
      el.setAttribute('data-step', String(Date.now()));
      el.style.background = `hsl(${Date.now() % 360}, 60%, 55%)`;
      el.textContent = '刚被改过 ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
      return el.getAttribute('data-step');
    },

    // 供「XHR / Fetch 断点」练习
    async fetchData(id) {
      const r = await fetch('/api/item/' + id);     // ← 对这个 URL 打 Fetch 断点
      return r.json();
    },

    // 供「事件监听断点」练习
    onButtonClick(label) {
      return `按钮被点了：${label}`;
    },
  };

  window.LAB_BP = B;
})();
