"""Sample one completed HLS segment per verified HD channel, on this host only.

No app background traffic. At most 4 MiB, a 15-second read-loop budget and an
8-second socket timeout (a pending read can outlast that loop budget). This is a
short network sample, not a guarantee of sustained playback or carrier coverage.
"""
import argparse
import json
import time
from pathlib import Path
from urllib.parse import urljoin
from urllib.request import Request, build_opener, ProxyHandler


def measure(url):
    opener = build_opener(ProxyHandler({}))
    for _ in range(3):
        with opener.open(Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=8) as response:
            url = response.url
            manifest = response.read(256 * 1024).decode('utf8')
        lines = manifest.splitlines()
        variants = []
        duration = None
        segments = []
        for index, line in enumerate(lines):
            if line.startswith('#EXT-X-STREAM-INF:') and index + 1 < len(lines):
                variants.append(urljoin(url, lines[index + 1].strip()))
            if line.startswith('#EXTINF:'):
                duration = float(line.split(':')[1].split(',')[0])
            elif line and not line.startswith('#') and not variants:
                segments.append((urljoin(url, line.strip()), duration))
        if variants:
            url = variants[0]
            continue
        if not segments:
            raise ValueError('No completed media segment')
        segment, duration = segments[-2 if len(segments) > 1 else -1]
        begin = time.monotonic()
        count = 0
        complete = False
        with opener.open(Request(segment, headers={'User-Agent': 'Mozilla/5.0'}), timeout=8) as response:
            while count < 4 * 1024 * 1024 and time.monotonic() - begin < 15:
                block = response.read(min(65536, 4 * 1024 * 1024 - count))
                if not block:
                    complete = True
                    break
                count += len(block)
            content_length = response.headers.get('Content-Length')
            complete = complete or (content_length is not None and count == int(content_length))
        elapsed = time.monotonic() - begin
        return {'bytes': count, 'downloadMs': round(elapsed * 1000),
                'downloadMbps': round(count * 8 / elapsed / 1e6, 2),
                'segmentSeconds': duration, 'complete': complete,
                'mediaMbps': round(count * 8 / duration / 1e6, 2) if complete and duration else None}
    raise ValueError('Too many master playlists')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('audit')
    p.add_argument('--output', required=True)
    args = p.parse_args()
    entries = json.loads(Path(args.audit).read_text(encoding='utf8'))['lines']
    results = []
    titles = set()
    for line in entries:
        if not line.get('verified1080') or line['title'] in titles:
            continue
        titles.add(line['title'])
        result = {'title': line['title'], 'url': line['url']}
        try:
            result.update(measure(line['url']))
        except Exception as error:
            result['error'] = str(error)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    Path(args.output).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf8')


if __name__ == '__main__':
    main()
