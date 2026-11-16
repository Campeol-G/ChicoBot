# chicos-bot

A personal Discord music bot in Java. It plays YouTube and SoundCloud tracks
and imports tracks from playlist screenshots with local OCR.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Set up the bot in the Discord Developer Portal](#set-up-the-bot-in-the-discord-developer-portal)
- [Get the IDs you need](#get-the-ids-you-need)
- [Invite the bot](#invite-the-bot)
- [Install and run](#install-and-run)
- [Configuration](#configuration)
- [Optional services](#optional-services)
- [Usage notes](#usage-notes)
- [Troubleshooting](#troubleshooting)
- [Development and tests](#development-and-tests)
- [Security notes](#security-notes)
- [What is new in v1.3](#what-is-new-in-v13)
- [License and disclaimer](#license-and-disclaimer)

## Features

- Plays YouTube and SoundCloud by link or search, with a per-server queue.
- Falls back between YouTube and SoundCloud; a per-source circuit breaker
  skips a degraded source instead of stalling the queue.
- Playlist links show a confirmation before anything is queued.
- Mention the bot with a playlist screenshot (PNG, JPEG or WebP, up to
  10 images): it reads the tracks with local Tesseract OCR, shows a preview
  with Play all, Remove items and Cancel, then queues the confirmed list.
- A `Play music from image` message command handles screenshots in older
  messages; `@bot p <query>` in a channel works like `/play`.
- Skipping or removing someone else's track in a call with 3+ humans needs a
  majority vote; your own track is always immediate.

| Command | Purpose |
| --- | --- |
| `/play [query]` | Play a song by name or link; bare `/play` resumes a paused track |
| `/pause` | Pause the current track |
| `/skip [target]` | Skip the current track or jump to a queued number or name |
| `/stop` | Stop and clear the queue |
| `/queue` | Show the queue |
| `/remove <target>` | Remove a queued track by number or name |
| `/exit` | Leave the voice channel and clear the queue |
| `/nowplaying` | Show the current track |
| `/loop [target]` | Loop the current track, a queued position, or the queue |
| `/unloop` | Stop any active loop |
| `/help` | Show the commands and the import flow |
| `/ping` | Replies with pong |
| `Play music from image` | Message command: import a screenshot from an older message |

## Requirements

- Linux x64, Java 25+, Maven 3.10+.
- Tesseract 5.x with `por` and `eng` data. On Arch:

  ```bash
  sudo pacman -S tesseract tesseract-data-por tesseract-data-eng
  ```

## Set up the bot in the Discord Developer Portal

1. Go to https://discord.com/developers/applications and create an
   application. Note the Application ID on the General Information page.
2. Open the Bot page and turn off Public Bot.
3. On the same page, turn off Requires OAuth2 Code Grant.
4. Under Privileged Gateway Intents, turn on Message Content (the `@bot p`
   mention flow reads message text). Leave Presence and Server Members off.
5. Under Installation, set Installation Contexts to Guild Install only.
6. Under Default Install Settings, select the `bot` and
   `applications.commands` scopes, with these permissions
   (integer `3230720`): View Channels, Send Messages, Embed Links,
   Read Message History, Connect, Speak. No administrator.
7. Click Reset Token, confirm, and copy the new token right away. Discord shows it only once. Put it in `~/.config/chicos-bot/env` (see Configuration), or let the setup wizard ask for it. Reset the token again only if it was ever shared, because each reset invalidates the previous one.

> [!WARNING]
> The bot token is a secret. Keep it in `~/.config/chicos-bot/env`
> (mode 600) only; never paste it in chat, logs, or code.

## Get the IDs you need

| Value | Where to find it | Used for |
| --- | --- | --- |
| Application ID | Developer Portal, General Information page | Invite URLs below |
| Server ID | Discord: enable Developer Mode under User Settings, Advanced, then right-click the server and choose Copy Server ID | `DEV_GUILD_ID` |
| Bot token | Bot page, Reset Token; shown once | `DISCORD_TOKEN` |

Channel IDs and user IDs are not needed.

## Invite the bot

Replace `<APP_ID>` with the Application ID and `<GUILD_ID>` with the
server ID for the test-server link:

```text
https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot%20applications.commands&permissions=3230720&integration_type=0
https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot%20applications.commands&permissions=3230720&integration_type=0&guild_id=<GUILD_ID>&disable_guild_select=true
```

> [!TIP]
> With `DEV_GUILD_ID` set, slash commands register on that test server
> instantly. Without it they register globally, which can take up to an hour
> to appear. `DEV_GUILD_ID` only works after the bot is in that server;
> otherwise registration fails with an error in the log and nothing is registered globally.

## Install and run

From a fresh clone, the first run is a setup wizard that asks for each
value in order, writes `~/.config/chicos-bot/env` (mode 600), builds
`target/chicos-bot.jar`, installs `~/.local/bin/chicoBot`, and starts the bot:

```bash
bin/chicoBot
```

After that:

```bash
chicoBot          # start in the foreground (also the bare default)
chicoBot start    # start in the foreground
chicoBot setup    # re-run the wizard
chicoBot check    # check Java, Maven, Tesseract, env, jar, and cipher
chicoBot help     # usage
```

Use Ctrl+C to stop. There is no restart loop: Discord allows 1000 IDENTIFY
per 24 h and resets the token when that is exceeded.

The manual route without the wizard:

```bash
mvn -q package -DskipTests   # produces target/chicos-bot.jar
./run.sh                     # loads ~/.config/chicos-bot/env and starts the bot
```

<details>
<summary>Manual launch equivalent</summary>

```bash
set -a; . ~/.config/chicos-bot/env; set +a
java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -jar target/chicos-bot.jar
```

</details>

<details>
<summary>What <code>chicoBot check</code> verifies</summary>

Java 25 or newer, Maven 3.10 or newer, the env file at mode 600 with
`DISCORD_TOKEN` set, the built jar, Tesseract with `por` and `eng` data,
and whether the optional cipher URL answers.

</details>

For a server install or background operation, see
`deploy/chicos-bot.service`.

## Configuration

All variables live in `~/.config/chicos-bot/env` (see `.env.example`).
`DISCORD_TOKEN` is the only required variable.

| Variable | Default | Description |
| --- | --- | --- |
| Discord |||
| `DISCORD_TOKEN` | — | Bot token (required). |
| `DEV_GUILD_ID` | empty | Test server id: instant command registration. Empty means global. |
| Commands and queue |||
| `COOLDOWN_USER_S` | `15` | Seconds between image imports per user. |
| `QUEUE_MAX` | `50` | Max queued tracks per server. |
| `PLAY_COOLDOWN_S` | `5` | Cooldown on `/play` per user. |
| Images and OCR |||
| `OCR_LANGS` | `por+eng` | Tesseract languages. |
| `OCR_TIMEOUT_MS` | `8000` | Timeout per OCR run. |
| `OCR_MAX_CONCURRENCY` | `2` | Simultaneous OCR processes. |
| `OCR_BINARIZE_DARK` | `false` | Opt-in binarization for dark prints; measured with no count gain, so it stays off. |
| `IMG_MAX_BYTES` | `8388608` | Max image size (8 MB). |
| `IMG_MAX_PIXELS` | `16777216` | Max pixels (4096x4096). |
| `IMPORT_MAX_TRACKS` | `50` | Tracks per import. |
| `IMPORT_SESSION_TTL_S` | `300` | Preview lifetime. |
| Playback and sources |||
| `IDLE_DISCONNECT_S` | `420` | Voice disconnect after the queue or channel is idle (7 min). |
| `PAUSED_DISCONNECT_S` | `2700` | Voice disconnect while a track is paused (45 min). |
| `MATCH_THRESHOLD` | `0.35` | Minimum score for OCR-imported and Spotify or Apple playlist tracks; `/play` text queries are never refused. Set `0.0` to disable. |
| `PARALLEL_SEARCH` | `true` | Search YouTube and SoundCloud at the same time. Set `false` for the sequential path (YouTube first). |
| `RESOLVE_TIMEOUT_MS` | `8000` | Timeout per track resolution. |
| `RESOLVE_CONCURRENCY` | `4` | Parallel resolutions on Play all. |
| `SOURCE_FAILURE_THRESHOLD` | `3` | Consecutive failures before a source degrades. |
| `SOURCE_DEGRADED_COOLDOWN_S` | `300` | How long a source stays degraded. |
| `LOG_LEVEL` | `INFO` | Log level. |
| Optional services |||
| `YT_REFRESH_TOKEN` | empty | Optional burner-account OAuth (below). |
| `YT_CIPHER_URL` | empty | Remote cipher URL (below). Empty means local cipher. |
| `YT_CIPHER_PASSWORD` | empty | Remote cipher password; empty is valid for the public no-auth cipher. |
| `SPOTIFY_CLIENT_ID` | empty | Optional Spotify app client id; set with the secret. |
| `SPOTIFY_CLIENT_SECRET` | empty | Optional Spotify app secret. |
| `SPOTIFY_REFRESH_TOKEN` | empty | Optional user login for owned or collaborative playlists. |
| `APPLE_MUSIC_DEVELOPER_TOKEN` | empty | Optional Apple Music developer token for catalog playlists. |
| `APPLE_MUSIC_STOREFRONT` | `us` | Catalog storefront, for example `us` or `br`. |
| `APPLE_MUSIC_USER_TOKEN` | empty | Optional Music User Token for library playlists. |

## Optional services

Spotify links are always metadata only; audio plays from YouTube or
SoundCloud.

1. Create an app at https://developer.spotify.com/dashboard and add the Redirect URI `http://127.0.0.1:8888/callback`.
2. Set `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` in the env file, both or neither.
3. For playlists you own or collaborate on, run the login helper. It needs only `SPOTIFY_CLIENT_ID` in your shell:

   ```bash
   mkdir -p ~/.cache/chicos-bot
   export SPOTIFY_CLIENT_ID=your-client-id
   java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true \
     -cp target/chicos-bot.jar dev.chico.bot.source.SpotifyOauthSetup ~/.cache/chicos-bot/spotify-token
   ```

   Open the URL it prints, approve, and wait for the helper to finish. Then append the token to the env file and delete the temporary file:

   ```bash
   cat ~/.cache/chicos-bot/spotify-token >> ~/.config/chicos-bot/env && rm ~/.cache/chicos-bot/spotify-token
   ```

Risk: leaking the client secret or refresh token gives access to the app and the account data, so never commit or share them.

Apple Music playlists are metadata only; audio plays from YouTube or
SoundCloud. Set `APPLE_MUSIC_DEVELOPER_TOKEN`, and optionally
`APPLE_MUSIC_STOREFRONT` (default `us`) plus `APPLE_MUSIC_USER_TOKEN` for
library playlists. Create a MusicKit key in your Apple Developer account and generate the developer token from it, following Apple's MusicKit documentation on keys and tokens. This path uses the official Apple Music API only. Risk:
tokens are long-lived credentials tied to a paid developer account, so never
commit or share them.

YouTube login unlocks playback from datacenter-like IPs, where streaming is
otherwise refused with a login-wall error; search keeps working either way.
Use a burner Google account, never your main. Run the login helper with a path inside your home directory:

```bash
mkdir -p ~/.cache/chicos-bot
java -cp target/chicos-bot.jar dev.chico.bot.source.YoutubeOauthSetup ~/.cache/chicos-bot/youtube-token
```

Open the Google link it prints with the burner account, enter the code, and wait. Then append the token to the env file and delete the temporary file: `cat ~/.cache/chicos-bot/youtube-token >> ~/.config/chicos-bot/env && rm ~/.cache/chicos-bot/youtube-token`. Without a path argument the helper prints `YT_REFRESH_TOKEN=...` instead. Risk: the token grants access to the Google account, so restrict
it to the burner. Keep the bot private and do not monetize it.

The remote cipher is required for YouTube playback while the local cipher
cannot decipher current player scripts. Set `YT_CIPHER_URL` (and
`YT_CIPHER_PASSWORD` unless the instance needs no auth). Risk: playback
requests go through whoever runs the cipher instance, so prefer a
self-hosted one.

## Usage notes

- Playlists show a confirmation before queueing. Confirmed playlists are the
  only path allowed past `QUEUE_MAX`. Every playlist, from any source, is
  capped at 500 tracks.
- `/play` from another voice channel is refused while people are listening
  in the bot's channel. Join that channel, or wait until it is empty. Other
  control commands also need you in the bot's voice channel while the bot
  is connected.
- Query text is capped at 500 characters.
- Spotify and Apple Music playlist items are queued only when they match at
  `MATCH_THRESHOLD`.
- A timed-out import stops its remaining work.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Slash commands missing | Global registration takes up to 1 hour; or the bot is not in `DEV_GUILD_ID` | Set `DEV_GUILD_ID` to the test server and re-invite, then restart |
| "Join my voice channel to control playback." | Control commands need you in the bot's voice channel; `/play` from elsewhere is refused while people are listening there | Join that channel, or wait until it is empty |
| "Could not join that voice channel." | Missing Connect or Speak permission there | Grant both permissions in that channel |
| "Slow down" | Per-user cooldown: `PLAY_COOLDOWN_S` for `/play`, `COOLDOWN_USER_S` between imports | Wait a few seconds and retry |
| YouTube playback failing with a login-wall message | Datacenter-like IP without authentication, or no cipher | Run the YouTube login flow and set `YT_CIPHER_URL` |
| "No tracks found in that image." | Screenshot unreadable, or Tesseract data missing | Send a sharper, full-size screenshot; run `chicoBot check` to confirm Tesseract with `por` and `eng` |
| "Could not read that link. Send the track name instead." | Unsupported or unreadable link | Send the track name, or a supported YouTube, SoundCloud, Spotify, or Apple Music link |

## Development and tests

```bash
mvn -q test
bash tests/chicoBot-test.sh
```

Unit tests need no network, Tesseract, or Discord. Integration tests carry
`@Tag("integration")` and are excluded by default; run them with
`mvn -Dgroups=integration -DexcludedGroups= test`. The shell script checks
the launcher.

## Security notes

- The token lives in the env file at mode 600; the OAuth helpers write
  token files at mode 600 too. Real tokens are never committed.
- Only the Guild Messages, Guild Voice States, and Message Content
  intents are enabled; Presence and Server Members stay off.
- Every message disables mentions and escapes OCR titles, authors, and URLs.
- Images are validated in stages and processed in memory; nothing is
  persisted. Only YouTube and SoundCloud sources are registered.
- Logs carry counts and durations, never image content, OCR text, or tokens.

## What is new in v1.3

- Playlist loads honor the source circuit breaker.
- YouTube and SoundCloud playlists capped at 500 tracks.
- Spotify and Apple Music playlist items need a match at `MATCH_THRESHOLD`.
- A timed-out import stops its remaining work.
- Play-all logs carry no OCR text or titles.
- `/play` from another channel is refused while listeners are present.
- Query text capped at 500 characters.
- Apple Music track IDs must be numeric.
- The OAuth helpers refuse a symbolic link as the token file.
- A loop is cleared when its track ends and playback cannot continue.

## License and disclaimer

MIT, copyright 2026 ChicoMagnusOpus. This is a personal bot for a few
servers: no database, no sharding, no public hosting, no monetization. It is
provided as-is, without warranty of any kind.
