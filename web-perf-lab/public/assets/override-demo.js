/* ══════════════════════════════════════════════════════════════
   这个文件是「动态 JS 替换」练习的目标。
   它里面有一个**故意的 bug**，你的任务是在浏览器里改掉它，
   而且**不碰服务器上的这个文件**。

   bug 在下面 discountRate 那一行 —— 找到它，用 Local Overrides 改掉。
   ══════════════════════════════════════════════════════════════ */

window.LAB_OVERRIDE = {

  // 商品原价
  price: 200,

  // ⚠ BUG：这里应该是 0.25（打七五折），写成了 0.1
  //    练习目标：用 Local Overrides 把它改成 0.25，刷新后价格应该变成 150
  discountRate: 0.1,

  // 满减门槛
  threshold: 180,
  deduction: 20,

  calcPrice() {
    const discounted = this.price * (1 - this.discountRate);
    return discounted >= this.threshold
      ? discounted - this.deduction
      : discounted;
  },

  render() {
    const el = document.getElementById('result');
    if (!el) return;

    const final = this.calcPrice();
    el.innerHTML = `
      <div class="row" style="justify-content:space-between;margin:0;padding:6px 0;border-bottom:1px dashed #222a35">
        <span class="hint">原价</span><span class="mono">¥${this.price}</span>
      </div>
      <div class="row" style="justify-content:space-between;margin:0;padding:6px 0;border-bottom:1px dashed #222a35">
        <span class="hint">折扣率</span>
        <span class="mono" style="color:var(--red)">${this.discountRate}  ← 这里不对</span>
      </div>
      <div class="row" style="justify-content:space-between;margin:0;padding:6px 0;border-bottom:1px dashed #222a35">
        <span class="hint">折后</span><span class="mono">¥${this.price * (1 - this.discountRate)}</span>
      </div>
      <div class="row" style="justify-content:space-between;margin:0;padding:6px 0">
        <span class="hint"><b>最终价</b></span>
        <span class="mono" style="font-size:18px;color:var(--yellow)">¥${final}</span>
      </div>
    `;

    // 顺便在控制台留个记录，方便你在 Console 里验证
    console.log('[LAB] 最终价 =', final, '（折扣率', this.discountRate, '）');
  },
};

// 页面加载后渲染一次
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => window.LAB_OVERRIDE.render());
} else {
  window.LAB_OVERRIDE.render();
}
