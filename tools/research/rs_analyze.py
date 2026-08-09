"""Analyze downloaded source files: channel counts by category + noise ratio.

Output: tools/research/analysis.json
"""
import json
import os
import re

ROOT = r"D:\AI\agent\codex\android\tv\tools\research"
SOURCES_DIR = os.path.join(ROOT, "sources")
DOWNLOADS_DIR = os.path.join(ROOT, "downloads")

NOISE_KW = ["回放", "点播", "春晚", "电影", "剧场", "影视", "广告", "测试", "体验", "纪录片",
            "航拍", "专题", "收藏", "花絮", "预告", "片花", "MV", "音乐现场", "中国村庄",
            "人与自然", "地理中国", "自然传奇", "飞碟之谜", "探索", "科教片"]
NOISE_URL_KW = ["gslbmgspvod", ".mp4", "vod", "playback", "migu_vod", "epg.pw/stream",
                "kwimgs", "yzzy", "bdstatic", "lzcdn"]


def parse_file(path: str) -> list[dict]:
    """Return list of {title, url, group}."""
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
                url = m.group(1)
                title = line[: m.start()].strip(" ,#\t")
                entries.append({"title": title, "url": url, "group": ""})
    return [e for e in entries if e["url"]]


def classify(e: dict) -> str:
    t = e["title"].lower()
    u = e["url"].lower()
    g = (e.get("group") or "").lower()
    if "cctv" in t or "cgtn" in t:
        return "cctv"
    if "卫视" in e["title"]:
        return "weishi"
    if not e["url"].startswith(("http://", "https://")):
        return "wrong_uri"
    if any(k in e["title"] for k in NOISE_KW) or any(k in u for k in NOISE_URL_KW):
        return "noise"
    if any(k in g for k in ["咪咕", "移动", "电信", "联通", "运营商", "广电", "魔百和", "百视通"]) \
            or any(k in e["title"] for k in ["咪咕", "移动", "电信", "联通", "魔百和", "百视通", "晴彩"]) \
            or any(k in u for k in ["itv.cmvideo.cn", "miguvideo.com", "bestv", "mgtv.com", "gslb", "chinamobile", "dxhmt"]):
        return "ott"
    if "直播" in e["title"] or "综合" in e["title"] or "频道" in e["title"]:
        return "local"
    return "local"


def summarize(files: list[str]) -> dict:
    cats = {"total": 0, "cctv": 0, "weishi": 0, "local": 0, "ott": 0, "noise": 0, "wrong_uri": 0}
    groups = {}
    for path in files:
        for e in parse_file(path):
            c = classify(e)
            cats[c] += 1
            cats["total"] += 1
            g = e["group"] or "?"
            groups[g] = groups.get(g, 0) + 1
    cats["groups"] = len(groups)
    return cats


def main():
    out = {}
    all_files = []
    for d in (SOURCES_DIR, DOWNLOADS_DIR):
        for fn in sorted(os.listdir(d)):
            if not fn.startswith("_") and os.path.isfile(os.path.join(d, fn)):
                all_files.append(os.path.join(d, fn))
    for path in all_files:
        fn = os.path.basename(path)
        out[fn] = summarize([path])
        c = out[fn]
        noise_pct = round(100 * c["noise"] / c["total"], 1) if c["total"] else 0
        print(f"{fn:32s} total={c['total']:6d} cctv={c['cctv']:4d} weishi={c['weishi']:4d} "
              f"local={c['local']:6d} noise={c['noise']:5d}({noise_pct}%) wrong={c['wrong_uri']:4d} groups={c['groups']}")
    with open(os.path.join(ROOT, "analysis.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
