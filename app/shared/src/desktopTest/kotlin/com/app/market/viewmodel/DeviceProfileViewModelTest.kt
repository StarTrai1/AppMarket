package com.app.market.viewmodel

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.OppoRequestContext
import com.app.market.domain.model.profile.OppoStoreRegion
import com.app.market.domain.model.profile.ProfileSource
import com.app.market.domain.model.profile.ProfileTemplate
import com.app.market.domain.model.profile.SamsungRequestContext
import com.app.market.domain.model.profile.SamsungStoreRegion
import com.app.market.domain.model.profile.oppoRequestContext
import com.app.market.domain.model.profile.oppoStoreRegion
import com.app.market.domain.model.profile.requestContext
import com.app.market.domain.model.profile.samsungStoreRegion
import com.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceProfileViewModelTest {
    @Test
    fun gettingDeviceAgainReplacesSavedAndUnsavedDeviceFields() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val source = AppSource.XIAOMI
            repository.save(profileForTest("Saved model"), setOf("model"), source)
            repository.setCurrentTemplateName("Saved template", source)
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.update(source, "sdk", "99")

            viewModel.setSource(ProfileSource.DEVICE, source)
            advanceUntilIdle()

            assertEquals(repository.deviceProfile, viewModel.profiles.value[source])
            assertEquals(ProfileSource.DEVICE, viewModel.sources.value[source])
            assertTrue(viewModel.overriddenFields.value[source].orEmpty().isEmpty())
            assertNull(viewModel.templateNames.value[source])

            repository.deviceProfile = repository.deviceProfile.copy(model = "Updated device")
            viewModel.update(source, "model", "Unsaved model")
            viewModel.setSource(ProfileSource.DEVICE, source)
            advanceUntilIdle()
            viewModel.save(source)
            advanceUntilIdle()

            assertEquals("Updated device", viewModel.profiles.value[source]?.model)
            assertEquals(2, repository.sourceChanges.size)
            assertTrue(repository.overridden[source].orEmpty().isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun gettingSamsungDevicePreservesRegionContextsAndOtherSourceEdits() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val region = SamsungStoreRegion.GLOBAL
            val savedContext = region.requestContext().copy(countryCode = "CAN", csc = "XAC")
            repository.setSamsungStoreRegion(region)
            repository.samsungContexts[region] = savedContext
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.updateSamsungRequestContext("lang", "fr_CA")
            viewModel.update(AppSource.XIAOMI, "model", "Other source edit")

            viewModel.setSource(ProfileSource.DEVICE, AppSource.SAMSUNG)
            advanceUntilIdle()

            assertEquals(region, repository.currentSamsungStoreRegion())
            assertEquals(region, viewModel.samsungStoreRegion.value)
            assertEquals(savedContext, repository.samsungContexts[region])
            assertEquals(savedContext.copy(language = "fr_CA"), viewModel.samsungRequestContext.value)
            assertEquals("Other source edit", viewModel.profiles.value[AppSource.XIAOMI]?.model)
            assertEquals(setOf("model"), viewModel.overriddenFields.value[AppSource.XIAOMI])
            assertEquals(0, repository.samsungContextWrites)

            viewModel.save(AppSource.SAMSUNG)
            advanceUntilIdle()
            assertEquals(savedContext.copy(language = "fr_CA"), repository.samsungContexts[region])
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun gettingDevicePreservesRegionsPreviouslyInferredFromThePreset() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            assertEquals(OppoStoreRegion.CHINA, viewModel.oppoStoreRegion.value)
            assertEquals(SamsungStoreRegion.CHINA, viewModel.samsungStoreRegion.value)
            assertEquals("US", repository.deviceProfile.co)

            viewModel.setSource(ProfileSource.DEVICE, AppSource.OPPO)
            viewModel.setSource(ProfileSource.DEVICE, AppSource.SAMSUNG)
            advanceUntilIdle()

            assertEquals(OppoStoreRegion.CHINA, repository.currentOppoStoreRegion())
            assertEquals(SamsungStoreRegion.CHINA, repository.currentSamsungStoreRegion())
            assertEquals(OppoStoreRegion.CHINA.oppoRequestContext(), viewModel.oppoRequestContext.value)
            assertEquals(SamsungStoreRegion.CHINA.requestContext(), viewModel.samsungRequestContext.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unavailableDeviceLeavesTheCurrentProfileAndEditsUntouched() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository(deviceAvailable = false)
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.update(AppSource.XIAOMI, "model", "Custom model")

            viewModel.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
            advanceUntilIdle()

            assertFalse(viewModel.canUseDevice.value.getValue(AppSource.XIAOMI))
            assertEquals(ProfileSource.PRESET, viewModel.sources.value[AppSource.XIAOMI])
            assertEquals("Custom model", viewModel.profiles.value[AppSource.XIAOMI]?.model)
            assertEquals(setOf("model"), viewModel.overriddenFields.value[AppSource.XIAOMI])
            assertTrue(repository.sourceChanges.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class DeviceProfileFakeRepository(
    private val deviceAvailable: Boolean = true,
) : ProfileRepository {
    var deviceProfile = profileForTest("Current device").copy(co = "US", lo = "US")
    val overridden = mutableMapOf<AppSource, Set<String>>()
    val sourceChanges = mutableListOf<Pair<ProfileSource, AppSource>>()
    val samsungContexts = mutableMapOf<SamsungStoreRegion, SamsungRequestContext>()
    var samsungContextWrites = 0
    private val profiles = mutableMapOf<AppSource, MarketProfile>()
    private val sources = mutableMapOf<AppSource, ProfileSource>()
    private val templateNames = mutableMapOf<AppSource, String?>()
    private var oppoRegion: OppoStoreRegion? = null
    private var samsungRegion: SamsungStoreRegion? = null

    override suspend fun load(appSource: AppSource): MarketProfile =
        profiles[appSource] ?: profileForTest("Preset")

    override suspend fun save(profile: MarketProfile) = save(profile, emptySet(), AppSource.XIAOMI)

    override suspend fun save(profile: MarketProfile, overridden: Set<String>, appSource: AppSource) {
        profiles[appSource] = profile
        this.overridden[appSource] = overridden
    }

    override suspend fun currentSource(appSource: AppSource): ProfileSource =
        sources[appSource] ?: ProfileSource.PRESET

    override suspend fun setSource(source: ProfileSource, appSource: AppSource) {
        sourceChanges += source to appSource
        sources[appSource] = source
        profiles[appSource] = if (source == ProfileSource.DEVICE) deviceProfile else profileForTest("Preset")
        overridden.remove(appSource)
        templateNames.remove(appSource)
    }

    override suspend fun currentOppoStoreRegion(): OppoStoreRegion =
        oppoRegion ?: load(AppSource.OPPO).oppoStoreRegion()

    override suspend fun setOppoStoreRegion(region: OppoStoreRegion) {
        oppoRegion = region
    }

    override suspend fun currentSamsungStoreRegion(): SamsungStoreRegion =
        samsungRegion ?: load(AppSource.SAMSUNG).samsungStoreRegion()

    override suspend fun setSamsungStoreRegion(region: SamsungStoreRegion) {
        samsungRegion = region
    }

    override suspend fun oppoRequestContext(region: OppoStoreRegion): OppoRequestContext = region.oppoRequestContext()
    override suspend fun saveOppoRequestContext(region: OppoStoreRegion, context: OppoRequestContext) = Unit
    override suspend fun samsungRequestContext(region: SamsungStoreRegion): SamsungRequestContext =
        samsungContexts[region] ?: region.requestContext()

    override suspend fun saveSamsungRequestContext(region: SamsungStoreRegion, context: SamsungRequestContext) {
        samsungContextWrites++
        samsungContexts[region] = context
    }

    override suspend fun currentTemplateName(appSource: AppSource): String? = templateNames[appSource]
    override suspend fun setCurrentTemplateName(name: String?, appSource: AppSource) {
        templateNames[appSource] = name
    }

    override suspend fun templates(): List<ProfileTemplate> = emptyList()
    override suspend fun saveTemplate(name: String, profile: MarketProfile, appSource: AppSource) = Unit
    override suspend fun applyTemplate(name: String, appSource: AppSource): Boolean = false
    override suspend fun deleteTemplate(name: String): Boolean = false
    override fun canUseDevice(appSource: AppSource): Boolean = deviceAvailable
    override suspend fun resetField(name: String, appSource: AppSource) = Unit
    override suspend fun resetAll(appSource: AppSource) = Unit
    override suspend fun isOverridden(name: String, appSource: AppSource): Boolean =
        name in overridden[appSource].orEmpty()

    override suspend fun syncFromServerIfDue() = Unit
}

private fun profileForTest(model: String) = MarketProfile(
    co = "CN",
    la = "zh",
    lo = "CN",
    cpuArchitecture = "arm64-v8a",
    device = "test_device",
    model = model,
    os = "test_os",
    osV2 = "test_os",
    androidVersion = "16",
    sdk = "36",
    resolution = "1080*2400",
    densityDpi = "420",
    densityScaleFactor = "2.625",
    miuiBigVersionCode = "",
    miuiBigVersionName = "",
    osBigVersionCode = "",
    osBigVersionName = "",
    marketVersion = "",
    pageConfigVersion = "",
    webResVersion = "",
    hybridFrameworkVersion = "",
    buildId = "test_build",
    instanceId = "test_instance",
    hasGMSCore = "true",
    supportedIslandVersion = "",
)
