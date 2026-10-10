// Screenshots of the page in a headless Edge driven over the DevTools protocol (Node 22+ has WebSocket).
// Start relay.py first (it serves the working page against the real phone), then:
//   node shoot.js --url http://127.0.0.1:8790/ --out DIR --devices phone-p,phone-l,tablet-p,desktop --themes light,dark
//                 --tabs home,phones,map,cams,phone,music,alerts,settings [--full] [--audit] [--js file.js] [--eval "expr"] [--errors]
// --audit  writes audit.json (overflow, small targets, unnamed buttons, clipped text, broken images) beside the shots
// --js     a script run in the page after it loads (set an accent: applyLook({accent:'yellow'}))
// --eval   an expression run in the page, its value printed (it may click tabs and await); --errors prints start-up exceptions
// Edge's own screenshots cannot go below ~500 px wide; this sets the size itself, so a phone's 390 px is real.
const { spawn } = require('child_process');
const fs = require('fs'), path = require('path'), os = require('os'), http = require('http');

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a.startsWith('--')) { const k = a.slice(2); const v = process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[++i] : true; args[k] = v; }
}
const URL0 = args.url || 'http://127.0.0.1:8790/';
const OUT = args.out || path.join(os.tmpdir(), 'shots');
fs.mkdirSync(OUT, { recursive: true });
const DEVICES = {
  'phone-p': { w: 390, h: 844, dpr: 2, mobile: true },
  'phone-l': { w: 844, h: 390, dpr: 2, mobile: true },
  'small-p': { w: 360, h: 740, dpr: 2, mobile: true },
  'tablet-p': { w: 820, h: 1180, dpr: 2, mobile: true },
  'tablet-l': { w: 1180, h: 820, dpr: 1.5, mobile: true },
  'laptop': { w: 1280, h: 720, dpr: 1, mobile: false },
  'desktop': { w: 1440, h: 900, dpr: 1, mobile: false },
};
const devices = (args.devices || 'phone-p').split(',');
const themes = (args.themes || 'light').split(',');
const tabs = (args.tabs || 'home,phones,map,cams,phone,music,alerts,settings').split(',');
const PORT = 9300 + Math.floor(Math.random() * 400);
const sleep = ms => new Promise(r => setTimeout(r, ms));

function getJson(url) {
  return new Promise((res, rej) => http.get(url, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => { try { res(JSON.parse(d)); } catch (e) { rej(e); } }); }).on('error', rej));
}

(async () => {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-'));
  const EDGE = ['C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', 'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'].find(p => fs.existsSync(p));
  if (!EDGE) { console.error('Microsoft Edge not found'); process.exit(1); }
  const edge = spawn(EDGE, [
    '--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile, '--no-first-run', '--no-proxy-server',
    '--hide-scrollbars', '--force-device-scale-factor=1', '--autoplay-policy=no-user-gesture-required', 'about:blank',
  ], { stdio: 'ignore' });
  let targets;
  for (let i = 0; i < 60; i++) { try { targets = await getJson('http://127.0.0.1:' + PORT + '/json'); if (targets.length) break; } catch (e) {} await sleep(250); }
  const page = targets.find(t => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r));
  let id = 0; const waiting = {}; const events = [];
  ws.addEventListener('message', m => { const d = JSON.parse(m.data); if (d.id && waiting[d.id]) { waiting[d.id](d); delete waiting[d.id]; } else events.push(d); });
  const send = (method, params) => new Promise(res => { const i = ++id; waiting[i] = res; ws.send(JSON.stringify({ id: i, method, params })); });
  const evalJs = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); return r.result && r.result.result ? r.result.result.value : undefined; };
  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
  const extra = args.js ? fs.readFileSync(args.js, 'utf8') : '';
  const audits = [];

  for (const dev of devices) {
    const D = DEVICES[dev];
    for (const theme of themes) {
      await send('Emulation.setDeviceMetricsOverride', { width: D.w, height: D.h, deviceScaleFactor: D.dpr, mobile: D.mobile, screenOrientation: { type: D.w > D.h ? 'landscapePrimary' : 'portraitPrimary', angle: D.w > D.h ? 90 : 0 } });
      await send('Emulation.setTouchEmulationEnabled', { enabled: D.mobile });
      await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] });
      await send('Page.navigate', { url: URL0 });
      await sleep(3500);
      // Load the audit helper from the relay and wait for the app.
      await evalJs("new Promise(r=>{const s=document.createElement('script');s.src='/__t/audit.js';s.onload=r;s.onerror=r;document.head.appendChild(s);})");
      await evalJs("applyTheme('" + theme + "')");
      if (extra) await evalJs(extra);
      if (args.errors) {
        const out = [];
        for (const e of events) {
          if (e.method === 'Runtime.exceptionThrown') { const d = e.params.exceptionDetails; out.push(['EXC', ((d.exception && d.exception.description) || d.text || '').slice(0, 400), d.lineNumber]); }
          else if (e.method === 'Runtime.consoleAPICalled') out.push(['console.' + e.params.type, (e.params.args || []).map(a => a.value || a.description).join(' ').slice(0, 200)]);
          else if (e.method === 'Log.entryAdded') out.push(['log', JSON.stringify(e.params.entry).slice(0, 220)]);
        }
        console.log('EVENTS', JSON.stringify(out.slice(0, 20)));
      }
      if (args.eval) console.log(dev, theme, JSON.stringify(await evalJs(args.eval)));
      for (const tab of tabs) {
        await evalJs("(function(){var b=document.querySelector('.tabs button[data-tab=\"" + tab + "\"]'); if(b) b.click(); window.scrollTo(0,0); return !!b;})()");
        await sleep(tab === 'music' || tab === 'map' || tab === 'cams' ? 3500 : 1600);
        const name = dev + '-' + theme + '-' + tab + '.png';
        let clip;
        if (args.full) {
          const h = await evalJs('Math.max(document.documentElement.scrollHeight, document.body.scrollHeight)');
          clip = { x: 0, y: 0, width: D.w, height: Math.min(h || D.h, 4200), scale: 1 };
        }
        const r = await send('Page.captureScreenshot', Object.assign({ format: 'png', captureBeyondViewport: !!args.full }, clip ? { clip } : {}));
        if (r.result && r.result.data) fs.writeFileSync(path.join(OUT, name), Buffer.from(r.result.data, 'base64'));
        else console.log('no image for', name, JSON.stringify(r.error || {}));
        if (args.audit) { const a = await evalJs('JSON.stringify(window.__audit ? window.__audit() : null)'); audits.push({ dev, theme, tab, a: a && JSON.parse(a) }); }
      }
    }
  }
  if (args.audit) fs.writeFileSync(path.join(OUT, 'audit.json'), JSON.stringify(audits, null, 1));
  try { ws.close(); } catch (e) {}
  edge.kill();
  console.log('done', OUT);
  process.exit(0);
})().catch(e => { console.error('shoot failed', e); process.exit(1); });
