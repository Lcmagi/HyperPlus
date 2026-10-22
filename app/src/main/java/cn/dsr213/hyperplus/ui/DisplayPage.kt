package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.ActiveDisplay
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.AppToast
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RootShell
import cn.dsr213.hyperplus.ScreenForm
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页：**显示增强**（2026-10-12 新增，入口在「功能」主页，排在「分屏增强」之后）。
 *
 * ============================ 这一页做什么 ============================
 * 只做一件事：**改全局显示密度（dpi）** —— 一个滑块，左右拖，让界面元素整体变大或变小。
 *
 * ★★★ 语义（用户 2026-10-12 原话：「**DPI 数值越大，画面越小，所以没错**」）：
 *   **dpi 大 ⇒ 画面小（内容多）**；**dpi 小 ⇒ 画面大（内容少）**。
 *   所以滑条是 **左 440（最小画面）→ 右 370（最大画面）**，
 *   ⛔ 别按"数字小在左边"的直觉把它做反 —— 用户报"下限/上限"时说的是**画面大小**。
 *
 * ============================ 为什么区间是 370~440（★ 别放宽到 350）============================
 * 🔴 **下限 370 是硬约束，不是审美选择** —— 它对着一颗会炸的雷：
 *   [ScreenForm] 判内/外屏用的是 `最小边像素 × 160 ÷ 密度` 与阈值 **512dp** 比。
 *   外屏物理最小边 1168px ⇒ 密度降到 **365** 时 `1168×160÷365 = 511.8`，再往下就 > 512
 *   ⇒ **外屏会被误判成内屏** ⇒ 形态串档，旋转/槽位/闸门全乱。
 *   用户最初报的 350 会踩中（`1168×160÷350 = 533.9 > 512`），据此收窄到 370（余量 6.9dp）。
 *   ✅ 2026-10-12 真机实验已**实证** 370 安全（`getRealMetrics` 走的那条路径在覆盖 370 时
 *   仍报 440，不跟 `wm density` 覆盖）—— 完整推理链见 [[MEM-19]]。
 *   ⛔ 除非 [ScreenForm] 的判定口径改了，否则**不许动这个下限**。
 *
 * ⚠️ 上限 440 = **物理密度**（本机两块屏 `Physical density: 440`）。
 *   ⇒ 440 就是"原样"，调到 440 等于没改；所以默认值取 440 是"回到出厂观感"。
 *
 * ============================ ★ 确认制（用户 2026-10-12 点名）============================
 * 用户原话：「**另外不要实时生效，调完之后按一下应用才生效**」。
 * ⇒ 滑块拖动 **只改草稿**（[AppPrefs.setDisplayDpi]），⛔ **一个字都不写系统**；
 *   只有当用户按下「应用」，才真正跑 `wm density`（[AppPrefs.applyDisplayDpi]）。
 *
 * 为什么这条特别重要：拖动是**高频探索动作**（找"哪个大小舒服"要来回拖十几次），
 * 若每次都写系统，就会每次触发一轮 WMS 配置变更 —— 界面重绘、闪烁、甚至短暂错位。
 * ⇒ 确认制不是"多一步麻烦"，而是**避免拖滑块时整机在抖**。
 *
 * ============================ ★★ 内外屏一致 ============================
 * 用户要求「**内外屏一致全局修改**」。实现上有个硬坑（2026-10-12 实测）：
 *   **`wm density` 不带 `-d` 时只作用于 display 0**！
 *   ⇒ 必须对**每块屏各发一次** `-d <id>`，否则只改一块屏，
 *     用户在内屏看就是"按了应用完全没反应"（必被报成 bug）。
 *   ⚠️ 而且 display id **不能写死 0/1** —— 按 [[MEM-12]]「`display 0` 是『当前主屏』动态别名」，
 *     它会随折叠形态重绑。⇒ 枚举 [ActiveDisplay.displayIds] 拿当前所有屏。
 *
 * ============================ 这一页的"生效值 vs 草稿值" ============================
 * 页面上有两个数字，⛔ 别把它们混为一谈：
 * - **当前生效值**：进页面时**现跑** `wm density` 读回来的（系统真实状态），只读；
 * - **草稿值**：滑块位置，可能还没应用（与生效值不同就提示"未应用"）。
 * 读回来的值在 [appliedDpi]，随「应用」/「恢复默认」成功而刷新。
 */
@Composable
internal fun DisplayPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 滑块位置 = 草稿值（[AppPrefs.setDisplayDpi] 只改它，不写系统） */
    val draftDpi by AppPrefs.displayDpi.collectAsState()

    /**
     * **系统当前生效的密度**（读回来的，⛔ 不是草稿）。
     *
     * ★ `null` 有**两种**含义，用 [appliedMixed] 区分（别只用一个 nullable Int 表达两件事）：
     *   ① 还没读回来（进页面一瞬）；② 读回来了、但**两块屏数值不一致**。
     */
    var appliedDpi by remember { mutableStateOf<Int?>(null) }

    /**
     * 两块屏的生效值**不一致**时置真（值是"内外屏对不上"这件事本身，不是某个 dpi）。
     *
     * ★★ 为什么必须单独有这个状态（2026-10-12 实测抓出的真问题）：
     *   只改一块屏的 `wm density` 会留下 `display 0 = 380 / display 1 = 440` 这种烂摊子
     *   （本机实测到过）。老设计"取第一块屏当真值"会**随便挑一块**显示成"当前生效"，
     *   用户在内屏量到别的数 ⇒ 而且这个错值还会被写进草稿、**自我固化**。
     *   ⇒ 不一致就如实报出来，让用户知道"先按一次应用把它拉齐"。
     */
    var appliedMixed by remember { mutableStateOf(false) }

    /** 应用/恢复的结果提示（null = 不显示）。⛔ 不用 Toast（铁律：短提示唯一出口是 AppToast） */
    var busy by remember { mutableStateOf(false) }

    // 进页面现读一次系统真实密度 —— ★ 那一刻为准，因为用户可能在别处（系统设置/adb）改过。
    // ⚠️ 读**逐屏**（不带 -d 只报 display 0），而且要看**全部**是否一致（理由见 [appliedMixed]）。
    LaunchedEffect(Unit) {
        val ids = ActiveDisplay.displayIds(context)
        val map = readDensityByDisplay(ids)
        val distinct = map.values.toSet()
        when {
            // 一块都没读到（root 没授权 / 命令失败）⇒ 两个状态都留空，界面不显示"当前生效"那行
            distinct.isEmpty() -> {
                appliedDpi = null
                appliedMixed = false
            }
            // 所有屏一致 ⇒ 正常显示
            distinct.size == 1 -> {
                appliedDpi = distinct.first()
                appliedMixed = false
            }
            // 不一致 ⇒ 不撒谎显示某一个值，只标记"对不上"
            else -> {
                appliedDpi = null
                appliedMixed = true
            }
        }
        // ★ 草稿对齐到系统实际值 —— 但**只在对齐无歧义时**做：
        //   不一致时不知道该对齐哪一个，硬对齐会让错误自我固化（见 [appliedMixed]）。
        //   ⚠️ 仅当草稿与系统不一致时才写（[AppPrefs.setDisplayDpi] 内部也做了等值短路）。
        val aligned = distinct.singleOrNull()
        if (aligned != null && aligned != draftDpi) AppPrefs.setDisplayDpi(aligned)
    }

    // 「草稿 ≠ 生效」= 有未应用的改动 —— 用它来提示 + 决定「应用」按钮是否可点
    //   ⚠️ 屏间不一致时（appliedDpi == null && appliedMixed）也算"有待处理" ⇒ 允许按应用拉齐
    val dirty = appliedMixed || (appliedDpi != null && appliedDpi != draftDpi)

    SubPage(title = stringResource(R.string.display_title), onBack = onBack) {
        SmallTitle(text = stringResource(R.string.display_section_size))
        Card(modifier = Modifier.padding(bottom = 12.dp)) {
            // ★ 步进 5 ⇒ 15 档（370/375/…/440）。滑条值用 Int 传，避免浮点吸附误差。
            //   ⚠️ 实测（[RotationPage] 同款）：滑条**只响应拖动，不响应点按定位**。
            SliderPreference(
                // ⚠️ 方向：区间左端是**大值**（440 画面最小），右端是小值（370 画面最大）。
                //   直接把 [DISPLAY_DPI_MIN]..[DISPLAY_DPI_MAX] 给它会在拖到左边时给最大值，
                //   语义反了 ⇒ 这里传**取反映射后的量**，见下。
                value = (AppPrefs.DISPLAY_DPI_MAX - draftDpi).toFloat(),
                onValueChange = {
                    // ⚠️ 用 `roundToInt()` **不是** `toInt()`：`toInt()` 是向零截断，
                    //   拖动时会稳定偏掉半档（滑条给回 69.6 想表达 70，截断成 69 ⇒ dpi 371）。
                    //   反正 [AppPrefs.snapDisplayDpi] 还会再吸附一次，但别把误差喂给它。
                    AppPrefs.setDisplayDpi(AppPrefs.DISPLAY_DPI_MAX - it.roundToInt())
                },
                title = stringResource(R.string.display_dpi_title),
                summary = stringResource(R.string.display_dpi_summary, draftDpi),
                valueText = stringResource(R.string.display_dpi_value, draftDpi),
                // ⚠️ 区间也得取反：0..(MAX-MIN)=70，左端 0 = 画面最小（对应 dpi 440）
                valueRange = 0f..(AppPrefs.DISPLAY_DPI_MAX - AppPrefs.DISPLAY_DPI_MIN).toFloat(),
            )
            // ------------------------------------------------------- 当前生效 vs 待应用
            DpiStatusRow(
                applied = appliedDpi,
                mixed = appliedMixed,
                draft = draftDpi,
                dirty = dirty,
            )
            // ------------------------------------------------------- 应用 / 恢复默认
            //
            // ⚠️ **左右边距由这张 Card 给**（MiuiX `Card` 自带 `CardDefaults.InsideMargin`，
            //   约 16dp）⇒ ⛔ 这里**不许再加 `padding(horizontal = 16.dp)`**：
            //   叠上去会变 32dp，与上面的滑条（只吃卡的 16dp）**明显不对齐**。
            //   ★ 判据「边距归谁给」：容器（Card）已经给了 ⇒ 内容只管纵向间距。
            //   ⚠️ 同理，上面的 [DpiStatusRow] 也不加横向 padding。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.display_btn_apply),
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            val ids = ActiveDisplay.displayIds(context)
                            val ok = AppPrefs.applyDisplayDpi(ids)
                            // ★★ 写后**回读**（用户要求"按一下应用才生效"，那就得让他看到到底成没成）。
                            //   ⛔ 别乐观地把草稿当生效值 —— 万一 root 没授权/命令失败就撒谎了。
                            refreshApplied(ids) { dpi, mixed ->
                                appliedDpi = dpi
                                appliedMixed = mixed
                            }
                            busy = false
                            AppToast.show(
                                context,
                                context.getString(
                                    if (ok) R.string.display_toast_applied else R.string.display_toast_failed,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && dirty,
                )
                TextButton(
                    text = stringResource(R.string.display_btn_reset),
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            val ids = ActiveDisplay.displayIds(context)
                            val ok = AppPrefs.resetDisplayDpi(ids)
                            // ★ 恢复默认后系统值是**物理密度**（440），回读刷新 + 草稿归位
                            refreshApplied(ids) { dpi, mixed ->
                                appliedDpi = dpi
                                appliedMixed = mixed
                                // reset 成功且各屏一致 ⇒ 草稿跟着归到那个值（通常 440）
                                if (!mixed && dpi != null) AppPrefs.setDisplayDpi(dpi)
                            }
                            busy = false
                            AppToast.show(
                                context,
                                context.getString(
                                    if (ok) R.string.display_toast_reset else R.string.display_toast_failed,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
            }
        }

        // ------------------------------------------------------- 说明卡
        // ⚠️ 横向边距由 [TextCard] 给（`insideMargin = CARD_H_INSET = 16dp`）⇒ 这里只补**纵向间距**。
        //   ⛔ 别加 `padding(horizontal = …)` —— 会变双层留白（这个坑 [DiagnosticsPage] 里踩过一次，
        //     那边注释留着"原来自画 16dp、当时用的是零内边距的 SectionCard"的记录）。
        // ★ 用 [Note] 而不是自写 `Text(style = body2)`：全工程说明文字都用它（`paragraph` 样式），
        //   自己选样式迟早与别处不一致。
        TextCard(title = stringResource(R.string.display_notes_title)) {
            Note(text = stringResource(R.string.display_note_global))
            Note(
                text = stringResource(R.string.display_note_semantic),
                modifier = Modifier.padding(top = 8.dp),
            )
            Note(
                text = stringResource(R.string.display_note_confirm),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 「当前生效 / 待应用」两行状态。
 *
 * ★ 为什么单独抽出来：这段是本页**唯一**会因状态变化而重排的东西，
 *   混在滑条那张卡里会让"卡里塞了太多东西"（用户 2026-10-06 抱怨过"还是很乱"）。
 *
 * ⚠️ **不加横向 padding** —— 外边那张 `Card` 自带内边距（见调用点的注释），
 *   这里再加一层就会与滑条不对齐。
 *
 * @param applied 各屏一致的生效值；null 表示"没读到"或"屏间不一致"（用 [mixed] 区分）
 * @param mixed true = 两块屏的密度**对不上**（本功能要消灭的状态）⇒ 显式报警，⛔ 不挑一块显示
 */
@Composable
private fun DpiStatusRow(applied: Int?, mixed: Boolean, draft: Int, dirty: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = stringResource(
                R.string.display_dpi_current,
                when {
                    // ⚠️ 顺序要紧：mixed 时 applied 必为 null，得先判 mixed，否则会显示成"未知"
                    mixed -> stringResource(R.string.display_dpi_mixed)
                    applied != null -> applied.toString()
                    else -> stringResource(R.string.display_dpi_unknown)
                },
            ),
            style = MiuixTheme.textStyles.body2,
            // ⚠️ 不一致是**故障态**，用 error 色（主题三件套之一，深浅色都有值）——
            //   与"正常读数"用同一个灰色会被当成普通信息扫过去。
            color = if (mixed) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        if (dirty) {
            Text(
                text = stringResource(R.string.display_dpi_pending, draft),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 回读系统密度并**折叠成界面要的两个状态**（一致值 / 是否不一致）。
 *
 * ★ 抽出来是因为「应用」与「恢复默认」两条路都要做**同一件事**：
 *   逐屏读 → 判是否一致 → 写回两个状态。各写一份迟早分叉（一个判了一致、另一个没判）。
 *
 * ⛔ **别在这里顺手写 [AppPrefs.setDisplayDpi]** —— 两个调用点对草稿的处置**不一样**
 *   （应用后草稿本就等于目标值、不该动；恢复默认后草稿要跟着回到 440），
 *   塞进来就得加开关参数，反而更容易错。
 *
 * @param onResult (一致值 or null, 是否不一致)
 */
private suspend fun refreshApplied(ids: List<Int>, onResult: (Int?, Boolean) -> Unit) {
    val distinct = readDensityByDisplay(ids).values.toSet()
    when {
        distinct.isEmpty() -> onResult(null, false)
        distinct.size == 1 -> onResult(distinct.first(), false)
        else -> onResult(null, true)
    }
}

/**
 * 逐屏读系统**当前生效**的显示密度，返回 `displayId → dpi`。
 *
 * ★ 输出格式（2026-10-12 真机实测）：
 * ```
 * Physical density: 440
 * Override density: 400     ← 有覆盖时才出现这一行
 * ```
 * ⇒ 有 `Override` 就取它，否则取 `Physical`（"从没覆盖过"时回报物理值）。
 *
 * ⚠️ **`-d <id>` 必带**：不带时 `wm density` 只报 display 0（2026-10-12 实测），
 *   而 display 0 会随折叠形态重绑 ⇒ 不带就读错屏。
 *
 * ★★ **为什么要返回整张表、而不是"取第一块"**（曾是错的设计，2026-10-12 实测抓出）：
 *   本机真实出现过 `display 0 = 380 / display 1 = 440` 这种**两块屏不一致**的状态
 *   （由只改一块屏的 `wm density` 造成 —— 正是本功能要消灭的那种情况）。
 *   若只取第一块，界面会**随便挑一块当真值显示**，用户看到"当前生效 380"却在内屏量到 440，
 *   而且这个错值还会被写进草稿 ⇒ 一个会**自我固化**的错误。
 *   ⇒ 返回整张表，让界面能如实报"不一致"。
 *
 * @return `displayId → dpi`；读不到的屏**不出现在表里**（⛔ 别填 0/440 冒充）
 */
private suspend fun readDensityByDisplay(ids: List<Int>): Map<Int, Int> =
    ids.mapNotNull { id ->
        val out = RootShell.runRaw("wm density -d $id") ?: return@mapNotNull null
        val override = Regex("Override density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val physical = Regex("Physical density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        (override ?: physical)?.let { id to it }
    }.toMap()
