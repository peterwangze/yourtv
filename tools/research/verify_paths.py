import json
import time
import urllib.request

UA = "Mozilla/5.0"


def get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read()


# 1) jsdelivr data API: confirm exact paths in both repos
print("== jsdelivr data API ==")
for pkg in ("gh/fanmingming/live@main", "gh/vbskycn/iptv@master"):
    try:
        st, body = get(f"https://data.jsdelivr.com/v1/packages/{pkg}")
        data = json.loads(body)
        files = [f["name"] for f in data.get("files", [])]
        hits = [f for f in files if "ipv4" in f.lower() or "m3u" in f.lower()]
        print(pkg, "status", st, "total files", len(files))
        for h in hits[:20]:
            print("   ", h)
    except Exception as e:
        print(pkg, "ERR", e)

# 2) github.com raw redirect path (different CDN)
print("== github.com /raw/ redirect ==")
try:
    st, body = get("https://github.com/fanmingming/live/raw/refs/heads/main/tv/m3u/ipv4.m3u", timeout=15)
    print("status", st, "bytes", len(body), "head", body[:30])
except Exception as e:
    print("ERR", e)

# 3) raw.githubusercontent.com flakiness gauge for base1: 6 attempts, 1s apart
print("== raw.githubusercontent flakiness (base1 x6) ==")
ok = 0
for i in range(6):
    try:
        req = urllib.request.Request(
            "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u",
            headers={"User-Agent": UA, "Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            ok += 1
            print(f"  try{i+1}: {resp.status} len={resp.headers.get('Content-Length')}")
    except urllib.error.HTTPError as e:
        print(f"  try{i+1}: HTTP {e.code}")
    except Exception as e:
        print(f"  try{i+1}: {type(e).__name__} {e}")
    time.sleep(1)
print("ok", ok, "/6")

# 4) flakiness gauge for base2 (vbskycn) x3
print("== raw.githubusercontent flakiness (base2 x3) ==")
ok = 0
for i in range(3):
    try:
        req = urllib.request.Request(
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
            headers={"User-Agent": UA, "Range": "bytes=0-0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            ok += 1
            print(f"  try{i+1}: {resp.status} len={resp.headers.get('Content-Length')}")
    except urllib.error.HTTPError as e:
        print(f"  try{i+1}: HTTP {e.code}")
    except Exception as e:
        print(f"  try{i+1}: {type(e).__name__} {e}")
    time.sleep(1)
print("ok", ok, "/3")
