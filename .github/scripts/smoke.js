// Verifica en Chromium que la versión OFUSCADA no rompe nada vs la original.
// Sirve _src (original) y _site (ofuscado) y compara página por página.
// Falla (exit 1) si la ofuscada introduce errores de JS nuevos o se queda en blanco.
// Uso: node smoke.js <carpetaOriginal> <carpetaOfuscada>
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const PAGINAS = [
  'index.html', 'tienda.html', 'firmar-am.html', 'activar-cabina.html',
  'cabina.html', 'app.html', 'carisma/index.html', 'papel/index.html',
];

const MIME = { '.html':'text/html', '.js':'text/javascript', '.json':'application/json',
  '.css':'text/css', '.wasm':'application/wasm', '.webp':'image/webp', '.jpg':'image/jpeg',
  '.jpeg':'image/jpeg', '.png':'image/png', '.mp4':'video/mp4', '.webmanifest':'application/manifest+json',
  '.apk':'application/vnd.android.package-archive', '.svg':'image/svg+xml' };

function servir(raiz, puerto) {
  return new Promise(ok => {
    const s = http.createServer((req, res) => {
      let p = decodeURIComponent(req.url.split('?')[0]);
      if (p.endsWith('/')) p += 'index.html';
      const f = path.join(raiz, p);
      if (!f.startsWith(raiz) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { 'content-type': MIME[path.extname(f).toLowerCase()] || 'application/octet-stream',
        'cross-origin-opener-policy':'same-origin', 'cross-origin-embedder-policy':'require-corp' });
      fs.createReadStream(f).pipe(res);
    });
    s.listen(puerto, () => ok(s));
  });
}

async function mirar(browser, url) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(String(e.message || e)));
  try { await page.goto(url, { waitUntil: 'load', timeout: 40000 }); } catch (e) { errs.push('goto:' + e.message); }
  await page.waitForTimeout(1800);
  const texto = await page.evaluate(() => document.body ? document.body.innerText.replace(/\s+/g,' ').trim() : '').catch(()=> '');
  await ctx.close();
  // los errores de red (CDN/recursos) son iguales en ambas versiones: se ignoran
  const red = e => /net::ERR|ERR_|Failed to (load|fetch)|CORS|cdnjs|MediaDevices|getUserMedia|NotAllowed|NotFound|secure context|AMMApp|Permission/i.test(e);
  return { app: errs.filter(e => !red(e)), texto };
}

(async () => {
  const [,, dirSrc, dirObf] = process.argv;
  const sa = await servir(path.resolve(dirSrc), 8081);
  const sb = await servir(path.resolve(dirObf), 8082);
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  let fallos = 0;
  for (const pg of PAGINAS) {
    if (!fs.existsSync(path.join(dirObf, pg))) { console.log(`- ${pg}: (no existe, saltada)`); continue; }
    const a = await mirar(browser, 'http://localhost:8081/' + pg);
    const b = await mirar(browser, 'http://localhost:8082/' + pg);
    const nuevos = b.app.filter(e => !a.app.includes(e));
    const simil = a.texto === b.texto ? 1 : (() => {
      const A = new Set(a.texto.split(' ')), B = new Set(b.texto.split(' '));
      let c = 0; A.forEach(w => B.has(w) && c++); return c / Math.max(1, A.size);
    })();
    const ok = nuevos.length === 0 && simil >= 0.95;
    console.log(`${ok ? '✔' : '✘'} ${pg}  | errores nuevos: ${nuevos.length} | texto: ${(simil*100).toFixed(0)}%`);
    nuevos.slice(0,5).forEach(e => console.log('     ✘', e));
    if (!ok) fallos++;
  }
  await browser.close(); sa.close(); sb.close();
  console.log(fallos ? `\nSMOKE: ✘ ${fallos} página(s) con problema — NO se despliega.` : '\nSMOKE: ✔ todo igual que el original.');
  process.exit(fallos ? 1 : 0);
})();
