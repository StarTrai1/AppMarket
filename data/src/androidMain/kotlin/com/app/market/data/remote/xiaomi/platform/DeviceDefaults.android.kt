package com.app.market.data.remote.xiaomi.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.app.market.domain.model.market.AppSource
import java.util.Locale

internal class AndroidDeviceDefaultsDataSource(
    private val context: Context,
) : DeviceDefaultsDataSource {
    override fun current(): DeviceDefaults {
        val osIncremental = Build.VERSION.INCREMENTAL
        val miOsIncremental = systemProperty("ro.mi.os.version.incremental")
        val osName = systemProperty("ro.mi.os.version.name")
        val osCode = systemProperty("ro.mi.os.version.code")
        val miuiName = systemProperty("ro.miui.ui.version.name")
        val miuiCode = systemProperty("ro.miui.ui.version.code")

        val model = Build.MODEL
        val device = Build.DEVICE
        val isOppoFamily = isOppoFamilyDevice(Build.MANUFACTURER, Build.BRAND)
        val isVivoFamily = isVivoFamilyDevice(Build.MANUFACTURER, Build.BRAND, Build.DEVICE)
        val isSamsungFamily = isSamsungFamilyDevice(Build.MANUFACTURER, Build.BRAND)
        val isHonorFamily = isHonorFamilyDevice(Build.MANUFACTURER, Build.BRAND)
        val isHuaweiFamily = isHuaweiFamilyDevice(Build.MANUFACTURER, Build.BRAND)
        val oplusRom = systemProperty("ro.build.version.oplusrom")
            .ifBlank { systemProperty("ro.build.version.opporom") }
            .ifBlank { systemProperty("ro.build.version.realmerom") }
        val oplusOta = systemProperty("ro.build.version.ota")
        val isXiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco") ||
                miuiName.isNotBlank()
        val isComplete = model.isNotBlank() && device.isNotBlank() &&
                miuiName.isNotBlank() &&
                miOsIncremental.isNotBlank()
        val isOppoComplete = model.isNotBlank() && device.isNotBlank() &&
                Build.VERSION.RELEASE.isNotBlank()
        val isVivoComplete = model.isNotBlank() && device.isNotBlank() &&
                Build.VERSION.RELEASE.isNotBlank() && Build.VERSION.SDK_INT > 0

        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val resolution = when {
            w <= 0 || h <= 0 -> ""
            w <= h -> "$w*$h"
            else -> "$h*$w"
        }

        return DeviceDefaults(
            cpuArchitecture = Build.SUPPORTED_ABIS.joinToString(","),
            device = device,
            model = model,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT.toString(),
            language = Locale.getDefault().language,
            manufacturer = Build.MANUFACTURER,
            os = if (isOppoFamily) {
                oplusOta.ifBlank { osIncremental }.ifBlank { oplusRom }.ifBlank { Build.VERSION.RELEASE }
            } else {
                osIncremental.ifBlank { miOsIncremental }.ifBlank { Build.VERSION.RELEASE }
            },
            osV2 = if (isOppoFamily) {
                oplusRom.ifBlank { oplusOta }.ifBlank { osIncremental }.ifBlank { Build.VERSION.RELEASE }
            } else {
                miOsIncremental.ifBlank { osIncremental }.ifBlank { Build.VERSION.RELEASE }
            },
            // 缺失的厂商系统属性保持未知，不能把其他 Android 设备标为 HyperOS。
            miuiBigVersionCode = if (isXiaomi) miuiCode else "",
            miuiBigVersionName = if (isXiaomi) miuiName else "",
            osBigVersionCode = when {
                isOppoFamily -> oplusRom.majorVersion()
                isXiaomi -> osCode
                else -> ""
            },
            osBigVersionName = when {
                isOppoFamily -> oplusRom.ifBlank { Build.VERSION.RELEASE }
                isXiaomi -> osName
                else -> ""
            },
            buildId = Build.ID,
            co = Locale.getDefault().country.ifBlank { "CN" },
            lo = if (isOppoFamily) {
                systemProperty("ro.oplus.regionmark")
                    .ifBlank { systemProperty("ro.vendor.oplus.regionmark") }
                    .ifBlank { Locale.getDefault().country }
                    .ifBlank { "CN" }
            } else if (isXiaomi) {
                systemProperty("ro.miui.region").ifBlank { "CN" }
            } else {
                Locale.getDefault().country.ifBlank { "CN" }
            },
            resolution = resolution,
            densityDpi = metrics.densityDpi.takeIf { it > 0 }?.toString().orEmpty(),
            densityScaleFactor = metrics.density.takeIf { it > 0 }?.toString().orEmpty(),
            hasGMSCore = (
                    systemProperty("ro.miui.has_gmscore") == "1" ||
                            packageVersionCode(context, "com.google.android.gms").isNotBlank()
                    ).toString(),
            supportedIslandVersion = if (isXiaomi) readIslandVersion(context) else "",
            hybridFrameworkVersion = packageVersionCode(context, "com.miui.hybrid"),
            marketVersions = mapOf(
                AppSource.XIAOMI to packageVersionCode(context, "com.xiaomi.market"),
                AppSource.VIVO to packageVersionCode(context, "com.bbk.appstore"),
            ),
            isAndroid = true,
            isXiaomi = isXiaomi,
            isComplete = isComplete,
            isOppoFamily = isOppoFamily,
            isOppoComplete = isOppoComplete,
            isVivoFamily = isVivoFamily,
            isVivoComplete = isVivoComplete,
            isSamsungFamily = isSamsungFamily,
            isSamsungComplete = isSamsungFamily && model.isNotBlank() && device.isNotBlank() &&
                    Build.VERSION.RELEASE.isNotBlank() && Build.VERSION.SDK_INT > 0,
            isHonorFamily = isHonorFamily,
            isHonorComplete = isHonorFamily && model.isNotBlank() && device.isNotBlank() &&
                    Build.VERSION.RELEASE.isNotBlank() && Build.VERSION.SDK_INT > 0,
            isHuaweiFamily = isHuaweiFamily,
            isHuaweiComplete = isHuaweiFamily && model.isNotBlank() && device.isNotBlank() &&
                    Build.VERSION.RELEASE.isNotBlank() && Build.VERSION.SDK_INT > 0,
            magicVersion = systemProperty("ro.build.version.magic")
                .ifBlank { systemProperty("ro.build.version.magicui") }
                .ifBlank { systemProperty("ro.build.version.emui") },
            honorMarketingName = systemProperty("ro.config.marketing_name"),
            honorTerminalType = honorTerminalType(context),
            honorAndroidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty(),
            honorUdid = honorUdid(),
            honorMagicSysVersion = systemProperty("ro.build.display.id").ifBlank { Build.DISPLAY },
            honorUserType = systemProperty("ro.logsystem.usertype").ifBlank { "-1" },
            honorDeviceMode = if (isHonorFamily) "1" else "2",
            // Official TerminalInfo encodes the normal Android user as 1 and Honor parallel space as 2.
            honorIsParallelSpace = "1",
        )
    }
}

private fun honorUdid(): String = runCatching {
    Class.forName("com.hihonor.android.os.Build")
        .getMethod("getUDID")
        .invoke(null) as? String
}.getOrNull().orEmpty()

private fun honorTerminalType(context: Context): String {
    val isFoldable = context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle") ||
            sequenceOf(
                "ro.config.hw_fold_disp",
                "ro.config.hw_fold_disp_extra",
                "ro.vendor.config.hw_fold_disp",
                "ro.vendor.config.hw_fold_disp_extra",
            ).any { systemProperty(it).isNotBlank() }
    return when {
        isFoldable -> "3"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "2"
        else -> "1"
    }
}

private fun String.majorVersion(): String = Regex("[0-9]+").find(this)?.value.orEmpty()

@Suppress("DEPRECATION")
private fun packageVersionCode(context: Context, pkg: String): String = runCatching {
    val info = context.packageManager.getPackageInfo(pkg, 0)
    (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()).toString()
}.getOrDefault("").validVersionCode()

private fun readIslandVersion(context: Context): String = runCatching {
    when (Settings.System.getString(context.contentResolver, "notification_focus_protocol")) {
        "1" -> "1"
        "2" -> "2"
        "3" -> "3"
        else -> "1"
    }
}.getOrDefault("1")

@SuppressLint("PrivateApi")
private fun systemProperty(key: String): String = runCatching {
    val clz = Class.forName("android.os.SystemProperties")
    clz.getMethod("get", String::class.java, String::class.java)
        .invoke(null, key, "") as? String
}.getOrNull().orEmpty()
