#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成聚源TV 内置预载快照 app/src/main/assets/bundled_channels.json。

数据源（tools/research/，由调研脚本产出）：
  channels.json              全部源的解析结果（title/group/url/source）
  classified.json            真实 ChannelClassifier 分类结果（category/region/mergeKey/noise/quality/...）
  probe_lines_results.json   全量线路活性探测（url -> alive/latency_ms）

策略：
  1. 过滤噪音条目（与 App 解析管线一致：noise=true 丢弃）
  2. 按 mergeKey 合并频道，线路去重
  3. 线路排序：存活优先 > 实测清晰度 > 源质量分层 > 标称清晰度 > 延迟
  4. 每频道线路数上限 10（全部死线时保留 6 条给其他网络兜底）
  5. 频道排序：央视 > 卫视 > 地方(省份序) > 海外(国家序) > 其他

用法：python tools/build_bundled.py
"""
import json
import ipaddress
import os
import re
import sys
import time
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESEARCH = os.path.join(ROOT, "tools", "research")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "bundled_channels.json")
QUALITY = os.path.join(ROOT, "app", "src", "main", "assets", "bundled_quality.json")

CAT_RANK = {"央视": 0, "卫视": 1, "地方": 2, "海外": 3, "其他": 4}
PROVINCES = ["北京", "天津", "上海", "重庆", "河北", "山西", "辽宁", "吉林", "黑龙江",
             "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南",
             "广东", "海南", "四川", "贵州", "云南", "陕西", "甘肃", "青海",
             "内蒙古", "广西", "西藏", "宁夏", "新疆", "香港", "澳门", "台湾"]


def source_weight(url: str, source: str) -> int:
    s = source.lower()
    if "zbds.top" in s:
        return 90
    if "vbskycn" in s:
        return 88
    if "ccsh" in s:
        return 68
    if "best-fan" in s:
        return 80
    if "yuechan" in s:
        return 80
    if "yangg-1989" in s:
        return 78
    if "migu" in s:
        return 75
    if "fanmingming.com" in s:
        return 76
    if "hujingguang" in s:
        return 65
    if "iptv-org" in s:
        return 55
    if "aptv" in s:
        return 45
    if "jk2024988" in s:
        return 35
    return 60


def host_of(url: str) -> str:
    try:
        return (urlparse(url).hostname or "").lower()
    except ValueError:
        return ""


def carrier_of(url: str) -> str:
    host = host_of(url)
    if any(x in host for x in ("chinamobile.com", "cmvideo.cn", "miguvideo.com", "gmcc.net", "mobaibox.com")):
        return "mobile"
    if any(x in host for x in ("chinaunicom.cn", "unicom", "wo.cn")):
        return "unicom"
    if any(x in host for x in ("chinatelecom", "dxhmt.cn", "189.cn", "ctcdn")):
        return "telecom"
    if host.startswith(("2409:", "39.134.", "39.135.", "39.136.", "183.207.")):
        return "mobile"
    if host.startswith(("2408:", "221.6.", "221.7.", "58.248.")):
        return "unicom"
    if host.startswith(("240e:", "61.136.", "219.147.", "222.169.")):
        return "telecom"
    return "public"


def usable_url(url: str) -> bool:
    try:
        parsed = urlparse(url)
        if parsed.scheme.lower() not in {"http", "https", "rtmp", "rtsp"}:
            return False
        if parsed.path.lower().endswith((".mp4", ".m4a", ".mp3", ".aac")):
            return False
        host = parsed.hostname
        if not host:
            return False
        try:
            ip = ipaddress.ip_address(host)
            if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved:
                return False
        except ValueError:
            pass
        return True
    except ValueError:
        return False


def is_ephemeral(title: str, group: str) -> bool:
    text = f"{title} {group}".lower()
    if re.search(r"体育-(?:今|明|昨|后)天\d{1,2}-\d{1,2}", group):
        return True
    return any(word in text for word in ("全场回放", "赛事回放", "精彩回放", "集锦", "录像回放"))


def select_diverse(lines: list[dict], limit: int = 8) -> list[dict]:
    alive = [line for line in lines if line["alive"]]
    if not alive:
        return []

    selected: list[dict] = []
    selected_urls: set[str] = set()
    host_counts: dict[str, int] = {}

    def add(line: dict, host_limit: int) -> bool:
        if len(selected) >= limit or line["url"] in selected_urls:
            return False
        host = host_of(line["url"])
        if host_counts.get(host, 0) >= host_limit:
            return False
        selected.append(line)
        selected_urls.add(line["url"])
        host_counts[host] = host_counts.get(host, 0) + 1
        return True

    add(alive[0], 2)
    for line in alive[1:]:
        if len(selected) >= min(4, limit):
            break
        add(line, 1)

    # Keep one carrier-specific fallback even when it was unreachable on the
    # build network; it can be the best line on the matching home broadband.
    for carrier in ("mobile", "unicom", "telecom"):
        candidate = next((line for line in lines
                          if carrier_of(line["url"]) == carrier
                          and source_weight(line["url"], line["source"]) >= 60), None)
        if candidate:
            add(candidate, 2)

    for line in alive:
        add(line, 2)
    return selected


def main() -> int:
    channels = json.load(open(os.path.join(RESEARCH, "channels.json"), encoding="utf-8"))
    classified = json.load(open(os.path.join(RESEARCH, "classified.json"), encoding="utf-8"))
    probe = json.load(open(os.path.join(RESEARCH, "probe_lines_results.json"), encoding="utf-8"))
    measured = {}
    if os.path.exists(QUALITY):
        snapshot = json.load(open(QUALITY, encoding="utf-8"))
        if 0 <= time.time() * 1000 - snapshot.get("measuredAtMs", 0) <= 30 * 86400 * 1000:
            measured = snapshot.get("resolutions", {})

    def quality_tier(url):
        dimensions = measured.get(url, "").split("x")
        if len(dimensions) == 2 and all(d.isdigit() for d in dimensions):
            return 3 if int(dimensions[0]) >= 1920 and int(dimensions[1]) >= 1080 else 0
        return 1

    probe_map = {line["url"]: line for line in probe.get("lines", [])}
    cls_map = {(c["title"], c["group"]): c for c in classified}

    merged = {}  # mergeKey -> channel dict
    for e in channels:
        c = cls_map.get((e["title"], e["group"]))
        if c is None or c.get("noise") or is_ephemeral(e["title"], e["group"]):
            continue
        key = c["mergeKey"]
        ch = merged.setdefault(key, {
            "title": c["displayName"],
            "name": c["displayName"],
            "group": e["group"],
            "category": c["category"],
            "region": c["region"],
            "displayGroup": c["displayGroup"],
            "lines": {},
        })
        url = e["url"]
        if url in ch["lines"] or not usable_url(url):
            continue
        p = probe_map.get(url)
        ch["lines"][url] = {
            "url": url,
            "source": e["source"],
            "alive": bool(p and p.get("alive")),
            "latency": (p.get("latency_ms") if p else None) or None,
            "quality": c.get("quality") or 0,
        }

    out = []
    cat_of = {ch["title"]: ch["category"] for ch in merged.values()}
    dropped_no_alive = 0
    for key, ch in merged.items():
        lines = sorted(ch["lines"].values(), key=lambda l: (
            not l["alive"],
            -quality_tier(l["url"]),
            -source_weight(l["url"], l["source"]),
            -l["quality"],
            l["latency"] if l["latency"] is not None else 10 ** 9,
            l["url"],
        ))
        selected = select_diverse(lines, 8)
        if not selected:
            dropped_no_alive += 1
            continue
        selected_urls = {line["url"] for line in selected}
        lines = [line for line in lines if line["url"] in selected_urls]
        out.append({
            "name": ch["title"],
            "title": ch["title"],
            "group": ch["group"],
            "uris": [l["url"] for l in lines],
            "uriSources": {l["url"]: l["source"] for l in lines},
            "playerType": "IPTV",
        })

    def group_rank(g):
        if g in PROVINCES:
            return PROVINCES.index(g)
        return 10 ** 6

    out.sort(key=lambda t: (
        CAT_RANK.get(cat_of.get(t["title"], "其他"), 4),
        group_rank(t["group"]),
        t["title"].lower(),
    ))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    total_lines = sum(len(t["uris"]) for t in out)
    print(f"bundled channels: {len(out)}  lines: {total_lines}  (all-dead channels dropped: {dropped_no_alive})")
    print(f"written: {OUT}  ({os.path.getsize(OUT) / 1024:.0f} KB)")
    return 0
if __name__ == "__main__":
    sys.exit(main())
