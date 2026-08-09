# -*- coding: utf-8 -*-
"""
全量线路活性探测工具

输入: tools/research/channels.json  (3763 条, 字段 source/group/title/url)
      tools/research/classified.json (与 channels.json 同序, 字段 category/region/displayGroup/mergeKey)
输出: tools/research/probe_stats.json
       tools/research/probe_lines_results.json (逐条结果, 供 stats 快速重算; 存在且条数一致时复用, --force 强制重探)

探测方式:
  - GET + Range: bytes=0-0, UA VLC/3.0.18, connect/read 各自超时 6s
  - alive 定义: HTTP 200/206/416 且读到 >=1 字节 (416 直接视为 alive)
  - 24 线程并发, 全局 14 分钟截止, 到点未完成按 dead(deadline) 记录
"""

import json
import os
import socket
import ssl
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait

BASE = os.path.dirname(os.path.abspath(__file__))
CHANNELS = os.path.join(BASE, "channels.json")
CLASSIFIED = os.path.join(BASE, "classified.json")
OUT = os.path.join(BASE, "probe_stats.json")
RESULTS = os.path.join(BASE, "probe_lines_results.json")

CATEGORY_RANK = {"央视": 0, "卫视": 1, "地方": 2, "海外": 3, "其他": 4}

CONCURRENCY = 24
TIMEOUT = 6           # 秒, connect/read 各自 <=6s
DEADLINE_S = 14 * 60  # 全局 14 分钟, 留 1 分钟余量
UA = "VLC/3.0.18"


def load_data():
    with open(CHANNELS, encoding="utf-8") as f:
        channels = json.load(f)
    with open(CLASSIFIED, encoding="utf-8") as f:
        classified = json.load(f)
    if len(channels) != len(classified):
        raise SystemExit(f"channels({len(channels)}) 与 classified({len(classified)}) 长度不一致")
    rows = []
    for ch, cl in zip(channels, classified):
        rows.append({
            "source": ch["source"],
            "group": ch.get("group", ""),
            "title": ch.get("title", ""),
            "url": ch.get("url", ""),
            "category": cl.get("category", ""),
            "region": cl.get("region", ""),
            "displayGroup": cl.get("displayGroup", ""),
            "mergeKey": cl.get("mergeKey", ""),
        })
    return rows


def load_cached_results(rows):
    if "--force" in sys.argv or not os.path.exists(RESULTS):
        return None
    with open(RESULTS, encoding="utf-8") as f:
        cached = json.load(f)
    if cached.get("total") != len(rows):
        return None
    return cached["lines"]


def probe(url: str):
    """返回 (alive: bool, latency_ms: float|None, note: str)"""
    start = time.perf_counter()
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Range": "bytes=0-0",
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "Connection": "close",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            status = resp.status
            if status == 416:
                return True, (time.perf_counter() - start) * 1000, "416"
            if status in (200, 206):
                data = resp.read(1)
                if data:
                    return True, (time.perf_counter() - start) * 1000, f"{status}"
                return False, (time.perf_counter() - start) * 1000, f"{status}:empty"
            return False, (time.perf_counter() - start) * 1000, f"status:{status}"
    except urllib.error.HTTPError as e:
        return False, (time.perf_counter() - start) * 1000, f"http:{e.code}"
    except urllib.error.URLError as e:
        return False, (time.perf_counter() - start) * 1000, f"url:{e.reason}"
    except (socket.timeout, TimeoutError):
        return False, (time.perf_counter() - start) * 1000, "timeout"
    except (ssl.SSLError, ConnectionError, OSError) as e:
        return False, (time.perf_counter() - start) * 1000, f"conn:{type(e).__name__}"
    except Exception as e:  # noqa: BLE001 - 探测脚本兜底, 单条失败不影响整体
        return False, (time.perf_counter() - start) * 1000, f"err:{type(e).__name__}"


def main():
    rows = load_data()
    total = len(rows)
    cached = load_cached_results(rows)
    if cached is not None:
        print(f"复用缓存 {RESULTS} ({len(cached)} 条), 跳过重新探测 (--force 可强制重探)", flush=True)
        results = [(c["alive"], c["latency_ms"], c["note"]) for c in cached]
        duration = 0.0
    else:
        print(f"载入 {total} 条线路, 并发 {CONCURRENCY}, 超时 {TIMEOUT}s, 全局截止 {DEADLINE_S}s", flush=True)
        results = [None] * total
        done = 0
        lock = threading.Lock()
        deadline = time.monotonic() + DEADLINE_S
        start_run = time.monotonic()

        def work(i, row):
            alive, ms, note = probe(row["url"])
            return i, alive, ms, note

        pending = []
        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            submitted = 0
            # 受控提交: 始终维持至多 CONCURRENCY*2 个在途任务, 便于到点收尾
            while submitted < total:
                if time.monotonic() >= deadline:
                    break
                while len(pending) < CONCURRENCY * 2 and submitted < total:
                    pending.append(pool.submit(work, submitted, rows[submitted]))
                    submitted += 1
                done_futs, _ = wait(pending, timeout=1, return_when=FIRST_COMPLETED)
                for fut in done_futs:
                    pending.remove(fut)
                    i, alive, ms, note = fut.result()
                    results[i] = (alive, ms, note)
                    with lock:
                        done += 1
                        if done % 500 == 0 or done == total:
                            elapsed = time.monotonic() - start_run
                            alive_n = sum(1 for r in results if r and r[0])
                            print(f"进度 {done}/{total} alive={alive_n} 耗时={elapsed:.0f}s", flush=True)
            # 到点未完成的任务按 dead 记录
            for fut in pending:
                fut.cancel()
            for i in range(total):
                if results[i] is None:
                    results[i] = (False, None, "deadline")
        duration = time.monotonic() - start_run

    for i, row in enumerate(rows):
        row["alive"], row["latency_ms"], row["note"] = results[i]

    alive_n = sum(1 for r in rows if r["alive"])
    dead_n = total - alive_n

    # ---- per_source ----
    per_source = {}
    for r in rows:
        s = per_source.setdefault(r["source"], {"alive": 0, "dead": 0, "lat": []})
        if r["alive"]:
            s["alive"] += 1
            if r["latency_ms"] is not None:
                s["lat"].append(r["latency_ms"])
        else:
            s["dead"] += 1
    for s in per_source.values():
        lat = sorted(s.pop("lat"))
        s["avg_ms"] = round(statistics.mean(lat), 1) if lat else None
        s["median_ms"] = round(statistics.median(lat), 1) if lat else None
        s["p90_ms"] = round(lat[int(len(lat) * 0.9) - 1], 1) if lat else None

    # ---- per_category ----
    per_category = {}
    for r in rows:
        c = per_category.setdefault(r["category"] or "(空)", {"alive": 0, "dead": 0})
        c["alive" if r["alive"] else "dead"] += 1

    # ---- dead 首选线路频道 (按 mergeKey 取文件序第一条) ----
    first_seen = {}
    dead_first = []
    for r in rows:
        key = r["mergeKey"]
        if key not in first_seen:
            first_seen[key] = r
            if not r["alive"]:
                dead_first.append(r)
    dead_first_sorted = sorted(
        dead_first,
        key=lambda r: (CATEGORY_RANK.get(r["category"], 9), r["title"], r["source"]),
    )
    dead_first_by_category = {}
    for r in dead_first:
        c = dead_first_by_category.setdefault(r["category"], {"dead_first": 0, "total_keys": 0})
        c["dead_first"] += 1
    for key in first_seen:
        cat = first_seen[key]["category"]
        dead_first_by_category.setdefault(cat, {"dead_first": 0, "total_keys": 0})["total_keys"] += 1

    out = {
        "meta": {
            "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "total": total,
            "alive": alive_n,
            "dead": dead_n,
            "alive_ratio": round(alive_n / total, 4),
            "duration_s": round(duration, 1),
            "concurrency": CONCURRENCY,
            "timeout_s": TIMEOUT,
            "deadline_s": DEADLINE_S,
            "note": "latency 统计仅针对 alive 请求; 首选线路 = 该 mergeKey 在文件序中的第一条",
        },
        "per_source": per_source,
        "per_category": per_category,
        "dead_first_line_channels": [
            {
                "title": r["title"],
                "group": r["group"],
                "url": r["url"],
                "source": r["source"],
                "category": r["category"],
                "mergeKey": r["mergeKey"],
                "note": r["note"],
            }
            for r in dead_first_sorted[:100]
        ],
        "dead_first_line_count": len(dead_first),
        "dead_first_by_category": dead_first_by_category,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    with open(RESULTS, "w", encoding="utf-8") as f:
        json.dump(
            {
                "total": total,
                "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "lines": [
                    {
                        "source": r["source"],
                        "title": r["title"],
                        "url": r["url"],
                        "category": r["category"],
                        "mergeKey": r["mergeKey"],
                        "alive": r["alive"],
                        "latency_ms": r["latency_ms"],
                        "note": r["note"],
                    }
                    for r in rows
                ],
            },
            f,
            ensure_ascii=False,
            indent=1,
        )

    # ---- 摘要 ----
    print(f"\n总存活率: {alive_n}/{total} = {alive_n/total:.1%}  (耗时 {duration:.0f}s)")
    print("\n按源存活率:")
    for src, s in sorted(
        per_source.items(),
        key=lambda kv: kv[1]["alive"] / max(kv[1]["alive"] + kv[1]["dead"], 1),
        reverse=True,
    ):
        n = s["alive"] + s["dead"]
        print(f"  {src:<28} {s['alive']:>4}/{n:<4} {s['alive']/n:6.1%}  "
              f"avg={s['avg_ms']}ms median={s['median_ms']}ms p90={s['p90_ms']}ms")
    print("\n按分类 dead 比例:")
    for cat, c in sorted(
        per_category.items(),
        key=lambda kv: kv[1]["dead"] / max(kv[1]["alive"] + kv[1]["dead"], 1),
        reverse=True,
    ):
        n = c["alive"] + c["dead"]
        print(f"  {cat:<10} dead={c['dead']:>4}/{n:<4} {c['dead']/n:6.1%}")
    print(f"\ndead 首选线路频道: {len(dead_first)} 个 mergeKey 的首选线路不可用, Top20 (按分类优先级):")
    for r in dead_first_sorted[:20]:
        print(f"  [{r['category']}] {r['title']} | {r['group']} | {r['source']} | {r['note']} | {r['url'][:90]}")
    print(f"\n结果已写入 {OUT}")


if __name__ == "__main__":
    main()
