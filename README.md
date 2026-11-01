# Ghar TV 2.0 — free live TV for Android TV and phones, only channels that actually work

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
| Browser grid | OK / Hold OK | Watch / options (favourite, add to playlist) |
| Watching | Up / Down | Next / previous channel |
| Watching | Left / Right | Open the channel panel (categories with channel counts + channels) |
| Channel panel | Up / Down | Move through the channels of the chosen category (stays in that category) |
| Channel panel | Left | Back to the category list (lands on the category you're in) |
| Channel panel | OK / Hold OK | Switch to that channel / options (favourite, playlists) |
| A playlist category | Hold OK | Rename or delete it |
| Watching | CH+ / CH− | Switch channel directly |
| Watching | OK | Show channel info |
| Watching | Hold OK | Add / remove the current channel from Favourites |
| Watching | Menu (≡) | Options: **search**, subtitles, screen mode, favourite, add to playlist, **rescan** |
| Channel panel | Search button (top) | Search by channel name, category or country |
| Watching | Back | Close the panel, or save and exit the app |

## Search and rescan

- **Search:** the **Search…** row at the top of the categories (TV panel, phone chips and the
  full grid), the **Search** button in the channel panel, or **Menu → Search channels…**.
  Type part of a name, a category or a country code (e.g. `kantipur`, `news`, `np`).
  Names starting with your words come first.
- **Rescan:** **Menu → Rescan all channels…** while watching (phone: the **⋯** button), or the
  **Rescan** button in the full grid. It asks first; channels refill as they pass. While a
  channel is playing the check runs gently, so it takes longer than from the grid.

## My playlists

Make your own lists: choose **+ New playlist** in the categories, or Hold OK / long-press
any channel → **Add to playlist…**. Your playlists appear with the categories
(after Favourites); hold OK / long-press one to rename or delete it. Saved on the device.

## Subtitles

When a channel carries subtitles, English is switched on automatically. Choose another
language or turn them off with **CC** (phone) or **Menu → Subtitles** (TV); your choice is
remembered. Most free channels have no subtitles; the app says so instead of failing.

## While a channel loads

If a channel takes more than about a second to start, a short family-friendly joke appears
(from icanhazdadjoke.com, downloaded in the background every few days and saved on the
device, so it also works offline). It disappears the moment the picture starts.

## Phone (same APK)

| Where | Gesture | Action |
|---|---|---|
| Video, middle | Swipe up / down | Next / previous channel |
| Video, left side | Drag up / down | Brightness (this app only), with an on-screen level |
| Video, right side | Drag up / down | Volume, with an on-screen level |
| Video | Tap | Show controls: ★, **Channels**, **CC**, **Fit / Fill / Stretch**, rotation |
| A channel in any list | Tap / double-tap / long-press | Watch / favourite / options & playlists |
| A playlist chip | Long-press | Rename or delete it |
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

## Ghar Launcher integration

Ghar Launcher shows your **Favourite Channels** and **Continue Watching** on the TV home screen.

- Ghar TV shares a **read-only** list (`content://com.ghar.tv.channels/favourites` and `/last`)
  with id, name, logo, category, availability, last successful check/playback time and
  average startup time. **Stream addresses are never shared.** Channels are identified by
  stable ids (the iptv-org channel id, or a short fingerprint).
- Access is limited to apps signed with the **same key** (signature permission
  `com.ghar.tv.permission.CHANNELS`); both repos sign with one key stored as GitHub secrets
  `GHAR_KEYSTORE_B64` and `GHAR_KEY_PASSWORD`. Without the secrets, builds still work but the
  two apps can't see each other.
- The launcher opens a channel with `com.ghar.tv.action.PLAY_CHANNEL` + `channel_id`, or
  continues with `com.ghar.tv.action.RESUME` (live streams resume live, not at a timestamp).
- Ghar TV does all channel checking; the launcher only reads the results.

## Notes

- Streams come from iptv-org, a public directory of free-to-air channels; availability
  changes constantly, which is why the app tests them.
- Player: Google Media3 (ExoPlayer) — HLS and MPEG-TS streams.

## Screensaver for Ghar Launcher (2.4)

When the TV sits idle on Ghar Launcher (and the launcher's screensaver is set to *Favourite channels*), the
launcher asks Ghar TV to play your favourite channels muted, one after another, changing every 10 minutes
(adjustable in the launcher's Settings). Any button returns to the home screen. Ghar TV plays the streams itself,
so the launcher still never sees stream addresses. Only apps signed with the shared Ghar key can start it.

## What's new in 2.5

- **6 categories**, the same as Ghar TV Mobile: Local (Nepal + India), News, Entertainment, Education,
  Lifestyle, Other.
- **Live search**: hold **Back** anywhere (or Menu → Search). Results update as you type and spelling doesn't
  have to be exact: "kisan" finds DD Kishan, "kantpur" finds Kantipur, "nepal tv" finds Nepal Television.
- **Previous channel**: press **OK twice** quickly (or the remote's Last / 0 button, or Menu) to flip back and forth.
- **Picture**: Fit, Fill, Stretch, 16:9 and 4:3.
- **Sleep timer** (Menu): 15 min to 2 hours; a one-minute countdown, any button keeps watching.
- **Favourites come back by themselves**: once a week, favourites that stopped working are checked again.
- A more premium look: red accent, red hearts on saved channels, white logo plates, cleaner rows.


## What's new in 2.6: favourites from your phone

Copy favourites and playlists from **Ghar TV Mobile** to the TV over your home Wi-Fi:

1. TV: Ghar TV → **Menu** → **Get favourites from phone**. The TV shows a 4-digit code (and its address).
2. Phone (same Wi-Fi): Ghar TV Mobile → **Settings** → **Send favourites & playlists to TV**.
3. Tap the TV in the list (or type the address it shows) and enter the code.

The TV only adds: favourites are added to yours, and a playlist with the same name gets the new channels.
It only listens while that screen is open, only accepts the code on screen, and closes after 5 wrong codes.
