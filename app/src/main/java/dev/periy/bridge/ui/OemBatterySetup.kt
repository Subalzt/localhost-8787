package dev.periy.bridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Getting past OEM background-process killers.
 *
 * A foreground service plus a battery-optimisation exemption is what the platform says
 * is sufficient. On a large fraction of shipped Android devices it is not: several
 * vendors run their own process reaper above the framework, with its own allow-list that
 * no public API can read or write. The only thing an app can do is walk the user to the
 * right settings screen and explain why.
 *
 * Those screens are private vendor activities. They get renamed and removed between
 * releases, and starting one that no longer exists is an immediate crash, so every
 * candidate here is resolved through the package manager before it is offered. A device
 * with no vendor screen simply sees the two platform entries, which always resolve.
 *
 * The list is keyed loosely by manufacturer, but every candidate is probed regardless of
 * vendor -- rebadged and regional variants routinely report a manufacturer string that
 * does not match the ROM they are actually running.
 */
object OemBatterySetup {

    data class Step(val title: String, val detail: String, val intent: Intent)

    private data class Candidate(
        val vendors: List<String>,
        val title: String,
        val detail: String,
        val pkg: String,
        val cls: String,
    )

    private val CANDIDATES = listOf(
        // Xiaomi / Redmi / POCO -- MIUI and HyperOS
        Candidate(
            listOf("xiaomi", "redmi", "poco"),
            "Allow autostart",
            "HyperOS and MIUI will not let the app restart itself after a reboot or a memory purge unless autostart is on.",
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        Candidate(
            listOf("xiaomi", "redmi", "poco"),
            "Set battery saver to No restrictions",
            "Find Localhost 8787 in the list and choose No restrictions rather than Battery saver.",
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        ),
        // Oppo / Realme / OnePlus -- ColorOS
        Candidate(
            listOf("oppo", "realme", "oneplus"),
            "Allow autostart",
            "ColorOS keeps a startup allow-list of its own, separate from Android battery settings.",
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        Candidate(
            listOf("oppo", "realme", "oneplus"),
            "Allow autostart (older ColorOS)",
            "The same list, in the location older builds use.",
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        Candidate(
            listOf("oppo", "realme"),
            "Allow autostart (legacy)",
            "The same list again, on older Oppo builds.",
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        Candidate(
            listOf("oneplus"),
            "Turn off advanced optimisation",
            "OxygenOS keeps a second kill switch inside its own security app.",
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        // Vivo / iQOO
        Candidate(
            listOf("vivo", "iqoo"),
            "Allow background startup",
            "Funtouch OS and OriginOS block background startup by default.",
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        Candidate(
            listOf("vivo", "iqoo"),
            "Add to the high background power allow-list",
            "Without this, a transfer is killed once the screen has been off for a while.",
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        // Huawei / Honor
        Candidate(
            listOf("huawei", "honor"),
            "Set app launch to Manage manually",
            "Then turn on auto-launch, secondary launch and run in background.",
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        Candidate(
            listOf("huawei", "honor"),
            "Add to protected apps",
            "Older EMUI calls the same list Protected apps.",
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        // Samsung
        Candidate(
            listOf("samsung"),
            "Remove from sleeping apps",
            "Device care puts unused apps to sleep, which ends a transfer in progress.",
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        // Asus
        Candidate(
            listOf("asus"),
            "Add to the auto-start manager",
            "ZenUI keeps its auto-start list inside Mobile Manager.",
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.entry.FunctionActivity",
        ),
        // Meizu
        Candidate(
            listOf("meizu"),
            "Allow background running",
            "Flyme calls this smart background management.",
            "com.meizu.safe",
            "com.meizu.safe.permission.SmartBGActivity",
        ),
        // Transsion -- Tecno, Infinix, itel
        Candidate(
            listOf("tecno", "infinix", "itel", "transsion"),
            "Allow background activity",
            "HiOS and XOS ship a Phone Master power manager with an allow-list of its own.",
            "com.transsion.phonemaster",
            "com.cyin.himgr.autostart.AutoStartActivity",
        ),
        // HMD / Nokia
        Candidate(
            listOf("hmd global", "nokia"),
            "Add a power saver exception",
            "Some Nokia builds add a vendor power saver on top of the Android one.",
            "com.evenwell.powersaving.g3",
            "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
        ),
    )

    /**
     * The steps worth showing on this specific device, vendor-specific ones first.
     * Anything that does not resolve is dropped rather than shown and crashed into.
     */
    fun steps(ctx: Context): List<Step> {
        val vendor = (Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()).lowercase()
        val vendorSteps = CANDIDATES
            .sortedByDescending { c -> if (c.vendors.any { it in vendor }) 1 else 0 }
            .mapNotNull { c ->
                val intent = Intent().setComponent(ComponentName(c.pkg, c.cls))
                if (resolves(ctx, intent)) Step(c.title, c.detail, intent) else null
            }
        return vendorSteps + platformSteps(ctx)
    }

    /** Available on every device, whatever the ROM. */
    private fun platformSteps(ctx: Context): List<Step> = buildList {
        val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (resolves(ctx, batteryIntent)) {
            add(
                Step(
                    "Set battery usage to Unrestricted",
                    "The platform's own setting. Find Localhost 8787 in the list and allow it to run in the background.",
                    batteryIntent,
                )
            )
        }
        add(
            Step(
                "Open app info",
                "Battery and background restrictions for this app, wherever this device keeps them.",
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null),
                ),
            )
        )
    }

    /**
     * HyperOS and MIUI keep their own Autostart switch (app-op 10008); off, they refuse to start
     * the app's notification listener again after an update ("AutoStartManagerService: Reject
     * service"), so the laptop gets no notifications. False where there is no such switch.
     */
    fun autostartBlocked(ctx: Context): Boolean = runCatching {
        val vendor = (Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()).lowercase()
        if (listOf("xiaomi", "redmi", "poco").none { it in vendor }) return false
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val mode = android.app.AppOpsManager::class.java
            .getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            .invoke(ops, 10008, android.os.Process.myUid(), ctx.packageName) as Int
        mode != android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Where Autostart is switched on for the app, on HyperOS and MIUI; app info elsewhere. */
    fun autostartIntent(ctx: Context): Intent {
        val i = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        return if (resolves(ctx, i)) i else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
    }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * The direct exemption prompt, offered once from the main screen. If a ROM refuses to
     * surface it, the settings list above is the fallback.
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context): Intent? {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", ctx.packageName, null),
        )
        return if (resolves(ctx, intent)) intent else null
    }

    private fun resolves(ctx: Context, intent: Intent): Boolean = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.resolveActivity(intent, 0) != null
    }.getOrDefault(false)
}
