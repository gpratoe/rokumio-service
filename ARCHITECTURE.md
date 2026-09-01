# RokuMio Service — Architecture & Design

> Current design of the RokuMio-hosted Stremio streaming-server prototype.

## Goal

Turn an Android phone into a headless streaming server that feeds a **Roku TV**.
The phone runs Stremio's standalone streaming server (`server.js`) over WebTorrent,
and the Roku plays its H.264/AAC HLS output. The hardest constraint is **CPU**: the
phone must encode in real time, but on the other hand this is already the default behaviour
when running mobile stremio clients.

## Why a phone server at all

Stremio's server can't run on Roku directly. Running it on a phone (instead of a PC)
lets the Roku play torrent media with no desktop machine in the loop.

### Why not just use the stremio mobile app as a server?

You can, but i had had issues in the past doing it that way, mainly because it uses old versions
of nodejs and ffmpeg. The other reason is to make a roku stremio client that connects to this server.
Having a dedicated app for it i can semi-automate the process of connecting both ends in the future,
wereas it would be more tedious if i was to use the stremio app.

## Components

```
  rokumio-service/
  ├─ app/                          Android app (Kotlin, com.rokumio.host)
  │  └─ src/main/
  │     ├─ java/com/rokumio/host/
  │     │  ├─ MainActivity.kt      Import / Run / Stop UI
  │     │  ├─ ServerService.kt     Foreground service, spawns Node, tee's logs
  │     │  └─ ServerLocator.kt     Locates binaries, writes preload + settings
  │     └─ AndroidManifest.xml     FGS + dataSync + wake lock perms
  ├─ native/
  │  ├─ bin/arm64-v8a/             libffmpeg.so + libffprobe.so + libnode.so (built/staged)
  │  └─ include/                   node headers (from fetch script)
  ├─ scripts/
  │  ├─ build-ffmpeg-android.sh    Cross-compile ffmpeg+ffprobe+libx264 for Android
  │  └─ fetch-node-android.sh      Stage the standalone Node executable
  ├─ reference/                    Private, uncommitted analysis docs (never shipped)
  └─ *.md                          Docs (this file)
```

> **Note on the server:** the actual Stremio server (`server.js`) is proprietary and
> is **never** shipped with this app (it is not in this repo). It is a user-supplied
> input — the user picks it at runtime via the Import button. `server-desktop.js` is
> not used by the app itself; it's only an external behavioral reference.

## Runtime flow

1. User taps **Import** and picks a `server.js` (proprietary Stremio code, supplied by
   the user — see the note above). It is copied into the app's private `filesDir`.
2. User taps **Run** → `ServerService` starts as a **foreground service**
   (`FOREGROUND_SERVICE_DATA_SYNC`) and takes a **partial wake lock** so the CPU
   stays awake with the screen off.
3. On a background thread, `ServerLocator`:
   - prepares `filesDir/stremio-cache` and seeds `filesDir/server-settings.json` with
     a real `cacheSize: 2 GiB` (fixes the Android-default `cacheSize = 0` bug that
     caused endless buffering on 4K re-seeks),
   - writes `filesDir/preload.js` setting `APP_PATH`, `HOME`, `FFMPEG_BIN`,
     `FFPROBE_BIN` before the server runs.
4. The service `ProcessBuilder`-spawns the **standalone Node 26 executable** with
   `node -r <preload> server.js`. Node runs its own process; its merged stdout/stderr
   are tee'd to `filesDir/node.log` and logcat by `TeeStream`.
5. When Node exits, the service stops itself. Stopping the service destroys the
   spawned Node process, shutting the server down completely.

## Server data on the phone

- `server-settings.json` is seeded **only if absent** (won't clobber server/UI writes):
  ```json
  { "cacheSize": 2147483648, "cacheRoot": "<filesDir>", "transcodeHardwareAccel": false }
  ```
- The server resolves its app/data dir from `APP_PATH`. On Android
  `process.platform === "android"`, so it would otherwise fall back to a non-writable
  `/tmp`; pointing `APP_PATH`/`HOME` at `filesDir` fixes that.
- Transcoders are resolved via `FFMPEG_BIN`/`FFPROBE_BIN`.

## How binaries get on-device

The app ships three **bundled native executables** (ffmpeg, ffprobe, node) — not
JNI/shared libraries. All three are staged under `native/bin/<abi>/` as `lib*.so`
so AGP packages them into the APK's `lib/<abi>/` and, with legacy packaging,
extracts them to `nativeLibraryDir` with the **exec bit** at install time.
`ProcessBuilder.exec()` ignores the `.so` suffix, so spawning works fine. Only
**arm64-v8a** is packaged (`app/build.gradle` `abiFilters`). The `jniLibs`
source-set in `app/build.gradle` is pointed at `native/bin` purely to reuse
AGP's jniLibs *packaging mechanism*; the files themselves are executed, never
loaded.

- `libffmpeg.so`, `libffprobe.so` — built by `scripts/build-ffmpeg-android.sh`
  (source build: FFmpeg 8.1.2 + libx264, static, self-contained; ffprobe is
  otherwise missing from the common prebuilt FFmpeg drops, and libx264 needs the
  GPL build).
- `libnode.so` — staged by `scripts/fetch-node-android.sh` from a hand-provided
  standalone Node tree (`nodejs-v26.6.0-android-arm64/bin/node`, a PIE executable).

`packagingOptions.jniLibs.useLegacyPackaging = true` forces real on-disk extraction
(required so the exec bit lands and the binaries are physically present).

## Serving the Roku (the key lever)

The HTTP API of the server is documented separately in
`reference/ENDPOINTS_AND_REQUIREMENTS.md` (a private, uncommitted analysis; a public
reverse-engineering write-up of the same endpoints is available on [GitHub/Loukious/stremio-runtime](https://github.com/Loukious/stremio-runtime) ). The parts
that matter for Roku:

- The server **transcodes everything** to H.264/`avc1.42E01F` + AAC/`mp4a.40.5`
  (`libx264`, `yuv420p`) — exactly Roku's native HLS profile. HEVC/VP9/AV1 sources are
  re-encoded.
- **Quality selector** — `stream-q-<height>` with `SUPPORTED_QUALITIES = [320, 480, 720]`
  (only heights ≤ the source's height). Requesting a lower quality applies
  `scale=-2:<height>` before encode, dramatically cutting phone CPU.
- **Copy vs. transcode** — with an H.264/AAC source and no `ffsplit`, numeric
  qualities always re-encode. The zero-re-encode copy variant (`stream-q-o`) is only
  advertised when `ffsplit` is present, which the `stremio-service` never bundles.
  This is a server-side behavior option; it is outside this app's scope to implement
  or ship.
