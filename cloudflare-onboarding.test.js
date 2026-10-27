import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { test } from 'node:test';
import { createCloudflareOnboarding, relayUrlFromWranglerOutput } from './cloudflare-onboarding.js';

const account = { id: 'a'.repeat(32), name: 'My Cloudflare' };
const workerUrl = 'https://dsh-remote-signal.example.workers.dev';

async function until(check) {
  for (let i = 0; i < 100; i++) {
    if (check()) return;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error('Onboarding did not reach the expected state');
}

test('deployment output yields only a workers.dev WSS signal URL', () => {
  const output = [JSON.stringify({ type: 'info', targets: ['https://wrong.test'] }),
    JSON.stringify({ type: 'deploy', targets: [workerUrl] })].join('\n');
  assert.equal(relayUrlFromWranglerOutput(output),
    'wss://dsh-remote-signal.example.workers.dev/signal');
  assert.throws(() => relayUrlFromWranglerOutput(JSON.stringify({
    type: 'deploy', targets: ['https://wrong.test'],
  })), /workers.dev/);
});

test('existing login deploys to the selected account and saves its URL', async () => {
  let saved;
  const calls = [];
  const run = async (args, options) => {
    calls.push(args[0]);
    if (args[0] === 'whoami') return JSON.stringify({ loggedIn: true, accounts: [account] });
    assert.equal(options.env.CLOUDFLARE_ACCOUNT_ID, account.id);
    await writeFile(options.env.WRANGLER_OUTPUT_FILE_PATH,
      JSON.stringify({ type: 'deploy', targets: [workerUrl] }) + '\n');
    return '';
  };
  const onboarding = createCloudflareOnboarding(url => { saved = url; }, run);
  assert.equal(onboarding.begin().phase, 'authenticating');
  await until(() => onboarding.snapshot().phase === 'ready');
  assert.deepEqual(calls, ['whoami', 'deploy']);
  assert.equal(saved, 'wss://dsh-remote-signal.example.workers.dev/signal');
});

test('multiple Cloudflare accounts require an explicit choice', async () => {
  const second = { id: 'b'.repeat(32), name: 'Second Cloudflare' };
  let selected;
  const run = async (args, options) => {
    if (args[0] === 'whoami') return JSON.stringify({
      loggedIn: true, accounts: [account, second],
    });
    selected = options.env.CLOUDFLARE_ACCOUNT_ID;
    await writeFile(options.env.WRANGLER_OUTPUT_FILE_PATH,
      JSON.stringify({ type: 'deploy', targets: [workerUrl] }) + '\n');
    return '';
  };
  const onboarding = createCloudflareOnboarding(() => {}, run);
  onboarding.begin();
  await until(() => onboarding.snapshot().phase === 'select_account');
  assert.equal(onboarding.chooseAccount('invalid'), false);
  assert.equal(onboarding.chooseAccount(second.id), true);
  await until(() => onboarding.snapshot().phase === 'ready');
  assert.equal(selected, second.id);
});
