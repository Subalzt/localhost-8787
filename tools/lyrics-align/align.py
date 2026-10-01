"""
Word timing for the phone's lyrics, worked out on this computer.

For every song whose lyrics are timed by the line (from LRCLIB or a .lrc), this fetches the song
from the phone, lines each word of each line up with the singing (PyTorch's multilingual forced
aligner, MMS_FA, on the GPU when there is one), and gives the phone the lyrics back as Enhanced LRC
(<mm:ss.xx> before each word), which the phone's player and the page light word by word.

  tools/lyrics-align/venv/Scripts/python tools/lyrics-align/align.py --phone 192.168.1.14:8787
      [--ids 151,206] [--redo] [--dry] [--restore] [--vocals] [--pad 600] [--min 0.15] [--stats]

--vocals pulls the singing out of the mix first (Demucs, htdemucs), which places far more lines
on busy songs; --pad is how far either side of a line's time its words are looked for, --min the
least confidence kept. A line the aligner is unsure of is tried again without its bracketed
ad-libs, which are fitted in between. The library was done with --vocals --pad 600 --min 0.15.

It signs in as this computer's helper (its session in %APPDATA%\\Xoosh\\session.txt). Every song's
lyrics as they were are kept in tools/lyrics-align/backup/<id>.json first; --restore puts those
back. Lines it cannot place with confidence (another script, mostly numbers, or the words lost
under the beat) keep their line timing only.
"""
import argparse, json, os, re, subprocess, sys, time, unicodedata, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BACKUP = os.path.join(HERE, "backup")
CACHE = os.path.join(HERE, "cache")

# ---------------------------------------------------------------- the phone

def session_cookie():
    raw = open(os.path.join(os.environ["APPDATA"], "Xoosh", "session.txt"), encoding="utf-8").read().strip()
    return raw if raw.startswith("xoosh_session=") else "xoosh_session=" + raw

class Phone:
    def __init__(self, addr):
        self.base = "http://" + addr
        self.cookie = session_cookie()

    def req(self, path, method="GET", body=None, timeout=120):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method,
                                   headers={"Cookie": self.cookie, "Content-Type": "application/json"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(r, timeout=timeout) as res:
            return res.read()

    def tracks(self):
        return json.loads(self.req("/api/music"))["tracks"]

    def lyrics(self, tid):
        d = json.loads(self.req(f"/api/music/lyrics/{tid}"))
        return d.get("lyrics") if d.get("found") else None

    def put_lyrics(self, tid, doc):
        return json.loads(self.req(f"/api/music/lyrics/{tid}", "PUT", doc))

    def audio(self, t):
        os.makedirs(CACHE, exist_ok=True)
        path = os.path.join(CACHE, f"{t['id']}.bin")
        if not os.path.exists(path) or os.path.getsize(path) != t.get("size", -1):
            data = self.req(f"/api/music/stream/{t['id']}", timeout=600)
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
        return path

# ---------------------------------------------------------------- lyrics

STAMP = re.compile(r"\[(\d+):(\d+(?:[.:]\d+)?)\]")
WORD_STAMP = re.compile(r"<\d+:\d+(?:\.\d+)?>")

def lrc_ms(m, s):
    return round((int(m) * 60 + float(s.replace(":", "."))) * 1000)

def fmt(ms):
    ms = max(0, int(round(ms)))
    return f"{ms // 60000:02d}:{(ms % 60000) / 1000:05.2f}"

def parse_lines(lrc):
    """The timed lines, one per time (a chorus stamped twice becomes two), in order; and the rest (header tags)."""
    lines, head = [], []
    for raw in lrc.splitlines():
        stamps, rest = [], raw
        while True:
            m = STAMP.match(rest.lstrip())
            if not m:
                break
            stamps.append(lrc_ms(m.group(1), m.group(2)))
            rest = rest.lstrip()[m.end():]
        if not stamps:
            if raw.strip():
                head.append(raw.rstrip())
            continue
        text = WORD_STAMP.sub("", rest).strip()
        for st in stamps:
            lines.append([st, text])
    lines.sort(key=lambda x: x[0])
    return lines, head

def norm(word):
    """A word as the aligner reads it: lower case, letters a-z and the apostrophe, accents dropped."""
    w = unicodedata.normalize("NFKD", word.lower())
    w = "".join(c for c in w if not unicodedata.combining(c))
    w = w.replace("’", "'").replace("`", "'")
    return "".join(c for c in w if "a" <= c <= "z" or c == "'").strip("'")

# ---------------------------------------------------------------- the aligner

class Aligner:
    def __init__(self):
        import torch, torchaudio
        self.torch, self.ta = torch, torchaudio
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        b = torchaudio.pipelines.MMS_FA
        self.sr = b.sample_rate
        self.model = b.get_model(with_star=False).to(self.device).eval()
        self.tokenizer = b.get_tokenizer()
        self.align = b.get_aligner()
        self.chars = set(b.get_dict(star=None).keys())

    def load(self, path, vocals=False):
        """The song as the aligner hears it: 16 kHz mono; with [vocals], the singing alone (Demucs)."""
        if vocals:
            return self.separated(path)
        pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(self.sr), "-f", "f32le", "-"],
                             capture_output=True, check=True).stdout
        return self.torch.frombuffer(bytearray(pcm), dtype=self.torch.float32)

    def separated(self, path):
        """The vocals pulled out of the mix by Demucs (htdemucs) on the GPU, so the beat does not
        get in the aligner's way; 16 kHz mono."""
        torch = self.torch
        if not hasattr(self, "demucs"):
            from demucs.pretrained import get_model
            from demucs.apply import apply_model
            self.demucs = get_model("htdemucs").to(self.device).eval()
            self.apply_model = apply_model
        m = self.demucs
        pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "2", "-ar", str(m.samplerate), "-f", "f32le", "-"],
                             capture_output=True, check=True).stdout
        wav = torch.frombuffer(bytearray(pcm), dtype=torch.float32).view(-1, 2).t().contiguous()
        ref = wav.mean(0)
        mean, std = ref.mean(), ref.std() + 1e-8
        with torch.inference_mode():
            out = self.apply_model(m, ((wav - mean) / std)[None], device=self.device, split=True, overlap=0.25, progress=False)[0]
        voc = (out[m.sources.index("vocals")] * std + mean).mean(0)
        return self.ta.functional.resample(voc.cpu(), m.samplerate, self.sr).contiguous()

    def line(self, audio, start_ms, end_ms, words, pad=250, skip=()):
        """Times (ms) for each word of one line between start_ms and end_ms (searched [pad] ms either
        side), and the mean confidence; None if it cannot. Words in [skip] (ad-libs) are not looked
        for, only placed between their neighbours."""
        keys = [("".join(c for c in norm(w) if c in self.chars)) if i not in skip else "" for i, w in enumerate(words)]
        idx = [i for i, k in enumerate(keys) if k]
        if not idx or len(idx) < (len(words) - len(skip)) * 0.6:
            return None
        a = max(0, int((start_ms - pad) * self.sr / 1000))
        b = min(audio.numel(), int((end_ms + pad) * self.sr / 1000))
        if b - a < self.sr * 0.2:
            return None
        seg = audio[a:b].unsqueeze(0).to(self.device)
        with self.torch.inference_mode():
            emission, _ = self.model(seg)
        tokens = self.tokenizer([keys[i] for i in idx])
        if sum(len(t) for t in tokens) > emission.size(1):
            return None
        try:
            spans = self.align(emission[0], tokens)
        except Exception:
            return None
        per_frame = (b - a) / emission.size(1) / self.sr * 1000
        starts, score = {}, []
        for i, sp in zip(idx, spans):
            starts[i] = a * 1000 / self.sr + sp[0].start * per_frame
            score.append(sum(s.score for s in sp) / len(sp))
        # Words the aligner cannot read (numbers, symbols): placed between their neighbours.
        times = []
        for i in range(len(words)):
            if i in starts:
                times.append(starts[i])
            else:
                prev = next((starts[j] for j in range(i - 1, -1, -1) if j in starts), start_ms)
                nxt = next((starts[j] for j in range(i + 1, len(words)) if j in starts), None)
                times.append(prev + 1 if nxt is None else (prev + nxt) / 2)
        # In order, and the first word not before the line itself.
        for k in range(1, len(times)):
            times[k] = max(times[k], times[k - 1] + 1)
        return times, sum(score) / len(score)

# ---------------------------------------------------------------- one song

MIN_SCORE = 0.2
PAD_MS = 250
# For --stats: each placed line's confidence and how far its first word is from the line's own time.
STATS = []

def adlibs(words):
    """The words inside brackets, (yeah, yeah) [Future], which are often barely heard."""
    out, depth = set(), 0
    for i, w in enumerate(words):
        opens = w.count("(") + w.count("[")
        if depth > 0 or opens:
            out.add(i)
        depth = max(0, depth + opens - w.count(")") - w.count("]"))
    return out

def enhance(aligner, audio, doc, length_ms):
    lines, head = parse_lines(doc["lrc"])
    out, placed, total = list(head), 0, 0
    for k, (t, text) in enumerate(lines):
        nxt = lines[k + 1][0] if k + 1 < len(lines) else min(length_ms, t + 8000)
        end = min(nxt, t + 12000)
        words = text.split()
        res = aligner.line(audio, t, end, words, pad=PAD_MS) if words else None
        # Not sure of the whole line: the main words alone, the ad-libs fitted in between.
        if words and (not res or res[1] < MIN_SCORE):
            skip = adlibs(words)
            if skip and len(skip) < len(words):
                again = aligner.line(audio, t, end, words, pad=PAD_MS, skip=skip)
                if again and again[1] >= MIN_SCORE:
                    res = again
        if words:
            total += 1
        if res and res[1] >= MIN_SCORE:
            placed += 1
            times, _ = res
            STATS.append((res[1], abs(times[0] - t)))
            body = "".join(f"<{fmt(ms)}>{w}{' ' if i < len(words) - 1 else ''}" for i, (w, ms) in enumerate(zip(words, times)))
            out.append(f"[{fmt(t)}]{body}")
        else:
            out.append(f"[{fmt(t)}]{text}")
    return "\n".join(out) + "\n", placed, total

def main():
    global PAD_MS, MIN_SCORE
    ap = argparse.ArgumentParser()
    ap.add_argument("--phone", required=True)
    ap.add_argument("--ids", default="")
    ap.add_argument("--redo", action="store_true", help="align songs already timed by the word again (from the backup)")
    ap.add_argument("--dry", action="store_true", help="work it out and report, but give the phone nothing")
    ap.add_argument("--restore", action="store_true", help="give the phone back the lyrics kept in the backup")
    ap.add_argument("--show", type=int, default=0, help="print this many of each song's new lines")
    ap.add_argument("--stats", action="store_true", help="report how close each line's first word lands to the line's own time, by confidence")
    ap.add_argument("--pad", type=int, default=PAD_MS, help="how far either side of a line's time to look for its words (ms)")
    ap.add_argument("--min", type=float, default=MIN_SCORE, help="the least confidence a line's word times are kept at")
    ap.add_argument("--vocals", action="store_true", help="align against the vocals alone, pulled out of the mix with Demucs (slower, more accurate)")
    args = ap.parse_args()
    PAD_MS, MIN_SCORE = args.pad, args.min
    phone = Phone(args.phone)
    tracks = phone.tracks()
    if args.ids:
        want = {int(x) for x in args.ids.split(",")}
        tracks = [t for t in tracks if t["id"] in want]
    os.makedirs(BACKUP, exist_ok=True)

    if args.restore:
        for t in tracks:
            p = os.path.join(BACKUP, f"{t['id']}.json")
            if os.path.exists(p):
                phone.put_lyrics(t["id"], json.load(open(p, encoding="utf-8")))
                print("restored", t["title"])
        return

    aligner = Aligner()
    print("aligner on", aligner.device, flush=True)
    done = skipped = 0
    for n, t in enumerate(tracks, 1):
        label = f"[{n}/{len(tracks)}] {t['title'][:50]}"
        try:
            doc = phone.lyrics(t["id"])
            bp = os.path.join(BACKUP, f"{t['id']}.json")
            if doc and WORD_STAMP.search(doc.get("lrc") or "") and os.path.exists(bp):
                if not args.redo:
                    print(label, "- timed by the word already"); skipped += 1; continue
                doc = json.load(open(bp, encoding="utf-8"))
            if not doc or not doc.get("lrc") or doc.get("instrumental") or doc.get("lyricsfile"):
                print(label, "- no line-timed lyrics to work from"); skipped += 1; continue
            if not os.path.exists(bp):
                json.dump(doc, open(bp, "w", encoding="utf-8"), ensure_ascii=False)
            t0 = time.time()
            path = phone.audio(t)
            audio = aligner.load(path, vocals=args.vocals)
            os.remove(path)  # the song's copy is only needed for this
            lrc, placed, total = enhance(aligner, audio, doc, t["durationMs"])
            print(f"{label} - {placed}/{total} lines by the word ({time.time() - t0:.1f} s)", flush=True)
            for ln in [l for l in lrc.splitlines() if "<" in l][:args.show]:
                print("    " + ln)
            if placed and not args.dry:
                new = dict(doc)
                new["lrc"] = lrc
                new.pop("sidecar", None)
                phone.put_lyrics(t["id"], new)
                done += 1
        except Exception as e:
            print(label, "- failed:", str(e)[:120], flush=True)
    print(f"done: {done} songs given word timing, {skipped} skipped")
    if args.stats and STATS:
        import statistics
        for lo, hi in ((0.0, 0.15), (0.15, 0.2), (0.2, 0.3), (0.3, 0.5), (0.5, 1.01)):
            d = sorted(off for sc, off in STATS if lo <= sc < hi)
            if d:
                print(f"  confidence {lo:.2f}-{hi:.2f}: {len(d):4} lines, first word off by median {statistics.median(d):.0f} ms,"
                      f" 90% within {d[int(len(d) * 0.9) - 1 if len(d) > 1 else 0]:.0f} ms")

if __name__ == "__main__":
    main()
