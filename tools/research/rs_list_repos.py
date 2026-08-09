"""List GitHub repo contents to discover actual playlist paths."""
import json
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0"}


def get(url):
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except Exception as e:
        return {"err": str(e)[:140]}


TARGETS = [
    ("fanmingming", "live", "tv/m3u"),
    ("Meroser", "IPTV", ""),
    ("YueChan", "Live", ""),
    ("YanG-1989", "m3u", ""),
    ("qwerttvv", "Beijing-IPTV", ""),
    ("vbskycn", "iptv", "tv"),
    ("Tommy-70", "iptv", ""),
    ("HOTMOVE", "iptv", ""),
    ("supersuiee", "iptv", ""),
    ("BurningC4", "Chinese-IPTV", ""),
    ("suxuang", "myIPTV", ""),
    ("CCSH", "IPTV", ""),
    ("imDazui", "Tvlist-awesome-m3u-m3u8", ""),
    ("yamaya315", "iptv", ""),
    ("120001240", "IPTV", ""),
    ("HerbertHe", "iptv-sources", ""),
    ("guoweiok", "tv", ""),
    ("kimwang1978", "collect-tv-txt", ""),
    ("ngo5", "IPTV", ""),
    ("Free-TV", "IPTV", "playlists"),
]

out = {}
for owner, repo, path in TARGETS:
    data = get(f"https://api.github.com/repos/{owner}/{repo}/contents/{path}")
    if "err" in data:
        out[f"{owner}/{repo}/{path}"] = {"err": data["err"]}
        print(f"{owner}/{repo} {path}: ERR {data['err']}")
    elif isinstance(data, list):
        out[f"{owner}/{repo}/{path}"] = [x.get("name") for x in data]
        print(f"{owner}/{repo} {path}: {[x.get('name') for x in data][:40]}")
    else:
        out[f"{owner}/{repo}/{path}"] = str(data)[:200]
        print(f"{owner}/{repo} {path}: {str(data)[:200]}")
    time.sleep(0.4)

with open(r"D:\AI\agent\codex\android\tv\tools\research\repo_listing.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
