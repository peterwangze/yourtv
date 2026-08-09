"""Download candidate IPTV source lists into tools/research/downloads/.

Usage: python tools/research/rs_download.py
Writes tools/research/downloads/_result.json with per-URL status.
"""
import json
import os
import ssl
import time
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
OUT = os.path.join(os.path.dirname(__file__), "downloads")
os.makedirs(OUT, exist_ok=True)

# (label, [url candidates tried in order])
CANDIDATES = [
    ("herberthe_iptv", ["https://raw.githubusercontent.com/HerbertHe/iptv-sources/main/iptv.m3u"]),
    ("herberthe_national", ["https://raw.githubusercontent.com/HerbertHe/iptv-sources/main/national.m3u"]),
    ("yuechan_aptv", ["https://raw.githubusercontent.com/YueChan/Live/main/APTV.m3u"]),
    ("yuechan_iptv", ["https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u"]),
    ("yuechan_iptv6", ["https://raw.githubusercontent.com/YueChan/Live/main/IPTV6.m3u"]),
    ("iill_gather", ["https://tv.iill.top/m3u/Gather"]),
    ("iill_cn", ["https://tv.iill.top/m3u/CN"]),
    ("yiov_iptv", ["https://raw.githubusercontent.com/Yiov/wo/main/IPTV.m3u"]),
    ("yiov_tv", ["https://raw.githubusercontent.com/Yiov/wo/main/TV.m3u"]),
    ("guoweiok_tv", ["https://raw.githubusercontent.com/guoweiok/tv/main/tv.txt"]),
    ("islovezz_tv", ["https://raw.githubusercontent.com/islovezz/iptv/main/tv.m3u"]),
    ("sx1978_iptv", ["https://raw.githubusercontent.com/sx1978/iptv/main/iptv.m3u"]),
    ("fanmingming_ipv4_raw", ["https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv4.m3u"]),
    ("fanmingming_index_raw", ["https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/index.m3u"]),
    ("kimwang1978_txt", ["https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/%E6%9C%80%E6%96%B0%E7%89%88.txt"]),
    ("wudongdefeng_iptv", ["https://raw.githubusercontent.com/wudongdefeng/iptv/main/iptv.m3u"]),
    ("zxing003_cctv", ["https://raw.githubusercontent.com/zxing003/iptv/main/cctv.m3u"]),
    ("meroser_iptv", ["https://raw.githubusercontent.com/Meroser/IPTV/main/IPTV.m3u"]),
    ("meroser_iptv6", ["https://raw.githubusercontent.com/Meroser/IPTV/main/IPTV-IPV6.m3u"]),
    ("silentdemsd_iptv", ["https://raw.githubusercontent.com/SilentDemonSD/IPTV/main/iptv.m3u"]),
    ("luongz_iptv", ["https://raw.githubusercontent.com/luongz/iptv/main/iptv.m3u"]),
    ("freetv_playlist", ["https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist.m3u8"]),
    ("fenxp_live", ["https://raw.githubusercontent.com/fenxp/iptv/main/live.m3u"]),
    ("suxuang_myiptv", ["https://raw.githubusercontent.com/suxuang/myIPTV/main/IPTV.m3u",
                        "https://raw.githubusercontent.com/suxuang/myIPTV/master/IPTV.m3u"]),
    ("ccsh_iptv", ["https://raw.githubusercontent.com/CCSH/IPTV/master/iptv.m3u",
                   "https://raw.githubusercontent.com/CCSH/IPTV/main/iptv.m3u"]),
    ("imdazui_tvlist", ["https://raw.githubusercontent.com/imDazui/Tvlist-awesome-m3u-m3u8/master/IPTV.m3u",
                        "https://raw.githubusercontent.com/imDazui/Tvlist-awesome-m3u-m3u8/main/IPTV.m3u"]),
    ("yang1989_m3u", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/IPTV.m3u",
                      "https://raw.githubusercontent.com/YanG-1989/m3u/master/IPTV.m3u"]),
    ("yang1989_gmcc", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/GMCC/IPTV.m3u",
                       "https://raw.githubusercontent.com/YanG-1989/m3u/main/ChinaMobile.m3u",
                       "https://raw.githubusercontent.com/YanG-1989/m3u/master/GMCC/IPTV.m3u"]),
    ("yang1989_dxhmt", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/DXHMT/IPTV.m3u",
                        "https://raw.githubusercontent.com/YanG-1989/m3u/master/DXHMT/IPTV.m3u"]),
    ("yamaya315_iptv", ["https://raw.githubusercontent.com/yamaya315/iptv/master/live.m3u",
                        "https://raw.githubusercontent.com/yamaya315/iptv/main/live.m3u"]),
    ("120001240_iptv", ["https://raw.githubusercontent.com/120001240/IPTV/main/IPTV.m3u",
                        "https://raw.githubusercontent.com/120001240/IPTV/master/IPTV.m3u"]),
    ("qwerttvv_beijing", ["https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/IPTV.m3u",
                          "https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/main/IPTV.m3u"]),
    ("tommy70_iptv", ["https://raw.githubusercontent.com/Tommy-70/iptv/master/tv.m3u",
                      "https://raw.githubusercontent.com/Tommy-70/iptv/main/tv.m3u"]),
    ("hotmove_iptv", ["https://raw.githubusercontent.com/HOTMOVE/iptv/master/iptv.m3u",
                      "https://raw.githubusercontent.com/HOTMOVE/iptv/main/iptv.m3u"]),
    ("supersuiee_iptv", ["https://raw.githubusercontent.com/supersuiee/iptv/master/iptv.m3u",
                         "https://raw.githubusercontent.com/supersuiee/iptv/main/iptv.m3u"]),
    ("burningc4_cctv", ["https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/CCTV.m3u",
                        "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/main/CCTV.m3u"]),
    ("anguszh_meroser", ["https://raw.githubusercontent.com/anguszh/Meroser-IPTV/main/IPTV.m3u",
                         "https://raw.githubusercontent.com/anguszh/Meroser-IPTV/master/IPTV.m3u"]),
    ("ng05_iptv", ["https://raw.githubusercontent.com/ngo5/IPTV/main/IPTV.m3u",
                   "https://raw.githubusercontent.com/ngo5/IPTV/master/IPTV.m3u"]),
    ("fanmingming_com_ipv4", ["https://live.fanmingming.com/tv/m3u/ipv4.m3u"]),
    ("fanmingming_com_index", ["https://live.fanmingming.com/tv/m3u/index.m3u"]),
    ("fanmingming_cn_index", ["https://live.fanmingming.cn/tv/m3u/index.m3u"]),
    ("fanmingming_cn_ipv4", ["https://live.fanmingming.cn/tv/m3u/ipv4.m3u"]),
]


def fetch(url: str, timeout: int = 25) -> tuple[bytes | None, float, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    ctx = ssl.create_default_context()
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            data = r.read()
            return data, (time.time() - t0) * 1000, ""
    except Exception as e:
        return None, (time.time() - t0) * 1000, str(e)[:200]


def main():
    results = []
    for label, urls in CANDIDATES:
        ok_url = None
        data = None
        ms = 0.0
        errs = []
        for u in urls:
            data, ms, err = fetch(u)
            if data is not None and len(data) > 0:
                ok_url = u
                break
            errs.append(f"{u} -> {err}")
        if ok_url is None:
            results.append({"label": label, "ok": False, "errs": errs[:2]})
            print(f"[FAIL] {label}: {errs[0] if errs else 'no data'}")
            continue
        fname = f"{label}.{ok_url.rsplit('.', 1)[-1] if '.' in ok_url.rsplit('/', 1)[-1] else 'bin'}"
        if fname.endswith((".m3u8", ".m3u", ".txt")):
            pass
        else:
            fname = f"{label}.bin"
        path = os.path.join(OUT, fname)
        with open(path, "wb") as f:
            f.write(data)
        results.append({"label": label, "ok": True, "url": ok_url, "size": len(data), "ms": round(ms)})
        print(f"[OK] {label}: {len(data)} bytes, {ms:.0f} ms <- {ok_url}")
    with open(os.path.join(OUT, "_result.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
