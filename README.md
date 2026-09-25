<div align="center">

# Rokumio Service

### Turn your Android phone into the streaming server behind Rokumio.

<p>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square" alt="Android" />
  <img src="https://img.shields.io/badge/language-Kotlin-7F52FF?style=flat-square" alt="Kotlin" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-22C55E?style=flat-square" alt="MIT License" /></a>
</p>

#### Runs Stremio's standalone streaming server on your phone, so a Roku running the Rokumio client can play torrent media — no desktop machine in the loop.

</div>

## Features

- **Server screen** — import your own `server.js`, then Start/Stop a foreground
  streaming server with live status and address.
- **Background server** — the server keeps running with a
  "Rokumio server running in the background" notification and a Stop action;
  it shuts down cleanly when you swipe the app away
- **Roku connect panel** — a modal bottom sheet that slides over the current
  screen: scan the LAN for Rokus (with friendly names), enter an IP manually,
  or disconnect. The selection is never persisted — a fresh launch starts
  disconnected
- **Add-ons manager** — install Stremio add-ons from their manifest URL and pick
  which ones to push to the channel
- **Per-screen send** — the toolbar **Send** button pushes only the active
  screen's changes (server, add-ons, …) to the connected Roku
- **Dev builds** — `-PdevMode=true` exposes a Developer screen with a channel-id
  override; production builds lock the channel id

## How streaming works

Rokumio Service is the **server half** of the pair: it hosts Stremio's standalone
streaming server on the phone and exposes it to the Roku on the local network.
The **[Rokumio client](https://github.com/gpratoe/rokumio-client)** — the other half —
is the 10-foot Roku app that points at this server's address and plays.

The app bundles the runtime it needs (a standalone Node.js 26 executable and a
cross-compiled ffmpeg/ffprobe) and a preload + settings layer so the server runs
natively on Android. What it does **not** bundle is `server.js` itself: that file
is proprietary Stremio code, so it's a user-supplied input — you pick it at
runtime via the **Import** button.

The server.js you run owns its own torrent engine and transcoding; Rokumio Service
just stands it up and keeps it alive.

## Run it

```bash
# Default production build (locked channel id)
gradle :app:assembleDebug

# Dev build: adds the Developer screen + channel-id override
gradle :app:assembleDebug -PdevMode=true
```

Builds need Gradle 8 + JDK 17, and a current `native/bin/arm64-v8a/` with the
staged binaries (see `scripts/` if you need to rebuild them):

```bash
scripts/fetch-node-android.sh     # stage the Node executable
scripts/build-ffmpeg-android.sh   # cross-compile ffmpeg/ffprobe for Android
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install it on your phone

1. Enable Developer Options and USB debugging on the phone.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Import `server.js`, start the server, and connect the Roku via the chip.

> Note: the app is arm64-only (`abiFilters "arm64-v8a"`) and targets Android 8+.

## Project layout

```
app/         Android app (Kotlin, com.rokumio.host): UI, foreground service,
             Roku discovery via SSDP/ECP, and ECP/DIAL push
native/      Cross-compiled executables: libnode.so, libffmpeg.so, libffprobe.so
scripts/     Tooling: fetch Node for Android, cross-compile ffmpeg
reference/   Private design notes (never shipped)
server-*.js  Behavioral reference for the server the user supplies
```

## Testing

There is no automated test suite yet; the app is exercised on-device (arm64):
import a `server.js`, start/stop the server, scan + connect a Roku, and verify
that each screen's **Send** pushes only its own subset to the channel.

## Disclaimer

<details>
<summary>Read the project disclaimer</summary>
<br>

Rokumio Service is a free, open-source companion application. It does not host,
index, cache, or distribute media content, run content servers or streaming
services of its own, or maintain a catalogue of sources. It runs a server
`server.js` that you supply yourself; we do not provide, operate, or control that
server and it is not bundled with or distributed by this project.

The bundled runtimes (Node.js, ffmpeg) only launch that user-supplied server on
the phone. Any torrent engine, transcoding, or content access happens inside the
server software you choose to run — not in this app.

You are responsible for making sure the sources you connect and the content you
access are lawful in your jurisdiction. Don't use this software to infringe
copyright or to circumvent access controls.

Rokumio Service is an independent project and is not affiliated with, endorsed
by, or associated with Roku, Inc., Stremio, or the Node.js Foundation. All
trademarks are the property of their respective owners.

Provided "as is", without warranty of any kind. See [LICENSE](LICENSE).

</details>
