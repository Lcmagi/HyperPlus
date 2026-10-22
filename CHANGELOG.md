# 更新日志 / Changelog

> 本文件自 `0.9.0-Alpha` 起建立，更早版本没有逐条留档。

## 0.9.0-Alpha

### 新增 / Added

- **显示增强（Display boost）** —— 新增功能，入口在「功能」主页、紧跟在「分屏」之后。
  - 全局显示密度可调，范围 **370–440 dpi**，步进 5；数值越大，画面越小。
  - **内外屏同时生效**：逐块屏下发，不会再出现一块变了一块没变。
  - **确认制**：拖动滑条只改草稿，点「应用」才写入系统，点之前不生效、不需要重启。
  - 提供「恢复默认」，回到系统原值 440。
  - 下限 370 是硬限制——低于约 365 外屏会被系统误判成内屏。

### 修复 / Fixed

- **打开「显示增强」后整机卡死**（用户 10-18 报障）。
  根因：执行 root 命令时同步等待退出、却不读它的输出；命令输出超过约 64KB 就会把子进程堵死，主线程跟着一起卡住。改为边执行边读。
- **「轻折加分屏」折了彻底没反应**。
  根因：投递被系统拒绝（Looper 已退出）时返回失败，但执行闸没有释放，之后**每一次**折叠都被当成"上一次还没完成"丢掉，日志里只有一句看不出异常的话。现在接住返回值，失败当场释放闸。
- **分屏高温保护实际上从未真正生效**。
  根因：两条高温回调里，无参那条用反射读温度时读错了对象，恒取到 null 后一律放行——注释里写的「两条都要拦」只做到了一条。已修。
- **自动旋转的「接管 / 交还」在换屏瞬间记到了另一块屏上**。
  根因：换屏回调里读到的是旧屏形态，而记账是逐屏各存一份。现在按回调传进来的准确形态记账，并直接读该屏自己的自动旋转偏好；读不到时不再当成「锁定」，而是不写。
- **用户没动过温度设置时，模块不再改写系统行为**。没改过配置就一个字节都不动。
- **清理分屏「加一格」里的死代码**：一段准入条件恒为假，永远不会执行。
- **诊断报告漏收 3 个日志标签**（`HyperPlusSplitGeom` / `HyperPlusActiveDisplay` / `HyperPlusLiquidGlassBar`），导致复现之后导出的报告里看不到这些记录。

### 其他 / Internal

- 分屏触发器的关键节点（档位、hook 是否挂上、阈值取值）同时写入启动摘要——logcat 只保留约 10 分钟，而报障时最先消失的恰好是这几条。
- 新增诊断标签核对脚本 `tools/check_diag_tags.py`：扫源码核对「新增 TAG 必须同步进诊断白名单」，本地跑，不进构建。

---

## English

### Added

- **Display boost** — new feature, entry on the main Functions page right after Split screen.
  - Global display density, **370–440 dpi** in steps of 5. Larger dpi means smaller content.
  - Applies to **both screens** by targeting each display individually.
  - **Confirm-based**: dragging the slider only edits a draft; nothing is written until you tap Apply. No reboot needed.
  - Includes "Reset to default" (440).
  - The 370 floor is a hard limit — below roughly 365 the outer screen is misdetected as the inner one.

### Fixed

- **Freeze after opening Display boost** (reported 2026-10-18).
  Cause: root commands were awaited without draining their output; anything over ~64KB deadlocked the child process and the main thread with it. Now read while running.
- **Light-fold split screen did nothing at all after one failure**.
  Cause: when the handler rejected the posted task, the in-flight gate was never released, so every later fold was dropped as "previous one still running". Now the return value is checked and the gate is released on failure.
- **The split-screen high-temperature guard never actually worked**.
  Cause: one of the two callbacks resolved the observer through the wrong receiver, always got null, and fell through to "allow". Both paths are now guarded as documented.
- **Auto-rotate takeover / hand-back was recorded against the wrong screen** right after a screen switch.
  Cause: the switch callback exposed the previous screen's form while bookkeeping is per-screen. Now it uses the accurate form passed in and reads that screen's own auto-rotate preference; "cannot read" no longer means "locked".
- **No system behaviour is modified when the user has not touched the temperature settings.**
- **Removed dead code** in the split "add one pane" path — an entry condition that was always false.
- **Three diagnostic tags were missing from the report** (`HyperPlusSplitGeom` / `HyperPlusActiveDisplay` / `HyperPlusLiquidGlassBar`).
