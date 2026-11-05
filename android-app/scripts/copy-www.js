// 把仓库根目录的 web 资产同步到 android-app/www（唯一来源，避免两份手改）
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const dest = path.resolve(__dirname, '..', 'www');

const files = ['index.html', 'manifest.webmanifest', 'sw.js'];
const dirs = ['icons'];

fs.mkdirSync(dest, { recursive: true });

for (const f of files) {
  fs.copyFileSync(path.join(root, f), path.join(dest, f));
}

for (const d of dirs) {
  const from = path.join(root, d);
  const to = path.join(dest, d);
  fs.rmSync(to, { recursive: true, force: true });
  fs.mkdirSync(to, { recursive: true });
  for (const f of fs.readdirSync(from)) {
    if (f.endsWith('.png')) fs.copyFileSync(path.join(from, f), path.join(to, f));
  }
}

console.log('[copy-www] web 资产已同步到 www/');
