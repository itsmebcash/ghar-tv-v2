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

**Opens where you left off.** The app starts straight into the last channel you watched
(even after Android closed it in the background). **Back** while watching saves everything,
stops the video and leaves the app. The full channel grid is under **All channels ›** in the
channel list; Back from the grid returns to your channel.

**Playback:** starts after ~2 seconds of video, keeps up to 60 seconds in reserve, and adapts
quality to your connection. If a playing channel drops, it reconnects automatically (up to 3
tries). When the internet is down it waits and resumes instead of skipping channels. A channel
that never starts within 12 seconds (while online) is skipped and removed from the list.

Channel checking pauses as soon as the app is closed and continues next time it's open
(more gently while you're watching, so the video keeps the bandwidth).

## Watching and browsing at the same time

**TV, or phone held sideways:** the video fills the screen. The bottom bar shows the channel
number, logo, name, programme (when the stream provides it; otherwise category · country)
and a 12-hour clock, plus the controls on a phone. **★ Favourite** sits at the top.
Open the channel panel to browse **categories and channels side by side** while the current
channel keeps playing.

**Phone held upright:** a YouTube-style watch page — video on top; below it the channel info
with clock and ★, a row of category chips, and the channel list. Tap a channel to switch.

Categories everywhere: **Last Watched** (your 20 most recent), Favourites, All, Nepal, India,
News, Sports, Movies…  Picking a channel from a category makes that category the
channel-up/down order.

## TV remote

| Where | Button | Action |
|---|---|---|
| Browser grid | OK / Hold OK | Watch / add or remove Favourite |
| Watching | Up / Down / Left | Open the channel panel (Left/Right moves between categories and channels) |
| Channel panel | OK / Hold OK | Switch to that channel / add or remove Favourite |
| Watching | CH+ / CH− | Switch channel directly |
| Watching | OK | Show channel info |
| Watching | Hold OK | Add / remove the current channel from Favourites |
| Watching | Right / Menu | Screen mode: Fit → Fill → Stretch |
| Watching | Back | Close the panel, or save and exit the app |

## Phone (same APK)

| Where | Gesture | Action |
|---|---|---|
| Video | Swipe up / down | Next / previous channel |
| Video | Tap | Show controls: ★, **Channels**, **Fit / Fill / Stretch**, rotation **Auto / Portrait / Landscape** |
| Watch page / panel | Tap / long-press a channel | Switch / add or remove Favourite |
| ★ next to the channel name | Tap | Add / remove Favourite |
| Anywhere | Back | Close the panel, or save and exit the app |

Screen modes: **Fit** shows the whole picture (black bars if needed), **Fill** zooms to
cover the screen (edges cropped), **Stretch** stretches to full screen. Your choice is remembered.
Rotation follows how you hold the phone unless you lock it; also remembered.

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
