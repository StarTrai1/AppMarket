package com.app.market.domain.model.profile

/** 字段最终取值的来源，与数值是否恰好等于预设无关。 */
enum class ProfileFieldOrigin { DEVICE, SERVER, PRESET, PRESET_FALLBACK, CUSTOM }

/** 同一份资料及其版本字段来源，供设置页一致显示。 */
data class ProfileSnapshot(
    val profile: MarketProfile,
    val fieldOrigins: Map<String, ProfileFieldOrigin> = emptyMap(),
) {
    companion object {
        val VERSION_FIELDS = setOf(
            "marketVersion", "pageConfigVersion", "webResVersion", "hybridFrameworkVersion",
        )
    }
}

/** 本次配置请求的结果；失败保留上次成功的数据。 */
enum class ProfileSyncResult { SUCCESS, PARTIAL, FAILED }
