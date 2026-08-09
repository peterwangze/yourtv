"""Third round: YanG-1989 Gather/Migu, qwerttvv operator lists, ngo5 ipv4."""
import json
import os
import re
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"}
OUT = os.path.join(os.path.dirname(__file__), "downloads")
os.makedirs(OUT, exist_ok=True)

CANDIDATES = [
    ("yang1989_gather", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u"]),
    ("yang1989_migu", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/Migu.m3u"]),
    ("qwerttvv_mobile", ["https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/IPTV-Mobile.m3u"]),
    ("qwerttvv_unicom", ["https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/IPTV-Unicom.m3u"]),
    ("ngo5_ipv4", ["https://raw.githubusercontent.com/ngo5/IPTV/main/m3u/ipv4.m3u"]),
    ("ngo5_ipv6", ["https://raw.githubusercontent.com/ngo5/IPTV/main/m3u/ipv6.m3u"]),
]


def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers=UA)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            return data, (time.time() - t0) * 1000, ""
    except Exception as e:
        return None, (time.time() - t0) * 1000, str(e)[:160]


def main():
    results = []
    for label, urls in CANDIDATES:
        data = ms = None
        errs = []
        ok_url = None
        for u in urls:
            data, ms, err = fetch(u)
            if data is not None and len(data) > 0:
                ok_url = u
                break
            errs.append(err)
        if ok_url is None:
            results.append({"label": label, "ok": False, "errs": errs[:2]})
            print(f"[FAIL] {label}: {errs[0] if errs else 'no data'}")
            continue
        ext = ok_url.rsplit(".", 1)[-1].lower()
        ext = ext if ext in ("m3u", "m3u8", "txt") else "bin"
        path = os.path.join(OUT, f"{label}.{ext}")
        with open(path, "wb") as f:
            f.write(data)
        results.append({"label": label, "ok": True, "url": ok_url, "size": len(data), "ms": round(ms)})
        print(f"[OK] {label}: {len(data)} B, {ms:.0f} ms <- {ok_url}")

    # Discover file names via GitHub HTML pages (no API rate limit) for two repos
    html_results = {}
    for owner, repo in [("kimwang1978", "collect-tv-txt"), ("CCSH", "IPTV")]:
        try:
            req = urllib.request.Request(f"https://github.com/{owner}/{repo}", headers=UA)
            with urllib.request.urlopen(req, timeout=25) as r:
                html = r.read().decode("utf-8", "ignore")
            names = sorted(set(re.findall(r'href="[^"]*/([^/"#?]+\.(?:txt|m3u|m3u8))"', html)))
            html_results[f"{owner}/{repo}"] = names[:50]
            print(f"{owner}/{repo} HTML files: {names[:50]}")
        except Exception as e:
            html_results[f"{owner}/{repo}"] = {"err": str(e)[:140]}
            print(f"{owner}/{repo} HTML ERR: {e}")

    with open(os.path.join(OUT, "_result3.json"), "w", encoding="utf-8") as f:
        json.dump({"downloads": results, "html": html_results}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
