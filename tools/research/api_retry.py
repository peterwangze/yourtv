import json
import time
import urllib.request

UA = "Mozilla/5.0"
TARGETS = [
    ("HerbertHe", "iptv-sources"),
    ("czs6", "IPTV"),
    ("chikewang", "iptv"),
]


def get_rate():
    req = urllib.request.Request("https://api.github.com/rate_limit", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode())
    core = data["resources"]["core"]
    return core["remaining"], core["reset"]


def fetch(owner, repo):
    url = f"https://api.github.com/repos/{owner}/{repo}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode())
            return {"owner": owner, "repo": repo,
                    "pushed_at": data.get("pushed_at"),
                    "updated_at": data.get("updated_at"),
                    "stars": data.get("stargazers_count"),
                    "branch": data.get("default_branch"),
                    "err": None}
    except urllib.error.HTTPError as e:
        return {"owner": owner, "repo": repo, "pushed_at": None,
                "updated_at": None, "stars": None, "branch": None,
                "err": f"HTTP {e.code}"}
    except Exception as e:
        return {"owner": owner, "repo": repo, "pushed_at": None,
                "updated_at": None, "stars": None, "branch": None,
                "err": str(e)}


# wait for rate limit reset
for _ in range(240):
    try:
        remaining, reset = get_rate()
        now = int(time.time())
        print(f"remaining={remaining} reset_in={max(0, reset - now)}s", flush=True)
        if remaining >= 3:
            break
    except Exception as e:
        print("rate check err:", e, flush=True)
    time.sleep(30)

out = []
for owner, repo in TARGETS:
    r = fetch(owner, repo)
    print(r, flush=True)
    out.append(r)

path = r"D:\AI\agent\codex\android\tv\tools\research\github_meta_retry.json"
with open(path, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print("WROTE", path)
