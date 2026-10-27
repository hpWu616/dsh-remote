import assert from 'node:assert/strict';
import WebSocket from 'ws';

const base = process.env.SIGNAL_URL ?? 'ws://127.0.0.1:8790';
const hostId = '0123456789ab';
const hostSecret = 'a'.repeat(64);
const pairingCode = '12345678';

function connect(path, options) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${base}${path}`, options);
    socket.once('open', () => resolve(socket));
    socket.once('error', reject);
    socket.once('unexpected-response', (_request, response) =>
      reject(new Error(`Unexpected HTTP status ${response.statusCode}`)));
  });
}

function next(socket) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Signal timeout')), 5000);
    socket.once('message', data => {
      clearTimeout(timer);
      resolve(JSON.parse(data.toString()));
    });
  });
}

const host = await connect(`/signal?role=host&id=${hostId}`, {
  headers: { Authorization: `Bearer ${hostSecret}` },
});
try {
  assert.deepEqual(await next(host), { type: 'registered', id: hostId });
  host.send(JSON.stringify({ type: 'pair-code', code: pairingCode }));
  await new Promise(resolve => setTimeout(resolve, 100));
  const phone = await connect(`/signal?role=phone&code=${pairingCode}`);
  try {
    assert.deepEqual(await next(phone), { type: 'connected', id: hostId });
    phone.send(JSON.stringify({ type: 'join', code: pairingCode,
      nonce: 'b'.repeat(32), publicKey: 'c'.repeat(88) }));
    assert.deepEqual(await next(host), {
      type: 'join-request', code: pairingCode, proof: null, nonce: 'b'.repeat(32),
      publicKey: 'c'.repeat(88),
    });
    host.send(JSON.stringify({ type: 'join-decision', accept: true,
      certificate: 'cert', signature: 'sig', publicKey: 'key' }));
    const hostReady = await next(host);
    const phoneReady = await next(phone);
    assert.equal(hostReady.type, 'ready');
    assert.equal(phoneReady.id, hostId);
    assert.equal(phoneReady.certificate, 'cert');
    phone.send(JSON.stringify({ type: 'data', seq: 1, payload: 'test' }));
    assert.deepEqual(await next(host), { type: 'data', seq: 1, payload: 'test' });
  } finally { phone.close(); }
} finally { host.close(); }
console.log('Signal smoke test passed');
