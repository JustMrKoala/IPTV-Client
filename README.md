# Lounge

A living room interface for Linux. It opens on Lounge, a TV style home screen with a clock and your apps: Tuner for IPTV (M3U playlists and Xtream logins, played through mpv), YouTube, Netflix, Twitch, and any website you add. Everything works with a mouse, a click remote, the keyboard or the phone remote. It also runs on Windows, and Tuner runs on its own on Android TV.

## Folders

- `lounge/`: the program itself, `server.py` and `apps.py`, for Linux and Windows alike.
- `web/`: the TV interface (`index.html`, `i18n.js`), Lounge's home screen (`dashboard.html`), the phone remote (`remote.html`) and the YouTube ad filter. The Android app uses `index.html`, `i18n.js` and `remote.html` too.
- `android/`: the Android TV app.
- `packaging/`: what goes into the Windows exe and the Linux AppImage, and the scripts that build them.
- `dist/`: where the builds end up (`dist/windows/Lounge.exe`, `dist/linux/Lounge-x86_64.AppImage`). It is not kept in version control.

## Install on Debian

```
sudo apt install mpv chromium python3
```

There are no Python packages to install. Run `dist/linux/Lounge-x86_64.AppImage` (make it executable first with `chmod +x`), or `python3 lounge/server.py` from the project folder; both take the options under Run below. To build the AppImage, run `sh packaging/linux/build-appimage.sh` on Linux, or use the Docker command at the top of that script. The AppImage uses the system's mpv and Chromium.

Netflix (and other services with copy protection, such as Disney+ or Prime Video) needs Google Chrome, because Debian's Chromium cannot play protected video. The first time you open Netflix, Lounge downloads Chrome from Google and installs it; a window asks for your password, and Netflix opens once it is done. This also adds Google's package source, so Chrome updates with the rest of the system. To install it by hand instead:

```
wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
sudo apt install ./google-chrome-stable_current_amd64.deb
```

## Install on Windows

`Lounge.exe` carries its own Python and mpv, so there is nothing else to install. Lounge uses Google Chrome when it is installed and Microsoft Edge otherwise; both play Netflix.

Double click `dist\windows\Lounge.exe` to open Lounge in its own window; closing the window stops it. To pass the options under Run, start it from a terminal instead (for example `Lounge.exe --kiosk --remote`). With Python and mpv installed, `python lounge\server.py` works the same.

To build the exe, install Python 3.10 or newer and PyInstaller (`python -m pip install pyinstaller`), then run `powershell -ExecutionPolicy Bypass -File packaging\windows\build.ps1`. The first build downloads mpv from the mpv project's GitHub releases and checks it against a fixed hash; `-Mpv C:\path\to\mpv` bundles another copy instead.

On Windows the settings live in `%APPDATA%\tvhub\` instead of `~/.config/tvhub/`, `--install-autostart` adds Lounge to your Startup folder, and the volume buttons on the phone remote change the Windows volume. The Hide Cursor and ufw notes below are for Linux only; Windows Firewall asks whether to allow Lounge (or Python) the first time the phone remote starts.

## Android TV

Tuner also runs on its own on Android TV and Google TV boxes (Android 7 or newer). It is the same TV interface, with videos played by the built-in player instead of mpv; Lounge's home screen and the web apps are not part of it. Copy `Tuner.apk` (built as described below) to the box and open it with a file manager, or install it from a computer with `adb install Tuner.apk`. Settings has the phone remote address, which works the same as on Linux. Back on Tuner's first page leaves the app.

Videos play with Netflix-style controls. Any press brings them up: OK pauses, left and right skip 10 seconds (faster when held), and down reaches the buttons, including Audio & subtitles when a stream has more than one audio track or has subtitles. On a phone or tablet, tap to show them and double-tap the left or right side to skip. While a video plays, the phone remote's pad works the same way and its volume buttons change the volume.

To build the APK yourself, install JDK 17 or 21 and the Android SDK, then run `gradlew assembleDebug` in `android` (`./gradlew` on Linux). The APK ends up in `android/app/build/outputs/apk/debug/`.

## Run

```
python3 lounge/server.py --kiosk
```

With the AppImage or the exe, give the same options to it instead, for example `./Lounge-x86_64.AppImage --kiosk`.

The `--kiosk` flag opens Lounge fullscreen. On KDE it also turns on the Hide Cursor effect, so the pointer of an air mouse remote disappears after 3 seconds without moving (change it under System Settings > Desktop Effects > Hide Cursor). Leave it off to use any browser at http://127.0.0.1:8775 instead. Tuner is at http://127.0.0.1:8775/tuner (the old /tvhub address still works).

To start Lounge fullscreen with the phone remote every time you log in, run `python3 lounge/server.py --install-autostart` once (or the AppImage or exe with that option) (undo it with `--remove-autostart`). Turn on automatic login in KDE System Settings so it starts straight from boot.

## Lounge

Lounge is the home screen. Home on the remote (or the Home key) always brings you back to it, closing whatever app or video is open.

YouTube opens its TV interface, made for remotes, in its own fullscreen window. Ads are filtered out before the page sees them, the same way uBlock Origin and Brave handle YouTube. YouTube changes often, so this can stop working without warning. Back on the phone remote, a click remote or a mouse goes back inside YouTube. Netflix opens in Chrome and Twitch in Chromium; the arrows move between titles, OK opens one, play/pause pauses and Back goes back a page. Back on an app's first page closes it and returns to Lounge, from the phone remote as well as from a USB remote. You need a keyboard once to sign in to each app; the logins are remembered in separate profiles under `~/.config/tvhub/apps/`.

Use the sliders button at the top right to reorder or remove apps, or add any website as a fullscreen app. The layout is saved in `~/.config/tvhub/dashboard.json`, which you can also edit by hand. Web tiles accept these extra fields:

- `user_agent`: what the browser calls itself (YouTube uses this to get its TV interface)
- `adblock`: `"youtube"` turns on the YouTube ad filter
- `spatial_nav`: `true` lets the arrows move between links and buttons on sites that are not made for remotes
- `search_url`: the app's search address with `{q}` where the words go, so text typed on the phone opens a search
- `keys`: which key each remote button sends, for example `{"back": "Escape", "playpause": " "}`. `"history.back"` goes back a page.

## Language

The first time Lounge starts it asks for your language; change it later with the globe button on Lounge or under Settings in Tuner. Menus are translated into English, Dutch, German, French, Spanish, Italian, Portuguese, Turkish, Polish and Greek. Arabic, Persian, Albanian, Hindi and Serbian/Croatian/Bosnian can also be chosen; the menus then stay in English but the rest below still applies. Movie and series names are never translated.

With a TMDB key, plots and genres on movie and series pages come from TMDB in your language, falling back to the provider's text when TMDB has no translation.

Providers often carry the same movie or series several times, once per language. Tuner shows each title once: the copy labelled with your language, else one without a label, else English, else the first copy. A title that exists only in another language still shows. Copies are matched by the TMDB id Xtream servers send, so copies with translated names are recognised too, and by name and year otherwise. Choosing a language in Search's Language filter shows every copy in that language.



Open Tuner from Lounge, go to Settings (the gear at the top right), add an M3U address or an Xtream server address with username and password, then browse Live TV, Movies and Series. Each section shows one row per category. To filter by category, open Search with the magnifier at the top right, pick a type and choose a category from the dropdown. Settings is the gear next to it. To type with the remote, move to the search box and press OK: an on-screen keyboard opens, and Results jumps to what you found. A search finds titles containing all the words you type, in any order. Press the heart on anything to save it. Movies and episodes remember where you stopped and resume automatically.

## Artwork

Series pages get a backdrop and plot from TVMaze with no setup. A free TMDB API key, pasted into Settings under Artwork, adds the rotating home wallpapers, backdrops and plots for series and movie pages, and the banner on Tuner's home. The posters in the rows come from your provider.

## Phone remote

Start the server with the remote flag, for example `python3 lounge/server.py --kiosk --remote`. It prints an address like http://192.168.1.20:8776/3fa91c0d/ and also shows it in Settings on the TV. Open that address on a phone connected to the same Wi-Fi, then use the browser menu to add it to your home screen. The pad moves around the TV interface. While a video is playing, the same pad pauses, seeks and changes the volume, and Back stops playback. In YouTube, Netflix and other apps the pad sends arrow keys, and the volume buttons change the system volume. Tap "Type text" to type with your phone's own keyboard: Send puts the text into Tuner's search, or searches YouTube and Netflix when one of them is open (in other apps it goes into the selected box). Tap "123 Channel" to type a channel number; it works from anywhere in Tuner, including while a channel is playing.

Anyone on your network who has the address can use the remote, so treat it like a password. Run with `--remote-reset` to get a new address. If you use a firewall such as ufw, allow it with `sudo ufw allow 8776/tcp`. The arrow keys, Enter and Escape on a keyboard move around the TV interface in the same way.

## Notes

Live TV shows what is on now on each channel. The guide comes from the Xtream server, or from the `url-tvg` address in an M3U playlist, and refreshes every 6 hours.

Playlists, the guide, artwork and episode lists are saved in `~/.config/tvhub/cache/`, so Tuner opens instantly after a restart. A playlist older than 6 hours is shown right away and updated in the background; artwork is looked up again after 30 days. Settings > Reload fetches a source right now.

Titles are cleaned on load: language codes in front of a title (such as `NL | `, `[NL]`, `NL - ` or `NL: `) are taken off and kept as the title's language, shown as a small label and offered as a Language filter in Search. When a title has none, the language of its category is used. Plain `NL Title` with only a space counts when a good share of the list is written that way. Quality tags, bracketed junk, emoji and decorative characters are removed. M3U files that name episodes like Show S01E02 are grouped automatically into series and seasons. A year after a movie or series title (`Title - 2009`, `Title (2009)` or `Title [2009]`) is taken off the name and shown next to it instead. Movies and Series then start with a Newly released row (this year and last year) and an Old classics row (before 1990), and Search has a Year filter with those two and every decade.

Selecting a movie opens its page first, with the year, genre, running time, plot and cast, and it plays from there. Xtream servers provide these details; for M3U playlists the plot comes from TMDB when a key is set.

Lounge and Tuner were called TVHub before, so their files still live in `~/.config/tvhub/`. Settings and logins are stored in `~/.config/tvhub/state.json`, readable only by your user. The server only listens on 127.0.0.1.

## License

MIT
