import assert from 'node:assert/strict';
import { createHash, createECDH, randomBytes, verify, X509Certificate } from 'node:crypto';
import { once } from 'node:events';
import https from 'node:https';
import WebSocket from 'ws';
import { createIdentity } from '../tls-identity.js';
import { secureChannel, startRelayHost } from '../remote-relay.js';

process.env.NODE_ENV = 'test';
const signalUrl = process.env.SIGNAL_URL || 'ws://127.0.0.1:8790/signal';
const token = randomBytes(32).toString('hex');
const hostId = createHash('sha256').update(token).digest('hex').slice(0, 12);
const identity = createIdentity(hostId);
const code = String(Math.floor(10000000 + Math.random() * 90000000));
const nonce = randomBytes(16).toString('hex');
const ecdh = createECDH('prime256v1');
const phonePublicKey = ecdh.generateKeys().toString('base64');
const server = https.createServer({ key: identity.key,
  cert: identity.certificate }, (request, response) => {
  assert.equal(request.headers.authorization, `Bearer ${token}`);
  response.writeHead(200);
  response.end(JSON.stringify({ ok: true }));
});
server.listen(0, '127.0.0.1');
await once(server, 'listening');

let status = '';
const host = startRelayHost({ pairing: {
  token, hostId, code, enabled: true,
  async claim(supplied) { return supplied === code; },
}, tlsIdentity: identity, port: server.address().port,
signalUrl, onStatus: value => { status = value; } });

try {
  for (let i = 0; i < 50 && status !== 'online'; i++) {
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.equal(status, 'online');
  await new Promise(resolve => setTimeout(resolve, 100));
  const phone = new WebSocket(`${signalUrl}?role=phone&code=${code}`);
  await once(phone, 'open');
  let channel;
  let body = '';
  let responseStatus = 0;
  const buffered = [];
  let finish;
  const completed = new Promise((resolve, reject) => { finish = { resolve, reject }; });
  function receive(message) {
    if (!channel) buffered.push(message);
    else channel.receive(message);
  }
  phone.on('message', raw => {
    try {
      const message = JSON.parse(raw.toString());
      if (message.type === 'connected') {
        assert.equal(message.id, hostId);
        phone.send(JSON.stringify({ type: 'join', code, nonce, publicKey: phonePublicKey }));
      } else if (message.type === 'ready') {
        const certificate = new X509Certificate(message.certificate);
        assert.equal(createHash('sha256').update(certificate.raw).digest('hex'),
          identity.fingerprint);
        assert.ok(verify('sha256', Buffer.from(
          `${nonce}\n${message.publicKey}\n${phonePublicKey}`),
        certificate.publicKey, Buffer.from(message.signature, 'base64')));
        const secret = ecdh.computeSecret(Buffer.from(message.publicKey, 'base64'));
        const key = createHash('sha256').update('dsh-remote-e2e-v1')
          .update(secret).update(Buffer.from(nonce, 'hex')).digest();
        channel = secureChannel(phone, key, 2);
        channel.onmessage = event => {
          const frame = JSON.parse(event.data);
          if (frame.type === 'paired') {
            assert.equal(frame.token, token);
            channel.send(JSON.stringify({ type: 'request', id: 'abc',
              method: 'GET', path: '/v1/health' }));
            channel.send(JSON.stringify({ type: 'end', id: 'abc' }));
          } else if (frame.type === 'response') responseStatus = frame.status;
          else if (frame.type === 'chunk') {
            body += Buffer.from(frame.data, 'base64').toString();
          } else if (frame.type === 'end') finish.resolve();
        };
        for (const frame of buffered) receive(frame);
        buffered.length = 0;
      } else if (message.type === 'data') receive(message);
    } catch (error) { finish.reject(error); }
  });
  await Promise.race([completed,
    new Promise((_, reject) => setTimeout(() => reject(new Error('Relay timeout')), 15_000))]);
  assert.equal(responseStatus, 200);
  assert.deepEqual(JSON.parse(body), { ok: true });
  phone.close();
  console.log('Encrypted relay and local API smoke test passed');
} finally {
  host.stop();
  server.close();
}
