package com.app.market.viewmodel

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.OppoRequestContext
import com.app.market.domain.model.profile.OppoStoreRegion
import com.app.market.domain.model.profile.ProfileFieldOrigin
import com.app.market.domain.model.profile.ProfileSnapshot
import com.app.market.domain.model.profile.ProfileSource
import com.app.market.domain.model.profile.ProfileSyncResult
import com.app.market.domain.model.profile.ProfileTemplate
import com.app.market.domain.model.profile.SamsungRequestContext
import com.app.market.domain.model.profile.SamsungStoreRegion
import com.app.market.domain.model.profile.oppoRequestContext
import com.app.market.domain.model.profile.oppoStoreRegion
import com.app.market.domain.model.profile.requestContext
import com.app.market.domain.model.profile.samsungStoreRegion
import com.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun snapshotOriginsAreUsedEvenWhenAllVersionValuesMatch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val origins = mapOf(
                "marketVersion" to ProfileFieldOrigin.DEVICE,
                "pageConfigVersion" to ProfileFieldOrigin.SERVER,
                "webResVersion" to ProfileFieldOrigin.PRESET,
                "hybridFrameworkVersion" to ProfileFieldOrigin.PRESET_FALLBACK,
            )
            repository.setSnapshot(
                ProfileSnapshot(
                    profileForTest("Device").copy(
                        marketVersion = "100",
                        pageConfigVersion = "100",
                        webResVersion = "100",
                        hybridFrameworkVersion = "100",
                    ),
                    origins,
                ),
            )
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()

            assertEquals(origins, viewModel.fieldOrigins.value[AppSource.XIAOMI])
            viewModel.update(AppSource.XIAOMI, "pageConfigVersion", "100")
            assertEquals(
                ProfileFieldOrigin.CUSTOM,
                viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("pageConfigVersion"),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backgroundUpdateRefreshesTheOpenPageAndPreservesPendingEdits() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.update(AppSource.XIAOMI, "pageConfigVersion", "My page version")
            viewModel.update(AppSource.HUAWEI, "model", "Other source edit")
            viewModel.updateSamsungRequestContext("lang", "fr_CA")
            repository.setSnapshot(
                ProfileSnapshot(
                    profileForTest("Device").copy(pageConfigVersion = "200", webResVersion = "300"),
                    mapOf(
                        "pageConfigVersion" to ProfileFieldOrigin.SERVER,
                        "webResVersion" to ProfileFieldOrigin.SERVER,
                    ),
                ),
            )

            repository.profileUpdates.emit(AppSource.XIAOMI)
            advanceUntilIdle()

            assertEquals("My page version", viewModel.profiles.value[AppSource.XIAOMI]?.pageConfigVersion)
            assertEquals("300", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals(ProfileFieldOrigin.CUSTOM, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("pageConfigVersion"))
            assertEquals(ProfileFieldOrigin.SERVER, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("webResVersion"))
            assertEquals(setOf("pageConfigVersion"), viewModel.overriddenFields.value[AppSource.XIAOMI])
            assertNull(viewModel.templateNames.value[AppSource.XIAOMI])
            assertEquals("Other source edit", viewModel.profiles.value[AppSource.HUAWEI]?.model)
            assertEquals("fr_CA", viewModel.samsungRequestContext.value.language)
            assertEquals(0, repository.samsungContextWrites)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editsMadeWhileSnapshotIsLoadingWinOverTheSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            val snapshotStarted = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            repository.beforeSnapshotReturns = {
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
            }
            repository.profileUpdates.emit(AppSource.XIAOMI)
            runCurrent()
            assertTrue(snapshotStarted.isCompleted)

            viewModel.update(AppSource.XIAOMI, "webResVersion", "Typed during refresh")
            releaseSnapshot.complete(Unit)
            advanceUntilIdle()

            assertEquals("Typed during refresh", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals(ProfileFieldOrigin.CUSTOM, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("webResVersion"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun manualSyncWorksWithoutDeviceInfoAndRefreshesBeforeReportingSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository(deviceAvailable = false)
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.update(AppSource.XIAOMI, "pageConfigVersion", "Custom page version")
            repository.onSync = {
                repository.setSnapshot(
                    ProfileSnapshot(
                        profileForTest("Preset").copy(pageConfigVersion = "500", webResVersion = "600"),
                        mapOf(
                            "pageConfigVersion" to ProfileFieldOrigin.SERVER,
                            "webResVersion" to ProfileFieldOrigin.SERVER,
                        ),
                    ),
                )
                ProfileSyncResult.SUCCESS
            }

            viewModel.syncConfiguration()
            assertTrue(viewModel.isSyncingConfiguration.value)
            assertNull(viewModel.configurationSyncResult.value)
            advanceUntilIdle()

            assertFalse(viewModel.isSyncingConfiguration.value)
            assertEquals(ProfileSyncResult.SUCCESS, viewModel.configurationSyncResult.value)
            assertEquals("600", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals("Custom page version", viewModel.profiles.value[AppSource.XIAOMI]?.pageConfigVersion)
            assertEquals(ProfileFieldOrigin.CUSTOM, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("pageConfigVersion"))
            assertEquals(ProfileFieldOrigin.SERVER, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("webResVersion"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun repeatedClicksDoNotStartConcurrentSyncAndPartialResultIsKept() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            val finishSync = CompletableDeferred<ProfileSyncResult>()
            repository.onSync = { finishSync.await() }

            viewModel.syncConfiguration()
            viewModel.syncConfiguration()
            runCurrent()
            viewModel.syncConfiguration()
            assertEquals(1, repository.syncRequests)
            assertTrue(viewModel.isSyncingConfiguration.value)

            finishSync.complete(ProfileSyncResult.PARTIAL)
            advanceUntilIdle()

            assertFalse(viewModel.isSyncingConfiguration.value)
            assertEquals(ProfileSyncResult.PARTIAL, viewModel.configurationSyncResult.value)
            viewModel.syncConfiguration()
            advanceUntilIdle()
            assertEquals(2, repository.syncRequests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedSyncKeepsValuesAndThrownErrorsBecomeFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            repository.setSnapshot(
                ProfileSnapshot(
                    profileForTest("Device").copy(webResVersion = "Previous value"),
                    mapOf("webResVersion" to ProfileFieldOrigin.SERVER),
                ),
            )
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            repository.onSync = { ProfileSyncResult.FAILED }

            viewModel.syncConfiguration()
            advanceUntilIdle()
            assertEquals(ProfileSyncResult.FAILED, viewModel.configurationSyncResult.value)
            assertEquals("Previous value", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)

            repository.onSync = { error("Network unavailable") }
            viewModel.syncConfiguration()
            advanceUntilIdle()

            assertFalse(viewModel.isSyncingConfiguration.value)
            assertEquals(ProfileSyncResult.FAILED, viewModel.configurationSyncResult.value)
            assertEquals("Previous value", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals(ProfileFieldOrigin.SERVER, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("webResVersion"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledSyncDoesNotReportSuccessOrFailureAndAllowsRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            repository.onSync = { throw CancellationException("Cancelled") }

            viewModel.syncConfiguration()
            advanceUntilIdle()

            assertFalse(viewModel.isSyncingConfiguration.value)
            assertNull(viewModel.configurationSyncResult.value)
            repository.onSync = { ProfileSyncResult.SUCCESS }
            viewModel.syncConfiguration()
            advanceUntilIdle()
            assertEquals(ProfileSyncResult.SUCCESS, viewModel.configurationSyncResult.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editsMadeDuringSaveRemainPendingWhileCompletedEditsAreCleared() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.update(AppSource.XIAOMI, "webResVersion", "Saved web version")
            viewModel.update(AppSource.XIAOMI, "marketVersion", "Saved market version")
            val saving = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            repository.beforeSave = {
                saving.complete(Unit)
                releaseSave.await()
            }

            viewModel.save(AppSource.XIAOMI)
            runCurrent()
            assertTrue(saving.isCompleted)
            viewModel.update(AppSource.XIAOMI, "webResVersion", "New web input")
            viewModel.update(AppSource.XIAOMI, "pageConfigVersion", "New page input")
            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertEquals("Saved web version", repository.load(AppSource.XIAOMI).webResVersion)
            assertEquals("New web input", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals("New page input", viewModel.profiles.value[AppSource.XIAOMI]?.pageConfigVersion)
            assertEquals(ProfileFieldOrigin.CUSTOM, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("pageConfigVersion"))

            repository.setSnapshot(
                ProfileSnapshot(profileForTest("Device").copy(
                    webResVersion = "Refreshed web version",
                    pageConfigVersion = "Refreshed page version",
                    marketVersion = "Refreshed market version",
                )),
            )
            repository.profileUpdates.emit(AppSource.XIAOMI)
            advanceUntilIdle()
            assertEquals("New web input", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals("New page input", viewModel.profiles.value[AppSource.XIAOMI]?.pageConfigVersion)
            assertEquals("Refreshed market version", viewModel.profiles.value[AppSource.XIAOMI]?.marketVersion)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editsMadeDuringTemplateSaveRemainPendingEvenWhenChangedBackToSavedValue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            viewModel.update(AppSource.XIAOMI, "webResVersion", "Saved value")
            val saving = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            repository.beforeSaveTemplate = {
                saving.complete(Unit)
                releaseSave.await()
            }

            viewModel.saveTemplate("My template", AppSource.XIAOMI)
            runCurrent()
            assertTrue(saving.isCompleted)
            viewModel.update(AppSource.XIAOMI, "webResVersion", "Intermediate input")
            viewModel.update(AppSource.XIAOMI, "webResVersion", "Saved value")
            viewModel.update(AppSource.XIAOMI, "pageConfigVersion", "New page input")
            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertEquals("Saved value", repository.savedTemplateProfiles.single().webResVersion)
            assertEquals("", repository.savedTemplateProfiles.single().pageConfigVersion)
            assertNull(viewModel.templateNames.value[AppSource.XIAOMI])
            assertEquals("New page input", viewModel.profiles.value[AppSource.XIAOMI]?.pageConfigVersion)

            repository.setSnapshot(ProfileSnapshot(profileForTest("Device").copy(webResVersion = "Server value")))
            repository.profileUpdates.emit(AppSource.XIAOMI)
            advanceUntilIdle()
            assertEquals("Saved value", viewModel.profiles.value[AppSource.XIAOMI]?.webResVersion)
            assertEquals(ProfileFieldOrigin.CUSTOM, viewModel.fieldOrigins.value[AppSource.XIAOMI]?.get("webResVersion"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editsMadeWhileSelectingCustomProfileArePreserved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = DeviceProfileFakeRepository()
            val viewModel = DeviceProfileViewModel(repository)
            advanceUntilIdle()
            val saving = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            repository.beforeSave = {
                saving.complete(Unit)
                releaseSave.await()
            }

            viewModel.useCustom(AppSource.XIAOMI)
            runCurrent()
            assertTrue(saving.isCompleted)
            viewModel.update(AppSource.XIAOMI, "model", "Typed while saving")
            releaseSave.complete(Unit)
            advanceUntilIdle()

            assertEquals("Preset", repository.load(AppSource.XIAOMI).model)
            assertEquals("Typed while saving", viewModel.profiles.value[AppSource.XIAOMI]?.model)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class DeviceProfileFakeRepository(
    private val deviceAvailable: Boolean = true,
) : ProfileRepository {
    override val profileUpdates = MutableSharedFlow<AppSource>(extraBufferCapacity = 8)
    var beforeSnapshotReturns: suspend () -> Unit = {}
    var beforeSave: suspend () -> Unit = {}
    var beforeSaveTemplate: suspend () -> Unit = {}
    val savedTemplateProfiles = mutableListOf<MarketProfile>()
    var onSync: suspend () -> ProfileSyncResult = { ProfileSyncResult.SUCCESS }
    var syncRequests = 0
    var deviceProfile = profileForTest("Current device").copy(co = "US", lo = "US")
    val overridden = mutableMapOf<AppSource, Set<String>>()
    val sourceChanges = mutableListOf<Pair<ProfileSource, AppSource>>()
    val samsungContexts = mutableMapOf<SamsungStoreRegion, SamsungRequestContext>()
    var samsungContextWrites = 0
    private val profiles = mutableMapOf<AppSource, MarketProfile>()
    private val sources = mutableMapOf<AppSource, ProfileSource>()
    private val templateNames = mutableMapOf<AppSource, String?>()
    private val fieldOrigins = mutableMapOf<AppSource, Map<String, ProfileFieldOrigin>>()
    private var oppoRegion: OppoStoreRegion? = null
    private var samsungRegion: SamsungStoreRegion? = null

    override suspend fun load(appSource: AppSource): MarketProfile =
        profiles[appSource] ?: profileForTest("Preset")

    override suspend fun snapshot(appSource: AppSource): ProfileSnapshot {
        val snapshot = ProfileSnapshot(
            load(appSource),
            fieldOrigins[appSource].orEmpty() + overridden[appSource].orEmpty().associateWith { ProfileFieldOrigin.CUSTOM },
        )
        beforeSnapshotReturns()
        return snapshot
    }

    fun setSnapshot(snapshot: ProfileSnapshot, appSource: AppSource = AppSource.XIAOMI) {
        profiles[appSource] = snapshot.profile
        fieldOrigins[appSource] = snapshot.fieldOrigins
    }

    override suspend fun save(profile: MarketProfile) = save(profile, emptySet(), AppSource.XIAOMI)

    override suspend fun save(profile: MarketProfile, overridden: Set<String>, appSource: AppSource) {
        beforeSave()
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
    override suspend fun saveTemplate(name: String, profile: MarketProfile, appSource: AppSource) {
        beforeSaveTemplate()
        savedTemplateProfiles += profile
        profiles[appSource] = profile
        templateNames[appSource] = name
    }
    override suspend fun applyTemplate(name: String, appSource: AppSource): Boolean = false
    override suspend fun deleteTemplate(name: String): Boolean = false
    override fun canUseDevice(appSource: AppSource): Boolean = deviceAvailable
    override suspend fun resetField(name: String, appSource: AppSource) = Unit
    override suspend fun resetAll(appSource: AppSource) = Unit
    override suspend fun isOverridden(name: String, appSource: AppSource): Boolean =
        name in overridden[appSource].orEmpty()

    override suspend fun syncFromServerIfDue() = Unit
    override suspend fun syncConfiguration(): ProfileSyncResult {
        syncRequests++
        return onSync()
    }
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
