'use strict';
// Throwaway two-root static server for the login-form Story testbed.
// Source HTML from the testbed; compiled main.js from shadow output.
const http = require('http');
const fs = require('fs');
const path = require('path');
const repo = path.resolve(__dirname, '..', '..', '..', '..', '..');
const roots = [
  path.join(repo, 'tools', 'story', 'testbeds', 'login_form'),
  path.join(repo, 'implementation', 'out', 'examples', 'login-form'),
];
const port = Number(process.env.PORT || 8766);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.map': 'application/json',
  '.css': 'text/css',
  '.edn': 'text/plain',
};
function find(rel) {
  const clean = String(rel || '/').replace(/\\/g, '/').split('?')[0];
  const name = (clean === '/' ? 'index.html' : clean.replace(/^\//, ''));
  for (const root of roots) {
    const full = path.join(root, name);
    if (fs.existsSync(full) && fs.statSync(full).isFile()) return full;
  }
  return null;
}
const server = http.createServer((req, res) => {
  const file = find(req.url || '/');
  if (!file) {
    res.writeHead(404);
    res.end('not found ' + req.url);
    return;
  }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
server.listen(port, '127.0.0.1', () => {
  console.log('listening http://127.0.0.1:' + port + '/#/stories');
});
