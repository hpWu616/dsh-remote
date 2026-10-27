import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { apply, chatBridge, createServer, loadPairing, workspaceBridge } from './index.js';
import { hostAnswer, isHostQuery, lanNetworks } from './discovery.js';

const token = 'x'.repeat(64);
const pairing = { code: '12345678', async claim(code) {
  if (code !== this.code) return false;
  this.code = '87654321';
  return true;
} };
const server = createServer(token, pairing);
let base;

before(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => {
  await new Promise(resolve => server.close(resolve));
});

test('authentication and browser origin are enforced', async () => {
  const anonymous = await fetch(`${base}/v1/health`);
  assert.equal(anonymous.status, 401);
  const browser = await fetch(`${base}/v1/health`, {
    headers: { Authorization: `Bearer ${token}`, Origin: 'https://example.com' },
  });
  assert.equal(browser.status, 403);
  const authorized = await fetch(`${base}/v1/health`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  assert.equal(authorized.status, 200);
});

test('pairing exchanges one code for a token and rotates it', async () => {
  const pair = code => fetch(`${base}/v1/pair`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  const first = await pair('12345678');
  assert.equal(first.status, 200);
  assert.equal((await first.json()).token, token);
  assert.notEqual(pairing.code, '12345678');
  assert.equal((await pair('12345678')).status, 401);
  pairing.enabled = false;
  assert.equal((await pair(pairing.code)).status, 403);
  delete pairing.enabled;
});

test('authenticated command returns streamed output and exit event', async () => {
  const response = await fetch(`${base}/v1/exec`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ command: 'echo dsh_test' }),
  });
  assert.equal(response.status, 200);
  const events = (await response.text()).trim().split('\n').map(JSON.parse);
  assert.ok(events.some(event => event.type === 'stdout' && event.data.includes('dsh_test')));
  assert.ok(events.some(event => event.type === 'exit' && event.data === '0'));
});

test('dsh Client Connection exposes the current pairing code', async () => {
  const routes = new Map();
  const effects = [];
  let record;
  const credentials = {
    async modifyRecord(key, mutate) { record = await mutate(record) ?? record; },
    async readRecord() { return record; },
  };
  apply({
    credentials,
    connection: { fetch: { register: value => { routes.set(value.path, value); } } },
    effect: value => effects.push(value),
  }, { lanHost: '10.7.32.43', port: 19388 });
  const route = routes.get('/api/dsh-remote-pair-code');
  assert.ok(route);
  assert.deepEqual(route.methods, ['GET']);
  assert.ok(routes.get('/api/dsh-remote-pairing'));
  assert.equal((await route.fetch()).status, 503);
  const first = await loadPairing(credentials);
  assert.match(first.hostId, /^[a-f0-9]{12}$/);
  const savedCode = first.code;
  const savedToken = first.token;
  const restarted = await loadPairing(credentials);
  assert.equal(restarted.code, savedCode);
  assert.equal(restarted.token, savedToken);
  assert.equal(await restarted.claim(savedCode), true);
  assert.notEqual(restarted.code, savedCode);
  assert.equal((await loadPairing(credentials)).token, savedToken);
  assert.equal((await loadPairing(credentials)).code, restarted.code);
  assert.equal(await restarted.claim(savedCode), false);
  await restarted.setEnabled(false);
  assert.equal((await loadPairing(credentials)).enabled, false);
  assert.equal(await restarted.claim(restarted.code), false);
  assert.equal(effects.length, 1);
  const details = await (await route.fetch()).json();
  assert.equal(details.lanHost, '自动发现');
  assert.equal(details.host, null);
  assert.ok(Array.isArray(details.networks));
});

test('PC network details show IPv4 address and subnet', () => {
  assert.deepEqual(lanNetworks([
    { address: '10.7.32.43', netmask: '255.255.192.0' },
    { address: '192.168.1.20', netmask: '255.255.255.0' },
  ]), [
    { address: '10.7.32.43', subnet: '10.7.0.0/18' },
    { address: '192.168.1.20', subnet: '192.168.1.0/24' },
  ]);
});

test('workspace endpoint lists all registered workspaces and creates one', async () => {
  const entries = [{ id: 'one', title: 'A', path: '/tmp/A', sessionIds: [] }];
  let selectedPath;
  const bridge = {
    list: () => entries.map(item => ({ id: item.id, title: item.title })),
    path: id => entries.find(item => item.id === id)?.path,
    browse: async path => ({ path, parent: null,
      items: [{ name: 'projects', path: process.cwd() }] }),
    create: async (name, path) => {
      selectedPath = path;
      return { id: 'two', title: name, path };
    },
    remove: async id => {
      const index = entries.findIndex(item => item.id === id);
      if (index < 0) throw new Error('Workspace not found');
      entries.splice(index, 1);
    },
  };
  const workspaceServer = createServer(token, pairing, bridge);
  await new Promise(resolve => workspaceServer.listen(0, '127.0.0.1', resolve));
  try {
    const url = `http://127.0.0.1:${workspaceServer.address().port}/v1/workspaces`;
    const headers = { Authorization: `Bearer ${token}` };
    assert.equal((await fetch(url)).status, 401);
    const listed = await (await fetch(url, { headers })).json();
    assert.equal(listed.items[0].title, 'A');
    const created = await fetch(url, { method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'B' }) });
    assert.equal(created.status, 201);
    assert.equal((await created.json()).workspace.title, 'B');
    const directories = `http://127.0.0.1:${workspaceServer.address().port}/v1/directories?path=`
      + encodeURIComponent(process.cwd());
    assert.equal((await fetch(directories)).status, 401);
    const folders = await (await fetch(directories, { headers })).json();
    assert.equal(folders.items[0].name, 'projects');
    const chosen = await fetch(url, { method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Chosen', path: process.cwd() }) });
    assert.equal(chosen.status, 201);
    assert.equal(selectedPath, process.cwd());
    const relative = await fetch(url, { method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Relative', path: 'some-folder' }) });
    assert.equal(relative.status, 400);
    const invalid = await fetch(url, { method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '../bad' }) });
    assert.equal(invalid.status, 400);
    assert.equal((await fetch(`${url}/one`, { method: 'DELETE' })).status, 401);
    assert.equal((await fetch(`${url}/one`, { method: 'DELETE', headers })).status, 204);
    assert.deepEqual(entries, []);
    assert.equal((await fetch(`${url}/one`, { method: 'DELETE', headers })).status, 404);
    assert.equal((await fetch(`${url}/../desktop/frame`, { headers })).status, 404);
  } finally {
    await new Promise(resolve => workspaceServer.close(resolve));
  }
});

test('workspace bridge browses PC folders and creates in the selected folder', async () => {
  let createdPath;
  let deletedId;
  const entries = [{ id: 'one', path: process.cwd(), title: 'Existing', sessionIds: [] }];
  const bridge = workspaceBridge({
    workspaceRegistry: { list: () => entries },
    workspaceController: {
      create: async ({ path }) => {
        createdPath = path;
        return { workspace: { workspaceId: 'two', title: 'Chosen' } };
      },
      rename: async () => {},
      delete: async ({ workspaceId }) => { deletedId = workspaceId; entries.length = 0; },
    },
  });
  const listing = await bridge.browse(process.cwd());
  assert.equal(listing.path, process.cwd());
  assert.ok(listing.items.some(item => ['app', 'locale'].includes(item.name)));
  const roots = await bridge.browse('');
  assert.ok(roots.items.some(item => process.cwd().startsWith(item.path)));
  const created = await bridge.create('Chosen', process.cwd());
  assert.equal(createdPath, process.cwd());
  assert.equal(created.path, process.cwd());
  await bridge.remove('one');
  assert.equal(deletedId, 'one');
  assert.deepEqual(entries, []);
  await assert.rejects(bridge.remove('missing'), /Workspace not found/);
});

test('chat bridge creates a dsh session, sends a prompt, and reads its messages', async () => {
  const workspace = { id: 'space', sessionIds: ['session-1'] };
  const archivedSessionIds = new Set();
  let prompted;
  let modelRequest;
  let returned = false;
  const chat = chatBridge({
    workspaceRegistry: { list: () => [workspace], archivedSessionIds, get: id =>
      id === workspace.id ? workspace : undefined },
    workspaceController: {
      archiveSession: async ({ sessionId }) => archivedSessionIds.add(sessionId),
      unarchiveSession: async ({ sessionId }) => archivedSessionIds.delete(sessionId),
    },
    sessionController: {
      modelCatalog: async () => ({ default: { provider: 'official', model: 'chat' },
        groups: [{ id: 'official', name: 'DeepSeek', models: [
          { id: 'chat', name: 'Chat' }, { id: 'reasoner', name: 'Reasoner' }] }], failures: [] }),
      projections: async () => ({ values: { modelSelection: {
        next: { provider: 'official', model: 'reasoner' } } } }),
      selectModel: async request => { modelRequest = request;
        return { selected: { provider: request.provider, model: request.model } }; },
      list: async () => ({ items: [{ sessionId: 'session-1', running: false }] }),
      create: async request => {
        assert.equal(request.workspaceId, 'space');
        workspace.sessionIds.push('session-2');
        return { sessionId: 'session-2' };
      },
      prompt: async request => { prompted = request; },
      follow: () => ({
        async next() { return { value: { type: 'snapshot', records: [
          { type: 'event', event: { seq: 1, type: 'user/message', surfaceOp: 'append',
            data: { source: { kind: 'user' }, content: [{ type: 'text', text: '你好' }] } } },
          { type: 'event', event: { seq: 2, type: 'user/message', surfaceOp: 'append',
            data: { source: { kind: 'runtime-context' },
              content: [{ type: 'text', text: 'Current runtime context' }] } } },
          { type: 'event', event: { seq: 3, type: 'assistant/message', surfaceOp: 'append',
            data: { message: { content: [{ type: 'reasoning', text: '分析问题' },
              { type: 'text', text: '正在检查工具' }] } } } },
          { type: 'event', event: { seq: 4, type: 'assistant/message', surfaceOp: 'append',
            data: { message: { content: [{ type: 'tool-call', name: 'read' }] } } } },
          { type: 'event', event: { seq: 5, type: 'assistant/message', surfaceOp: 'append',
            data: { message: { content: [{ type: 'reasoning', text: '已经确认' },
              { type: 'text', text: '你好！' }] } } } },
        ], hasMore: true } } },
        async return() { returned = true; },
      }),
      page: async request => {
        assert.equal(request.beforeSeq, 1);
        assert.equal(request.throughSeq, 0);
        return { records: [{ type: 'event', event: { seq: 0, type: 'user/message',
          surfaceOp: 'append', data: { content: [{ type: 'text', text: 'older' }] } } }],
        hasMore: false };
      },
    },
  });
  assert.equal((await chat.list('space'))[0].id, 'session-1');
  assert.equal((await chat.create('space')).id, 'session-2');
  assert.equal((await chat.models('session-1')).selected.model, 'reasoner');
  assert.equal((await chat.models('')).selected.model, 'chat');
  assert.equal((await chat.selectModel('session-1', 'official', 'chat')).model, 'chat');
  assert.deepEqual(modelRequest, { sessionId: 'session-1', provider: 'official', model: 'chat' });
  await assert.rejects(chat.selectModel('unknown', 'official', 'chat'), /Session not found/);
  await chat.prompt('session-2', 'first message');
  assert.equal(prompted.sessionId, 'session-2');
  await chat.prompt('session-1', '问题');
  assert.equal(prompted.content[0].text, '问题');
  const visible = await chat.messages('session-1');
  assert.deepEqual(visible.map(item => item.text), ['你好', '你好！']);
  assert.equal(visible[1].reasoning, '分析问题\n\n已经确认');
  const recent = await chat.history('session-1');
  assert.equal(recent.before, 1);
  assert.equal(recent.hasMore, true);
  assert.deepEqual((await chat.history('session-1', recent.before)).items.map(item => item.text),
    ['older']);
  await chat.archive('session-1', true);
  assert.equal((await chat.list('space'))[0].archived, true);
  await chat.archive('session-1', false);
  assert.equal((await chat.list('space'))[0].archived, false);
  assert.equal(returned, true);
});

test('authenticated mobile chat routes scope sessions to a workspace', async () => {
  const calls = [];
  const chatServer = createServer(token, pairing,
    { path: id => id === 'space' ? '/tmp/space' : undefined }, {
      list: async id => { calls.push(['list', id]); return [{ id: 'one', title: '新会话' }]; },
      create: async id => { calls.push(['create', id]); return { id: 'two', title: '新会话' }; },
      messages: async id => { calls.push(['messages', id]); return [{ role: 'user', text: '你好' }]; },
      prompt: async (id, message, receipt) => { calls.push(['prompt', id, message, receipt]); },
      command: async (id, line) => { calls.push(['command', id, line]);
        return { kind: 'success', text: '已执行' }; },
      upload: async (id, data, name) => { calls.push(['upload', id, data, name]);
        return { receiptId: '00000000-0000-4000-8000-000000000000' }; },
      log: async id => { calls.push(['log', id]);
        return [{ type: 'header', data: { id } }]; },
      history: async (id, before) => {
        calls.push(['history', id, before]);
        return { items: [{ role: 'user', text: 'older' }], hasMore: false, before: null };
      },
      archive: async (id, archived) => { calls.push(['archive', id, archived]); },
      models: async id => { calls.push(['models', id]); return {
        groups: [{ id: 'official', name: 'DeepSeek', models: [{ id: 'chat', name: 'Chat' }] }],
        selected: { provider: 'official', model: 'chat' }, failures: [] }; },
      selectModel: async (id, provider, model) => {
        calls.push(['selectModel', id, provider, model]); return { provider, model }; },
    });
  await new Promise(resolve => chatServer.listen(0, '127.0.0.1', resolve));
  try {
    const url = `http://127.0.0.1:${chatServer.address().port}`;
    const headers = { Authorization: `Bearer ${token}` };
    assert.equal((await fetch(`${url}/v1/workspaces/space/sessions`)).status, 401);
    assert.equal((await fetch(`${url}/v1/workspaces/missing/sessions`, { headers })).status, 404);
    assert.equal((await fetch(`${url}/v1/workspaces/space/sessions`, { headers })).status, 200);
    assert.equal((await fetch(`${url}/v1/workspaces/space/sessions`,
      { method: 'POST', headers })).status, 201);
    assert.equal((await fetch(`${url}/v1/sessions/one/messages`, { headers })).status, 200);
    assert.equal((await fetch(`${url}/v1/models?sessionId=one`)).status, 401);
    const catalog = await (await fetch(`${url}/v1/models?sessionId=one`, { headers })).json();
    assert.equal(catalog.groups[0].models[0].id, 'chat');
    assert.equal((await fetch(`${url}/v1/sessions/one/model`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'official', model: 'chat' }),
    })).status, 200);
    assert.equal((await fetch(`${url}/v1/sessions/one/model`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: '', model: 'chat' }),
    })).status, 400);
    assert.equal((await fetch(`${url}/v1/sessions/one/messages`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: '问题' }),
    })).status, 202);
    assert.equal((await fetch(`${url}/v1/sessions/one/commands`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ line: '/plan task' }),
    })).status, 200);
    assert.equal((await fetch(`${url}/v1/sessions/one/commands`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ line: '/unknown' }),
    })).status, 400);
    assert.equal((await fetch(`${url}/v1/sessions/one/files`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'note.txt', data: 'aGVsbG8=' }),
    })).status, 201);
    assert.equal((await fetch(`${url}/v1/sessions/one/messages`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: '',
        receiptId: '00000000-0000-4000-8000-000000000000' }),
    })).status, 202);
    const log = await fetch(`${url}/v1/sessions/one/log`, { headers });
    assert.equal(log.status, 200);
    assert.match(await log.text(), /"type":"header"/);
    assert.equal((await fetch(`${url}/v1/sessions/one/history`)).status, 401);
    const history = await (await fetch(`${url}/v1/sessions/one/history?before=5`,
      { headers })).json();
    assert.equal(history.items[0].text, 'older');
    assert.equal((await fetch(`${url}/v1/sessions/one/archive`, {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived: true }),
    })).status, 200);
    assert.deepEqual(calls, [['list', 'space'], ['create', 'space'],
      ['messages', 'one'], ['models', 'one'], ['selectModel', 'one', 'official', 'chat'],
      ['prompt', 'one', '问题', undefined],
      ['command', 'one', '/plan task'],
      ['upload', 'one', 'aGVsbG8=', 'note.txt'],
      ['prompt', 'one', '', '00000000-0000-4000-8000-000000000000'],
      ['log', 'one'],
      ['history', 'one', 5], ['archive', 'one', true]]);
  } finally {
    await new Promise(resolve => chatServer.close(resolve));
  }
});

test('native session commands and file uploads use the owning dsh Agent', async () => {
  const agent = { id: 'session-1' };
  const seen = [];
  const bridge = chatBridge({
    workspaceRegistry: { list: () => [{ id: 'space', sessionIds: ['session-1'] }] },
    sessionController: {
      resolveAgent: async id => { seen.push(['resolve', id]); return { agent }; },
      inspect: async id => ({ meta: { id }, events: [{ seq: 1 }] }),
    },
    commands: { execute: async (actual, line, files) => {
      seen.push(['command', actual, line, files]);
      return { result: { kind: 'success', text: 'OK' } };
    } },
    fileUploads: { upload: async (actual, request) => {
      seen.push(['upload', actual, request]); return { receiptId: 'receipt' };
    } },
  });
  assert.deepEqual(await bridge.command('session-1', '/goal'),
    { kind: 'success', text: 'OK' });
  assert.equal((await bridge.upload('session-1', 'aGVsbG8=', 'note.txt')).receiptId,
    'receipt');
  assert.equal((await bridge.log('session-1')).length, 2);
  assert.deepEqual(seen[0], ['resolve', 'session-1']);
  assert.deepEqual(seen[1], ['command', agent, '/goal', []]);
  assert.deepEqual(seen[3], ['upload', agent, { data: 'aGVsbG8=', name: 'note.txt' }]);
  await assert.rejects(bridge.command('unknown', '/goal'), /Session not found/);
});

test('mobile smart search uses the PC web service and keeps source context out of visible history', async () => {
  let prompted;
  const records = [];
  const bridge = chatBridge({
    workspaceRegistry: { list: () => [{ sessionIds: ['one'] }] },
    web: { search: async request => {
      assert.deepEqual(request, { query: '今天有什么新发现？', maxResults: 6 });
      return { sources: [{ title: '资料标题', url: 'https://example.org/source',
        snippet: '摘要内容' }] };
    } },
    sessionController: {
      prompt: async request => {
        prompted = request;
        records.push({ type: 'event', event: { seq: 1, type: 'user/message',
          surfaceOp: 'append', data: { source: { kind: 'user' },
            content: request.content } } });
      },
      follow: () => ({
        next: async () => ({ value: { type: 'snapshot', records } }),
        return: async () => {},
      }),
    },
  });
  await bridge.prompt('one', '今天有什么新发现？', undefined, true);
  assert.match(prompted.content[0].text, /https:\/\/example.org\/source/);
  assert.deepEqual((await bridge.messages('one')).map(item => item.text),
    ['今天有什么新发现？']);
});

test('mDNS answer names the discovered host and address', () => {
  const host = 'dsh-0123456789ab.local';
  const question = Buffer.alloc(12);
  question.writeUInt16BE(1, 4);
  const labels = Buffer.concat(host.split('.').map(part => Buffer.concat([
    Buffer.from([part.length]), Buffer.from(part)])));
  const packet = Buffer.concat([question, labels, Buffer.from([0, 0, 1, 0, 1])]);
  assert.equal(isHostQuery(packet, host), true);
  assert.equal(isHostQuery(packet, 'other.local'), false);
  assert.deepEqual([...hostAnswer(host, '10.7.32.43').subarray(-4)], [10, 7, 32, 43]);
});

test('Client entry registers the pairing card on the installed plugin page', () => {
  let moduleEntry;
  runInNewContext(readFileSync(new URL('./client.js', import.meta.url), 'utf8'), {
    window: { __ModuleLoader__: { load: entry => { moduleEntry = entry; } } },
  });
  const plugin = moduleEntry.factory(name => {
    assert.equal(name, 'react');
    return { createElement() {} };
  });
  let registered;
  plugin.apply({ slots: {
    inject: (name, callback) => {
      assert.equal(name, 'plugins.item');
      callback();
    },
    register: (options, component) => { registered = { options, component }; },
  } });
  assert.equal(registered.options.name, 'plugins.item');
  assert.equal(registered.options.label, '手机连接');
  assert.equal(typeof registered.component, 'function');
});

test('mobile mode, reasoning, and scheduled task routes use dsh services', async () => {
  const selected = [];
  const tasks = [];
  const chats = {
    modes: async () => [{ id: 'standard', name: '标准模式' }],
    mode: async () => ({ id: 'standard', name: '标准模式' }),
    skills: async () => [{ name: 'office', description: 'Office documents' }],
    create: async (workspaceId, preset) => ({ id: 'new', title: preset }),
    selectModel: async (id, provider, model, effort) => {
      selected.push({ id, provider, model, effort });
      return { provider, model, reasoningEffort: effort };
    },
  };
  const schedules = {
    belongs: id => id === 'one',
    list: async () => tasks,
    create: async (id, request) => {
      const task = { id: 'task-1', title: request.title, sessionId: id };
      tasks.push(task);
      return task;
    },
    remove: async () => ({ deleted: true }),
  };
  const instance = createServer(token, pairing, { path: () => '/tmp' }, chats, schedules);
  await new Promise(resolve => instance.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${instance.address().port}`;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  try {
    assert.equal((await fetch(`${url}/v1/modes`, { headers }).then(r => r.json()))
      .items[0].id, 'standard');
    assert.equal((await fetch(`${url}/v1/sessions/one/mode`, { headers }).then(r => r.json()))
      .mode.name, '标准模式');
    assert.equal((await fetch(`${url}/v1/sessions/one/skills`, { headers }).then(r => r.json()))
      .items[0].name, 'office');
    assert.equal((await fetch(`${url}/v1/workspaces/any/sessions`, { method: 'POST', headers,
      body: JSON.stringify({ agentPreset: 'standard' }) }).then(r => r.json()))
      .session.title, 'standard');
    assert.equal((await fetch(`${url}/v1/sessions/one/model`, { method: 'POST', headers,
      body: JSON.stringify({ provider: 'test', model: 'm', reasoningEffort: 'high' }) })).status, 200);
    assert.deepEqual(selected[0], { id: 'one', provider: 'test', model: 'm', effort: 'high' });
    assert.equal((await fetch(`${url}/v1/sessions/one/tasks`, { method: 'POST', headers,
      body: JSON.stringify({ title: 'Reminder', prompt: 'Check', after_seconds: 60 }) })).status, 201);
    assert.equal((await fetch(`${url}/v1/sessions/one/tasks`, { headers }).then(r => r.json()))
      .items.length, 1);
    assert.equal((await fetch(`${url}/v1/sessions/one/tasks/task-1`, {
      method: 'DELETE', headers })).status, 204);
  } finally {
    await new Promise(resolve => instance.close(resolve));
  }
});

test('skill list uses the PC session-scoped catalog', async () => {
  const bridge = chatBridge({
    workspaceRegistry: { list: () => [{ sessionIds: ['session-1'] }] },
    sessionSkillCatalog: { list: async request => {
      assert.equal(request.sessionId, 'session-1');
      return { skills: [{ name: 'research', description: 'Research papers' }] };
    } },
  });
  assert.deepEqual(await bridge.skills('session-1'),
    [{ name: 'research', description: 'Research papers' }]);
  await assert.rejects(bridge.skills('another-session'), /Session not found/);
});
