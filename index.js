import https from 'node:https';
import { execFile, spawn } from 'node:child_process';
import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { once } from 'node:events';
import { mkdir, readdir, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, dirname, isAbsolute, parse } from 'node:path';
import { StringDecoder } from 'node:string_decoder';
import { promisify } from 'node:util';
import { lanInterfaces, lanNetworks, startDiscovery } from './discovery.js';
import { loadTlsIdentity } from './tls-identity.js';
import { startRelayHost } from './remote-relay.js';
import { createCloudflareOnboarding } from './cloudflare-onboarding.js';

const MAX_BODY = 8192;
const MAX_FILE_BODY = 12 * 1024 * 1024;
const MAX_OUTPUT = 1024 * 1024;
const TIMEOUT_MS = 120_000;
const PAIR_WINDOW_MS = 300_000;
const CREDENTIAL_KEY = 'dsh-remote-host/pairing';
const RELAY_KEY = 'dsh-remote-host/relay';
const execFileAsync = promisify(execFile);

async function driveRoots() {
  if (process.platform !== 'win32') return ['/'];
  const { stdout } = await execFileAsync('powershell.exe', ['-NoLogo', '-NoProfile',
    '-NonInteractive', '-Command', '[Environment]::GetLogicalDrives()'], {
    timeout: 5000, windowsHide: true,
  });
  return stdout.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
}

function newCode() {
  return String(randomInt(10_000_000, 100_000_000));
}

function authorized(header, token) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
  const supplied = Buffer.from(header.slice(7));
  const expected = Buffer.from(token);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

function reply(res, status, message) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store' });
  res.end(JSON.stringify({ error: message }));
}

function commandProcess(command, cwd) {
  if (process.platform === 'win32') {
    const script = '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n'
      + '$OutputEncoding = [System.Text.Encoding]::UTF8\n' + command;
    return spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive',
      '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')],
    { windowsHide: true, cwd, stdio: ['ignore', 'pipe', 'pipe'] });
  }
  return spawn('/bin/sh', ['-c', command], { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
}

export function createServer(token, pairing, workspaces, chats, schedules, tlsIdentity) {
  if (typeof token !== 'string' || token.length < 32) {
    throw new Error('Remote token must contain at least 32 characters');
  }
  if (!tlsIdentity?.key || !tlsIdentity?.certificate) {
    throw new Error('HTTPS identity is required');
  }
  const children = new Set();
  const attempts = new Map();
  const handler = async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (req.headers.origin) return reply(res, 403, 'Browser requests are not accepted');
    if (req.method === 'POST' && req.url === '/v1/pair') {
      if (pairing.enabled === false) return reply(res, 403, 'Pairing is disabled on this PC');
      const address = req.socket.remoteAddress || 'unknown';
      const previous = attempts.get(address);
      const state = previous && Date.now() - previous.since < PAIR_WINDOW_MS
        ? previous : { since: Date.now(), count: 0 };
      if (state.count >= 5) return reply(res, 429, 'Try pairing again later');
      let body = '';
      try {
        for await (const chunk of req) {
          body += chunk.toString('utf8');
          if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
        }
      } catch {
        return;
      }
      let code;
      try { code = JSON.parse(body).code; } catch { return reply(res, 400, 'Invalid JSON'); }
      let accepted = false;
      if (typeof code === 'string' && /^[0-9]{8}$/.test(code)) {
        try { accepted = await pairing.claim(code); }
        catch { return reply(res, 503, 'Pairing store unavailable'); }
      }
      if (!accepted) {
        state.count += 1;
        attempts.set(address, state);
        return reply(res, 401, 'Invalid pairing code');
      }
      attempts.delete(address);
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store' });
      return res.end(JSON.stringify({ token, protocol: 1 }));
    }
    if (!authorized(req.headers.authorization, token)) return reply(res, 401, 'Unauthorized');
    if (req.method === 'GET' && req.url === '/v1/health') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store' });
      return res.end(JSON.stringify({ status: 'ok', protocol: 1 }));
    }
    if (req.method === 'GET' && req.url === '/v1/workspaces') {
      if (!workspaces) return reply(res, 503, 'Workspace service unavailable');
      try {
        const items = workspaces.list();
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ items }));
      } catch { return reply(res, 503, 'Workspace service unavailable'); }
    }
    if (req.method === 'GET' && req.url?.startsWith('/v1/models')) {
      if (!chats?.models) return reply(res, 503, 'Model catalog unavailable');
      let address;
      try { address = new URL(req.url, 'https://localhost'); }
      catch { return reply(res, 400, 'Invalid model request'); }
      if (address.pathname !== '/v1/models') return reply(res, 404, 'Not found');
      const sessionId = address.searchParams.get('sessionId') || '';
      try {
        const models = await chats.models(sessionId);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify(models));
      } catch (error) { return reply(res, 503, error.message || 'Model catalog unavailable'); }
    }
    if (req.method === 'GET' && req.url === '/v1/modes') {
      if (!chats?.modes) return reply(res, 503, 'Modes unavailable');
      try {
        const items = await chats.modes();
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ items }));
      } catch (error) { return reply(res, 503, error.message || 'Modes unavailable'); }
    }
    const sessionMode = /^\/v1\/sessions\/([^/]+)\/mode$/.exec(req.url);
    if (sessionMode && req.method === 'GET') {
      if (!chats?.mode) return reply(res, 503, 'Mode unavailable');
      try {
        const mode = await chats.mode(sessionMode[1]);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ mode }));
      } catch (error) { return reply(res, 503, error.message || 'Mode unavailable'); }
    }
    const sessionSkills = /^\/v1\/sessions\/([^/]+)\/skills$/.exec(req.url);
    if (sessionSkills && req.method === 'GET') {
      if (!chats?.skills) return reply(res, 503, 'Skill catalog unavailable');
      try {
        const items = await chats.skills(sessionSkills[1]);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ items }));
      } catch (error) { return reply(res, 503, error.message || 'Skill catalog unavailable'); }
    }
    const sessionCommand = /^\/v1\/sessions\/([^/]+)\/commands$/.exec(req.url);
    if (sessionCommand && req.method === 'POST') {
      if (!chats?.command) return reply(res, 503, 'Commands unavailable');
      try {
        let body = '';
        for await (const chunk of req) {
          body += chunk.toString('utf8');
          if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
        }
        const { line } = JSON.parse(body);
        if (typeof line !== 'string' || line.length > 4000
            || !/^\/(goal|plan|feedback|permission)(\s|$)/.test(line)) {
          return reply(res, 400, 'Unsupported command');
        }
        const result = await chats.command(sessionCommand[1], line);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify(result));
      } catch (error) { return reply(res, 503, error.message || 'Command failed'); }
    }
    const sessionFile = /^\/v1\/sessions\/([^/]+)\/files$/.exec(req.url);
    if (sessionFile && req.method === 'POST') {
      if (!chats?.upload) return reply(res, 503, 'File uploads unavailable');
      try {
        let body = '';
        for await (const chunk of req) {
          body += chunk.toString('utf8');
          if (Buffer.byteLength(body) > MAX_FILE_BODY) return reply(res, 413, 'File too large');
        }
        const { data, name } = JSON.parse(body);
        if (typeof data !== 'string' || !/^[A-Za-z0-9+/]*={0,2}$/.test(data)
            || typeof name !== 'string' || !name.trim() || name.length > 180
            || /[\\/\x00-\x1f]/.test(name)) return reply(res, 400, 'Invalid file');
        const upload = await chats.upload(sessionFile[1], data, name);
        res.writeHead(201, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ receiptId: upload.receiptId }));
      } catch (error) { return reply(res, 503, error.message || 'Upload failed'); }
    }
    const sessionLog = /^\/v1\/sessions\/([^/]+)\/log$/.exec(req.url);
    if (sessionLog && req.method === 'GET') {
      if (!chats?.log) return reply(res, 503, 'Session log unavailable');
      try {
        const lines = await chats.log(sessionLog[1]);
        res.writeHead(200, { 'Content-Type': 'application/x-ndjson; charset=utf-8',
          'Content-Disposition': 'attachment; filename="dsh-session.jsonl"',
          'Cache-Control': 'no-store' });
        for (const line of lines) {
          if (!res.write(`${JSON.stringify(line)}\n`)) await once(res, 'drain');
        }
        return res.end();
      } catch (error) { return reply(res, 503, error.message || 'Log unavailable'); }
    }
    if (req.method === 'GET' && req.url?.startsWith('/v1/directories?')) {
      if (!workspaces?.browse) return reply(res, 503, 'Directory browser unavailable');
      const requested = new URL(req.url, 'https://localhost').searchParams.get('path') || '';
      if (requested.length > 2048) return reply(res, 400, 'Directory path too long');
      try {
        const directory = await workspaces.browse(requested);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify(directory));
      } catch (error) { return reply(res, 400, `Cannot open directory: ${error.message}`); }
    }
    if (req.method === 'POST' && req.url === '/v1/workspaces') {
      if (!workspaces) return reply(res, 503, 'Workspace service unavailable');
      let body = '';
      try {
        for await (const chunk of req) {
          body += chunk.toString('utf8');
          if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
        }
        const { name, path } = JSON.parse(body);
        if (typeof name !== 'string' || name.length > 80 || !name.trim()
            || /[\\/:*?"<>|\x00-\x1f]/.test(name) || name.endsWith('.')) {
          return reply(res, 400, 'Invalid workspace name');
        }
        if (path !== undefined && (typeof path !== 'string' || path.length > 2048
            || !isAbsolute(path))) return reply(res, 400, 'Invalid workspace path');
        const workspace = await workspaces.create(name.trim(), path);
        res.writeHead(201, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ workspace }));
      } catch (error) {
        return reply(res, 500, `Could not create workspace: ${error.message}`);
      }
    }
    const workspaceDelete = /^\/v1\/workspaces\/([^/]+)$/.exec(req.url);
    if (workspaceDelete && req.method === 'DELETE') {
      if (!workspaces?.remove) return reply(res, 503, 'Workspace service unavailable');
      try {
        await workspaces.remove(workspaceDelete[1]);
        res.writeHead(204, { 'Cache-Control': 'no-store' });
        return res.end();
      } catch (error) { return reply(res,
        error.message === 'Workspace not found' ? 404 : 503,
        error.message || 'Workspace removal failed'); }
    }
    const workspaceSessions = /^\/v1\/workspaces\/([^/]+)\/sessions$/.exec(req.url);
    if (workspaceSessions && chats) {
      const workspaceId = workspaceSessions[1];
      if (!workspaces.path(workspaceId)) return reply(res, 404, 'Workspace not found');
      try {
        if (req.method === 'GET') {
          const items = await chats.list(workspaceId);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store' });
          return res.end(JSON.stringify({ items }));
        }
        if (req.method === 'POST') {
          let body = '';
          for await (const chunk of req) {
            body += chunk.toString('utf8');
            if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
          }
          let agentPreset;
          try { agentPreset = body ? JSON.parse(body).agentPreset : undefined; }
          catch { return reply(res, 400, 'Invalid JSON'); }
          if (agentPreset !== undefined && (typeof agentPreset !== 'string'
              || !agentPreset || agentPreset.length > 128)) {
            return reply(res, 400, 'Invalid mode');
          }
          const created = await chats.create(workspaceId, agentPreset);
          res.writeHead(201, { 'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store' });
          return res.end(JSON.stringify({ session: created }));
        }
      } catch (error) { return reply(res, 503, error.message || 'Session service unavailable'); }
    }
    const sessionMessages = /^\/v1\/sessions\/([^/]+)\/messages$/.exec(req.url);
    if (sessionMessages && chats) {
      const sessionId = sessionMessages[1];
      try {
        if (req.method === 'GET') {
          const items = await chats.messages(sessionId);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store' });
          return res.end(JSON.stringify({ items }));
        }
        if (req.method === 'POST') {
          if (!req.headers['content-type']?.startsWith('application/json')) {
            return reply(res, 415, 'Expected JSON');
          }
          let body = '';
          for await (const chunk of req) {
            body += chunk.toString('utf8');
            if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
          }
          let message, receiptId, search;
          try { ({ message, receiptId, search } = JSON.parse(body)); }
          catch { return reply(res, 400, 'Invalid JSON'); }
          if (typeof message !== 'string' || message.length > 8000
              || (receiptId !== undefined && (typeof receiptId !== 'string'
                || !/^[0-9a-f-]{36}$/.test(receiptId)))
              || (search !== undefined && typeof search !== 'boolean')
              || (!message.trim() && !receiptId)
              || (search && !message.trim())) {
            return reply(res, 400, 'Invalid message');
          }
          await chats.prompt(sessionId, message.trim(), receiptId, search === true);
          res.writeHead(202, { 'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store' });
          return res.end(JSON.stringify({ accepted: true }));
        }
      } catch (error) { return reply(res, 503, error.message || 'Message service unavailable'); }
    }
    const sessionModel = /^\/v1\/sessions\/([^/]+)\/model$/.exec(req.url);
    if (sessionModel && chats?.selectModel && req.method === 'POST') {
      if (!req.headers['content-type']?.startsWith('application/json')) {
        return reply(res, 415, 'Expected JSON');
      }
      let body = '';
      try {
        for await (const chunk of req) {
          body += chunk.toString('utf8');
          if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
        }
        const { provider, model, reasoningEffort } = JSON.parse(body);
        if (typeof provider !== 'string' || !provider || provider.length > 128
            || typeof model !== 'string' || !model || model.length > 256
            || (reasoningEffort !== undefined && (typeof reasoningEffort !== 'string'
              || reasoningEffort.length > 64))) {
          return reply(res, 400, 'Invalid model selection');
        }
        const selected = await chats.selectModel(sessionModel[1], provider, model, reasoningEffort);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ selected }));
      } catch (error) { return reply(res, 503, error.message || 'Model selection failed'); }
    }
    const sessionTasks = /^\/v1\/sessions\/([^/]+)\/tasks$/.exec(req.url);
    if (sessionTasks && schedules) {
      const sessionId = sessionTasks[1];
      if (!schedules.belongs(sessionId)) return reply(res, 404, 'Session not found');
      try {
        if (req.method === 'GET') {
          const items = await schedules.list(sessionId);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store' });
          return res.end(JSON.stringify({ items }));
        }
        if (req.method === 'POST') {
          let body = '';
          for await (const chunk of req) {
            body += chunk.toString('utf8');
            if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
          }
          let request;
          try { request = JSON.parse(body); }
          catch { return reply(res, 400, 'Invalid JSON'); }
          if (typeof request.title !== 'string' || !request.title.trim()
              || request.title.length > 120 || typeof request.prompt !== 'string'
              || !request.prompt.trim() || request.prompt.length > 4000
              || !['after_seconds', 'every_seconds', 'daily'].filter(key => key in request).length) {
            return reply(res, 400, 'Invalid task');
          }
          const task = await schedules.create(sessionId, request);
          res.writeHead(201, { 'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store' });
          return res.end(JSON.stringify({ task }));
        }
      } catch (error) { return reply(res, 400, error.message || 'Task request failed'); }
    }
    const sessionTask = /^\/v1\/sessions\/([^/]+)\/tasks\/([^/]+)$/.exec(req.url);
    if (sessionTask && schedules && req.method === 'DELETE') {
      if (!schedules.belongs(sessionTask[1])) return reply(res, 404, 'Session not found');
      try {
        const result = await schedules.remove(sessionTask[1], sessionTask[2]);
        if (!result.deleted) return reply(res, 404, 'Task not found');
        res.writeHead(204, { 'Cache-Control': 'no-store' });
        return res.end();
      } catch (error) { return reply(res, 503, error.message || 'Task removal failed'); }
    }
    const sessionHistory = /^\/v1\/sessions\/([^/]+)\/history(?:\?before=([0-9]+))?$/.exec(req.url);
    if (sessionHistory && chats && req.method === 'GET') {
      const before = sessionHistory[2] === undefined ? undefined : Number(sessionHistory[2]);
      if (before !== undefined && (!Number.isSafeInteger(before) || before < 1)) {
        return reply(res, 400, 'Invalid history cursor');
      }
      try {
        const history = await chats.history(sessionHistory[1], before);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify(history));
      } catch (error) { return reply(res, 503, error.message || 'History unavailable'); }
    }
    const sessionArchive = /^\/v1\/sessions\/([^/]+)\/archive$/.exec(req.url);
    if (sessionArchive && chats && req.method === 'POST') {
      if (!req.headers['content-type']?.startsWith('application/json')) {
        return reply(res, 415, 'Expected JSON');
      }
      let body = '';
      try {
        for await (const chunk of req) {
          body += chunk.toString('utf8');
          if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
        }
        const { archived } = JSON.parse(body);
        if (typeof archived !== 'boolean') return reply(res, 400, 'Invalid archive state');
        await chats.archive(sessionArchive[1], archived);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store' });
        return res.end(JSON.stringify({ archived }));
      } catch (error) { return reply(res, 503, error.message || 'Archive unavailable'); }
    }
    if (req.method !== 'POST' || req.url !== '/v1/exec') {
      return reply(res, 404, 'Not found');
    }
    if (children.size >= 1) return reply(res, 429, 'A command is already running');
    if (!req.headers['content-type']?.startsWith('application/json')) {
      return reply(res, 415, 'Expected JSON');
    }
    let body = '';
    try {
      for await (const chunk of req) {
        body += chunk.toString('utf8');
        if (Buffer.byteLength(body) > MAX_BODY) return reply(res, 413, 'Request too large');
      }
    } catch {
      return;
    }
    let command;
    let workspaceId;
    try {
      const request = JSON.parse(body);
      command = request.command;
      workspaceId = request.workspaceId;
    } catch {
      return reply(res, 400, 'Invalid JSON');
    }
    if (typeof command !== 'string' || !command.trim() || command.length > 4096) {
      return reply(res, 400, 'Invalid command');
    }
    let cwd;
    if (workspaceId) {
      cwd = workspaces?.path(workspaceId);
      if (!cwd) return reply(res, 404, 'Workspace not found');
    }
    res.writeHead(200, { 'Content-Type': 'application/x-ndjson; charset=utf-8',
      'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' });
    const send = (type, data) => {
      if (!res.destroyed && !res.writableEnded) res.write(JSON.stringify({ type, data }) + '\n');
    };
    let child;
    try {
      child = commandProcess(command, cwd);
    } catch {
      send('error', 'Could not start command');
      return res.end();
    }
    children.add(child);
    let outputBytes = 0;
    let truncated = false;
    const decoders = { stdout: new StringDecoder('utf8'), stderr: new StringDecoder('utf8') };
    const forward = (type, chunk) => {
      if (truncated) return;
      outputBytes += chunk.length;
      if (outputBytes > MAX_OUTPUT) {
        truncated = true;
        send('error', 'Output limit reached; command stopped');
        child.kill();
        return;
      }
      send(type, decoders[type].write(chunk));
    };
    child.stdout.on('data', chunk => forward('stdout', chunk));
    child.stderr.on('data', chunk => forward('stderr', chunk));
    const timer = setTimeout(() => {
      send('error', 'Time limit reached; command stopped');
      child.kill();
    }, TIMEOUT_MS);
    child.on('error', () => send('error', 'Could not start command'));
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      children.delete(child);
      for (const type of ['stdout', 'stderr']) {
        const tail = decoders[type].end();
        if (tail) send(type, tail);
      }
      send('exit', String(code ?? signal ?? 'unknown'));
      if (!res.destroyed) res.end();
    });
    res.on('close', () => {
      if (!res.writableEnded) child.kill();
    });
  };
  const server = https.createServer({ key: tlsIdentity.key,
    cert: tlsIdentity.certificate }, handler);
  server.on('close', () => {
    for (const child of children) child.kill();
  });
  return server;
}

export async function loadPairing(credentials) {
  await credentials.modifyRecord(CREDENTIAL_KEY, current => current ?? {
    kind: 'grant', payload: {
      token: randomBytes(32).toString('hex'), code: newCode(),
    },
  });
  const record = await credentials.readRecord(CREDENTIAL_KEY);
  const token = record?.payload?.token;
  let code = record?.payload?.code;
  let enabled = record?.payload?.enabled !== false;
  if (record?.kind !== 'grant' || !/^[a-f0-9]{64}$/.test(token)
      || !/^[0-9]{8}$/.test(code)) {
    throw new Error('Invalid dsh Remote pairing record');
  }
  return {
    token,
    hostId: createHash('sha256').update(token).digest('hex').slice(0, 12),
    get code() { return code; },
    get enabled() { return enabled; },
    async setEnabled(value) {
      await credentials.modifyRecord(CREDENTIAL_KEY, current => {
        if (current?.payload?.token !== token) throw new Error('Pairing record changed');
        return { kind: 'grant', payload: { ...current.payload, enabled: value } };
      });
      enabled = value;
    },
    async claim(supplied) {
      let accepted = false;
      let nextCode;
      await credentials.modifyRecord(CREDENTIAL_KEY, current => {
        if (current?.payload?.token !== token || current?.payload?.code !== supplied
            || current?.payload?.enabled === false) {
          return undefined;
        }
        nextCode = newCode();
        accepted = true;
        return { kind: 'grant', payload: { ...current.payload, code: nextCode } };
      });
      if (accepted) code = nextCode;
      return accepted;
    },
  };
}

export function workspaceBridge(ctx) {
  const defaultBase = () => {
    const existing = ctx.workspaceRegistry.list();
    const defaultSpace = existing.find(item => item.path.endsWith('default-workspace'));
    return defaultSpace ? dirname(defaultSpace.path)
      : join(homedir(), 'Documents', 'deepseek-harness');
  };
  return {
    path(id) {
      return ctx.workspaceRegistry.list().find(item => String(item.id) === id)?.path;
    },
    list() {
      return ctx.workspaceRegistry.list().map(workspace => ({
        id: String(workspace.id), title: workspace.title,
        path: workspace.path, sessionCount: workspace.sessionIds.length,
      }));
    },
    async browse(requested) {
      if (!requested) {
        const roots = await driveRoots();
        return { path: '', parent: null, items: roots.map(path => ({ name: path, path })) };
      }
      const path = requested;
      if (!isAbsolute(path)) throw new Error('Absolute path required');
      const info = await stat(path);
      if (!info.isDirectory()) throw new Error('Not a directory');
      const entries = await readdir(path, { withFileTypes: true });
      return { path, parent: path === parse(path).root ? '' : dirname(path),
        items: entries.filter(item => item.isDirectory()).map(item => ({
          name: item.name, path: join(path, item.name),
        })).sort((a, b) => a.name.localeCompare(b.name)) };
    },
    async create(name, selectedPath) {
      const path = selectedPath ?? join(defaultBase(), name);
      if (selectedPath === undefined) await mkdir(path, { recursive: true });
      else if (!(await stat(path)).isDirectory()) throw new Error('Not a directory');
      const result = await ctx.workspaceController.create({ path });
      if (result.workspace.title !== name) {
        await ctx.workspaceController.rename({ workspaceId: result.workspace.workspaceId,
          title: name });
      }
      return { id: String(result.workspace.workspaceId), title: name, path };
    },
    async remove(id) {
      if (!ctx.workspaceRegistry.list().some(item => String(item.id) === id)) {
        throw new Error('Workspace not found');
      }
      await ctx.workspaceController.delete({ workspaceId: id });
    },
  };
}

function messageText(content, type = 'text') {
  return Array.isArray(content) ? content.filter(part => part?.type === type)
    .map(part => part.text).filter(value => typeof value === 'string').join('\n') : '';
}

const WEB_CONTEXT_MARKER = '\n\n[手机端联网搜索资料]\n';

function historyMessages(records) {
  const events = records.filter(record => record.type === 'event')
    .map(record => record.event)
    .filter(event => event.surfaceOp === 'append'
      && (event.type === 'user/message' || event.type === 'assistant/message'));
  const messages = [];
  let answer = null;
  let reasoning = [];
  const flush = () => {
    if (answer) messages.push({ ...answer, reasoning: reasoning.join('\n\n') });
    answer = null;
    reasoning = [];
  };
  for (const event of events) {
    if (event.type === 'user/message') {
      if (event.data?.source?.kind && event.data.source.kind !== 'user') continue;
      flush();
      const attachments = (event.data?.content || [])
        .filter(part => part?.type === 'file' || part?.type === 'image')
        .map(part => `📎 ${part.attachment?.name || '附件'}`);
      const visible = messageText(event.data?.content).split(WEB_CONTEXT_MARKER)[0];
      const text = [visible, ...attachments].filter(Boolean).join('\n');
      if (text) messages.push({ id: String(event.seq), role: 'user', text });
      continue;
    }
    const content = event.data?.message?.content;
    if (!Array.isArray(content)) continue;
    const thought = messageText(content, 'reasoning');
    if (thought) reasoning.push(thought);
    if (content.some(part => part?.type === 'tool-call')) continue;
    const text = messageText(content);
    if (text) answer = { id: String(event.seq), role: 'assistant', text };
  }
  flush();
  return messages;
}

export function chatBridge(ctx) {
  const signal = () => new AbortController().signal;
  const belongs = sessionId => ctx.workspaceRegistry.list()
    .some(workspace => workspace.sessionIds.includes(sessionId));
  return {
    async modes() {
      return (await ctx.agentPresets.list()).filter(item => !item.broken)
        .map(({ id, name, description }) => ({ id, name, description }));
    },
    async mode(sessionId) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const projection = await ctx.sessionController.projections({ sessionId }, signal());
      const id = projection?.values?.agentPreset || '';
      const entry = (await this.modes()).find(item => item.id === id);
      return { id, name: entry?.name || id };
    },
    async skills(sessionId) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const catalog = ctx.get?.('sessionSkillCatalog') ?? ctx.sessionSkillCatalog;
      if (!catalog?.list) throw new Error('Skill catalog unavailable');
      const result = await catalog.list({ sessionId }, signal());
      return result.skills.map(({ name, description }) => ({ name, description }));
    },
    async models(sessionId) {
      if (sessionId && !belongs(sessionId)) throw new Error('Session not found');
      const catalog = await ctx.sessionController.modelCatalog();
      let selected = catalog.default;
      if (sessionId) {
        const projection = await ctx.sessionController.projections({ sessionId }, signal());
        selected = projection?.values?.modelSelection?.next ?? catalog.default;
      }
      return { groups: catalog.groups, failures: catalog.failures,
        default: catalog.default, selected };
    },
    async selectModel(sessionId, provider, model, reasoningEffort) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const result = await ctx.sessionController.selectModel({ sessionId, provider, model,
        ...(reasoningEffort === undefined ? {} : { reasoningEffort }) });
      return result.selected;
    },
    async list(workspaceId) {
      const workspace = ctx.workspaceRegistry.get(workspaceId);
      if (!workspace) throw new Error('Workspace not found');
      const rows = (await ctx.sessionController.list({}, signal())).items;
      const byId = new Map(rows.map(row => [row.sessionId, row]));
      const archivedIds = new Set(ctx.workspaceRegistry.archivedSessionIds ?? []);
      return workspace.sessionIds.map(id => ({ id,
        title: byId.get(id)?.projections?.values?.title?.title || '新对话 · ' + id.slice(-6),
        running: byId.get(id)?.running === true,
        archived: archivedIds.has(id) }));
    },
    async create(workspaceId, agentPreset) {
      if (agentPreset && !(await this.modes()).some(item => item.id === agentPreset)) {
        throw new Error('Mode not found');
      }
      const result = await ctx.sessionController.create({ workspaceId,
        ...(agentPreset ? { agentPreset } : {}) });
      return { id: result.sessionId, title: '新会话' };
    },
    async messages(sessionId) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const controller = new AbortController();
      const stream = ctx.sessionController.follow({
        address: { kind: 'session', sessionId }, maxMessages: 100,
      }, controller.signal);
      try {
        const snapshot = (await stream.next()).value;
        if (snapshot?.type !== 'snapshot') throw new Error('Session history unavailable');
        return historyMessages(snapshot.records);
      } finally {
        controller.abort();
        await stream.return();
      }
    },
    async history(sessionId, before) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      let page;
      if (before === undefined) {
        const controller = new AbortController();
        const stream = ctx.sessionController.follow({
          address: { kind: 'session', sessionId }, maxMessages: 100,
        }, controller.signal);
        try { page = (await stream.next()).value; }
        finally { controller.abort(); await stream.return(); }
        if (page?.type !== 'snapshot') throw new Error('Session history unavailable');
      } else {
        page = await ctx.sessionController.page({
          address: { kind: 'session', sessionId }, throughSeq: before - 1,
          beforeSeq: before, maxMessages: 100,
        }, signal());
      }
      const first = page.records.find(record => record.type === 'event')?.event.seq;
      return { items: historyMessages(page.records), hasMore: page.hasMore === true,
        before: page.hasMore && first !== undefined ? first : null };
    },
    async archive(sessionId, archived) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      if (archived) await ctx.workspaceController.archiveSession({ sessionId });
      else await ctx.workspaceController.unarchiveSession({ sessionId });
    },
    async prompt(sessionId, message, receiptId, search = false) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      if (search) {
        if (!ctx.web?.search) throw new Error('PC 尚未启用联网搜索服务');
        const result = await ctx.web.search({ query: message, maxResults: 6 }, signal());
        const sources = (result.sources || []).map((source, index) => {
          const title = String(source.title || '').slice(0, 180);
          const url = String(source.url || '').slice(0, 600);
          const snippet = String(source.snippet || '').slice(0, 650);
          return `${index + 1}. ${title}\n${url}\n${snippet}`;
        });
        if (sources.length === 0) throw new Error('联网搜索没有返回资料');
        message += WEB_CONTEXT_MARKER
          + '请基于以下联网资料回答，并在引用时附上来源链接。\n'
          + sources.join('\n\n').slice(0, 6500);
      }
      await ctx.sessionController.prompt({ sessionId,
        requestId: randomBytes(16).toString('hex'), mode: 'followup',
        content: [...message ? [{ type: 'text', text: message }] : [],
          ...receiptId ? [{ type: 'file', receiptId }] : []] }, signal());
    },
    async command(sessionId, line) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const resolved = await ctx.sessionController.resolveAgent(sessionId);
      if ('error' in resolved) throw resolved.error;
      const execution = await ctx.commands.execute(resolved.agent, line, [], signal());
      if (!execution) throw new Error('Command unavailable for this session');
      return { kind: execution.result.kind, text: execution.result.text || '' };
    },
    async upload(sessionId, data, name) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const resolved = await ctx.sessionController.resolveAgent(sessionId);
      if ('error' in resolved) throw resolved.error;
      return ctx.fileUploads.upload(resolved.agent, { data, name }, signal());
    },
    async log(sessionId) {
      if (!belongs(sessionId)) throw new Error('Session not found');
      const snapshot = await ctx.sessionController.inspect(sessionId, signal());
      if (!snapshot) throw new Error('Session not found');
      return [{ type: 'header', data: snapshot.meta },
        ...snapshot.events.map(event => ({ type: 'event', data: event }))];
    },
  };
}

export function scheduleBridge(ctx) {
  const belongs = sessionId => ctx.workspaceRegistry.list()
    .some(workspace => workspace.sessionIds.includes(sessionId));
  return {
    belongs,
    list: sessionId => ctx.schedule.catalog().then(rows => rows.filter(
      row => row.sessionId === sessionId)),
    create: (sessionId, request) => ctx.schedule.create(sessionId, request),
    remove: (sessionId, id) => ctx.schedule.delete({ sessionId, id }),
  };
}

export function apply(ctx, config = {}) {
  let pairing;
  let tlsIdentity;
  let relay;
  let relayUrl = '';
  let relayStatus = 'off';
  let updateRelay;
  let onboarding;
  const port = Number(config.securePort ?? 19390);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('DSH_REMOTE_PORT must be a valid TCP port');
  }
  ctx.connection.fetch.register({
    path: '/api/dsh-remote-pair-code', methods: ['GET'], requestBody: 'buffered',
    fetch: () => new Response(JSON.stringify({ code: pairing?.enabled ? pairing.code : null,
      enabled: pairing?.enabled ?? false, lanHost: '自动发现',
      host: pairing ? `dsh-${pairing.hostId}.local` : null, port,
      hostId: pairing?.hostId ?? null, relayUrl, relayStatus,
      fingerprint: tlsIdentity?.fingerprint ?? null,
      networks: lanNetworks() }), {
      status: pairing ? 200 : 503,
      headers: { 'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store' },
    }),
  });
  ctx.connection.fetch.register({
    path: '/api/dsh-remote-pairing', methods: ['POST'], requestBody: 'buffered',
    fetch: async request => {
      if (!pairing) return new Response(null, { status: 503 });
      try {
        const { enabled } = await request.json();
        if (typeof enabled !== 'boolean') return new Response(null, { status: 400 });
        await pairing.setEnabled(enabled);
        relay?.refresh();
        return new Response(JSON.stringify({ enabled }), {
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
        });
      } catch { return new Response(null, { status: 500 }); }
    },
  });
  ctx.connection.fetch.register({
    path: '/api/dsh-remote-relay', methods: ['POST'], requestBody: 'buffered',
    fetch: async request => {
      if (!updateRelay) return new Response(null, { status: 503 });
      try {
        const { url } = await request.json();
        if (typeof url !== 'string' || (url &&
            !/^wss:\/\/[a-z0-9.-]+(?::[0-9]+)?\/signal$/i.test(url))) {
          return new Response(JSON.stringify({ error: 'Invalid WSS signal URL' }),
            { status: 400 });
        }
        await updateRelay(url);
        return new Response(JSON.stringify({ url }), {
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
        });
      } catch { return new Response(null, { status: 500 }); }
    },
  });
  ctx.connection.fetch.register({
    path: '/api/dsh-remote-cloudflare', methods: ['GET'], requestBody: 'buffered',
    fetch: () => new Response(JSON.stringify(onboarding?.snapshot() ?? {
      phase: 'idle', message: '' }), { headers: {
      'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    } }),
  });
  ctx.connection.fetch.register({
    path: '/api/dsh-remote-cloudflare/start', methods: ['POST'], requestBody: 'buffered',
    fetch: () => new Response(JSON.stringify(onboarding?.begin() ?? {
      phase: 'error', message: '插件尚未就绪' }), { status: onboarding ? 200 : 503,
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
    }),
  });
  ctx.connection.fetch.register({
    path: '/api/dsh-remote-cloudflare/account', methods: ['POST'], requestBody: 'buffered',
    fetch: async request => {
      let id;
      try { ({ id } = await request.json()); } catch { return new Response(null, { status: 400 }); }
      if (!onboarding?.chooseAccount(id)) return new Response(null, { status: 400 });
      return new Response(JSON.stringify(onboarding.snapshot()), { headers: {
        'Content-Type': 'application/json; charset=utf-8',
      } });
    },
  });
  ctx.effect(async () => {
    pairing = await loadPairing(ctx.credentials);
    tlsIdentity = await loadTlsIdentity(ctx.credentials, pairing.hostId);
    const storedRelay = await ctx.credentials.readRecord(RELAY_KEY);
    relayUrl = storedRelay ? (storedRelay.payload?.url || '')
      : (config.signalUrl || '');
    updateRelay = async url => {
      await ctx.credentials.modifyRecord(RELAY_KEY,
        () => ({ kind: 'grant', payload: { url } }));
      relay?.stop();
      relay = null;
      relayUrl = url;
      relayStatus = url ? 'connecting' : 'off';
      if (url) relay = startRelayHost({ pairing, tlsIdentity, port,
        signalUrl: url, onStatus: value => { relayStatus = value; } });
    };
    onboarding = createCloudflareOnboarding(updateRelay);
    if (relayUrl) {
      relayStatus = 'connecting';
      relay = startRelayHost({ pairing, tlsIdentity, port,
        signalUrl: relayUrl, onStatus: value => { relayStatus = value; } });
    }
    const workspaces = workspaceBridge(ctx);
    const chats = chatBridge(ctx);
    const schedules = scheduleBridge(ctx);
    const servers = new Map();
    const syncListeners = () => {
      const hosts = new Set(['127.0.0.1',
        ...lanInterfaces().map(item => item.address)]);
      for (const [key, server] of servers) {
        const host = key.split(':')[0];
        if (!hosts.has(host)) {
          servers.delete(key);
          if (server.listening) server.close();
        }
      }
      for (const host of hosts) {
        const key = `${host}:${port}`;
        if (servers.has(key)) continue;
        const server = createServer(pairing.token, pairing, workspaces,
          chats, schedules, tlsIdentity);
        server.on('error', error => console.error(`dsh Remote listener ${key}:`, error.message));
        server.listen(port, host);
        servers.set(key, server);
      }
    };
    syncListeners();
    const refresh = setInterval(syncListeners, 10_000);
    const stopDiscovery = startDiscovery(pairing, port, tlsIdentity.fingerprint);
    return () => {
      onboarding?.stop();
      onboarding = null;
      relay?.stop();
      updateRelay = null;
      clearInterval(refresh);
      stopDiscovery();
      for (const server of servers.values()) if (server.listening) server.close();
    };
  });
}

export const inject = ['connection', 'credentials', 'workspaceRegistry',
  'workspaceController', 'sessionController', 'agentPresets', 'schedule',
  'commands', 'fileUploads', 'web'];
