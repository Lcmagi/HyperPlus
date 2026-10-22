package cn.dsr213.hyperplus

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================ 采集会话（与 Activity 生命周期解耦）============================
 *
 * ⚠️⚠️ **为什么采集逻辑不在 Activity 里**（2026-10-18 实测教训）：
 *   用户点完「开始」一展开内屏，采集**立刻归零**。
 *   原因：展开 = 屏幕尺寸/密度/方向同时变化 ⇒ Activity 走 `onDestroy → onCreate` 重建
 *   ⇒ 旧实例的 `writer` 被关掉、`running` 归 false、倒计时从头开始。
 *   ⇒ 交互增强功能的**整个实验场景就是"折叠"**，所以"折叠会把实验打断"是致命缺陷，
 *     不是小毛病。
 *
 *   ⇒ 定案：**采集状态与传感器监听全部放在本 object（进程级单例）**。
 *     ① 传感器注册在 object 上，`SensorManager` 用 applicationContext，不随 Activity 销毁注销；
 *     ② Activity 只是**视图**，重建后从本 object 读状态重新渲染（`onTick`）；
 *     ③ 展开/合上/旋转/换密度 → 采集**一行不丢**；哪怕 Activity 真被系统杀掉重建，也不影响。
 *
 *   ★ 判据（MEM-20）：这条路径**必须能在日志里看出来** ——
 *     `采集未中断（Activity 重建后恢复）` 这句就是现场证据，别删。
 *
 * ⛔ 本类只做采样与落盘，**不做任何判定、不写任何系统状态**。
 */
internal object HandProbeSession : SensorEventListener {

    const val TAG = "HyperPlusHandProbe"

    // ---- 关注的内核传感器 type（vendor 私有 type 直接用整数，Android 没有对应常量） ----
    private const val T_SAR = 33171015            // sx937x
    private const val T_HOLDER = 33171125          // holder_detect
    private const val T_LARGE_AREA = 33171031      // large_area_detect
    private const val T_FOLD_STATUS = 33171087     // fold_status
    private const val T_SAR_ALGO = 33171028        // sar_algo（SAR 算法层输出）
    private const val T_DEVICE_ORIENT = 27         // android.sensor.device_orientation

    /**
     * 采样周期：100ms（10Hz）。
     * `sx937x` 硬件标称 5Hz，实测能到 12Hz，系统会夹到它能给的最快值。
     */
    private const val PERIOD_US = 100_000

    private const val CSV_HEADER =
        "t_sec,phase,type,sensor,v0,v1,v2,v3,v4,v5,v6,v7,v8,v9,v10,v11,v12,v13,v14,v15"

    /**
     * ★★ 2026-10-18 新增：**触摸 IC 的 palm_sensor 走 sysfs，SensorManager 里根本没有它们**。
     *
     * 背景：`sx937x`（SAR，33171015）已被定标实验**证伪** ——
     *   展开态左右手差异 22 计数 / 满量程 7,800 = 0.28%，而同一只手不动时它自己就漂 24 计数；
     *   换手瞬间没有台阶，四段读数单调缓变 ⇒ 那个差异是时间趋势，不是手。
     * ⇒ 本机剩下的握持信号只剩 `/sys/class/touch/touch_dev/` 下这几个节点。
     *   它们**没有对应的 Sensor 类型**，`getSensorList` 里列不出来，只能 `cat` sysfs。
     *
     * ⚠️ 每次采样单独发一条 `su -c cat` 是不可行的（实测单次 su 约 200ms，2Hz 就吃满）。
     *    ⇒ 改成**一个常驻 su 进程**跑 shell 循环，stdout 一行一次采样，由后台线程读走。
     *    ⛔ 时序不要用 shell 里 `date` 打点（toybox 的 %N 不稳），
     *       统一用**读到那一行的时刻** `elapsedRealtimeNanos()` 当时间戳 —— 200ms 周期下延迟是噪声级。
     *
     * id 用 9001+ 而不是传感器 type，避免和 `dumpsys sensorservice` 的编号撞车。
     */
    private val SYSFS_NODES = listOf(
        9001 to "palm_sensor",
        9002 to "palm_sensor_1",
        9003 to "touch_finger_status",
        9004 to "weak_doubletap_sensor",
        9005 to "touch_doze_analysis",
    )
    private const val SYSFS_DIR = "/sys/class/touch/touch_dev"
    private const val SYSFS_PERIOD = "0.2"
    private const val SYSFS_STOP_FLAG = "/data/local/tmp/hp_probe_stop"

    /**
     * 实验脚本：**每步由用户点「下一步」推进，⛔ 没有任何自动计时推进**。
     *
     * 2026-10-18 用户原话：「**不要一个接一个不停的测，每个阶段测完，让我点击确认再测下一个，
     * 不然来不及**」⇒ 折叠本身要花时间（还要换手、换姿势），固定 8 秒自动切档必然串味。
     * 括起来的秒数只是**界面上的建议时长**（提示"握够了吗"），不参与任何判定。
     *
     * ★ 好处不只是"来得及"：phase 号由**用户点击的那一刻**切换，
     *   所以 CSV 里每一段的时间边界是**人为确认过的**，比按秒数猜切点准得多。
     */
    val phases: List<Pair<String, Int>> = listOf(
        "① 合上 · 只用左手握" to 8,
        "② 合上 · 只用右手握" to 8,
        "③ 展开 · 双手握" to 8,
        "④ 展开 · 只用左手握" to 6,
        "⑤ 展开 · 只用右手握" to 6,
    )

    private val main = Handler(Looper.getMainLooper())
    private var sm: SensorManager? = null

    /** 本次实验里真正注册成功的传感器（key = type）。 */
    private val registered = LinkedHashMap<Int, Sensor>()

    /** 每个 type 收到的事件数，跑完直接打出来 —— 「注册成功但 0 事件」是重要结论。 */
    private val eventCount = LinkedHashMap<Int, Int>()

    /** 每个 type 最近一次读数（界面上实时显示，方便当场发现「全 0」）。 */
    private val lastValues = LinkedHashMap<Int, FloatArray>()

    private var writer: BufferedWriter? = null
    var csvFile: File? = null
        private set

    private var t0Nanos = 0L

    /** 当前阶段从哪一刻开始（只用于界面显示"这一步握了几秒"，⛔ 不参与推进）。 */
    private var phaseStartNanos = 0L

    var running = false
        private set
    var currentPhase = -1
        private set

    private var linesSinceFlush = 0

    /** CSV 写入锁：传感器回调在 main，sysfs 在后台线程 ⇒ `BufferedWriter` 不是线程安全的。 */
    private val ioLock = Any()

    private var sysfsProc: Process? = null
    private var sysfsThread: Thread? = null
    var sysfsAlive = false
        private set
    var sysfsNote = ""
        private set

    /** sysfs 最近一次采样（节点名 → 原始字符串），给 UI 实时显示。 */
    private var sysfsLast: LinkedHashMap<String, String> = LinkedHashMap()

    fun sysfsLive(): Map<String, String> = sysfsLast

    /** 注册结果摘要（给 UI 看）。 */
    var registerNote = ""
        private set

    /**
     * UI 刷新钩子。Activity 重建后在这里重新挂上，销毁时置 null。
     * ⚠️ 采集**绝不依赖**它 —— 它是 null 时数据照样往 CSV 里写。
     */
    var onTick: (() -> Unit)? = null

    // ---- 只读访问器，给 Activity 渲染用 ----
    fun registeredSensors(): Map<Int, Sensor> = registered
    fun counts(): Map<Int, Int> = eventCount
    fun lastValuesOf(type: Int): FloatArray? = lastValues[type]
    fun elapsedSec(): Double =
        if (!running) 0.0 else (SystemClock.elapsedRealtimeNanos() - t0Nanos) / 1e9

    /** 当前这一步已经握了几秒（纯显示，⛔ 不触发任何推进）。 */
    fun phaseElapsedSec(): Double =
        if (!running) 0.0 else (SystemClock.elapsedRealtimeNanos() - phaseStartNanos) / 1e9

    /**
     * 用户点「这一步做完了」⇒ 进下一步；已是最后一步 ⇒ 收尾保存。
     * ★ 这是**唯一**的阶段推进入口（没有任何定时器能推进阶段）。
     */
    fun nextPhase() {
        if (!running) return
        val next = currentPhase + 1
        if (next >= phases.size) {
            stop("用户确认最后一步完成")
            return
        }
        advancePhase(next)
        onTick?.invoke()
    }

    fun shortName(type: Int): String = when (type) {
        T_SAR -> "sx937x(SAR)"
        T_HOLDER -> "holder_detect"
        T_LARGE_AREA -> "large_area"
        T_FOLD_STATUS -> "fold_status"
        T_SAR_ALGO -> "sar_algo"
        T_DEVICE_ORIENT -> "device_orient"
        9001 -> "palm_sensor"
        9002 -> "palm_sensor_1"
        9003 -> "touch_finger_status"
        9004 -> "weak_doubletap_sensor"
        9005 -> "touch_doze_analysis"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_HINGE_ANGLE -> "hinge_angle"
        Sensor.TYPE_PROXIMITY -> "proximity"
        else -> "type=$type"
    }

    /**
     * 开始一轮采集。传 **applicationContext**（Activity 的 context 会随重建失效）。
     */
    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val mgr = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sm = mgr

        running = true
        currentPhase = -1
        registered.clear()
        eventCount.clear()
        lastValues.clear()
        linesSinceFlush = 0

        // ---- 落盘 ----
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "handprobe_$stamp.csv")
        csvFile = f
        writer = BufferedWriter(FileWriter(f))
            .also { it.write(CSV_HEADER); it.newLine() }

        // ---- 注册全部候选传感器 ----
        val all = mgr.getSensorList(Sensor.TYPE_ALL)
        val want = listOf(
            T_SAR, T_HOLDER, T_LARGE_AREA, T_FOLD_STATUS, T_SAR_ALGO,
            Sensor.TYPE_GRAVITY, Sensor.TYPE_HINGE_ANGLE, Sensor.TYPE_PROXIMITY,
            T_DEVICE_ORIENT,
        )
        val sb = StringBuilder()
        for (type in want) {
            val s = all.firstOrNull { it.type == type }
            if (s == null) {
                sb.append("缺 type=$type；")
                Log.w(TAG, "本机没有 type=$type")
                continue
            }
            val ok: Boolean = runCatching {
                mgr.registerListener(this, s, PERIOD_US, main)
            }.getOrDefault(false)
            eventCount[type] = 0
            if (ok) registered[type] = s else sb.append("注册失败 ${s.name}；")
            Log.i(TAG, "type=$type name=${s.name} 注册=${if (ok) "成功" else "失败"} minDelay=${s.minDelay}us")
        }
        registerNote = if (sb.isEmpty()) "全部候选已注册" else "⚠️ $sb"

        t0Nanos = SystemClock.elapsedRealtimeNanos()
        startSysfs()
        advancePhase(0)
        main.postDelayed(uiTicker, 250)

        Log.i(TAG, "开跑，落盘 = ${f.absolutePath}")
        onTick?.invoke()
    }

    /**
     * 收尾：注销监听、关文件、把「每个传感器收到多少事件」打进 logcat。
     *
     * ⚠️ `from` 是**调用来源**，只为排查「谁把采集停掉了」——
     *    2026-10-18 实测过一次「按 HOME 后采集自己结束了」，当时没有来源日志只能靠猜。
     */
    fun stop(from: String = "unknown") {
        if (!running) return
        running = false
        val caller = Throwable().stackTrace.drop(1).take(4)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        Log.i(TAG, "stop() 来源=$from | $caller")

        main.removeCallbacks(uiTicker)
        stopSysfs()
        runCatching { sm?.unregisterListener(this) }
        runCatching { synchronized(ioLock) { writer?.flush(); writer?.close() } }
        writer = null

        val sb = StringBuilder("【采集结束】\n")
        for ((type, s) in registered) {
            sb.append("  ${shortName(type)}(${s.name}) 事件=${eventCount[type] ?: 0}\n")
        }
        for (type in eventCount.keys) {
            if (!registered.containsKey(type)) sb.append("  type=$type 未注册成功\n")
        }
        sb.append("  sysfs：${sysfsNote.ifEmpty { "已采样" }}\n")
        for ((k, v) in sysfsLast) sb.append("    $k=$v\n")
        sb.append("  文件=${csvFile?.absolutePath}\n")
        Log.i(TAG, sb.toString())

        onTick?.invoke()
    }

    /**
     * 只管**刷新界面**（显示"这一步握了几秒"）。
     *
     * ⛔⛔ **它绝不推进阶段、也绝不因时间到而结束采集** ——
     *   2026-10-18 用户明确要求「每个阶段测完，让我点击确认再测下一个，不然来不及」。
     *   定时推进会把两段动作切在一起，数据直接废掉。
     *
     * ⚠️ 用**固定的 Runnable 引用**，⛔ 不许写 `removeCallbacksAndMessages(null)` ——
     *    那会清掉该 Handler 的**全部**消息（连 View 相关的都一起干掉）。
     */
    private val uiTicker = object : Runnable {
        override fun run() {
            if (!running) return
            onTick?.invoke()
            main.postDelayed(this, 250)
        }
    }

    private fun advancePhase(i: Int) {
        currentPhase = i
        phaseStartNanos = SystemClock.elapsedRealtimeNanos()
        Log.i(TAG, "阶段 $i：${phases[i].first}（建议 ${phases[i].second}s，等用户确认）")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val type = event.sensor.type
        eventCount[type] = (eventCount[type] ?: 0) + 1
        lastValues[type] = event.values.copyOf()

        val w = writer ?: return
        val t = (SystemClock.elapsedRealtimeNanos() - t0Nanos) / 1e9
        val sb = StringBuilder()
        sb.append("%.3f".format(Locale.US, t)).append(',')
            .append(currentPhase).append(',')
            .append(type).append(',')
            .append(event.sensor.name.replace(',', ' '))
        for (k in 0 until 16) {
            sb.append(',')
            if (k < event.values.size) sb.append("%.2f".format(Locale.US, event.values[k]))
        }
        synchronized(ioLock) {
            w.write(sb.toString())
            w.newLine()
            if (++linesSinceFlush >= 20) {
                w.flush()
                linesSinceFlush = 0
            }
        }
    }

    /**
     * 启动 sysfs 采样：一个常驻 `su -c` 进程跑循环，stdout 一行 = 一次采样。
     *
     * ⛔ 不许改成"每次采样发一条 su"：单次 su 约 200ms，5 个节点轮一遍就把 200ms 周期吃满。
     * ★ 停法：先 `touch` 停止标志文件让循环自己退出，再 `destroy` 兜底 ——
     *   只 destroy 的话 sh 子进程可能变孤儿继续跑（这就是 10-12 清理残留进程那个坑）。
     */
    private fun startSysfs() {
        if (sysfsProc != null) return
        val nodes = SYSFS_NODES.joinToString(" ") { it.second }
        val script = "B=" + SYSFS_DIR + "; rm -f " + SYSFS_STOP_FLAG + "; " +
            "while [ ! -f " + SYSFS_STOP_FLAG + " ]; do " +
            "L=''; " +
            "for n in " + nodes + "; do " +
            "L=\"\$L\$(cat \$B/\$n 2>/dev/null | tr -d ' \\n'),\"; " +
            "done; " +
            "echo \"\$L\"; sleep " + SYSFS_PERIOD + "; done"
        val proc = runCatching {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        }.getOrNull()
        if (proc == null) {
            sysfsNote = "⚠️ su 起不来，sysfs 未采样"
            Log.w(TAG, sysfsNote)
            return
        }
        sysfsProc = proc
        sysfsAlive = true
        sysfsLast.clear()
        Log.i(TAG, "sysfs 采样启动：$nodes")
        sysfsThread = Thread {
            var bad = 0
            runCatching {
                val r = proc.inputStream.bufferedReader()
                while (true) {
                    val line = r.readLine() ?: break
                    if (!running) break
                    if (!line.contains(",")) {
                        // su 的报错（未授权 / 找不到节点）：记下来，别当数据写
                        if (bad++ < 3) Log.w(TAG, "sysfs 非数据行：$line")
                        sysfsNote = "⚠️ " + line.take(60)
                        if (bad > 6) break
                        continue
                    }
                    val parts = line.split(',')
                    val t = (SystemClock.elapsedRealtimeNanos() - t0Nanos) / 1e9
                    val map = LinkedHashMap<String, String>()
                    for ((i, node) in SYSFS_NODES.withIndex()) {
                        val raw = parts.getOrNull(i)?.trim().orEmpty()
                        if (raw.isEmpty()) continue
                        map[node.second] = raw
                        writeSysfsRow(t, node.first, node.second, raw)
                    }
                    if (map.isNotEmpty()) sysfsLast = map
                }
            }.onFailure { Log.w(TAG, "sysfs 线程退出：${it.message}") }
        }.apply { isDaemon = true; name = "hp-sysfs"; start() }
    }

    private fun stopSysfs() {
        sysfsAlive = false
        runCatching {
            ProcessBuilder("su", "-c", "touch " + SYSFS_STOP_FLAG)
                .redirectErrorStream(true).start().waitFor()
        }
        runCatching { sysfsProc?.destroy() }
        runCatching { sysfsThread?.interrupt() }
        sysfsProc = null
        sysfsThread = null
        Log.i(TAG, "sysfs 采样已停")
    }

    private fun writeSysfsRow(t: Double, type: Int, name: String, value: String) {
        val w = writer ?: return
        synchronized(ioLock) {
            runCatching {
                w.write("%.3f".format(Locale.US, t) + "," + currentPhase + "," + type + "," +
                    name.replace(',', ' ') + "," + value)
                w.newLine()
                if (++linesSinceFlush >= 20) {
                    w.flush()
                    linesSinceFlush = 0
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * 左右手识别的**信号定标探针**（2026-10-18，只读，⛔ 不写任何系统状态）。
 *
 * ============================ 为什么需要它 ============================
 * 「交互增强 · 左右手识别」的判据全部依赖硬件信号，而其中主力信号
 * `sx937x`（`xiaomi.sensor.sar`，type 33171015）虽然实测能读到 **16 路电容原始值**，
 * 但**每个字段是什么含义没人知道**（SX937x 典型是 CS0~CS4 的 raw/avg/diff，本机只启用了一部分）。
 *
 * ⇒ 不做**定标实验**就写判据，等于凭猜写死系数 —— 本项目明令禁止。
 * 本探针的唯一职责：**把 5 种握持姿势下的原始信号完整落盘**，供事后比对。
 *
 * ============================ 它采什么、为什么是这些 ============================
 * 华为智感握姿的公开原理是「手掌遮挡部分天线 ⇒ 信号变化 ⇒ 多路融合判左右」。
 * 本机拿不到天线阻抗，能找到的**物理同构**候选全部注册上，一次实验拿全：
 *
 * | type     | 名称                | 为什么要在 |
 * |----------|---------------------|-----------|
 * | 33171015 | `sx937x`（SAR）     | ⭐ 主力：贴在天线附近的电容电极，16 路原始值 |
 * | 33171125 | `holder_detect`     | 厂商自带握持检测，语义未知但名字最对题 |
 * | 33171031 | `large_area_detect` | 大面积接触（掌心压屏），内屏方案的关键 |
 * | 33171087 | `fold_status`       | 形态，用来事后校验每一段到底是不是我以为的形态 |
 * | 9        | 重力                | 弱特征（右手握机通常右倾），也是形态的交叉校验 |
 * | 36       | `hinge_angle`       | 折叠角，同上 |
 * | 8        | proximity           | 兜底：贴近检测，随便看看有没有信息量 |
 *
 * ============================ 它怎么用 ============================
 * `adb shell am start -n cn.dsr213.hyperplus/.HandProbeActivity`
 * → 点「开始采集」→ 跟着屏幕上的大字提示做动作 → 结束后自动落盘。
 *
 * 落盘位置：`/sdcard/Android/data/cn.dsr213.hyperplus/files/handprobe_<时间戳>.csv`
 * 取回：`adb pull //sdcard/Android/data/cn.dsr213.hyperplus/files/`
 *
 * ⚠️ 三种「没数据」的排法（这是本探针**最重要**的输出，别当成失败就跳过）：
 *   ① 某个传感器 `注册失败` ⇒ 该 type 在本进程不可见（权限/可见性），要改到 SystemUI 进程采；
 *   ② 注册成功但 `事件=0` ⇒ 驱动不采样（如 `sx937x` 平时 `active-count=0`），要换触发条件；
 *   ③ 有事件但**全 0** ⇒ 该通道未启用，换下一个候选。
 *
 * ============================ 2026-10-18 修：折叠会把实验打断 ============================
 * 现象：点「开始」后一展开内屏，采集归零。
 * 原因：展开 ⇒ 屏幕尺寸/密度/方向同时变 ⇒ Activity 重建 ⇒ writer 被关、running 归 false。
 * 修法（两道）：
 *   ① `AndroidManifest` 给本 Activity 补 `configChanges`（与 MainActivity 一致）；
 *   ② **根本解**：采集状态全部搬到 [HandProbeSession]（进程级 object）——
 *      Activity 只是视图，重建后从 session 重新渲染，采集一行不丢。
 *
 * ⛔ 本类**不进正式导航、不做任何判定**，只负责显示与触发。
 */
class HandProbeActivity : Activity() {

    companion object {
        private const val TAG = "HyperPlusHandProbe"
    }

    private lateinit var tvPhase: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvLive: TextView
    private lateinit var btnStart: Button
    private lateinit var btnNext: Button

    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        // ⚠️ 重建分支必须留痕 —— 不然「到底有没有被重建」永远只能猜
        if (HandProbeSession.running) {
            Log.i(
                TAG,
                "采集未中断（Activity 重建后恢复）：已进行 %.1f 秒，当前阶段 %d".format(
                    Locale.US, HandProbeSession.elapsedSec(), HandProbeSession.currentPhase
                )
            )
        }
        attachUi()
    }

    private fun attachUi() {
        HandProbeSession.onTick = { runOnUiThread { render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        attachUi()
    }

    override fun onDestroy() {
        // ⛔ 这里**绝不能**注销传感器或关文件 —— 那正是「一展开就归零」的原因。
        if (HandProbeSession.onTick != null) HandProbeSession.onTick = null
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================ UI ================================
    // ⚠️ 刻意用传统 View 而不是 Compose：本探针是**一次性调试工具**，不该为了它去动
    //    MiuiX 主题/导航那套东西。它连 `HyperPlusApp` 都不进。

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.BLACK)
        }

        tvPhase = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            text = "左右手信号定标"
        }
        root.addView(tvPhase, lp(match, wrap))

        tvCountdown = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD24A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            text = "共 5 步 · 每步做完点下面的按钮（展开/合上不会中断）"
        }
        root.addView(tvCountdown, lp(match, wrap).apply { topMargin = dp(6) })

        // ★ 主按钮：**每一步都由它推进**（用户要求：测完一步、点确认、再测下一步）
        btnNext = Button(this).apply {
            text = "这一步做完了，下一步"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnClickListener {
                HandProbeSession.nextPhase()
                render()
            }
        }
        root.addView(btnNext, lp(match, dp(72)).apply { topMargin = dp(14) })

        btnStart = Button(this).apply {
            text = "开始采集"
            setOnClickListener {
                if (HandProbeSession.running) {
                    HandProbeSession.stop("点按钮停止")
                } else {
                    HandProbeSession.start(this@HandProbeActivity)
                }
                render()
            }
        }
        root.addView(btnStart, lp(match, wrap).apply { topMargin = dp(8) })

        tvLive = TextView(this).apply {
            setTextColor(Color.parseColor("#8FE388"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
            text = "点开始后这里滚实时读数"
        }
        root.addView(ScrollView(this).apply {
            addView(tvLive)
            isFillViewport = true
        }, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(8) })

        return root
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================================ 渲染 ================================

    private fun render() {
        val s = HandProbeSession
        if (s.running) {
            val idx = s.currentPhase.coerceIn(0, s.phases.lastIndex)
            val held = s.phaseElapsedSec()
            val suggest = s.phases[idx].second

            btnStart.visibility = View.VISIBLE
            btnStart.text = "停止并保存"
            btnNext.visibility = View.VISIBLE
            btnNext.isEnabled = true
            btnNext.text = if (idx == s.phases.lastIndex) "✅ 这是最后一步，做完了保存"
            else "这一步做完了，下一步（还剩 ${s.phases.size - 1 - idx} 步）"

            tvPhase.text = s.phases[idx].first
            tvCountdown.text = buildString {
                append("第 ${idx + 1}/${s.phases.size} 步 · 已握 ${"%.0f".format(Locale.US, held)} 秒")
                if (held < suggest) append("（建议 ≥${suggest} 秒，不急，握够了再点）")
                else append(" · 已够时长，可以点下一步")
                append(" · 合计 ${"%.0f".format(Locale.US, s.elapsedSec())} 秒")
            }
        } else {
            btnStart.visibility = View.VISIBLE
            btnStart.text = if (s.csvFile == null) "开始采集" else "重新采集"
            btnNext.visibility = View.GONE
            tvPhase.text = if (s.csvFile == null) "左右手信号定标" else "采集结束"
            tvCountdown.text = when {
                s.csvFile != null -> "已保存：${s.csvFile?.name}"
                s.registerNote.isNotEmpty() -> s.registerNote
                else -> "共 5 步 · 每步做完点下面的按钮（展开/合上不会中断）"
            }
        }
        refreshLive()
    }

    private fun refreshLive() {
        val s = HandProbeSession
        val sb = StringBuilder()
        for ((type, sensor) in s.registeredSensors()) {
            val n = s.counts()[type] ?: 0
            val v = s.lastValuesOf(type)
            sb.append("%-18s n=%-4d ".format(Locale.US, s.shortName(type), n))
            if (v == null) sb.append("(无读数)")
            else sb.append(v.take(6).joinToString(",") { "%.1f".format(Locale.US, it) })
            sb.append('\n')
        }
        for ((k, v) in s.sysfsLive()) {
            sb.append("%-18s n=—    %s".format(Locale.US, k, v)).append('\n')
        }
        tvLive.text = if (sb.isEmpty()) "点开始后这里滚实时读数" else sb.toString()
    }

    /**
     * 返回键：采集中**不退出、也不停**，只提示一句。
     * ⚠️ 2026-10-18：原来这里会 `stop()`，实测「按 HOME 后采集莫名结束」——
     *    怀疑就是这条路径被系统在别的场景下走到了。采集中一律不停，来源日志会告诉我们真相。
     */
    @Suppress("DEPRECATION")
    @Deprecated("Android 13+ 走 OnBackInvokedDispatcher，本探针只做调试，保持简单")
    override fun onBackPressed() {
        if (HandProbeSession.running) {
            Log.i(TAG, "返回键被按下，采集进行中 ⇒ 不停、不退出")
            tvCountdown.text = "采集中：要停止请点上面的按钮"
            return
        }
        super.onBackPressed()
    }
}
