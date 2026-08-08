"""Dump 4K/UHD/Migu entries from a source list (m3u or txt)."""
import re
import sys
import urllib.request
import ssl
from probe_sources import fetch, parse_entries


def main():
    urls = sys.argv[1:]
    for u in urls:
        data = fetch(u)
        if data is None:
            print(f"FAIL {u}")
            continue
        text = data.decode("utf-8", errors="replace")
        entries = parse_entries(text)
        print(f"== {u} ({len(entries)} channels)")
        for title, url in entries:
            if re.search(r"4k|2160|uhd|migu", url, re.I) or re.search(r"4k|2160|uhd|咪咕", title, re.I):
                print(f"   {title} | {url[:160]}")


if __name__ == "__main__":
    main()
