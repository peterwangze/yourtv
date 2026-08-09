"""Discover playlist file paths via jsdelivr data API (no GitHub rate limit)."""
import json
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0"}

REPOS = [
    ("Meroser", "IPTV", "main"),
    ("YueChan", "Live", "main"),
    ("YanG-1989", "m3u", "main"),
    ("qwerttvv", "Beijing-IPTV", "master"),
    ("BurningC4", "Chinese-IPTV", "master"),
    ("kimwang1978", "collect-tv-txt", "main"),
    ("suxuang", "myIPTV", "main"),
    ("CCSH", "IPTV", "master"),
    ("imDazui", "Tvlist-awesome-m3u-m3u8", "master"),
    ("ngo5", "IPTV", "main"),
    ("HerbertHe", "iptv-sources", "main"),
    ("Guovin", "iptv-api", "master"),
    ("zhmzjj310144", "migu-sports", "main"),
    ("jk2024988", "TV2024", "main"),
    ("hououinkami", "AppleTV", "main"),
    ("hujingguang", "ChinaIPTV", "main"),
    ("Free-TV", "IPTV", "master"),
    ("Kimentanm", "aptv", "master"),
]


def list_files(owner, repo, ref):
    url = f"https://data.jsdelivr.com/v1/packages/gh/{owner}/{repo}@{ref}?structure=flat"
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=25) as r:
            data = json.load(r)
        return [f["name"] for f in data.get("files", [])]
    except Exception as e:
        return {"err": str(e)[:140]}


out = {}
for owner, repo, ref in REPOS:
    files = list_files(owner, repo, ref)
    if isinstance(files, dict):
        print(f"{owner}/{repo}@{ref}: ERR {files['err']}")
        out[f"{owner}/{repo}"] = files
    else:
        interesting = [f for f in files if f.lower().endswith((".m3u", ".m3u8", ".txt"))
                       and not any(x in f.lower() for x in ["readme", "license", ".git"])]
        print(f"{owner}/{repo}@{ref}: {len(files)} files; playlists: {interesting[:60]}")
        out[f"{owner}/{repo}"] = interesting[:60]
    time.sleep(0.5)

with open(r"D:\AI\agent\codex\android\tv\tools\research\jsdelivr_listing.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
