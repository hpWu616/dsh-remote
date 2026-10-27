import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rmdir, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const WRANGLER = 'wrangler@4.119.0';
const WORKER_NAME = 'dsh-remote-signal';
const WORKER_DIR = fileURLToPath(new URL('./cloudflare/', import.meta.url));

export function relayUrlFromWranglerOutput(output) {
  const events = output.split(/\r?\n/).filter(Boolean);
  for (const line of events.reverse()) {
    let event;
    try { event = JSON.parse(line); } catch { continue; }
    if (event.type !== 'deploy' || !Array.isArray(event.targets)) continue;
    for (const target of event.targets) {
      try {
        const url = new URL(target);
        if (url.protocol === 'https:' && url.username === '' && url.password === ''
            && url.pathname === '/' && !url.search && !url.hash
            && /^[a-z0-9.-]+\.workers\.dev$/i.test(url.hostname)) {
          return `wss://${url.hostname}/signal`;
        }
      } catch { /* Ignore targets that are not a usable workers.dev URL. */ }
    }
  }
  throw new Error('部署完成，但未找到 workers.dev 地址；请在 Cloudflare 控制台确认已启用 workers.dev');
}

function runWrangler(args, options = {}) {
  const command = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const child = spawn(command, ['--yes', WRANGLER, ...args], {
    cwd: WORKER_DIR,
    env: { ...process.env, WRANGLER_SEND_METRICS: 'false',
      WRANGLER_SEND_ERROR_REPORTS: 'false', ...options.env },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    shell: process.platform === 'win32',
  });
  options.onChild?.(child);
  return new Promise((resolve, reject) => {
    let stdout = '';
    const collect = chunk => {
      if (stdout.length < 64_000) stdout += chunk.toString('utf8').slice(0, 64_000 - stdout.length);
    };
    child.stdout.on('data', collect);
    child.stderr.resume();
    const timer = setTimeout(() => child.kill(), options.timeout ?? 180_000);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', code => {
      clearTimeout(timer);
      if (code === 0) resolve(stdout);
      else reject(new Error(code === null ? 'Wrangler 已中断' : `Wrangler 退出码 ${code}`));
    });
  });
}

export function createCloudflareOnboarding(onDeployed, run = runWrangler) {
  let state = { phase: 'idle', message: '' };
  let stopped = false;
  let activeChild;
  let selectAccount;
  const snapshot = () => ({ ...state });
  const invoke = (args, options = {}) => run(args, {
    ...options, onChild: child => { activeChild = child; },
  });
  const begin = () => {
    if (stopped || !['idle', 'ready', 'error'].includes(state.phase)) return snapshot();
    state = { phase: 'authenticating', message: '正在检查 Cloudflare 登录状态…' };
    void (async () => {
      try {
        let whoami;
        try { whoami = JSON.parse(await invoke(['whoami', '--json'], { timeout: 60_000 })); }
        catch { whoami = null; }
        if (!whoami?.loggedIn) {
          state = { phase: 'authenticating', message: '请在浏览器完成 Cloudflare 授权…' };
          await invoke(['login', '--device', '--use-keyring'], { timeout: 360_000 });
          whoami = JSON.parse(await invoke(['whoami', '--json'], { timeout: 60_000 }));
        }
        if (!whoami?.loggedIn || !Array.isArray(whoami.accounts)
            || whoami.accounts.length === 0) {
          throw new Error('Cloudflare 登录成功，但未发现可用账户');
        }
        const accounts = whoami.accounts.filter(account =>
          /^[a-f0-9]{32}$/i.test(account.id) && typeof account.name === 'string');
        if (!accounts.length) throw new Error('未发现可部署 Worker 的 Cloudflare 账户');
        let accountId = accounts[0].id;
        if (accounts.length > 1) {
          state = { phase: 'select_account', message: '请选择要部署中继的 Cloudflare 账户',
            accounts: accounts.map(({ id, name }) => ({ id, name })) };
          accountId = await new Promise(resolve => { selectAccount = resolve; });
          selectAccount = null;
          if (!accountId || stopped) return;
        }
        state = { phase: 'deploying', message: '正在部署你的 Cloudflare 中继…' };
        const directory = await mkdtemp(join(tmpdir(), 'dsh-remote-wrangler-'));
        const outputFile = join(directory, 'deploy.ndjson');
        try {
          await invoke(['deploy', '--config', 'wrangler.jsonc', '--name', WORKER_NAME], {
            timeout: 300_000,
            env: { CLOUDFLARE_ACCOUNT_ID: accountId,
              WRANGLER_OUTPUT_FILE_PATH: outputFile },
          });
          if (stopped) return;
          const url = relayUrlFromWranglerOutput(await readFile(outputFile, 'utf8'));
          await onDeployed(url);
          if (!stopped) state = { phase: 'ready', message: '中继地址已自动保存', url };
        } finally {
          await unlink(outputFile).catch(() => {});
          await rmdir(directory).catch(() => {});
        }
      } catch (error) {
        if (!stopped) state = { phase: 'error', message: error.message.includes('ENOENT')
          ? '未找到 Node.js/npm；请先在 PC 安装 Node.js 后重试'
          : error.message };
      }
    })();
    return snapshot();
  };
  return {
    snapshot,
    begin,
    chooseAccount(id) {
      if (state.phase !== 'select_account' ||
          !state.accounts.some(account => account.id === id)) return false;
      selectAccount?.(id);
      return true;
    },
    stop() {
      stopped = true;
      selectAccount?.(null);
      activeChild?.kill();
    },
  };
}
