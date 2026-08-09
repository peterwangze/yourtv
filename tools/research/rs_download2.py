"""Second round: download candidates with confirmed file paths."""
import json
import os
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"}
OUT = os.path.join(os.path.dirname(__file__), "downloads")
os.makedirs(OUT, exist_ok=True)

CANDIDATES = [
    ("yuechan_cutv", ["https://raw.githubusercontent.com/YueChan/Live/main/CUTV.txt"]),
    ("yuechan_gntv", ["https://raw.githubusercontent.com/YueChan/Live/main/GNTV.m3u"]),
    ("burningc4_tvipv4", ["https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u"]),
    ("suxuang_ipv4", ["https://raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u"]),
    ("suxuang_migu", ["https://raw.githubusercontent.com/suxuang/myIPTV/main/%E5%92%AA%E5%92%95%E7%9B%B4%E6%92%AD.txt"]),
    ("suxuang_yd", ["https://raw.githubusercontent.com/suxuang/myIPTV/main/%E7%A7%BB%E5%8A%A8IPTV.m3u"]),
    ("suxuang_gd", ["https://raw.githubusercontent.com/suxuang/myIPTV/main/%E5%B9%BF%E4%B8%9C%E7%94%B5%E4%BF%A1.txt"]),
    ("migu_tv", ["https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/migu_tv.m3u"]),
    ("migu_cctv", ["https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/%E5%A4%AE%E8%A7%86%E7%BB%BC%E5%90%88%E6%BA%90.m3u"]),
    ("hououinkami_gather", ["https://raw.githubusercontent.com/hououinkami/AppleTV/main/Source/Gather.m3u"]),
    ("hououinkami_v6", ["https://raw.githubusercontent.com/hououinkami/AppleTV/main/Source/China_v6.m3u"]),
    ("hujingguang_guonei", ["https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV2_GuoNei.m3u8"]),
    ("freetv_china", ["https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_china.m3u8"]),
    ("aptv_bjyd", ["https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/bjyd.m3u"]),
    ("aptv_jsyd", ["https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/jsyd.m3u"]),
    ("aptv_zjyd", ["https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/zjyd.m3u"]),
    ("aptv_hnyd", ["https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/hnyd.m3u"]),
    ("yang1989_gmcc", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/GMCC/IPTV.m3u",
                       "https://raw.githubusercontent.com/YanG-1989/m3u/main/ChinaMobile.m3u",
                       "https://raw.githubusercontent.com/YanG-1989/m3u/main/GMCC/ChinaMobile.m3u"]),
    ("yang1989_dxhmt", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/DXHMT/IPTV.m3u",
                        "https://raw.githubusercontent.com/YanG-1989/m3u/main/DXHMT/ChinaTelecom.m3u",
                        "https://raw.githubusercontent.com/YanG-1989/m3u/main/ChinaTelecom.m3u"]),
    ("yang1989_lt", ["https://raw.githubusercontent.com/YanG-1989/m3u/main/DXLT/IPTV.m3u",
                     "https://raw.githubusercontent.com/YanG-1989/m3u/main/ChinaUnicom.m3u",
                     "https://raw.githubusercontent.com/YanG-1989/m3u/main/YD/IPTV.m3u"]),
    ("qwerttvv_iptv", ["https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/IPTV.m3u8",
                       "https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/IPTV.m3u",
                       "https://raw.githubusercontent.com/qwerttvv/Beijing-IPTV/master/m3u/IPTV.m3u"]),
    ("kimwang1978", ["https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/IPTV.m3u",
                     "https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/tv.m3u",
                     "https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/%E7%9B%B4%E6%92%AD.txt",
                     "https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/%E5%BD%B1%E8%A7%86.txt"]),
    ("ccsh_iptv", ["https://raw.githubusercontent.com/CCSH/IPTV/master/m3u/iptv.m3u",
                   "https://raw.githubusercontent.com/CCSH/IPTV/master/IPTV.m3u",
                   "https://raw.githubusercontent.com/CCSH/IPTV/master/tv.m3u"]),
    ("meroser_demo", ["https://raw.githubusercontent.com/Meroser/IPTV/main/IPTV-demo.m3u"]),
    ("fanmingming_com_ipv6", ["https://live.fanmingming.com/tv/m3u/ipv6.m3u"]),
]


def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers=UA)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            return data, (time.time() - t0) * 1000, ""
    except Exception as e:
        return None, (time.time() - t0) * 1000, str(e)[:160]


def main():
    results = []
    for label, urls in CANDIDATES:
        data = ms = None
        errs = []
        ok_url = None
        for u in urls:
            data, ms, err = fetch(u)
            if data is not None and len(data) > 0:
                ok_url = u
                break
            errs.append(err)
        if ok_url is None:
            results.append({"label": label, "ok": False, "errs": errs[:2]})
            print(f"[FAIL] {label}: {errs[0] if errs else 'no data'}")
            continue
        ext = ok_url.rsplit(".", 1)[-1].lower()
        ext = ext if ext in ("m3u", "m3u8", "txt") else "bin"
        path = os.path.join(OUT, f"{label}.{ext}")
        with open(path, "wb") as f:
            f.write(data)
        results.append({"label": label, "ok": True, "url": ok_url, "size": len(data), "ms": round(ms)})
        print(f"[OK] {label}: {len(data)} B, {ms:.0f} ms <- {ok_url}")
    with open(os.path.join(OUT, "_result2.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
