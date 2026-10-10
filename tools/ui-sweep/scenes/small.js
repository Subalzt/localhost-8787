window.__small = async function () {
  const out = {};
  for (const t of ['home', 'phones', 'cams', 'phone', 'music', 'alerts', 'settings']) {
    document.querySelector('.tabs button[data-tab="' + t + '"]').click();
    await new Promise(r => setTimeout(r, t === 'music' ? 3000 : 1300));
    const seen = new Set();
    document.querySelectorAll('button, [role=button], a.sr-action, select, input:not([type=hidden]):not([type=range])').forEach(el => {
      const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
      if (r.width === 0 || cs.display === 'none' || cs.visibility === 'hidden' || el.closest('[hidden]') || el.closest('.tabs')) return;
      if (r.height < 40 || r.width < 40) { const k = el.tagName.toLowerCase() + '.' + String(el.className).split(' ').slice(0, 2).join('.') + ' ' + Math.round(r.width) + 'x' + Math.round(r.height); seen.add(k); }
    });
    out[t] = [...seen].slice(0, 14);
  }
  return JSON.stringify(out);
};
