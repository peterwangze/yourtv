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
  3. 线路排序：存活优先 > 源质量分层 > 清晰度分 > 延迟
  4. 每频道线路数上限 10（全部死线时保留 6 条给其他网络兜底）
  5. 频道排序：央视 > 卫视 > 地方(省份序) > 海外(国家序) > 其他

用法：python tools/build_bundled.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESEARCH = os.path.join(ROOT, "tools", "research")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "bundled_channels.json")

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
        return 85
    if "best-fan" in s:
        return 80
    if "yuechan" in s:
        return 80
    if "yangg-1989" in s:
        return 78
    if "migu" in s:
        return 75
    if "iptv-org" in s:
        return 55
    if "aptv" in s:
        return 45
    if "jk2024988" in s or "hujingguang" in s:
        return 35
    return 60


def main() -> int:
    channels = json.load(open(os.path.join(RESEARCH, "channels.json"), encoding="utf-8"))
    classified = json.load(open(os.path.join(RESEARCH, "classified.json"), encoding="utf-8"))
    probe = json.load(open(os.path.join(RESEARCH, "probe_lines_results.json"), encoding="utf-8"))

    probe_map = {line["url"]: line for line in probe.get("lines", [])}
    cls_map = {(c["title"], c["group"]): c for c in classified}

    merged = {}  # mergeKey -> channel dict
    for e in channels:
        c = cls_map.get((e["title"], e["group"]))
        if c is None or c.get("noise"):
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
        if url in ch["lines"]:
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
            -source_weight(l["url"], l["source"]),
            -l["quality"],
            l["latency"] if l["latency"] is not None else 10 ** 9,
            l["url"],
        ))
        alive = [l for l in lines if l["alive"]]
        cap = 10 if alive else 6
        if not alive:
            dropped_no_alive += 1
        lines = lines[:cap]
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
    print(f"bundled channels: {len(out)}  lines: {total_lines}  (all-dead channels kept: {dropped_no_alive})")
    print(f"written: {OUT}  ({os.path.getsize(OUT) / 1024:.0f} KB)")
    return 0
if __name__ == "__main__":
    sys.exit(main())
