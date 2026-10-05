"""
Checks the phone's lyrics against the singing, and puts right what is wrong.

LRCLIB is written by its users, and an entry is now and then another song's, another version's, or
lines in the wrong order with times that do not fit. align.py trusts each line's time and looks for
its words near it, so it cannot tell. This lines the whole song's words up with the whole song at
once (MMS_FA again, on the vocals Demucs pulls out, with a "star" between lines that takes up any
singing the lyrics leave out), without looking at the times at all, and so learns:

- how sure it is of the words (a wrong song's words find nothing to land on), and
- when each line is really sung, against the time the lyrics give it.

A song whose lyrics fit badly is looked up again on LRCLIB: every entry of about its length is lined
up the same way, and the best one's words are kept. Lines are then timed from where they are sung
(the lyrics' own time kept where it agrees, or where the line could not be heard), and align.py's
word timing runs on the result.

  tools/lyrics-align/venv/Scripts/python tools/lyrics-align/verify.py --phone 127.0.0.1:8788
      [--ids 151,206] [--album "good kid"] [--fix] [--words]

Without --fix it only reports. --fix gives the phone the better lyrics (the old kept in backup/ as
align.py does); --words then times them word by word.
"""
import argparse, json, os, re, statistics, sys, time, urllib.parse, urllib.request

import align
from align import Phone, Aligner, parse_lines, norm, fmt, WORD_STAMP, BACKUP

LRCLIB = "https://lrclib.net/api"
UA = "Localhost 8787 lyrics check (https://github.com/subalzt/localhost-8787)"
# A song's words placed with less mean confidence than this do not fit it.
FIT = 0.30
# A line sung further than this from its given time is timed from the singing.
OFF_MS = 1200
CHUNK_S, CONTEXT_S = 30, 1

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def say_number(n):
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        return ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + say_number(n % 100))
    if 1900 <= n < 2100 and n % 100:  # years, as said: nineteen ninety-four
        return say_number(n // 100) + " " + say_number(n % 100)
    if n < 10000:
        return say_number(n // 1000) + " thousand" + ("" if n % 1000 == 0 else " " + say_number(n % 1000))
    return ""


def spoken(word):
    """What the aligner should listen for: digits as said ("82" as "eighty two"), else the word."""
    m = re.fullmatch(r"\W*(\d{1,4})(s|th|st|nd|rd)?\W*", word)
    if m:
        return say_number(int(m.group(1))).split()
    k = norm(word)
    return [k] if k else []


class Whole:
    """The whole song's words lined up with the whole song."""

    def __init__(self, base):
        import torchaudio
        self.base, self.torch = base, base.torch
        b = torchaudio.pipelines.MMS_FA
        self.model = b.get_model(with_star=True).to(base.device).eval()
        self.tokenizer = b.get_tokenizer()
        self.aligner = b.get_aligner()
        self.chars = set(b.get_dict(star="*").keys()) - {"*", "-"}
        self.sr = base.sr

    def emission(self, audio):
        """Frame by frame (20 ms) likelihoods for the whole song, worked out 30 s at a time with a
        second either side so no frame sits at a cut."""
        torch, hop = self.torch, 320
        win, ctx = CHUNK_S * self.sr, CONTEXT_S * self.sr
        parts = []
        for s in range(0, audio.numel(), win):
            a, b = max(0, s - ctx), min(audio.numel(), s + win + ctx)
            seg = audio[a:b].unsqueeze(0).to(self.base.device)
            if seg.size(1) < 800:
                break
            with torch.inference_mode():
                em, _ = self.model(seg)
            em = em[0].cpu()
            per = (b - a) / em.size(0)
            lo = int(round((s - a) / per))
            hi = int(round((min(s + win, audio.numel()) - a) / per))
            parts.append(em[lo:hi])
        em = torch.cat(parts)
        return em, audio.numel() / self.sr * 1000 / em.size(0)

    def fit(self, em, ms_per_frame, lines):
        """lines: [(time or None, text)]. Returns, per line, (start_ms, end_ms, confidence) or None
        when it had nothing to listen for; and the song's mean confidence over its words."""
        words, owner = [], []
        for k, (_, text) in enumerate(lines):
            for w in text.split():
                for part in spoken(w):
                    key = "".join(c for c in part if c in self.chars)
                    if key:
                        words.append(key)
                        owner.append(k)
        if not words:
            return [None] * len(lines), 0.0
        # A star before the song, between lines and after it, for singing the lyrics leave out.
        seq, who = ["*"], [-1]
        for i, w in enumerate(words):
            if i and owner[i] != owner[i - 1]:
                seq.append("*"); who.append(-1)
            seq.append(w); who.append(owner[i])
        seq.append("*"); who.append(-1)
        tokens = self.tokenizer(seq)
        if sum(len(t) for t in tokens) > em.size(0):
            return [None] * len(lines), 0.0
        spans = self.aligner(em, tokens)
        per_line = {}
        scores = []
        for sp, k in zip(spans, who):
            if k < 0:
                continue
            sc = sum(s.score for s in sp) / len(sp)
            scores.append(sc)
            st, en = sp[0].start * ms_per_frame, sp[-1].end * ms_per_frame
            p = per_line.setdefault(k, [st, en, []])
            p[0], p[1] = min(p[0], st), max(p[1], en)
            p[2].append(sc)
        out = [(p[0], p[1], sum(p[2]) / len(p[2])) if (p := per_line.get(k)) else None for k in range(len(lines))]
        return out, sum(scores) / len(scores)


def report(lines, fitted, conf):
    """How far the given times are from where the lines are sung (lines heard about as surely as the
    song's average): median, and the share within OFF_MS."""
    sure = max(0.08, conf * 0.8)
    offs = [abs(f[0] - t) for (t, _), f in zip(lines, fitted) if f and t is not None and f[2] >= sure]
    if not offs:
        return None, 0.0
    return statistics.median(offs), sum(1 for o in offs if o <= OFF_MS) / len(offs)


def lrclib_candidates(t):
    """LRCLIB's entries for the song, within 4 s of its length."""
    def get(path, q):
        url = LRCLIB + path + "?" + urllib.parse.urlencode(q)
        r = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(r, timeout=20) as res:
                return json.loads(res.read())
        except Exception:
            return None
    title = re.sub(r"\s*[\(\[][^)\]]*(bonus|remaster|version|edit|deluxe|explicit|live)[^)\]]*[\)\]]", "", t["title"], flags=re.I).strip()
    artist = re.split(r",|&| feat\.| ft\.| x ", t["artist"])[0].strip()
    found = {}
    for q in ({"track_name": title, "artist_name": artist}, {"q": title + " " + artist}, {"track_name": t["title"], "artist_name": artist}):
        for x in get("/search", q) or []:
            if abs((x.get("duration") or 0) - t["durationMs"] / 1000) <= 4 and (x.get("syncedLyrics") or x.get("plainLyrics")):
                found[x["id"]] = x
    return list(found.values())


def as_lines(entry):
    """An LRCLIB entry as [(time or None, text)]: its timed lines, else its plain lines untimed."""
    if entry.get("syncedLyrics"):
        lines, _ = parse_lines(entry["syncedLyrics"])
        return [(t, x) for t, x in lines if x.strip()]
    return [(None, x.strip()) for x in (entry.get("plainLyrics") or "").splitlines() if x.strip() and not re.fullmatch(r"\[[^\]]*\]", x.strip())]


def retime(lines, fitted, conf, length_ms):
    """The lines timed where they are sung: the given time where it agrees (within OFF_MS) or where
    the line could not be heard; otherwise where it was heard; lines with neither, spread between
    their neighbours. Kept in order."""
    times = []
    for (t, _), f in zip(lines, fitted):
        heard = f[0] if f and f[2] >= max(0.08, conf * 0.8) else None
        if heard is not None and (t is None or abs(heard - t) > OFF_MS):
            times.append(heard)
        else:
            times.append(t)
    # Fill gaps between known times.
    k = 0
    while k < len(times):
        if times[k] is not None:
            k += 1
            continue
        j = k
        while j < len(times) and times[j] is None:
            j += 1
        lo = times[k - 1] if k else 0
        hi = times[j] if j < len(times) else length_ms
        for i in range(k, j):
            times[i] = lo + (hi - lo) * (i - k + 1) / (j - k + 1)
        k = j
    for i in range(1, len(times)):
        times[i] = max(times[i], times[i - 1] + 10)
    return "".join(f"[{fmt(ms)}]{text}\n" for ms, (_, text) in zip(times, lines))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phone", required=True)
    ap.add_argument("--ids", default="")
    ap.add_argument("--album", default="")
    ap.add_argument("--fix", action="store_true", help="give the phone better lyrics where these fit badly")
    ap.add_argument("--words", action="store_true", help="then time them word by word (align.py)")
    ap.add_argument("--out", default=os.path.join(align.HERE, "verify.json"))
    args = ap.parse_args()
    align.remember_where()
    phone = Phone(args.phone)
    tracks = phone.tracks()
    if args.ids:
        want = {int(x) for x in args.ids.split(",")}
        tracks = [t for t in tracks if t["id"] in want]
    if args.album:
        tracks = [t for t in tracks if args.album.lower() in (t.get("album") or "").lower()]
    base = Aligner()
    whole = Whole(base)
    results = json.load(open(args.out, encoding="utf-8")) if os.path.exists(args.out) else {}
    os.makedirs(BACKUP, exist_ok=True)
    for n, t in enumerate(tracks, 1):
        label = f"[{n}/{len(tracks)}] {t['title'][:40]} - {t['artist'][:20]}"
        try:
            doc = phone.lyrics(t["id"])
            if doc and doc.get("instrumental"):
                print(label, "- instrumental"); continue
            bp = os.path.join(BACKUP, f"{t['id']}.json")
            # Word-timed already: check the line-timed lyrics it was worked from.
            src = json.load(open(bp, encoding="utf-8")) if doc and WORD_STAMP.search(doc.get("lrc") or "") and os.path.exists(bp) else doc
            have = [(tm, x) for tm, x in parse_lines(src["lrc"])[0] if x.strip()] if src and src.get("lrc") else \
                   [(None, x.strip()) for x in ((src or {}).get("plain") or "").splitlines() if x.strip()]
            t0 = time.time()
            path = phone.audio(t)
            audio = base.load(path, vocals=True)
            os.remove(path)
            em, mpf = whole.emission(audio)
            fitted, conf = whole.fit(em, mpf, have) if have else ([], 0.0)
            med, within = report(have, fitted, conf) if have else (None, 0.0)
            timed = bool(have) and have[0][0] is not None
            # Worth looking for better: no timing, times that do not agree with the singing, or words
            # that fit poorly (a fit is only poor against others for the same song: buried vocals fit
            # little however right the words are).
            bad = not have or not timed or within < 0.85 or conf < FIT
            line = f"{label} - fit {conf:.2f}" + (f", times off by median {med / 1000:.1f} s, {within:.0%} within {OFF_MS / 1000:.1f} s" if med is not None else "")
            r = {"title": t["title"], "artist": t["artist"], "album": t["album"], "fit": round(conf, 3),
                 "median_off_ms": med, "within": round(within, 3), "lines": len(have), "bad": bad}
            best = None
            if bad:
                cands = []
                for c in lrclib_candidates(t):
                    cl = as_lines(c)
                    if not cl:
                        continue
                    cf, cc = whole.fit(em, mpf, cl)
                    cm, cw = report(cl, cf, cc)
                    score = cc + (0.3 * cw if c.get("syncedLyrics") else 0)
                    cands.append((score, cc, cw, c, cl, cf))
                cands.sort(key=lambda x: -x[0])
                if cands:
                    score, cc, cw, c, cl, cf = cands[0]
                    line += f"  ->  best of {len(cands)} on LRCLIB (#{c['id']}): fit {cc:.2f}" + (f", {cw:.0%} on time" if c.get("syncedLyrics") else ", untimed")
                    # Better words (clearly), or the same words on time where these are not.
                    mine = conf + (0.3 * within if timed else 0)
                    if cc > conf + 0.05 or (score > mine + 0.05 and cc >= conf - 0.03):
                        best = (c, cl, cf, cc)
                r["candidates"] = [{"id": x[3]["id"], "fit": round(x[1], 3), "within": round(x[2], 3), "synced": bool(x[3].get("syncedLyrics"))} for x in cands[:6]]
            # Nothing better to be had, but the words fit: they are timed from the singing instead
            # (untimed lyrics, or times that are off). Buried vocals (a poor fit) are left alone,
            # since where they are heard cannot be trusted either.
            if bad and not best and have and conf >= 0.18 and (not timed or within < 0.85):
                best = (None, have, fitted, conf)
            print(line + f" ({time.time() - t0:.0f} s)", flush=True)
            r["fixed"] = False
            if best and args.fix:
                c, cl, cf, cc = best
                lrc = retime(cl, cf, cc, t["durationMs"])
                new = dict(src or {})
                if c is not None:
                    new.update(source="LRCLIB", plain=c.get("plainLyrics") or "", instrumental=False)
                new["lrc"], new["lyricsfile"] = lrc, ""
                new.pop("sidecar", None)
                if src and not os.path.exists(bp + ".before-verify"):
                    json.dump(src, open(bp + ".before-verify", "w", encoding="utf-8"), ensure_ascii=False)
                json.dump(new, open(bp, "w", encoding="utf-8"), ensure_ascii=False)
                phone.put_lyrics(t["id"], new)
                r["fixed"] = True
                src = new
                print("    given the phone:", (f"LRCLIB #{c['id']}" if c else "the same words, retimed"), flush=True)
            # Word by word (align.py), from the vocals already here: songs fixed now, and songs not timed by the word yet.
            if args.words and src and src.get("lrc") and (r["fixed"] or not WORD_STAMP.search((doc or {}).get("lrc") or "")):
                if not os.path.exists(bp):
                    json.dump(src, open(bp, "w", encoding="utf-8"), ensure_ascii=False)
                lrc, placed, total = align.enhance(base, audio, src, t["durationMs"])
                if placed:
                    new = dict(src)
                    new["lrc"] = lrc
                    new.pop("sidecar", None)
                    phone.put_lyrics(t["id"], new)
                r["words"] = [placed, total]
                print(f"    {placed}/{total} lines by the word", flush=True)
            results[str(t["id"])] = r
            json.dump(results, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        except Exception as e:
            print(label, "- failed:", str(e)[:160], flush=True)


if __name__ == "__main__":
    main()
