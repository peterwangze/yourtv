import urllib.request

UA = "Mozilla/5.0"


def probe(url, head=400):
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": UA,
            "Range": "bytes=0-0",
        })
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read(head)
            print(f"OK   {resp.status} len={resp.headers.get('Content-Length')} {url}")
            print(f"     head: {body[:120]!r}")
            return True
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} {url}")
        return False
    except Exception as e:
        print(f"ERR  {type(e).__name__} {e} {url}")
        return False


# vbskycn candidate paths (raw, no API cost)
print("== vbskycn/iptv raw candidates ==")
probe("https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u")
probe("https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.txt")
probe("https://raw.githubusercontent.com/vbskycn/iptv/master/iptv4.m3u")
probe("https://raw.githubusercontent.com/vbskycn/iptv/main/tv/iptv4.m3u")
probe("https://raw.githubusercontent.com/vbskycn/iptv/master/README.md")

# content check of suspicious mirrors
print("== suspicious mirror content ==")
probe("https://ghproxy.cn/https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u", 600)
probe("https://gh.con.sh/https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u", 600)

# rate limit status
print("== rate limit ==")
try:
    req = urllib.request.Request("https://api.github.com/rate_limit", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=15) as resp:
        import json
        data = json.loads(resp.read().decode())
        core = data["resources"]["core"]
        print("remaining:", core["remaining"], "reset:", core["reset"])
except Exception as e:
    print("rate_limit check failed:", e)
