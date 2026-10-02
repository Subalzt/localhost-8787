<p align="center">
  <img src="docs/images/icon.png" width="112" alt="Localhost 8787 icon">
</p>

<h1 align="center">Localhost 8787</h1>

<p align="center">
  Your Android phone becomes a small, private server. Your laptop opens it in a browser.<br>
  Clipboard, files, music, notifications and control move between them, and nothing leaves the room.
</p>

<p align="center">
  <a href="../../releases/latest"><b>Download the app</b></a>
</p>

<p align="center">
  <img src="docs/images/web-home.png" width="760" alt="The page on the laptop">
</p>

| Home | Devices | Control | Settings |
| --- | --- | --- | --- |
| <img src="docs/images/phone-home.png" width="190"> | <img src="docs/images/phone-devices.png" width="190"> | <img src="docs/images/phone-control.png" width="190"> | <img src="docs/images/phone-settings.png" width="190"> |

---

## ⚡ Speed

| How they are connected | Phone → laptop | Laptop → phone | Laptop keeps internet |
| :--- | ---: | ---: | :--- |
| **USB-C cable** (USB 3, USB tethering on) | **224–235 MB/s** | **257–271 MB/s** | Yes |
| **The phone's hotspot** | 59–68 MB/s | 64–65 MB/s | Yes, through the phone |
| **Direct link** (the phone's own private network) | 55–101 MB/s | 54–115 MB/s | No |
| Home Wi-Fi, through the router | 7.1–7.3 MB/s | 6.0–6.1 MB/s | Yes |

Measured between a Xiaomi 15 and a Wi-Fi 7 laptop (24–26 September 2026), with the data made
and thrown away on the spot so only the connection counts. A USB 2 cable (most charging cables)
tops out near 40 MB/s, and the app says so when that happens. The router here runs a narrow
20 MHz channel, which is why it is the slowest; see [Faster over the air](#faster-over-the-air).

The app itself is not what limits these. Measured on 27 September against a bare TCP connection
with no app at all, it matches the connection both ways, on the cable and on the hotspot, and a
real 2 GB file, written to the phone's storage, moved at 238 MB/s to the phone and 230 MB/s back
over the cable.

---

## 🔗 Every way to connect

Laptops and phones can reach each other in the ways below, from the simplest up. Each line is
the short version; **click it for the full explanation.** Nothing ever goes through a server on
the internet: every byte travels between your own devices.

### Laptop and phone

<details>
<summary><b>1. The same Wi-Fi.</b> Both on your router. Nothing to set up; the slowest way.</summary>

<br>

**Path:** laptop → router → phone. Two hops through the air.

Turn the switch on at the top of the phone's Home and open the address it shows (for example
`10.117.25.178:8787`) in any browser on the laptop. It works on any Wi-Fi where devices can see
each other: your home router, an office network. Every byte crosses the air twice, once to the
router and once from it, and the slower of the two links decides the speed. Here that is about
7 MB/s, because the router uses a narrow channel.

Some Wi-Fi networks (guest networks, most campus and hotel networks) keep devices from seeing
each other. There the page will not open; use the hotspot or a cable instead.

</details>

<details>
<summary><b>2. The phone's hotspot.</b> The laptop joins the phone's hotspot and stays online through it. About 65 MB/s.</summary>

<br>

**Path:** laptop → phone. One hop through the air.

Turn on the phone's ordinary hotspot and join it from the laptop. The phone is now the laptop's
router, so there is only one hop between them, and the laptop keeps its internet through the
phone. Home shows the hotspot address with its speed; the page shows *Phone's hotspot* as a badge.

*Settings → Speed → Laptop link* shows the hotspot's name and password, so the laptop helper can
join it by itself.

</details>

<details>
<summary><b>3. A USB-C cable.</b> USB tethering on. About 250 MB/s, the fastest by far.</summary>

<br>

**Path:** laptop ⇄ phone over the cable, no radio at all.

Plug in and turn on USB tethering (Home offers the button as soon as a cable is plugged in). The
phone becomes a network adapter on the laptop, at USB speed: 224–271 MB/s with a USB 3 cable. The
laptop keeps its internet through the phone. Most charging cables are USB 2, which tops out near
40 MB/s; the app tells the two apart and says so.

</details>

<details>
<summary><b>4. The direct link.</b> A private network the phone starts by itself. 55–115 MB/s, no internet.</summary>

<br>

**Path:** laptop → phone. One hop, on a network only the two of you are on.

*Settings → Speed → Laptop link → Direct link → Start.* The phone starts a network of its own
(Android's local-only hotspot) with a random name and password; the laptop helper joins it without
asking. It is as fast as the hotspot or faster, but it has no internet, so the laptop is offline
until it stops. Android allows one hotspot at a time: the app says when the ordinary hotspot is in
the way.

</details>

<details>
<summary><b>5. USB debugging.</b> The helper's last resort: works with no network between them at all.</summary>

<br>

**Path:** laptop → adb over the cable → phone.

When the laptop cannot reach the phone over any network (the phone on another Wi-Fi, tethering
off) but the cable is in and *USB debugging* is on, the laptop helper forwards a port through
`adb` and serves the phone at `127.0.0.2` as if it were on the network. The page, the clipboard
and the trackpad work; the second screen needs a real network. It is slower than tethering, and
the page says *turn on USB tethering for full speed*. The helper uses the Android SDK's `adb`, or
the one that comes with scrcpy.

</details>

<details>
<summary><b>6. The laptop helper.</b> One address, <code>localhost:8787</code>, always on the fastest of the above.</summary>

<br>

**Path:** browser → the helper on the laptop → whichever way above is fastest → phone.

On Windows, double-click **blazeit-pc.bat**; on Linux, run `python3 blazeit-linux.py` in a
terminal; on a Mac, `python3 blazeit-mac.py` in Terminal. The page's Settings offers the one for
the computer it is open on, and nothing is installed. The helper finds the phone by itself, over the cable, the hotspot, the direct link,
Wi-Fi or USB debugging, and moves to a faster way the moment one appears and back when it goes.
The page stays at `http://localhost:8787` whichever it is, so bookmarks and open tabs never
change. Starting it again replaces the running copy.

The Linux helper needs only Python 3 and uses what the system has: `wl-clipboard` (Wayland) or
`xclip` (X11) for the clipboard; write access to `/dev/uinput`, or `xdotool` (X11), or `ydotool`,
for the trackpad and keyboard; `pactl` or `wpctl` for the volume; `nmcli` to join the direct link;
`scrcpy` for the phone's screen; `ffmpeg` (X11) or `wf-recorder` (sway, Hyprland) for the second
screen. It says what is missing when something needs it. Under WSL it names itself *Linux* and
leaves the trackpad and the second screen to the Windows helper.

On a Mac the same file uses what macOS has: `pbcopy`, `pbpaste` and AppKit for the clipboard
(text, pictures, a file), Quartz events for the trackpad and keyboard, AppleScript for the volume,
and from [Homebrew](https://brew.sh) `ffmpeg` for the second screen and `scrcpy` for the phone's
screen. macOS asks once for **Accessibility** (the trackpad) and **Screen Recording** (the second
screen) for the app the helper runs in. The phone's Windows shortcuts come out as the Mac's: Ctrl+C
is ⌘C, the app switcher is ⌘-Tab, three fingers up is Mission Control, four fingers change Space.
To use the phone's hotspot or direct link, join it from the Wi-Fi menu; the helper follows the
phone there. A USB cable works when the phone's USB tethering uses NCM, which macOS supports
(it has no RNDIS).

<img src="docs/images/web-settings.png" width="620" alt="The helper, running, in the page's Settings">

</details>

### Phone and phone

<details>
<summary><b>7. Finding and linking phones.</b> Phones on the same Wi-Fi find each other; one code links them both ways.</summary>

<br>

**Path:** phone ⇄ phone over the network they share (a router, or one phone's hotspot).

The **Devices** tab looks for other phones running Localhost 8787 on the same network (they
announce themselves, as printers do). Tap one, compare the 4-digit code, and tap **Allow** on the
other phone. That one approval links the two **both ways**: each can now reach the other without
asking again, for a year, renewed on its own. A phone the search cannot see (another network
that still routes to this one) can be added with **Connect by address**.

<img src="docs/images/phone-devices.png" width="240" alt="The Devices tab: computers, and looking for phones">

</details>

<details>
<summary><b>8. Each other's files.</b> A linked phone's folders open on this phone and on the laptop.</summary>

<br>

**Path:** this phone (or its laptop) → this phone → the linked phone.

Tap a linked phone in *Devices* to browse its folders, with thumbnails, and save a file or a whole
folder here. On the laptop page, the **Phone** tab has a chip for each linked phone, so a laptop
connected to one phone can browse and download from the other. Read-only, as the laptop's view of
the phone is.

</details>

<details>
<summary><b>9. One clipboard.</b> Copy on one phone, paste on the other.</summary>

<br>

**Path:** phone → phone, then on to each phone's laptop.

With *Sync clipboard* on, whatever is copied on one phone (text, a picture, a file) goes to every
linked phone, and from there to the laptops on it. The newest copy wins; a copy that came from a
phone is not sent back to it.

</details>

<details>
<summary><b>10. Sending between phones.</b> Share a file to the other phone; big ones go over several connections at once.</summary>

<br>

**Path:** phone → phone.

Send files from Home, from any app's Share menu, or from a linked phone's row in *Devices*. Files
over 16 MB are split over up to 8 connections at once, in 64 MB pieces, and land in the other
phone's received-files folder.

</details>

### Laptop and laptop

<details>
<summary><b>11. Through one phone.</b> Two laptops on the same phone: the file streams through it, kept nowhere.</summary>

<br>

**Path:** laptop → phone → laptop. The phone only passes it on.

On the page, the **to …** menu on *Send files* lists every computer with the page open. Pick one:
the other laptop gets a card with **Save** and **Decline**, and on Save the file streams through the
phone as it is read. It is never written to the phone's storage, so a 50 GB file needs no room
there. The speed is that of the slower link, and when both laptops share the phone's hotspot they
share its air time too.

| Where to send | A file arriving |
| --- | --- |
| <img src="docs/images/web-send-to.png" width="420" alt="Where to send: this phone, or a computer with the page open"> | <img src="docs/images/web-incoming.png" width="420" alt="A file arriving from another laptop, to Save or Decline"> |

</details>

<details>
<summary><b>12. Through two phones.</b> Each laptop on its own phone, the phones linked: laptop → phone → phone → laptop.</summary>

<br>

**Path:** laptop → its phone → the linked phone → that phone's laptop.

The menu lists the computers on linked phones too (*a laptop · through the other phone*), and
the other phone itself. The file streams across both phones in one go and is stored on neither.
This is how two laptops on two different hotspots reach each other.

</details>

<details>
<summary><b>13. Direct, browser to browser.</b> When the two laptops can reach each other, the file skips the phones entirely.</summary>

<br>

**Path:** laptop → laptop. The phones only introduce them.

Before streaming through the phone (11 or 12), the two pages try to connect to each other
directly (WebRTC). The phones carry only the introductions, a few kilobytes: an offer, an answer,
and the addresses each side can be reached at. Each phone answers STUN on UDP port 3478, which
tells each browser its own address as seen from the network. If the laptops share a network (the
same router, the same hotspot, a cable between them), the data then flows straight from one
browser to the other and the phones carry none of it. If they cannot reach each other within
8 seconds, the file goes through the phones as in 11 or 12, by itself.

In Chrome and Edge the receiving laptop picks where to save, and the file is written as it
arrives. Browsers without that (Firefox, Safari) take a direct send of up to 512 MB in memory;
larger ones go through the phone as a normal download.

</details>

### Different networks

<details>
<summary><b>14. What works across different networks, and what does not.</b></summary>

<br>

Pair once on the same network. After that, a laptop or phone on another network reaches the
phone through its **tunnel**: one connection to the phone's IPv6 address, with its own
encryption and every request carried inside it
([how it works](docs/tunnel-protocol.md)). The phone is the only server; there is none on the
internet. The helper and linked phones learn the phone's address while they are together and
switch to the tunnel by themselves when no local path answers, and back again when one does.

From a network with no IPv6, the laptop and the phone swap their public IPv4 addresses as two
small sealed notes on a public message board ([ntfy.sh](https://ntfy.sh), which keeps nothing),
then **punch through both NATs** over UDP and run the same tunnel on that path. Nothing but those
two notes goes through anyone else. It works unless both networks have the hard kind of NAT (a new
port for every destination); from Airtel home broadband to a phone on Jio data it takes 5 to 9 s.
Between two phones the tunnel is still IPv6 only.

| Situation | Works? |
| :--- | :--- |
| Laptop and phone on the same Wi-Fi, hotspot or cable | Yes |
| Laptop and phone on different networks, cable plugged in | Yes, through USB debugging (5) |
| Laptop on another network with IPv6, phone on mobile data | Yes, through the tunnel |
| Two phones far apart, on mobile data | Yes, through the tunnel, once linked |
| Two laptops on different networks, one phone | Yes: straight between the laptops when both have IPv6, else through the phone |
| Two laptops, each on its own phone's hotspot | Yes, through both phones (12); from far apart, over the tunnel between the phones |
| Laptop on a network with IPv4 only, phone on mobile data | Yes, punched through both NATs over UDP, unless both are the hard kind (then no) |
| Guest or campus Wi-Fi that hides devices from each other | Through the tunnel if the network has IPv6; else use a hotspot or a cable |

Through the tunnel: the clipboard, files, browsing the phone, music, notifications, the
trackpad. The second screen and the phone's screen stay local. On mobile data every byte counts;
Settings → Laptop access → From other networks shows who is connected that way and how much it
has carried, and turns it off. If the phone's address changes while the two are apart (mobile IPv6
changes whenever the phone reconnects), the helper asks the phone where it is now through the same
message board and is back in about 3 seconds.

</details>

---

## Features

### 1. Turn it on and connect a computer

Flip the switch at the top of Home. The phone shows its address in large type; tap it to copy, or
show a QR code. Open that address on the laptop, click **Ask to connect**, and tap **Allow** on the
phone (both show the same 4-digit code). No account, no password. The phone lists every computer
and phone allowed in and can remove any.

<img src="docs/images/web-login.png" width="480" alt="Connect this computer">

### 2. One clipboard for both

Copy on one, paste on the other: text, pictures and files up to 50 MB. The box on Home shows what
is shared, and under its title **Shared with** names every machine it reaches right now, on the
phone and on every page: *Shared with Xiaomi 15, LEGION_7I and Linux*. Machines go by their name,
never their browser; a browser in WSL is *Linux*. **History** (the clock) brings back the last 30
items and closes by itself after ten seconds; with nothing in it, the clock just says *Nothing to
clear*. **Clear history** empties the history and the clipboard everywhere. With the laptop
helper running it all happens by itself both ways, including **every screenshot** you take on
the phone. To send copies from any phone app straight away, allow two things once over USB:

```bash
adb shell pm grant dev.periy.bridge.debug android.permission.READ_LOGS
adb shell appops set dev.periy.bridge.debug SYSTEM_ALERT_WINDOW allow
```

### 3. Send files and whole folders

- **Laptop → phone**: drop files or folders on the page. Big files go over several connections at
  once and carry on where they stopped if the connection drops.
- **Laptop → another computer or phone**: the **to …** menu on *Send files* (see
  [Laptop and laptop](#laptop-and-laptop)).
- **Phone → laptop**: share anything to Localhost 8787 from any app, or tap *Send files* on Home.
  It appears under *On the phone* on the page.
- Nothing can be deleted from the laptop. On the phone, *Clear* takes files off the list only.

### 4. Browse the phone from the laptop

The page's **Phone** tab shows the phone's folders with thumbnails, and those of linked phones.
Click a file to see it before downloading: photos (HEIC too), videos, music, PDFs, text and Office
files, full screen with arrows to step through. Download one file or a whole folder as a zip.
Read-only, and off until you allow it on the phone.

| The phone's folders | A photo, previewed |
| --- | --- |
| <img src="docs/images/web-phone.png" width="420"> | <img src="docs/images/web-viewer.png" width="420"> |

### 5. Your music, from the phone

The page's **Music** tab is the phone's Music (section 6), fitted to the window: on a laptop beside
the page's own tabs, with more songs and albums to a row; in a phone's browser the whole screen,
the other tabs a menu away at its top left. Songs stream straight from the phone, so FLAC and
everything else starts at once, and skipping is instant. Media keys and the Windows media overlay
work.

- **Tracks, Albums and Liked**, as the app's: Namida's bar along the foot to go between them (the
  page slides in from its side), each page's count and what can be done to all of it (shuffle,
  play) on the app bar's line beside the search. The app bar is a shade lighter than the page, so
  a line runs along its foot.
- **Tracks and Liked** are Namida's tiles: the cover, title, who it is by, the album and year, what
  kind of file it is, how long it is, a heart, and a menu (play next, play last, go to its album).
  **Pull a song left** and *Play After* shows from under it. **Albums** are Namida's cards, the
  year frosted into a corner and a play button in the other.
- **An album** opens as a page pushed in from the right, its cover flying from its card into the
  frame at the top, with shuffle, **Play Last**, its order (disc number, title, duration or
  artist, either way round) and a header before each disc.
- **Search** grows out of its button (or `/`), sliding the count away to the left; `Esc` clears it,
  then closes it.
- While a song plays, a small note sits beside *Music* in the tabs.
- **The player, full screen**, moves as [Namida](https://github.com/namidaco/namida)'s does, with
  its times and curves: every move up or down settles over 300 ms, a song sideways over 600. Drag
  the mini player up (or click its cover) and it grows into a player the size of the window: the
  cover flies from the mini player's corner to its place, the title and the play button follow,
  and the waveform and the buttons come in last. Drag on up for the queue: every song in the order
  it will play, to play from, drag into a new order by its handle, or take off. Let go anywhere and
  it settles by how far and how fast you dragged, back into the mini player with a small bounce.
  Swipe the cover or the mini player sideways for the song before or after: the cover and title
  slide, the title a little quicker. The cover swells with the loud moments, the seek bar is the
  song's own waveform (drag along it to choose a place, drag up off it to take the seek back), the
  colour is the cover's, and faint specks drift behind, quicker when the music is loud. `Esc` or
  Back steps back down.
- **The queue is Namida's**: a sheet with round corners rising under the song, which shrinks to
  the top as it comes. Its header says where you are in it and the time left, with a button back
  down; each song is Namida's tile (cover, title, artist, album and year, its length, a heart, a
  handle and a menu to take it off). Along its foot, a broom to remove the songs before or after,
  and **Shuffle**, which shuffles only what is left. When the song changes, its colour passes from
  one tile to the next.
- **It looks like Namida's too**: its font (Lexend Deca) and its icons (Iconsax), the song large
  over its artist with a **heart** beside them (kept on the phone, so a song with a heart has it on
  the phone's player and every page), a thin waveform, and a glowing play disc in the cover's
  colour, which tints the whole player. Along the bottom, **what the file is** (FLAC, MP3, OPUS...,
  its bitrate, its sample rate and, for a 24-bit file, a Hi-Res badge), **repeat**, the **sound
  controls** (Namida's Configure: speed and volume, each with a step either side of its slider) and
  **lyrics**. Its menus, its notes and its dialog are Namida's too.
- **Repeat** opens Namida's menu: stop after the last song, repeat this song, repeat it a number of
  more times (set with its − and +), repeat the queue, or repeat the queue shuffled anew each time
  round.
- **Lyrics over the cover**, as the phone's (below), riding on the cover into the queue, and
  **full-page lyrics** on a tap.

| Your albums | Playing, with what is next |
| --- | --- |
| <img src="docs/images/web-music.png" width="420"> | <img src="docs/images/web-album.png" width="420"> |

### 6. Music on the phone itself

The phone's own **Music** tab opens the phone's music full screen, laid out as
[Namida](https://github.com/namidaco/namida)'s library, in the colour of the song playing (Back
returns to the app). It plays on the phone: its speaker, headphones or a car, with Localhost 8787
in the background, the controls in the notification and on the lock screen, and a headset's
buttons working.

- **Tracks, Albums and Liked**, side by side under Namida's bottom bar: tap one or swipe between
  them. Sizes are Namida's own, text included (it draws its text at nine tenths). Each page's
  count, with shuffle and play all, sits on the app bar's line beside the search, and the app bar
  is a shade lighter than the page, so a line runs along its foot.
- **Tracks**: every song A to Z, each with its cover, who it is by, its album and year, what kind
  of file it is (a small FLAC, MP3, OPUS... label, lossless ones in the song's colour), how long
  it is, a heart, and a menu (play next, play last, go to its album). The song playing is lit. **Pull a song left** and *Play After* shows from under it, as in
  Namida: let go past it and the song plays after the one playing.
- **Liked**: the songs with a heart, newest heart first, with shuffle and play all; the same list
  as the page's *Liked*.
- **Albums**: three to a row, each cover with its year frosted into a corner (the cover seen
  blurred through it, as Namida's) and a play button in the other, and how many songs and how long
  under it.
- **An album**: its cover in a frame with its name, who it is by and its year, **shuffle** and
  **Play Last**; then its songs, each cover marked with its number in a frosted corner, in the
  order you choose (disc number, title, duration or artist, either way round). An album on more
  than one disc has a header before each disc.
- **Search** at the top right filters the page you are on (or the album open) as you type; the
  box grows out of its icon as the count slides out to the left, and back when it closes.
- **Namida's transitions**: an album opens as a page pushed in from the right (the one under it
  slides a third aside), its cover flying from the grid into the album's header; songs and albums
  come in one after another, sliding up as they fade in; and the page sinks back a little as the
  player opens over it. Going back, the page leaves quickly and slows as it arrives, as Namida's
  does. It is all in Namida's font, Lexend Deca, with Namida's icons.

What is playing floats over the bar as a mini player, the same player as the page's, after
Namida's:

- **One continuous drag.** Up from the mini player to full screen, and on up to the queue; down
  again the same way. Everything follows the finger at once and settles where the drag says, with
  Namida's times and curves: 300 ms up or down (the mini player with a small bounce), 600 ms for a
  song sideways. The bar sinks away under it as it opens. Back steps down.
- **Swipe sideways** on the mini player or the cover for the song before or after, with a tick
  under the finger when it changes.
- **The cover swells** with the song's loud moments and **the seek bar is its waveform**, worked
  out once on the phone and kept (the page gets the same, from the phone).
- **Namida's look**: the song over its artist with a heart, a thin waveform, the glowing play disc,
  colours from the cover and specks drifting behind. Along the bottom, **what the file is** (FLAC,
  MP3, OPUS..., its bitrate and sample rate), **repeat**, and the **sound controls**: speed, pitch
  and volume in Namida's Configure dialog (pitch in percent or semitones, a one-tap 432 Hz, speed
  carrying pitch if you like), kept for next time.
- **Repeat** opens Namida's menu: stop after the last song, repeat this song, repeat it a number of
  more times (set with its − and +; it counts down, then carries on), repeat the queue, or repeat
  the queue shuffled anew each time round.
- **Lyrics over the cover**, from Namida's lyrics button beside the sound controls: the cover
  blurs and the words scroll over it in time with the song, the line being sung in the middle on
  a card of the song's colour, each word lighting as it is sung (for songs timed word by word),
  and three dots filling across a long pause. Scroll to look around and they come back to the
  song three seconds later. Untimed lyrics scroll as a page. Pull up to the queue (or down to the
  mini player) and they ride on the cover's middle at their own size, fading as it shrinks.
- **Full-page lyrics**: tap the lyrics over the cover and they take the whole screen, as
  Namida's: the cover blurred behind, who and what is playing along the top, the lines large from
  the left with the one being sung on a soft card (tap a line to go there), and the waveform,
  times and controls along the foot. Back, or the arrow at the top, returns to the player.
- **Found by the phone itself.** A moment after each song starts (in the background too), when
  the phone has no lyrics for it, it looks them up on [LRCLIB](https://lrclib.net) and keeps them
  just as the page does: a `.lrc` beside the song and a copy in the app. Lyrics found by the phone
  show on the page, and lyrics found by the page show on the phone. The button shows **?** when a
  song has none and **x** when they could not be looked up (no internet); they are tried again
  the next time it plays.
- **The queue**: Namida's sheet, rising under the song as the cover shrinks to the top, opening at
  the song playing. Tap a song to play it, drag it by its handle to move it, swipe it away or use
  its menu to take it off. The broom removes the songs before or after (or all), **Shuffle**
  shuffles what is left, and the round button goes back to the song playing. Pull the list down
  from its top and the player comes back down with it.
- Pull the mini player down under itself to stop and put it away. The queue is kept for next time,
  paused where it stopped.

Albums run on from one song to the next without a gap, and a call or another app's sound pauses
it.

### 7. Lyrics, found and saved by themselves

Press the lyrics button in the full player's bottom row: the words scroll over the cover with the
song, the line being sung on a card of the song's colour. Click them for the full page, where a
click on a line jumps there. With word timing, each word fills as it is sung; in a long
instrumental gap, three dots swell until the singing starts again.

- **Looked up by themselves.** When a song starts and the phone has no lyrics for it, the browser
  looks them up on [LRCLIB](https://lrclib.net), a free, open lyrics library, provided the laptop
  has internet (the phone does the same for songs it plays itself). Nothing is typed and nothing
  is sent but the song's title, artist, album and length.
- **Downloaded and kept.** What is found is saved on the phone twice: in the app, and as a
  standard `.lrc` file **beside the song** in its music folder (for example `Music/Song.lrc`), which
  other players read too. Next time the lyrics come from the phone, with or without internet.
- **Your own files first.** A `.lrc` file already beside a song is used as it is and never
  overwritten.
- Saving `.lrc` files beside songs needs *All files access* for Localhost 8787 on the phone.
- **Word by word, worked out on the laptop.** LRCLIB times most songs by the line only. With the
  phone plugged in, run `tools\lyrics-align\align.bat`: it fetches each song not yet timed by the
  word, pulls the vocals out of the mix (Demucs), lines every word up with the singing (PyTorch's
  MMS_FA aligner, on an NVIDIA GPU when there is one) and gives the phone the lyrics back word by
  word. Run it again after adding songs; it skips the ones already done. The first run sets itself
  up in `tools\lyrics-align\venv` (Python 3.11 and ffmpeg needed, about 3 GB). Every song's lyrics
  as they were are kept in `tools\lyrics-align\backup`, and `align.bat usb --restore` puts them back.

| On the laptop | At phone width |
| --- | --- |
| <img src="docs/images/web-lyrics.png" width="520"> | <img src="docs/images/web-lyrics-phone.png" width="200"> |

### 8. The phone's notifications on the laptop

The page's **Alerts** tab shows the phone's notifications. Reply to a message, press their
buttons (*Mark as read*), or clear them, and it happens on the phone. Ongoing ones (music,
downloads) sit apart and never pop up. Needs *Notification access* on the phone.

<img src="docs/images/web-alerts.png" width="620" alt="Alerts">

### 9. The phone as trackpad and keyboard

The phone's **Control** tab is a trackpad for the laptop, with Windows gestures (two fingers to
scroll, three for Task View, four to switch desktops), the phone's keyboard typing into the
laptop, and the laptop's volume and play/pause/next keys. It opens **locked**: tap once to use it,
so a swipe across it changes tab instead. It locks again when you leave.

### 10. The phone's screen on the laptop

*Phone screen* on the page opens the phone in a window on the laptop, to use with the mouse and
keyboard, sound included (via [scrcpy](https://github.com/Genymobile/scrcpy); the phone needs USB
debugging on). It uses the cable when one is plugged in.

### 11. The phone as a second screen

*Use as a second screen* on the Control tab shows the laptop's desktop on the phone; taps on it
click there. With a virtual-display driver on the laptop (Windows) or an extra monitor set up with
`xrandr` (Linux) it is a real extra monitor; otherwise it mirrors the laptop's screen. Protected
video (Netflix, Prime Video) shows black, as it does for any screen capture.

### 12. A live monitor

The pulse button shows a small floating pill: speed each way, ping and signal, over any screen.
Tap it for the full picture: the last minute, how full the link is and with what, and the
session's peak and total. On the laptop, *Monitor* docks it down the right side.

| On the phone | Docked beside the page |
| --- | --- |
| <img src="docs/images/phone-monitor.png" width="190"> | <img src="docs/images/web-monitor.png" width="560"> |

The laptop downloading from the phone over shared Wi-Fi (both on channel 36, so every byte crosses
the air twice) at about 10 MB/s: 57% of what that link carries.

### 13. Measure the connection

*Settings → Measure* on the page tests the connection alone for five seconds each way. Compare it
with a real transfer: close means the network is the limit, far below means storage is.

### 14. Back works as in an app

- **On the phone**, Back closes whatever is open (the player a step at a time, a search, an album,
  Music, a linked phone's files, the clipboard's history), then returns to Home; at Home it sends the app to the background, as Home does. It
  never closes the app, and the server keeps running. From Android 14 the swipe shows it coming:
  what it would close follows the finger (a page slides aside and fades, an album slides off the
  way it came, the player lowers), finishes quickly when you let go, and settles back if you swipe
  back out first.
- **On the laptop**, the browser's Back steps back through the page: it closes the player, the
  lyrics, a file being viewed or the monitor, then returns to the tab before. It leaves the page only from where
  you opened it. Each tab has its own address (`#music`), so a reload or a bookmark opens it.

### 15. Any browser, any computer

The page is one file with no dependencies and works in Chrome, Edge, Brave, Opera, Firefox and
Safari, on Windows, macOS, Linux, ChromeOS, Android and iPad. It is checked in Edge and Firefox,
including over plain `http://` (a "not secure" address), where browsers switch off some features:
there the page falls back by itself (copying still works, a direct laptop-to-laptop file is
held in memory or goes through the phone). The laptop helper runs on Windows, Linux and macOS;
everything else needs just a browser.

### 16. The look

After the Apple TV app, the same on the phone and the laptop: the app's own icon, a night-blue banner, large titles,
capsule buttons, and on the phone the tabs float along the bottom within reach of a thumb. Music
has Namida's look of its own, in the colours of the song playing.
**Swipe left or right** to move between tabs. **Colour** (*Settings → Appearance*, shared by the
phone and every open page): Automatic (black and white) or red, orange, yellow, green, mint, blue
or purple. **Light, dark or automatic** sits underneath.

---

## Getting started

1. Install the APK from [Releases](../../releases/latest) (Android 10 or later).
2. Open **Localhost 8787**, go to **Settings**, choose where received files go, and allow
   notifications (and music, if you want the Music tab).
3. Flip the switch on **Home** and open the address it shows on the laptop. Tap **Allow**.
4. For full speed and the extras, get the laptop helper from the page's **Settings** and run it:
   **blazeit-pc.bat** on Windows, **blazeit-linux.py** on Linux (`python3 blazeit-linux.py`),
   **blazeit-mac.py** on a Mac (`python3 blazeit-mac.py`).
   Other computers need only the browser.
5. For the most speed, plug in a USB-C cable and turn on USB tethering, or use the hotspot.

Settings on the phone, top to bottom:

| Look, receiving and speed | The laptop link | Laptop access, music, keep running |
| --- | --- | --- |
| <img src="docs/images/phone-settings.png" width="190"> | <img src="docs/images/phone-settings-2.png" width="190"> | <img src="docs/images/phone-settings-3.png" width="190"> |

## Faster over the air

For hotspot mode, checked on 26 September 2026:

- The phone's hotspot already runs at its best here: 5 GHz, 80 MHz wide, Wi-Fi 6.
- Keep the phone close to the laptop and not face down.
- On the laptop (Device Manager → Wi-Fi adapter → Advanced): turn *Leisure Power Save* off and
  set *Roaming Aggressiveness* to lowest.
- Set the home router to an **80 MHz** channel instead of 20 MHz for faster plain Wi-Fi (some
  Airtel routers lock this; their support can change it).
- 6 GHz Wi-Fi was opened in India in January 2026. Once the phone's software supports it there,
  the hotspot can move to a wider, emptier channel.
- Tried and not worth it: 160 MHz or Wi-Fi 7 on the phone's hotspot (switched off by the
  maker), two links at once, Wi-Fi Direct, more parallel connections, and turning the phone's
  own Wi-Fi off while the laptop is on its hotspot (measured the same either way).

## Under the hood

- The phone runs a small web server (Ktor) in the background; the laptop page is one HTML file
  it serves. Live updates reach the page over a single event stream.
- Uploads use the resumable [tus](https://tus.io) protocol, split over parallel connections
  into one pre-allocated file. Downloads use byte ranges, so they resume too.
- Phones find each other with DNS-SD (`_blazeit._tcp`) on the local network and link with the
  same pairing as computers, once each way.
- A file sent to another computer or phone is a *pipe*: offered, accepted, then streamed through
  in one pass and never stored. A direct send uses WebRTC data channels; the phone passes the
  introductions along the pipe and answers STUN on UDP 3478.
- Lyrics come from [LRCLIB](https://lrclib.net), fetched by the browser for the page and by the
  phone for its own player (LyricsFinder, which reads them exactly as the page does), and stored
  on the phone with a `.lrc` file beside the song.
- The phone's own player is Android's MediaPlayer, with the next song prepared and chained on for
  a gapless handover, in a media-playback service with a MediaSession. How loud each 50 ms of a
  song is gets decoded once, at low priority, and kept (a few kilobytes a song); the phone's player
  and the page (`/api/music/loudness/{id}`) both draw from it. What a file is (its kind as the
  decoder sees it, bitrate and sample rate) is read once a song (`/api/music/info/{id}`), and the
  hearts are kept on the phone (`/api/music/favourites`, announced to the pages as they change).
- The player's motion is after [Namida](https://github.com/namidaco/namida)'s: one number from 0
  (mini) through 1 (full) to 2 (queue) that every piece is placed from, and the same settling rules
  and curves, and its pages and player are drawn after Namida's, with its sizes, spacing and
  colours. It is written anew for Compose and for the page; none of Namida's code is used.
- The music's font is [Lexend Deca](https://fonts.google.com/specimen/Lexend+Deca) (SIL Open Font
  License, `app/src/main/assets/fonts/OFL.txt`), bundled in the app and served to the page from
  `/fonts/`. Its icons are [Iconsax](https://github.com/lusaxweb/iconsax) in its Broken style (MIT
  licence), drawn from the paths of the `iconsax-react` package.
- Only computers and phones you allow get in: each gets a signed session you can revoke on the
  phone. Everything stays on the local network: no cloud, no account. Traffic is plain HTTP, so
  treat it like a file share on your own Wi-Fi.

## Building

JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kotlin and Jetpack Compose on the phone (Android 10+), Ktor 3 for the server; the page has no
build step and no dependencies. The code lives in `app/src/main/`: `assets/bridge.html` (the
page), `assets/blazeit-pc.bat` and `assets/blazeit-helper.py` (the helpers: Windows's, and one for Linux and the Mac), and `java/dev/periy/bridge/` (`server/` for the
routes, uploads, music, lyrics, linked phones, pipes and notifications, `ui/` for the app's
screens, `music/` for the phone's own player, `net/` for addresses, the direct link and STUN).

## Known limits

- Over the air, 80 MHz Wi-Fi 6 is the most this phone's hotspot offers; for more, use a cable.
- On the direct link the laptop has no internet until the link stops.
- On Linux the second screen works on X11 and on wlroots desktops (sway, Hyprland), not yet on
  GNOME or KDE under Wayland; the trackpad needs `/dev/uinput`, `xdotool` (X11) or `ydotool`. Under
  WSL the Windows helper runs both.
- The Mac helper is written to Apple's documented tools and checked piece by piece, but has not
  yet run on a real Mac. On a Mac the helper does not switch Wi-Fi itself.
- Across the internet the laptop needs IPv6 of its own, and the phone's carrier has to let
  connections in to it; a proxy that allows only port 443 cannot reach the phone (Android apps
  cannot listen below 1024).
- Lyrics need internet on the laptop the first time a song is played; after that they are on
  the phone.
- Protected video cannot be shown on the second screen.
- Apple Lossless (ALAC) does not play in browsers. Empty folders are not created.
