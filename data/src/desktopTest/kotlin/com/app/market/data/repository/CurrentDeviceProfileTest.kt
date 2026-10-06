package com.app.market.data.repository

import com.app.market.data.remote.xiaomi.platform.DeviceDefaults
import com.app.market.data.remote.xiaomi.platform.DesktopDeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.platform.isOppoFamilyDevice
import com.app.market.data.remote.xiaomi.platform.isSamsungFamilyDevice
import com.app.market.data.remote.xiaomi.platform.isVivoFamilyDevice
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.ProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentDeviceProfileTest {
    @Test
    fun compatibleAndroidSourcesDefaultToTheCurrentDevice() {
        val compatibleDevices = mapOf(
            AppSource.XIAOMI to defaults().copy(isXiaomi = true, isComplete = true),
            AppSource.OPPO to defaults(isOppoFamily = true, isOppoComplete = true),
            AppSource.VIVO to defaults(isVivoFamily = true, isVivoComplete = true),
            AppSource.SAMSUNG to defaults(isSamsungFamily = true, isSamsungComplete = true),
            AppSource.HONOR to defaults(
                isHonorFamily = true,
                isHonorComplete = true,
                honorAndroidId = "app-scoped-id",
            ),
            AppSource.HUAWEI to defaults().copy(isHuaweiFamily = true, isHuaweiComplete = true),
        )

        compatibleDevices.forEach { (brandSource, device) ->
            editableSources.forEach { source ->
                val expected = if (source == brandSource && source != AppSource.OPPO) {
                    ProfileSource.DEVICE
                } else {
                    ProfileSource.PRESET
                }
                assertEquals(expected, defaultProfileSource(source, device), "$brandSource / $source")
            }
        }
    }

    @Test
    fun missingHonorIdentityKeepsPresetDefaultButAllowsManualDeviceSelection() {
        val android = defaults(
            isHonorFamily = true,
            isHonorComplete = true,
            honorAndroidId = "app-scoped-id",
        )

        assertTrue(canUseCurrentDevice(AppSource.HONOR, android))
        val withoutIdentity = android.copy(honorAndroidId = "")
        assertTrue(canUseCurrentDevice(AppSource.HONOR, withoutIdentity))
        assertEquals(ProfileSource.PRESET, defaultProfileSource(AppSource.HONOR, withoutIdentity))
        assertTrue(canUseCurrentDevice(AppSource.HONOR, android.copy(isHonorFamily = false)))
    }

    @Test
    fun everyEditableSourceAcceptsAndroidAcrossBrandsWithoutVendorProperties() {
        listOf("Google", "Xiaomi", "OPPO", "vivo", "samsung", "HONOR", "HUAWEI").forEach { brand ->
            val android = defaults().copy(manufacturer = brand)
            editableSources.forEach { source ->
                assertTrue(canUseCurrentDevice(source, android), "$brand / $source")
                assertEquals(ProfileSource.PRESET, defaultProfileSource(source, android))
            }
            assertFalse(canUseCurrentDevice(AppSource.WANDOUJIA, android))
            assertFalse(canUseCurrentDevice(AppSource.TAPTAP, android))
        }
    }

    @Test
    fun desktopAndMissingAndroidBuildFieldsCannotPretendToBeTheCurrentPhone() {
        val unavailable = listOf(
            DesktopDeviceDefaultsDataSource().current(),
            defaults().copy(isAndroid = false, isXiaomi = true, isComplete = true),
            defaults().copy(model = ""),
            defaults().copy(device = ""),
            defaults().copy(androidVersion = ""),
            defaults().copy(model = "unknown"),
            defaults().copy(device = "UNKNOWN"),
            defaults().copy(sdk = "0"),
            defaults().copy(sdk = "invalid"),
        )
        unavailable.forEach { device ->
            editableSources.forEach { source ->
                assertFalse(canUseCurrentDevice(source, device), "$source / $device")
            }
        }
        editableSources.forEach { source ->
            assertEquals(ProfileSource.PRESET, defaultProfileSource(source, unavailable.first()))
        }
    }

    @Test
    fun presetSourceDoesNotLeakDeviceBackedValues() {
        assertEquals(
            "arm64-v8a",
            selectProfileField(
                presetValue = "arm64-v8a",
                deviceValue = "x86_64,arm64-v8a,x86",
                useDevice = false,
                appLevel = false,
                deviceBacked = true,
            ),
        )
        assertEquals(
            "x86_64,arm64-v8a,x86",
            selectProfileField(
                presetValue = "arm64-v8a",
                deviceValue = "x86_64,arm64-v8a,x86",
                useDevice = true,
                appLevel = false,
                deviceBacked = true,
            ),
        )
    }

    @Test
    fun incompleteVendorPropertiesDoNotBlockManualOppoSelection() {
        val oppo = defaults(isOppoFamily = true, isOppoComplete = true)

        assertTrue(canUseCurrentDevice(AppSource.OPPO, oppo))
        assertTrue(canUseCurrentDevice(AppSource.XIAOMI, oppo))
        assertTrue(canUseCurrentDevice(AppSource.VIVO, oppo))
        assertFalse(canUseCurrentDevice(AppSource.WANDOUJIA, oppo))
        assertTrue(canUseCurrentDevice(AppSource.OPPO, oppo.copy(isOppoComplete = false)))
    }

    @Test
    fun oppoOnePlusAndRealmeBrandsAreRecognizedCaseInsensitively() {
        assertTrue(isOppoFamilyDevice("OPPO", "OPPO"))
        assertTrue(isOppoFamilyDevice("OnePlus", "OnePlus"))
        assertTrue(isOppoFamilyDevice("realme", "realme"))
        assertTrue(isOppoFamilyDevice("unknown", "REALME"))
        assertFalse(isOppoFamilyDevice("Xiaomi", "Redmi"))
    }

    @Test
    fun vivoAndIqooDevicesCanUseTheVivoProfile() {
        assertTrue(isVivoFamilyDevice("vivo", "vivo", "PD2241"))
        assertTrue(isVivoFamilyDevice("iQOO", "iQOO", "I2401"))
        assertFalse(isVivoFamilyDevice("Xiaomi", "Redmi", "popsicle"))
        val vivo = defaults(isVivoFamily = true, isVivoComplete = true)
        assertTrue(canUseCurrentDevice(AppSource.VIVO, vivo))
        assertTrue(canUseCurrentDevice(AppSource.OPPO, vivo))
    }

    @Test
    fun vivoPresetMatchesCurrentTemplateOneWithoutXiaomiProtocolValues() {
        val profile = vivoPresetProfile(defaults(), "instance-id")

        assertEquals("PD2408", profile.device)
        assertEquals("V2408A", profile.model)
        assertEquals("compiler260710223223", profile.os)
        assertEquals("compiler260710223223", profile.osV2)
        assertEquals("16", profile.androidVersion)
        assertEquals("36", profile.sdk)
        assertEquals("1440*2560", profile.resolution)
        assertEquals("640", profile.densityDpi)
        assertEquals("4.0", profile.densityScaleFactor)
        assertEquals("61510", profile.marketVersion)
        assertEquals("18411801", profile.pageConfigVersion)
        assertEquals("3211", profile.webResVersion)
        assertEquals("V417IR", profile.buildId)
        assertEquals("instance-id", profile.instanceId)
        assertEquals("true", profile.hasGMSCore)
        assertEquals("", profile.miuiBigVersionCode)
        assertEquals("", profile.miuiBigVersionName)
        assertEquals("", profile.osBigVersionCode)
        assertEquals("", profile.osBigVersionName)
        assertEquals("", profile.hybridFrameworkVersion)
        assertEquals("", profile.supportedIslandVersion)
    }

    @Test
    fun samsungBrandRecognitionRemainsSeparateFromManualAvailability() {
        assertTrue(isSamsungFamilyDevice("SAMSUNG", "samsung"))
        assertFalse(isSamsungFamilyDevice("Xiaomi", "Redmi"))
        val samsung = defaults(isSamsungFamily = true, isSamsungComplete = true)
        assertTrue(canUseCurrentDevice(AppSource.SAMSUNG, samsung))
        assertTrue(canUseCurrentDevice(AppSource.OPPO, samsung))
        assertTrue(canUseCurrentDevice(AppSource.SAMSUNG, samsung.copy(isSamsungComplete = false)))
        assertEquals(
            ProfileSource.PRESET,
            defaultProfileSource(AppSource.SAMSUNG, samsung.copy(isSamsungComplete = false)),
        )
    }

    private fun defaults(
        isOppoFamily: Boolean = false,
        isOppoComplete: Boolean = false,
        isVivoFamily: Boolean = false,
        isVivoComplete: Boolean = false,
        isSamsungFamily: Boolean = false,
        isSamsungComplete: Boolean = false,
        isHonorFamily: Boolean = false,
        isHonorComplete: Boolean = false,
        honorAndroidId: String = "",
    ) = DeviceDefaults(
        cpuArchitecture = "arm64-v8a",
        device = "device",
        model = "model",
        androidVersion = "16",
        sdk = "36",
        language = "zh",
        isAndroid = true,
        isOppoFamily = isOppoFamily,
        isOppoComplete = isOppoComplete,
        isVivoFamily = isVivoFamily,
        isVivoComplete = isVivoComplete,
        isSamsungFamily = isSamsungFamily,
        isSamsungComplete = isSamsungComplete,
        isHonorFamily = isHonorFamily,
        isHonorComplete = isHonorComplete,
        honorAndroidId = honorAndroidId,
    )

    private val editableSources = listOf(
        AppSource.XIAOMI, AppSource.OPPO, AppSource.VIVO,
        AppSource.SAMSUNG, AppSource.HONOR, AppSource.HUAWEI,
    )
}
