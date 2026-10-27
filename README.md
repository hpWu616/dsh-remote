# dsh Remote

[中文](README.md) · [English](README.en.md)

用 Android 手机连接运行 DeepSeek Harness（dsh）的电脑。项目由 [Android App](app/) 和 [dsh Host 插件](dsh-remote-host/)组成：手机可管理多台电脑的工作区与会话，发送消息，并使用 PC 端的模型、工作模式、技能和自动化任务。

## 下载

从私有仓库的 [最新 Release](https://github.com/hpWu616/dsh-remote/releases/latest) 下载：

| 文件 | 用途 |
| --- | --- |
| [dsh-remote-android-v2.7.apk](https://github.com/hpWu616/dsh-remote/releases/download/v2.7.0/dsh-remote-android-v2.7.apk) | 安装到 Android 手机 |
| [dsh-remote-host-v0.12.0.zip](https://github.com/hpWu616/dsh-remote/releases/download/v2.7.0/dsh-remote-host-v0.12.0.zip) | 解压后安装到每台运行 dsh 的电脑 |
| [SHA256SUMS.txt](https://github.com/hpWu616/dsh-remote/releases/download/v2.7.0/SHA256SUMS.txt) | 核对下载文件 |

仓库目前为私有状态，下载前需在浏览器登录有访问权限的 GitHub 账号。**APK 是调试签名版本**；若手机已安装另一台电脑构建的同包名 App，Android 可能因签名不同而拒绝覆盖安装。卸载旧版会清除手机保存的设备和配对信息，重新安装后需要再次配对。

## 安装 PC 插件

1. 在电脑下载并解压插件 ZIP，找到内含 `package.json` 和 `cordis.patch.yml` 的 `dsh-remote-host` 文件夹。
2. 打开 dsh 的「插件」页面，使用本地 bundle 安装功能选择**解压后的文件夹**。也可在 dsh Creator 模式调用 `plugin_manager` → `install_bundle`，将该文件夹的**绝对路径**填入 `target`。不要把 ZIP 文件本身作为目标。
3. 确认插件已启用，**完全退出并重新打开 dsh**。更新插件版本后也需重启。
4. 打开「插件 → 已安装 → 内置插件配置 → 手机连接」。此处可打开或关闭新手机配对、显示或隐藏 8 位配对码，并查看电脑地址与端口。收起后会保留一个小的「手机连接」入口。

不需要改动 dsh 的安装目录或用户配置文件。

## 安装 Android App

1. 用手机浏览器登录 GitHub，打开上面的 Release，点击 APK 文件下载。
2. 在手机「文件」或「下载」中找到**扩展名为 `.apk`** 的文件并打开。若系统提示限制安装，请按提示允许当前浏览器或文件管理器安装应用。
3. 安装后打开 dsh Remote。通过微信等聊天软件传文件时，「已接收」只表示传输完成；仍需在文件管理器中打开 APK。

## 连接与配对

1. 电脑和手机必须处于**可互通的局域网**。电脑使用网线、手机使用 Wi-Fi 没问题，但路由器不能隔离两者；8 位码本身无法打通不同网段。
2. 在 App 左上角菜单中点「发现」，选择电脑。若没有自动发现，先检查 PC 插件已运行及网络互通。
3. 在 PC 的「手机连接」中开启「允许新手机配对」，点「显示」查看 8 位码。手机选择该电脑，输入代码并点「配对设备」。每台电脑分别配对一次。
4. 配对后可以在 PC 隐藏配对码或关闭新手机配对；已配对的手机重新打开 App 时会自动检查连接。手机退到后台时不保持实时连接。

## 常用操作

- **工作区与会话：**「全部 PC 工作区」汇总已连接电脑的工作区。新建时在手机上的选择器浏览**PC 的磁盘和文件夹**；删除工作区只从 dsh 列表移除，原文件夹和会话保留。进入会话可查看历史，长按会话可查看详情或归档，已归档会话可恢复。
- **对话：**选择工作区后输入消息，点「发送」或键盘发送键。无会话时会自动新建；回复和历史会自动刷新。
- **模型与模式：**对话区可选择 PC 提供的模型、思考等级和工作模式。切换工作模式会创建该模式的新会话。
- **技能与搜索：**点「技能」选择当前会话可用的 skill，或手动输入 `/技能名称`。开启「智能搜索」后，PC 会尝试先联网检索；这要求 PC 上有可用的搜索提供方。
- **更多：**「＋」可添加不超过 8 MiB 的文件，调用 PC 原生的目标、计划、反馈和权限命令，搜索当前对话，或导出 JSONL 日志。「自动化任务」可为选中会话创建、查看和删除任务。

## 连接问题

- **未发现电脑：**确认 dsh 已重启、插件正在运行、手机与 PC 可互访；检查路由器的客户端隔离，以及 PC 防火墙对专用网络的 TCP 19388、UDP 19389 和 UDP 5353 的限制。
- **有配对码但无法连接：**配对码只用于认证。手机若无法访问 PC，输入相同数字也无法建立连接。PC「手机连接」中的地址和端口可用于排查网络。
- **无法覆盖安装 APK：**通常是两个调试 APK 的签名不同；先保留所需的手机端资料，再卸载旧版并安装 Release APK，随后重新配对。

插件提供的命令接口具有执行 PC 操作的能力，仅适合可信局域网；当前局域网 HTTP 没有传输加密，**不要把端口开放到公网**。协议、端口及认证细节见 [接口说明](docs/API.md)。

## 从源码构建

Windows 上准备 Android SDK 和 JDK 25，在仓库根目录运行：

```powershell
.\scripts\package.ps1
```

脚本会构建调试 APK，并在忽略提交的 `dist/` 目录生成 APK、插件 ZIP 和 SHA-256 校验文件。只构建 APK 可运行 `.\gradlew.bat :app:assembleDebug`；插件测试可在 `dsh-remote-host` 目录运行 `node --test`。Release 由 [GitHub Actions](.github/workflows/package-release.yml) 自动构建。

## 仓库目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 客户端 |
| `dsh-remote-host/` | PC 端 dsh 插件 |
| `scripts/package.ps1` | 本地打包脚本 |
| `docs/API.md` | 网络和接口说明 |

本机配置、IDE 文件、密钥和构建产物已由 [.gitignore](.gitignore) 排除。许可证见 [LICENSE](LICENSE)。
