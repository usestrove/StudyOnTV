// 全仓轻量静态守卫（借鉴 stablyai/orca 的机械护栏，零新增外部依赖）：
// 1. 禁止 parent-ui/src 裸调 crypto.randomUUID()（局域网纯 HTTP 非安全上下文会白屏）；
// 2. 禁止 parent-ui/src 业务组件硬编码 hex 色值（必须走 theme.css Token，白名单项除外）；
// 3. 校验 app/web/assets/js/ 非 vendor 脚本严格符合 ES5（兼容 Android 5/6 老电视 WebView）；
// 4. 校验 :parent 家长端清单严格遵守零敏感权限红线（仅 INTERNET + ACCESS_NETWORK_STATE）；
// 5. 强制单文件纯代码行数上限（.ts <= 300, .tsx <= 400, .kt <= 450, app/web .js <= 400）；
// 6. 禁止新建模糊垃圾桶文件名（utils / helpers / common / misc，仅保留 shadcn 标配 lib/utils.ts）。
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { basename, join, relative, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse as parseJs } from 'acorn';

const parentUiDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(parentUiDir, '..');
const failures = [];

function walk(dir, filterFn, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, filterFn, acc);
    } else if (filterFn(full, entry.name)) {
      acc.push(full);
    }
  }
  return acc;
}

function stripJsComments(source) {
  let out = '';
  let i = 0;
  let inSingle = false;
  let inDouble = false;
  let inTemplate = false;
  let inLineComment = false;
  let inBlockComment = false;
  while (i < source.length) {
    const ch = source[i];
    const next = source[i + 1];
    if (inLineComment) {
      if (ch === '\n') { inLineComment = false; out += '\n'; }
      i++; continue;
    }
    if (inBlockComment) {
      if (ch === '*' && next === '/') { inBlockComment = false; i += 2; continue; }
      if (ch === '\n') out += '\n';
      i++; continue;
    }
    if (inSingle) {
      out += ch;
      if (ch === '\\' && i + 1 < source.length) { out += next; i += 2; continue; }
      if (ch === "'") inSingle = false;
      i++; continue;
    }
    if (inDouble) {
      out += ch;
      if (ch === '\\' && i + 1 < source.length) { out += next; i += 2; continue; }
      if (ch === '"') inDouble = false;
      i++; continue;
    }
    if (inTemplate) {
      out += ch;
      if (ch === '\\' && i + 1 < source.length) { out += next; i += 2; continue; }
      if (ch === '`') inTemplate = false;
      i++; continue;
    }
    if (ch === '/' && next === '/') { inLineComment = true; i += 2; continue; }
    if (ch === '/' && next === '*') { inBlockComment = true; i += 2; continue; }
    if (ch === "'") inSingle = true;
    else if (ch === '"') inDouble = true;
    else if (ch === '`') inTemplate = true;
    out += ch;
    i++;
  }
  return out;
}

function countCodeLines(source) {
  let code = 0;
  for (const line of source.split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) continue;
    code++;
  }
  return code;
}

const VAGUE_NAME_RE = /(?:^|[-_.])(?:utils?|helpers?|common|misc)\.[a-z0-9]+$/i;
const VAGUE_NAME_ALLOWLIST = new Set([
  'parent-ui/src/lib/utils.ts', // shadcn/ui 标配 cn() 工具入口
]);

const MAX_LINES_ALLOWLIST = new Set([
  'parent-ui/src/components/ui/sidebar.tsx', // shadcn/ui 原生侧边栏原语
]);

function checkNameAndLines(rel, source, maxLines) {
  if (!VAGUE_NAME_ALLOWLIST.has(rel) && VAGUE_NAME_RE.test(basename(rel))) {
    failures.push(`[no-vague-name] ${rel} 禁止使用 utils/helpers/common/misc 模糊命名，请按领域概念命名`);
  }
  if (!MAX_LINES_ALLOWLIST.has(rel)) {
    const codeLines = countCodeLines(source);
    if (codeLines > maxLines) {
      failures.push(`[max-lines] ${rel} 纯代码行数 ${codeLines} 超出上限 ${maxLines}，请按职责拆分模块`);
    }
  }
}

// 1, 2, 5, 6. 检查 parent-ui/src 的 TS/TSX
const HEX_ALLOWLIST = new Set([
  'parent-ui/src/components/bank/question-image.ts', // Canvas 导出 JPEG 填充白底
  'parent-ui/src/FeedbackPanel.tsx',                 // 电视端深/浅主题预览小样色卡
  'parent-ui/src/main.tsx',                          // 浏览器 <meta name="theme-color">
]);
const HEX_STRING_RE = /['"`][^'"`\n]*#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b[^'"`\n]*['"`]/;

const uiFiles = walk(
  join(parentUiDir, 'src'),
  (_, name) => /\.(ts|tsx)$/.test(name) && !name.endsWith('.test.ts') && !name.endsWith('.test.tsx')
);

for (const file of uiFiles) {
  const rel = relative(repoRoot, file).replace(/\\/g, '/');
  const raw = readFileSync(file, 'utf8');
  checkNameAndLines(rel, raw, rel.endsWith('.tsx') ? 400 : 300);

  const clean = stripJsComments(raw);
  const lines = clean.split('\n');
  lines.forEach((line, idx) => {
    if (/\brandomUUID\s*\(/.test(line)) {
      failures.push(`[no-random-uuid] ${rel}:${idx + 1} 禁止在局域网纯 HTTP 前端调用 randomUUID()，非安全上下文会白屏`);
    }
    if (!HEX_ALLOWLIST.has(rel) && HEX_STRING_RE.test(line)) {
      failures.push(`[no-hardcoded-hex] ${rel}:${idx + 1} 禁止手写 hex 色值，请使用 parent-ui/src/theme.css Token 或 color-mix()`);
    }
  });
}

// 3, 5, 6. 校验 app/web/assets/js/ 严格 ES5 与行数（排除第三方 *.min.js）
const webJsDir = join(repoRoot, 'app/web/assets/js');
const webJsFiles = walk(webJsDir, (_, name) => name.endsWith('.js') && !name.endsWith('.min.js'));
for (const file of webJsFiles) {
  const rel = relative(repoRoot, file).replace(/\\/g, '/');
  const code = readFileSync(file, 'utf8');
  checkNameAndLines(rel, code, 400);
  try {
    parseJs(code, { ecmaVersion: 5, sourceType: 'script' });
  } catch (err) {
    const loc = err?.loc ? `${err.loc.line}:${err.loc.column}` : '?';
    failures.push(`[app-web-es5] ${rel}:${loc} 出现非 ES5 语法（老旧电视 WebView 会白屏）：${err.message}`);
  }
}

// 4. 校验 :parent 家长端零敏感权限红线
const parentManifest = join(repoRoot, 'app/native/parent/src/main/AndroidManifest.xml');
if (existsSync(parentManifest)) {
  const xml = readFileSync(parentManifest, 'utf8');
  const allowed = new Set(['android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE']);
  for (const m of xml.matchAll(/<uses-permission\b[^>]*android:name="([^"]+)"/g)) {
    const perm = m[1];
    if (!allowed.has(perm)) {
      failures.push(`[parent-minimal-perms] app/native/parent/src/main/AndroidManifest.xml 禁止声明敏感权限：${perm}`);
    }
  }
}

// 5 & 6. 校验 Android Kotlin 业务源文件行数与命名
const ktFiles = [
  ...walk(join(repoRoot, 'app/native/app/src/main/java'), (_, name) => name.endsWith('.kt')),
  ...walk(join(repoRoot, 'app/native/parent/src/main/java'), (_, name) => name.endsWith('.kt')),
];
for (const file of ktFiles) {
  const rel = relative(repoRoot, file).replace(/\\/g, '/');
  const raw = readFileSync(file, 'utf8');
  checkNameAndLines(rel, raw, 450);
}

if (failures.length > 0) {
  console.error('repo-guard 检查未通过：');
  for (const msg of failures) console.error('  ✗', msg);
  process.exit(1);
}

console.log(`PASS repo-guard (${uiFiles.length} UI, ${webJsFiles.length} ES5, ${ktFiles.length} Kotlin, parent perms)`);
