import assert from 'node:assert/strict';
import { once } from 'node:events';
import https from 'node:https';
import test from 'node:test';
import { attachApiChannel } from './remote-relay.js';
import { createIdentity } from './tls-identity.js';

test('data channel forwards an authenticated request to the local API', async () => {
  const identity = createIdentity('0123456789ab');
  const server = https.createServer({ key: identity.key,
    cert: identity.certificate }, (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer test-token');
    assert.equal(request.url, '/v1/health');
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ status: 'ok' }));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const frames = [];
  const channel = { readyState: 'open', bufferedAmount: 0,
    send(data) { frames.push(JSON.parse(data)); },
    close() { this.readyState = 'closed'; } };
  attachApiChannel(channel, 'test-token', server.address().port);
  try {
    channel.onmessage({ data: JSON.stringify({ type: 'request', id: 'abc',
      method: 'GET', path: '/v1/health' }) });
    channel.onmessage({ data: JSON.stringify({ type: 'end', id: 'abc' }) });
    for (let i = 0; i < 50 && !frames.some(frame => frame.type === 'end'); i++) {
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    assert.equal(frames[0].status, 200);
    const body = Buffer.concat(frames.filter(frame => frame.type === 'chunk')
      .map(frame => Buffer.from(frame.data, 'base64'))).toString();
    assert.deepEqual(JSON.parse(body), { status: 'ok' });
    assert.equal(frames.at(-1).type, 'end');
  } finally { server.close(); }
});

test('data channel rejects paths outside the plugin API', () => {
  const channel = { readyState: 'open', send() {},
    close() { this.readyState = 'closed'; } };
  attachApiChannel(channel, 'token', 19390);
  channel.onmessage({ data: JSON.stringify({ type: 'request', id: 'abc',
    method: 'GET', path: '/api/private' }) });
  assert.equal(channel.readyState, 'closed');
});
