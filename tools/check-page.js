// Syntax-checks the page's script (node tools/check-page.js [path to bridge.html]); a typo in it leaves the whole page dead.
const fs = require('fs');
const html = fs.readFileSync(process.argv[2] || 'app/src/main/assets/bridge.html', 'utf8');
const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g;
let m, n = 0, bad = 0;
while ((m = re.exec(html))) {
  n++;
  try { new Function(m[1]); } catch (e) { bad++; console.log('script #' + n + ' (' + m[1].length + ' chars): ' + e.message); }
}
console.log('scripts: ' + n + ', with syntax errors: ' + bad);
