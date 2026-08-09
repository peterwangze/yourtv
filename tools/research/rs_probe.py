"""Probe sample channel URLs per source for liveness.

GET with Range: bytes=0-0, 6s timeout, stratified sample: 5 cctv / 5 weishi / 5 local+ott.
Output: tools/research/probe_results.json
"""
import json
import os
import random
import re
import ssl
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = r"D:\AI\agent\codex\android\tv\tools\research"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

NOISE_KW = ["回放", "点播", "春晚", "电影", "剧场", "影视", "广告", "测试", "体验", "纪录片",
            "航拍", "专题", "收藏", "花絮", "预告", "片花", "MV"]
NOISE_URL_KW = ["gslbmgspvod", ".mp4", "vod", "playback", "migu_vod", "epg.pw/stream",
                "kwimgs", "yzzy", "bdstatic", "lzcdn"]


def parse_file(path: str) -> list[dict]:
    try:
        raw = open(path, "rb").read().decode("utf-8", "ignore")
    except Exception:
        return []
    entries = []
    if raw.lstrip().startswith("#EXTM3U") or ".m3u" in path.lower():
        lines = raw.splitlines()
        i = 0
        cur_group = ""
        while i < len(lines):
            line = lines[i].strip()
            if line.startswith("#EXTINF"):
                m = re.search(r'group-title="([^"]*)"', line)
                if m:
                    cur_group = m.group(1)
                title = line.split(",", 1)[1] if "," in line else ""
                j = i + 1
                while j < len(lines) and (lines[j].strip().startswith("#") or not lines[j].strip()):
                    j += 1
                url = lines[j].strip() if j < len(lines) else ""
                entries.append({"title": title.strip(), "url": url, "group": cur_group})
                i = j + 1
            else:
                i += 1
    else:
        for line in raw.splitlines():
            line = line.strip()
            m = re.search(r"(https?://\S+)", line)
            if m:
                entries.append({"title": line[: m.start()].strip(" ,#\t"), "url": m.group(1), "group": ""})
    return [e for e in entries if e["url"].startswith(("http://", "https://"))]


def category(e: dict) -> str:
    t = e["title"].lower()
    u = e["url"].lower()
    g = (e.get("group") or "").lower()
    if "cctv" in t or "cgtn" in t:
        return "cctv"
    if "卫视" in e["title"]:
        return "weishi"
    if any(k in e["title"] for k in NOISE_KW) or any(k in u for k in NOISE_URL_KW):
        return "noise"
    return "other"


def sample(entries: list[dict], per_cat: int = 5) -> list[dict]:
    random.seed(42)
    by_cat = {"cctv": [], "weishi": [], "other": []}
    for e in entries:
        c = category(e)
        if c in by_cat:
            by_cat[c].append(e)
    picked = []
    for c, lst in by_cat.items():
        random.shuffle(lst)
        # prefer distinct hosts
        seen_hosts = set()
        for e in lst:
            if len(picked) >= 15:
                break
            host = re.sub(r"^https?://", "", e["url"]).split("/")[0]
            if host in seen_hosts and len(picked) >= 10:
                continue
            seen_hosts.add(host)
            picked.append(e)
            if len([p for p in picked if category(p) == c]) >= per_cat:
                break
    return picked


def probe_one(e: dict):
    url = e["url"]
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-0"})
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=6, context=ctx) as r:
            r.read(1024)
            code = r.status
            ms = (time.time() - t0) * 1000
            return {"url": url, "ok": 200 <= code < 400, "code": code, "ms": round(ms)}
    except Exception as ex:
        return {"url": url, "ok": False, "code": None, "ms": round((time.time() - t0) * 1000), "err": str(ex)[:80]}


def main():
    files = []
    for d in (os.path.join(ROOT, "sources"), os.path.join(ROOT, "downloads")):
        for fn in sorted(os.listdir(d)):
            if not fn.startswith("_") and os.path.isfile(os.path.join(d, fn)):
                files.append(os.path.join(d, fn))
    results = {}
    for path in files:
        entries = parse_file(path)
        picked = sample(entries)
        if not picked:
            results[os.path.basename(path)] = {"sampled": 0, "alive": 0, "detail": []}
            continue
        with ThreadPoolExecutor(max_workers=12) as ex:
            futs = [ex.submit(probe_one, e) for e in picked]
            detail = [f.result() for f in as_completed(futs)]
        alive = sum(1 for d in detail if d["ok"])
        results[os.path.basename(path)] = {
            "sampled": len(detail),
            "alive": alive,
            "rate": round(100 * alive / len(detail), 1) if detail else 0,
            "detail": detail,
        }
        print(f"{os.path.basename(path):32s} {alive}/{len(detail)} alive ({results[os.path.basename(path)]['rate']}%)")
    with open(os.path.join(ROOT, "probe_results.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
