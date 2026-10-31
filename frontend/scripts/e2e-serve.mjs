// Serves an `expo export --platform web` bundle for the E2E run: node scripts/e2e-serve.mjs <dir> <port>.
// `expo serve` answers 404 for every dynamic route (/book/lumen-studio, /b/<id>, /clients/<id>) and for unknown URLs,
// because the static export only holds book/[slug].html, b/[id].html, ... and +not-found.html. A real host rewrites to
// those files; so does this server: an exact file or <path>.html first, then the [param] file of the directory, and
// +not-found.html with status 404 when nothing matches.
import { createServer } from 'node:http';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';

const [dir, port] = process.argv.slice(2);
if (!dir || !port) {
  console.error('Usage: node scripts/e2e-serve.mjs <dir> <port>');
  process.exit(2);
}
const root = path.resolve(dir);
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.ttf': 'font/ttf',
  '.woff2': 'font/woff2',
};

const read = (file) => readFile(file).then((body) => ({ file, body }), () => null);

async function find(base, segments) {
  if (segments.length === 0) return read(path.join(base, 'index.html'));
  const [head, ...rest] = segments;
  const names = await readdir(base).catch(() => []);
  const params = names.filter((name) => /^\[[^\]]+\]/.test(name));
  if (rest.length === 0) {
    const files = [head, `${head}.html`, path.join(head, 'index.html'), ...params.filter((name) => name.endsWith('.html'))];
    for (const file of files) {
      if (!names.includes(file.split(path.sep)[0])) continue;
      const found = await read(path.join(base, file));
      if (found) return found;
    }
    return null;
  }
  for (const name of [head, ...params.filter((param) => !param.endsWith('.html'))]) {
    if (!names.includes(name)) continue;
    const found = await find(path.join(base, name), rest);
    if (found) return found;
  }
  return null;
}

createServer(async (req, res) => {
  const segments = decodeURI(new URL(req.url ?? '/', 'http://localhost').pathname).split('/').filter(Boolean);
  let found = segments.includes('..') ? null : await find(root, segments);
  let status = 200;
  if (!found || path.extname(found.file) === '') {
    found = await read(path.join(root, '+not-found.html'));
    status = 404;
  }
  res.writeHead(status, { 'Content-Type': TYPES[path.extname(found?.file ?? '')] ?? 'application/octet-stream' });
  res.end(found?.body ?? 'Not Found');
}).listen(Number(port), () => console.log(`Serving ${root} on http://localhost:${port}`));
