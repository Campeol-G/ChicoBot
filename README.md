# chicos-bot

Personal Discord music bot (Java): plays YouTube and SoundCloud tracks and
imports tracks from playlist screenshots through local OCR, with a preview.

Status: v1 feature-complete. The bot connects, plays by link or search,
keeps a per-guild queue, and imports YouTube Music / Spotify screenshots
via Tesseract (`@bot` + image → preview → Play all).

## Features

- `/play <name or link>`, `/pause`, `/resume`, `/skip [target]`, `/stop`,
  `/queue`, `/remove <target>`, `/leave`, `/nowplaying`, `/ping`.
- `@bot p <query>` in a channel works like `/play`.
- Mention the bot with a playlist screenshot (PNG, JPEG or WebP, up to 5 images):
  it reads the tracks with local OCR, shows a preview with **Play all**,
  **Remove items** and **Cancel** buttons, then queues the confirmed list
  in print order. Only the importer can confirm.
- Message command **Play music from image** for screenshots in older messages.
- Automatic fallback between YouTube and SoundCloud; per-source circuit
  breaker so a YouTube outage does not stall a 25-track import.
- Links to Apple Music and Spotify tracks are accepted as metadata: the bot
  reads the title and artist and searches YouTube / SoundCloud. Spotify uses
  a free Web API app (`SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET`; empty
  disables Spotify links). Playlist links are refused (or reduced to one
  track); no playlist ever floods the queue.

## Requirements

- Linux x64, Java 25+ (tested on JDK 27), Maven 3.10+
- Tesseract 5.x with `por` and `eng` data
  (`sudo pacman -S tesseract tesseract-data-por tesseract-data-eng` on Arch)
- Token in `~/.config/chicos-bot/env` (mode 600), created from `.env.example`

## Run

```bash
mvn -q package -DskipTests   # produces target/chicos-bot.jar
./run.sh                     # loads ~/.config/chicos-bot/env and starts the bot
```

`run.sh` is the supported path: it loads the env file itself, so it works in a
fresh terminal. The manual equivalent is:

```bash
set -a; . ~/.config/chicos-bot/env; set +a
java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -jar target/chicos-bot.jar
```

Do not restart in a loop: Discord allows 1000 IDENTIFY per 24 h and resets the
token when that is exceeded. The systemd unit uses `RestartSec=10` for the
same reason.

For a server install, see `deploy/chicos-bot.service`.

## Creating the bot (Developer Portal)

In https://discord.com/developers/applications, on the bot app:

- **Bot → Public Bot:** off. With it on, anyone with the link can add the bot
  to other servers.
- **Bot → Requires OAuth2 Code Grant:** off.
- **Bot → Privileged Gateway Intents:** Message Content on (the `@bot p`
  mention flow reads message text), Presence and Server Members off. The bot
  receives mentions, attachments on mention messages, and context-menu
  targets with Message Content; voice state still arrives without Server
  Members.
- **Installation → Installation Contexts:** Guild Install only; User Install off.
- **Installation → Default Install Settings:** scopes `bot` and
  `applications.commands`, with these permissions (integer `3230720`):
  View Channels, Send Messages, Embed Links, Read Message History,
  Connect, Speak. No 2FA permission, no administrator.
- After writing the token to the env file, use **Bot → Reset Token** so no
  older copy stays valid. Never paste the token in chat, logs or code.

Invite link (replace both IDs; the second form targets the test server):

```text
https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot%20applications.commands&permissions=3230720&integration_type=0
https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot%20applications.commands&permissions=3230720&integration_type=0&guild_id=<GUILD_ID>&disable_guild_select=true
```

With `DEV_GUILD_ID` set, slash commands register on the test server
(instant). Without it they register globally (up to 1 hour to appear).

## Configuration

All variables live in `~/.config/chicos-bot/env` (see `.env.example`).
Only `DISCORD_TOKEN` is required.

| Variable | Default | Description |
| --- | --- | --- |
| `DISCORD_TOKEN` | — | Bot token (required). |
| `DEV_GUILD_ID` | empty | Test server id: instant command registration. Empty = global. |
| `OCR_LANGS` | `por+eng` | Tesseract languages. |
| `OCR_TIMEOUT_MS` | `8000` | Timeout per OCR run. |
| `OCR_MAX_CONCURRENCY` | `2` | Simultaneous OCR processes. |
| `IMG_MAX_BYTES` | `8388608` | Max image size (8 MB). |
| `IMG_MAX_PIXELS` | `16777216` | Max pixels (4096×4096). |
| `IMPORT_MAX_TRACKS` | `50` | Tracks per import (remove menu shows the first 25). |
| `IMPORT_SESSION_TTL_S` | `300` | Preview lifetime. |
| `COOLDOWN_USER_S` | `15` | Seconds between imports per user. |
| `QUEUE_MAX` | `50` | Max queued tracks per server. |
| `IDLE_DISCONNECT_S` | `300` | Voice disconnect when idle. |
| `MATCH_THRESHOLD` | `0.35` | Minimum score for an OCR-imported track (below → "not found"); `/play` text queries are never refused. Set `0.0` to disable. |
| `PARALLEL_SEARCH` | `true` | Search YouTube and SoundCloud at the same time and pick the best-ranked result. Set `false` for the v1 sequential path (YouTube first). |
| `RESOLVE_TIMEOUT_MS` | `8000` | Timeout per track resolution. |
| `RESOLVE_CONCURRENCY` | `4` | Parallel resolutions on Play all. |
| `SOURCE_FAILURE_THRESHOLD` | `3` | Consecutive failures before a source degrades. |
| `SOURCE_DEGRADED_COOLDOWN_S` | `300` | How long a source stays degraded. |
| `PLAY_COOLDOWN_S` | `5` | Cooldown on `/play` per user. |
| `LOG_LEVEL` | `INFO` | Log level. |
| `YT_REFRESH_TOKEN` | empty | Optional burner-account OAuth (below). |
| `SPOTIFY_CLIENT_ID` | empty | Spotify Web API app id. Set both Spotify values or neither (both empty disables Spotify links). |
| `SPOTIFY_CLIENT_SECRET` | empty | Spotify Web API secret; keep it in the env file (mode 600), never in git. |
| `YT_CIPHER_URL` | empty | Remote cipher URL (below). Empty = local cipher. |
| `YT_CIPHER_PASSWORD` | empty | Remote cipher password. |

## YouTube login-wall (OAuth, optional)

Without authentication YouTube refuses streaming from datacenter-like IPs
("This video requires login"). Search still works; playback fails. To unlock
it with a burner Google account (never your main):

```bash
# 1. prints a google.com/device link and a code
java -cp target/chicos-bot.jar dev.chico.bot.source.YoutubeOauthSetup
# 2. open the link with the burner account, enter the code, wait
# 3. the refresh token is printed; store it (mode 600, never commit/share):
printf 'YT_REFRESH_TOKEN=%s\n' 'paste-token-here' >> ~/.config/chicos-bot/env
# 4. restart
./run.sh
```

With a token set, the `Tv` client handles authenticated playback. Without it
the bot stays on unauthenticated access (best-effort).

## Remote cipher (required for YouTube playback)

`youtube-source` 1.18.2 cannot decipher current player scripts locally
(upstream issue #241; the maintainer's answer is a remote cipher server).
Without it, playback fails even with OAuth. Configure:

```bash
YT_CIPHER_URL=http://localhost:8001
YT_CIPHER_PASSWORD=choose-a-password
```

Options, in order of preference:

1. **Self-hosted** (already set up in `../yt-cipher`, data stays local):
   `run.sh` starts it automatically when `YT_CIPHER_URL` points at localhost.
   Manage it with `docker compose` in that dir; update with
   `./scripts/update-compose.sh`. Docker is enabled at boot and the container
   uses `restart: unless-stopped`.
2. **Public instance** (`https://cipher.kikkia.dev/`, no password, 10 req/s):
   fine for testing, not for daily use.

Recheck each `youtube-source` release: clear `YT_CIPHER_URL`, restart, and play
a login-walled video. If it plays, the local cipher works again — drop the
remote cipher and bump the pinned version.

YouTube is best-effort: when it is down, the bot falls back to SoundCloud
automatically. Using YouTube with a personal bot and no monetization is the
accepted risk; large public bots were shut down over its terms of service in
2021, so keep this bot private and do not monetize it.

## Offline OCR check

```bash
mvn -q package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true \
  -cp target/chicos-bot.jar dev.chico.bot.ocr.OcrCli <image.png>
```

Prints one `title - artist` line per detected track. Method and recall
numbers: `docs/ocr-evaluation.md`.

## Test

```bash
mvn -q test
```

Unit tests need no network, Tesseract or Discord. Integration tests carry
`@Tag("integration")` and are excluded by default; run them with
`mvn -Dgroups=integration -DexcludedGroups= test`.

## Security notes

- Images are validated in stages (declared type/size/dimensions, CDN host
  allowlist, magic bytes, header dimensions) and processed in memory; the
  OCR runs in an isolated process with a timeout. Nothing is persisted.
- Only `YoutubeAudioSourceManager` and SoundCloud are registered; the HTTP
  and local Lavaplayer sources are never enabled.
- Every message disables mentions and escapes OCR titles, authors and URLs.
- Logs carry a correlation id per import, stage durations and counts —
  never image content, OCR text or the token.

## Personal use, no warranty

This is a personal bot for a few servers: no database, no sharding, no
public hosting, no monetization. It is provided as-is under the MIT license,
without warranty of any kind.
