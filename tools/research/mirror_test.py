import json
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0"
BASE_OLD = "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u"  # removed upstream, historical
BASE_1 = "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/index.m3u"   # current canonical list
BASE_2 = "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u"

MIRRORS = [
    "https://github.moeyy.xyz/",
    "https://ghfast.top/",
    "https://gh-proxy.llyke.com/",
    "https://cf.ghproxy.cc/",
    "https://gh.llkk.cc/",
    "https://ghproxy.cn/",
    "https://www.ghproxy.cc/",
    "https://github.horsenma.top/",
    "https://ghp.ci/",
    "https://ghproxy.click/",
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
    "https://raw.gitmirror.com/",
    "https://ghps.cc/",
    "https://gh.ddlc.top/",
    "https://mirror.ghproxy.com/",
    "https://ghproxy.cc/",
    "https://gh.con.sh/",
    "https://hub.gitmirror.com/",
]


def probe(url, label=""):
    """One attempt; returns dict with status/ms/length/body-prefix/err."""
    start = time.time()
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": UA,
            "Range": "bytes=0-0",
        })
        with urllib.request.urlopen(req, timeout=8) as resp:
            ms = int((time.time() - start) * 1000)
            body = resp.read(512)
            return {"status": resp.status, "ms": ms,
                    "length": resp.headers.get("Content-Length"),
                    "body": body, "err": None}
    except urllib.error.HTTPError as e:
        ms = int((time.time() - start) * 1000)
        return {"status": e.code, "ms": ms, "length": None, "body": b"", "err": f"HTTP {e.code}"}
    except Exception as e:
        ms = int((time.time() - start) * 1000)
        return {"status": None, "ms": ms, "length": None, "body": b"",
                "err": f"{type(e).__name__}: {e}"}


def is_ok(r):
    """A valid raw-file response: 206 with byte range, or 200 that is not HTML."""
    if r["status"] == 206 and r["length"] == "1":
        return True
    if r["status"] == 200:
        b = r["body"]
        if b.startswith(b"#EXTM3U") or b.startswith(b"#") or b.startswith(b"\xef\xbb\xbf#"):
            return True
        if not (b.lstrip().startswith(b"<!DOCTYPE") or b.lstrip().startswith(b"<html")
                or b.startswith(b"Suspent")):
            # non-HTML 200 (e.g. plain error text) counts as unknown; treat as ok only if m3u-like
            return False
    return False


def run(task, attempts=3):
    mirror, base, url = task
    best = None
    for i in range(attempts):
        r = probe(url, label=mirror)
        if best is None or r["status"] == 206:
            best = r
        if is_ok(r):
            break
        time.sleep(0.3)
    return {
        "mirror": mirror,
        "base_file": base,
        "url": url,
        "status": best["status"],
        "ms": best["ms"],
        "length": best["length"],
        "content_type": ("m3u" if best["body"].lstrip().startswith(b"#")
                         else "html" if b"<!DOCTYPE" in best["body"] or b"<html" in best["body"]
                         else "text" if best["body"] else ""),
        "err": best["err"],
        "attempts": attempts,
    }


tasks = []
tasks.append(("DIRECT", BASE_OLD, BASE_OLD))
tasks.append(("DIRECT", BASE_1, BASE_1))
tasks.append(("DIRECT", BASE_2, BASE_2))
for m in MIRRORS:
    tasks.append((m, BASE_OLD, m + BASE_OLD))
    tasks.append((m, BASE_1, m + BASE_1))
    tasks.append((m, BASE_2, m + BASE_2))
tasks.append(("JSDELIVR", BASE_OLD, "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/m3u/ipv4.m3u"))
tasks.append(("JSDELIVR", BASE_1, "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/m3u/index.m3u"))
tasks.append(("JSDELIVR", BASE_2, "https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u"))
tasks.append(("FMM-DIRECT", BASE_OLD, "https://live.fanmingming.com/tv/m3u/ipv4.m3u"))
tasks.append(("FMM-DIRECT", BASE_OLD, "https://live.fanmingming.cn/tv/m3u/ipv4.m3u"))
tasks.append(("FMM-DIRECT", BASE_1, "https://live.fanmingming.cn/tv/m3u/index.m3u"))
tasks.append(("FMM-DIRECT", BASE_1, "https://live.fanmingming.cn/tv/m3u/demo.m3u"))

results = []
with ThreadPoolExecutor(max_workers=10) as ex:
    for r in ex.map(run, tasks):
        results.append(r)

with open(r"D:\AI\agent\codex\android\tv\tools\research\mirror_test.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
for r in sorted(results, key=lambda x: (x["status"] or 999, x["ms"])):
    print(f"{r['status']!s:>5} {r['ms']:>6}ms len={r['length']!s:>6} {r['content_type']:>5} "
          f"{r['mirror']:<26} {r['base_file'][8:60]}")
print("DONE", len(results), "tests")
