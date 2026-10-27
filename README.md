# dsh Remote

[中文](README.md) · [English](README.en.md)

用 Android 手机连接运行 DeepSeek Harness（dsh）的电脑。项目由 [Android App](app/)、仓库根目录的 dsh Host 插件和 [Cloudflare WebSocket 中继](cloudflare/) 组成：手机可管理多台电脑的工作区与会话，发送消息，并使用 PC 端的模型、工作模式、技能和自动化任务。手机与 PC 可在不同路由器下使用，但两端都必须能连接同一个中继服务。

## 下载

打开 [最新 Release](https://github.com/hpWu616/dsh-remote/releases/latest)，在 **Assets** 中下载以下文件（不要下载 GitHub 自动生成的 `Source code` 压缩包）：

| 文件 | 用途 |
| --- | --- |
| `dsh-remote-android-v*.apk` | 安装到 Android 手机 |
| `dsh-remote-host-v*.zip` | 解压后安装到每台运行 dsh 的电脑 |
| `SHA256SUMS.txt` | 核对下载文件 |

当前发布的 **APK 是调试签名版本**；若手机已安装另一台电脑构建的同包名 App，Android 可能因签名不同而拒绝覆盖安装。卸载旧版会清除手机保存的设备和配对信息，重新安装后需要再次配对。

## 安装 PC 插件

1. 在 dsh「插件」页面选择安装第三方插件，输入 `github:hpWu616/dsh-remote`（也可输入 `https://github.com/hpWu616/dsh-remote`）。仓库根目录就是插件包，包含 `package.json` 和 `cordis.patch.yml`。
2. 确认插件已启用，**完全退出并重新打开 dsh**。更新插件版本后也需重启。
3. 打开「插件 → 已安装 → 内置插件配置 → 手机连接」。此处可打开或关闭新手机配对、显示或隐藏 8 位配对码，并查看电脑 IPv4、HTTPS 端口、身份码和远程地址。收起后会保留一个小的「手机连接」入口。

每台要连接的电脑都需单独安装插件。本版插件包名为 `@hpwu616/dsh-remote-host`；如果此前安装了 `@local/dsh-remote-host`，请先在 dsh 中卸载或停用旧插件，完全退出 dsh 后再安装新版，避免两个插件同时占用端口。

如果 dsh 显示“安装超时”，先在电脑终端运行 `git ls-remote https://github.com/hpWu616/dsh-remote.git HEAD`。此命令也无法连接时，说明电脑的 Git 无法访问 GitHub；修改插件文件结构不能解决网络超时。此时可从 Release 下载 `dsh-remote-host-v*.zip`，解压后在 dsh「插件」页面选择本地目录安装，或在 Creator 模式调用 `plugin_manager` → `install_bundle`，将解压后的 `dsh-remote-host` 文件夹绝对路径填入 `target`。不要选择 ZIP 文件本身，也不要修改 dsh 的安装文件或 profile。

不需要改动 dsh 的安装目录或用户配置文件。

## 安装 Android App

1. 用手机浏览器打开上面的 Release，在 Assets 中点击 APK 文件下载。
2. 在手机「文件」或「下载」中找到**扩展名为 `.apk`** 的文件并打开。若系统提示限制安装，请按提示允许当前浏览器或文件管理器安装应用。
3. 安装后打开 dsh Remote。如果通过聊天软件传输 APK，「已接收」只表示传输完成；仍需在文件管理器中打开安装包。

## 不同路由器远程连接

插件不预设任何公用中继。每位需要跨路由器连接的用户都在**自己的 Cloudflare 账号**部署 Worker，然后在每台 PC 的插件中填写自己的地址；手机只需使用 PC 面板生成的设备地址。

1. 点击 [部署到 Cloudflare](https://deploy.workers.cloudflare.com/?url=https://github.com/hpWu616/dsh-remote/tree/main/cloudflare)，登录 Cloudflare 和 GitHub，选择自己的 Cloudflare 账号，按页面提示创建 Worker 与 Durable Object 并完成部署。详细步骤和命令行备选方案见 [中继部署说明](cloudflare/README.md)。
2. 记下部署完成后的 `https://你的Worker.你的子域名.workers.dev`，先在浏览器打开该地址后加 `/health`，确认显示 `{"ok":true}`。
3. 在每台 PC 的「插件 → 手机连接」中填写 `wss://你的Worker.你的子域名.workers.dev/signal` 并保存。等待状态变为 `online`，再复制面板生成的 `relay://…/<电脑编号>` 地址及前 24 位电脑身份码。首次使用请开启「允许新手机配对」并显示 8 位码。
4. 在 App 中点「＋添加」，填写设备名称、复制的 `relay://…` 地址和 PC 身份码。选择设备，输入 PC 当前显示的 8 位码完成配对。之后可以关闭新手机配对；重新打开 App 时会自动连接。

多台 PC 可以使用同一个自有 Worker，但每台 PC 的设备地址、身份码和配对码不同。更新旧版插件后，如已保存旧中继地址，请在「手机连接」中改成自己的 Worker 地址；更换中继域名后，需要在 App 中重新添加相应设备。局域网连接无需部署 Worker。手机不需要安装 Tailscale，也不需要开放路由器端口。

中继只转发经过端到端加密的帧。手机在首次连接时核对 PC 证书身份码，后续检查 PC 签名；Cloudflare 无法读取会话内容或配对令牌。双方都必须能访问所部署的域名。Workers 免费计划无需 TURN 密钥；免费额度耗尽时中继可能暂时不可用，见 [Cloudflare 官方额度](https://developers.cloudflare.com/durable-objects/platform/pricing/)。

## 局域网连接与配对

1. 手机必须能访问目标电脑的地址和 HTTPS 端口；同一可路由的私有网络内，即使电脑处于不同网段也可以手动添加。跨路由器且无法直连时请用上面的中继方式。
2. 在 App 点「＋添加」，输入该电脑的 `https://IPv4:19390`，例如 `https://10.15.223.91:19390`。App 会显示电脑身份码；请与 PC「手机连接」中的身份码逐组核对，一致后点「一致，保存」。每台电脑可设置不同名称和备注。已保存的旧 HTTP 地址需在「编辑资料」中换为 HTTPS 地址并重新核对身份码。
3. 在 PC 的「手机连接」中开启「允许新手机配对」，点「显示」查看 8 位码。手机选择该电脑，输入代码并点「配对设备」。每台电脑分别核对身份并配对一次。
4. 配对后可以在 PC 隐藏配对码或关闭新手机配对；已配对的手机重新打开 App 时会自动检查连接。手机退到后台时不保持实时连接。

## 常用操作

- **工作区与会话：**「全部 PC 工作区」汇总已连接电脑的工作区。新建时在手机上的选择器浏览**PC 的磁盘和文件夹**；删除工作区只从 dsh 列表移除，原文件夹和会话保留。进入会话可查看历史，长按会话可查看详情或归档，已归档会话可恢复。
- **对话：**选择工作区后输入消息，点「发送」或键盘发送键。无会话时会自动新建；回复和历史会自动刷新。
- **模型与模式：**对话区可选择 PC 提供的模型、思考等级和工作模式。切换工作模式会创建该模式的新会话。
- **技能与搜索：**点「技能」选择当前会话可用的 skill，或手动输入 `/技能名称`。开启「智能搜索」后，PC 会尝试先联网检索；这要求 PC 上有可用的搜索提供方。
- **更多：**「＋」可添加不超过 8 MiB 的文件，调用 PC 原生的目标、计划、反馈和权限命令，搜索当前对话，或导出 JSONL 日志。「自动化任务」可为选中会话创建、查看和删除任务。

## 连接问题

- **未发现电脑：**自动发现仅在可广播的局域网中工作。跨网段请手动输入 `https://电脑IPv4:19390`；检查 PC 插件已更新并重启，以及防火墙是否允许 dsh 的 TCP 19390。浏览器访问该地址可能提示自签名证书，请在 App 中核对身份码后配对。
- **远程地址离线：**查看 PC「手机连接」中的中继状态；手机和 PC 都需要能访问所配置的 `workers.dev` 域名。状态不是 `online` 时，检查该域名在 PC 浏览器是否可打开 `/health`；网络阻断时可改用自己的 Cloudflare Worker 域名。远程地址必须以 `relay://` 开头，不能填 `wss://`。
- **已保存 `.local` 地址却离线：**在设备的「编辑资料」中把连接地址改为该 PC 的 `https://IPv4:19390`，核对身份码后保存。如果电脑的配对身份未变，App 会迁移原有令牌。
- **插件安装提示没有写入权限：**若安装详情含 `ERR_PNPM_EPERM` 及 `@local/dsh-remote-host` 的目录重命名失败，通常是旧包目录被占用。请在 dsh 中卸载或停用旧插件，完全退出 dsh 后安装当前新版包；不要手工修改 dsh 的 profile。
- **有配对码但无法连接：**配对码只用于认证。手机若无法访问 PC，输入相同数字也无法建立连接。PC「手机连接」中的地址和端口可用于排查网络。
- **无法覆盖安装 APK：**通常是两个调试 APK 的签名不同；先保留所需的手机端资料，再卸载旧版并安装 Release APK，随后重新配对。

插件提供的命令接口具有执行 PC 操作的能力。局域网连接使用身份码核对的 HTTPS，远程连接使用身份码核对的端到端加密 WebSocket 中继；**不要把插件端口开放到公网**。协议、端口及认证细节见 [接口说明](docs/API.md)。

## 从源码构建

Windows 上准备 Android SDK 和 JDK 25，在仓库根目录运行：

```powershell
.\scripts\package.ps1
```

脚本会构建调试 APK，并在忽略提交的 `dist/` 目录生成 APK、插件 ZIP 和 SHA-256 校验文件。只构建 APK 可运行 `.\gradlew.bat :app:assembleDebug`；在仓库根目录运行 `npm ci`、`npm run build:relay` 和 `npm test` 可构建并测试插件。Release 由 [GitHub Actions](.github/workflows/package-release.yml) 构建。

## 仓库目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 客户端 |
| `package.json`、`cordis.patch.yml`、`index.js` 等 | PC 端 dsh 插件；仓库根目录可作为 Git 插件包安装 |
| `scripts/package.ps1` | 本地打包脚本 |
| `cloudflare/` | 免费 WebSocket 中继 Worker；部署方法见该目录说明 |
| `docs/API.md` | 网络和接口说明 |

本机配置、IDE 文件、密钥和构建产物已由 [.gitignore](.gitignore) 排除。许可证见 [LICENSE](LICENSE)。
