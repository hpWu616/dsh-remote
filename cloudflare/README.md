# WebSocket relay / WebSocket 中继

The Worker forwards encrypted frames between one dsh Host plugin and one phone. It does not need Cloudflare TURN, a public PC port, or a payment method. It uses a SQLite-backed Durable Object available on Workers Free. Both devices still need to reach the Worker hostname.

此 Worker 在一台 PC 插件与一部手机之间转发加密消息。不需要 Cloudflare TURN、公网 PC 端口或支付方式；使用 Workers 免费计划可用的 SQLite Durable Object。手机和 PC 都必须能访问 Worker 域名。

## Deploy / 部署

From the repository root / 在仓库根目录：

```powershell
npm ci
npx wrangler login
cd cloudflare
npx wrangler deploy
```

Create your account's `workers.dev` subdomain if Wrangler requests it. The deployment output gives a URL such as `https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev`. In the PC plugin's **手机连接** panel, set `wss://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/signal` and save. Wait for status `online`; copy the displayed `relay://…/<host-id>` address to the Android app and compare the PC identity code. Each PC can use the same Worker URL but has its own host ID, identity code, and pairing code.

若 Wrangler 提示创建 `workers.dev` 子域名，先在账号中设置。部署输出会给出 `https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev`。在 PC 插件「手机连接」中填写 `wss://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/signal` 并保存；等待状态显示 `online`，再将面板显示的 `relay://…/<电脑编号>` 地址填入 App，并核对 PC 身份码。多台 PC 可共用 Worker URL，但各有自己的编号、身份码和配对码。

`GET /health` returns `{ "ok": true }`. The Worker stores a hash of each host's relay registration secret and temporary pairing-code mappings. It does not receive the dsh API bearer token or decrypt API frames. The phone verifies the PC certificate fingerprint and an ephemeral ECDH handshake signed by that PC. A PC can disable new pairing without invalidating an already paired phone.

Worker Free has [request and Durable Object usage limits](https://developers.cloudflare.com/durable-objects/platform/pricing/); connectivity can stop when the allowance is exhausted. Keep the Worker URL stable after pairing; changing it requires adding each PC again in the app. If `workers.dev` is inaccessible from either network, deploy under a reachable domain you control.
