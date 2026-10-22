#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核对 `DiagnosticsCollect.MODULE_TAGS` 有没有漏 TAG。

============================ 为什么要有这个脚本（2026-10-12） ============================
「导出诊断日志」里那段**模块日志**是按 TAG 过滤的（`logcat -s <MODULE_TAGS...>`）。
一张手写的 TAG 表必然会随新功能腐化 —— 已经漏过两次：
  · `HyperPlusSplitGeom`（10-11 新增的几何探针，2026-10-12 才发现没进表）；
  · `HyperPlusActiveDisplay` / `HyperPlusLiquidGlassBar`（同批漏）。
漏了的后果**很隐蔽**：新功能的日志在用户导出的报告里静默消失，而报告里另有一段
「未过滤的全量尾部」只覆盖最近十几分钟 ⇒ 排查时看到的是"什么都没打"。

本脚本把「源码里所有 TAG」与「MODULE_TAGS」对一遍，**只读、不改任何文件**。

用法（在 FaceRotateMvp/ 下）：  python tools/check_diag_tags.py
退出码：0 = 齐了；1 = 有漏（会把缺失项打出来）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main"
DIAG = SRC / "java" / "cn" / "dsr213" / "hyperplus" / "DiagnosticsCollect.kt"

# 只看我们自己的 TAG：`const val TAG = "HyperPlus…"` / `"FaceRotate"`（大小写敏感）
TAG_DECL = re.compile(r'const\s+val\s+TAG\s*(?::\s*String)?\s*=\s*"([A-Za-z0-9_]+)"')

# 前缀白名单：`HyperPlus` 打头的都算；`FaceRotate` 是历史遗留（老日志的公共前缀）
KEEP_PREFIX = ("HyperPlus", "FaceRotate")

# 这两个 TAG 不指望进表：
#   · HyperPlusDiag   —— 收集器自己（它跑在 App 进程，采的是别人）
#   · HyperPlusBoot   —— 启动摘要的 TAG，正文已经整份落在 `hyperplus_boot.txt`，
#                        报告里有专门的采集段（`DiagnosticsCollect.BOOT_REPORT_NAME`）
EXCLUDE = {"HyperPlusDiag", "HyperPlusBoot"}


def collect_tags() -> dict[str, set[str]]:
    """返回 {TAG: {出现它的文件…}}。"""
    found: dict[str, set[str]] = {}
    for kt in SRC.rglob("*.kt"):
        try:
            text = kt.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for tag in TAG_DECL.findall(text):
            if not tag.startswith(KEEP_PREFIX) or tag in EXCLUDE:
                continue
            found.setdefault(tag, set()).add(str(kt.relative_to(ROOT)))
    return found


def collect_module_tags() -> set[str]:
    text = DIAG.read_text(encoding="utf-8")
    start = text.find("private val MODULE_TAGS")
    if start < 0:
        sys.exit("❌ 在 %s 里找不到 MODULE_TAGS" % DIAG)
    end = text.find(")", text.find("listOf(", start))
    block = text[start:end]
    return set(re.findall(r'"([A-Za-z0-9_]+)"', block))


def main() -> int:
    src_tags = collect_tags()
    listed = collect_module_tags()

    missing = sorted(t for t in src_tags if t not in listed)
    # 反方向也报一下（表里有、源码里已经没有）——那是重构后忘记删的死项
    stale = sorted(t for t in listed if t not in src_tags)

    print("源码里共 %d 个 TAG，MODULE_TAGS 里有 %d 个" % (len(src_tags), len(listed)))
    if missing:
        print("\n🔴 漏进 MODULE_TAGS 的 TAG（这些日志在导出的诊断里会静默消失）：")
        for t in missing:
            print("   - %-24s %s" % (t, ", ".join(sorted(src_tags[t]))))
    if stale:
        print("\n🟡 只在 MODULE_TAGS 里、源码已找不到的 TAG（可以删了）：")
        for t in stale:
            print("   - %s" % t)
    if missing or stale:
        print("\n⛔ 纪律见 `记忆库/20-纪律/MEM-20-新功能必须带日志与导出日志.md`。")
        return 1
    print("\n✅ MODULE_TAGS 与源码一致，没有漏项。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
