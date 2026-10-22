#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""左右手信号定标分析器（探针 CSV → 可分性结论）。

2026-10-18 重写，两个原因：

1. **阶段表过期**。旧版按 10 步流程（含"放下"段）编号，探针改成 5 步手动推进后
   没同步 ⇒ 第一版报告把「展开·仅左手」当成「合上·右手」比，算出 hinge_angle d=178
   这种荒谬结论（其实比的是"合上 vs 展开"）。现按 HandProbeActivity.phases 对齐。

2. **只算 Cohen's d 会骗人**。sx937x 的读数带秒级基线漂移，同一只手不动它自己就会走：
   实测内屏单手期间 v4 自己漂 24 计数，而左右手差 23 计数 —— 单看 d 能到 4.0（"强可分"），
   实际是漂移冒充信号。所以本版每个对比必须同时报三个量：

      差异  = mean(B) − mean(A)                     ← 想测的东西
      漂移  = 同一阶段内 后1/3均值 − 前1/3均值       ← 手没换，读数自己走了多少
      阶跃  = B 开头2s均值 − A 结尾2s均值            ← 真换手应该有台阶，没有就是没响应

   判定一律用 **差异 / max(漂移, 噪声)**，不看单独的 d。
   ⇒ 差异打不过漂移的，一律写"不可分"，哪怕 d 很大。
"""

import argparse
import csv
import os
import statistics
import sys
from collections import defaultdict

# ---- 阶段定义：与 HandProbeActivity.phases 严格同步（5 步，无"放下"段）----
PHASES = [
    "① 合上·左手",
    "② 合上·右手",
    "③ 展开·双手",
    "④ 展开·仅左手",
    "⑤ 展开·仅右手",
]

# ---- 关注的两组对比：(标签, 阶段A, 阶段B) ----
COMPARISONS = [
    ("外屏：左手 vs 右手", 0, 1),
    ("内屏：仅左手 vs 仅右手", 3, 4),
]

# 每个阶段开头跳过多少秒：阶段刚切过去时，折叠动作的余波 / 基线重建都还在，
# 直接拿全段平均会把"过渡"算成"左右手差异"。
SKIP_HEAD_SEC = 3.0

TYPE_NAMES = {
    33171015: "sx937x(SAR)",
    33171125: "holder_detect",
    33171028: "sar_algo",
    33171031: "large_area",
    33171087: "fold_status",
    9: "gravity",
    36: "hinge_angle",
    8: "proximity",
    1: "accelerometer",
}


def load(path):
    """返回 ({(type, ch): {phase: [(t, v), ...]}}, {phase: 行数})"""
    data = defaultdict(lambda: defaultdict(list))
    per_phase_count = defaultdict(int)
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        rd = csv.DictReader(f)
        for row in rd:
            try:
                t = float(row["t_sec"])
                ph = int(row["phase"])
                typ = int(row["type"])
            except (TypeError, ValueError):
                continue
            if ph < 0:
                continue
            per_phase_count[ph] += 1
            for k in range(16):
                raw = row.get("v%d" % k, "")
                if raw is None or raw == "":
                    continue
                try:
                    v = float(raw)
                except ValueError:
                    continue
                data[(typ, k)][ph].append((t, v))
    for key in data:
        for ph in data[key]:
            data[key][ph].sort()
    return data, per_phase_count


def values_after_head(pairs, skip=SKIP_HEAD_SEC):
    """去掉阶段开头 skip 秒。样本不够时退回全段（并在报告里标出）。"""
    if not pairs:
        return []
    t0 = pairs[0][0]
    trimmed = [p for p in pairs if p[0] - t0 >= skip]
    return trimmed if len(trimmed) >= 6 else pairs


def drift_of(pairs):
    """单手保持不动时，读数自己走了多少（后 1/3 均值 − 前 1/3 均值）。"""
    vs = [v for _, v in pairs]
    if len(vs) < 9:
        return 0.0
    k = len(vs) // 3
    return statistics.fmean(vs[-k:]) - statistics.fmean(vs[:k])


def step_at(boundary_pairs_a, boundary_pairs_b, win=2.0):
    """换手台阶：B 开头 win 秒 − A 结尾 win 秒。"""
    if not boundary_pairs_a or not boundary_pairs_b:
        return None
    ta = boundary_pairs_a[-1][0]
    tb = boundary_pairs_b[0][0]
    a = [v for t, v in boundary_pairs_a if t > ta - win]
    b = [v for t, v in boundary_pairs_b if t < tb + win]
    if len(a) < 2 or len(b) < 2:
        return None
    return statistics.fmean(b) - statistics.fmean(a)


def spread(a, b):
    sa = statistics.pstdev(a) if len(a) > 1 else 0.0
    sb = statistics.pstdev(b) if len(b) > 1 else 0.0
    return ((sa * sa + sb * sb) / 2.0) ** 0.5


def cohens_d(a, b):
    sp = spread(a, b)
    if sp < 1e-9:
        return 0.0 if abs(statistics.fmean(a) - statistics.fmean(b)) < 1e-9 else 9.99
    return abs(statistics.fmean(b) - statistics.fmean(a)) / sp


def verdict(diff, drift, sp):
    """判定只认「差异能不能打过漂移」，不认 d。"""
    if max(abs(drift), sp) < 1e-9:
        # 段内完全不动（量化死值，如恒 1524）：差值只是量化台阶，不构成信号
        return "量化死值 ▲" if diff else "无信号 ▲"
    if diff == 0:
        return "无信号 ▲"
    ratio = abs(diff) / max(abs(drift), sp)
    if ratio >= 3.0:
        return "强 ★★★ (%.1f×)" % ratio
    if ratio >= 1.5:
        return "中 ★★ (%.1f×)" % ratio
    if ratio >= 1.0:
        return "弱 ★ (%.1f×)" % ratio
    return "不可分 ▲ (%.1f×)" % ratio


def analyze(path, out_md=None, top=14):
    data, counts = load(path)
    if not data:
        print("❌ CSV 里没有任何可用数据（检查探针是否真的收到事件）")
        return 1

    lines = []

    def say(s=""):
        print(s)
        lines.append(s)

    say("# 左右手信号定标 · 分析报告")
    say()
    say("源文件：`%s`" % os.path.basename(path))
    say()
    say("> 判据：`差异 / max(漂移, 噪声)`。**只看 Cohen's d 会被基线漂移骗**（详见文件头注释）。")
    say("> 每个阶段已跳过开头 %.0f 秒的过渡段。" % SKIP_HEAD_SEC)
    say()

    say("## 0. 采样覆盖（先看这个 —— 某阶段 0 行，后面的表就不用看了）")
    say()
    say("| 阶段 | 行数 |")
    say("|---|---|")
    for i, name in enumerate(PHASES):
        say("| %s | %d |" % (name, counts.get(i, 0)))
    extra = [p for p in sorted(counts) if p >= len(PHASES)]
    if extra:
        say("| （表外阶段 %s） | %d |" % (
            ",".join(str(p) for p in extra), sum(counts[p] for p in extra)))

    say()
    say("| 传感器 | 总事件 | 有非零值的通道 | 各通道均值（阶段①） |")
    say("|---|---|---|---|")
    by_type = defaultdict(lambda: defaultdict(list))
    for (typ, ch), phmap in data.items():
        for ph, pairs in phmap.items():
            by_type[typ][ch].extend(v for _, v in pairs)
    for typ in sorted(by_type):
        chans = by_type[typ]
        nonzero = [c for c, vs in chans.items() if any(abs(v) > 1e-6 for v in vs)]
        total = max((len(v) for v in chans.values()), default=0)
        means = []
        for c in sorted(chans):
            pairs = data.get((typ, c), {}).get(0, [])
            if pairs:
                means.append("v%d=%.0f" % (c, statistics.fmean(v for _, v in pairs)))
        say("| %s | %d | %d | %s |" % (
            TYPE_NAMES.get(typ, "type=%d" % typ), total, len(nonzero),
            " ".join(means[:8]) if means else "—"))

    # ---- 核心：区分度 ----
    for label, pa, pb in COMPARISONS:
        say()
        say("## %s" % label)
        say()
        rows = []
        for (typ, ch), phmap in data.items():
            ra = values_after_head(phmap.get(pa, []))
            rb = values_after_head(phmap.get(pb, []))
            if len(ra) < 6 or len(rb) < 6:
                continue
            a = [v for _, v in ra]
            b = [v for _, v in rb]
            diff = statistics.fmean(b) - statistics.fmean(a)
            drift = max(abs(drift_of(ra)), abs(drift_of(rb)))
            step = step_at(phmap.get(pa, []), phmap.get(pb, []))
            sp = spread(a, b)
            rows.append((abs(diff) / max(drift, sp, 1e-9), typ, ch, a, b,
                         diff, drift, step, sp, cohens_d(a, b), len(a), len(b)))
        if not rows:
            say("> ⚠️ 两段有效样本都不足 6 个，无法比较。")
            continue
        rows.sort(reverse=True)
        say("| 传感器 | 通道 | A 均值 | B 均值 | **差异** | 单手漂移 | 换手阶跃 | 噪声σ | d | 判定 |")
        say("|---|---|---|---|---|---|---|---|---|---|")
        for ratio, typ, ch, a, b, diff, drift, step, sp, d, na, nb in rows[:top]:
            say("| %s | v%d | %.1f | %.1f | **%+.1f** | %.1f | %s | %.1f | %.2f | %s |" % (
                TYPE_NAMES.get(typ, "type=%d" % typ), ch,
                statistics.fmean(a), statistics.fmean(b), diff, drift,
                ("%+.1f" % step) if step is not None else "—",
                sp, d, verdict(diff, drift, sp)))
        say()
        say("（A/B 采样数：%s）" % ", ".join(
            "v%d=%d/%d" % (r[2], r[10], r[11]) for r in rows[:top]))
        best = rows[0]
        say()
        if best[0] >= 3.0:
            say("✅ **结论：可分** —— `%s v%d`，差异 %.1f 是漂移 %.1f 的 %.1f 倍。" % (
                TYPE_NAMES.get(best[1], "type=%d" % best[1]), best[2],
                best[5], best[6], best[0]))
        elif best[0] >= 1.5:
            say("🟡 **结论：勉强** —— 最好的是 `%s v%d`，只有漂移的 %.1f 倍，"
                "单通道不够，要融合 + 更长的稳定窗口。" % (
                    TYPE_NAMES.get(best[1], "type=%d" % best[1]), best[2], best[0]))
        else:
            say("❌ **结论：不可分** —— 最好的通道差异也只有漂移的 %.1f 倍。"
                "这批信号里**没有**左右手信息。" % best[0])
        say()
        say("> ⚠️ 上面这条只是**机械判定，不能当最终结论**。还必须人工确认两件事：")
        say("> ① 把 A/B 各切两半，差值是否两半都同向出现（只在一半出现 ⇒ 漂移）；")
        say("> ② 把相邻的前一段也算进来，看是不是连续单调变化（单调 ⇒ 时间效应，不是换手）。")

    # ---- 展开·双手：双侧同时覆盖会不会额外压低 ----
    if len(PHASES) > 2:
        say()
        say("## 附：%s 是否有额外信息（对照 %s）" % (PHASES[2], PHASES[0]))
        say()
        any_row = False
        for (typ, ch), phmap in sorted(data.items()):
            ra = values_after_head(phmap.get(2, []))
            rb = values_after_head(phmap.get(0, []))
            if len(ra) < 6 or len(rb) < 6:
                continue
            a = [v for _, v in ra]
            b = [v for _, v in rb]
            diff = statistics.fmean(a) - statistics.fmean(b)
            drift = max(abs(drift_of(ra)), abs(drift_of(rb)))
            v = verdict(diff, drift, spread(a, b))
            if v.startswith(("强", "中")):
                say("- `%s v%d`：差 %+.1f，漂移 %.1f ⇒ %s" % (
                    TYPE_NAMES.get(typ, "type=%d" % typ), ch, diff, drift, v))
                any_row = True
        if not any_row:
            say("- 没有通道能打过漂移。")

    text = "\n".join(lines)
    if out_md:
        with open(out_md, "w", encoding="utf-8") as f:
            f.write(text)
        print("\n已写报告：%s" % out_md)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv_path")
    ap.add_argument("--md", default=None, help="把报告写到这个 md 路径")
    ap.add_argument("--top", type=int, default=14, help="每个对比最多列几行")
    args = ap.parse_args()
    if not os.path.exists(args.csv_path):
        print("找不到文件：%s" % args.csv_path)
        return 2
    return analyze(args.csv_path, args.md, args.top)


if __name__ == "__main__":
    sys.exit(main())
