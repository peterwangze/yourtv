"""Merge ffprobe audit files into offline dimensions; never ship host speed/health."""
import argparse
import datetime
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audits', nargs='+')
    parser.add_argument('--output', default='app/src/main/assets/bundled_quality.json')
    args = parser.parse_args()
    audits = sorted((json.loads(Path(p).read_text(encoding='utf8')) for p in args.audits),
                    key=lambda a: a['measuredAt'])
    resolutions = {}
    for audit in audits:
        for line in audit['lines']:
            video = line.get('video') or {}
            if video.get('width', 0) > 0 and video.get('height', 0) > 0:
                resolutions[line['url']] = f"{video['width']}x{video['height']}"
    # Earliest observation bounds the lifetime of every merged measurement.
    measured = datetime.datetime.fromisoformat(audits[0]['measuredAt'])
    output = {'measuredAtMs': int(measured.timestamp() * 1000),
              'note': 'Observed video dimensions only. Revalidate during playback; expire after 30 days.',
              'resolutions': dict(sorted(resolutions.items()))}
    Path(args.output).write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print(f'{len(resolutions)} measured endpoints written to {args.output}')


if __name__ == '__main__':
    main()
