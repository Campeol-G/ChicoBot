# chicos-bot

Personal Discord music bot (Java): plays YouTube and SoundCloud tracks and
imports tracks from playlist screenshots through local OCR, with a preview.

Status: early scaffold (Task 1). The bot connects and answers `/ping`.

## Requirements

- Linux x64, Java 25+ (tested on JDK 27)
- Maven 3.10+
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
token when that is exceeded.

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

## Test

```bash
mvn -q test
```
