package com.app.market.data.repository

import com.app.market.data.remote.xiaomi.XiaomiApi
import com.app.market.domain.model.profile.MarketProfile

internal data class ProfileConfigurationVersions(
    val webResVersion: String = "",
    val pageConfigVersion: String = "",
)

/** 只获取配置；资料仓库统一负责超时、串行化和配置的原子持久化。 */
internal fun interface ProfileConfigurationSource {
    suspend fun fetch(profile: MarketProfile): ProfileConfigurationVersions?
}

internal class XiaomiProfileConfigurationSource(private val api: XiaomiApi) : ProfileConfigurationSource {
    override suspend fun fetch(profile: MarketProfile): ProfileConfigurationVersions? =
        api.syncServerVersions(profile)?.let {
            ProfileConfigurationVersions(it.webResVersion, it.pageConfigVersion)
        }
}
