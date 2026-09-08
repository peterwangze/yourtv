"""Verify IPTV stream URLs are alive: fetch playlist and confirm it contains segments."""
import re
import sys
import urllib.request
from urllib.parse import urljoin
import ssl
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = "VLC/3.0.18 LibVLC/3.0.18"


def check(url: str, timeout: int = 10):
    def fetch(target: str, byte_range: str = "bytes=0-199999"):
        req = urllib.request.Request(target, headers={"User-Agent": UA, "Range": byte_range})
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as response:
            return response.status, response.read(200000)

    try:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        code, data = fetch(url)
        text = data.decode("utf-8", errors="replace")
        if "#EXTM3U" in text:
            has_stream_inf = "#EXT-X-STREAM-INF" in text
            segments = len(re.findall(r"#EXTINF", text))
            media_lines = [line.strip() for line in text.splitlines()
                           if line.strip() and not line.lstrip().startswith("#")]
            media_base_url = url
            if has_stream_inf and media_lines:
                child_url = urljoin(url, media_lines[0])
                _, child_data = fetch(child_url)
                child_text = child_data.decode("utf-8", errors="replace")
                media_lines = [line.strip() for line in child_text.splitlines()
                               if line.strip() and not line.lstrip().startswith("#")]
                media_base_url = child_url
            if media_lines:
                segment_url = urljoin(media_base_url, media_lines[0])
                _, segment_data = fetch(segment_url, "bytes=0-4095")
                return (f"PLAYLIST code={code} streaminf={has_stream_inf} segs={segments} "
                        f"segment_bytes={len(segment_data)}")
            return f"PLAYLIST code={code} streaminf={has_stream_inf} segs={segments} no_segment"
        if data:
            return f"DATA code={code} bytes={len(data)}"
        return f"EMPTY code={code}"
    except Exception as e:
        return f"ERR {type(e).__name__}: {str(e)[:80]}"


def main():
    urls = sys.argv[1:]
    with ThreadPoolExecutor(max_workers=10) as ex:
        futures = {ex.submit(check, u): u for u in urls}
        for f in as_completed(futures):
            u = futures[f]
            print(f"{u}\n    -> {f.result()}")


if __name__ == "__main__":
    main()
