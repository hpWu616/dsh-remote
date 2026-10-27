# dsh Remote 使用说明

手机 App 通过独立的 dsh Host 插件连接多台电脑，管理设备资料、工作区和 dsh 会话，可从手机发送消息并查看回复，也可在工作区执行命令。PC 端「插件 → 已安装 → 内置插件配置」列表里有「手机连接」入口；点开后可开启或关闭新设备配对、选择显示或隐藏 8 位配对码，并查看电脑的 IPv4 地址、网段、连接地址和服务端口。

## 构建与安装

安装 Android SDK 和项目要求的 JDK 后，在项目根目录用 PowerShell 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建结果为 `app\build\outputs\apk\debug\app-debug.apk`。插件源码在 `dsh-remote-host/`；下载插件压缩包后先解压，通过 dsh Creator 模式的 `plugin_manager` → `install_bundle`，把解压所得 `dsh-remote-host` 文件夹的绝对路径传给 `target`；也可在 dsh 的 Plugins 页面安装本地 bundle。确认插件已启用。更新插件代码后重启 dsh。不要手动修改 dsh 安装目录或 desktop profile。

在 Windows PowerShell 中运行 `.\scripts\package.ps1` 可一次构建 APK、封装插件，并在 `dist/` 生成 SHA-256 校验文件。已构建 APK 时可运行 `.\scripts\package.ps1 -SkipBuild`。`dist/`、本机配置、IDE 文件和密钥文件不会进入源码仓库。

## 配对与使用

1. 在每台电脑安装并启用同一 Host 插件，然后完全退出并重新打开 dsh。进入「插件 → 已安装 → 内置插件配置 → 手机连接」，打开新设备配对并显示配对码。关闭配对后，已配对手机仍可连接。
2. 将新版 APK 安装到手机。手机与各台 PC 须在可互通的同一局域网；PC 用网线、手机用 Wi-Fi 可以。手机点左上角菜单后选择「发现」，或重新打开 App，自动加入新电脑，无需输入 IP。首页顶部显示 deepseek HARNESS 标识，中央显示「探索未至之境」。
3. 为设备编辑名称和备注，选中设备后输入该 PC 的 8 位码并点「配对设备」。每台 PC 独立配对一次；重新打开 App 会并行检查已保存的设备。
4. 「全部 PC 工作区」会汇总已连接电脑的 dsh 工作区，并定时同步 PC 端的增删。点「新建」后输入名称，再在手机上的文件夹选择器中点选该 PC 的磁盘和文件夹；工作区会使用选中的文件夹。点工作区切换对应 PC，点旁边的「删除」可在确认后从 PC 的 dsh 列表移除工作区，原文件夹和会话保留。下方「当前工作区 · 对话」列出会话及所属文件夹；点会话进入对话，长按可查看详情、分页历史并归档。已归档会话可恢复。手机键盘的发送键和页面的「发送」按钮都可发送；若还没有会话，会自动新建。回复自动刷新。远程命令入口仍在设备面板中。
5. 在对话区点「模型」查看 PC 可选模型，点「思考等级」查看当前模型支持的等级；所选等级显示在按钮上。点「模式」打开可用工作模式列表，当前模式在列表中打勾，选择后新建相应模式的对话；「自动化任务」可为选中会话创建按分钟延后或定期重复的任务、查看状态并删除。
6. 对话输入框固定在页面底部。点「技能」可读取当前会话可调用的 skill，列表只显示名称和短说明，选择后会把 `/技能名称` 插入输入框；也可手动输入该命令并发送。点「智能搜索」后发送文本消息，PC 插件会先调用 dsh 的联网搜索服务，把检索到的来源交给当前会话作答；PC 必须配置可用的搜索提供方。点「＋」可附加不超过 8 MiB 的文件、调用 PC 原生的目标/计划/反馈/权限命令、搜索当前对话或导出当前会话的 JSONL 日志。回复和历史记录会排版标题、列表、粗体、链接与代码块。手机只展示真实用户消息和 dsh 最终回答；有记录的推理内容可点「查看思考过程」展开。

USB 调试可执行 `adb reverse tcp:19388 tcp:19388`，然后在手机端改用 `http://127.0.0.1:19388`。切换到另一个服务地址时需要为该地址重新配对。
旧版保存的 `10.7.32.43` 设备在发现同一台 PC 后，会迁移到稳定的 `.local` 地址并保留名称、备注和令牌。

插件监听 `127.0.0.1:19388` 和电脑的私有 IPv4 地址，使用 UDP 19389 发现设备，并用 mDNS 5353 解析该设备的 `dsh-<标识>.local` 名称。手机扫描会向通用广播地址和其网卡的子网广播地址发出探测，并在扫描期间保持 Wi-Fi 多播接收。Android 12 及更新系统支持系统级 `.local` 解析；较旧系统可继续使用已配置的固定地址。成功配对后数字码更新；当前数字码、配对开关和令牌由 dsh 凭据服务持久保存，重启 dsh 后仍有效。Android Keystore 分别加密保存每台设备的令牌。Android 17 首次连接时会申请局域网权限。手机进入后台时不保持实时连接，重新打开 App 会自动检查设备。

Android 网络配置仅对 `.local` 域、原来的 `10.7.32.43` 和 USB 回环地址放行明文 HTTP；App 代码进一步只接受发现协议生成的 `dsh-<标识>.local` 名称。该端口可以执行电脑命令，不应开放到公网。局域网 HTTP 没有传输加密；若网络不可信，需先加 TLS 或安全隧道。若发现不到 PC，检查路由器是否允许有线与 Wi-Fi 设备互访，以及 PC 防火墙对专用网络的 TCP 19388、UDP 19389 和 UDP 5353 的限制。

## 接口约定

| 请求 | 内容 |
| --- | --- |
| `POST /v1/pair` | JSON `{"code":"8位数字"}`，返回随机令牌 |
| `GET /v1/health` | `Authorization: Bearer <令牌>`，检查连接 |
| `POST /v1/exec` | 同一认证头，JSON `{"command":"..."}`，以 NDJSON 流返回 `stdout`、`stderr`、`error`、`exit` 事件 |
| `GET /v1/workspaces` | 返回该 PC 中全部 dsh 工作区，需令牌 |
| `GET /v1/directories?path=...` | 浏览该 PC 的磁盘和文件夹，需令牌 |
| `POST /v1/workspaces` | JSON `{"name":"工作区名","path":"PC 上的绝对文件夹路径"}`，在所选文件夹创建 dsh 工作区，需令牌 |
| `DELETE /v1/workspaces/:id` | 从 dsh 列表移除工作区，保留原文件夹和会话，需令牌 |
| `GET/POST /v1/workspaces/:id/sessions` | 列出或新建该工作区的 dsh 会话，需令牌 |
| `GET /v1/modes` | 列出 PC 上可用的工作模式，需令牌 |
| `GET /v1/sessions/:id/mode` | 读取该会话当前工作模式，需令牌 |
| `GET /v1/models`、`POST /v1/sessions/:id/model` | 读取模型目录，选择模型及推理等级，需令牌 |
| `GET/POST /v1/sessions/:id/messages` | 读取最近消息或发送新消息；POST 可带 `search:true` 先由 PC 联网检索，需令牌 |
| `POST /v1/sessions/:id/files` | 将手机文件上传到当前 dsh 会话，返回文件凭据，需令牌 |
| `POST /v1/sessions/:id/commands` | 运行受限的 PC 原生会话命令，包括目标、计划、反馈及权限，需令牌 |
| `GET /v1/sessions/:id/log` | 下载当前会话的 JSONL 事件日志，需令牌 |
| `GET /v1/sessions/:id/skills` | 读取该会话可由用户调用的 skill 列表，需令牌 |
| `GET/POST /v1/sessions/:id/tasks`、`DELETE /v1/sessions/:id/tasks/:taskId` | 查询、新建及删除自动化任务，需令牌 |
| `GET /v1/sessions/:id/history?before=...` | 分页读取会话历史，需令牌 |
| `POST /v1/sessions/:id/archive` | JSON `{"archived":true/false}`，归档或恢复会话，需令牌 |

同时最多执行一个命令，运行上限为 120 秒，输出上限为 1 MiB。插件不记录令牌。
