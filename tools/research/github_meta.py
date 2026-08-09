import json
import time
import urllib.request

UA = "Mozilla/5.0"
REPOS = [
    ("fanmingming", "live"), ("Guovin", "iptv-api"), ("YueChan", "Live"),
    ("Meroser", "IPTV"), ("vbskycn", "iptv"), ("zhmzjj310144", "migu-sports"),
    ("hououinkami", "AppleTV"), ("jk2024988", "TV2024"), ("best-fan", "iptv-sources"),
    ("BurningC4", "Chinese-IPTV"), ("qwerttvv", "Beijing-IPTV"), ("Tommy-70", "iptv"),
    ("HOTMOVE", "iptv"), ("supersuiee", "iptv"), ("iptv-org", "iptv"),
    ("Kimentanm", "aptv"), ("hujingguang", "ChinaIPTV"), ("vicjl", "myIPTV"),
    ("HerbertHe", "iptv-sources"), ("Yiov", "wo"), ("guoweiok", "tv"),
    ("islovezz", "iptv"), ("sx1978", "iptv"), ("kimwang1978", "collect-tv-txt"),
    ("wudongdefeng", "iptv"), ("zxing003", "iptv"), ("SilentDemonSD", "IPTV"),
    ("luongz", "iptv"), ("fenxp", "iptv"), ("Free-TV", "IPTV"),
    ("imldl", "iptv"), ("YanG-1989", "m3u"), ("yamaya315", "iptv"),
    ("jitongxp", "IPTV"), ("kovstrok", "IPTV"), ("wwwwg", "iptv"),
    ("sgsdxzy", "IPTV"), ("mitong", "iptv"), ("supzhang", "iptv"),
    ("120001240", "IPTV"), ("niuhuan", "iptv-sources"), ("czs6", "IPTV"),
    ("chikewang", "iptv"),
]


def fetch(owner, repo):
    url = f"https://api.github.com/repos/{owner}/{repo}"
    for attempt in (1, 2):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return {
                    "owner": owner,
                    "repo": repo,
                    "pushed_at": data.get("pushed_at"),
                    "updated_at": data.get("updated_at"),
                    "stars": data.get("stargazers_count"),
                    "branch": data.get("default_branch"),
                    "err": None,
                }
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"owner": owner, "repo": repo, "pushed_at": None,
                        "updated_at": None, "stars": None, "branch": None,
                        "err": "404 not found"}
            if e.code == 403 and attempt == 1:
                print(f"[rate limit] {owner}/{repo}, waiting 30s...", flush=True)
                time.sleep(30)
                continue
            return {"owner": owner, "repo": repo, "pushed_at": None,
                    "updated_at": None, "stars": None, "branch": None,
                    "err": f"HTTP {e.code}"}
        except Exception as e:
            return {"owner": owner, "repo": repo, "pushed_at": None,
                    "updated_at": None, "stars": None, "branch": None,
                    "err": str(e)}


results = [fetch(o, r) for o, r in REPOS]
with open(r"D:\AI\agent\codex\android\tv\tools\research\github_meta.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(json.dumps(results, ensure_ascii=False, indent=2))
