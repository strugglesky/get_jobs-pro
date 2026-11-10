// Local stand-in for the robot text webhook. No real credentials or outbound requests.
const http = require('node:http');

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  if (req.method !== 'POST' || !['/cgi-bin/webhook/send', '/cgi-bin/webhook/upload_media'].includes(url.pathname)) {
    res.writeHead(501);
    return res.end(JSON.stringify({ error: 'This mock models only robot text messages' }));
  }
  const respond = (payload) => {
    if (url.pathname.endsWith('/upload_media')) {
      if (url.searchParams.get('type') !== 'file') {
        return res.end(JSON.stringify({ errcode: 40058, errmsg: 'invalid file upload' }));
      }
      return res.end(JSON.stringify({ errcode: 0, errmsg: 'ok', type: 'file', media_id: 'local-test-media-id' }));
    }
    if (payload.msgtype === 'file' && payload.file?.media_id === 'local-test-media-id') {
      return res.end(JSON.stringify({ errcode: 0, errmsg: 'ok' }));
    }
    if (payload.msgtype !== 'text' || typeof payload.text?.content !== 'string' ||
        Buffer.byteLength(payload.text.content, 'utf8') > 2048 || !url.searchParams.get('key')) {
      return res.end(JSON.stringify({ errcode: 40058, errmsg: 'invalid text payload' }));
    }
    switch (url.searchParams.get('case')) {
      case 'rate-limit': return res.end(JSON.stringify({ errcode: 45009, errmsg: 'rate limited' }));
      case 'invalid-key': return res.end(JSON.stringify({ errcode: 93000, errmsg: 'invalid webhook key' }));
      case 'http-error': res.writeHead(503); return res.end(JSON.stringify({ error: 'unavailable' }));
      case 'malformed': return res.end('not-json');
      case 'timeout': return setTimeout(() => res.end(JSON.stringify({ errcode: 0, errmsg: 'ok' })), 6500);
      default: return res.end(JSON.stringify({ errcode: 0, errmsg: 'ok' }));
    }
  };
  if (req.body) return respond(req.body);
  let raw = '';
  req.on('data', chunk => raw += chunk);
  req.on('end', () => {
    if (url.pathname.endsWith('/upload_media')) return respond({ multipart: raw });
    try { respond(JSON.parse(raw)); }
    catch { res.writeHead(400); res.end(JSON.stringify({ error: 'invalid JSON' })); }
  });
}).listen(process.env.PORT || 4500);
