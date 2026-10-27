# dsh Remote

[中文](README.md) · [English](README.en.md)

Use an Android phone to connect to a PC running DeepSeek Harness (dsh). This repository contains the [Android app](app/) and a dsh Host plugin at the repository root. The app can manage workspaces and conversations across multiple PCs, send messages, and use models, work modes, skills, and scheduled tasks available on the PC. You need a PC with dsh, a phone running Android 7.0 or later, and a local network on which the two devices can reach each other.

## Download

Open the [latest release](https://github.com/hpWu616/dsh-remote/releases/latest) and download these files from **Assets** (not GitHub's automatically generated `Source code` archives):

| File | Purpose |
| --- | --- |
| `dsh-remote-android-v*.apk` | Install on an Android phone |
| `dsh-remote-host-v*.zip` | Extract and install on every PC running dsh |
| `SHA256SUMS.txt` | Verify the downloads |

**The current APK is debug signed.** Android may reject an update over a build signed on another machine. Uninstalling the old app erases its saved devices and pairing data; you will need to pair again after reinstalling.

## Install the PC plugin

1. In dsh's Plugins page, choose to install a third-party plugin and enter `github:hpWu616/dsh-remote` (or `https://github.com/hpWu616/dsh-remote`). The repository root is the plugin package, with `package.json` and `cordis.patch.yml`.
2. Confirm that the plugin is enabled, then **fully quit and restart dsh**. Restart again after updating the plugin.
3. Open “Plugins → Installed → Built-in plugin settings → Phone connection” (the dsh UI currently labels the last item “手机连接”). There you can control pairing and see the PC's IPv4 address, HTTPS port, and identity code. Collapsing the panel leaves a small “手机连接” button.

Install the plugin separately on every PC you want to connect. The new package name is `@hpwu616/dsh-remote-host`. If `@local/dsh-remote-host` is already installed, remove or disable it in dsh, fully quit dsh, and install the new package so both versions do not compete for the same port.

If dsh reports an installation timeout, run `git ls-remote https://github.com/hpWu616/dsh-remote.git HEAD` on the PC. If that command also cannot connect, GitHub is unreachable to Git on this PC; changing the plugin layout cannot fix that timeout. Download `dsh-remote-host-v*.zip` from the release instead, extract it, and select the extracted `dsh-remote-host` directory in dsh's local bundle installer. In Creator mode, you can also call `plugin_manager` → `install_bundle` with that directory's absolute path as `target`. Do not select the ZIP itself or edit dsh's installed files or profile by hand.

## Install the Android app

1. On the phone, open the release above in a browser and download the APK from Assets.
2. Find the file with the **`.apk` extension** in Files or Downloads and open it. If Android blocks the installation, allow installs from the browser or file manager when prompted.
3. Open dsh Remote. When transferring the APK through a chat app, “received” only confirms the transfer; you still need to open the APK from a file manager.

## Connect and pair

1. The phone must be able to reach the PC's address and HTTPS port. Manually added PCs can be on different routed private subnets. The eight-digit code cannot cross an isolated network by itself.
2. Tap Add device and enter the PC's `https://IPv4:19390`, for example `https://10.15.223.91:19390`. Compare the identity code shown on the phone with the code in the PC's Phone connection panel, then confirm only if they match. Give each PC its own name and note. Replace saved HTTP addresses with the HTTPS address shown on the PC, then verify the identity code again.
3. On the PC, enable “允许新手机配对” (Allow new phones to pair) under “手机连接”, then tap “显示” (Show) for the eight-digit code. Enter it for that PC in the app and tap “配对设备” (Pair device). Verify and pair each PC separately.
4. You may hide the code or disable new pairing afterward. Previously paired devices are checked automatically when the app is reopened. The app does not maintain a live connection while in the background.

## Everyday use

- **Workspaces and conversations:** “全部 PC 工作区” (All PC workspaces) combines workspaces from connected PCs. To create one, browse the **PC's drives and folders** from the phone. Removing a workspace takes it out of the dsh list while retaining its folder and conversations. Open a conversation to read its history; long-press one for details or archiving. Archived conversations can be restored.
- **Chat:** Select a workspace, type a message, and tap “发送” (Send) or the keyboard's send key. A conversation is created automatically if needed; replies and history refresh in the app.
- **Models and modes:** Choose a PC-provided model, reasoning level, and work mode in the chat view. Changing work mode creates a new conversation in that mode.
- **Skills and search:** Tap “技能” (Skills) to choose an available skill, or type `/skill-name`. “智能搜索” (Smart search) asks the PC to search the web before answering and requires a configured search provider on the PC.
- **More tools:** “＋” lets you attach a file up to 8 MiB, invoke PC-native goal, plan, feedback, and permission commands, search the current conversation, or export its JSONL log. “自动化任务” (Scheduled tasks) lets you create, inspect, and delete tasks for the selected conversation.

## Troubleshooting

- **No PC found:** Automatic discovery needs a broadcast LAN. Across subnets, enter `https://PC-IPv4:19390` manually. Confirm the plugin has been updated and dsh restarted, and that TCP 19390 is reachable. A browser may warn about the self-signed certificate at `https://PC-IPv4:19390/v1/health`; verify the identity code in the app before pairing.
- **Saved `.local` address is offline:** Edit the device and enter its `https://IPv4:19390` address. Compare identity codes and save. The app migrates an existing token when the PC identity matches.
- **Plugin installer says no write permission:** If the installation details show `ERR_PNPM_EPERM` while renaming the old `@local/dsh-remote-host` directory, remove or disable the old plugin in dsh, fully quit dsh, and install the new package. Do not edit the dsh profile by hand.
- **Code shown, but no connection:** The code authenticates a reachable PC; it does not establish a network route. The address and port shown in the PC panel help diagnose reachability.
- **APK will not update:** Debug signatures from different builds may differ. Preserve any phone-side data you need before uninstalling the old app, installing the release APK, and pairing again.

The plugin can execute operations on the PC. All device connections use fingerprint-verified HTTPS. **Do not expose any plugin port to the public internet.** See [API and network details](docs/API.md).

## Build from source

On Windows, install the Android SDK and JDK 25, then run from the repository root:

```powershell
.\scripts\package.ps1
```

The script builds a debug APK and writes the APK, plugin ZIP, and SHA-256 checksums to the Git-ignored `dist/` directory. To build only the APK, run `.\gradlew.bat :app:assembleDebug`. Run `npm test` at the repository root for plugin tests. [GitHub Actions](.github/workflows/package-release.yml) builds release assets.

## Repository layout

| Path | Contents |
| --- | --- |
| `app/` | Android client |
| `package.json`, `cordis.patch.yml`, `index.js`, etc. | PC-side dsh plugin; the repository root is installable as a Git package |
| `scripts/package.ps1` | Local packaging script |
| `docs/API.md` | Network and API details |

Machine-specific settings, IDE files, signing keys, and build outputs are excluded by [.gitignore](.gitignore). See [LICENSE](LICENSE) for the license.
