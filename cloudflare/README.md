# Deploy your own relay / 部署自己的中继

The Android app and PC plugin do not use a shared public relay. Deploy this Worker in your own Cloudflare account for connections across different routers. The Worker forwards end-to-end encrypted messages; it needs a SQLite-backed Durable Object, but no Cloudflare TURN key or public PC port.

Android App 与 PC 插件不使用项目作者的公用中继。跨路由器连接前，请将本目录的 Worker 部署到自己的 Cloudflare 账号。Worker 转发端到端加密的消息，需要一个 SQLite Durable Object，不需要 Cloudflare TURN 密钥或 PC 公网端口。

## Plugin-assisted setup / 插件自动配置

Install Node.js and npm on the PC. In dsh, open **Plugins → Phone connection → Cross-network connection**. The plugin runs the official Wrangler CLI, opens Cloudflare authorization in your browser, deploys this Worker to the account you choose, and saves the resulting WSS address. If you have several Cloudflare accounts, choose the intended one in the plugin. Cloudflare may first ask you to enable a `workers.dev` subdomain. / 在 PC 安装 Node.js 与 npm，打开 dsh「插件 → 手机连接 → 跨网络连接」。插件调用官方 Wrangler，在浏览器中完成 Cloudflare 授权，将 Worker 部署到选定账号，并自动保存 WSS 地址。多个账户时需在插件中选择；Cloudflare 可能要求先开通 `workers.dev` 子域名。

## Browser setup / 浏览器部署

1. Open [Deploy to Cloudflare](https://deploy.workers.cloudflare.com/?url=https://github.com/hpWu616/dsh-remote/tree/main/cloudflare). Sign in to Cloudflare and GitHub, then select **your** Cloudflare account. / 打开链接，登录 Cloudflare 和 GitHub，选择**自己的** Cloudflare 账号。
2. Review the proposed Worker and Durable Object, then complete deployment. If prompted, enable a `workers.dev` subdomain in your account. / 检查页面中的 Worker 与 Durable Object，完成部署；如提示，先为账号开通 `workers.dev` 子域名。
3. Note the deployed `https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev` URL. Visit `https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/health` from both the PC and phone; it should return `{"ok":true}`. / 记下部署后的 HTTPS 地址，用电脑和手机浏览器分别访问其 `/health` 路径，确认返回 `{"ok":true}`。
4. In dsh, open **Plugins → Phone connection** and save `wss://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/signal`. Wait for relay status `online`. Copy the panel's `relay://…/<host-id>` address and PC identity code into the Android app, then pair with the eight-digit code shown on the PC. / 在 dsh「插件 → 手机连接」中保存对应的 `wss://…/signal` 地址。等中继状态变为 `online`，再将面板生成的 `relay://…/<电脑编号>` 地址和电脑身份码填入 App，并输入 PC 上显示的 8 位配对码。

One Worker can serve several PCs owned by the same user; configure the same WSS URL on each PC. Each PC retains its own host ID, identity code, and pairing code. / 同一用户的多台电脑可以共用自己的 Worker；每台电脑都需填写相同的 WSS 地址，但各自有独立的编号、身份码和配对码。

## Command-line alternative / 命令行备选

From this directory, run `npx wrangler login` and then `npx wrangler deploy`. Wrangler will print your Worker URL. / 在本目录运行 `npx wrangler login`、`npx wrangler deploy`；Wrangler 会输出 Worker 地址。

## Notes / 注意

- The plugin does not connect to any relay until you configure one. Local network discovery and HTTPS pairing work without Cloudflare. / 未配置时插件不会连接任何中继；局域网发现和 HTTPS 配对不依赖 Cloudflare。
- If an older installation has a saved relay URL, replace it in the PC panel. Changing the Worker hostname changes the `relay://` device address, so add the PC again in the app. / 旧版若保存过中继地址，请在 PC 面板中改为自己的地址；更换域名会改变 App 中的设备地址，需要重新添加该电脑。
- Both devices must be able to reach your Worker domain. Workers Free has [request and Durable Object limits](https://developers.cloudflare.com/durable-objects/platform/pricing/). / 手机和电脑均需能访问该域名；Workers 免费计划有请求和 Durable Object 用量限制。

[Cloudflare's Deploy button documentation](https://developers.cloudflare.com/workers/platform/deploy-buttons/) explains the deployment flow and automatic Durable Object provisioning. / Cloudflare 官方文档说明了部署按钮和 Durable Object 的自动创建流程。
