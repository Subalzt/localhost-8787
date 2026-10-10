# UI sweep

Looks at the page (and the map inside it) at every size, orientation, theme and tab, against the real phone's
data, without building the app. Written 2026-10-11 after going through every screen by hand.

```
python tools/ui-sweep/relay.py 8790 192.168.1.14:8787      # the phone's address, or 127.0.0.1:8788 after adb forward
node tools/ui-sweep/shoot.js --devices phone-p,phone-l,tablet-p,desktop --themes light,dark --out tmp/shots --audit
```

- `relay.py` serves the working `bridge.html` and `map.html` and relays the rest to the phone with the helper's
  saved session (`%APPDATA%\Xoosh\session.txt`). The page's freshness id is left unstamped there.
- `shoot.js` drives a headless Edge over the DevTools protocol (Node 22+). Devices: `phone-p` 390x844,
  `phone-l` 844x390, `small-p`, `tablet-p`, `tablet-l`, `laptop`, `desktop`. `--audit` writes `audit.json`
  (sideways overflow, small tap targets, unnamed buttons, clipped text, broken images, script errors).
  `--eval` runs an expression in the page and prints it; `scenes/small.js` lists tap targets under 40 px.
- A tab that never settles in the shot (Cameras, Music) is usually the phone's server being busy: ask it
  `/api/ping` first.
