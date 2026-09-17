"""Bounded, developer-triggered media audit. Never runs during app playback.

ffprobe reads actual video headers, not the playlist title or HTTP status.
Results describe only the machine/network on which this command was run.
"""
import argparse
import concurrent.futures
import datetime
import json
import subprocess
import time
from pathlib import Path
from urllib.parse import urlsplit


def audit(item, ffprobe):
    title, url = item
    start = time.monotonic()
    result = {"title": title, "url": url, "host": urlsplit(url).hostname}
    try:
        run = subprocess.run([ffprobe, '-v', 'error', '-rw_timeout', '7000000',
            '-analyzeduration', '2500000', '-probesize', '1500000',
            '-show_entries', 'stream=codec_type,codec_name,width,height,bit_rate,field_order,avg_frame_rate',
            '-of', 'json', url], capture_output=True, timeout=18)
        data = json.loads(run.stdout or b'{}')
        video = next((s for s in data.get('streams', []) if s.get('codec_type') == 'video'), None)
        result.update({"video": video, "verified1080": bool(video and
            video.get('width', 0) >= 1920 and video.get('height', 0) >= 1080),
            "status": "video" if video else "unavailable"})
    except (subprocess.TimeoutExpired, ValueError, OSError):
        result.update(status='timeout_or_invalid', verified1080=False)
    result['inspectionMs'] = round((time.monotonic() - start) * 1000)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--catalog', default='app/src/main/assets/bundled_channels.json')
    p.add_argument('--titles', default='CCTV1,CCTV2,四川卫视,湖南卫视,浙江卫视')
    p.add_argument('--ffprobe', default='ffprobe')
    p.add_argument('--limit', type=int, default=40)
    p.add_argument('--per-channel', type=int, default=8)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    titles = set(args.titles.split(','))
    channels = json.loads(Path(args.catalog).read_text(encoding='utf8'))
    items = list(dict.fromkeys((c['title'], url) for c in channels
        if c['title'] in titles for url in c['uris'][:args.per_channel]))[:max(0, min(args.limit, 80))]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda item: audit(item, args.ffprobe), items))
    output = {"measuredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "note": "Current host network only; inspection time is not first-frame time or throughput.",
              "lines": results}
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf8')
    for r in results:
        print(r['title'], r['host'], r['status'], r.get('video'), flush=True)


if __name__ == '__main__':
    main()
