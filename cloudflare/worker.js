const HOST_ID = /^[a-f0-9]{12}$/;
const PAIR_CODE = /^[0-9]{8}$/;
const MAX_SIGNAL = 64 * 1024;

async function secretHash(secret) {
  const bytes = new TextEncoder().encode(secret);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), byte =>
    byte.toString(16).padStart(2, '0')).join('');
}

const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store' },
});

export default {
  fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health') return json({ ok: true });
    if (url.pathname !== '/signal' || request.headers.get('Upgrade') !== 'websocket') {
      return json({ error: 'Not found' }, 404);
    }
    const role = url.searchParams.get('role');
    const id = url.searchParams.get('id');
    const code = url.searchParams.get('code');
    if (role !== 'host' && role !== 'phone') return json({ error: 'Invalid role' }, 400);
    if (role === 'host' && (!HOST_ID.test(id || '')
        || !/^Bearer [a-f0-9]{64}$/.test(request.headers.get('Authorization') || ''))) {
      return json({ error: 'Invalid host credentials' }, 400);
    }
    if (role === 'phone' && !HOST_ID.test(id || '') && !PAIR_CODE.test(code || '')) {
      return json({ error: 'Enter a device ID or pairing code' }, 400);
    }
    const hub = env.HUB.get(env.HUB.idFromName('dsh-remote-hub'));
    return hub.fetch(request);
  },
};

export class SignalHub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  sockets(role, id) {
    return this.ctx.getWebSockets(role).filter(socket =>
      socket.deserializeAttachment()?.id === id);
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get('role');
    let id = url.searchParams.get('id');
    const code = url.searchParams.get('code');
    if (role === 'host') {
      const secret = request.headers.get('Authorization').slice(7);
      const stored = await this.ctx.storage.get(`host:${id}`);
      const digest = await secretHash(secret);
      if (stored && stored !== digest) return json({ error: 'Host identity changed' }, 403);
      if (!stored) await this.ctx.storage.put(`host:${id}`, digest);
      for (const old of this.sockets('host', id)) old.close(4000, 'Reconnected');
    } else if (role === 'phone') {
      const address = request.headers.get('CF-Connecting-IP') || 'unknown';
      const key = `attempt:${address}`;
      const previous = await this.ctx.storage.get(key);
      const now = Date.now();
      const attempts = previous && now - previous.since < 60_000
        ? previous : { since: now, count: 0 };
      if (++attempts.count > 10) return json({ error: 'Too many attempts' }, 429);
      await this.ctx.storage.put(key, attempts);
      if (code) {
        const lookup = await this.ctx.storage.get(`code:${code}`);
        if (!lookup || lookup.expires < Date.now()) {
          return json({ error: 'Pairing code is unavailable' }, 404);
        }
        id = lookup.id;
      }
      if (!this.sockets('host', id).length || this.sockets('phone', id).length) {
        return json({ error: 'PC offline or already connecting' }, 409);
      }
    } else return json({ error: 'Invalid role' }, 400);

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [role]);
    server.serializeAttachment({ role, id, accepted: role === 'host', code: code || null });
    if (role === 'phone') {
      server.send(JSON.stringify({ type: 'connected', id }));
    } else server.send(JSON.stringify({ type: 'registered', id }));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, raw) {
    if (typeof raw !== 'string' || raw.length > MAX_SIGNAL) {
      socket.close(1009, 'Signal too large');
      return;
    }
    const sender = socket.deserializeAttachment();
    if (!sender) return;
    let message;
    try { message = JSON.parse(raw); } catch { return; }
    if (sender.role === 'host' && message.type === 'pair-code') {
      if (message.code === null) {
        const oldCode = await this.ctx.storage.get(`current:${sender.id}`);
        if (oldCode) await this.ctx.storage.delete(`code:${oldCode}`);
        await this.ctx.storage.delete(`current:${sender.id}`);
        return;
      }
      if (!PAIR_CODE.test(message.code || '')) return;
      const reserved = await this.ctx.storage.get(`code:${message.code}`);
      if (reserved && reserved.id !== sender.id && reserved.expires > Date.now()) {
        socket.send(JSON.stringify({ type: 'error', error: 'Pairing code collision' }));
        return;
      }
      const oldCode = await this.ctx.storage.get(`current:${sender.id}`);
      if (oldCode) await this.ctx.storage.delete(`code:${oldCode}`);
      await this.ctx.storage.put(`current:${sender.id}`, message.code);
      await this.ctx.storage.put(`code:${message.code}`, {
        id: sender.id, expires: Date.now() + 5 * 60_000,
      });
      return;
    }
    if (sender.role === 'phone' && message.type === 'join') {
      if (sender.accepted || sender.joined) return;
      if (sender.code) {
        const lookup = await this.ctx.storage.get(`code:${sender.code}`);
        if (!lookup || lookup.id !== sender.id || lookup.expires < Date.now()
            || message.code !== sender.code) {
          socket.close(4003, 'Pairing code expired');
          return;
        }
      } else if (!/^[a-f0-9]{64}$/.test(message.proof || '')) {
        socket.close(4003, 'Invalid device proof');
        return;
      }
      if (!/^[a-f0-9]{32}$/.test(message.nonce || '')) {
        socket.close(4003, 'Invalid connection challenge');
        return;
      }
      if (typeof message.publicKey !== 'string' || message.publicKey.length > 128
          || !/^[A-Za-z0-9+/=]+$/.test(message.publicKey)) {
        socket.close(4003, 'Invalid connection key');
        return;
      }
      socket.serializeAttachment({ ...sender, joined: true });
      const host = this.sockets('host', sender.id)[0];
      if (!host) { socket.close(4004, 'PC disconnected'); return; }
      host.send(JSON.stringify({ type: 'join-request', code: sender.code,
        proof: sender.code ? null : message.proof, nonce: message.nonce,
        publicKey: message.publicKey }));
      return;
    }
    const otherRole = sender.role === 'host' ? 'phone' : 'host';
    const other = this.sockets(otherRole, sender.id)[0];
    if (!other) return;
    if (sender.role === 'host' && message.type === 'join-decision') {
      if (typeof message.accept !== 'boolean'
          || !other.deserializeAttachment()?.joined
          || other.deserializeAttachment()?.accepted) return;
      if (!message.accept) { other.close(4003, 'PC rejected connection'); return; }
      if (typeof message.certificate !== 'string'
          || message.certificate.length > 8192
          || typeof message.signature !== 'string'
          || message.signature.length > 256
          || typeof message.publicKey !== 'string'
          || message.publicKey.length > 128) {
        other.close(4003, 'Invalid PC identity');
        return;
      }
      const attachment = other.deserializeAttachment();
      other.serializeAttachment({ ...attachment, accepted: true });
      socket.send(JSON.stringify({ type: 'ready', id: sender.id }));
      other.send(JSON.stringify({ type: 'ready', id: sender.id,
        certificate: message.certificate, signature: message.signature,
        publicKey: message.publicKey }));
      return;
    }
    if (!sender.accepted || !other.deserializeAttachment()?.accepted
        || message.type !== 'data'
        || !Number.isSafeInteger(message.seq) || message.seq < 0
        || typeof message.payload !== 'string' || message.payload.length > 48 * 1024) return;
    other.send(raw);
  }

  webSocketClose(socket) {
    const sender = socket.deserializeAttachment();
    if (sender?.role === 'host') {
      for (const phone of this.sockets('phone', sender.id)) {
        phone.close(4004, 'PC disconnected');
      }
    }
  }
}
