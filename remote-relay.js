import https from 'node:https';
import { execFileSync } from 'node:child_process';
import { createHash, createHmac, createECDH, createCipheriv, createDecipheriv,
  sign, timingSafeEqual } from 'node:crypto';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { WebSocket, HttpsProxyAgent } = require('./vendor/relay-deps.cjs');
const MAX_BODY = 12 * 1024 * 1024;
const CHUNK_SIZE = 8192;
const RECONNECT_MS = 5000;

export function relayProxyUrl(environment = process.env, platform = process.platform,
  readWindowsProxy = () => execFileSync('reg', ['query',
    'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings',
    '/v', 'ProxyEnable'], { encoding: 'utf8', timeout: 2000 }) + '\n' +
    execFileSync('reg', ['query',
      'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings',
      '/v', 'ProxyServer'], { encoding: 'utf8', timeout: 2000 })) {
  const fromEnvironment = environment.HTTPS_PROXY || environment.https_proxy;
  if (fromEnvironment) return fromEnvironment;
  if (platform !== 'win32') return null;
  try {
    const settings = readWindowsProxy();
    if (!/ProxyEnable\s+REG_DWORD\s+0x1\b/i.test(settings)) return null;
    const value = settings.match(/ProxyServer\s+REG_SZ\s+([^\r\n]+)/i)?.[1]?.trim();
    if (!value) return null;
    const entries = value.includes('=') ? value.split(';') : [value];
    const entry = entries.find(item => /^https=/i.test(item))
      || entries.find(item => /^http=/i.test(item))
      || (value.includes('=') ? null : entries[0]);
    if (!entry) return null;
    const address = entry.replace(/^(?:https?)=/i, '').trim();
    if (!/^[a-z0-9.-]+:[0-9]{1,5}$/i.test(address)) return null;
    return `http://${address}`;
  } catch { return null; }
}

function validProof(token, nonce, proof) {
  if (typeof proof !== 'string') return false;
  const x = Buffer.from(createHmac('sha256', token).update(nonce).digest('hex'));
  const y = Buffer.from(proof);
  return x.length === y.length && timingSafeEqual(x, y);
}

function signal(socket, message) {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function sendChunked(channel, id, response) {
  if (channel.readyState !== 'open') return;
  const frame = JSON.stringify({ type: 'response', id,
    status: response.statusCode || 502 });
  channel.send(frame);
  let bytes = 0;
  response.on('data', async chunk => {
    response.pause();
    bytes += chunk.length;
    if (bytes > MAX_BODY) {
      response.destroy();
      channel.close();
      return;
    }
    for (let offset = 0; offset < chunk.length; offset += CHUNK_SIZE) {
      if (channel.readyState !== 'open') break;
      channel.send(JSON.stringify({ type: 'chunk', id,
        data: chunk.subarray(offset, offset + CHUNK_SIZE).toString('base64') }));
      while (channel.bufferedAmount > 256 * 1024) {
        await new Promise(resolve => setTimeout(resolve, 10));
      }
    }
    response.resume();
  });
  response.on('end', () => {
    if (channel.readyState === 'open') channel.send(JSON.stringify({ type: 'end', id }));
  });
  response.on('error', () => {
    if (channel.readyState === 'open') channel.send(JSON.stringify({ type: 'end', id }));
  });
}

export function attachApiChannel(channel, token, port) {
  const requests = new Map();
  channel.onmessage = ({ data }) => {
    let frame;
    try { frame = JSON.parse(data.toString()); } catch { channel.close(); return; }
    const id = frame?.id;
    if (typeof id !== 'string' || !/^[a-f0-9]{1,32}$/.test(id)) {
      channel.close(); return;
    }
    if (frame.type === 'request') {
      if (requests.has(id) || requests.size >= 8
          || !['GET', 'POST', 'DELETE'].includes(frame.method)
          || typeof frame.path !== 'string' || !frame.path.startsWith('/v1/')
          || frame.path.length > 2048 || frame.path.includes('://')) {
        channel.close(); return;
      }
      requests.set(id, { method: frame.method, path: frame.path, chunks: [], size: 0 });
      return;
    }
    const request = requests.get(id);
    if (!request) { channel.close(); return; }
    if (frame.type === 'chunk') {
      if (typeof frame.data !== 'string' || frame.data.length > CHUNK_SIZE * 2) {
        channel.close(); return;
      }
      const bytes = Buffer.from(frame.data, 'base64');
      request.size += bytes.length;
      if (request.size > MAX_BODY) { channel.close(); return; }
      request.chunks.push(bytes);
      return;
    }
    if (frame.type !== 'end') { channel.close(); return; }
    requests.delete(id);
    const body = Buffer.concat(request.chunks);
    const local = https.request({ hostname: '127.0.0.1', port,
      path: request.path, method: request.method, rejectUnauthorized: false,
      timeout: 120_000,
      headers: { Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': body.length } }, response => sendChunked(channel, id, response));
    local.on('timeout', () => local.destroy(new Error('Local request timed out')));
    local.on('error', () => {
      if (channel.readyState === 'open') {
        channel.send(JSON.stringify({ type: 'response', id, status: 502 }));
        channel.send(JSON.stringify({ type: 'end', id }));
      }
    });
    local.end(body);
  };
  channel.onclose = () => requests.clear();
}

export function secureChannel(socket, key, senderSide) {
  let sent = 0;
  let received = 0;
  const channel = {
    get readyState() { return socket.readyState === WebSocket.OPEN ? 'open' : 'closed'; },
    get bufferedAmount() { return socket.bufferedAmount; },
    onmessage: null,
    onclose: null,
    close() { socket.close(); },
    send(data) {
      if (++sent > Number.MAX_SAFE_INTEGER) throw new Error('Relay sequence exhausted');
      const iv = Buffer.alloc(12);
      iv[0] = senderSide;
      iv.writeBigUInt64BE(BigInt(sent), 4);
      const cipher = createCipheriv('aes-256-gcm', key, iv);
      const payload = Buffer.concat([cipher.update(data, 'utf8'), cipher.final(),
        cipher.getAuthTag()]);
      signal(socket, { type: 'data', seq: sent, payload: payload.toString('base64') });
    },
    receive(message) {
      if (message.seq !== received + 1 || typeof message.payload !== 'string') {
        socket.close(); return;
      }
      const bytes = Buffer.from(message.payload, 'base64');
      if (bytes.length < 16) { socket.close(); return; }
      const iv = Buffer.alloc(12);
      iv[0] = senderSide === 1 ? 2 : 1;
      iv.writeBigUInt64BE(BigInt(message.seq), 4);
      try {
        const decipher = createDecipheriv('aes-256-gcm', key, iv);
        decipher.setAuthTag(bytes.subarray(bytes.length - 16));
        const plaintext = Buffer.concat([decipher.update(bytes.subarray(0, -16)),
          decipher.final()]).toString('utf8');
        received++;
        channel.onmessage?.({ data: plaintext });
      } catch { socket.close(); }
    },
  };
  return channel;
}

export function startRelayHost({ pairing, tlsIdentity, port, signalUrl, onStatus = () => {} }) {
  const localTest = process.env.NODE_ENV === 'test'
    && /^ws:\/\/127\.0\.0\.1:[0-9]+\/signal$/.test(signalUrl);
  if (!localTest && !/^wss:\/\/[a-z0-9.-]+(?:\:[0-9]+)?\/signal$/i.test(signalUrl)) {
    throw new Error('Signal URL must be a WSS /signal endpoint');
  }
  const hostSecret = createHash('sha256')
    .update(`dsh-remote-relay-v1:${pairing.token}`).digest('hex');
  const target = new URL(signalUrl);
  target.searchParams.set('role', 'host');
  target.searchParams.set('id', pairing.hostId);
  let socket;
  let channel;
  let stopped = false;
  let reconnect;
  let latestCode;

  function publishCode(force = false) {
    const code = pairing.enabled ? pairing.code : null;
    if (force || code !== latestCode) {
      latestCode = code;
      signal(socket, { type: 'pair-code', code });
    }
  }
  async function acceptJoin(message) {
    channel = null;
    const nonce = message.nonce;
    let accepted = false;
    let phoneKey;
    let ecdh;
    try {
      phoneKey = Buffer.from(message.publicKey, 'base64');
      if (phoneKey.length !== 65 || phoneKey[0] !== 4) throw new Error('Invalid key');
      ecdh = createECDH('prime256v1');
      ecdh.generateKeys();
      ecdh.computeSecret(phoneKey);
    } catch {
      signal(socket, { type: 'join-decision', accept: false });
      return;
    }
    if (/^[a-f0-9]{32}$/.test(nonce || '')
        && typeof message.publicKey === 'string') {
      if (message.code) {
        if (pairing.enabled && message.code === pairing.code) {
          accepted = await pairing.claim(message.code);
          publishCode();
        }
      } else accepted = validProof(pairing.token, nonce, message.proof);
    }
    if (!accepted) {
      signal(socket, { type: 'join-decision', accept: false });
      return;
    }
    const hostKey = ecdh.getPublicKey().toString('base64');
    const secret = ecdh.computeSecret(phoneKey);
    const key = createHash('sha256').update('dsh-remote-e2e-v1')
      .update(secret).update(Buffer.from(nonce, 'hex')).digest();
    channel = secureChannel(socket, key, 1);
    const signature = sign('sha256', Buffer.from(
      `${nonce}\n${hostKey}\n${message.publicKey}`), tlsIdentity.key)
      .toString('base64');
    signal(socket, { type: 'join-decision', accept: true,
      certificate: tlsIdentity.certificate, signature, publicKey: hostKey });
  }
  async function onSignal(message) {
    if (message.type === 'registered') {
      onStatus('online');
      latestCode = undefined;
      publishCode();
      return;
    }
    if (message.type === 'join-request') {
      await acceptJoin(message);
      return;
    }
    if (message.type === 'ready') {
      if (!channel) return;
      attachApiChannel(channel, pairing.token, port);
      channel.send(JSON.stringify({ type: 'paired', token: pairing.token }));
      onStatus('connected');
      return;
    }
    if (message.type === 'data') channel?.receive(message);
  }
  function connect() {
    if (stopped) return;
    const proxyUrl = relayProxyUrl();
    socket = new WebSocket(target.href, {
      headers: { Authorization: `Bearer ${hostSecret}` },
      handshakeTimeout: 10_000,
      ...(proxyUrl ? { agent: new HttpsProxyAgent(proxyUrl) } : {}),
    });
    socket.on('message', raw => {
      try { Promise.resolve(onSignal(JSON.parse(raw.toString())))
        .catch(error => onStatus(`error:${error.message}`)); }
      catch { onStatus('error:invalid signal'); }
    });
    socket.on('error', error => onStatus(`error:${error.message}`));
    socket.on('close', () => {
      channel?.onclose?.();
      channel = null;
      onStatus('offline');
      if (!stopped) reconnect = setTimeout(connect, RECONNECT_MS);
    });
  }
  connect();
  const refresh = setInterval(() => publishCode(true), 60_000);
  return { refresh: publishCode, stop() {
    stopped = true;
    clearInterval(refresh);
    clearTimeout(reconnect);
    channel = null;
    socket?.close();
  } };
}
