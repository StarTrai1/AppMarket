package com.app.market.data.repository

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.xiaomi.UpdateInfoCache
import com.app.market.data.remote.xiaomi.XiaomiApi
import com.app.market.data.remote.xiaomi.XiaomiClient
import com.app.market.data.remote.xiaomi.XiaomiHttpClient
import com.app.market.data.remote.xiaomi.platform.DeviceDefaults
import com.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentity
import com.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentityDataSource
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.ProfileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentDeviceProfilePersistenceTest {
    @Test
    fun manualSelectionClearsTemplateOverridesAndSurvivesRepositoryRecreation() = runBlocking {
        val fixture = ProfileFixture(androidDefaults())
        try {
            editableSources.forEach { source ->
                val repository = fixture.repository
                val preset = repository.load(source)
                assertEquals(ProfileSource.PRESET, repository.currentSource(source))
                repository.saveTemplate("saved-$source", preset.copy(model = "custom-model"), source)
                assertEquals("custom-model", repository.load(source).model)

                repository.setSource(ProfileSource.DEVICE, source)
                val current = repository.load(source)
                assertEquals("Pixel test", current.model)
                assertEquals("test-device", current.device)
                assertEquals("real-build-id", current.buildId)
                assertEquals("15", current.androidVersion)
                assertEquals("35", current.sdk)
                assertEquals("Google", current.hman)
                assertEquals(preset.marketVersion, current.marketVersion)
                assertEquals(preset.pageConfigVersion, current.pageConfigVersion)
                assertEquals(preset.webResVersion, current.webResVersion)
                assertEquals(preset.apkVer, current.apkVer)
                assertEquals(preset.apkVerName, current.apkVerName)
                assertEquals(36, current.instanceId.length)
                assertNotEquals("app-scoped-android-id", current.instanceId)
                assertFalse(repository.isOverridden("model", source))
                assertNull(repository.currentTemplateName(source))

                // 从已写入的偏好快照重建存储与仓库，避免仅验证原仓库的内存缓存。
                val reloaded = fixture.recreateRepository()
                assertEquals(ProfileSource.DEVICE, reloaded.currentSource(source))
                assertEquals(current, reloaded.load(source))
                assertNull(reloaded.currentTemplateName(source))
                assertFalse(reloaded.isOverridden("model", source))

                if (source == AppSource.XIAOMI) {
                    assertTrue(fixture.client.userAgent.contains("Pixel test Build/real-build-id"))
                }
            }
        } finally {
            fixture.http.close()
        }
    }

    @Test
    fun explicitPresetRemainsSelectedOnACompatibleAndroidDevice() = runBlocking {
        val fixture = ProfileFixture(androidDefaults().copy(isXiaomi = true, isComplete = true))
        try {
            val repository = fixture.repository
            assertEquals(ProfileSource.DEVICE, repository.currentSource(AppSource.XIAOMI))
            repository.setSource(ProfileSource.PRESET, AppSource.XIAOMI)
            val preset = repository.load(AppSource.XIAOMI)
            repository.save(preset.copy(model = "saved-model"), setOf("model"), AppSource.XIAOMI)

            val reloaded = fixture.recreateRepository()
            assertEquals(ProfileSource.PRESET, reloaded.currentSource(AppSource.XIAOMI))
            assertEquals("saved-model", reloaded.load(AppSource.XIAOMI).model)
            assertEquals(preset.instanceId, reloaded.load(AppSource.XIAOMI).instanceId)
            assertTrue(reloaded.isOverridden("model", AppSource.XIAOMI))
        } finally {
            fixture.http.close()
        }
    }

    @Test
    fun missingOptionalFieldsUseCompatibilityFallbackWithoutInventingVendorVersions() = runBlocking {
        val fixture = ProfileFixture(
            androidDefaults().copy(
                cpuArchitecture = "",
                resolution = "",
                densityDpi = "",
                densityScaleFactor = "",
                buildId = "",
            ),
        )
        try {
            editableSources.forEach { source ->
                val repository = fixture.repository
                val preset = repository.load(source)
                assertTrue(repository.canUseDevice(source))
                repository.setSource(ProfileSource.DEVICE, source)
                val current = repository.load(source)
                assertEquals("Pixel test", current.model)
                assertEquals(preset.cpuArchitecture, current.cpuArchitecture)
                assertEquals(preset.resolution, current.resolution)
                assertEquals(preset.densityDpi, current.densityDpi)
                assertEquals(preset.densityScaleFactor, current.densityScaleFactor)
                assertEquals(preset.buildId, current.buildId)
                assertEquals(preset.instanceId, current.instanceId)
                assertEquals("", current.miuiBigVersionCode)
                assertEquals("", current.miuiBigVersionName)
                assertEquals("", current.osBigVersionCode)
                assertEquals("", current.osBigVersionName)
                assertEquals("", current.supportedIslandVersion)
                assertEquals(".0.0", current.magicVersion)
            }
        } finally {
            fixture.http.close()
        }
    }

    @Test
    fun deviceRefreshReplacesCachedValuesAndFallsBackWhenBasicBuildFieldsDisappear() = runBlocking {
        val fixture = ProfileFixture(androidDefaults())
        try {
            val repository = fixture.repository
            val preset = repository.load(AppSource.XIAOMI)
            repository.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
            assertEquals("Pixel test", repository.load(AppSource.XIAOMI).model)

            fixture.defaults.value = androidDefaults().copy(model = "updated-model")
            repository.setSource(ProfileSource.DEVICE, AppSource.XIAOMI)
            assertEquals("updated-model", repository.load(AppSource.XIAOMI).model)
            assertTrue(fixture.client.userAgent.contains("updated-model Build/real-build-id"))
            assertEquals(ProfileSource.PRESET, repository.currentSource(AppSource.OPPO))

            fixture.defaults.value = androidDefaults().copy(model = "")
            val reloaded = fixture.recreateRepository()
            assertFalse(reloaded.canUseDevice(AppSource.XIAOMI))
            assertEquals(preset, reloaded.load(AppSource.XIAOMI))

            fixture.defaults.value = androidDefaults().copy(isAndroid = false)
            assertFalse(fixture.recreateRepository().canUseDevice(AppSource.XIAOMI))
            assertEquals(preset, fixture.recreateRepository().load(AppSource.XIAOMI))
        } finally {
            fixture.http.close()
        }
    }

    private fun androidDefaults() = DeviceDefaults(
        cpuArchitecture = "arm64-v8a,armeabi-v7a",
        device = "test-device",
        model = "Pixel test",
        androidVersion = "15",
        sdk = "35",
        language = "en",
        manufacturer = "Google",
        buildId = "real-build-id",
        isAndroid = true,
        honorAndroidId = "app-scoped-android-id",
    )

    private val editableSources = listOf(
        AppSource.XIAOMI, AppSource.OPPO, AppSource.VIVO,
        AppSource.SAMSUNG, AppSource.HONOR, AppSource.HUAWEI,
    )
}

private class ProfileFixture(initialDefaults: DeviceDefaults) {
    val defaults = MutableProfileDefaults(initialDefaults)
    val preferences = ProfileMemoryPreferences()
    val client = XiaomiClient()
    val http = HttpClient(CIO)
    val repository = createRepository(preferences)

    fun recreateRepository(): ProfileRepositoryImpl =
        createRepository(ProfileMemoryPreferences(preferences.snapshot()))

    private fun createRepository(preferences: ProfileMemoryPreferences): ProfileRepositoryImpl =
        ProfileRepositoryImpl(
            preferences = preferences,
            api = XiaomiApi(
                preferences = preferences,
                updateInfoCache = UpdateInfoCache(preferences),
                identityProvider = object : XiaomiDeviceIdentityDataSource {
                    override suspend fun identity(): XiaomiDeviceIdentity =
                        error("Profile selection must not request a remote identity")
                },
                http = XiaomiHttpClient(http, client),
                xiaomiClient = client,
            ),
            deviceDefaults = defaults,
            xiaomiClient = client,
        )
}

private class MutableProfileDefaults(var value: DeviceDefaults) : DeviceDefaultsDataSource {
    override fun current(): DeviceDefaults = value
}

private class ProfileMemoryPreferences(initial: Map<StringPreferenceKey, String?> = emptyMap()) :
    PreferencesDataSource {
    private val strings = initial.mapValues { MutableStateFlow(it.value) }.toMutableMap()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableStateFlow<Boolean>>()

    fun snapshot(): Map<StringPreferenceKey, String?> = strings.mapValues { it.value.value }

    override fun observe(key: StringPreferenceKey): Flow<String?> =
        strings.getOrPut(key) { MutableStateFlow(null) }

    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> =
        booleans.getOrPut(key) { MutableStateFlow(key.default) }

    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.requireNamespace(namespace)
        changes.strings.forEach { (key, value) ->
            strings.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
        changes.booleans.forEach { (key, value) ->
            booleans.getOrPut(key) { MutableStateFlow(key.default) }.value = value ?: key.default
        }
    }
}
