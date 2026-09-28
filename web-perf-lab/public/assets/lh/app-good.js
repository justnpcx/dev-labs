/* Lighthouse 优化版的脚本：小、被 defer、且**真的被用到**。
   对比 bad 版：那边额外加载了 60KB 从未被调用的历史代码。 */

(function () {
  'use strict';

  // 订阅表单：阻止默认提交，给个反馈
  const form = document.getElementById('subscribe');
  if (form) {
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const btn = form.querySelector('button[type="submit"]');
      const email = form.querySelector('#email');
      btn.textContent = '已订阅';
      btn.disabled = true;
      if (email) email.value = '';
      const msg = document.getElementById('form-msg');
      if (msg) msg.textContent = '订阅成功（这是演练场，不会真的发邮件）';
    });
  }

  // 顶栏里的年份
  const year = document.getElementById('year');
  if (year) year.textContent = String(new Date().getFullYear());
})();
