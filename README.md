# Ghar TV — free live TV for Android TV, only channels that actually work

Plays the public [iptv-org](https://github.com/iptv-org/iptv) playlist
(`https://iptv-org.github.io/iptv/index.m3u`), but **tests every channel first**:

1. Downloads the stream's playlist,
2. follows it to the first quality level,
3. downloads the start of a real video segment —

all within **6 seconds**. Anything broken, geo-blocked or slow is never shown.
Working channels appear on screen as soon as they pass (Nepal first, then India,
then the world). **The scan runs once:** results are saved on the TV and reused every
time, so the app opens instantly. It never rescans on its own — only when you press
**Rescan** (it asks first). If the first scan is interrupted, it resumes where it stopped.

**Favourites** (hold OK on a channel) are saved permanently, survive app updates and
rescans, and are never removed from your list if they fail once while watching.

While watching, a channel that stops or doesn't start within 12 seconds is
skipped automatically and removed from the list.

## TV remote

| Where | Button | Action |
|---|---|---|
| Channel list | OK | Watch |
| Channel list | Hold OK | Add / remove Favourite |
| Categories (left) | Up / Down | Filter channels (All, Favourites, Nepal, India, News, Sports…) |
| Watching | Up / Down / Left | Open the channel list over the video — scroll, OK to switch, Back to close |
| Watching | CH+ / CH− | Switch channel directly |
| Watching | OK | Show channel info |
| Watching | Right / Menu | Screen mode: Fit → Fill → Stretch |
| Watching | Back | Back to the browser |

## Phone (same APK)

| Where | Gesture | Action |
|---|---|---|
| Browser | Tap / long-press | Watch / add to Favourites |
| Watching | Swipe up / down | Next / previous channel |
| Watching | Tap | Show controls: **Channels** list and **Fit / Fill / Stretch** |
| Watching | Back | Close list / back to the browser |

Screen modes: **Fit** shows the whole picture (black bars if needed), **Fill** zooms to
cover the screen (edges cropped), **Stretch** stretches to full screen. Your choice is remembered.

## Build and install (TV)

1. Push this folder to a **new** GitHub repo → *Actions* → wait for the green tick →
   download **GharTV-apk** and unzip.
2. `adb connect <TV-IP>:5555`
3. `adb install -r app-debug.apk`
4. Open **Ghar TV** from All Apps in Ghar Launcher (hold OK → Add to favourites to put it in the dock).

The first full check of ~10,000 channels takes roughly 10–20 minutes, depending on your
internet; you can start watching as soon as the first channels appear.

## Install on a phone

Download the same `app-debug.apk` on the phone (e.g. from the GitHub Actions page, or
send it to yourself), open it, and allow "Install unknown apps" when Android asks.

## Notes

- Streams come from iptv-org, a public directory of free-to-air channels; availability
  changes constantly, which is why the app tests them.
- Player: Google Media3 (ExoPlayer) — HLS and MPEG-TS streams.
