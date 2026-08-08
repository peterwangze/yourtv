"""Verify IPTV stream URLs are alive: fetch playlist and confirm it contains segments."""
import re
import sys
import urllib.request
import ssl
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = "VLC/3.0.18 LibVLC/3.0.18"


def check(url: str, timeout: int = 10):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-0"})
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            data = r.read(200000)
            code = r.status
        text = data.decode("utf-8", errors="replace")
        if "#EXTM3U" in text:
            has_stream_inf = "#EXT-X-STREAM-INF" in text
            segments = len(re.findall(r"#EXTINF", text))
            return f"PLAYLIST code={code} streaminf={has_stream_inf} segs={segments}"
        if data:
            return f"DATA code={code} bytes={len(data)}"
        return f"EMPTY code={code}"
    except Exception as e:
        return f"ERR {type(e).__name__}: {str(e)[:80]}"


def main():
    urls = sys.argv[1:]
    with ThreadPoolExecutor(max_workers=10) as ex:
        futures = [ex.submit(check, u) for u in urls]
        for f, u in zip(as_completed(futures), urls):
            print(f"{u}\n    -> {f.result()}")


if __name__ == "__main__":
    main()
