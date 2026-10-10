// Injected into the page by the relay (/__t/audit.js): __audit() lists layout and accessibility trouble on the tab
// shown, __sweep(tabs) visits each tab and audits it.
window.__errs = window.__errs || [];
if (!window.__errHook) {
  window.__errHook = 1;
  window.addEventListener('error', function (e) { window.__errs.push(String(e.message).slice(0, 160)); });
  window.addEventListener('unhandledrejection', function (e) { window.__errs.push('rej:' + String(e.reason && e.reason.message || e.reason).slice(0, 160)); });
}
window.__audit = function () {
  const vw = innerWidth, vh = innerHeight, out = { tab: document.body.dataset.tab, vw: vw, vh: vh };
  const vis = function (el) { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0' && !el.closest('[hidden]'); };
  const name = function (el) { return (el.getAttribute('aria-label') || el.title || el.textContent || el.value || el.id || el.className || el.tagName).toString().trim().replace(/\s+/g, ' ').slice(0, 28); };
  out.docOverflowX = document.documentElement.scrollWidth - vw;
  const over = [], small = [], unl = [], clip = [], tiny = [], imgs = [], cut = [];
  document.querySelectorAll('body *').forEach(function (el) {
    if (!vis(el)) return;
    const r = el.getBoundingClientRect(), tag = el.tagName;
    if ((r.right > vw + 1 || r.left < -1) && !el.closest('.tabs,.tl-days,[data-scroll],.scroller,pre,table,.mon-sheet,#player,.np,.nm,.mon-pill') && r.width < vw * 3 && over.length < 8) over.push(name(el) + '[' + Math.round(r.left) + ',' + Math.round(r.right) + ']');
    const clickable = tag === 'BUTTON' || tag === 'SELECT' || (tag === 'INPUT' && el.type !== 'hidden' && el.type !== 'range') || el.getAttribute('role') === 'button' || el.getAttribute('role') === 'tab';
    if (clickable) {
      if ((r.width < 36 || r.height < 36) && small.length < 14) small.push(name(el) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
      if (!(el.textContent || '').trim() && !el.getAttribute('aria-label') && !el.title && unl.length < 12) unl.push((el.id || el.className || tag).toString().slice(0, 40) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
      if (r.bottom > vh + 1 && r.top < vh && cut.length < 5) cut.push(name(el));
    }
    const cs = getComputedStyle(el);
    if (el.children.length === 0 && (el.textContent || '').trim() && cs.overflow !== 'visible' && el.scrollWidth > el.clientWidth + 2 && !el.title && clip.length < 8) clip.push(name(el));
    if (el.children.length === 0 && (el.textContent || '').trim() && parseFloat(cs.fontSize) < 11 && tiny.length < 8) tiny.push(name(el) + ' ' + cs.fontSize);
    if (tag === 'IMG' && el.complete && el.naturalWidth === 0 && imgs.length < 5) imgs.push(el.src.slice(0, 60));
  });
  out.over = over; out.small = small; out.unlabeled = unl; out.clipped = clip; out.tinyText = tiny; out.badImgs = imgs; out.errs = window.__errs.slice(-5);
  return out;
};
window.__sweep = async function (tabs) {
  const res = [];
  for (const t of tabs || ['home', 'phones', 'map', 'cams', 'phone', 'music', 'alerts', 'settings']) {
    const b = document.querySelector('.tabs button[data-tab="' + t + '"]');
    if (!b) { res.push({ tab: t, missing: true }); continue; }
    b.click();
    await new Promise(function (r) { setTimeout(r, t === 'music' || t === 'map' || t === 'cams' ? 3200 : 1300); });
    window.scrollTo(0, 0);
    res.push(window.__audit());
  }
  return res;
};
window.__brief = function (res) {
  return res.map(function (x) {
    const o = { tab: x.tab, vw: x.vw };
    ['docOverflowX', 'over', 'small', 'unlabeled', 'clipped', 'tinyText', 'badImgs', 'errs'].forEach(function (k) {
      const v = x[k]; if (v && (!Array.isArray(v) || v.length) && v !== 0) o[k] = v;
    });
    return o;
  });
};
