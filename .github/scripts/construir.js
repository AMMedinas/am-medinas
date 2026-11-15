// Construye el sitio OFUSCADO en una carpeta de salida.
// - Copia todo tal cual (media, APK, wasm, librerías…).
// - Ofusca SOLO los <script> en línea (JS) de los .html, sin renombrar globales.
// - Omite carpetas frágiles (convertidor: usa workers/WASM/COOP).
// Uso: node construir.js <carpetaSitio>
const fs = require('fs');
const path = require('path');
const JO = require('javascript-obfuscator');

const OMITIR_DIRS = ['convertidor'];   // se sirven tal cual
const OPCIONES = {
  compact: true,
  controlFlowFlattening: false,
  deadCodeInjection: false,
  selfDefending: false,
  renameGlobals: false,            // CRÍTICO: no romper onclick="..." ni variables globales entre bloques
  identifierNamesGenerator: 'hexadecimal',
  stringArray: true,
  stringArrayEncoding: ['base64'],
  stringArrayThreshold: 0.75,
  numbersToExpressions: true,
  simplify: true,
  transformObjectKeys: false,
  unicodeEscapeSequence: false,
};

function tipoEsJS(attrs) {
  const m = /type\s*=\s*["']?([^"'\s>]+)/i.exec(attrs);
  if (!m) return true;
  return ['text/javascript', 'application/javascript', 'module', 'text/ecmascript'].includes(m[1].toLowerCase());
}

function ofuscarHtml(html, rel) {
  let n = 0, saltados = 0;
  const out = html.replace(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi, (full, attrs, code) => {
    if (/\bsrc\s*=/i.test(attrs)) return full;
    if (!tipoEsJS(attrs)) return full;
    if (!code.trim()) return full;
    try {
      const ofus = JO.obfuscate(code, OPCIONES).getObfuscatedCode();
      n++;
      return `<script${attrs}>${ofus}</script>`;
    } catch (e) {
      saltados++;
      console.error(`  ⚠ ${rel}: bloque sin ofuscar (${e.message.split('\n')[0]})`);
      return full;   // ante la duda, se deja el original
    }
  });
  return { out, n, saltados };
}

function recorrer(dir, raiz) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, ent.name);
    const rel = path.relative(raiz, abs);
    if (ent.isDirectory()) {
      if (OMITIR_DIRS.includes(ent.name)) { console.error(`  (omitida la carpeta ${rel})`); continue; }
      recorrer(abs, raiz);
    } else if (/\.html?$/i.test(ent.name)) {
      const html = fs.readFileSync(abs, 'utf8');
      const { out, n, saltados } = ofuscarHtml(html, rel);
      fs.writeFileSync(abs, out);
      console.error(`  ${rel}: ${n} bloque(s) ofuscado(s)${saltados ? ', ' + saltados + ' saltado(s)' : ''}`);
    }
  }
}

const sitio = process.argv[2];
if (!sitio) { console.error('falta la carpeta del sitio'); process.exit(1); }
console.error('Ofuscando sitio en', sitio);
recorrer(sitio, sitio);
console.error('Listo.');
