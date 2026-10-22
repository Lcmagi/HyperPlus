package cn.dsr213.hyperplus

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import dalvik.system.DexClassLoader

/**
 * UWB 宿主能力探针（2026-10-19）。
 *
 * ============================ 它要回答什么 ============================
 * 记忆库 MEM-24 的判据是「按依赖选宿主」。在把左右手识别塞进某个进程之前，
 * 必须先证明**那个进程真的具备能力**。本界面是这么用的：
 *
 * ```
 * adb shell am start -n cn.dsr213.hyperplus/.UwbHostProbeActivity
 * adb logcat -s HyperPlusUwbProbe
 * ```
 *
 * 依次探测：
 *  ① 本进程 uid / 进程名（确认跑在哪个进程里，`android:process` 一改就看得出来）
 *  ② `UWB_RANGING` 是否已授予（AOSP 标准通道的门槛，dangerous）
 *  ③ `/system/framework/com.nxp.uwb.jar` 能否被 `DexClassLoader` 加载并取到 `NxpUwbManager`
 *     （★ 它**不在** BOOTCLASSPATH，所以这一步不成立，整条 NXP 雷达路线就作废）
 *  ④ `ServiceManager.getService("VendorUwbOemService")` 能否拿到 binder
 *     （NXP 私有服务；✅ 字节码里 0 次 `enforceCallingPermission`，理论上谁都能 bind）
 *  ⑤ 公开 API `android.uwb.UwbManager` 能否拿到（对照组：不碰任何隐藏 API 时通道是否通）
 *
 * ⛔ 全程只读：不建会话、不发 UCI、不改任何系统状态。
 */
class UwbHostProbeActivity : Activity() {

    private val sb = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply { textSize = 12f; setPadding(40, 120, 40, 40) }
        setContentView(ScrollView(this).apply { addView(tv) })

        Thread {
            runCatching { probe() }
                .onFailure { note("探测整体异常：$it") }
            runOnUiThread { tv.text = sb.toString() }
        }.start()
    }

    private fun note(s: String) {
        synchronized(sb) { sb.append(s).append('\n') }
        Log.i(TAG, s)
    }

    private fun probe() {
        note("=== UWB 宿主能力探测 ===")
        note("进程=${applicationContext.packageName} pid=${Process.myPid()} uid=${Process.myUid()}")
        note("进程名=${runCatching { Application.getProcessName() }.getOrNull()}")
        note("SELinux 域见 adb shell ps -AZ")

        // ② 权限
        val perm = if (checkSelfPermission(android.Manifest.permission.UWB_RANGING) ==
            PackageManager.PERMISSION_GRANTED
        ) "已授予" else "未授予（可运行时申请）"
        note("② UWB_RANGING：$perm")

        // ③ NXP jar 可加载性
        runCatching {
            val dir = codeCacheDir.absolutePath
            val dcl = DexClassLoader("/system/framework/com.nxp.uwb.jar", dir, null, classLoader)
            val c = Class.forName("com.nxp.uwb.NxpUwbManager", false, dcl)
            note("③ NxpUwbManager：可加载 $c")
            val r = runCatching { Class.forName("com.nxp.uwb.radar.UwbRadarManager", false, dcl) }
            note("③ UwbRadarManager：${if (r.isSuccess) "可加载" else "失败 ${r.exceptionOrNull()?.javaClass?.simpleName}"}")
        }.onFailure {
            note("③ NXP jar：失败 ${it.javaClass.simpleName}: ${it.message}")
        }

        // ④ NXP 私有服务 binder
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val m = sm.getDeclaredMethod("getService", String::class.java)
            m.isAccessible = true
            val b = m.invoke(null, "VendorUwbOemService")
            note("④ VendorUwbOemService：${if (b == null) "null（未注册或被拦）" else "ok ${b.javaClass.name}"}")
        }.onFailure {
            note("④ VendorUwbOemService：异常 ${it.javaClass.simpleName}: ${it.message}")
        }

        // ⑤ 公开 API 对照
        runCatching {
            val cls = Class.forName("android.uwb.UwbManager")
            val svc = getSystemService(cls)
            note("⑤ android.uwb.UwbManager：${if (svc == null) "null（可能缺权限）" else "ok"}")
        }.onFailure {
            note("⑤ android.uwb.UwbManager：异常 ${it.javaClass.simpleName}: ${it.message}")
        }

        note("=== 探测结束 ===")
    }

    companion object {
        private const val TAG = "HyperPlusUwbProbe"
    }
}
