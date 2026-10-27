# dsh Remote

[中文](README.md) · [English](README.en.md)

Use an Android phone to connect to a PC running DeepSeek Harness (dsh). This repository contains the [Android app](app/) and the [dsh Host plugin](dsh-remote-host/). The app can manage workspaces and conversations across multiple PCs, send messages, and use models, work modes, skills, and scheduled tasks available on the PC.

## Download

Get these files from the private repository's [latest release](https://github.com/hpWu616/dsh-remote/releases/latest):

| File | Purpose |
| --- | --- |
| [dsh-remote-android-v2.7.apk](https://github.com/hpWu616/dsh-remote/releases/download/v2.7.0/dsh-remote-android-v2.7.apk) | Install on an Android phone |
| [dsh-remote-host-v0.12.0.zip](https://github.com/hpWu616/dsh-remote/releases/download/v2.7.0/dsh-remote-host-v0.12.0.zip) | Extract and install on every PC running dsh |
| [SHA256SUMS.txt](https://github.com/hpWu616/dsh-remote/releases/download/v2.7.0/SHA256SUMS.txt) | Verify the downloads |

The repository is currently private, so sign in to a GitHub account with access before downloading. **The APK is debug signed.** Android may reject an update over a build signed on another machine. Uninstalling the old app erases its saved devices and pairing data; you will need to pair again after reinstalling.

## Install the PC plugin

1. Download and extract the plugin ZIP on the PC. Locate the `dsh-remote-host` directory containing `package.json` and `cordis.patch.yml`.
2. In dsh's Plugins page, use the local bundle installation option and select the **extracted directory**. Alternatively, in dsh Creator mode invoke `plugin_manager` → `install_bundle` with that directory's **absolute path** as `target`. Do not select the ZIP itself.
3. Confirm that the plugin is enabled, then **fully quit and restart dsh**. Restart again after updating the plugin.
4. Open “Plugins → Installed → Built-in plugin settings → Phone connection” (the dsh UI currently labels the last item “手机连接”). There you can allow or block new pairing, show or hide the eight-digit code, and see the PC address and port. Collapsing the panel leaves a small “手机连接” button.

Do not edit dsh's installed files or profile by hand.

## Install the Android app

1. On the phone, sign in to GitHub in a browser, open the release above, and download the APK.
2. Find the file with the **`.apk` extension** in Files or Downloads and open it. If Android blocks the installation, allow installs from the browser or file manager when prompted.
3. Open dsh Remote. When transferring the APK through a chat app, “received” only confirms the transfer; you still need to open the APK from a file manager.

## Connect and pair

1. The phone and PC must be on a **mutually reachable local network**. Ethernet for the PC and Wi-Fi for the phone are fine, provided the network does not isolate them. The eight-digit code cannot bridge separate, unreachable subnets.
2. Open the app's top-left menu and tap “发现” (Discover), then select the PC. If it is not found, check that the PC plugin is running and that the devices can communicate.
3. On the PC, enable “允许新手机配对” (Allow new phones to pair) under “手机连接”, then tap “显示” (Show) for the eight-digit code. Enter it for that PC in the app and tap “配对设备” (Pair device). Pair each PC separately.
4. You may hide the code or disable new pairing afterward. Previously paired devices are checked automatically when the app is reopened. The app does not maintain a live connection while in the background.

## Everyday use

- **Workspaces and conversations:** “全部 PC 工作区” (All PC workspaces) combines workspaces from connected PCs. To create one, browse the **PC's drives and folders** from the phone. Removing a workspace takes it out of the dsh list while retaining its folder and conversations. Open a conversation to read its history; long-press one for details or archiving. Archived conversations can be restored.
- **Chat:** Select a workspace, type a message, and tap “发送” (Send) or the keyboard's send key. A conversation is created automatically if needed; replies and history refresh in the app.
- **Models and modes:** Choose a PC-provided model, reasoning level, and work mode in the chat view. Changing work mode creates a new conversation in that mode.
- **Skills and search:** Tap “技能” (Skills) to choose an available skill, or type `/skill-name`. “智能搜索” (Smart search) asks the PC to search the web before answering and requires a configured search provider on the PC.
- **More tools:** “＋” lets you attach a file up to 8 MiB, invoke PC-native goal, plan, feedback, and permission commands, search the current conversation, or export its JSONL log. “自动化任务” (Scheduled tasks) lets you create, inspect, and delete tasks for the selected conversation.

## Troubleshooting

- **No PC found:** Restart dsh after installing the plugin. Check network isolation and the PC firewall's private-network rules for TCP 19388, UDP 19389, and UDP 5353.
- **Code shown, but no connection:** The code authenticates a reachable PC; it does not establish a network route. The address and port shown in the PC panel help diagnose reachability.
- **APK will not update:** Debug signatures from different builds may differ. Preserve any phone-side data you need before uninstalling the old app, installing the release APK, and pairing again.

The plugin can execute operations on the PC. Use it only on a trusted LAN; its current local HTTP connection has no transport encryption. **Do not expose its port to the public internet.** See [API and network details](docs/API.md).

## Build from source

On Windows, install the Android SDK and JDK 25, then run from the repository root:

```powershell
.\scripts\package.ps1
```

The script builds a debug APK and writes the APK, plugin ZIP, and SHA-256 checksums to the Git-ignored `dist/` directory. To build only the APK, run `.\gradlew.bat :app:assembleDebug`. Run `node --test` in `dsh-remote-host` for plugin tests. [GitHub Actions](.github/workflows/package-release.yml) builds the release assets.

## Repository layout

| Path | Contents |
| --- | --- |
| `app/` | Android client |
| `dsh-remote-host/` | PC-side dsh plugin |
| `scripts/package.ps1` | Local packaging script |
| `docs/API.md` | Network and API details |

Machine-specific settings, IDE files, signing keys, and build outputs are excluded by [.gitignore](.gitignore). See [LICENSE](LICENSE) for the license.
