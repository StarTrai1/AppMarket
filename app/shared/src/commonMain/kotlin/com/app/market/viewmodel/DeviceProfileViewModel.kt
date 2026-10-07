package com.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.MarketProfileFields
import com.app.market.domain.model.profile.OppoRequestContext
import com.app.market.domain.model.profile.OppoStoreRegion
import com.app.market.domain.model.profile.ProfileFieldOrigin
import com.app.market.domain.model.profile.ProfileSource
import com.app.market.domain.model.profile.ProfileSyncResult
import com.app.market.domain.model.profile.ProfileTemplate
import com.app.market.domain.model.profile.SamsungRequestContext
import com.app.market.domain.model.profile.SamsungStoreRegion
import com.app.market.domain.model.profile.oppoRequestContext
import com.app.market.domain.model.profile.requestContext
import com.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeviceProfileViewModel(
    private val store: ProfileRepository,
) : ViewModel() {

    private val _profiles = MutableStateFlow<Map<AppSource, MarketProfile>>(emptyMap())
    val profiles: StateFlow<Map<AppSource, MarketProfile>> = _profiles.asStateFlow()

    private val _fieldOrigins = MutableStateFlow<Map<AppSource, Map<String, ProfileFieldOrigin>>>(emptyMap())
    val fieldOrigins: StateFlow<Map<AppSource, Map<String, ProfileFieldOrigin>>> = _fieldOrigins.asStateFlow()

    private val _isSyncingConfiguration = MutableStateFlow(false)
    val isSyncingConfiguration: StateFlow<Boolean> = _isSyncingConfiguration.asStateFlow()

    private val _configurationSyncResult = MutableStateFlow<ProfileSyncResult?>(null)
    val configurationSyncResult: StateFlow<ProfileSyncResult?> = _configurationSyncResult.asStateFlow()

    private val _sources = MutableStateFlow<Map<AppSource, ProfileSource>>(emptyMap())
    val sources: StateFlow<Map<AppSource, ProfileSource>> = _sources.asStateFlow()

    private val _templateNames = MutableStateFlow<Map<AppSource, String?>>(emptyMap())
    val templateNames: StateFlow<Map<AppSource, String?>> = _templateNames.asStateFlow()

    private val _overriddenFields = MutableStateFlow<Map<AppSource, Set<String>>>(emptyMap())
    val overriddenFields: StateFlow<Map<AppSource, Set<String>>> = _overriddenFields.asStateFlow()

    private val _canUseDevice = MutableStateFlow<Map<AppSource, Boolean>>(emptyMap())
    val canUseDevice: StateFlow<Map<AppSource, Boolean>> = _canUseDevice.asStateFlow()

    private val _oppoStoreRegion = MutableStateFlow(OppoStoreRegion.CHINA)
    val oppoStoreRegion: StateFlow<OppoStoreRegion> = _oppoStoreRegion.asStateFlow()

    private val _oppoRequestContext = MutableStateFlow(OppoStoreRegion.CHINA.oppoRequestContext())
    val oppoRequestContext: StateFlow<OppoRequestContext> = _oppoRequestContext.asStateFlow()

    private val _samsungStoreRegion = MutableStateFlow(SamsungStoreRegion.CHINA)
    val samsungStoreRegion: StateFlow<SamsungStoreRegion> = _samsungStoreRegion.asStateFlow()

    private val _samsungRequestContext = MutableStateFlow(SamsungStoreRegion.CHINA.requestContext())
    val samsungRequestContext: StateFlow<SamsungRequestContext> = _samsungRequestContext.asStateFlow()

    private val _templates = MutableStateFlow<List<ProfileTemplate>>(emptyList())
    val templates: StateFlow<List<ProfileTemplate>> = _templates.asStateFlow()

    private val _showSaveTemplateDialog = MutableStateFlow<AppSource?>(null)
    val showSaveTemplateDialog: StateFlow<AppSource?> = _showSaveTemplateDialog.asStateFlow()

    private val edited = mutableMapOf<AppSource, MutableMap<String, Long>>()
    private var editRevision = 0L
    private val editedOppoRequestContexts = mutableMapOf<OppoStoreRegion, OppoRequestContext>()
    private val editedSamsungRequestContexts = mutableMapOf<SamsungStoreRegion, SamsungRequestContext>()
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch {
            store.profileUpdates.collect { appSource ->
                if (appSource in EDITABLE_SOURCES) {
                    try {
                        refreshSource(appSource)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 后台刷新失败时保留当前页面，后续通知仍可重试。
                    }
                }
            }
        }
        viewModelScope.launch {
            refreshAll()
        }
    }

    fun syncConfiguration() {
        if (!_isSyncingConfiguration.compareAndSet(expect = false, update = true)) return
        _configurationSyncResult.value = null
        viewModelScope.launch {
            try {
                val result = store.syncConfiguration()
                refreshSource(AppSource.XIAOMI)
                _configurationSyncResult.value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _configurationSyncResult.value = ProfileSyncResult.FAILED
            } finally {
                _isSyncingConfiguration.value = false
            }
        }
    }

    fun setSource(source: ProfileSource, appSource: AppSource) = mutate {
        if (source == ProfileSource.DEVICE && !store.canUseDevice(appSource)) return@mutate
        if (source == ProfileSource.DEVICE) {
            // 区域可能从旧设备资料推断；先保存当前选择，避免获取后跟随本机地区变化。
            when (appSource) {
                AppSource.OPPO -> store.setOppoStoreRegion(store.currentOppoStoreRegion())
                AppSource.SAMSUNG -> store.setSamsungStoreRegion(store.currentSamsungStoreRegion())
                else -> Unit
            }
        }
        store.setSource(source, appSource)
        if (appSource == AppSource.SAMSUNG && source != ProfileSource.DEVICE) {
            resetSamsungRequestContexts()
        }
        edited.remove(appSource)
        if (source == ProfileSource.DEVICE) {
            // 获取设备信息只替换当前来源的字段，保留区域参数和其他来源尚未保存的编辑。
            refreshSource(appSource)
        } else {
            refreshAll()
        }
    }

    fun setOppoStoreRegion(region: OppoStoreRegion) = mutate {
        store.setOppoStoreRegion(region)
        _oppoStoreRegion.value = region
        _oppoRequestContext.value = editedOppoRequestContexts[region] ?: store.oppoRequestContext(region)
    }

    fun setSamsungStoreRegion(region: SamsungStoreRegion) = mutate {
        store.setSamsungStoreRegion(region)
        _samsungStoreRegion.value = region
        _samsungRequestContext.value =
            editedSamsungRequestContexts[region] ?: store.samsungRequestContext(region)
    }

    fun updateOppoRequestContext(name: String, value: String) {
        val current = _oppoRequestContext.value
        val next = when (name) {
            "user-region" -> current.copy(userRegion = value)
            "system-locale" -> current.copy(systemLocale = value)
            "supported-locales" -> current.copy(supportedLocales = value)
            "locale" -> current.copy(locale = value)
            else -> return
        }
        editedOppoRequestContexts[_oppoStoreRegion.value] = next
        _oppoRequestContext.value = next
    }

    fun updateSamsungRequestContext(name: String, value: String) {
        val current = _samsungRequestContext.value
        val next = when (name) {
            "countryCode" -> current.copy(countryCode = value)
            "lang" -> current.copy(language = value)
            "mcc" -> current.copy(mcc = value)
            "mnc" -> current.copy(mnc = value)
            "csc" -> current.copy(csc = value)
            else -> return
        }
        editedSamsungRequestContexts[_samsungStoreRegion.value] = next
        _templateNames.update { it + (AppSource.SAMSUNG to null) }
        _samsungRequestContext.value = next
    }

    fun applyTemplate(name: String, appSource: AppSource) = mutate {
        if (store.applyTemplate(name, appSource)) {
            if (appSource == AppSource.SAMSUNG) resetSamsungRequestContexts()
            edited.remove(appSource)
            refreshAll()
        }
    }

    fun useCustom(appSource: AppSource) = mutate {
        val profile = _profiles.value[appSource] ?: return@mutate
        val savedEdits = edited[appSource].orEmpty().toMap()
        store.save(profile, fieldsFor(appSource).toSet(), appSource)
        store.setCurrentTemplateName(null, appSource)
        clearPersistedEdits(appSource, savedEdits)
        refreshAll()
    }

    fun update(appSource: AppSource, name: String, value: String) {
        val profile = _profiles.value[appSource] ?: return
        edited.getOrPut(appSource) { mutableMapOf() }[name] = ++editRevision
        _templateNames.update { it + (appSource to null) }
        _profiles.update { it + (appSource to MarketProfileFields.set(profile, name, value)) }
        _overriddenFields.update { overridden ->
            overridden + (appSource to (overridden[appSource].orEmpty() + name))
        }
        _fieldOrigins.update { origins ->
            origins + (appSource to (origins[appSource].orEmpty() + (name to ProfileFieldOrigin.CUSTOM)))
        }
    }

    fun save(appSource: AppSource) {
        val oppoContextEdits = if (appSource == AppSource.OPPO) editedOppoRequestContexts.toMap() else emptyMap()
        val samsungContextEdits =
            if (appSource == AppSource.SAMSUNG) editedSamsungRequestContexts.toMap() else emptyMap()
        mutate {
            val profile = _profiles.value[appSource] ?: return@mutate
            val savedEdits = edited[appSource].orEmpty().toMap()
            store.save(
                profile,
                savedEdits.keys + _overriddenFields.value[appSource].orEmpty(),
                appSource,
            )
            oppoContextEdits.forEach { (region, context) ->
                store.saveOppoRequestContext(region, context)
                if (editedOppoRequestContexts[region] == context) {
                    editedOppoRequestContexts.remove(region)
                }
            }
            samsungContextEdits.forEach { (region, context) ->
                store.saveSamsungRequestContext(region, context)
                if (editedSamsungRequestContexts[region] == context) {
                    editedSamsungRequestContexts.remove(region)
                }
            }
            clearPersistedEdits(appSource, savedEdits)
            refreshAll()
        }
    }

    fun setShowSaveTemplateDialog(appSource: AppSource?) {
        _showSaveTemplateDialog.value = appSource
    }

    fun defaultTemplateName(): String {
        val used = _templates.value.mapTo(mutableSetOf()) { it.name }
        var index = 1
        while ("模板$index" in used) index++
        return "模板$index"
    }

    fun saveTemplate(name: String, appSource: AppSource) = mutate {
        val profile = _profiles.value[appSource] ?: return@mutate
        val savedEdits = edited[appSource].orEmpty().toMap()
        store.saveTemplate(name.ifBlank { defaultTemplateName() }, profile, appSource)
        clearPersistedEdits(appSource, savedEdits)
        refreshAll()
        _showSaveTemplateDialog.value = null
    }

    fun deleteTemplate(name: String) = mutate {
        if (store.deleteTemplate(name)) {
            edited.clear()
            refreshAll()
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun clearPersistedEdits(appSource: AppSource, savedEdits: Map<String, Long>) {
        val pending = edited[appSource] ?: return
        // 只清除本次保存的修订；保存期间再次输入，即使改回原值也保留为未保存编辑。
        savedEdits.forEach { (field, revision) ->
            if (pending[field] == revision) pending.remove(field)
        }
        if (pending.isEmpty()) edited.remove(appSource)
    }

    private suspend fun resetSamsungRequestContexts() {
        editedSamsungRequestContexts.clear()
        SamsungStoreRegion.entries.forEach { region ->
            store.saveSamsungRequestContext(region, region.requestContext())
        }
    }

    private suspend fun refreshAll() {
        _templates.value = store.templates()
        EDITABLE_SOURCES.forEach { refreshSource(it) }
        val region = store.currentOppoStoreRegion()
        _oppoStoreRegion.value = region
        _oppoRequestContext.value = editedOppoRequestContexts[region] ?: store.oppoRequestContext(region)
        val samsungRegion = store.currentSamsungStoreRegion()
        _samsungStoreRegion.value = samsungRegion
        _samsungRequestContext.value =
            editedSamsungRequestContexts[samsungRegion] ?: store.samsungRequestContext(samsungRegion)
    }

    private suspend fun refreshSource(appSource: AppSource) = refreshMutex.withLock {
        val source = store.currentSource(appSource)
        val templateName = store.currentTemplateName(appSource)
        val snapshot = store.snapshot(appSource)
        val overridden = MarketProfileFields.ALL.filterTo(mutableSetOf()) { field ->
            store.isOverridden(field, appSource)
        }
        val deviceAvailable = store.canUseDevice(appSource)

        // 读取期间用户仍可输入；在所有挂起调用结束后合并最新编辑，避免旧快照覆盖输入。
        val pendingFields = edited[appSource].orEmpty().keys.toSet()
        val displayedProfile = _profiles.value[appSource]
        val profile = pendingFields.fold(snapshot.profile) { refreshed, field ->
            if (displayedProfile == null) refreshed else {
                MarketProfileFields.set(refreshed, field, MarketProfileFields.valueOf(displayedProfile, field))
            }
        }
        val origins = snapshot.fieldOrigins + pendingFields.associateWith { ProfileFieldOrigin.CUSTOM }
        val hasContextEdits = appSource == AppSource.SAMSUNG && editedSamsungRequestContexts.isNotEmpty()
        _sources.update { it + (appSource to source) }
        _templateNames.update { it + (appSource to templateName.takeIf { pendingFields.isEmpty() && !hasContextEdits }) }
        _profiles.update { it + (appSource to profile) }
        _fieldOrigins.update { it + (appSource to origins) }
        _overriddenFields.update { it + (appSource to (overridden + pendingFields)) }
        _canUseDevice.update { it + (appSource to deviceAvailable) }
    }

    companion object {
        /** 有独立协议/指纹消费方的来源；豌豆荚请求不携带设备信息，故不在编辑页展示。 */
        val EDITABLE_SOURCES = listOf(
            AppSource.XIAOMI,
            AppSource.VIVO,
            AppSource.OPPO,
            AppSource.SAMSUNG,
            AppSource.HONOR,
            AppSource.HUAWEI,
        )

        private val HONOR_ONLY_FIELDS = setOf(
            "hman", "htype", "spreadModelName", "deliveryCountry", "roamingCountry",
            "osVer", "magicVersion", "androidApiVersion", "apkVer", "apkVerName",
            "language", "dpi", "cpu", "supportGms", "terminalType",
        )
        val FIELDS = MarketProfileFields.ALL.filterNot { it in HONOR_ONLY_FIELDS }

        // Only values consumed by OppoSigner/openId are editable in OPPO mode. Region/locale
        // request values are shown separately by DeviceProfileScreen.
        val OPPO_FIELDS = listOf(
            "co",
            "device",
            "model",
            "os",
            "osV2",
            "androidVersion",
            "sdk",
            "resolution",
            "osBigVersionName",
            "buildId",
            "instanceId",
        )

        // vivo's update protocol consumes these device/request values. Xiaomi/HyperOS-only
        // version, page-resource and island fields are intentionally hidden in vivo mode.
        val VIVO_FIELDS = listOf(
            "co",
            "la",
            "lo",
            "cpuArchitecture",
            "device",
            "model",
            "os",
            "osV2",
            "androidVersion",
            "sdk",
            "resolution",
            "densityDpi",
            "densityScaleFactor",
            "marketVersion",
            "buildId",
            "instanceId",
            "hasGMSCore",
        )

        val SAMSUNG_FIELDS = listOf(
            "cpuArchitecture",
            "model",
            "sdk",
            "instanceId",
        )

        val HUAWEI_FIELDS = listOf(
            "co",
            "la",
            "lo",
            "cpuArchitecture",
            "model",
            "androidVersion",
            "buildId",
            "instanceId",
        )

        val HONOR_FIELDS = listOf(
            "hman",
            "htype",
            "spreadModelName",
            "deliveryCountry",
            "roamingCountry",
            "osVer",
            "magicVersion",
            "androidApiVersion",
            "apkVer",
            "apkVerName",
            "language",
            "dpi",
            "resolution",
            "cpu",
            "supportGms",
            "terminalType",
            "instanceId",
        )

        fun fieldsFor(source: AppSource): List<String> = when (source) {
            AppSource.OPPO -> OPPO_FIELDS
            AppSource.VIVO -> VIVO_FIELDS
            AppSource.SAMSUNG -> SAMSUNG_FIELDS
            AppSource.HONOR -> HONOR_FIELDS
            AppSource.HUAWEI -> HUAWEI_FIELDS
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.TAPTAP -> FIELDS
        }

        fun hasCustomSamsungRequestContext(
            region: SamsungStoreRegion,
            context: SamsungRequestContext,
        ): Boolean = context != region.requestContext()

        fun valueOf(profile: MarketProfile, name: String): String = MarketProfileFields.valueOf(profile, name)
    }
}
