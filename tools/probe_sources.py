"""Probe public IPTV source lists: download, count channels, detect 4K/Migu entries.

Usage: python tools/probe_sources.py [url ...]
If no URLs given, probes the default candidate set.
"""
import re
import sys
import urllib.request
import urllib.error
import ssl
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

DEFAULT_URLS = [
    # current bundled sources
    "https://live.zbds.top/tv/iptv4.txt",
    "https://live.fanmingming.cn/tv/m3u/ipv6.m3u",
    "https://live.fanmingming.com/tv/m3u/ipv6.m3u",
    "https://live.fanmingming.cn/tv/m3u/ipv4.m3u",
    "https://iptv-org.github.io/iptv/countries/cn.m3u",
    "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8",
    "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
    "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
    "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u",
    "https://iptv-org.github.io/iptv/languages/zho.m3u",
    "https://iptv-org.github.io/iptv/countries/hk.m3u",
    "https://iptv-org.github.io/iptv/countries/tw.m3u",
    "https://iptv-org.github.io/iptv/countries/mo.m3u",
    # candidates
    "https://raw.githubusercontent.com/HerbertHe/iptv-sources/main/iptv.m3u",
    "https://raw.githubusercontent.com/HerbertHe/iptv-sources/main/national.m3u",
    "https://raw.githubusercontent.com/YueChan/Live/main/APTV.m3u",
    "https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u",
    "https://raw.githubusercontent.com/YueChan/Live/main/IPTV6.m3u",
    "https://tv.iill.top/m3u/Gather",
    "https://tv.iill.top/m3u/CN",
    "https://raw.githubusercontent.com/Yiov/wo/main/IPTV.m3u",
    "https://raw.githubusercontent.com/Yiov/wo/main/TV.m3u",
    "https://raw.githubusercontent.com/guoweiok/tv/main/tv.txt",
    "https://raw.githubusercontent.com/islovezz/iptv/main/tv.m3u",
    "https://raw.githubusercontent.com/sx1978/iptv/main/iptv.m3u",
    "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
    "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u",
    "https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/最新版.txt",
    "https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/影视.txt",
    "https://raw.githubusercontent.com/bestfans/iptv-sources/main/cn_all.m3u8",
    "https://raw.githubusercontent.com/wudongdefeng/iptv/main/iptv.m3u",
    "https://raw.githubusercontent.com/zxing003/iptv/main/cctv.m3u",
    "https://raw.githubusercontent.com/Meroser/IPTV/main/IPTV.m3u",
    "https://raw.githubusercontent.com/Meroser/IPTV/main/IPTV-IPV6.m3u",
    "https://raw.githubusercontent.com/SilentDemonSD/IPTV/main/iptv.m3u",
    "https://raw.githubusercontent.com/luongz/iptv/main/iptv.m3u",
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist.m3u8",
    "https://raw.githubusercontent.com/fenxp/iptv/main/live.m3u",
]


def fetch(url: str, timeout: int = 20) -> bytes | None:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            data = r.read()
            return data if len(data) > 0 else None
    except Exception:
        return None


def parse_entries(text: str):
    """Return list of (title, url) from m3u / txt / json-ish content."""
    entries = []
    if text.startswith("#EXTM3U") or "#EXTINF" in text:
        lines = text.splitlines()
        cur_title = None
        for line in lines:
            s = line.strip()
            if s.startswith("#EXTINF"):
                m = re.search(r'tvg-name="([^"]+)"', s)
                if m:
                    cur_title = m.group(1)
                else:
                    m2 = re.search(r",\s*([^,]+)\s*$", s)
                    cur_title = m2.group(1).strip() if m2 else None
            elif s and not s.startswith("#") and cur_title:
                if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", s):
                    entries.append((cur_title, s))
                cur_title = None
        return entries
    # txt style: "name,url" or "group,#genre#"
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "#genre#" in s:
            continue
        parts = s.split(",")
        if len(parts) >= 2 and re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", parts[-1].strip()):
            entries.append((parts[0].strip(), parts[-1].strip()))
    return entries


def analyze(url: str):
    t0 = time.time()
    data = fetch(url)
    dt = time.time() - t0
    if data is None:
        return {"url": url, "ok": False, "ms": int(dt * 1000)}
    text = data.decode("utf-8", errors="replace")
    entries = parse_entries(text)
    urls = [u for _, u in entries]
    fourk = sum(1 for u in urls if re.search(r"4k|2160|uhd", u, re.I))
    migu = sum(1 for u in urls if "migu" in u.lower())
    domains = {}
    for u in urls:
        m = re.match(r"https?://([^/]+)", u)
        if m:
            d = m.group(1)
            domains[d] = domains.get(d, 0) + 1
    top_domains = sorted(domains.items(), key=lambda kv: -kv[1])[:8]
    return {
        "url": url, "ok": True, "ms": int(dt * 1000), "bytes": len(data),
        "channels": len(entries), "4k": fourk, "migu": migu,
        "top_domains": top_domains,
    }


def main():
    urls = sys.argv[1:] or DEFAULT_URLS
    results = []
    with ThreadPoolExecutor(max_workers=12) as ex:
        futures = {ex.submit(analyze, u): u for u in urls}
        for f in as_completed(futures):
            results.append(f.result())
    results.sort(key=lambda r: urls.index(r["url"]))
    for r in results:
        if r["ok"]:
            print(f"OK   {r['ms']:5d}ms {r['bytes']:>8d}B ch={r['channels']:5d} 4k={r['4k']:4d} migu={r['migu']:4d} | {r['url']}")
            if r["top_domains"]:
                print("        domains: " + ", ".join(f"{d}({n})" for d, n in r["top_domains"]))
        else:
            print(f"FAIL {r['ms']:5d}ms | {r['url']}")
    print("\n=== SUMMARY ===")
    for r in results:
        if r["ok"]:
            print(f"ch={r['channels']:5d} 4k={r['4k']:4d} migu={r['migu']:4d} {r['url']}")
        else:
            print(f"FAIL {r['url']}")


if __name__ == "__main__":
    main()
