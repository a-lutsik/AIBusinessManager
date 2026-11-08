// Stub of the Telegram Bot API for the E2E run (no real Telegram call is ever made): records every sendMessage and answers
// ok. GET /__sent lists what was received, [{ chat_id, text }].
// Run: node scripts/telegram-stub.mjs <port>
import http from 'node:http';

const sent = [];
http
  .createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/__sent') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(sent));
      return;
    }
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      try {
        const { chat_id, text } = JSON.parse(body);
        sent.push({ chat_id: String(chat_id), text });
        res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true,"result":{}}');
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' }).end('{"ok":false,"description":"bad request"}');
      }
    });
  })
  .listen(Number(process.argv[2]), '127.0.0.1');
