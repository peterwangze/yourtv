"""Read a Git revision; write only this archive's evidence files. No network I/O.

Run from the repository root: python docs/architecture/2026-09-08/audit_snapshot.py
The inventory records coverage, not a claim that every file has a defect.
"""
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit
import csv
import hashlib
import io
import json
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent / "evidence"
REVISION = "1b47d7446074b9bf26931e8ed0945c6a6630723d"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def domain(path):
    if path.startswith("app/src/test/"):
        return "tests"
    if path.startswith("app/src/main/java/com/horsenma/mytv1/"):
        return "legacy-web"
    if path.startswith("app/src/main/java/"):
        return "main-kotlin"
    if path.startswith("app/src/main/res/"):
        return "resources"
    if path.startswith("app/src/main/assets/"):
        return "assets"
    if path.startswith("tools/"):
        return "research-tools-data"
    if path.startswith("docs/") or path.startswith("README"):
        return "historical-docs"
    return "build-release-other"


def main():
    revision = git("rev-parse", REVISION).decode().strip()
    archive = tarfile.open(fileobj=io.BytesIO(git("archive", revision)))
    files = {m.name: archive.extractfile(m).read() for m in archive if m.isfile()}
    rows = []
    for path, data in sorted(files.items()):
        try:
            text = data.decode("utf-8-sig")
            is_text = "\x00" not in text
        except UnicodeDecodeError:
            text, is_text = "", False
        rows.append({
            "path": path, "domain": domain(path), "bytes": len(data),
            "lines": len(text.splitlines()) if is_text else "",
            "kind": "text-static-inventory" if is_text else "binary-metadata-only",
            "scan_signals": ";".join(name for name, pattern in {
                "network": r"https?://|HttpClient|openConnection|fetch\(",
                "async-lifecycle": r"CoroutineScope|viewModelScope|lifecycleScope|postDelayed|onDestroy|onPause",
                "focus-input": r"requestFocus|KEYCODE_|nextFocus|setOnKeyListener",
                "mutable-state": r"MutableLiveData|SharedPreferences|SP\.|writeText|writeFile",
                "media": r"ExoPlayer|MediaSource|WebView|BUFFERING|video\.play",
            }.items() if is_text and re.search(pattern, text)),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    bundled_path = "app/src/main/assets/bundled_channels.json"
    channels = json.loads(files[bundled_path])
    urls = [u for c in channels for u in c.get("uris", [])]
    hosts = Counter(urlsplit(u).hostname for u in urls)
    first_hosts = Counter(urlsplit(c["uris"][0]).hostname for c in channels if c.get("uris"))
    sources = Counter(s for c in channels for u, s in c.get("uriSources", {}).items() if u in c["uris"])
    def source_name(s):
        parsed = urlsplit(s)
        if parsed.hostname == "raw.githubusercontent.com":
            return "/".join(parsed.path.strip("/").split("/")[:2])
        return parsed.hostname or s or "unknown"
    source_counts = Counter()
    for s, n in sources.items():
        source_counts[source_name(s)] += n
    sp = files["app/src/main/java/com/horsenma/yourtv/SP.kt"].decode()
    defaults = json.loads(re.search(r'var DEFAULT_SOURCES = """(.*?)"""', sp, re.S).group(1))
    data = {
        "baseline": revision,
        "method": "Static Git blob counts; no current reachability, playback, ISP or regional claim.",
        "tracked_files": len(rows),
        "domain_files": dict(sorted(Counter(r["domain"] for r in rows).items())),
        "main_kotlin_files": sum(p.startswith("app/src/main/") and p.endswith(".kt") for p in files),
        "main_kotlin_lines": sum(r["lines"] for r in rows if r["path"].startswith("app/src/main/") and r["path"].endswith(".kt")),
        "layouts": sum(p.startswith("app/src/main/res/layout/") for p in files),
        "test_files": sum(p.startswith("app/src/test/") and p.endswith(".kt") for p in files),
        "default_lists": len(defaults),
        "default_list_hosts": dict(Counter(urlsplit(s["uri"]).hostname for s in defaults)),
        "bundled": {
            "sha256": hashlib.sha256(files[bundled_path]).hexdigest(),
            "bytes": len(files[bundled_path]), "channels": len(channels),
            "line_slots": len(urls), "unique_urls": len(set(urls)),
            "line_count_histogram": dict(sorted(Counter(len(c.get("uris", [])) for c in channels).items())),
            "single_host_channels": sum(len({urlsplit(u).hostname for u in c.get("uris", [])}) == 1 for c in channels),
            "schemes": dict(Counter(urlsplit(u).scheme for u in urls)),
            "ipv6_literal_slots": sum(":" in (urlsplit(u).hostname or "") for u in urls),
            "channels_with_uri_headers": sum(bool(c.get("uriHeaders")) for c in channels),
            "channels_with_channel_headers": sum(bool(c.get("headers")) for c in channels),
            "top_hosts": hosts.most_common(10), "top_first_hosts": first_hosts.most_common(10),
            "source_attribution_slots": source_counts.most_common(),
            "sample_channel_line_counts": {c["title"]: len(c["uris"]) for c in channels if c["title"] in ["CCTV1", "CCTV5", "CCTV13", "湖南卫视", "浙江卫视", "东方卫视", "北京卫视"]},
        },
        "bundler_input_in_revision": {p: p in files for p in [
            "tools/research/channels.json", "tools/research/classified.json",
            "tools/research/probe_lines_results.json"]},
    }
    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "inventory.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    (OUT / "snapshot.json").write_bytes((json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(json.dumps(data, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
