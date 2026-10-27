# dsh Remote: network and API

[中文使用说明](../README.md) · [English user guide](../README.en.md)

The Android app talks to the dsh Host plugin on each PC. Locally, the plugin listens on TCP 19390 for HTTPS, uses UDP 19389 for LAN discovery, and advertises an mDNS `dsh-<device-id>.local` name on UDP 5353. Across routers, the PC and phone connect outbound to the Cloudflare WebSocket relay. The PC's existing dsh web server is separate from these listeners.

## Pairing and transport

1. The PC displays an eight-digit, one-time code only while new-device pairing is enabled.
2. `POST /v1/pair` exchanges the code for a random long-lived bearer token. A successful pairing rotates the code.
3. Each PC generates a separate self-signed TLS certificate and stores its private key in dsh's credential service. On first manual connection, the phone displays the certificate's first 96 fingerprint bits; the user compares them with the PC panel. The phone pins the full SHA-256 certificate fingerprint and stores each device token with Android Keystore protection.
4. All remaining endpoints require `Authorization: Bearer <token>`.

The endpoint can invoke PC operations, including commands; do not forward the HTTPS listener to the internet. Manual HTTPS connection works across reachable private subnets and does not depend on mDNS or UDP. For isolated networks, use the relay described below.

## Encrypted WebSocket relay

The PC connects to the configured `wss://…/signal` endpoint and registers its 12-character host ID with a secret derived from its stored pairing token. The phone connects to the same endpoint by host ID or eight-digit code. The PC validates the code or a nonce-bound HMAC proof from a previously paired phone. A successful code pairing rotates the code.

Both sides generate ephemeral P-256 ECDH keys. The PC signs the challenge and both public keys with the private key of its existing HTTPS identity certificate. The phone checks the pinned certificate fingerprint and signature before deriving a per-connection AES-256-GCM key. Frame sequence numbers and distinct direction IVs reject replay and tampering. The PC relays authenticated `/v1/*` requests to its loopback HTTPS listener. The Worker sees host IDs, temporary codes, and encrypted frame sizes, but not API bodies or bearer tokens. One phone can be active per host at a time. The Worker has rate limits on phone joins and message-size limits; see [Worker source](../cloudflare/worker.js).

## HTTPS endpoints

| Endpoint | Purpose |
| --- | --- |
| `POST /v1/pair` | Exchange `{\"code\":\"12345678\"}` for a token |
| `GET /v1/health` | Check an authenticated connection |
| `POST /v1/exec` | Run a PC command; stream `stdout`, `stderr`, `error`, and `exit` as NDJSON |
| `GET /v1/workspaces` | List dsh workspaces on this PC |
| `GET /v1/directories?path=...` | Browse PC drives and directories |
| `POST /v1/workspaces` | Create a workspace from a name and absolute PC folder path |
| `DELETE /v1/workspaces/:id` | Remove a workspace from dsh without deleting its folder or conversations |
| `GET/POST /v1/workspaces/:id/sessions` | List or create conversations |
| `GET /v1/modes` | List PC work modes |
| `GET /v1/sessions/:id/mode` | Get the active work mode |
| `GET /v1/models` | List PC models and reasoning levels |
| `POST /v1/sessions/:id/model` | Select a model and reasoning level |
| `GET/POST /v1/sessions/:id/messages` | Read recent messages or send one; POST may enable web search |
| `POST /v1/sessions/:id/files` | Attach a phone file to a dsh conversation |
| `POST /v1/sessions/:id/commands` | Run supported dsh conversation commands |
| `GET /v1/sessions/:id/log` | Export the conversation's JSONL event log |
| `GET /v1/sessions/:id/skills` | List user-invocable skills |
| `GET/POST /v1/sessions/:id/tasks` | List or create scheduled tasks |
| `DELETE /v1/sessions/:id/tasks/:taskId` | Delete a scheduled task |
| `GET /v1/sessions/:id/history?before=...` | Page through conversation history |
| `POST /v1/sessions/:id/archive` | Archive or restore a conversation |

The command endpoint permits one command at a time, with a 120-second runtime limit and a 1 MiB output limit. Phone attachments are limited to 8 MiB. The plugin does not log bearer tokens.

## Local network checks

If discovery fails, first confirm the plugin is enabled and dsh has been restarted. Then check whether the router allows Wi-Fi clients to reach wired PCs and whether the PC firewall allows TCP 19390 and UDP 19389/5353 on the private network. The PC plugin panel displays its current IPv4 address, subnet, and port for diagnosis.

The Android app accepts private HTTPS IPv4 addresses after certificate identity confirmation. LAN discovery advertises the same HTTPS port. Saved HTTP addresses must be edited and verified again. USB debugging can use `adb reverse tcp:19390 tcp:19390` and `https://127.0.0.1:19390`.
