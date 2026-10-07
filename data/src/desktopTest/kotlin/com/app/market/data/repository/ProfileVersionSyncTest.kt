package com.app.market.data.repository

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.xiaomi.XiaomiClient
import com.app.market.data.remote.xiaomi.platform.DeviceDefaults
import com.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.preferences.ProfilePreferenceKeys
import com.app.market.data.remote.xiaomi.preferences.XiaomiIdentityPreferenceKeys
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.ProfileFieldOrigin
import com.app.market.domain.model.profile.ProfileSnapshot
import com.app.market.domain.model.profile.ProfileSource
import com.app.market.domain.model.profile.ProfileSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileVersionSyncTest {
    @Test
    fun installedMarketAndFrameworkVersionsKeepDeviceOriginsEvenWhenEqualToPreset() = runTest {
        val fixture = VersionFixture()
        listOf(AppSource.XIAOMI, AppSource.VIVO).forEach { source ->
            val preset = fixture.repository.snapshot(source)
            assertTrue(preset.fieldOrigins.values.all { it == ProfileFieldOrigin.PRESET })
            fixture.defaults = fixture.defaults.copy(
                marketVersions = mapOf(source to preset.profile.marketVersion),
                hybridFrameworkVersion = "13170201",
            )
            fixture.repository.setSource(ProfileSource.DEVICE, source)
            val current = fixture.repository.snapshot(source)
            assertEquals(preset.profile.marketVersion, current.profile.marketVersion)
            assertEquals("13170201", current.profile.hybridFrameworkVersion)
            assertEquals(ProfileFieldOrigin.DEVICE, current.fieldOrigins["marketVersion"])
            assertEquals(ProfileFieldOrigin.DEVICE, current.fieldOrigins["hybridFrameworkVersion"])
            assertEquals(ProfileFieldOrigin.PRESET_FALLBACK, current.fieldOrigins["pageConfigVersion"])
            assertEquals(ProfileFieldOrigin.PRESET_FALLBACK, current.fieldOrigins["webResVersion"])
            assertEquals(preset.profile.instanceId, current.profile.instanceId)
            assertEquals(preset.profile.apkVer, current.profile.apkVer)

            fixture.repository.setSource(ProfileSource.PRESET, source)
            assertEquals(preset, fixture.repository.snapshot(source))
        }
    }

    @Test
    fun missingAndInvalidPackageVersionsUseLabeledFallbacks() = runTest {
        val fixture = VersionFixture()
        val preset = fixture.repository.load(AppSource.XIAOMI)
        listOf("", "0", "-1", "not-a-version").forEach { version ->
            fixture.defaults = fixture.defaults.copy(
                marketVersions = mapOf(AppSource.XIAOMI to version),
                hybridFrameworkVersion = version,
            )
            fixture.repository.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
            val current = fixture.repository.snapshot(AppSource.XIAOMI)
            assertEquals(preset.marketVersion, current.profile.marketVersion)
            assertEquals(preset.hybridFrameworkVersion, current.profile.hybridFrameworkVersion)
            assertTrue(current.fieldOrigins.values.all { it == ProfileFieldOrigin.PRESET_FALLBACK })
        }

        fixture.defaults = fixture.defaults.copy(
            marketVersions = mapOf(AppSource.XIAOMI to "40009999", AppSource.VIVO to "70000"),
            hybridFrameworkVersion = "15000000",
        )
        fixture.repository.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
        assertEquals("40009999", fixture.repository.load(AppSource.XIAOMI).marketVersion)
        assertEquals("15000000", fixture.repository.load(AppSource.XIAOMI).hybridFrameworkVersion)
        fixture.repository.setSource(ProfileSource.DEVICE, AppSource.VIVO)
        assertEquals("70000", fixture.repository.load(AppSource.VIVO).marketVersion)
    }

    @Test
    fun synchronizationKeepsCustomValuesAndDoesNotContaminateOtherSources() = runTest {
        val fixture = VersionFixture()
        val otherSources = AppSource.entries.filterNot { it == AppSource.XIAOMI }
        val before = otherSources.associateWith { fixture.repository.snapshot(it) }
        fixture.repository.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
        val custom = fixture.repository.load(AppSource.XIAOMI).copy(
            marketVersion = "701",
            pageConfigVersion = "702",
            webResVersion = "703",
            hybridFrameworkVersion = "704",
        )
        fixture.repository.save(custom, ProfileSnapshot.VERSION_FIELDS, AppSource.XIAOMI)
        fixture.fetch = { ProfileConfigurationVersions(webResVersion = "803", pageConfigVersion = "802") }

        assertEquals(ProfileSyncResult.SUCCESS, fixture.repository.syncConfiguration())
        val snapshot = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals(custom, snapshot.profile)
        assertTrue(snapshot.fieldOrigins.values.all { it == ProfileFieldOrigin.CUSTOM })
        otherSources.forEach { source ->
            assertEquals(before[source], fixture.repository.snapshot(source))
            // 清空缓存再读也不能带入小米同步值。
            fixture.repository.setSource(ProfileSource.PRESET, source)
            assertEquals(before[source], fixture.repository.snapshot(source))
        }

        fixture.repository.resetField("pageConfigVersion", AppSource.XIAOMI)
        val reset = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals("802", reset.profile.pageConfigVersion)
        assertEquals(ProfileFieldOrigin.SERVER, reset.fieldOrigins["pageConfigVersion"])
        assertEquals(ProfileFieldOrigin.CUSTOM, reset.fieldOrigins["webResVersion"])
        assertEquals(ProfileFieldOrigin.CUSTOM, fixture.recreateRepository().snapshot(AppSource.XIAOMI).fieldOrigins["webResVersion"])
    }

    @Test
    fun equalServerValuesStillHaveServerOriginsAndForcedSyncBypassesDailyThrottle() = runTest {
        val fixture = VersionFixture()
        val preset = fixture.repository.load(AppSource.XIAOMI)
        fixture.preferences.put(XiaomiIdentityPreferenceKeys.ServerDeviceContext, "encrypted-context")
        fixture.fetch = { ProfileConfigurationVersions(preset.webResVersion, preset.pageConfigVersion) }
        val notifications = mutableListOf<AppSource>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.profileUpdates.collect { notifications += it }
        }

        fixture.repository.syncFromServerIfDue()
        assertEquals(1, fixture.fetchCount)
        assertEquals(listOf(AppSource.XIAOMI), notifications)
        val snapshot = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals(preset, snapshot.profile)
        assertEquals(ProfileFieldOrigin.SERVER, snapshot.fieldOrigins["pageConfigVersion"])
        assertEquals(ProfileFieldOrigin.SERVER, snapshot.fieldOrigins["webResVersion"])

        fixture.repository.syncFromServerIfDue()
        assertEquals(1, fixture.fetchCount)
        assertEquals(ProfileSyncResult.SUCCESS, fixture.repository.syncConfiguration())
        assertEquals(2, fixture.fetchCount)
        assertEquals(listOf(AppSource.XIAOMI, AppSource.XIAOMI), notifications)
        assertEquals(snapshot, fixture.recreateRepository().snapshot(AppSource.XIAOMI))
    }

    @Test
    fun partialAndFailedResponsesRetainLastGoodValues() = runTest {
        val fixture = VersionFixture()
        fixture.repository.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
        fixture.fetch = { ProfileConfigurationVersions(webResVersion = "501") }
        assertEquals(ProfileSyncResult.PARTIAL, fixture.repository.syncConfiguration())
        val partial = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals(ProfileFieldOrigin.SERVER, partial.fieldOrigins["webResVersion"])
        assertEquals(ProfileFieldOrigin.PRESET_FALLBACK, partial.fieldOrigins["pageConfigVersion"])

        fixture.fetch = { ProfileConfigurationVersions(pageConfigVersion = "502") }
        assertEquals(ProfileSyncResult.PARTIAL, fixture.repository.syncConfiguration())
        val complete = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals("501", complete.profile.webResVersion)
        assertEquals("502", complete.profile.pageConfigVersion)
        val savedTime = fixture.preferences.read(ProfilePreferenceKeys.LastServerSync)

        listOf<ProfileConfigurationVersions?>(null, ProfileConfigurationVersions(), ProfileConfigurationVersions("-1", "bad"))
            .forEach { result ->
                fixture.fetch = { result }
                assertEquals(ProfileSyncResult.FAILED, fixture.repository.syncConfiguration())
                assertEquals(complete, fixture.repository.snapshot(AppSource.XIAOMI))
                assertEquals(savedTime, fixture.preferences.read(ProfilePreferenceKeys.LastServerSync))
            }
        fixture.fetch = { error("network unavailable") }
        assertEquals(ProfileSyncResult.FAILED, fixture.repository.syncConfiguration())
        assertEquals(complete, fixture.repository.snapshot(AppSource.XIAOMI))
    }

    @Test
    fun failedAtomicPersistenceKeepsThePreviousSnapshotAndDoesNotNotify() = runTest {
        val fixture = VersionFixture()
        val before = fixture.repository.snapshot(AppSource.XIAOMI)
        val notifications = mutableListOf<AppSource>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.profileUpdates.collect { notifications += it }
        }
        fixture.fetch = { ProfileConfigurationVersions("801", "901") }
        fixture.preferences.failNextUpdate = true

        assertEquals(ProfileSyncResult.FAILED, fixture.repository.syncConfiguration())
        assertEquals(before, fixture.repository.snapshot(AppSource.XIAOMI))
        assertNull(fixture.preferences.read(ProfilePreferenceKeys.LastServerSync))
        assertNull(fixture.preferences.read(ProfilePreferenceKeys.SyncedWebResource))
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun committedConfigurationNotifiesEvenWhenTheNextDeviceReadFails() = runTest {
        val fixture = VersionFixture()
        fixture.repository.load(AppSource.XIAOMI)
        val notifications = mutableListOf<AppSource>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.profileUpdates.collect { notifications += it }
        }
        fixture.fetch = {
            fixture.failDeviceRead = true
            ProfileConfigurationVersions("811", "911")
        }

        assertEquals(ProfileSyncResult.SUCCESS, fixture.repository.syncConfiguration())
        assertEquals(listOf(AppSource.XIAOMI), notifications)
        assertEquals("811", fixture.preferences.read(ProfilePreferenceKeys.SyncedWebResource))
        assertEquals("911", fixture.preferences.read(ProfilePreferenceKeys.SyncedPageConfig))
        assertTrue(!fixture.preferences.read(ProfilePreferenceKeys.LastServerSync).isNullOrBlank())
        assertFailsWith<IllegalStateException> { fixture.repository.snapshot(AppSource.XIAOMI) }

        fixture.failDeviceRead = false
        val snapshot = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals("811", snapshot.profile.webResVersion)
        assertEquals("911", snapshot.profile.pageConfigVersion)
        assertEquals(ProfileFieldOrigin.SERVER, snapshot.fieldOrigins["webResVersion"])
        assertEquals(ProfileFieldOrigin.SERVER, snapshot.fieldOrigins["pageConfigVersion"])
    }

    @Test
    fun cancellationPropagatesAndTimeoutCannotCommitConfiguration() = runTest {
        val fixture = VersionFixture()
        val before = fixture.repository.snapshot(AppSource.XIAOMI)
        fixture.fetch = { awaitCancellation() }
        val cancelled = async { fixture.repository.syncConfiguration() }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertEquals(before, fixture.repository.snapshot(AppSource.XIAOMI))
        assertNull(fixture.preferences.read(ProfilePreferenceKeys.LastServerSync))

        val timedOut = async { fixture.repository.syncConfiguration() }
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(ProfileSyncResult.FAILED, timedOut.await())
        assertEquals(before, fixture.repository.snapshot(AppSource.XIAOMI))
        assertNull(fixture.preferences.read(ProfilePreferenceKeys.SyncedWebResource))

        fixture.fetch = { throw CancellationException("caller cancelled") }
        assertFailsWith<CancellationException> { fixture.repository.syncConfiguration() }
        assertNull(fixture.preferences.read(ProfilePreferenceKeys.LastServerSync))
    }

    @Test
    fun requestsAreSerializedWithoutBlockingProfileEditsDuringNetworkWait() = runTest {
        val fixture = VersionFixture()
        val firstResponse = CompletableDeferred<Unit>()
        fixture.fetch = {
            if (fixture.fetchCount == 1) firstResponse.await()
            ProfileConfigurationVersions("${600 + fixture.fetchCount}", "${700 + fixture.fetchCount}")
        }
        val first = async { fixture.repository.syncConfiguration() }
        runCurrent()
        val second = async { fixture.repository.syncConfiguration() }
        runCurrent()
        assertEquals(1, fixture.fetchCount)

        val profile = fixture.repository.load(AppSource.XIAOMI)
        fixture.repository.save(profile.copy(marketVersion = "custom-during-sync"), setOf("marketVersion"), AppSource.XIAOMI)
        firstResponse.complete(Unit)
        runCurrent()
        assertEquals(ProfileSyncResult.SUCCESS, first.await())
        assertEquals(ProfileSyncResult.SUCCESS, second.await())
        assertEquals(2, fixture.fetchCount)
        val current = fixture.repository.snapshot(AppSource.XIAOMI)
        assertEquals("602", current.profile.webResVersion)
        assertEquals("702", current.profile.pageConfigVersion)
        assertEquals("custom-during-sync", current.profile.marketVersion)
        assertEquals(ProfileFieldOrigin.CUSTOM, current.fieldOrigins["marketVersion"])
    }

    @Test
    fun slowPagesReceiveTheLatestSnapshotWithoutBlockingSynchronization() = runTest {
        val fixture = VersionFixture()
        val releasePage = CompletableDeferred<Unit>()
        val received = mutableListOf<ProfileSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.profileUpdates.collect {
                received += fixture.repository.snapshot(it)
                if (received.size == 1) releasePage.await()
            }
        }
        fixture.fetch = { ProfileConfigurationVersions("${800 + fixture.fetchCount}", "${900 + fixture.fetchCount}") }
        repeat(3) { assertEquals(ProfileSyncResult.SUCCESS, fixture.repository.syncConfiguration()) }
        assertEquals(1, received.size)
        releasePage.complete(Unit)
        runCurrent()
        assertEquals("803", received.last().profile.webResVersion)
        assertEquals("903", received.last().profile.pageConfigVersion)
        assertFalse(received.isEmpty())
    }
}

private class VersionFixture {
    var failDeviceRead = false
    var defaults = DeviceDefaults(
        cpuArchitecture = "arm64-v8a",
        device = "test-device",
        model = "test-model",
        androidVersion = "16",
        sdk = "36",
        language = "zh",
        isAndroid = true,
    )
    val preferences = VersionPreferences()
    var fetchCount = 0
    var fetch: suspend (MarketProfile) -> ProfileConfigurationVersions? = { null }
    private val source = ProfileConfigurationSource { profile ->
        fetchCount += 1
        fetch(profile)
    }
    val repository = createRepository(preferences)

    fun recreateRepository(): ProfileRepositoryImpl = createRepository(VersionPreferences(preferences.snapshot()))

    private fun createRepository(preferences: PreferencesDataSource) = ProfileRepositoryImpl(
        preferences,
        source,
        object : DeviceDefaultsDataSource {
            override fun current(): DeviceDefaults {
                check(!failDeviceRead) { "simulated device read failure" }
                return defaults
            }
        },
        XiaomiClient(),
    )
}

private class VersionPreferences(initial: Map<StringPreferenceKey, String?> = emptyMap()) : PreferencesDataSource {
    private val strings = initial.mapValues { MutableStateFlow(it.value) }.toMutableMap()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableStateFlow<Boolean>>()
    var failNextUpdate = false

    fun snapshot(): Map<StringPreferenceKey, String?> = strings.mapValues { it.value.value }

    override fun observe(key: StringPreferenceKey): Flow<String?> =
        strings.getOrPut(key) { MutableStateFlow(null) }

    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> =
        booleans.getOrPut(key) { MutableStateFlow(key.default) }

    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.requireNamespace(namespace)
        if (failNextUpdate) {
            failNextUpdate = false
            error("simulated atomic persistence failure")
        }
        changes.strings.forEach { (key, value) ->
            strings.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
        changes.booleans.forEach { (key, value) ->
            booleans.getOrPut(key) { MutableStateFlow(key.default) }.value = value ?: key.default
        }
    }
}
